# RFC 0013 — The numeric tower

- **Status**: Implemented
- **Champions**: jolt maintainers

## Summary

jolt runs on the host's numeric tower, not an emulation of one. Integers are
one seamless type that never overflows, ratios are exact and native, and the
surface JVM Clojure code sees — classes, promotion, printing — is a parity
layer on top. Type hints (`^long`/`^double`) can opt a hot path down to
machine arithmetic without shrinking the tower for everyone else.

## Integers: one representation, no overflow

There is no fixnum/bignum boundary at the language surface. Machine fixnums
promote to bignums transparently mid-computation:

```clojure
(inc 9223372036854775807)   ;=> 9223372036854775808N
(* 123456789012345678901234567890 987654321098765432109876543210)
;;=> 121932631137021795226185032733622923332237463801111263526900N
```

Arithmetic never throws on integer overflow because overflow has no
representation to reach. The JVM's `Long`/`BigInt` split — two classes, two
sets of ops, and the `+'` family of explicitly-promoting variants it motivates
— is not a *representation* split here: there is one exact integer, and `+`
already does what `+'` does. `+'` still exists and still works, because JVM
code calls it.

What the split does survive as is a reporting convention, described next.

## Ratios

Division of integers that doesn't divide evenly yields an exact ratio, and
ratio arithmetic stays exact until a double enters:

```clojure
1/3                  ;=> 1/3
(+ 1/3 1/6)          ;=> 1/2
(/ 3 2)              ;=> 3/2
(+ 1/3 0.5)          ;=> 0.8333333333333333
(numerator 3/4)      ;=> 3
(denominator 3/4)    ;=> 4
```

This matches JVM Clojure's `clojure.lang.Ratio` behavior — jolt ratios *are*
reported as `clojure.lang.Ratio`.

## The JVM-shaped class surface

The host representation is Chez's; the class surface is Clojure's, so code
that dispatches on `(class x)` or prints values keeps working:

```clojure
(class 42)                       ;=> java.lang.Long
(class (inc Long/MAX_VALUE))     ;=> clojure.lang.BigInt
(class 1.5)                      ;=> java.lang.Double
(class 1.5M)                     ;=> java.math.BigDecimal
(class 3/4)                      ;=> clojure.lang.Ratio
```

The integer answers are derived from its **magnitude**, not from a stored tag:
an exact integer inside the `long` range reports `java.lang.Long`, one outside
it reports `clojure.lang.BigInt`, and printing follows the same rule — the `N`
suffix appears exactly where the JVM would print one. That gets JVM code the
class and the rendering it expects without a second integer type existing.

The consequence is that the reported class follows the value rather than its
history. A JVM `BigInt` stays a `BigInt` when arithmetic brings it back into
range; here it is a `Long` again:

```clojure
(class (dec (inc Long/MAX_VALUE)))   ;=> java.lang.Long      (JVM: clojure.lang.BigInt)
(class 10N)                          ;=> java.lang.Long      (JVM: clojure.lang.BigInt)
2r1010N                              ;=> 10                  (JVM: reader error)
```

An `N` suffix therefore asks for nothing the value does not already have: it
is accepted, and it is a no-op. Values and arithmetic agree with the JVM
throughout — `(= 10 10N)` and `(= (inc Long/MAX_VALUE) 9223372036854775808)`
are both true — only the class of a small `N` literal differs, and jolt's
divergence registry carries these rows under `:integer-box-model` and
`:reader-model`. A faithfully narrow box is not worth its cost: it would have
to crash raw compiled `(+ …)` or de-optimize all arithmetic.

`M` literals are decimal (`BigDecimal`) as on the JVM. `##Inf`, `##-Inf`,
`##NaN` read and print with JVM spelling, and `Math`-shaped operations keep
JVM results (`(Math/sqrt -1)` is `##NaN`, not an error).

## Lowering opts out per-site, not globally

The `^long`/`^double` hints drive `fl*`/`fx*` lowering — unchecked machine
ops with coercion at the boundaries — as specified in RFC 0004 and RFC 0007.
The hint is a contract on that one function; every unhinted call keeps
full-tower semantics. There is no build mode in which the tower silently
shrinks.

## Complex numbers

Chez's tower extends below what Clojure has: it carries exact and inexact
complex numbers natively. jolt does not surface them first-class — no reader
syntax, no `complex?` — but they are the layer underneath, reachable through
the raw host seam (`jolt.scheme`) where Chez procedures and their results
cross untouched:

```clojure
(require '[jolt.scheme :as s])
(s/call "sqrt" -1)              ;=> 0+1i
(s/call "make-rectangular" 1 2) ;=> 1+2i
```

Such a value reports `java.lang.Number` and has no Clojure-level arithmetic of
its own; it is a host value passing through. First-class complex support is
deliberately not promised here — exposing more of the host tower is future
work on that seam (RFC 0010).

## Why this shape

JVM Clojure's tower is an emulation layered over JVM primitives: `Long` with
promotion rules into `BigInteger`, a `Ratio` class, boxes for each. jolt's is
the host's own representation with a Clojure-compatible veneer, so exactness
is the default rather than a promotion path, and the parity surface (classes,
printing, promotion, literals) is exactly the part JVM code can observe. That
is the split: representation follows the host, contract follows Clojure.

## Related

RFC 0004 (type hints) and RFC 0007 (compilation modes) — the opt-in lowering;
RFC 0010 (portable Scheme layer) — what a second host must provide to keep
this tower honest.
