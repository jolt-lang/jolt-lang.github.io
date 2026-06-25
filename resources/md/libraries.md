These Clojure libraries are confirmed to load and pass their conformance checks on Jolt. Most load unmodified from git; a few rely on host shims Jolt provides, and some need `JOLT_FEATURES` including `clj` (noted below). See the [examples repository](https://github.com/jolt-lang/examples) for runnable projects.

## Web and routing

- **[ring-core](https://github.com/ring-clojure/ring)** — via `:deps/root "ring-core"`, on the [ring-app example](https://github.com/jolt-lang/examples/tree/main/ring-app).
- **[ring-codec](https://github.com/ring-clojure/ring-codec)** — URL/form encoding.
- **[reitit-core](https://github.com/metosin/reitit)** — data-driven routing; the `reitit.Trie` Java class is mirrored by [jolt-lang/router](https://github.com/jolt-lang/router). `JOLT_FEATURES` `clj`.
- **[integrant](https://github.com/weavejester/integrant)** — data-driven system configuration (`#ig/ref`), with its [dependency](https://github.com/weavejester/dependency) and [meta-merge](https://github.com/weavejester/meta-merge) deps.

## Data and schemas

- **[malli](https://github.com/metosin/malli)** — data schema validation, on the [malli-app example](https://github.com/jolt-lang/examples/tree/main/malli-app). `JOLT_FEATURES` `clj`.
- **[honeysql](https://github.com/seancorfield/honeysql)** — SQL formatter and helpers.
- **[clojure.data.json](https://github.com/clojure/data.json)** — JSON reading and writing.
- **[clojure.spec.alpha](https://github.com/clojure/spec.alpha)** — data specs.
- **[core.match](https://github.com/clojure/core.match)** — pattern matching. `JOLT_FEATURES` `clj`.
- **[transit-jolt](https://github.com/jolt-lang/transit-jolt)** — Transit (JSON) read/write.
- **[medley](https://github.com/weavejester/medley)** — collection utilities.
- **[config](https://github.com/yogthos/config)** — environment configuration.
- **[aero](https://github.com/juxt/aero)** — EDN configuration with tag literals (`#ref`/`#env`/`#or`/`#profile`/`#long`/…).

## Databases

- **[clojure.jdbc](https://github.com/yogthos/clojure.jdbc)** — as [jolt-lang/db](https://github.com/jolt-lang/db)'s `jdbc.core`, over built-in SQLite access (libsqlite3 through Chez's FFI).
- **[next.jdbc](https://github.com/seancorfield/next-jdbc)** — a compatibility layer in [jolt-lang/db](https://github.com/jolt-lang/db) over `jdbc.core`.
- **[migratus](https://github.com/yogthos/migratus)** — database migrations over the next.jdbc layer.

## Templating, markup, text

- **[Selmer](https://github.com/yogthos/Selmer)** — Django-style templates.
- **[hiccup](https://github.com/weavejester/hiccup)** — HTML from Clojure data, on the [hiccup-app example](https://github.com/jolt-lang/examples/tree/main/hiccup-app).
- **[markdown-clj](https://github.com/yogthos/markdown-clj)** — Markdown to HTML, on the [markdown-app example](https://github.com/jolt-lang/examples/tree/main/markdown-app).
- **[cuerdas](https://github.com/funcool/cuerdas)** — string manipulation.

## Date and time

- **[tick](https://github.com/juxt/tick)** — date/time over Jolt's `java.time`; `#time/…` literals via `time-literals`. `JOLT_FEATURES` `clj`.

## Logging

- **[tools.logging](https://github.com/clojure/tools.logging)** — runs verbatim over a native `clojure.tools.logging.impl` stderr backend.

---

Loading a library is per-function: a namespace can load with most functions working and a few not. If something is missing, it's usually a `clojure.core` function Jolt doesn't implement yet or a Java class to shim — see [Host Interop](/docs/host-interop.html).
