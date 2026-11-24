`jolt.native` lets you write a hot loop as a **native region**: a block of numeric Clojure that
`jolt build` compiles to C and links into the binary. The region reads and writes primitive arrays
in place, allocates and frees its own arrays without the garbage collector, and gives the same
answers and the same exceptions as the JVM would. Under `jolt run` and in the REPL the same checked
program runs as ordinary Jolt, so you develop it interactively and ship it as C.

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

Arguments are converted the way a `^long` or `^double` parameter converts on the JVM, so `3.9` passed
as a `^long` arrives as `3`. A value of the wrong kind, such as a vector where `^doubles` is
expected, is a `ClassCastException`.

The body is a subset of Clojure:

- `let`, `if`, `when`, `when-not`, `do`, `loop`/`recur`, `dotimes`, `and`, `or`, `not`
- `+ - * /`, `inc`, `dec`, `quot`, `rem`, `mod`, `< <= > >= == = not=`, `zero?`, `pos?`, `neg?`
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

### A body per platform

A helper can give a different body per platform, chosen by the C compiler with a preprocessor
condition:

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
form in its body must be one. The Jolt fallback, and the helper called outside a region, run the
`:default` body, or the first one when there is no default.

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
as `(aclone xs)`; passing it directly is a compile error. Outside a region, and in a region's Jolt
fallback, the same name calls the function through [`jolt.ffi`](/docs/api/ffi.html), copying each
array into foreign memory and back out after the call. Because of that copy, a function that reads
one array argument while writing another sees different values if you pass the same array twice, so
pass distinct arrays.

In a `jolt build` the function is linked like any other symbol, so it must come from libc, libm, or a
`:static` [`:jolt/native` library](/docs/native-interop.html#static_vs_dynamic_linking_in_a_built_binary).
The ownership checker cannot see into C: a region that calls an extern is memory safe only as far as
that function is.

## What the compiler does with a region

**Checks it cannot fail are removed.** A range analysis drops the overflow, int-cast and bounds
checks it can prove never fire: loop counters, `(< i (alength a))` scans, and indexes computed from
them. Checks that depend on the length of an array parameter are gathered into one guard at the
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

Here `p` never touches memory at all. The C is compiled with `-ffp-contract=off`, so the compiler does
not fuse a multiply and an add into one instruction that rounds differently from the Jolt fallback.

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

## Building

Nothing changes in how you build. `jolt build` finds every region the program loads, compiles them
into one C file with the system C compiler, and links the object into the binary:

```
$ jolt build -m demo.main -o demo
jolt build: compiled 9 native region(s)
...
```

A build with regions needs a C compiler: `cc` on `PATH`, or the one `JOLT_CC` names. Without one the
build stops with an error. A program with no regions does not need one.

Under `jolt run` and in the REPL there is no C step. Each region runs as its checked Jolt fallback,
which follows the same rules (the same overflow, the same exceptions, the same results), so tests you
run with `jolt run` hold for the binary. `linked-regions` tells you which way the regions in this
process run:

```clojure
(require '[jolt.native :refer [linked-regions]])

(linked-regions)
;; => {jn_e6bceec7b45ccb71 false, ...}   under jolt run
;; => {jn_e6bceec7b45ccb71 true, ...}    in the built binary
```

It maps each region whose call site has loaded to whether it runs as C.
