`jolt.ffi` is Jolt's foreign-function interface: it loads C shared libraries and declares typed bindings over their functions, then marshals memory by hand. There is no automatic struct introspection and no garbage collection of foreign memory — you manage it, the way you would in C.

For the end-to-end guide to writing a binding — declaring the library in `deps.edn`, static vs dynamic linking, out-parameters, structs by offset, and binary data — see [Native Interop (FFI)](/docs/native-interop.html). This page is the compact API reference for `jolt.ffi` itself.

```clojure
(require '[jolt.ffi :as ffi])

(ffi/load-library {:darwin "libsqlite3.0.dylib" :linux "libsqlite3.so.0"})
(ffi/defcfn sqlite3-open "sqlite3_open" [:string :pointer] :int)

(with-open [arena (ffi/confined-arena)]
  (let [pp (ffi/alloc arena :pointer)]
    (sqlite3-open "x.db" pp)
    (ffi/read pp :pointer)))
```

Every allocation belongs to an **arena**, which owns its lifetime, or to you, to
`free` by hand. `jolt.ffi` matches [`babashka.ffi`](https://github.com/babashka/ffi)
name for name and semantics for semantics wherever one substrate can match the
other — see [babashka.ffi compatibility](#babashkaffi-compatibility) for the
short list of places they still differ, and why.

## Binding functions

- `defcfn` `name csym argtypes rettype [:blocking]` — define a foreign function `name` bound to the C symbol `csym`. `(sqlite3-open "x.db" pp)` becomes an ordinary Clojure fn you call with Jolt values.
- `foreign-fn` `csym argtypes rettype [:blocking]` — the anonymous form; returns a callable instead of `def`ing a name.
- `cfn` — babashka.ffi's name for `foreign-fn`, and the same macro.
- `defcfn` takes an optional **docstring** and **attribute map** before the C
  symbol, both of which land on the var, so a namespace of bindings documents
  itself and `^:private` works.
- `defcfn` also has a **wrapper form**: a symbol after the return type names the
  raw binding, and the rest is an ordinary `fn` tail — the shape for an
  out-parameter or an error code callers should never see.
- A trailing `:blocking` marks a call that may wait — network I/O, a lock, a sleep, a UI run loop you never return from. The call is emitted collect-safe so a thread parked inside it does not pin the garbage collector. An unmarked call stops collection process-wide for as long as it runs: other threads halt at their next allocation, far from the call responsible, while the parked thread itself looks healthy. Mark anything that can block; leave pure, fast calls unmarked.

```clojure
(ffi/defcfn c-connect "connect" [:int :pointer :int] :int :blocking)
(ffi/defcfn c-strlen  "strlen"  [:string] :size_t)

(ffi/defcfn sqlite3-open
  "Opens the database at path, storing the handle in the out-parameter."
  {:private true}
  "sqlite3_open" [:string :pointer] :int)

;; the wrapper form: the raw binding is local, the var is the friendly fn
(ffi/defcfn open-db
  "sqlite3_open_v2" [:string :pointer :int :string] :int
  open-native
  [filename flags]
  (with-open [arena (ffi/confined-arena)]
    (let [pdb  (ffi/alloc arena :pointer)
          code (open-native filename pdb flags nil)]
      (if (zero? code)
        (ffi/read pdb :pointer)
        (throw (ex-info "open failed" {:code code}))))))
```

**Variadic C functions.** `:&` inside the argtype vector marks the boundary: the
types before it are the fixed (named) parameters, the types after it are the
tail this binding passes. `:varargs` is Jolt's older spelling of the same marker.
The call is emitted with the variadic calling convention, which Apple arm64
requires — it passes variadic arguments on the stack, and a fixed-arity binding
silently corrupts them. C's default argument promotions still apply after the
marker, so pass anything narrower than `int` as `:int` (and `float` as
`:double`).

```clojure
(ffi/defcfn c-fcntl "fcntl" [:int :int :& :int] :int)
```

The tail belongs to the *binding*, not to the call: bind one signature per tail
shape. A bare `:&` with nothing after it — babashka.ffi's per-call tail
inference — raises, because a `foreign-procedure`'s types are fixed when it is
compiled.

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
  nested `[:struct ...]`, or a fixed array `[:array type count]`. The descriptor
  must be a literal — it is read at compile time, not evaluated.
- `layout-size` `layout` — `sizeof` the struct, padding included.
- `layout-alignment` `layout` — its alignment requirement.
- `field-offset` `layout path` — the byte offset of a field. `path` is a keyword,
  or a vector of them to reach into a nested struct.
- `read-field` `ptr layout path` / `write-field` `ptr layout path value` — read or
  write a scalar field by name, at the offset the layout knows.
- `read` and `write` also take a **whole layout** where they take a type: a
  struct reads as a map of its fields and writes from one, an array reads as a
  vector and writes from any sequence (or Jolt array) of the declared length. A
  struct value must hold each field and no others.
- `place` `layout [path]` — resolve one member **once** into a value `read` and
  `write` accept where they take a type. The member decodes and encodes as its
  own shape, and resolving the path is the work a place removes — so make one
  and keep it, as you would a `defcfn` binding. A path that names nothing raises
  rather than answering `nil`: a layout is closed, so a member that is not in it
  is a mistake in the program.

```clojure
(def date (ffi/layout [:struct [[:year :int32] [:month :uint8] [:day :uint8]]]))

(ffi/layout-size date)                ; => 8  (6 bytes, padded to int32 alignment)
(ffi/field-offset date :month)        ; => 4

(ffi/with-layout [p date]
  (ffi/write-field p date :year 2026)
  (ffi/write-field p date :month 8)
  (ffi/read-field p date :year))       ; => 2026

;; …or the whole struct as a map
(with-open [a (ffi/confined-arena)]
  (let [p (ffi/alloc a date)]
    (ffi/write p date {:year 2026 :month 8 :day 31})
    (ffi/read p date)))                ; => {:year 2026 :month 8 :day 31}

;; …or one member, resolved once and reused
(def year (ffi/place date :year))
(ffi/read p year)                      ; => 2026
```

A nested struct is addressed by path:

```clojure
(def event (ffi/layout [:struct [[:tag :uint8]
                                 [:when [:struct [[:year :int32] [:month :uint8]]]]
                                 [:seq :uint16]]]))
(ffi/field-offset event [:when :year])
(ffi/read-field p event [:when :month])
```

A fixed array is `[:array element-type count]` — babashka.ffi's order — and
elements may themselves be
arrays or structs — so a matrix is an array of arrays, and a ring buffer of
events is an array of structs. Array indices are **integer** components in a
field path, alongside the keywords:

```clojure
(def frame (ffi/layout [:struct [[:tag    :int32]
                                 [:matrix [:array [:array :double 3] 2]]
                                 [:name   [:array :uint8 8]]]]))

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
(def q (ffi/layout [:struct [[:events [:array [:struct [[:code  :int32]
                                                        [:frame :int32]]] 4]]]]))
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

- `foreign-callable` `f argtypes rettype [:collect-safe]` — wrap a Jolt fn `f` as a C-callable function pointer: the inverse of `defcfn`, so C can call back *into* Jolt (a `qsort` comparator, a GTK signal handler, any C API that takes a callback). The args C passes arrive as Jolt values; the Jolt return is marshaled back per `rettype`. The callback stays live until `free-callable` releases it. Pass a trailing `:collect-safe` whenever the callback can arrive on a thread that is not an active Jolt thread at that moment: either a thread the runtime never started (a dispatch queue, a pthread the C library spawned), or a Jolt thread parked in a `:blocking` foreign call (e.g. a GUI main loop). The collect-safe entry activates the thread before any Jolt code runs; a plain entry runs Jolt code on a thread the collector does not know is running, and the process dies with a nonrecoverable memory fault no handler can catch. The activation costs a little per call, so leave it off when C only ever invokes the callback on the thread that called into it (a `qsort` comparator).
- `free-callable` `ptr` — release a callable built by `foreign-callable`; returns `nil`.
- `callback` `arena f argtypes rettype [:collect-safe]` — the same pointer, owned by an arena, with no `free-callable` to remember. Choose the arena for the thread that calls back: a **shared** arena lets C invoke it from any thread (an event-loop notification), a **confined** one is for a callback C only invokes during a call you make (a comparison function), and an **automatic** one releases the pointer once the arena is unreachable — the collector cannot see the copy C holds, so use it only when your own reference outlives every call C can make. C can call the pointer until its arena releases it and not one instruction longer, so unregister the callback with the C library first.
- `export!` `name f argtypes rettype [:collect-safe]` — publish `f` as a C-callable entry point under `name`, for `jolt build --library`. An embedder resolves it via `jolt_lookup("name")` after `jolt_library_init`. The argtypes/rettype keywords are the same as `defcfn`. A trailing `:collect-safe` follows the same rule as `foreign-callable`: pass it when the embedder may call the export from a thread the runtime did not start.

```clojure
;; qsort comparator callable into libc
(def cmp (ffi/foreign-callable
           (fn [pa pb]
             (let [a (ffi/read pa :int) b (ffi/read pb :int)]
               (cond (< a b) -1 (> a b) 1 :else 0)))
           [:pointer :pointer] :int))
(c-qsort arr n (ffi/sizeof :int) cmp)
(ffi/free-callable cmp)

;; …or let an arena own it
(with-open [a (ffi/confined-arena)]
  (let [cmp (ffi/callback a compare-ints [:pointer :pointer] :int)]
    (c-qsort arr n (ffi/sizeof :int) cmp)))
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
- `:bool` — a one-byte C boolean (`_Bool`/`stdbool.h`), `true`/`false` in Jolt.
  Jolt truthiness decides the byte going out, so `nil` and `false` send 0 and
  everything else sends 1 — a C predicate answers `true`/`false` rather than the
  truthy number `0`. It travels as one byte, not as an int-sized `boolean`,
  which would be the wrong width for `_Bool` and read three bytes of whatever
  the callee left above the result.
- `:void` — return ignored (`nil`)

## Memory and libraries

Foreign memory is manual — allocate, use, free. There is no finalizer.

- `load-library` `[spec]` — load a shared object. With no spec, binds symbols already in the running process (libc, POSIX). With a spec, a per-OS map (`{:darwin "libsqlite3.0.dylib" :linux "libsqlite3.so.0"}` — `:mac` is accepted for `:darwin`), a bare path, or an **ordered list of candidates** tried in turn, since the same library is spelled differently across distributions. Answers `{:path "..."}` naming the candidate that loaded. Inside a `deps.edn` project you usually declare natives under `:jolt/native` instead (see the guide).
- `loaded?` `name` — was a library loaded?

**Symbol resolution.** A library loaded through `load-library` or a `:jolt/native`
declaration is loaded privately and registered: a `defcfn` resolves its C symbol
against the registered libraries first (in load order), and only falls back to
the process-global namespace when none of them export it (libc calls, `:process`
natives). The OS's own libraries can therefore never shadow a declared native,
even when they export the same symbol names — macOS ships BoringSSL system-wide
with the full `EVP_*` set, and before this rule an OpenSSL-backed library's
digest calls could silently land there and abort the process.
- `alloc` `[arena] n [alignment]` — allocate **zeroed** memory; returns a pointer.
  `n` is a byte count, a type keyword, or a compiled layout. With an arena the
  arena owns it; without one you `free` it. A type or layout aligns naturally, a
  byte count aligns to 16, and a third argument overrides.
- `free` `ptr` — release memory from the arena-less `alloc` / `string->ptr`, and
  forget any size recorded for that pointer (see [Pointer sizes](#pointer-sizes)).
- `sizeof` `type-or-layout` / `alignof` `type-or-layout` — size and alignment,
  padding included for a layout.
- `read` `ptr type [offset]` — read a typed value at `ptr` (+ optional byte offset).
  `type` is a type keyword, a compiled layout, or a `place`.
- `write` `ptr type value [offset]` — write a typed value. **The value comes
  before the offset**, as it does in babashka.ffi; an offset and a value are
  both integers, so a call written the other way round is not detectable and
  writes the wrong thing.
- `read-array` `ptr n` — `n` bytes → `byte-array` (binary-faithful, no encoding).
- `read-array` `ptr type n [offset]` — `n` elements of one scalar type → an array
  of that width: a `byte-array` for the one-byte types, `short-array`,
  `int-array` or `long-array` for the 2-, 4- and 8-byte integer and pointer
  types, `float-array` / `double-array` for the floating types. The type gives
  the *width* and nothing else, so the bits land as they are.
- `write-array` `ptr arr` / `ptr arr off n` — `byte-array` → memory.
- `write-array` `ptr type arr [offset]` — the typed inverse of `read-array`.
- `read-bytes` `ptr n` — `n` bytes → string (UTF-8).
- `write-bytes` `ptr s` — a string's UTF-8 bytes → memory.
- `string->ptr` `[arena] s` — a NUL-terminated C string. `nil` answers `NULL` and
  allocates nothing.
- `ptr->string` `ptr [limit]` — read a NUL-terminated C string back. A `limit`
  bounds the scan, so a buffer with no NUL raises instead of running off the end.
- `copy` `src dst [n]` — copy bytes, `memmove` semantics (overlap is safe).
  Without `n`, the source's known `size`.
- `clone` `arena src [n]` — a copy of `src` allocated in `arena`.
- `null` — the null pointer; `null?` `p` — the test.
- `pointer?` `x` — true for a non-negative integer address. A Jolt pointer *is*
  its address, so this cannot tell one from any other address-shaped integer.
- `address` `p` — the address as a long (the identity on a Jolt pointer; it
  exists so code written against either FFI reads the same).
- `size` `p` — the size Jolt was **told**: what an arena allocated, or what
  `segment` / `reinterpret` declared. `0` for anything else, including every
  pointer C hands back. This is what lets the countless `copy` and `clone` work.
- `segment` `addr [n]` — a pointer to `addr`, recording `n` as its size.
- `slice` `p offset [len]` — a pointer `offset` bytes into `p`.
- `reinterpret` `p n [arena [cleanup]]` — declare that `p` addresses `n` bytes.
  With an arena the declaration is dropped when the arena closes, and the arena
  calls `cleanup` with `p` — where a C library's own deallocator goes. The arena
  frees nothing here; the memory is C's.
- `find-symbol` `sym` — the address of `sym`, or `nil`. Searches declared natives
  first, then the process's own symbols — the same resolution a `defcfn` gets.
- `load-system-library` `name` — load by short name: `"z"` finds `libz.so`,
  `libz.dylib` or `z.dll`. On Linux it also globs the versioned sonames, since a
  runtime-only package has `libz.so.1` and no `libz.so`.

Nothing here is bounds-checked: a Jolt pointer is a raw address and carries no
size, so reading past an allocation reads whatever is there.

## Arenas

An arena is a group of allocations with one lifetime: allocate into it, and
closing it releases every block, callback and registered cleanup at once.

```clojure
(with-open [a (ffi/confined-arena)]
  (let [buf   (ffi/alloc a 4096)
        name  (ffi/string->ptr a "config.toml")
        on-ev (ffi/callback a handle-event [:pointer :int] :void)]
    ...))                              ; all three released here
```

Four kinds, differing only in who may use them and who closes them:

| | usable from | closed by |
|---|---|---|
| `confined-arena` | the creating thread only | `with-open` / `close-arena` |
| `shared-arena` | any thread | `with-open` / `close-arena` |
| `global-arena` | any thread | never — it lives as long as the process |
| `auto-arena` | any thread | the collector, once the arena is unreachable |

A **confined** arena is the one to reach for inside a function: it is cheapest to
reason about, and using it from another thread raises rather than letting two
threads race on one block list — a failure that otherwise has no error of its
own, only a fault inside the allocator. A **shared** arena is for memory that
outlives the call and is released elsewhere. An **automatic** arena is for the
case no scope can cover: a callback C may invoke from a thread Jolt never
started.

- `confined-arena` / `shared-arena` / `global-arena` / `auto-arena` — construct one.
- `close-arena` `a` — release everything it owns. This is what `with-open` calls.
  A second close releases nothing and is not an error, so an early release inside
  a `with-open` body does not turn into an exception thrown from the `finally`.
  Closing from the wrong thread, or closing a global or automatic arena, raises:
  those are wrong rather than redundant.
- `with-arena` `[a] & body` — `with-open` with the constructor spelled in.
- `arena?` `x` / `arena-open?` `a` — the predicates.
- `drain-auto-arenas!` — release every automatic arena the collector has
  reclaimed, and answer how many. Jolt drains when arenas are next created or
  allocated into, so an automatic arena is released promptly in code that keeps
  allocating; in code that stops, not until the process ends — which is the same
  memory the process was going to return anyway.

`alloc`, `string->ptr`, `clone`, `reinterpret` and `callback` all take an arena
in the same first position. An arena releases three kinds of thing: blocks (in
reverse allocation order), callbacks, and `reinterpret` views, whose cleanup
function runs while the memory is still readable. A cleanup that raises does not
strand the rest of the group — everything is released and the first failure is
re-thrown.

> **Do not close an arena while C still holds one of its pointers.** C can read
> released memory, and nothing raises.

### Pointer sizes

A Jolt pointer is a bare machine address — it carries no length. `size` answers
what Jolt was *told* a pointer addresses, which is what lets `copy` and `clone`
work without a byte count:

- `size` `p` — the recorded size, or `0` for a pointer Jolt was never told about
  (every pointer that comes back from C).
- `segment` `[arena] addr [nbytes]` — a pointer to `addr`, recording `nbytes`.
- `slice` `[arena] p offset [len]` — a pointer `offset` bytes into `p`,
  recording `len`.
- `reinterpret` `[arena] p nbytes [cleanup]` — declare that `p` addresses
  `nbytes`. With an arena, the arena also calls `cleanup` with `p` on close —
  the place to hand a C library its own deallocator. The arena frees nothing
  here; the memory is C's.

An arena allocation records its size, and the arena forgets it on close. `free`
forgets the size of the pointer it releases.

**Without an arena, `segment`, `slice` and `reinterpret` record for the life of
the process.** That is the form to use for a pointer whose size you declare once
at startup. It is the wrong form in a loop, for two reasons:

- The record is never reclaimed, so it grows without bound.
- The record is keyed by **address**, and an allocator hands the same address
  out again. Once the memory behind a declared address is released, the record
  outlives it, and a later allocation that lands there inherits a size it never
  had — which `copy` and `clone` would then use as a byte count.

Pass an arena and both problems go away, because the record dies with the group:

```clojure
;; walking an array of structs — the sizes die with the arena
(with-open [a (ffi/confined-arena)]
  (dotimes [i n]
    (let [p (ffi/slice a arr (* i (ffi/sizeof point)) point)]
      (handle (ffi/read p point)))))
```

`reinterpret` is also how a pointer from C gets a size in the first place. Give
it the **actual** size: nothing can check it, Jolt does not bounds-check a read,
and a size larger than the allocation is a read off the end.

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

## babashka.ffi compatibility

`jolt.ffi` is a name-for-name, semantics-for-semantics match of
[`babashka.ffi`](https://github.com/babashka/ffi) wherever one substrate can
match the other, so a shim in either direction is a namespace alias plus a short
list of gaps.

Matching: the arena constructors and every arena-taking function, `alloc` /
`free`, `read` / `write` (including the argument order — the offset is last),
`sizeof`, `alignof`, `place`, `copy`, `clone`, `size`, `address`, `segment`,
`slice`, `reinterpret`, `pointer?`, `null`, `null?`, `ptr->string`,
`string->ptr`, `read-array`, `write-array`, `find-symbol`, `load-library`,
`load-system-library`, `cfn`, `defcfn`, `callback`, the `:&` variadic marker,
and the type keywords with `:bool` included.

Where the two differ, they differ because the substrate does:

- **A Jolt pointer is a raw address, not a sized `MemorySegment`.** So `read` and
  `write` do not bounds-check, and a zero-size pointer is not something Jolt can
  refuse. `size` answers what Jolt was *told* — by an arena allocation, `segment`
  or `reinterpret` — and `0` otherwise, which is enough for `copy` and `clone` to
  work without a count on memory Jolt handed out. `pointer?` is true for any
  non-negative integer, because that is all a pointer is here.
- **`cfn` and `callback` are macros.** Chez's `foreign-procedure` needs its types
  at compile time, which is what makes an emitted binding a real typed call and
  not an interpreter. So argtypes must be a literal vector, the target must be a
  literal C symbol name rather than a function pointer, and the library-scoped
  4-argument `cfn` (and `defcfn`'s `:library`) *raises with an explanation*
  instead of quietly searching every loaded library — Jolt already resolves a
  declared `:jolt/native`'s symbols through its own handle, which is the
  guarantee that argument buys in babashka.ffi.
- **A layout is compiled by the `layout` macro**, and `read` / `write` take that
  compiled value, where babashka.ffi takes the literal descriptor at each call.
  Chez builds the ABI layout with an `ftype`, at compile time.
- **No `:union` in a layout, and no `byte-buffer`** — there is no `java.nio`
  here. `read-bytes`, `read-array` and `read-into!` are the block moves.
- **No bare `:&`.** babashka.ffi's `[:string :int :&]` infers each call's tail
  from the values it is given; a compiled `foreign-procedure` has its types fixed
  before any call, so the tail belongs to the binding — bind one signature per
  tail shape. The bare marker raises saying so.

Jolt adds, with no babashka.ffi equivalent: the arena-less `(alloc n)`, the
`with-*` scoped helpers, `arena?` / `arena-open?` / `close-arena` /
`drain-auto-arenas!`, `layout-size` / `layout-alignment` / `field-offset` /
`read-field` / `write-field`, `foreign-fn` / `foreign-callable` /
`free-callable` / `export!`, `:varargs` (a second spelling of `:&`), `:blocking`,
`:capture-native-error`, `errno`, `loaded?`, `defining-libraries`, `read-bytes` /
`write-bytes` / `read-into!`, and the exact-width type aliases.
