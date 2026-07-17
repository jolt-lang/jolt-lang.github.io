`jolt.deps` is the dependency front-end: it resolves `deps.edn` coordinates
into source roots the loader searches. The CLI runs it for you on every
`joltc run`/`repl`/`build` (see [Building & Running](/docs/building-and-deps.html));
this page covers the programmatic API — chiefly `add-deps`, which resolves
dependencies from inside a running script.

## add-deps

`jolt.deps/add-deps` mirrors `babashka.deps/add-deps`: pass a map with a
`:deps` entry, and the resolved source roots are appended to the loader so a
following `require` finds them. It's how a single-file script declares its
dependencies without a `deps.edn`:

```clojure
(jolt.deps/add-deps
 '{:deps {org.clojure/data.json {:mvn/version "2.5.0"}}})

(require '[clojure.data.json :as json])
(json/write-str {:ok true})   ; => "{\"ok\":true}"
```

All coordinate types work: `:mvn/version` (the JAR's Clojure source, fetched
from Clojars then Maven Central), `:git/url` + `:git/sha`, and `:local/root`
(resolved against the project directory). Transitive dependencies resolve too.

A script meant to also run on babashka or the JVM can guard on the
`jolt.version` system property — the same shape as babashka's
`babashka.version` idiom:

```clojure
(when (System/getProperty "jolt.version")
  ((requiring-resolve 'jolt.deps/add-deps)
   '{:deps {org.clojure/data.json {:mvn/version "2.5.0"}}}))

(ns main (:require [clojure.data.json :as json]))
```

On jolt the property is always set (so the deps are added); elsewhere it's
absent and the form is skipped. Jolt-only scripts can test `*jolt-version*`
instead — it's always bound, never nil.

Notes:

- New roots are appended **after** the current roots, so an added dep can
  never shadow a namespace the runtime already resolves. The return value is
  the vector of roots actually added (empty when everything was already
  known).
- A second options-map argument is accepted for babashka call-shape
  compatibility; no options are currently honored.
- `:jolt/native` declarations carried by added deps are **not** auto-loaded —
  native libraries are a project-launch concern. A warning names them so the
  caller can load them via `jolt.ffi`.
- An `:mvn/local-repo` key at the top of the map relocates the Maven
  repository for that call, like the `deps.edn` key.

## Caches

Resolution reuses what the JVM toolchain already has, in both directions:

- **Maven** — JARs live at their standard path in the local Maven repository
  (`~/.m2/repository`): an artifact `clj` already fetched is reused without a
  download, and one jolt fetches is there for `clj`. Jolt's source extraction
  sits beside the jar in a `<artifact>-<version>.jar.jolt/` directory.
  `:mvn/local-repo` in `deps.edn` relocates the repository like tools.deps;
  the `JOLT_LOCAL_REPO` environment variable overrides it.
- **git** — an existing tools.gitlibs checkout (`$GITLIBS`, else `~/.gitlibs`)
  is reused when present; otherwise clones land in a sha-immutable cache
  (`$JOLT_GITLIBS`, else `~/.jolt/gitlibs`) shared across projects.

## The rest of the namespace

`resolve-project` reads a project's `deps.edn` and returns its resolved roots,
native-library declarations, and build options — the CLI calls it before every
run. See [deps.edn Internals](/docs/tools-deps.html) for the resolution design
(breadth-first walk, top-level pins win, aliases, tasks).
