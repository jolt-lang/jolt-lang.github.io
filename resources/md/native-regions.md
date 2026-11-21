A native region is a block of numeric code that `jolt build` compiles to C and links into the binary.
It is for the hot loop that the [numeric optimizations](/docs/numeric-performance) can't make fast
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

In a binary built with `jolt build` the body runs as C. Under `jolt run` and in the REPL the same
checked program runs as Jolt, so the two agree, and you develop it like any other Clojure code. A
build with regions needs a C compiler (`JOLT_CC`, or `cc` on `PATH`).

## What a region can do

The vector names the locals the region reads, each with its type:

| Hint | Meaning |
|---|---|
| `^long`, `^double`, `^boolean` | a scalar, converted as `long`, `double` convert; a boolean must be one |
| `^doubles`, `^longs` | an array the region **borrows**: it reads and writes the caller's array in place, with no copy, and cannot keep it |

The body is a subset of Clojure:

- `let`, `if`, `when`, `when-not`, `do`, `loop`/`recur`, `dotimes`, `and`, `or`, `not`
- `+ - * /`, `inc`, `dec`, `quot`, `rem`, `mod`, `< <= > >= == = not=`, `zero?`, `pos?`, `neg?`
- `long`, `double`
- `aget`, `aset`, `alength`, and `double-array`, `long-array`, `aclone`, which create arrays the region **owns**
- `Math/sqrt`, `sin`, `cos`, `tan`, `exp`, `log`, `floor`, `ceil`, `pow`, `atan2`
- your own macros, which expand inside the region, and [helpers](#helpers)

A region returns a long, double, boolean, `nil`, or an array it owns, which arrives as a fresh
`double-array` or `long-array`.

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

## Same answers as Jolt

A region follows the JVM's primitive semantics, and its Jolt fallback runs the same checked program,
so the two give the same results and the same exceptions:

- Long arithmetic that leaves the long range raises `ArithmeticException` ("integer overflow"). Plain
  Jolt promotes to a bignum there; C can't, so a region doesn't, in either half.
- A variadic op folds left pair by pair: `(+ Long/MAX_VALUE 1 1.5)` overflows on the first pair.
- `(/ long long)` is a compile error, because in Clojure it's a ratio. Use `quot`, or make an operand a double.
- `=` compares values of one type; use `==` to compare numbers across types.
- A test must be a boolean, since every non-nil value is truthy.
- Indexes and sizes follow the JVM: an out-of-range index raises `ArrayIndexOutOfBoundsException`,
  and `(double-array n)` takes `n` through `intValue`.
- The C is compiled with `-ffp-contract=off`, so floating point rounds exactly as Jolt's does.

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

A helper borrows the arrays passed to it: it can read and write them, but not free or return them. It
can create and return arrays of its own. A helper that calls itself needs a return type hint,
`(defnative ^long fib [n] ...)`. Outside a region, the name is an ordinary function over the same
body.

## Safe and unsafe regions

While a region runs as C, other threads keep running and collecting garbage. The region locks the
arrays it borrows in place for the duration of the call, and the call releases its thread to the
collector, the same approach as the JVM's region pinning for JNI and GHC's safe foreign calls. That
costs about 45ns a call.

For a short region on a hot path, `^:unsafe` opts into a plain call of about 2.5ns that holds every
other thread's collection until the region returns:

```clojure
(native ^:unsafe [^double x] (* x x))
```

Use it only for regions that finish quickly: a long unsafe region stalls the whole process's GC.

## Performance

The boundary costs a few nanoseconds: a borrowed array is the Chez flvector itself, read in place.
An n-body step as a region runs about 5.5x faster than the same hinted Jolt code in an optimized
build, with bit-identical results. Regions pay off for work per call; converting a persistent vector
to an array first costs about 14ns an element, so keep data in primitive arrays across calls.

`(jolt.native/linked-regions)` answers, for each region that has loaded, whether it runs as C in
this process: true in a built binary, false under `jolt run`.
