Jolt compiles to Chez Scheme, which represents small integers and flonums close to the metal (tagged fixnums and unboxed flonums), and jolt leans on that to run tight numeric code without the boxing and megamorphic dispatch a dynamic language usually pays. The key idea: when jolt can *prove* a value is a `double` (flonum) or a `long` (fixnum), it emits the native Chez operation — `fl+`/`fl*`/`flsqrt`, `fx+`/`fx*` — instead of the generic numeric-tower path that inspects the operand's type at runtime. This page is about how that proof happens and how you help it along.

Everything here is a pure optimization: it never changes a result, only how fast it runs. A program that gives jolt no type information still runs correctly through the generic path — it is just slower in a hot loop.

## Most of the time you write plain Clojure

Jolt infers numeric types structurally, with no hints, in a closed-world build ([RFC 0005](/docs/rfc)). A few things fall out of that:

- **Whole-program parameter inference.** A function whose every call site passes a flonum has its parameter typed `double`, so its body unboxes — even with no `^double` hint. An integer caller leaves it generic; a function that escapes (stored in a var, passed around) stays dynamic.
- **Record field inference.** A `defrecord` whose constructor is only ever called with flonums for a field has that field typed `double`, so reads off the field unbox and arithmetic over them lowers to `fl` ops. All-flonum construction is enough; you don't annotate the field.
- **Flonum contagion.** A numeric field read next to a proven `double` operand is coerced (`exact->inexact`) and lowers to `fl*`, matching Clojure's contagion rules, without giving up the generic path where the type isn't proven.

So idiomatic numeric code — a `defrecord` of doubles, functions over them, a `reduce` accumulating a sum — often unboxes on its own. Reach for hints when the inference can't see enough: across a var boundary, at an entry point, or on a value that comes from outside the closed world.

## Hints, when you need them

A `^double` or `^long` hint on a parameter or `let`-binding asserts the type directly, and arithmetic over that binding lowers to the native op:

```clojure
(defn dist ^double [^double dx ^double dy]
  (Math/sqrt (+ (* dx dx) (* dy dy))))
```

Here `dx`/`dy` are proven `double`, so `*` and `+` emit `fl*`/`fl+`, `Math/sqrt` emits `flsqrt` (see below), and the `^double` on the function name coerces the return. No value is boxed in the loop that calls `dist`.

- `^double` / `^long` on a **param** or **`let`-binding** → the binding's arithmetic unboxes to `fl*`/`fl+`/… or `fx*`/`fx+`/….
- `^double` on the **defn name** → the body's return value is coerced to a flonum.
- A **float literal** (`2.0`, `1e3`) is a flonum, so it seeds `double` into the expression it sits in.

A hint is an assertion, not a cast: if you hint `^double` on a value that is actually a boxed integer at runtime, the result can differ from the JVM. The success-type lint (`JOLT_CHECK`, [RFC 0006](/docs/rfc)) surfaces provably-wrong arithmetic as a located warning, and is a good screen for a hinted numeric kernel.

## Primitive arrays

`double-array` and `float-array` produce arrays backed by an **unboxed Chez flvector**, so the elements never box, and the other array constructors (`long-array`, `int-array`, `short-array`, `object-array`) carry their element kind too. Reading and writing them stays unboxed when jolt knows the element type, which you assert with an array hint:

```clojure
(defn dot ^double [^doubles a ^doubles b ^long n]
  (loop [i 0 acc 0.0]
    (if (< i n)
      (recur (inc i) (+ acc (* (aget a i) (aget b i))))
      acc)))
```

- `^doubles` / `^floats` on a param or `let`-binding → `(aget a i)` lowers to a direct `flvector-ref` typed `double`, and `(aset a i v)` to a direct `flvector-set!`. So a read/fill loop stays unboxed on both ends, and the surrounding arithmetic (`acc`, `*`) unboxes with it.
- `^longs` / `^ints` are threaded through the same plumbing.
- Even without a hint, the hot one-dimensional `aget` / `aset` / `alength` lower to the array-aware native ops rather than the `clojure.core` overlay's var-deref + reduce path; multi-dimensional forms fall back to the overlay.

This is the fastest way to hold a large block of numbers in jolt: a primitive array reads and writes with no per-element allocation, where a persistent vector of boxed doubles would allocate on every update.

## java.lang.Math

When every operand of a `java.lang.Math` call is a proven `double` (or an integer literal, which is coerced to one, as long as at least one operand is a genuine double), the call lowers to the native Chez flonum op and its result types `double`:

| Clojure | native op |
| --- | --- |
| `Math/sqrt` `Math/sin` `Math/cos` `Math/tan` | `flsqrt` `flsin` `flcos` `fltan` |
| `Math/asin` `Math/acos` `Math/atan` `Math/atan2` | `flasin` `flacos` `flatan` `flatan` |
| `Math/exp` `Math/log` | `flexp` `fllog` |
| `Math/floor` `Math/ceil` `Math/pow` `Math/abs` | `flfloor` `flceiling` `flexpt` `flabs` |

Because the result types `double`, a `Math` call in the middle of an arithmetic expression keeps the whole expression unboxed (flonum contagion is preserved) rather than de-optimizing it. Untyped operands, or an all-integer call like `(Math/abs 5)`, stay on the generic string-keyed host-static path and return the ordinary boxed result.

## How to tell it worked

The numeric fast paths are covered by gates in the jolt repo (`make numeric`, `numwp`, `mandelbrot-num`, `fieldnum`, `fieldjoin`, `contagion`, `flarr`, `mathfl`) that assert the emitted Scheme contains the native op — `fl+`, `jolt-flaget`, `flsqrt` — and not the generic dispatch. If you are tuning a kernel and want to confirm it unboxes, those gates are the reference for what proven-numeric emission looks like, and the [`bench/`](https://github.com/jolt-lang/jolt/tree/main/bench) programs (mandelbrot, the numeric micro-benchmarks) are the wall-clock check.

Remember the fallback is always correctness: if a value can't be proven numeric, jolt runs it through the generic tower and the answer is the same. Hints and structural shape only decide whether that happens in a register or through a dispatch.
