Jolt targets Clojure semantics but runs on Chez Scheme, not the JVM. Most portable Clojure runs unchanged; the differences below are where the host shows through.

## Host platform

There is no JVM, no reflection, and no `gen-class`/`proxy` of Java classes. Interop syntax (`Class.`, `Class/staticMethod`, `.method`) works against a shimmed subset of the `java.*` standard library, and a class token resolves to a name. See [Host Interop](/docs/host-interop.html) for what's covered and how to register your own host classes.

## Numbers

Jolt carries the full Scheme numeric tower, matching the JVM:

- exact integers and bignums
- exact ratios — `(/ 1 2)` ⇒ `1/2`
- flonum doubles

`=` is category-aware (`(= 3 3.0)` ⇒ `false`); `==` is value-equality (`(== 3 3.0)` ⇒ `true`). `integer?`/`int?` test exact integers, `float?`/`double?` test flonums, and `ratio?` tests an exact non-integer. There is no `BigDecimal` (`decimal?` is always false).

## Concurrency

`future`/`promise`/`agent`/`pmap` run on real OS threads over a **shared heap**, matching JVM semantics — not isolated-heap snapshots. Atoms use a per-atom mutex with JVM-style compare-and-set. There is no STM (refs/`dosync`).

`clojure.core.async` provides blocking channels and `go`/`<!`/`>!`/`alts!`/`timeout`.

## Collections

Immutable persistent vectors (32-way tries), cons lists, and HAMT maps/sets, with Clojure value semantics. Hash-map and hash-set iteration order is unspecified — use `sorted-map`/`sorted-set` when order matters. Transients are real mutable scratch collections.

## Regex

Backed by [irregex](https://github.com/ashinn/irregex) (vendored). PCRE/Java-style patterns work through `re-find`, `re-matches`, `re-seq`, and the `clojure.string` regex functions, plus `#"…"` literals.

## What's supported

Lazy and infinite sequences, transducers, destructuring, multimethods with hierarchies, protocols and records (`deftype`/`defrecord`/`reify`/`extend-protocol`), metadata, namespaces, runtime `eval`/`load-string`/`defmacro`, and the full reader (`#()`, `#_`, `#?`, tagged literals, `#"…"`).

The conformance corpus — a host-neutral language spec whose expected values come from reference JVM Clojure — is the contract Jolt is held to. The per-function coverage of `clojure.core` lives in the [project's spec](https://github.com/jolt-lang/jolt/tree/main/docs/spec).
