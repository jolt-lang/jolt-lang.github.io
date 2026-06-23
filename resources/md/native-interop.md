Jolt has no JVM, so it has no `java.*` to lean on for talking to the outside world. Instead it ships a foreign-function interface (`jolt.ffi`) that binds C shared libraries directly. This is how the real libraries work: the [db](https://github.com/jolt-lang/db) library binds `libsqlite3`/`libpq`, and the [http-client](https://github.com/jolt-lang/http-client) binds POSIX sockets, OpenSSL, and zlib. This page is the guide for writing your own.

The FFI is a thin, explicit layer: you declare the library, bind each C function with its argument and return types, and marshal memory by hand. There is no automatic struct introspection and no garbage collection of foreign memory — you manage it, the way you would in C.

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

- `:name` — a human-readable label, used only in the "library not found" error.
- `:darwin` / `:linux` / `:windows` — per-platform candidates, a string or a vector tried in order. Jolt picks the key for the host OS (`os.name`) and loads the first candidate that resolves. List the versioned name first (`libsqlite3.so.0`), the bare name as a fallback.
- `:optional true` — a missing library is skipped instead of erroring. Use it for feature-gated drivers (the db library makes Postgres optional). Check `(jolt.ffi/loaded? "libpq.so.5")` before using such a binding.
- `:process true` — bind symbols already in the running process (libc, POSIX) rather than loading a file. The http-client uses this for `socket`/`connect`/`send`/`recv`:

```clojure
:jolt/native [{:name "libc (POSIX sockets)" :process true}
              {:name "z"   :darwin ["libz.dylib"]      :linux ["libz.so.1" "libz.so"]}
              {:name "ssl" :darwin ["libssl.dylib"]     :linux ["libssl.so.3" "libssl.so"]}]
```

If you're binding outside a `deps.edn` project, call `(jolt.ffi/load-library "libsqlite3.dylib")` (or `(jolt.ffi/load-library)` with no argument for the process's own symbols) before the first call.

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

A trailing `:blocking` marks a call that may wait — network I/O, a lock, a sleep. The http-client marks every socket call:

```clojure
(ffi/defcfn c-connect "connect" [:int :pointer :int] :int :blocking)
(ffi/defcfn c-recv    "recv"    [:int :pointer :size_t :int] :ssize_t :blocking)
```

This matters for correctness, not just speed: without `:blocking`, a thread parked inside a foreign call pins the garbage collector for every thread. With it, Jolt releases the collector while the call waits. Mark anything that can block; leave pure, fast calls unmarked.

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
| `:string` | `char *` | string — marshaled both ways |
| `:void` | `void` | return ignored (nil) |

A `:string` argument is copied to a NUL-terminated C string for the call; a `:string` return reads a NUL-terminated C string back, decoding UTF-8 (falling back to Latin-1). Pointers are plain integers — you pass them around, offset them, and hand them back to C.

## Memory and strings

Foreign memory is manual. Allocate, use, free — there is no finalizer:

```clojure
(ffi/alloc nbytes)          ; -> pointer (address); you must free it
(ffi/free ptr)              ; release it
(ffi/sizeof :pointer)       ; size of a type, for laying out structs/out-params

(ffi/read  ptr type [offset])      ; read a typed value at ptr (+ optional byte offset)
(ffi/write ptr type offset value)  ; write a typed value at ptr + offset

(ffi/string->ptr s)         ; alloc a C string from s (free it yourself)
(ffi/ptr->string ptr)       ; read a NUL-terminated C string back

(ffi/read-array ptr n)      ; n bytes -> byte-array (binary-faithful)
(ffi/write-array ptr arr)   ; byte-array -> memory
(ffi/read-bytes ptr n)      ; n bytes -> string (UTF-8)
(ffi/write-bytes ptr s)     ; string's UTF-8 bytes -> memory

(ffi/null)  (ffi/null? p)   ; the null pointer, and the test
(ffi/loaded? name)          ; was a library loaded?
```

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

### Structs by offset

There is no struct introspection — you write the layout out as byte offsets and use `ffi/read`/`ffi/write`. The http-client's zlib binding lays out `z_stream` by hand:

```clojure
(def ^:private ZS 112)            ; sizeof(z_stream), LP64
(def ^:private O-next-in 0)
(def ^:private O-avail-in 8)
(def ^:private O-next-out 24)
(def ^:private O-avail-out 32)

(let [strm (ffi/alloc ZS)]
  (dotimes [i ZS] (ffi/write strm :uint8 i 0))   ; zero the struct
  (ffi/write strm :pointer O-next-in  src-buf)
  (ffi/write strm :uint    O-avail-in n)
  ...)
```

Offsets and sizes are platform-specific. The http-client keeps a per-OS offset where macOS and Linux disagree:

```clojure
(def ^:private macos?
  (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))
(def ^:private O-ai-addr (if macos? 32 24))      ; addrinfo.ai_addr
```

### Binary data

For bytes that aren't text, use the array helpers — they don't touch encoding. The http-client moves ciphertext through OpenSSL's in-memory BIOs this way:

```clojure
(let [buf (ffi/alloc n)
      got (c-BIO-read wbio buf n)]
  (when (pos? got) (net/send-bytes sock (ffi/read-array buf got)))
  (ffi/free buf))
```

## Checklist for a binding

- Declare the library in `deps.edn` `:jolt/native` with per-OS candidates; mark optional drivers `:optional`, process symbols `:process`.
- Bind each C function with `defcfn`, exact argument/return types, and `:blocking` on anything that waits.
- Free every `ffi/alloc` and `ffi/string->ptr` — wrap allocation in `try`/`finally`. Leaked foreign memory is never reclaimed.
- Check C return codes and null pointers explicitly, and `throw` an `ex-info` on failure.
- Keep struct offsets and type widths LP64-correct, and branch on `os.name` where macOS and Linux differ.
