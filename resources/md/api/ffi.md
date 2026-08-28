`jolt.ffi` is Jolt's foreign-function interface: it loads C shared libraries and declares typed bindings over their functions, then marshals memory by hand. There is no automatic struct introspection and no garbage collection of foreign memory — you manage it, the way you would in C.

For the end-to-end guide to writing a binding — declaring the library in `deps.edn`, static vs dynamic linking, out-parameters, structs by offset, and binary data — see [Native Interop (FFI)](/docs/native-interop.html). This page is the compact API reference for `jolt.ffi` itself.

```clojure
(require '[jolt.ffi :as ffi])

(ffi/load-library {:darwin "libsqlite3.0.dylib" :linux "libsqlite3.so.0"})
(ffi/defcfn sqlite3-open "sqlite3_open" [:string :pointer] :int)

(let [pp (ffi/alloc (ffi/sizeof :pointer))]
  (sqlite3-open "x.db" pp)
  (let [db (ffi/read pp :pointer)]
    (ffi/free pp)
    db))
```

## Binding functions

- `defcfn` `name csym argtypes rettype [:blocking]` — define a foreign function `name` bound to the C symbol `csym`. `(sqlite3-open "x.db" pp)` becomes an ordinary Clojure fn you call with Jolt values.
- `foreign-fn` `csym argtypes rettype [:blocking]` — the anonymous form; returns a callable instead of `def`ing a name.
- A trailing `:blocking` marks a call that may wait — network I/O, a lock, a sleep. The call is emitted collect-safe so a thread parked inside it does not pin the garbage collector. Mark anything that can block; leave pure, fast calls unmarked.

```clojure
(ffi/defcfn c-connect "connect" [:int :pointer :int] :int :blocking)
(ffi/defcfn c-strlen  "strlen"  [:string] :size_t)
```

The trailing option may instead be a **map**, which is how you combine flags and
how you ask for the error code:

- `{:blocking true}` — the same as the `:blocking` keyword.
- `{:capture-native-error true}` — the call returns `[native-result error-code]`,
  with POSIX `errno` (or Windows `GetLastError`) read *inside* the foreign-call
  return path, before anything else can overwrite it. See
  [errno](/docs/native-interop.html) for why that matters.

```clojure
(ffi/defcfn c-open "open" [:string :int :varargs :int] :int
            {:capture-native-error true})

(c-open "/definitely/not/here" 0 0)   ; => [-1 2]
(ffi/errno-message 2)                 ; => "No such file or directory"
```

Keys and values are validated at compile time and fail closed: an unknown key
or a non-literal Boolean is an error rather than a silently ignored option.
Capture needs a scalar result to pair the code with, so it is rejected on
`:void` and on by-value struct returns.

## Struct layouts

A layout compiles a literal, data-only descriptor into the ABI metadata for a C
struct: its size, its alignment, and the byte offset of every field. Chez derives
those from the same declarations the C compiler would, so a padded or nested
struct comes out right without you counting bytes.

- `layout` `descriptor` — compile `[:struct [[field type] ...]]`. Field names are
  unqualified keywords and must be unique; a field is a fixed-size scalar, a
  nested `[:struct ...]`, or a fixed array `[:array count type]`. The descriptor
  must be a literal — it is read at compile time, not evaluated.
- `layout-size` `layout` — `sizeof` the struct, padding included.
- `layout-alignment` `layout` — its alignment requirement.
- `field-offset` `layout path` — the byte offset of a field. `path` is a keyword,
  or a vector of them to reach into a nested struct.
- `read-field` `ptr layout path` / `write-field` `ptr layout path value` — read or
  write a scalar field by name, at the offset the layout knows.

```clojure
(def date (ffi/layout [:struct [[:year :int32] [:month :uint8] [:day :uint8]]]))

(ffi/layout-size date)                ; => 8  (6 bytes, padded to int32 alignment)
(ffi/field-offset date :month)        ; => 4

(ffi/with-layout [p date]
  (ffi/write-field p date :year 2026)
  (ffi/write-field p date :month 8)
  (ffi/read-field p date :year))       ; => 2026
```

A nested struct is addressed by path:

```clojure
(def event (ffi/layout [:struct [[:tag :uint8]
                                 [:when [:struct [[:year :int32] [:month :uint8]]]]
                                 [:seq :uint16]]]))
(ffi/field-offset event [:when :year])
(ffi/read-field p event [:when :month])
```

A fixed array is `[:array count element-type]`, and elements may themselves be
arrays or structs — so a matrix is an array of arrays, and a ring buffer of
events is an array of structs. Array indices are **integer** components in a
field path, alongside the keywords:

```clojure
(def frame (ffi/layout [:struct [[:tag    :int32]
                                 [:matrix [:array 2 [:array 3 :double]]]
                                 [:name   [:array 8 :uint8]]]]))

(ffi/layout-size frame)                    ; => 64
(ffi/field-offset frame :matrix)           ; => 8   (the array's base offset)
(ffi/field-offset frame [:matrix 1 2])     ; => 48
(ffi/field-offset frame [:name 3])         ; => 59

(ffi/with-layout [p frame]
  (ffi/write-field p frame [:matrix 1 2] 3.5)
  (ffi/read-field  p frame [:matrix 1 2]))  ; => 3.5
```

```clojure
;; an array of structs, indexed then named
(def q (ffi/layout [:struct [[:events [:array 4 [:struct [[:code  :int32]
                                                          [:frame :int32]]]]]]]))
(ffi/field-offset q [:events 2 :frame])     ; => 20
```

Naming the array itself (`:matrix`, with no index) still gives its base offset,
which is what you pass to C when the function wants a pointer to the first
element. Offsets for indexed paths are computed from the ABI's element stride
rather than stored per element, so metadata stays one entry per declared array
shape — a million-element array costs no more to describe than a two-element one.

Unions, bitfields, explicit packing, and self-referential descriptors are still
not supported; lay those out by offset as before.

## Structs by value

A C function that takes or returns a struct *by value* — not a pointer to one —
is declared with `[:by-value descriptor]` in place of a type keyword, using the
same descriptor `layout` takes.

An argument is a **non-null pointer to caller-owned storage** holding the struct's
bytes; Jolt passes what it points at. An aggregate-returning function takes a
**destination pointer as its first Jolt argument**, writes the returned struct
there, and hands that pointer back. The buffer is always yours, so nothing
allocates behind your back and the ownership is visible at the call site.

The descriptor is read at compile time, so it has to be written out literally at
the call — a `def` holding one will not do, in a signature any more than in
`layout`.

```clojure
(def date (ffi/layout [:struct [[:year :int32] [:month :uint8] [:day :uint8]]]))

;; int64_t date_score(struct date d);
(ffi/defcfn score "date_score"
  [[:by-value [:struct [[:year :int32] [:month :uint8] [:day :uint8]]]]] :int64)

;; struct date make_date(int32_t y, uint8_t m, uint8_t d);
(ffi/defcfn make-date "make_date" [:int32 :uint8 :uint8]
  [:by-value [:struct [[:year :int32] [:month :uint8] [:day :uint8]]]])

(ffi/with-layout [d date]
  (ffi/write-field d date :year 2026)
  (ffi/write-field d date :month 8)
  (ffi/write-field d date :day 22)
  (score d))                           ; => 20260822

(ffi/with-layout [out date]
  (make-date out 2026 8 22)            ; writes into `out`, returns it
  (ffi/read-field out date :year))     ; => 2026
```

Nested structs, several aggregate arguments, a fixed aggregate before `:varargs`,
and `:blocking` all work. A null aggregate pointer raises `NullPointerException` before the
native call rather than faulting. Not supported: aggregate *variadic* arguments, an
aggregate return combined with `:varargs`, and aggregates in `foreign-callable` /
`export!` — those are rejected at compile time.

## Calling back into Jolt

- `foreign-callable` `f argtypes rettype [:collect-safe]` — wrap a Jolt fn `f` as a C-callable function pointer: the inverse of `defcfn`, so C can call back *into* Jolt (a `qsort` comparator, a GTK signal handler, any C API that takes a callback). The args C passes arrive as Jolt values; the Jolt return is marshaled back per `rettype`. The callback stays live until `free-callable` releases it. Pass a trailing `:collect-safe` when the callback may be entered from a thread the runtime never activated: a thread parked in a `:blocking` foreign call (e.g. a GUI main loop), or any OS thread C created for itself, such as a worker pool or a completion handler delivered on a background queue. Without it, entering the callable from such a thread is undefined behaviour, and in practice ends the process rather than raising something you can catch.
- `free-callable` `ptr` — release a callable built by `foreign-callable`; returns `nil`.
- `export!` `name f argtypes rettype [:collect-safe]` — publish `f` as a C-callable entry point under `name`, for `jolt build --library`. An embedder resolves it via `jolt_lookup("name")` after `jolt_library_init`. The argtypes/rettype keywords are the same as `defcfn`. `:collect-safe` means what it does for `foreign-callable`, and the thread that matters here is the one that ran `jolt_library_init`: without it, an embedder calling the entry point from any other OS thread is undefined behaviour. See [Native Interop](/docs/native-interop.html) for the rest of the embedding rules.

```clojure
;; qsort comparator callable into libc
(def cmp (ffi/foreign-callable
           (fn [pa pb]
             (let [a (ffi/read pa :int) b (ffi/read pb :int)]
               (cond (< a b) -1 (> a b) 1 :else 0)))
           [:pointer :pointer] :int))
(c-qsort arr n (ffi/sizeof :int) cmp)
(ffi/free-callable cmp)
```

## Types

Argument and return types are keywords:

- `:int` `:uint` — `int` / `unsigned`
- `:long` `:ulong` — `long` / `unsigned long`
- `:int64` `:uint64` — 64-bit integer
- `:size_t` `:ssize_t` — `size_t` / `ssize_t`
- `:iptr` `:uptr` — pointer-sized integer (handy for `NULL` sentinels)
- `:double` `:float` — `double` / `float`
- `:char` — `char` (a code point)
- `:uint8` (alias `:u8`, `:byte`) — `unsigned char`, number 0–255
- `:pointer` (alias `:void*`) — any pointer (a machine address)
- `:string` — `char *`, marshaled both ways (UTF-8 both directions)
- `:void` — return ignored (`nil`)

## Memory and libraries

Foreign memory is manual — allocate, use, free. There is no finalizer.

- `load-library` `[spec]` — load a shared object. With no spec, binds symbols already in the running process (libc, POSIX). With a spec, a per-OS map (`{:darwin "libsqlite3.0.dylib" :linux "libsqlite3.so.0"}`) or a bare path. Inside a `deps.edn` project you usually declare natives under `:jolt/native` instead (see the guide).
- `loaded?` `name` — was a library loaded?

**Symbol resolution.** A library loaded through `load-library` or a `:jolt/native`
declaration is loaded privately and registered: a `defcfn` resolves its C symbol
against the registered libraries first (in load order), and only falls back to
the process-global namespace when none of them export it (libc calls, `:process`
natives). The OS's own libraries can therefore never shadow a declared native,
even when they export the same symbol names — macOS ships BoringSSL system-wide
with the full `EVP_*` set, and before this rule an OpenSSL-backed library's
digest calls could silently land there and abort the process.
- `alloc` `nbytes` — allocate `nbytes`; returns a pointer (address). You must `free` it.
- `free` `ptr` — release memory from `alloc` / `string->ptr`.
- `sizeof` `type` — byte size of a type, for laying out structs and out-parameters.
- `read` `ptr type [offset]` — read a typed value at `ptr` (+ optional byte offset).
- `write` `ptr type offset value` — write a typed value at `ptr + offset`.
- `read-array` `ptr n` — `n` bytes → `byte-array` (binary-faithful, no encoding).
- `write-array` `ptr arr` — `byte-array` → memory.
- `read-bytes` `ptr n` — `n` bytes → string (UTF-8).
- `write-bytes` `ptr s` — a string's UTF-8 bytes → memory.
- `string->ptr` `s` — allocate a NUL-terminated C string from `s` (free it yourself).
- `ptr->string` `ptr` — read a NUL-terminated C string back.
- `null` — the null pointer; `null?` `p` — the test.

### Scoped allocation

Every `alloc` needs a matching `free` on every path out, including the one an
exception takes. These macros bind a pointer for the body and release it exactly
once when the body ends, however it ends.

- `with-alloc` `[ptr byte-count] & body` — allocate `byte-count` bytes.
- `with-out` `[ptr scalar-type] & body` — allocate one scalar, for an out-parameter.
- `with-layout` `[ptr layout] & body` — allocate one instance of a layout.
- `with-c-string` `[ptr value] & body` — a NUL-terminated UTF-8 copy of `value`.
- `with-c-string-array` `[ptr count] values & body` — an array of `count` C
  strings. `values` is evaluated once, and if a conversion fails partway the
  strings already built are freed before the error propagates.

Each returns the body's value. The pointers are valid only inside the body — do
not let one escape, since it is freed on the way out.

```clojure
(defn open [path]
  (ffi/with-out [pp :pointer]                  ; freed on both paths
    (let [rc (sqlite3-open path pp)]
      (when-not (= rc SQLITE-OK)
        (throw (ex-info (str "sqlite open failed: " path) {:rc rc})))
      (ffi/read pp :pointer))))

(ffi/with-c-string [s "SELECT 1"]
  (sqlite3-prepare db s -1 stmt ffi/null))
```

These own only what they allocate. A handle C hands you — a `FILE*`, a
connection, anything with its own `close` — is still yours to release.
