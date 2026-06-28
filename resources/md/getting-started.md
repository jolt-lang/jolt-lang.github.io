Jolt is a Clojure implementation that runs on [Chez Scheme](https://cisco.github.io/ChezScheme/). It reads Clojure source, analyzes it to a host-neutral IR, emits Scheme, and runs it on Chez. The compiler is self-hosted — written in Clojure and compiling itself — and ships a Clojure-compatible standard library.

## Requirements

The only requirement is Chez Scheme, invoked as `chez`. There is no JVM and no build step. The conformance test suite additionally uses Clojure on the JVM as an oracle, but running Jolt does not.

## Install and run

The bootstrap seed is checked in, so a fresh clone runs immediately:

```bash
git clone --recurse-submodules https://github.com/jolt-lang/jolt.git
cd jolt
bin/joltc -e '(+ 1 2)'        # => 3
```

The submodules pull the vendored regex and SCI sources used by the tests.

## Evaluating code

`bin/joltc -e EXPR` evaluates a Clojure expression and prints the result:

```bash
$ bin/joltc -e '(->> (range 10) (filter even?) (map (fn [x] (* x x))) (reduce +))'
120
$ bin/joltc -e '(/ 1 2)'
1/2
```

## Running a project

`bin/joltc` is both the runtime and the dependency front-end. Point it at a file, a namespace, or a `deps.edn` project:

```bash
bin/joltc run FILE          # run a Clojure file
bin/joltc run -m myapp.core # resolve deps.edn, load the ns, call -main
bin/joltc -M:alias [args]   # run an alias's :main-opts
bin/joltc path              # print the resolved source roots
```

You can also point Jolt at a directory of Clojure source with no dependency machinery, using `JOLT_PATH` (a colon-separated list of directories, like a classpath):

```bash
JOLT_PATH=/path/to/lib/src bin/joltc run myfile.clj
```

## Compiling a standalone binary

`bin/joltc build` ahead-of-time compiles a project into a single self-contained executable. The runtime, `clojure.core`, the standard library, and your application — together with its `deps.edn` dependencies — are linked in, so the result needs no Chez install, no JVM, and no source on disk to run.

```bash
bin/joltc build -m myapp.core -o myapp   # compile myapp.core's -main into ./myapp
./myapp arg1 arg2                        # runs anywhere; args reach -main
```

Three build modes trade dynamism for speed:

- default (**release**) — a closed-world binary on the proven code generator.
- `--opt` (**optimized**) — also runs the type-inference, inlining, and scalar-replacement passes over the closed-world program.
- `--dev` — an unoptimized build.

### Closed-world optimizations

Two opt-in flags trade runtime dynamism for speed and a smaller binary. They commit to a *closed world* — the program is known in full at build time and isn't extended at runtime — so they are off by default; a plain build stays fully dynamic.

- `--direct-link` — a call between your own functions binds to its target directly, skipping the var lookup and generic dispatch a dynamic call pays. The cost is that those vars can no longer be redefined at runtime (mark one `^:redef` or `^:dynamic` to keep it dynamic). On a call-heavy workload this is several times faster.
- `--tree-shake` — ship only the code reachable from `-main`. The build walks the call graph across your app, its libraries, **and** `clojure.core`, and drops every function that nothing on the path to `-main` can reach. An app that never `eval`s also drops the compiler (analyzer + back end) from the binary.

You can set either in `deps.edn` instead of on the command line:

```clojure
{:jolt/build {:direct-link true :tree-shake true}}
```

Tree-shaking typically removes 1–2 MB. A small app pulls in a handful of `clojure.core` functions and a few functions per library, but a normal build bakes in all of them; tree-shaking keeps only what runs. For example, `malli-app` goes from ~10.0 MB to ~8.1 MB with identical behavior.

### When tree-shaking can't help: runtime resolution

Tree-shaking is **sound** — it never drops code that could actually run. To stay sound it has to give up on any program whose reachable code looks a function up *by name at runtime*: `eval`, `resolve`, `ns-resolve`, `requiring-resolve`, `find-var`, `load-string`, and friends. A static call graph can't see where those land, so if any reachable code uses one, the build keeps everything and tells you why:

```
jolt build: tree-shake skipped (reachable code resolves vars at runtime):
  selmer.filters/generate-json -> clojure.core/resolve
  clojure.tools.logging/call-str -> clojure.core/ns-resolve
```

This is almost always a **library**, not your code. Mature Clojure libraries use `resolve` for plugin systems and optional integrations — a logging backend chosen at runtime, a template filter that lazily loads an optional JSON or pretty-printing dependency. On the JVM that flexibility is free; in a closed-world binary it blocks the analysis.

To make an app tree-shakeable, remove runtime resolution from the *reachable* path:

- A library that resolves a backend or factory that is fixed on Jolt can reference it directly. (The Jolt `tools.logging` port does this — it has one native backend, so it dropped the JVM's dynamic factory selection.)
- An optional integration (a Selmer `|json` filter resolving a JSON library you don't use) keeps the whole binary unshakeable even when the feature is never invoked; dropping or hard-wiring it on Jolt frees the analysis.

Unreached code that uses `resolve` is fine — it's shaken away like anything else and never triggers the bail. Only resolution on the live path matters.

## REPL and editor integration

```bash
bin/joltc repl                  # a line REPL with the project deps loaded
bin/joltc --nrepl-server [port] # an nREPL server (default port 7888)
```

`--nrepl-server` writes a `.nrepl-port` file and auto-resolves a `deps.edn` in the directory, so the server starts with your app and its dependencies loaded. Connect CIDER, Calva, or Cursive to the port. See [REPL-Driven Development](/docs/repl-driven-development.html) for the full workflow.

## Dependencies

`bin/joltc` reads a `deps.edn` in the current directory, fetches its dependencies, and prepends the resolved source directories to the load path for the run. Git and local dependencies are supported (no Maven):

```clojure
{:paths ["src"]
 :deps {weavejester/medley {:git/url "https://github.com/weavejester/medley"
                            :git/sha "<full-sha>"}
        my/helpers          {:local/root "../helpers"}}}
```

```bash
bin/joltc run -m myapp.main
```

See [Writing Libraries](/docs/writing-libraries.html) for the full `deps.edn` surface (aliases, tasks, transitive deps), and [Supported Libraries](/docs/libraries.html) for Clojure libraries known to work.
