A Jolt library is ordinary Clojure source resolved through `deps.edn`. There is no separate packaging step — point a project at a git or local dependency and Jolt prepends its source directories to the load path.

## Project layout

A library is a directory with a `deps.edn` and Clojure source under `:paths` (default `["src"]`):

```
my-lib/
  deps.edn
  src/
    my_lib/
      core.clj
```

Namespaces map to files the usual way: dots become directories and dashes become underscores, so `my-lib.core` lives at `src/my_lib/core.clj`. Jolt tries `<ns>.clj` then `<ns>.cljc`.

## Depending on a library

In a consuming project's `deps.edn`:

```clojure
{:paths ["src"]
 :deps {weavejester/medley {:git/url "https://github.com/weavejester/medley"
                            :git/sha "<full-sha>"}
        my/helpers          {:local/root "../helpers"}}}
```

Then run it:

```bash
bin/joltc run -m myapp.main
```

### Git dependencies

`{:git/url … :git/sha …}` — use a **full** SHA (`git fetch` can't resolve a short one). An optional `:deps/root` selects a subdirectory of the repo. Transitive dependencies from each library's own `deps.edn` are resolved too. Resolution is breadth-first, so a top-level coordinate always wins over a transitive one for the same library.

Git clones land in a global, sha-immutable cache shared across projects — `$JOLT_GITLIBS`, else `~/.jolt/gitlibs`.

### Local dependencies

`{:local/root "../path"}` points at a sibling checkout — handy while developing two libraries together.

## Aliases and tasks

Aliases add extra paths, deps, and main options:

```clojure
{:aliases {:dev  {:extra-paths ["dev"]
                  :extra-deps  {…}}
           :test {:main-opts ["-m" "my-lib.test-runner"]}}}
```

`:extra-paths`/`:extra-deps` accumulate across selected aliases (`-A:dev:test`); `:main-opts` is last-wins and runs via `-M:alias`.

Tasks are named shell commands or Jolt invocations:

```clojure
{:tasks {clean "rm -rf target"
         test  {:main-opts ["-m" "my-lib.test-runner"]}}}
```

Run one with `bin/joltc <taskname>`.

## What works as a dependency

- **git deps** — `{:git/url … :git/sha …}`, with optional `:deps/root`.
- **local deps** — `{:local/root "../path"}`.
- **aliases** — `:extra-paths`, `:extra-deps`, `:main-opts`.
- **tasks** — string (shell) or map (Jolt) entries.

What doesn't:

- **No Maven.** `:mvn/version` deps are skipped with a warning — git and local only. Vendor a Maven-only dependency as a git repo if you need it.
- **Pure `clj`/`cljc` only.** A library that needs the JVM (Java interop, host classes) or a `clojure.core` feature Jolt doesn't implement will fail to load, or fail at the call site. Coverage is per-function, so a namespace can load with most functions working and a few not.

If a library reaches for a Java class, you can often supply it from Clojure — see [Host Interop](/docs/host-interop.html) for registering your own host-class shims from a library, the same mechanism Jolt's own libraries use to run unmodified Clojure code.
