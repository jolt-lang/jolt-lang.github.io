A native region is a block of numeric code that runs as C: `jolt build` compiles it and links it
into the binary, and in the REPL and under `jolt run` a JIT compiles the same C in process. It is for the hot loop that the [numeric optimizations](/docs/numeric-performance) can't make fast
enough: a kernel over arrays of doubles, a simulation step, an inner loop of a codec. The region is
checked when it is compiled, the arrays it creates are freed deterministically, and nothing it
allocates can leak out of it, so the C it becomes is memory safe.

```clojure
(require '[jolt.native :refer [native]])

(defn norm [^doubles xs]
  (native [^doubles xs]
    (loop [i 0 s 0.0]
      (if (< i (alength xs))
        (recur (inc i) (+ s (* (aget xs i) (aget xs i))))
        (Math/sqrt s)))))
```

In a binary built with `jolt build` the body runs as C linked into it. Under `jolt run` and in the
REPL, the first time the call site loads, jolt compiles the region's C in process with
[MIR](https://github.com/vnmakarov/mir)'s C compiler, which is linked into jolt. Both compile the
same C, so a hot loop runs at native speed while you tune it at the REPL, and gives the same answers
in the binary. A build with regions needs a C compiler (`JOLT_CC`, or `cc` on `PATH`); the JIT
needs none, not even system headers.

Where there is no native path, calling a region raises `UnsupportedOperationException` with the
reason: on the Gambit target, on a machine MIR has no code generator for, and in a jolt built with
`JOLT_NO_MIR=1`. A source checkout of jolt gets the JIT from `make mirlib`, or from the library
`JOLT_MIR_LIB` names.

## What a region can do

The vector names the locals the region reads, each with its type:

| Hint | Meaning |
|---|---|
| `^long`, `^double`, `^boolean` | a scalar, converted as `long`, `double` convert; a boolean must be one |
| `^doubles`, `^longs` | an array the region **borrows**: it reads and writes the caller's array in place, with no copy, and cannot keep it |
| `^bytes` | a byte array the region reads in place, each element a signed byte as a long; a region can't write, clone or create one |

The body is a subset of Clojure:

- `let`, `if`, `when`, `when-not`, `do`, `loop`/`recur`, `dotimes`, `and`, `or`, `not`
- `+ - * /`, `inc`, `dec`, `quot`, `rem`, `mod`, `< <= > >= == = not=`, `zero?`, `pos?`, `neg?`
- `long`, `double`
- `bit-and`, `bit-or`, `bit-xor`, `bit-not`, `bit-shift-left`, `bit-shift-right`, `unsigned-bit-shift-right`
- `aget`, `aset`, `alength`, and `double-array`, `long-array`, `aclone`, which create arrays the region **owns**
- `Math/sqrt`, `sin`, `cos`, `tan`, `exp`, `log`, `floor`, `ceil`, `pow`, `atan2`
- your own macros, which expand inside the region, [helpers](#helpers), and
  [C functions you declare](#calling-c)

A region returns a long, double, boolean, `nil`, or an array it owns, which arrives as a fresh
`double-array` or `long-array`.

## Types and hints

The hints on a region's vector are the only type annotations it needs. They are where Jolt values
cross into C, so they say exactly what crosses; a region with an unhinted name is a compile error.
Everything inside the region is inferred, forward from what is already known, with Clojure's numeric
rules:

| In the region | Its type |
|---|---|
| a literal | `1` is a long, `1.0` a double, `true` a boolean |
| a `let` binding | the type of its value |
| arithmetic | long if every operand is a long, double as soon as one is a double, folded left pair by pair as Clojure does |
| a comparison, `zero?`, `not`, `and`, `or` | boolean |
| `aget` | the array's element type |
| a `loop` variable | the type of its initial value |
| a helper call | what the helper's body returns for those argument types |

```clojure
(native [^doubles xs ^long n]
  (let [k (* n 2)             ; long
        x (* k 0.5)           ; double: a long times a double
        a (double-array k)]   ; an array of doubles the region owns
    (aset a 0 x)
    (+ x (aget xs 0))))        ; double
```

A helper's parameters take no hints. Each call site's argument types are known, so the helper is
compiled once per combination of them: the same helper over arrays of doubles in one region and
arrays of longs in another gives two compiled copies. Its result type is inferred from its body. A
helper that calls itself, or a pair that call each other, is solved from the base case: the
recursive calls start with no type, the base case gives the body one, and the body is re-checked
until the type stops changing.

```clojure
(defnative fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))   ; long, from the base case
```

A return hint on a helper, `(defnative ^long fib [n] ...)`, is optional; when it is there it is
checked against what the body returns. A helper with no base case is a compile error
("never returns").

A few programs type-check in Clojure but would answer differently in C, so a region refuses them
with the fix in the message rather than guess:

| Refused | Why | Write instead |
|---|---|---|
| `(loop [s 0] ... (recur (+ s 0.5)))` | the loop variable would change from long to double partway | start it as `0.0` |
| `(/ a b)` with both longs | in Clojure that is a ratio | `quot`, or make an operand a double |
| `(= n 1.0)` with `n` a long | `=` is false across number types | `==` |
| `(if n ...)` with `n` a number | every number is truthy | a boolean test, such as `(pos? n)` |
| `(dotimes [i 2.5] ...)` | `dotimes` counts to `(long n)`, so 2.5 runs twice | `(dotimes [i (long n)] ...)` |

## Ownership

A region frees the arrays it creates without a garbage collector, using the ownership rules of
[Carp](https://github.com/carp-lang/Carp), whose checker jolt's is ported from. An array has one
owner. Binding it to another name, returning it, or passing it to `recur` gives it away; `aget`,
`aset`, `alength`, `aclone` and helper calls only borrow it. When the owner's scope ends, the array is
freed. The rules are checked at compile time, so these are compile errors rather than crashes:

```clojure
(native [] (let [a (double-array 4) b a] (aget a 0)))
;; native region: uses a after it was given away (moved); use (aclone x) to keep a copy

(native [^doubles xs] xs)
;; native region: a region cannot return an array it borrowed; return (aclone it)
```

A loop iteration that continues may not give away a value from outside the loop (the next iteration
would use it again); the path that leaves the loop may. The rules for `if`, `let`, `loop`/`recur` and
error exits are proved with z3 alongside the implementation, and every program in the test suite runs
under an allocator that fails on any leak or double free.

## Same answers as Clojure

A region follows the JVM's primitive semantics, so it gives the results and exceptions the same body
gives as hinted Clojure on the JVM, whether it was linked into a binary or compiled by the JIT:

- Long arithmetic that leaves the long range raises `ArithmeticException` ("integer overflow"). Plain
  Jolt promotes to a bignum there; C can't, so a region doesn't.
- A variadic op folds left pair by pair: `(+ Long/MAX_VALUE 1 1.5)` overflows on the first pair.
- `quot`, `rem` and `mod` by zero raise `ArithmeticException` ("/ by zero"), and
  `(quot Long/MIN_VALUE -1)` wraps to `Long/MIN_VALUE` as the JVM's long division does.
- `(/ long long)` is a compile error, because in Clojure it's a ratio. Use `quot`, or make an operand a double.
- `=` compares values of one type; use `==` to compare numbers across types.
- A test must be a boolean, since every non-nil value is truthy.
- Shift counts are masked to 0..63 as the JVM's long shifts are, where C would leave a count of 64
  or more undefined.
- A helper that recurses deeper than the thread's stack raises `StackOverflowError` instead of
  crashing the process, and frees the arrays it held.- Indexes and sizes follow the JVM: an out-of-range index raises `ArrayIndexOutOfBoundsException`,
  and `(double-array n)` takes `n` through `intValue`.
- Floating point rounds exactly as Jolt's does: MIR never fuses a multiply and an add, and a build
  compiles with `-ffp-contract=off` so the C compiler doesn't either.

A borrowed `long-array` is stored as Chez fixnums, so storing a value outside -2^60..2^60-1 into one
raises; widen the array outside the region first. An owned long array has no such limit.

## Helpers

`defnative` defines a function regions can call. Its parameters take no hints: the helper is compiled
once for each set of argument types it is called with, so one helper serves arrays of doubles and of
longs.

```clojure
(require '[jolt.native :refer [native defnative]])

(defnative sum-of-squares [xs]
  (loop [i 0 s 0.0]
    (if (< i (alength xs)) (recur (inc i) (+ s (* (aget xs i) (aget xs i)))) s)))

(defn norm [^doubles xs]
  (native [^doubles xs] (Math/sqrt (sum-of-squares xs))))
```

A helper borrows an array passed to it, unless its body gives the array away: returns it, binds it
into what it returns, or passes it on to a helper that takes it. Then the helper takes ownership of
it, and the caller's binding is given away, as if bound to a new name. Nothing is annotated; the
compiler infers it from the helper's body, and says so when it matters:

```clojure
(defnative keep-it [a] a)

(native [] (let [a (double-array 3) b (keep-it a)] (aget a 0)))
;; native region: uses a after it was given away (moved); keep-it took ownership of its
;; 1st argument (it returns it); use (aclone x) to keep a copy
```

A helper can create and return arrays of its own. A helper that calls itself, or a pair that call
each other, has its result type inferred from the base case; a hint like `(defnative ^long fib [n]
...)` is optional and checked. Outside a region, the name is an ordinary function over the same body.

### A body per platform

A helper can give a body per platform, chosen by the C compiler, the way
[Ferret](https://github.com/nakkaya/ferret)'s `defnative` does:

```clojure
(defnative sum [xs]
  (on "defined(__aarch64__)" (loop ...))
  (on :default (loop ...)))
```

Each guard is a C preprocessor condition, and the first one that holds is compiled. A platform no
guard covers fails the build unless there is an `(on :default ...)`. The bodies must agree on their
result type and on which arguments they take. Outside a region the name is an ordinary function over
the `:default` body, or the first one.

A guard can also test a CPU feature. `jolt build` compiles for its C compiler's default target,
usually the baseline, where macros like `__AVX2__` are off. The JIT compiles on the machine that runs
the code, and defines the macro of each feature that CPU has: `__SSE4_1__`, `__SSE4_2__`,
`__POPCNT__`, `__AVX__`, `__AVX2__`, `__FMA__`, `__BMI2__`, `__AVX512F__` and `__ARM_NEON`, plus
`__SSE__` and `__SSE2__` on any x86-64. So a SIMD body and a portable one can sit side by side, and
each machine runs the one it can:

```clojure
(defextern ^double dot-avx2 [^doubles xs ^doubles ys])   ; built with -mavx2, in a :jolt/native lib

(defnative dot [xs ys]
  (on "defined(__AVX2__)" (dot-avx2 xs ys))
  (on :default (loop [i 0 s 0.0]
                 (if (< i (alength xs))
                   (recur (inc i) (+ s (* (aget xs i) (aget ys i))))
                   s))))
```

MIR's C compiler has no SIMD intrinsics, so the SIMD body calls C that has them, through
[`defextern`](#calling-c). `(jolt.native/cpu-features)` answers the features the JIT targets in this
process, as keywords (`#{:avx2 :fma ...}`), and is empty without a JIT. `JOLT_MIR_CPU=baseline` makes
the JIT target the baseline, to reproduce a build's choice in the REPL.

## Calling C

`defextern` declares a C function a region can call:

```clojure
(require '[jolt.native :refer [native defextern]])

(defextern ^double fast-erf [^double x])
(defextern ^long next-seed [^long s] {:c "xs_next" :header "xorshift.h"})

(defn noise [^long seed ^long n]
  (native [^long seed ^long n]
    (loop [i 0 s seed acc 0.0]
      (if (< i n) (recur (inc i) (next-seed s) (+ acc (fast-erf (double s)))) acc))))
```

Scalar arguments and results are `^long`, `^double` or `^boolean`, and no result hint makes a
`void` function. An array parameter, `^doubles` or `^longs`, reaches C as a pointer to its elements
followed by their count as an `int64_t`, for the length of the call; the function may read and
write them but must not keep the pointer. A region passes the array's own storage. A `^longs` the
region borrowed from Jolt is stored as fixnums, so pass it as `(aclone xs)`. A `^bytes` can't be
passed to C. Pass distinct arrays: the same array passed twice reaches C as one pointer from a
region, but the name called outside a region copies each argument into its own buffer, so a
function that reads one while it writes the other sees different values in the two.

The region's C calls the function directly. Its C name is the symbol's with each character C
doesn't allow replaced by `_` (`fast-erf` is `fast_erf`); `:c` names it otherwise. It is declared
from the hints, or included through `:header`, which takes a quoted name (`"erf.h"`) or a system
one (`"<math.h>"`). A function a system header already declares, such as libc's `abs` or `labs`,
needs its `:header` (or a wrapper with another name): the prototype made from the hints would
conflict with the one the region's C includes.

Outside a region, the name calls the same function through `jolt.ffi`, copying arrays into foreign
memory and back. A `jolt build` links the function like any other symbol, so it has to come from a
`:static` entry in `:jolt/native`, or from libc or libm. The JIT finds it among the libraries the
process has loaded.

The JIT reads a `:header` itself, from the system include directories and any listed in
`JOLT_MIR_INCLUDE`. MIR's C compiler can't parse every system's headers (Android's bionic ones among
them), and a region whose header it can't read raises outside a build. A header that only declares
the function can be swapped for the prototype made from the hints.

The ownership checker can't see inside C: a region that calls one is memory safe only as far as
that function is.

## Pinned and GC-holding regions

Every region is memory safe. The choice here is only about what other threads' garbage collection
does while the region runs as C.

By default other threads keep running and collecting. The region pins the arrays it borrows in
place for the duration of the call, and the call releases its thread to the collector, the same
approach as the JVM's region pinning for JNI and GHC's safe foreign calls. That costs about 45ns a
call.

For a short region on a hot path, `^:hold-gc` opts into a plain call of about 2.5ns that holds every
other thread's collection until the region returns:

```clojure
(native ^:hold-gc [^double x] (* x x))
```

Use it only for regions that finish quickly: a long `^:hold-gc` region stalls the whole process's GC.

## Performance

The boundary costs a few nanoseconds: a borrowed array is the Chez flvector itself, read in place.
An n-body step as a region runs about 5.5x faster than the same hinted Jolt code in an optimized
build, with bit-identical results. Regions pay off for work per call; converting a persistent vector
to an array first costs about 14ns an element, so keep data in primitive arrays across calls.

Two things keep the C lean without giving up any checks' meaning. A range analysis proves where
an overflow, int-cast or bounds check can't fire and leaves those checks out of a fast copy of the
function. It covers:

- loop counters, `(< i (alength a))` scans, and indexes built from them
- an index below a long parameter, `(< i m)`, or below another array parameter's length
- an index below a length minus a constant, such as `(< i (dec n))` reading `(aget xs (inc i))`
- an index masked with `(bit-and i 3)`
- an accumulator that adds a bounded step once per counted iteration, so a sum over a `^bytes`
  array keeps no overflow check

The fast copy runs when a guard at the function's entry holds (each array argument long enough, a
long parameter such as `m` no larger than the array it bounds), and the fully checked code runs
otherwise, so results and exceptions are the same either way. Pure arithmetic a block repeats, such
as `(inc i)` in both a `recur` and an index, is computed once. And a small constant-size array (up to 256
elements) that never leaves the function or its loop iteration lives on the C stack, with no
allocation and no free. A function's frame holds at most 8KB of stack arrays, the largest going to
the heap past that, and a recursive helper keeps its arrays on the heap, since every frame would
push them again. When such an array is only indexed by constants, a scratch `[x y z]`, its
elements become plain C variables and it takes no memory at all.

On x86-64 Linux with glibc, `jolt build` compiles each region function that has a loop twice, once
for AVX2 and once for the baseline, and the loader picks the one the running CPU supports. This
needs gcc, or clang 18 or later; older clang builds one baseline copy. To turn it off, add
`-DJN_NO_CPU_CLONES` to the compiler: `JOLT_CC="cc -DJN_NO_CPU_CLONES"`. The two copies come from one
pass of the C preprocessor, so a `defnative` guard on `__AVX2__` still sees the baseline in both.

`(jolt.native/linked-regions)` answers, for each region whose call site has loaded, how it runs in
this process: `:linked` in a built binary, `:jit` under `jolt run` and in the REPL, or `nil` where
there is no native path and calling it raises.

## Specializing on values

The range analysis drops more checks when it knows real numbers. `^{:specialize [...]}` on the
region's vector compiles a variant of the region for each distinct value of those parameters the
call site sees, with the value as a constant:

```clojure
(defn msum [^long n ^doubles m]
  (native ^{:specialize [n]} [^long n ^doubles m]
    (loop [i 0 s 0.0]
      (if (< i n)
        (recur (inc i)
               (loop [j 0 s s]
                 (if (< j n) (recur (inc j) (+ s (aget m (+ (* i n) j)))) s)))
        s))))
```

For `n = 4` the index `(+ (* i n) j)` is known to be in 0..15, so the variant checks once that `m`
has at least 16 elements and leaves out the overflow, cast and bounds checks inside the loops; the
generic region has to check `(* i n)` for overflow even in its fast copy. A 512×512 matrix sum runs
about 1.7x faster specialized.

Each variant is compiled by the JIT the first time its values arrive, up to `:max-variants` of them
per call site (8 by default, `^{:specialize [n] :max-variants 16}`). Values past that, a variant that
fails to compile, and a binary `jolt build` made (which has no JIT) run the region as written. The
answers are the same either way. Values are cast as the region casts its arguments, so `2` and `2.0`
for a `^double` share a variant. Only `^long`, `^double` and `^boolean` parameters can be
specialized; to specialize on an array's size, pass the size as a `^long`.
`(jolt.native/specializations)` lists the values each call site has compiled variants for.

## Regions built at runtime

`native-fn` compiles a region from data, at runtime, and answers a function that runs it as native
code. The vector and body are the ones `native` takes, quoted:

```clojure
(require '[jolt.native :refer [native-fn]])

(def sq (native-fn '[^long n] '((* n n))))
(sq 7) ;=> 49
```

The region is compiled before `native-fn` returns, and a compile error throws from the call. Helpers,
`defextern`s and macros in the body resolve in the current namespace, or in the one passed as a
third argument. So a program can generate a region from what it only learns at runtime, a schema, a
record layout, a size, and run it with no interpretation left. It needs the JIT: in a binary `jolt
build` made, `native-fn` raises `UnsupportedOperationException`. `(jolt.native/jit?)` says which case
you're in.

`jolt.native.layout` is built on it. A binary record layout is data, and each function lowers it into
a region with the offsets, widths, byte order and sign extension as constants in the C:

```clojure
(require '[jolt.native.layout :as layout])

(def header [[:magic :u32] [:version :u8] [:pad 1] [:count :u16] [:size :i64]])

(def parse (layout/parser header))
(vec (parse buf 0))            ;=> [magic version count size], from the byte-array buf at offset 0

(def sizes (layout/column header :size))
(vec (sizes buf))              ;=> the :size of every whole record in buf
```

A field is `[name type]`, with type one of `:u8 :i8 :u16 :i16 :u32 :i32 :i64`, or `[:pad n]` for `n`
bytes skipped. An unsigned field reads as a non-negative long. `:endian :little` is the default;
pass `:endian :big` after the layout for the other order. `layout/record-size` gives a layout's size
in bytes. A read past the end of the array raises `ArrayIndexOutOfBoundsException`, as `aget` does.
