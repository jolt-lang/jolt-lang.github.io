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

## Calling back into Jolt

- `foreign-callable` `f argtypes rettype [:collect-safe]` — wrap a Jolt fn `f` as a C-callable function pointer: the inverse of `defcfn`, so C can call back *into* Jolt (a `qsort` comparator, a GTK signal handler, any C API that takes a callback). The args C passes arrive as Jolt values; the Jolt return is marshaled back per `rettype`. The callback stays live until `free-callable` releases it. Pass a trailing `:collect-safe` when C invokes the callback from a thread parked in a `:blocking` foreign call (e.g. a GUI main loop).
- `free-callable` `ptr` — release a callable built by `foreign-callable`; returns `nil`.
- `export!` `name f argtypes rettype [:collect-safe]` — publish `f` as a C-callable entry point under `name`, for `joltc build --library`. An embedder resolves it via `jolt_lookup("name")` after `jolt_library_init`. The argtypes/rettype keywords are the same as `defcfn`.

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
