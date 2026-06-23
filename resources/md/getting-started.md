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
- `--opt` (**optimized**) — also runs the type-inference and scalar-replacement passes over the closed-world program.
- `--dev` — an unoptimized build.

## REPL and editor integration

```bash
bin/joltc repl              # a line REPL with the project deps loaded
bin/joltc nrepl-server      # an nREPL server (default port 7888)
```

`nrepl-server` writes a `.nrepl-port` file and auto-resolves a `deps.edn` in the directory, so the server starts with your app and its dependencies loaded. Connect CIDER or Calva to the port.

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
