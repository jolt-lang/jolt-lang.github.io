Jolt is a Clojure-compatible host that runs on Scheme, not the JVM. Portable `.cljc` and `.clj` libraries load and run against Jolt's `clojure.core` and its `java.*`/`clojure.lang.*` shims, usually unchanged. When a library needs to behave differently on Jolt (because something Jolt-specific is available, or because something the JVM provides is not), Jolt offers three layered ways to override, all of which leave Clojure and every other dialect running the portable source untouched.

The thread running through all of them is the reader-conditional feature set.

## Reader conditionals: `:jolt`, `:bb`, `:clj`, `:default`

Jolt's reader-conditional feature set is `#{:jolt :bb :clj :default}`. As on Clojure, the **first clause whose feature key the platform satisfies wins; matching is by clause order, not key priority.**

Because Jolt emulates `clojure.lang.*` and `java.*`, it satisfies `:clj`, so it reads the `:clj` branch of a `.cljc` library by default (the JVM code path its host shims target) and never the `:cljs` one.

Jolt also satisfies `:bb`, exactly as babashka does (`#{:bb :clj}`): a library's `:bb` branch solves the same non-JVM problems Jolt has (no reflection, no JVM-only classes), and libraries list it ahead of `:clj` precisely so a bb-like host takes it. Code written for babashka usually does the right thing on Jolt through those branches. To give Jolt its own branch, place `:jolt` **before** `:bb` and `:clj`:

```clojure
(def backend
  #?(:jolt :scheme
     :clj  :jvm
     :cljs :js))
```

`#?@(...)` splices a sequential form into its surroundings, just as on the JVM:

```clojure
(def version
  [0 #?@(:jolt [1 2] :clj [1 2 3]) 9])
```

Two Jolt-specific details worth knowing:

- `:default` matches every platform, so it's the right key for "any Clojure" code.
- Unlike the JVM reader, Jolt resolves `#?()` in **every** file type, not just `.cljc`. A `#?(:cljs …)` form with no `:clj`/`:jolt` branch simply reads as nothing, in a `.clj` or `.jolt` file as much as a `.cljc` one. (The reader spec allows this only when an implementation documents it; Jolt does; see [reader spec S18](/docs/spec/02-reader.html#s18_reader_conditionals).)

Clause order is the whole mechanism: `:jolt` is checked before `:clj`, so a Jolt branch overrides the JVM one without affecting any other dialect.

## File precedence: `.jolt` over `.clj` over `.cljc`

When `require` resolves a namespace to a file, Jolt tries extensions in this order, first existing file wins:

1. `<ns>.jolt`
2. `<ns>.clj`
3. `<ns>.cljc`

This mirrors how `.clj` wins over `.cljc` on the JVM, extended one step: a library can ship a portable `foo.cljc` (or `foo.clj`) alongside a `foo.jolt` that Jolt loads, while every other dialect keeps using the portable file.

A `.jolt` file is **the same language** as a `.clj` file; the reader, analyzer, and emitter never look at the extension. The extension only marks intent: "this source uses Jolt-specific behavior and is not portable Clojure." Reach for it when an entire namespace needs a Jolt-specific implementation, rather than a handful of per-form branches.

This is the coarsest override (it replaces a whole namespace), so it suits a namespace whose Jolt path diverges substantially from the portable one.

## Data-reader overrides: `data_readers.jolt`

The same precedence applies to a project's `data_readers` files, tried as `data_readers.jolt`, then `data_readers.clj`, then `data_readers.cljc`; the first one found on a source root wins. Put a Jolt-specific reader literal in `data_readers.jolt` to override the portable one.

## The `clojurestar` portability convention

The reader-conditional mechanism generalizes beyond the `:clj`/`:cljs` split. Several Clojure dialects (Jolt, babashka, ClojureScript, glojure, let-go) each define their own feature key, so a library can target them all from one portable source. The `clojurestar` namespace is a small, dialect-neutral facade built on exactly that idea.

Jolt ships `clojurestar.deps` as a dialect-neutral way to add dependencies to a running process:

```clojure
(ns my-lib.repl
  (:require [clojurestar.deps :as deps]))

;; The same call works on babashka, jolt, glojure, let-go, and Clojure JVM.
(deps/add-deps {:deps {'org.clojure/core.cache {:mvn/version "1.1.234"}}})
```

The facade is a single `:require` that picks its implementation per dialect:

```clojure
(ns clojurestar.deps
  (:require
   #?(:bb   [babashka.deps :as implementation]
      :glj  [glojure.deps :as implementation]
      :jolt [jolt.deps :as implementation]
      :lg   [let-go.deps :as implementation]
      :clj  [grenadine.jvm :as implementation])))
```

Each dialect supplies its own `babashka.deps`, `jolt.deps`, and so on behind the same `add-deps` signature; on Jolt the `:jolt` branch resolves it to `jolt.deps`. A library that calls `clojurestar.deps/add-deps` then works unchanged across all of them, the same pattern you'd use for any cross-dialect abstraction: branch on the dialect key inside a `:require`, expose one common API. `add-deps` deliberately returns `nil` as its portable entry point; reach for the dialect-specific namespace (`jolt.deps`) when you need backend-specific options or results.

## Choosing an override

- **A few forms differ** → a `:jolt` branch in a `#?()` reader conditional.
- **A whole namespace differs** → a sibling `foo.jolt` that shadows `foo.clj`/`foo.cljc`.
- **A reader literal differs** → `data_readers.jolt`.

All three leave the portable source intact for Clojure and every other dialect, and all three resolve the same way: Jolt checks its own branch first, then falls back to `:clj`.

For the rare case where the feature set itself needs to change at runtime (loading a library that expects a non-standard key, for instance), `clojure.core/__reader-features-set!` replaces the active set. It is a host seam (the leading `__` marks it as Jolt-internal), so it is not portable: it exists on Jolt and not on Clojure or ClojureScript, and code that calls it must itself live behind a `:jolt` branch.
