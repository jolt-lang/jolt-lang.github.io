# Rationale

Jolt is a drop-in replacement for JVM Clojure with fast startup and a small
memory footprint. It runs on Chez Scheme: Clojure source compiles to Scheme, and
Chez's native compiler, generational garbage collector, and compact runtime
produce a self-contained `jolt` binary that needs neither a JVM nor a Chez
install. The same runtime can be embedded as a library in C, C++, or Rust
projects, exposing a Clojure API and nREPL on top of native code.

## Why Chez Scheme?

Clojure's core semantics map directly onto Scheme. Both are Lisp-family
languages with immutable data, lexical scoping, and first-class functions, so
there is none of the impedance mismatch that comes from hosting a functional
language on the JVM's object model. Chez contributes a mature native compiler, a
generational GC well suited to the allocation patterns of immutable data, and
native binaries across a wide range of operating systems.

## The JVM Problem

The JVM is the most common reason developers give for not adopting
Clojure. Java runtimes were designed for long-running application servers that
tolerate multi-second startup and a large resident footprint. Modern services
are deployed as containers that boot in milliseconds and scale horizontally; a
warmed-up Java environment used to run one small service fits that model poorly.
Jolt keeps Clojure's semantics while dropping the runtime that makes Clojure
heavy.

## Existing Ecosystem and Java Shims

Most Clojure libraries depend on a small slice of the Java standard library,
primarily `java.io`, `java.time`, and a few other packages. Jolt shims that
surface in Scheme, so portable libraries and the familiar `deps.edn` and nREPL
workflows run unchanged. Libraries that reach deeper into the JVM are handled
case by case: some, like Reitit, are covered by shims provided by Jolt
libraries; for the rest you can register a shim yourself or bind the underlying
C library directly through the FFI.

## Conformance

Jolt's conformance suite checks the reader, special forms, and the bulk of
`clojure.core` against reference JVM Clojure. It started from the Jank team's
[clojure-test-suite](https://github.com/jank-lang/clojure-test-suite) and was
extended to cover the JVM host contract that libraries depend on. The suite
holds around 3,500 cases, each tagged portable (`:common`) or host-dependent
(`:jvm`), and a certification step re-evaluates the whole corpus against real
Clojure to catch unclassified divergences.

## Internals

Jolt mirrors nine `java.*` packages commonly used by Clojure libraries
(`java.io`, `java.lang`, `java.util`, `java.time`, `java.math`, `java.net`,
`java.nio`, `java.sql`, `java.text`), with roughly a thousand method and field
implementations. With no JVM underneath, class identity is synthesized from a
hierarchy graph that backs `instance?` and `(class x)`, and Chez runtime errors
are mapped onto the JVM exception hierarchy so `catch` dispatch works.

The shims were tested by running the actual test suites of the libraries Jolt
supports. Porting spec.alpha, core.logic, core.async, test.check, tools.reader,
rewrite-clj, and a few dozen others shook out most of the edge cases: data
readers returning code forms, namespaced-map literals, `*print-length*` and
`*print-level*`, protocol methods merging across `deftype` and `reify`, and soft
and weak references wired through Chez's weak pairs and guardians.

A minimal binary is about 13 MB without optimizations and 8 MB direct-linked.
Performance is comparable to JVM Clojure on most benchmarks: at parity or about
2x slower, and around 7x worse on a couple. When that isn't enough, you can drop
to Scheme or C FFI directly, the way JVM Clojure drops to Java.

## Why Not Wait for Leyden?

Project Leyden addresses JVM startup and memory footprint through ahead-of-time
compilation under closed-world constraints. Jolt's closed world goes further:
Chez gives proper tail calls, which the JVM cannot; values carry no Java object
header; and numeric types live in registers (NaN boxing, tagged pointers), so
tight numeric loops avoid the boxing and GC pressure that JVM Clojure needs type
hints to escape. Jolt's AOT is optional (the same code runs interpretively in a
REPL), while Leyden's compilation model is incompatible with interactive
development.

Jolt also tree-shakes on the model of the ClojureScript compiler: tracing the
call graph and dropping unreferenced namespaces and functions produces a leaner
release binary.

## Desktop and Native Interop

Running on Scheme gives direct access to the C ecosystem. Instead of wrapping a
Java UI toolkit, Jolt binds native C libraries through the FFI.
[glimmer](https://github.com/jolt-lang/glimmer) drives native components with
Reagent-style reactive atoms, [glimmer-gtk](https://github.com/jolt-lang/glimmer-gtk)
renders them as GTK widgets, and [glimmer-gl](https://github.com/jolt-lang/glimmer-gl)
adds OpenGL contexts inside GTK windows. Desktop apps end up with a far smaller
memory footprint than a JVM equivalent, and nREPL-driven development works the
same way it does for any other Jolt app.
