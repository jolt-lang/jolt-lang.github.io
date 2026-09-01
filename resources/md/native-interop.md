Jolt has no JVM, so it has no `java.*` to lean on for talking to the outside world. Instead it ships a foreign-function interface (`jolt.ffi`) that binds C shared libraries directly. This is how the real libraries work: the [db](https://github.com/jolt-lang/db) library binds `libsqlite3`/`libpq`, and the [http-client](https://github.com/jolt-lang/http-client) binds POSIX sockets, OpenSSL, and zlib. This page is the guide for writing your own.

The FFI is a thin, explicit layer: you declare the library, bind each C function with its argument and return types, and marshal memory by hand. There is no automatic struct introspection and no garbage collection of foreign memory; you manage it, the way you would in C.

## Declaring the native library

A library names the shared objects it needs in its `deps.edn` under `:jolt/native`. Jolt loads them at startup, before any namespace is required, so the bindings resolve. From the db library:

```clojure
:jolt/native [{:name "sqlite3"
               :darwin ["libsqlite3.0.dylib" "libsqlite3.dylib"]
               :linux  ["libsqlite3.so.0" "libsqlite3.so"]}
              {:name "pq"
               :darwin ["libpq.5.dylib" "libpq.dylib"]
               :linux  ["libpq.so.5" "libpq.so"]
               :optional true}]
```

Each entry is a map:

- `:name`: a human-readable label, used only in the "library not found" error.
- `:darwin` / `:linux` / `:windows`: per-platform candidates, a string or a vector tried in order. Jolt picks the key for the host OS (`os.name`) and loads the first candidate that resolves. List the versioned name first (`libsqlite3.so.0`), the bare name as a fallback.

    A candidate is a **name** or a **path**, and the difference is a separator — the same line `dlopen` draws. `libsqlite3.so.0` is searched for on the loader path; `native/libfoo.so` is a path, resolved **relative to the project** (the directory holding `deps.edn`), not to wherever you happened to run `jolt` from. Absolute paths are used as given.
- `:optional true`: a missing library is skipped instead of erroring. Use it for feature-gated drivers (the db library makes Postgres optional). Check `(jolt.ffi/loaded? "libpq.so.5")` before using such a binding.
- `:process true`: bind symbols already in the running process (libc, POSIX) rather than loading a file. The http-client uses this for `socket`/`connect`/`send`/`recv`:

### Shipping your own C

A shim you compile yourself is a path candidate, so it can live beside the source it is built from:

```
my-project/
  deps.edn
  native/shim.c              ← the source
  native/libshim.dylib       ← the artifact, named by :jolt/native
```

The artifact is a build product, so a fresh checkout does not have one. Put the compile step in a [task](/docs/tools-deps.html#tasks) and it travels with the project, no makefile or shell script beside it:

```clojure
{:jolt/native [{:name "shim"
                :darwin ["native/libshim.dylib"]
                :linux  ["native/libshim.so"]}]

 :tasks
 {:requires ([babashka.fs :as fs])
  :init (def lib (str "native/libshim."
                      (if (re-find #"^Mac" (System/getProperty "os.name")) "dylib" "so")))

  native
  {:doc "compile native/shim.c"
   :task (when (seq (fs/modified-since lib ["native/shim.c"]))
           (shell "cc" "-O2" "-fPIC" "-shared" "native/shim.c" "-o" lib)
           (println "built:" lib))}}}
```

```bash
jolt native      # compiles it
jolt run -m app  # loads it
```

Running a **task** does not require the libraries to be present — it warns and carries on, because the task may be the very thing that builds one. Every other command still refuses to start without a required library, so a missing artifact never reaches your code as a confusing binding error.

Pass the compiler its arguments **separately**, as above, rather than as one command string: no shell sees them, so an ELF rpath keeps its literal `$ORIGIN` (a single string would have the shell expand it away).

```clojure
:jolt/native [{:name "libc (POSIX sockets)" :process true}
              {:name "z"   :darwin ["libz.dylib"]      :linux ["libz.so.1" "libz.so"]}
              {:name "ssl" :darwin ["libssl.dylib"]     :linux ["libssl.so.3" "libssl.so"]}]
```

If you're binding outside a `deps.edn` project, call `(jolt.ffi/load-library "libsqlite3.dylib")` (or `(jolt.ffi/load-library)` with no argument for the process's own symbols) before the first call.

### Which library serves a symbol

Declared natives are loaded privately and registered; a `defcfn` looks its C
symbol up in the registered libraries first (in load order) and falls back to
the process-global namespace only when none of them export it. Two properties
follow. A system library that happens to export the same names as your declared
native (macOS ships BoringSSL system-wide with OpenSSL's `EVP_*` symbol set)
can never intercept your bindings. And a library may bind symbols that another
*declared* dependency's native provides (glimmer-gl binds GTK symbols declared
by glimmer-gtk), since every declared native participates in the lookup. Plain
libc symbols need no declaration; they resolve through the process fallback.

### Static vs dynamic linking in a built binary

When you `run`/`repl`, the candidates above are loaded dynamically; the `.so`/`.dylib` has to be present on the machine. When you `jolt build`, you can instead **link the library statically into the binary**, so the executable calls the C code with no shared object present at runtime. Add a `:static` archive to the spec:

```clojure
:jolt/native [{:name "sqlite3"
               :static {:archive "/opt/homebrew/lib/libsqlite3.a"}  ; baked into the binary
               :darwin ["libsqlite3.0.dylib" "libsqlite3.dylib"]     ; still used by run/repl
               :linux  ["libsqlite3.so.0" "libsqlite3.so"]}]
```

A spec with `:static` is **statically linked by default** on `jolt build`. `:static {:archive PATH}` force-loads the whole `.a` and is the reliable cross-platform form; `:static {:lib NAME :libdir DIR}` links `-lNAME` (with a `-Bstatic` preference on Linux, where an archive path is safer on macOS). Keep the `:darwin`/`:linux` candidates too; `run`/`repl` have no static binary and still load the shared object, as does a build passed `--dynamic` (or `:jolt/build {:dynamic-natives true}`), which keeps the runtime-load behavior for every lib.

Static linking needs a C compiler (`cc`) on `PATH` at build time; the distributed `jolt` bundles the Chez kernel and re-links its launcher with the archive baked in, so no external Chez is required, just `cc`. The *produced* binary needs nothing: drop it on a machine and it runs, calling the statically-linked C code, with only the standard system libraries present. (Like Go's cgo or Rust, building against a C library needs a C toolchain; running the result does not.)

## Binding a function

`defcfn` defines a named binding; `foreign-fn` produces an anonymous one. The shape is the same:

```clojure
(require '[jolt.ffi :as ffi])

(ffi/defcfn name "c_symbol" [arg-types...] return-type [:blocking])
```

From the db library's SQLite bindings:

```clojure
(ffi/defcfn sqlite3-open         "sqlite3_open"         [:string :pointer] :int)
(ffi/defcfn sqlite3-prepare      "sqlite3_prepare_v2"   [:pointer :string :int :pointer :pointer] :int)
(ffi/defcfn sqlite3-step         "sqlite3_step"         [:pointer] :int)
(ffi/defcfn sqlite3-column-text  "sqlite3_column_text"  [:pointer :int] :string)
(ffi/defcfn sqlite3-column-int64 "sqlite3_column_int64" [:pointer :int] :int64)
(ffi/defcfn sqlite3-bind-text    "sqlite3_bind_text"    [:pointer :int :string :int :iptr] :int)
```

Each defined function is an ordinary Clojure fn you call with Jolt values; arguments and the return value are marshaled according to the declared types.

### `:blocking`

A trailing `:blocking` marks a call that may wait (network I/O, a lock, a sleep). The http-client marks every socket call:

```clojure
(ffi/defcfn c-connect "connect" [:int :pointer :int] :int :blocking)
(ffi/defcfn c-recv    "recv"    [:int :pointer :size_t :int] :ssize_t :blocking)
```

This matters for correctness as much as speed: without `:blocking`, a thread parked inside a foreign call pins the garbage collector for every thread. With it, Jolt releases the collector while the call waits. Mark anything that can block; leave pure, fast calls unmarked.

### `:&` (variadic C functions)

A `:&` marker inside the argtype vector declares a **variadic** C function (`fcntl`, `ioctl`, `open`) and marks the boundary: the types before it are the fixed (named) parameters, the types after it are the concrete variadic arguments the binding always passes. `:varargs` is Jolt's older spelling of the same marker and still works. `fcntl` is `int fcntl(int fd, int cmd, ...)`, so a binding that passes flags to `F_SETFL` is:

```clojure
(ffi/defcfn c-fcntl "fcntl" [:int :int :& :int] :int)
(c-fcntl fd F-SETFL O-NONBLOCK)
```

This is not optional decoration. On Apple arm64 variadic arguments are passed on the stack, not in registers; a fixed-arity binding to a variadic function compiles, runs, and silently hands the callee garbage for every argument after the named ones. The marker makes Jolt emit the variadic calling convention so the arguments land where the callee's `va_list` reads them.

Two shapes are rejected at compile time with an error naming the rule: the marker first (C requires at least one named parameter) and the marker last — babashka.ffi's bare `:&`, where each call infers its own tail, which a compiled `foreign-procedure` cannot do because its types are fixed before any call. The tail belongs to the binding: bind one signature per tail shape. The marker does not combine with `:blocking`. A call that passes **no** variadic arguments (`fcntl(fd, F_GETFL)`) may use a plain fixed-arity binding: named arguments travel identically in both conventions.

## Types at the boundary

The argument and return types are keywords. The full set:

| Keyword | C type | Jolt value |
|---|---|---|
| `:int` `:uint` | `int` / `unsigned` | number |
| `:long` `:ulong` | `long` / `unsigned long` | number |
| `:int64` `:uint64` | 64-bit integer | number |
| `:size_t` `:ssize_t` | `size_t` / `ssize_t` | number |
| `:iptr` `:uptr` | pointer-sized integer | number (handy for `NULL` sentinels) |
| `:double` `:float` | `double` / `float` | number |
| `:char` | `char` | number (code point) |
| `:uint8` (`:u8`, `:byte`) | `unsigned char` | number 0–255 |
| `:pointer` (`:void*`) | any pointer | number (machine address) |
| `:string` | `char *` | string (marshaled both ways) |
| `:void` | `void` | return ignored (nil) |

A `:string` argument is copied to a NUL-terminated C string for the call; a `:string` return reads a NUL-terminated C string back, decoding UTF-8 (falling back to Latin-1). Pointers are plain integers; you pass them around, offset them, and hand them back to C.

## Memory and strings

Foreign memory is manual. Allocate, use, free; there is no finalizer:

```clojure
(ffi/alloc arena n)         ; -> pointer; the arena releases it (see Arenas)
(ffi/alloc nbytes)          ; -> pointer (address); you must free it
(ffi/free ptr)              ; release an arena-less allocation
(ffi/sizeof :pointer)       ; size of a type, for laying out structs/out-params

(ffi/read  ptr type [offset])      ; read a typed value at ptr (+ optional byte offset)
(ffi/write ptr type value [offset]) ; write a typed value — VALUE before offset

(ffi/string->ptr s)         ; alloc a C string from s (free it yourself)
(ffi/ptr->string ptr)       ; read a NUL-terminated C string back

(ffi/read-array ptr n)      ; n bytes -> byte-array (binary-faithful)
(ffi/write-array ptr arr)   ; byte-array -> memory
(ffi/read-bytes ptr n)      ; n bytes -> string (UTF-8)
(ffi/write-bytes ptr s)     ; string's UTF-8 bytes -> memory

(ffi/null)  (ffi/null? p)   ; the null pointer, and the test
(ffi/loaded? name)          ; was a library loaded?
```

Rather than pairing each `alloc` with a `free` on every path out (including the
one an exception takes), bind it for a scope:

```clojure
(ffi/with-alloc [p 64] ...)             ; freed however the body ends
(ffi/with-out [pp :pointer] ...)        ; one scalar, for an out-parameter
(ffi/with-layout [p a-layout] ...)      ; one instance of a layout
(ffi/with-c-string [s "SELECT 1"] ...)  ; a NUL-terminated UTF-8 copy
(ffi/with-c-string-array [argv 2] ["a" "b"] ...)
```

Each returns the body's value, and frees exactly what it allocated; a handle C
gave you is still yours to close. The pointer is valid only inside the body.

### Out-parameters

C functions that "return" through a pointer argument are the common case. Allocate a cell, pass its address, read it back. From the db library opening a connection (`sqlite3_open(path, &db)`):

```clojure
(defn open [path]
  (let [pp (ffi/alloc (ffi/sizeof :pointer))]   ; space for a db*
    (try
      (let [rc (sqlite3-open path pp)            ; C writes the handle into pp
            db (ffi/read pp :pointer)]           ; read it out
        (when-not (= rc SQLITE-OK)
          (throw (ex-info (str "sqlite open failed: " path) {:rc rc})))
        db)
      (finally (ffi/free pp)))))
```

or, with the allocation scoped:

```clojure
(defn open [path]
  (ffi/with-out [pp :pointer]
    (let [rc (sqlite3-open path pp)]
      (when-not (= rc SQLITE-OK)
        (throw (ex-info (str "sqlite open failed: " path) {:rc rc})))
      (ffi/read pp :pointer))))
```

### Structs by layout

Declare the struct and let Chez work out the ABI (size, alignment and every
field offset) instead of counting bytes:

```clojure
(def date (ffi/layout [:struct [[:year :int32] [:month :uint8] [:day :uint8]]]))

(ffi/layout-size date)          ; => 8, the padding included
(ffi/field-offset date :month)  ; => 4

(ffi/with-layout [p date]                  ; allocated and freed for the body
  (ffi/write-field p date :year 2026)
  (ffi/read-field p date :year))
```

Fields nest, and a nested field is reached by path:

```clojure
(def event (ffi/layout [:struct [[:tag :uint8]
                                 [:when [:struct [[:year :int32] [:month :uint8]]]]
                                 [:seq :uint16]]]))
(ffi/read-field p event [:when :year])
```

A struct passed or returned **by value** uses the same descriptor, wrapped in
`[:by-value ...]`. An argument is a pointer to storage you own; an
aggregate-returning call takes a destination pointer first, writes the result
there and returns it:

```clojure
;; the descriptor is read at compile time, so write it out at the call
(ffi/defcfn score "date_score"
  [[:by-value [:struct [[:year :int32] [:month :uint8] [:day :uint8]]]]] :int64)

(ffi/defcfn make-date "make_date" [:int32 :uint8 :uint8]
  [:by-value [:struct [[:year :int32] [:month :uint8] [:day :uint8]]]])
```

A **fixed array** field is `[:array element-type count]` — babashka.ffi's order — and elements may
themselves be arrays or structs. Indices are integer components in the path,
mixed in with the keywords:

```clojure
(def frame (ffi/layout [:struct [[:tag    :int32]
                                 [:matrix [:array [:array :double 3] 2]]
                                 [:events [:array [:struct [[:code  :int32]
                                                            [:frame :int32]]] 4]]]]))

(ffi/field-offset frame :matrix)          ; the array's base offset — the pointer C wants
(ffi/field-offset frame [:matrix 1 2])    ; one element
(ffi/field-offset frame [:events 2 :frame])
```

Indexed offsets come from the ABI's element stride rather than a stored entry
per element, so a large array is no more expensive to describe than a small one.

Unions, bitfields and explicit packing are still not modelled; for those, and
for any struct whose shape you cannot state literally, lay it out by offset as
below.

### Structs by offset

Written out as byte offsets, using `ffi/read`/`ffi/write` directly. The http-client's zlib binding lays out `z_stream` by hand:

```clojure
(def ^:private ZS 112)            ; sizeof(z_stream), LP64
(def ^:private O-next-in 0)
(def ^:private O-avail-in 8)
(def ^:private O-next-out 24)
(def ^:private O-avail-out 32)

(let [strm (ffi/alloc ZS)]                        ; ffi/alloc zeroes the block
  (ffi/write strm :pointer src-buf O-next-in)
  (ffi/write strm :uint    n       O-avail-in)
  ...)
```

Offsets and sizes are platform-specific. The http-client keeps a per-OS offset where macOS and Linux disagree:

```clojure
(def ^:private macos?
  (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))
(def ^:private O-ai-addr (if macos? 32 24))      ; addrinfo.ai_addr
```

### Binary data

For bytes that aren't text, use the array helpers; they don't touch encoding. The http-client moves ciphertext through OpenSSL's in-memory BIOs this way:

```clojure
(let [buf (ffi/alloc n)
      got (c-BIO-read wbio buf n)]
  (when (pos? got) (net/send-bytes sock (ffi/read-array buf got)))
  (ffi/free buf))
```

## errno

A syscall that fails reports *how* through `errno`, and `errno` is not a
global: every modern libc keeps a per-thread slot behind a function
(`__error` on macOS, `__errno_location` on Linux, `_errno` on Windows).
`jolt.ffi/errno` reads the calling thread's slot through the right one, so
it is correct under threads, and under fibers, whose syscall and errno read
both run on the fiber's carrier thread. `errno-message` renders a code (or
the current errno) through `strerror`:

```clojure
(ffi/defcfn c-open "open" [:string :int :& :int] :int)

(let [fd (c-open path 0 0)]
  (when (neg? fd)
    (throw (ex-info (str "open " path ": " (ffi/errno-message))
                    {:path path :errno (ffi/errno)}))))
```

Read it **immediately** after the failing call. Anything that can enter the
runtime between the call and the read (an allocation, a park, another FFI
call) may make a syscall of its own and overwrite the slot.

When "immediately" isn't good enough — a `:blocking` call, or anything where
cleanup or the collector can run before you get control back — bind the function
with `{:capture-native-error true}` and the error code is read *inside* the
foreign-call return path, where nothing can get between:

```clojure
(ffi/defcfn c-open "open" [:string :int :& :int] :int
            {:capture-native-error true})

(let [[fd err] (c-open path 0 0)]
  (when (neg? fd)
    (throw (ex-info (str "open " path ": " (ffi/errno-message err))
                    {:path path :errno err}))))
```

The call returns `[native-result error-code]` instead of the bare result. It
composes with blocking — `{:blocking true :capture-native-error true}` — and on
Windows the captured code is `GetLastError`. Capture needs a scalar result to
pair the code with, so it is rejected on `:void` and on by-value struct returns.

## Checklist for a binding

- Declare the library in `deps.edn` `:jolt/native` with per-OS candidates; mark optional drivers `:optional`, process symbols `:process`. Add a `:static` archive to link it into a built binary (keep the dynamic candidates for `run`/`repl`).
- Bind each C function with `defcfn`, exact argument/return types, and `:blocking` on anything that waits. Add `{:capture-native-error true}` where you need `errno` and can't guarantee an immediate read.
- Free every `ffi/alloc` and `ffi/string->ptr`; wrap allocation in `try`/`finally`. Leaked foreign memory is never reclaimed.
- Check C return codes and null pointers explicitly, and `throw` an `ex-info` on failure; `(ffi/errno-message)` makes the message say what went wrong.
- Keep struct offsets and type widths LP64-correct, and branch on `os.name` where macOS and Linux differ.

## Calling into Jolt from C

`bin/jolt build --library` (see the README) produces a shared object whose
entry points you reach through a C ABI instead of JVM-style interop. The Jolt
side uses `jolt.ffi/export!`; the C side uses `jolt_library_init` +
`jolt_lookup`. This is the inverse of `foreign-fn`: `foreign-fn` calls *out* of
Jolt into C; `export!` lets C call *in*.

```clojure
(defn add [x y] (+ x y))
(jolt.ffi/export! "add" add [:int :int] :int)
```

The argtype/rettype keywords are the same set `foreign-fn`/`ffi-type->chez`
accepts: `:int :uint :long :ulong :int64 :uint64 :size_t :ssize_t :iptr :uptr
:double :float :pointer` (alias `:void*`) `:string :void :uint8` (aliases
`:u8`/`:byte`) `:char`. A `:pointer`/`:void*` returns an opaque address you pass
back unchanged; `:string` copies a C string in/out.

```c
typedef int (*init_fn)(int, char**);
typedef void* (*lookup_fn)(const char*);
typedef int (*add_fn)(int, int);

void* h = dlopen("./libadd.so", RTLD_NOW | RTLD_LOCAL);
((init_fn)dlsym(h, "jolt_library_init"))(0, NULL);
add_fn add = (add_fn)((lookup_fn)dlsym(h, "jolt_lookup"))("add");
add(2, 3);                          /* => 5 */
```

Things to keep in mind across the boundary:

- **Single thread.** The library carries its own GC. Call `jolt_library_init`
  exactly once, and make `jolt_lookup` and every exported-function call from that
  same thread; the callbacks are not registered as collect-safe, so entering
  them from another OS thread the runtime never activated is undefined behavior.
  If the embedder must call in from a thread it started itself, build that export
  with a trailing `:collect-safe` (`export!` accepts it; same rule as
  [`foreign-callable`](/docs/api/ffi.html)) so the entry activates the calling
  thread on the way in. Call `jolt_library_shutdown` to tear it down.
- **Pointer lifetimes.** A value returned as `:pointer`/`:void*` is not GC-tracked
  by the caller; if Jolt hands back a pointer into managed memory you must keep
  it alive on the Jolt side (e.g. hold it in a top-level ref) for as long as C
  uses it.
- **Linux needs a PIC kernel.** The link folds Chez's `libkernel.a` into the
  shared object. On Linux that archive must be position-independent; a kernel
  built without `-fPIC` fails the `-shared` link with a relocation error. macOS is
  always PIC. Build Chez from source with a PIC kernel if your distro's isn't.
