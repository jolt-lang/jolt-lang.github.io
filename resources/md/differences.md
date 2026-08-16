Jolt targets Clojure semantics but runs on Scheme, not the JVM. Most portable Clojure runs unchanged: persistent collections (32-way-trie vectors, HAMT maps/sets, RRB vectors), the numeric tower (exact integers, bignums, ratios, doubles, and `BigDecimal` with `M` literals and `with-precision`), lazy and infinite sequences, transducers, destructuring, multimethods with hierarchies, protocols and records, metadata, namespaces, atoms, refs and software transactional memory (`ref`/`dosync`/`alter`/`commute`/`ref-set`/`ensure`), `future`/`promise`/`agent`/`pmap`, `clojure.core.async`, runtime `eval`/`load-string`/`defmacro`, and the full reader all behave as on the JVM. `=` is category-aware (`(= 3 3.0)` ⇒ `false`) and `==` is value-equality, exactly as in Clojure.

The sections below are the genuine divergences — where the absence of the JVM actually shows through.

## No JVM, no Java interop

This is the difference that matters. There is no JVM underneath, so there is no general Java interop, no reflection, and no `gen-class`/`proxy`. Interop syntax (`Class.`, `Class/staticMethod`, `.method`) resolves only against a shimmed subset of the `java.*` standard library; a class token is a name, not a loaded class. See [Host Interop](/docs/host-interop.html) for what's covered and how to register your own host classes.

To call C libraries directly — the way the SQLite/Postgres and HTTP-client libraries work — Jolt has a foreign-function interface. See [Native Interop](/docs/native-interop.html).

## The `java.*` shims are not the JVM

A shimmed class implements its JVM counterpart's *API*, in Scheme. It does not bring the JVM's runtime along with it, so a shim can behave correctly call-for-call while everything around it follows Scheme's rules rather than the JVM's. When something surprising happens, check whether the explanation you have in mind is a JVM-runtime explanation:

- **Process lifetime.** A `java.lang.Thread` is a real OS thread over Chez's `fork-thread`, but it does not keep the process alive: when the main thread finishes, the process exits and a still-running thread is dropped. On the JVM a non-daemon thread would hold the process open. `.setDaemon` is accepted and ignored, since every thread already behaves as a daemon.
- **No classloaders.** Nothing loads classes at runtime. `Class/forName` answers only for names the runtime already shims and throws for anything else, and there is no class path scanning or service-loader discovery.
- **No JVM memory model to tune.** Garbage collection is Chez's. JVM heap flags, GC ergonomics, and reference/finalizer semantics have no counterpart.

## Codepoint strings

Strings are Chez strings: codepoint-indexed, with no UTF-16 surrogate pairs. `count`, `seq`, `subs`, and `nth` all index codepoints, so `(count "😀")` is 1 where the JVM answers 2, and `subs` never splits a character in half. Only code doing UTF-16 unit arithmetic notices the difference.

## Regex engine

Patterns compile through [irregex](https://github.com/ashinn/irregex) (vendored), not `java.util.regex`. `re-find`, `re-matches`, `re-seq`, the `clojure.string` regex functions, and `#"…"` literals all work for common patterns, but Java-specific regex features can differ at the edges.

## Coverage

`clojure.core` is implemented function by function against a conformance corpus whose expected values come from reference JVM Clojure. Coverage is broad but not total: a namespace can load with most functions working and a few not yet implemented. The per-function coverage lives in the [language specification](/docs/spec/README.html).

## The `.jolt` extension

A namespace's source can be `foo.jolt` as well as `foo.clj` or `foo.cljc`, and the three are the same language — the reader, analyzer, and emitter never look at the extension. `.jolt` is a marker for readers and tooling: it says the file uses jolt-specific interop and is not portable Clojure. It resolves first, so a library can ship a portable `foo.cljc` next to a `foo.jolt` that wins on Jolt, the way `.clj` wins over `.cljc` on the JVM. `data_readers.jolt` works like `data_readers.clj` too. See [Cljc Interop](/docs/cljc-interop.html).
