Jolt targets Clojure semantics but runs on Chez Scheme, not the JVM. Most portable Clojure runs unchanged: persistent collections (32-way-trie vectors, HAMT maps/sets), the numeric tower (exact integers, bignums, ratios, doubles), lazy and infinite sequences, transducers, destructuring, multimethods with hierarchies, protocols and records, metadata, namespaces, atoms, refs and software transactional memory (`ref`/`dosync`/`alter`/`commute`/`ref-set`/`ensure`), `future`/`promise`/`agent`/`pmap`, `clojure.core.async`, runtime `eval`/`load-string`/`defmacro`, and the full reader all behave as on the JVM. `=` is category-aware (`(= 3 3.0)` ⇒ `false`) and `==` is value-equality, exactly as in Clojure.

The sections below are the genuine divergences — where the absence of the JVM actually shows through.

## No JVM, no Java interop

This is the difference that matters. There is no JVM underneath, so there is no general Java interop, no reflection, and no `gen-class`/`proxy`. Interop syntax (`Class.`, `Class/staticMethod`, `.method`) resolves only against a shimmed subset of the `java.*` standard library; a class token is a name, not a loaded class. See [Host Interop](/docs/host-interop.html) for what's covered and how to register your own host classes.

To call C libraries directly — the way the SQLite/Postgres and HTTP-client libraries work — Jolt has a foreign-function interface. See [Native Interop](/docs/native-interop.html).

## No BigDecimal

The numeric tower otherwise matches the JVM, but there is no `BigDecimal`: `decimal?` is always false, and there is no `M`-suffixed literal. Exact integers, bignums, ratios (`(/ 1 2)` ⇒ `1/2`), and doubles all work as expected.

## Regex engine

Patterns compile through [irregex](https://github.com/ashinn/irregex) (vendored), not `java.util.regex`. `re-find`, `re-matches`, `re-seq`, the `clojure.string` regex functions, and `#"…"` literals all work for common patterns, but Java-specific regex features can differ at the edges.

## Coverage

`clojure.core` is implemented function by function against a conformance corpus whose expected values come from reference JVM Clojure. Coverage is broad but not total: a namespace can load with most functions working and a few not yet implemented. The per-function coverage lives in the [language specification](/docs/spec/README.html).
