Jolt is a Clojure implementation that runs on [Chez Scheme](https://cisco.github.io/ChezScheme/). It reads Clojure source, analyzes it to a host-neutral IR, emits Scheme, and runs it on Chez. The compiler is self-hosted — written in Clojure and compiling itself — and ships a Clojure-compatible standard library.

## Requirements

The prebuilt binaries need only the base system libraries: **Linux x86_64**
requires glibc 2.35 or newer (Ubuntu 22.04+, Debian 12+, RHEL 9+ — the
installer verifies the binary runs and reports the exact glibc mismatch if
not); **macOS arm64** requires macOS 14+. Anything else (Intel Mac,
musl/Alpine, older glibc) builds from source — see [From source](#from_source).

## Install

**If you are using an Intel Mac, musl/Alpine, or an older glibc, the prebuilt binaries below are not supported. Follow the [From source](#from_source) instructions instead.**

The quickest way is the prebuilt `joltc` binary (Linux and macOS). It bundles the runtime, compiler, and standard library, so there is no JVM, no Chez, and nothing else to install.

With Homebrew:

```bash
brew install jolt-lang/jolt/jolt
```

Or with the install script — it installs to `/usr/local/bin` by default; `--dir <dir>` and `--version <version>` override that:

```bash
curl -sL https://raw.githubusercontent.com/jolt-lang/jolt/main/install | bash
```

Then:

```bash
joltc -e '(+ 1 2)'        # => 3
```

### From source

Jolt also runs straight from a clone with no build step — the bootstrap seed is checked in. This path needs [Chez Scheme](https://cisco.github.io/ChezScheme/), invoked as `chez`:

```bash
git clone --recurse-submodules https://github.com/jolt-lang/jolt.git
cd jolt
bin/joltc -e '(+ 1 2)'        # => 3
```

The submodules pull the vendored regex and SCI sources used by the tests. (The conformance test suite additionally uses Clojure on the JVM as an oracle, but running Jolt does not.)

The examples below use `bin/joltc` from a clone; with the installed binary the command is just `joltc`.

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

## Diagnostics

Jolt has a few compile-time diagnostics beyond the default error report.

**"Did you mean?"** — when a bare symbol doesn't resolve, the error lists the closest in-scope names by edit distance (current-namespace vars, `clojure.core` publics, and lexical locals):

```bash
$ bin/joltc -e '(prinltn 1)'
Unable to resolve symbol: prinltn in this context (did you mean print, printf, println?)
```

**`JOLT_DIAG=edn`** — emit an uncaught error as a single line of valid EDN to stderr instead of the human report, so an editor or tool can read it back. The map carries the human `:message` and the source `:line`/`:column`/`:file`; an unresolved symbol also carries structured `:type`/`:symbol`/`:suggestions`/`:ns`. Default output is unchanged.

**`JOLT_CHECK`** — opt-in success-type lint ([RFC 0006](/docs/rfc)): each runtime-compiled top-level form is run through the success-type checker and any findings print to stderr as located warnings (`line:col: warning: …`), e.g. `` 1:10: warning: `+` requires a number, but argument 2 is a keyword ``. Off by default (zero cost, no behavior change); a checker error never breaks a compile.

**`JOLT_DEBUG`** — verbose dependency resolution (the fetching / using-cache / skipping progress lines that are otherwise quiet) plus the host static-shim drift warning. See [dependency resolution](#dependencies) below.

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

### Native libraries

If your app binds a C library through the FFI, a build **statically links** it into the executable by default when the `:jolt/native` spec provides a `:static` archive — so the binary runs with no shared object on the target. Pass `--dynamic` (or set `:jolt/build {:dynamic-natives true}`) to keep the runtime `load-shared-object` behavior instead. Static linking needs a C compiler at build time; the produced binary needs none. See [Native interop](native-interop.html#static_vs_dynamic_linking_in_a_built_binary) for the spec and details.

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
bin/joltc                       # no arguments starts a REPL, like bb or clj
bin/joltc repl                  # the same, explicitly
bin/joltc nrepl-server [port]   # an nREPL server (default port 7888)
```

`nrepl-server` writes a `.nrepl-port` file and auto-resolves a `deps.edn` in the directory, so the server starts with your app and its dependencies loaded. Connect CIDER, Calva, or Cursive to the port. See [REPL-Driven Development](/docs/repl-driven-development.html) for the full workflow.

## Dependencies

`bin/joltc` reads a `deps.edn` in the current directory, fetches its dependencies, and prepends the resolved source directories to the load path for the run. Git, local, and Maven dependencies are supported (a Maven jar carries Clojure source, which is what Jolt loads):

```clojure
{:paths ["src"]
 :deps {weavejester/medley {:git/url "https://github.com/weavejester/medley"
                            :git/sha "<full-sha>"}
        org.clojure/math.combinatorics {:mvn/version "0.2.0"}
        my/helpers          {:local/root "../helpers"}}}
```

```bash
bin/joltc run -m myapp.main
```

As in tools.deps, a git dependency whose lib name encodes a host can omit `:git/url` — `io.github.OWNER/REPO` (and the `com.github.`, `io.gitlab.`/`com.gitlab.`, `io.bitbucket.`/`org.bitbucket.`, and `ht.sr.~OWNER` prefixes) derives the clone URL from the name, so `io.github.paintparty/lasertag {:git/sha "aa898c1967d10fc198385f1914893b9c75410d16"}` resolves with no URL. The `:git/sha` (a full commit SHA) is still required.

See [Writing Libraries](/docs/writing-libraries.html) for the full `deps.edn` surface (aliases, tasks, transitive deps), and [Supported Libraries](/docs/libraries.html) for Clojure libraries known to work.

### System requirements for dependency resolution

The `joltc` binary itself is self-contained. Resolving *dependencies* uses a few standard tools, each needed only for the coordinate types you use — a dependency that can't be fetched is skipped, never fatal:

- **Git dependencies** (`:git/url` + `:git/sha`) need `git` on `PATH`.
- **Maven dependencies** (`:mvn/version`) are downloaded over HTTPS with Jolt's own TLS (no `curl`), which uses the system **OpenSSL** (`libssl`/`libcrypto`) via FFI, and extracted with `unzip`. A jar already in your local `~/.m2/repository` (e.g. fetched by a JVM Clojure toolchain) is reused with no download.

By platform:

- **macOS** — install OpenSSL with Homebrew (`brew install openssl@3`); Jolt loads the Homebrew copy because the protected system `/usr/lib` OpenSSL can't be loaded into a non-Apple binary. `git` and `unzip` ship with the Xcode command-line tools.
- **Linux** — the distro `libssl3`/`libcrypto3` (or `libssl`/`libcrypto`) packages, plus `git` and `unzip`, all from your package manager.
- **Windows** — install [Git for Windows](https://git-scm.com/download/win); it puts `git` on `PATH` and bundles the OpenSSL (`libssl-3-x64.dll`/`libcrypto-3-x64.dll`) and `unzip` that Jolt needs (or install [OpenSSL for Windows](https://slproweb.com/products/Win32OpenSSL.html) and `unzip` separately). Run `joltc` from a shell where those are on `PATH` (Git Bash works).

Cross-compilation and `joltc build` additionally need a C toolchain (`cc`, `ar`); see [Building & Deps](/docs/building-and-deps.html).
