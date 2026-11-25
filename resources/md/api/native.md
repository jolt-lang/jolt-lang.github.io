`jolt.native` lets you write a hot loop as a **native region**: a block of numeric Clojure that
compiles to C and always runs as native code. The region reads and writes primitive arrays in place,
allocates and frees its own arrays without the garbage collector, and gives the same answers and the
same exceptions as the JVM would. `jolt build` compiles it with the system C compiler and links it
into the binary; under `jolt run` and in the REPL jolt compiles the same C in process with a JIT, so a
hot loop runs at native speed while you tune it.

Most numeric code does not need this. Start with [Numeric Performance](/docs/numeric-performance.html):
hinted Clojure already unboxes to native Chez flonum and fixnum operations. Reach for a region when
a profile shows a tight loop over arrays is still the bottleneck.

```clojure
(require '[jolt.native :refer [native]])

(defn norm [^doubles xs]
  (native [^doubles xs]
    (let [n (alength xs)]
      (loop [i 0 s 0.0]
        (if (< i n)
          (recur (inc i) (+ s (* (aget xs i) (aget xs i))))
          (Math/sqrt s))))))

(norm (double-array [3 4]))  ;; => 5.0
```

## Writing a region

`(native [params*] body*)` takes a vector naming the locals the region reads, each with a type hint,
followed by the body. A region sees only those parameters and the locals it binds itself. A var, a
constant like `Long/MAX_VALUE`, or a local left out of the vector is a compile error.

| hint | what the region gets |
| --- | --- |
| `^long` | a 64-bit integer |
| `^double` | a double |
| `^boolean` | a boolean |
| `^doubles` | the caller's `double-array`, borrowed: read and written in place, no copy |
| `^longs` | the caller's `long-array`, borrowed the same way |
| `^bytes` | the caller's `byte-array`, read in place; each element is a signed byte, read as a long. A region never writes one |

Arguments are converted the way a `^long` or `^double` parameter converts on the JVM, so `3.9` passed
as a `^long` arrives as `3`. A value of the wrong kind, such as a vector where `^doubles` is
expected, is a `ClassCastException`. An `aset` on a `^bytes` parameter is a compile error.

The body is a subset of Clojure:

- `let`, `if`, `when`, `when-not`, `do`, `loop`/`recur`, `dotimes`, `and`, `or`, `not`
- `+ - * /`, `inc`, `dec`, `quot`, `rem`, `mod`, `< <= > >= == = not=`, `zero?`, `pos?`, `neg?`
- `bit-and`, `bit-or`, `bit-xor`, `bit-not`, `bit-shift-left`, `bit-shift-right`, `unsigned-bit-shift-right`,
  with the JVM's masked shift counts (`(bit-shift-left x 64)` is `x`)
- `long` and `double` conversions
- `aget`, `aset`, `alength`, and `double-array`, `long-array`, `aclone` to make arrays
- `Math/sqrt`, `sin`, `cos`, `tan`, `exp`, `log`, `floor`, `ceil`, `pow`, `atan2`
- calls to [`defnative`](#helpers_with_defnative) helpers and [`defextern`](#calling_c_with_defextern) functions

Macros expand first, so a macro that expands into these forms works. Anything else is refused when
the region compiles:

```
native region: not allowed in a native region: println (line 9)
```

A region returns a long, a double, a boolean, `nil`, or an array it created, which arrives in Jolt as
a fresh `double-array` or `long-array`:

```clojure
(defn squares [n]
  (native [^long n]
    (let [a (long-array n)]
      (dotimes [i n] (aset a i (* i i)))
      a)))

(vec (squares 5))  ;; => [0 1 4 9 16]

(defn scale! [^doubles xs ^double k]
  (native [^doubles xs ^double k]
    (dotimes [i (alength xs)]
      (aset xs i (* k (aget xs i))))))

(let [xs (double-array [1 2 3])] (scale! xs 2.0) (vec xs))  ;; => [2.0 4.0 6.0]
```

### Where it differs from plain Clojure

The type rules are Clojure's (a long and a double combine to a double, `1` is a long and `1.0` a
double, a loop local keeps the type of its initial value), but wherever C could give a different
answer from the JVM the region refuses the form rather than pick one:

- `(/ a b)` on two longs would be a ratio, so it is an error; use `quot`, or make one side a double.
- `=` across number types (`(= n 1.0)` with a long `n`) is an error.
- A test must be a boolean: `(if n ...)` with a long `n` is an error, since every non-nil value is truthy in Clojure.
- `recur` must be in tail position and pass values of the loop locals' types.

Long arithmetic does not promote to a bignum. It overflows with `ArithmeticException`, as `+` and `*`
do on the JVM:

```clojure
(defn add [a b] (native [^long a ^long b] (+ a b)))

(add Long/MAX_VALUE 1)  ;; ArithmeticException: integer overflow
```

Other failures raise the JVM's exceptions with the JVM's messages: an index out of range is
`ArrayIndexOutOfBoundsException` (`Index 5 out of bounds for length 2`), `(quot a 0)` is
`ArithmeticException` (`/ by zero`), and `(long 1e30)` is `IllegalArgumentException`
(`Value out of range for long: 1.0E30`). Double division by zero is `##Inf`, as on the JVM.

A borrowed `^longs` array holds Jolt fixnums, so a value outside -2^60 to 2^60-1 does not fit in it,
and storing one raises `IllegalArgumentException`. Widen the array outside the region, or build the
result in an array the region owns.

## Ownership

An array the region makes with `double-array`, `long-array` or `aclone` is **owned**: the region frees
it when it is done with it, or hands it back to Jolt as the result. A `^doubles` or `^longs` parameter
is **borrowed**: the region can read and write it, but it stays the caller's.

Binding an owned array to another name, returning it, or passing it to a helper that keeps it gives
it away. The checker, a port of the ownership pass from [Carp](https://github.com/carp-lang/Carp), tracks
this when the region compiles, so the mistakes that would be a use-after-free or a leak in C are
compile errors instead:

```clojure
(native [] (let [a (double-array 4) b a] (aget a 0)))
;; native region: borrows a after it was given away (moved); use (aclone x) to keep a copy

(native [^doubles xs] xs)
;; native region: a region cannot return an array it borrowed; return (aclone it)
```

Giving an array away inside a loop iteration that continues is refused as well, because the next
iteration would use it again. Give it away only on the path that leaves the loop.

The check runs whenever the region compiles, in the REPL too, so you see these errors while you
write the code and not when you first build the binary.

## Helpers with `defnative`

A region can call helpers defined with `defnative`. Their parameters need no hints: a helper is
compiled once for each set of argument types it is called with, so the same `dot` works on double
arrays in one region and long arrays in another.

```clojure
(require '[jolt.native :refer [native defnative]])

(defnative dot [xs ys]
  (loop [i 0 s 0.0]
    (if (< i (alength xs))
      (recur (inc i) (+ s (* (aget xs i) (aget ys i))))
      s)))

(defn cosine [^doubles a ^doubles b]
  (native [^doubles a ^doubles b]
    (/ (dot a b) (Math/sqrt (* (dot a a) (dot b b))))))

(cosine (double-array [1 0]) (double-array [1 1]))  ;; => 0.7071067811865475
```

An array argument is borrowed unless the helper's body gives it away (returns it, or passes it to a
helper that keeps it). Then the helper takes ownership, the caller's binding is moved, and the error
names the helper:

```
native region: borrows a after it was given away (moved); user/keep-it took ownership of its
1st argument (it returns it); use (aclone x) to keep a copy
```

A helper can make and return arrays of its own and can call itself. A recursive helper's result type
is inferred from its base case; a return hint, `(defnative ^double f [x] ...)`, is optional and checked.

Outside a region, a `defnative` name is an ordinary function over the same body with Jolt's own
semantics, so `(dot (double-array [1 2]) (double-array [3 4]))` returns `11.0` from the REPL too.

### A body per platform or CPU

A helper can give a different body per platform or per CPU, chosen by the C compiler with a
preprocessor condition:

```clojure
(defnative lsum [xs]
  (on "defined(__aarch64__)"
      (loop [i 0 s 0] (if (< i (alength xs)) (recur (inc i) (+ s (aget xs i))) s)))
  (on :default
      (loop [i (dec (alength xs)) s 0] (if (>= i 0) (recur (dec i) (+ s (aget xs i))) s))))
```

Each guard is a one-line C condition string, or `:default`. The first guard that holds is compiled.
A platform no guard covers is a build error unless there is an `(on :default ...)`. The bodies must
agree on their result type and on which arguments they take, and once a helper uses `on`, every
form in its body must be one. The helper called outside a region runs the `:default` body, or the
first one when there is no default.

The JIT compiles for the CPU it runs on. It defines the C macro of each feature the machine has
(`__SSE4_1__`, `__SSE4_2__`, `__POPCNT__`, `__AVX__`, `__AVX2__`, `__FMA__`, `__BMI2__`, `__AVX512F__`,
`__ARM_NEON`), so a SIMD body and a portable one can sit side by side and each machine runs the one it
can. MIR's C compiler has no SIMD intrinsics, so the SIMD body calls C that has them through
[`defextern`](#calling_c_with_defextern), from a library compiled with `-mavx2`:

```clojure
(defextern ^double dot-avx2 [^doubles xs ^doubles ys])

(defnative dot [xs ys]
  (on "defined(__AVX2__)" (dot-avx2 xs ys))
  (on :default (loop [i 0 s 0.0]
                 (if (< i (alength xs))
                   (recur (inc i) (+ s (* (aget xs i) (aget ys i))))
                   s))))
```

`cpu-features` lists what the JIT targets in this process, as keywords:

```clojure
(jolt.native/cpu-features)  ;; => #{:neon}           on Apple silicon
                            ;; => #{:avx2 :fma ...}  on a recent x86-64
```

It is empty without a JIT. `JOLT_MIR_CPU=baseline` makes the JIT target the baseline, with every
feature macro off. `jolt build` still compiles for its C compiler's default target, usually the
baseline, so a binary runs the `:default` body unless you build with flags that turn features on.

## Calling C with `defextern`

`defextern` declares a C function a region calls directly:

```clojure
(require '[jolt.native :refer [native defextern]])

(defextern ^double cbrt [^double x] {:header "<math.h>"})

(defn cube-roots [^doubles xs]
  (native [^doubles xs]
    (let [out (aclone xs)]
      (dotimes [i (alength out)] (aset out i (cbrt (aget out i))))
      out)))

(vec (cube-roots (double-array [8 27])))  ;; => [2.0 3.0]
```

- Scalar parameters and the result are hinted `^long`, `^double` or `^boolean`. With no result hint
  the function is `void` and the call answers `nil`.
- The C name is the symbol's with every character C does not allow replaced by `_` (`fast-erf`
  becomes `fast_erf`), unless the options map names it with `:c`.
- Without `:header`, the region declares the function from the hints. A function libc already
  declares (`abs`, `labs`) needs its own `:header`, or a wrapper with another name, because the
  hinted prototype would conflict with the one the region's C already includes.
- An array parameter (`^doubles`, `^longs`) reaches C as a pointer to its elements followed by their
  count as `int64_t`, valid for the length of the call. The function may read and write the elements
  but must not keep the pointer.

```clojure
(defextern scale! [^doubles xs ^double k])  ; C: void scale_(double *xs, int64_t n, double k)
```

A region passes an array's own storage. A borrowed `^longs` holds fixnums, not `int64_t`, so pass it
as `(aclone xs)`; passing it directly is a compile error. Outside a region, the same name calls the
function through [`jolt.ffi`](/docs/api/ffi.html), copying each array into foreign memory and back out
after the call. Because of that copy, a function that reads
one array argument while writing another sees different values if you pass the same array twice, so
pass distinct arrays.

In a `jolt build` the function is linked like any other symbol, so it must come from libc, libm, or a
`:static` [`:jolt/native` library](/docs/native-interop.html#static_vs_dynamic_linking_in_a_built_binary).
Under the JIT it is found among the libraries this process has loaded. The JIT reads a `:header`
itself, from the system include directories and any directories `JOLT_MIR_INCLUDE` lists
(`:`-separated, `;` on Windows). MIR's C compiler cannot parse every system's headers (Android's bionic
ones among them), and a region whose header it cannot read raises outside a build. A header that only
declares the function can be dropped in favor of the hinted prototype.

The ownership checker cannot see into C: a region that calls an extern is memory safe only as far as
that function is.

## What the compiler does with a region

**Checks it cannot fail are removed.** A range analysis drops the overflow, int-cast and bounds
checks it can prove never fire: loop counters, `(< i (alength a))` scans, and indexes computed from
them, an index bounded by a `^long` parameter (`(< i m)`), by another array parameter's length, or by
a length minus a constant (`(< i (dec n))` reading `(aget xs (inc i))`), and an index masked by a
non-negative constant, so `(aget xs (bit-and i 3))` keeps no bounds check. An accumulator that adds a
bounded step once per counted iteration keeps no overflow check, so a sum over a `^bytes` array runs
unchecked. Checks that depend on the length of an array parameter are gathered into one guard at the
function's entry. When the guard holds, a copy of the body without those checks runs. Otherwise the
fully checked body runs, so the results and exceptions are the same either way.

**Small arrays stay on the C stack.** An array of constant size up to 256 elements that never leaves
the function or the loop iteration that made it is placed on the stack, with no allocation and no
free. An array that is returned, carried by `recur`, taken by a helper, or bound to a name that may
also hold a heap array stays on the heap. One indexed only by constants, like a scratch point, turns
into plain C locals:

```clojure
(defn dist-from [^double x ^double y]
  (native [^double x ^double y]
    (let [p (double-array 2)]
      (aset p 0 (- x 1.0))
      (aset p 1 (- y 2.0))
      (Math/sqrt (+ (* (aget p 0) (aget p 0)) (* (aget p 1) (aget p 1)))))))
```

Here `p` never touches memory at all. `jolt build` compiles the C with `-ffp-contract=off`, so the C
compiler does not fuse a multiply and an add into one instruction that rounds differently from the
JIT, which never fuses. A region answers the same in a binary and in the REPL.

**Hot loops get a copy per CPU in a binary.** On x86-64 glibc, `jolt build` compiles each region
function that has a loop twice, for AVX2 and for the baseline, and the loader picks the one the CPU
can run (with gcc, or clang 18 and later). Defining `JN_NO_CPU_CLONES` turns it off, for example with
`JOLT_CC="cc -DJN_NO_CPU_CLONES"`.

## Threads and the garbage collector

By default, while a region runs as C, other threads keep running and collecting, and the arrays the
region borrows are pinned in place for the call. That costs about 45ns per call.

For a region that finishes quickly, mark the parameter vector `^:hold-gc`:

```clojure
(defn sq [x]
  (native ^:hold-gc [^double x] (* x x)))
```

The call is then a plain C call, about 2.5ns, but no other thread can collect until it returns, so a
long-running `^:hold-gc` region stalls every thread that allocates. Either way the region is memory safe.

## Specializing on runtime values

Mark scalar parameters with `:specialize` and the JIT compiles a variant of the region for each
distinct value of them the call site sees, with the value as a constant:

```clojure
(defn sum-matrix [^long n ^doubles m]
  (native ^{:specialize [n]} [^long n ^doubles m]
    (loop [i 0 s 0.0]
      (if (< i (* n n))
        (recur (inc i) (+ s (aget m i)))
        s))))
```

With `n` known, the range analysis can prove checks away that it could not for an unknown `n`; a
512x512 matrix sum runs about 1.7 times faster. Each variant is compiled the first time its values
arrive, up to `:max-variants` of them per site (8 by default; `^{:specialize [n] :max-variants 4}`
sets it). Values past the budget, and every call in a `jolt build` binary, which has no JIT, run the
region as written. The answers are the same either way.

`:specialize` takes a vector of `^long`, `^double` or `^boolean` parameter names. An array parameter
is refused; specialize on a long that gives its size. Values are keyed after the region's own casts,
so `2` and `2.0` passed for a `^double` share a variant, while `0.0` and `-0.0` do not.
`specializations` lists the variants compiled so far, keyed by a symbol naming the site:

```clojure
(jolt.native/specializations)
;; => {jn_8ae972569bccebf4$n$pinned$8$user [[2] [3]]}
```

## Regions from data

`native-fn` compiles a region at runtime from a params vector and a seq of body forms, and answers a
fn that runs it as native code. A program can generate a region from what it only learns at runtime,
such as a schema, a record layout or a size:

```clojure
(require '[jolt.native :as native])

(def sq (native/native-fn '[^long n] '((* n n))))
(sq 7)  ;; => 49
```

The region is checked and compiled before `native-fn` returns, so a compile error throws there. Names
in the body (`defnative` helpers, `defextern`s, macros) resolve in the current namespace, or in the
namespace passed as a third argument. Each distinct region is compiled once and kept for the life of
the process, so the same params and body give back the compiled code, but a program that generates
regions without bound grows without bound. Generate one per shape and pass what varies as
parameters, or specialize on it.

`native-fn` needs the JIT. `jit?` says whether this process has one: true under `jolt run` and in the
REPL on a machine MIR supports, false in a `jolt build` binary, where `native-fn` throws
`UnsupportedOperationException`.

### Binary record layouts

`jolt.native.layout` builds regions this way from a record layout described as data. The offsets,
widths, byte order and sign extension end up as constants in the C:

```clojure
(require '[jolt.native.layout :as layout])

(def header [[:magic :u32] [:version :u8] [:pad 1] [:count :u16] [:size :i64]])

(def parse (layout/parser header))
(vec (parse buf 0))      ;; => [magic version count size] of the record at byte 0

(def counts (layout/column header :count))
(vec (counts buf))       ;; => the :count of every whole record in buf

(layout/record-size header)  ;; => 16
```

A field is `[name type]`, with type one of `:u8 :i8 :u16 :i16 :u32 :i32 :i64`, or `[:pad n]` for `n`
bytes skipped. An unsigned field reads as a non-negative long. `parser` answers a fn of a byte-array
and a byte offset that returns a `long-array` of the named fields in layout order. `column` answers a
fn of a byte-array that scans it as consecutive records and returns a `long-array` of one field,
ignoring trailing bytes short of a record. Both take `:endian :little` (the default) or `:endian :big`
after the layout arguments, and a read past the end raises `ArrayIndexOutOfBoundsException`.
`parser-body` and `column-body` return the region body as data. Like `native-fn`, these need the JIT.

## How a region runs

A region always runs as native code, compiled from the same C either way.

**In a binary.** `jolt build` finds every region the program loads, compiles them into one C file
with the system C compiler, and links the object into the binary:

```
$ jolt build -m demo.main -o demo
jolt build: compiled 9 native region(s)
...
```

A build with regions needs a C compiler: `cc` on `PATH`, or the one `JOLT_CC` names. Without one the
build stops with an error. A program with no regions does not need one.

**Everywhere else.** Under `jolt run`, in the REPL and in tests, jolt compiles each region in process
with the C compiler of [MIR](https://github.com/vnmakarov/mir) the first time its call site loads. A
released jolt has MIR linked in. A source checkout gets it from `make mirlib`, which builds
`target/mir/libjoltmir`; `bin/jolt` loads that, and `JOLT_MIR_LIB` names another build of it.
`JOLT_NO_MIR=1` builds a jolt without the JIT.

Where there is no native path (the gambit target, or a machine MIR has no code generator for),
calling a region raises `UnsupportedOperationException` with the reason. Before 0.8.31 a region ran
as an equivalent Jolt function there; that fallback is gone.

`linked-regions` tells you how the regions in this process run:

```clojure
(require '[jolt.native :refer [linked-regions]])

(linked-regions)
;; => {jn_e6bceec7b45ccb71 :jit, ...}      under jolt run or in the REPL
;; => {jn_e6bceec7b45ccb71 :linked, ...}   in the built binary
```

It maps each region whose call site has loaded to `:linked` (compiled into this binary), `:jit`
(compiled in this process) or `nil` (no native code here; calling it raises). Before 0.8.31 it
answered `true` or `false`.
