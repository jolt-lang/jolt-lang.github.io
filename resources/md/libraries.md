These Clojure libraries are confirmed to load and pass their conformance checks on Jolt. Most load unmodified from git; a few rely on host shims Jolt provides. See the [examples repository](https://github.com/jolt-lang/examples) for runnable projects.

## Web and routing

- **[ring-core](https://github.com/ring-clojure/ring)** — via `:deps/root "ring-core"`, on the [ring-app example](https://github.com/jolt-lang/examples/tree/main/ring-app)'s spork/http adapter.
- **[ring-codec](https://github.com/ring-clojure/ring-codec)** — URL/form encoding.
- **[reitit-core](https://github.com/metosin/reitit)** — data-driven routing; the `reitit.Trie` Java class is mirrored in Clojure by [jolt-lang/router](https://github.com/jolt-lang/router). Load with `JOLT_FEATURES` including `clj`.

## Data and schemas

- **[malli](https://github.com/metosin/malli)** — schema validation, on the [malli-app example](https://github.com/jolt-lang/examples/tree/main/malli-app). `m/validate` and `m/explain` work across the vocabulary (predicates, `:int`/`:string`/`:keyword`, nested/optional `:map`, `:vector`, `:tuple`, `:enum`, `:maybe`, `:and`/`:or`, `:re`, bounded int/string). Load with `JOLT_FEATURES` including `clj`.
- **[honeysql](https://github.com/seancorfield/honeysql)** — full SQL formatter and helpers (select/insert/update/delete/joins/`:inline`), loaded unmodified from git.
- **[medley](https://github.com/weavejester/medley)** — collection utilities.
- **[config](https://github.com/yogthos/config)** — environment configuration.

## Databases

- **[clojure.jdbc](https://github.com/yogthos/clojure.jdbc)** — as [jolt-lang/db](https://github.com/jolt-lang/db)'s `jdbc.core`, over built-in SQLite access (libsqlite3 through Chez's FFI).
- **[next.jdbc](https://github.com/seancorfield/next-jdbc)** — a compatibility layer in [jolt-lang/db](https://github.com/jolt-lang/db) (`next.jdbc`, `next.jdbc.sql`, `next.jdbc.prepare`, `next.jdbc.transaction`) over `jdbc.core`.
- **[migratus](https://github.com/yogthos/migratus)** — database migrations; loads unmodified and runs filesystem SQL/EDN migrations against SQLite through the next.jdbc layer. `migrate`/`rollback` round-trip end to end.

## Templating, markup, text

- **[Selmer](https://github.com/yogthos/Selmer)** — Django-style templates.
- **[hiccup](https://github.com/weavejester/hiccup)** — HTML from Clojure data, on the [hiccup-app example](https://github.com/jolt-lang/examples/tree/main/hiccup-app). Element tags, attribute maps, nested elements, and `for` comprehensions; its `html` macro pre-compiles the markup.
- **[markdown-clj](https://github.com/yogthos/markdown-clj)** — Markdown to HTML, on the [markdown-app example](https://github.com/jolt-lang/examples/tree/main/markdown-app). Headings, emphasis, inline code, links, lists, tables, strikethrough.
- **[cuerdas](https://github.com/funcool/cuerdas)** — string manipulation.

## Logging

- **[tools.logging](https://github.com/clojure/tools.logging)** — the real `clojure.tools.logging` source runs verbatim. Jolt provides a native `clojure.tools.logging.impl` backend (a stderr `LoggerFactory`, the library's designed extension point) plus the host shims it needs. The level macros, `logf`/`logp`, `spy`, and `enabled?` all work; output goes to stderr.

---

Loading a library is per-function: a namespace can load with most functions working and a few not. If something is missing, it's usually a `clojure.core` function Jolt doesn't implement yet or a Java class to shim — see [Host Interop](/docs/host-interop.html).
