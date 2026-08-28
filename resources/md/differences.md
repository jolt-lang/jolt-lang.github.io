Jolt targets Clojure semantics but runs on Scheme, not the JVM. Most portable Clojure runs unchanged: persistent collections (32-way-trie vectors, HAMT maps/sets, RRB vectors), the numeric tower (exact integers, bignums, ratios, doubles, and `BigDecimal` with `M` literals and `with-precision`), lazy and infinite sequences, transducers, destructuring, multimethods with hierarchies, protocols and records, metadata, namespaces, atoms, refs and software transactional memory (`ref`/`dosync`/`alter`/`commute`/`ref-set`/`ensure`), `future`/`promise`/`agent`/`pmap`, `clojure.core.async`, and runtime `eval`/`load-string`/`defmacro` all behave as on the JVM. `=` is category-aware (`(= 3 3.0)` ⇒ `false`) and `==` is value-equality, exactly as in Clojure.

The sections below are the genuine divergences, where the absence of the JVM actually shows through. Every example on this page is checked against reference JVM Clojure; the machine-readable list the conformance suite gates against lives in [`test/conformance/known-divergences.edn`](https://github.com/jolt-lang/jolt/blob/main/test/conformance/known-divergences.edn), which is the authority when this page and it disagree.

## No JVM, no Java interop

This is the difference that matters. There is no JVM underneath, so there is no general Java interop, no reflection, and no `gen-class`/`proxy`. Interop syntax (`Class.`, `Class/staticMethod`, `.method`) resolves only against a shimmed subset of the `java.*` standard library; a class token is a name, not a loaded class. See [Host Interop](/docs/host-interop.html) for what's covered, how to register a class Jolt doesn't ship, and how to add a method to one it only part-shims.

To call C libraries directly (the way the SQLite/Postgres and HTTP-client libraries work), Jolt has a foreign-function interface. See [Native Interop](/docs/native-interop.html).

## The `java.*` shims are not the JVM

A shimmed class implements its JVM counterpart's *API*, in Scheme. It does not bring the JVM's runtime along with it, so a shim can behave correctly call-for-call while everything around it follows Scheme's rules rather than the JVM's. When something surprising happens, check whether the explanation you have in mind is a JVM-runtime explanation:

- **Process lifetime.** A `java.lang.Thread` is a real OS thread over Chez's `fork-thread`, but it does not keep the process alive: when the main thread finishes, the process exits and a still-running thread is dropped. On the JVM a non-daemon thread would hold the process open. `.setDaemon` is accepted and ignored, since every thread already behaves as a daemon.
- **No classloaders.** Nothing loads classes at runtime. `Class/forName` answers only for names the runtime already shims and throws for anything else, and there is no class path scanning or service-loader discovery.
- **No JVM memory model to tune.** Garbage collection is Chez's. JVM heap flags, GC ergonomics, and reference/finalizer semantics have no counterpart.
- **No stack traces from the thread.** `(.getStackTrace (Thread/currentThread))` is empty, because guaranteed tail calls mean there is no JVM-shaped frame stack to walk. Exceptions still carry their own traces.
- **Reflection answers about the model, not a class file.** `(.getMethods String)` is `0` where the JVM reports 95, and `(supers String)` lists 3 ancestors rather than 6: Jolt reports what its registries know a class declares, and a class whose methods are implemented as a Scheme `cond` has nothing to enumerate.

## Numbers

Jolt has one integer type. The narrowing constructors are accepted and return the same value, so the *class* collapses:

```clojure
(class (byte 5))     ;; Jolt: java.lang.Long   JVM: java.lang.Byte
(class (int 5))      ;; Jolt: java.lang.Long   JVM: java.lang.Integer
(class (bigint 21))  ;; Jolt: java.lang.Long   JVM: clojure.lang.BigInt
(instance? Float 1.5) ;; Jolt: true            JVM: false
```

Arithmetic values agree; only the box type differs. Code that dispatches on `(class n)` or on `instance?` for a numeric type is the code that notices.

Two related edges: `(format "%x" -1)` prints `"ff"` — the narrowest width that holds the value — where the JVM pads to a 64-bit `"ffffffffffffffff"`; and `(double \a)` returns `97.0` where the JVM throws `ClassCastException`.

## Sequences and collections

Every lazy sequence is one type. Clojure's specialised seq classes are not modelled separately:

```clojure
(class (repeat 3 1))    ;; Jolt: clojure.lang.LazySeq  JVM: clojure.lang.Repeat
(class (cycle [1]))     ;; Jolt: clojure.lang.LazySeq  JVM: clojure.lang.Cycle
(class (drop 1 [1 2 3])) ;; Jolt: clojure.lang.LazySeq JVM: …PersistentVector$ChunkedSeq
```

`range`, `iterate` and `map` agree with the JVM. Chunking differs: building a `mapcat` or `dedupe` sequence realizes nothing on Jolt, where the JVM forces the first 32-element chunk just to construct it. Code that depends on chunk-boundary side effects sees a different number of them.

## Codepoint strings

Strings are Chez strings: codepoint-indexed, with no UTF-16 surrogate pairs. `count`, `seq`, `subs`, and `nth` all index codepoints, so `(count "😀")` is 1 where the JVM answers 2, and `subs` never splits a character in half. Only code doing UTF-16 unit arithmetic notices the difference.

## Regex engine

Patterns compile through [irregex](https://github.com/ashinn/irregex) (vendored), not `java.util.regex`. `re-find`, `re-matches`, `re-seq`, the `clojure.string` regex functions, and `#"…"` literals all work for common patterns, but Java-specific regex features can differ at the edges.

## The reader

Reader conditionals (`#?(…)`) are read by default; on the JVM they need `{:read-cond :allow}` and throw otherwise. `:bb` is matched ahead of `:clj`, which is how a library ships a Jolt-specific branch — see [Cljc Interop](/docs/cljc-interop.html).

Three more reader differences, all in the direction of reading something the JVM refuses:

```clojure
(read-string "")           ;; Jolt: nil        JVM: throws (EOF)
(read-string "2r1010N")    ;; Jolt: 10         JVM: NumberFormatException
(read-string "#foo/bar 5") ;; Jolt: #foo/bar 5 JVM: throws (no reader fn)
```

An unregistered tag reads as an inert tagged literal rather than failing, so a file using a tag you have not registered loads and only fails if something looks inside the value.

Quoted lists carry no source position: `(meta '(foo))` is `nil` where the JVM answers `{:line … :column …}`. The reader's position metadata is stripped from quoted lists so a quoted form is a constant.

## Namespaces and vars

**`^:private` is not enforced across namespaces.** A private var is readable from another namespace; `ns-publics` still excludes it, so tooling that lists a namespace's public API is correct, but the compiler will not stop a caller:

```clojure
(ns other) (def ^:private secret 42)
(ns mine)  other/secret   ;; Jolt: 42   JVM: compile error, "var: other/secret is not public"
```

Treat privacy as documentation on Jolt. Code that *relies* on the JVM refusing is the code to watch, and a library that reaches into another's private var will keep working here and break when run on the JVM.

`ns-aliases` takes no argument here and one on the JVM.

## Concurrency

Beyond daemon threads (above), the one to know is that **`compare-and-set!` compares by value, not by reference identity**:

```clojure
(let [a (atom [1])] [(compare-and-set! a [1] [2]) @a])
;; Jolt: [true [2]]    JVM: [false [1]]
```

On the JVM the old value must be the *same object*; on Jolt an equal value is enough. A CAS loop written the ordinary way — read, compute, `compare-and-set!` the value you read — behaves identically. Code relying on identity to detect that a value was replaced by an equal one does not.

## Printing

Jolt renders a lazy sequence by its contents, which is friendlier and deliberately not the JVM's answer:

```clojure
(str (map inc [1 2]))
;; Jolt: "(2 3)"    JVM: "clojure.lang.LazySeq@402"
```

Records print as `user.Rc{:a 1}` rather than `user.Rc@<hash>`. Anything that parses the JVM's `Class@hash` rendering will not find it.

## Where Jolt is more permissive

Several operations with one reasonable meaning succeed here and throw on the JVM. This is a superset: portable JVM code behaves identically, and only code that *depends on the throw* differs.

```clojure
(distinct #{1 2 3})                    ;; Jolt: (1 3 2)  JVM: UnsupportedOperationException
(keys [[:a 1] [:b 2]])                 ;; Jolt: (:a :b)  JVM: ClassCastException
(empty (first {:a 1}))                 ;; Jolt: []       JVM: nil
(.-value {:value 41})                  ;; Jolt: 41       JVM: IllegalArgumentException
(clojure.string/replace "abc" \b "XX") ;; Jolt: "aXXc"   JVM: ClassCastException
(with-precision 3 :HALF_UP (/ 1M 3))   ;; Jolt: 0.333M   JVM: compile error
(unchecked-add)                        ;; Jolt: 0        JVM: arity error
```

`(.-field m)` reading a map as an object is the one worth calling out: it makes a map interchangeable with a record for field access, and it will not port back.

On the I/O side, `line-seq` and `file-seq` are duck-typed and accept a path string, `with-open` closes anything carrying a `:close` function, and `spit` is atomic — a failure leaves the original file intact, which is stronger than the JVM's guarantee. `(instance? java.io.BufferedReader (java.io.StringReader. "x"))` is `true` here and `false` on the JVM, because a reader and a buffered reader are one shim.

## Dependencies: `org.clojure/clojure` is terminal

`org.clojure/clojure` contributes no transitive dependencies in Jolt, so a project that declares only Clojure does **not** get `spec.alpha` and `core.specs.alpha` along with it. Requiring `clojure.spec.alpha` then fails with a message that reads like a coverage gap rather than a missing coordinate:

```
Could not locate clojure/spec/alpha.jolt (or .clj/.cljc) on the source roots
```

Declare them explicitly:

```clojure
{:deps {org.clojure/clojure          {:mvn/version "1.12.0"}
        org.clojure/spec.alpha       {:mvn/version "0.5.238"}
        org.clojure/core.specs.alpha {:mvn/version "0.4.74"}}}
```

The same applies to `org.clojure/clojurescript`.

## Coverage

`clojure.core` is implemented function by function against a conformance corpus whose expected values come from reference JVM Clojure. Coverage is broad but not total: a namespace can load with most functions working and a few not yet implemented. The per-function coverage lives in the [language specification](/docs/spec/README.html).

## The `.jolt` extension

A namespace's source can be `foo.jolt` as well as `foo.clj` or `foo.cljc`, and the three are the same language; the reader, analyzer, and emitter never look at the extension. `.jolt` is a marker for readers and tooling: it says the file uses jolt-specific interop and is not portable Clojure. It resolves first, so a library can ship a portable `foo.cljc` next to a `foo.jolt` that wins on Jolt, the way `.clj` wins over `.cljc` on the JVM. `data_readers.jolt` works like `data_readers.clj` too. See [Cljc Interop](/docs/cljc-interop.html).
