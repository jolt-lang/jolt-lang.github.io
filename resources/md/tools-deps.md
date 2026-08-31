# deps.edn support: design notes

How Jolt loads pure-Clojure libraries from a `deps.edn`, and why it's built the
way it is. For how to *use* it, see [building-and-deps.md](/docs/building-and-deps.html).

Scope, decided up front:

- **source resolution only**: git, local, and Maven coordinates all resolve to
  Clojure *source* directories; compiled `.class` files are ignored.
- **pure `clj`/`cljc`**: anything needing the JVM won't load or run; expected.
- **no classpath abstraction**: `require` just needs to find a dep's namespaces;
  "the classpath" is an ordered list of source directories.
- **own resolver, own reader**: `deps.edn` is read by jolt's own reader. Git
  deps shell out to `git`; Maven artifacts are fetched over HTTPS by jolt itself
  (system OpenSSL via FFI, no `curl`) and unpacked with `unzip`. No external
  package manager, and no Java.
- **deps-agnostic runtime core**: resolution is a CLI front-end concern, not a
  runtime one. The runtime knows nothing about `deps.edn`; it only consumes a
  list of source roots. The CLI resolves a `deps.edn` into those roots before
  running.

## How resolution works

`jolt.deps` (`jolt-core/jolt/deps.clj`) reads `deps.edn` (jolt's own reader
parses the EDN), then walks `:deps`:

- `:git/url` + `:git/sha` (+ optional `:deps/root`) → clone the sha into the git
  cache and contribute the checkout (or its `:deps/root` subdir). `:git/url` may
  be omitted when the lib name encodes a host the way tools.deps allows
  (`io.github.OWNER/REPO` (or `com.github.`), `io.gitlab.`/`com.gitlab.`,
  `io.bitbucket.`/`org.bitbucket.`, and `ht.sr.~OWNER`), in which case the clone
  URL is derived from the name;
- `:git/tag` + a short `:git/sha` → the tag is resolved to its commit with
  `git ls-remote` (preferring the peeled `^{}` ref of an annotated tag) and the
  short sha is verified as a prefix of it, exactly as tools.deps requires: a
  tag alone never pins a commit;
- `:local/root` → the path as-is, or, when it names a `.jar`, its extraction;
- `:mvn/version` → fetch the JAR and use its extracted Clojure source as a
  root (see below);
- anything else → reported as an unsupported coordinate.

Coordinate types are a small SPI (`jolt.deps.ext`) modelled on
`clojure.tools.deps.extensions`: a type publishes its identifying keys and
implements `dep-id` / `coord-deps` / `coord-info` / `compare-versions`. That is
what lets the expansion engine below be shared across git, Maven, and local
coordinates, and lets the test suite register a fake in-memory type to exercise
the engine without touching the network.

git resolution shells out to `git` through `jolt.host/sh`: `git init` + remote
add + fetch + reset at the requested sha. An existing `tools.gitlibs` checkout
(`$GITLIBS`, else `~/.gitlibs`) is reused when the JVM toolchain already
fetched the sha; otherwise clones land in a global, sha-immutable cache
(`$JOLT_GITLIBS`, else `~/.jolt/gitlibs`) shared across projects.

Maven resolution works because a Clojure library's JAR ships its `.clj`/`.cljc`
source alongside bytecode: the JAR is fetched (Clojars, then Maven Central)
into the standard local repository (`~/.m2/repository`) at its standard path
(so artifacts are shared with JVM Clojure/tools.deps in both directions) and
extracted once into a `<artifact>-<version>.jar.jolt/` directory beside it,
whose source becomes the root. The POM's `<dependency>` blocks supply
transitive deps (test/provided/optional scopes skipped). A JAR with no
Clojure source (pure Java) contributes nothing; the ClojureScript compiler
subtree is skipped outright. `:mvn/local-repo` in `deps.edn` relocates the
repository like tools.deps; `JOLT_LOCAL_REPO` overrides from the environment.

Each resolved dependency contributes its own `:paths` (default `["src"]`) as
source roots. The result is a de-duplicated, ordered list of directories.

### Expansion

The tree expansion is tools.deps' own algorithm, ported: a **version map**
records every version of every library seen and which one is selected, an
**exclusion tree** scopes `:exclusions` by dependency path (narrowing to the
intersection when the same library/version arrives by another path), and
**orphan cutting** removes libraries whose only parent paths went away when a
newer version displaced them. Selection follows the same rules (a top-level
coordinate pins, otherwise the newest version wins) with `compare-versions`
per coordinate type: Maven versions order by ComparableVersion semantics
(reimplemented, since there is no JVM to ask), git coordinates by commit
ancestry via `git merge-base`. Where no order exists, jolt warns and keeps the
already-selected coordinate rather than failing the whole resolution.

The alias args map is built by `jolt.deps.edn`: `merge-edns`,
`merge-alias-maps`, `combine-aliases`, and `canonicalize` taken verbatim from
`clojure.tools.deps.edn`, so per-key merge behavior (maps merge, paths append
uniquely, `:main-opts` is last-wins) is the reference implementation's rather
than a lookalike. Reading follows the same chain too: user `deps.edn`, then
project, then an optional `-Sdeps` map.

**Tasks** remain jolt's own: a string task is
a shell command, a map task is `{:main-opts […]}`; bare Clojure expressions
aren't a separate task form.

### What's still missing

`:deps/prep-lib` is recognized but never run: jolt has no prep step, so a
library needing one is named in a warning instead of silently producing a
half-built root. Maven `:classifier`/`:extension` coordinates, `pom.xml` as a
project manifest, `-Stree` output, and the `:deps/manifest` override are not
implemented.

## How the CLI ties it together

`jolt.main` (`jolt-core/jolt/main.clj`) is the CLI dispatch. Driven by `cli.ss`,
it resolves the project (`jolt.deps/resolve-project`), prepends the resolved
roots, and de-sugars the argv into a run:

- `run -m NS args` → load `NS`, call its `-main`;
- `run FILE` → load the file;
- `-M:alias` → run the alias's `:main-opts`;
- `-A:alias` → add the alias's paths/deps, then run the rest (the selected
  aliases stay in effect for whatever command follows, so `-A:x path` and
  `-A:x -M:y` both see them);
- `-X:alias [ns/fn] [k v …]` → call `:exec-fn` (or the given symbol, qualified
  through `:ns-default`/`:ns-aliases`) with `:exec-args` merged under the
  trailing key/value pairs;
- `-T:alias …` → `-X` with the project's own paths and deps replaced by the
  alias's, for tools that shouldn't see the project classpath;
- `-Sdeps '<edn>'` → merge an extra `deps.edn` map, then run the rest;
- `repl` → a line REPL;
- `path` → print the resolved roots;
- `build -m NS [-o OUT] [--opt|--dev]` → AOT-compile the app into a standalone binary;
- `tasks` → list the project's tasks;
- `<task> [args]` → run a `bb.edn` / `deps.edn` `:tasks` entry (see below).

The resolver lives in the overlay alongside the runtime, but the runtime's only
dependency interface is the list of source roots it's handed.

Scripts can also resolve deps at runtime with `jolt.deps/add-deps` (the
`babashka.deps/add-deps` twin): same coordinates, roots appended after the
current ones so an added dep never shadows a loaded namespace. See
[Dependencies (jolt.deps)](/docs/api/deps.html).

## Tasks

A project's tasks live in the `:tasks` map of its `bb.edn` or its `deps.edn`,
and follow babashka's semantics. **Either file works** — a project that has no
`bb.edn` declares its tasks in `deps.edn` and loses nothing:

```clojure
;; deps.edn — no bb.edn needed
{:paths ["src"]
 :tasks {:requires ([babashka.fs :as fs])
         clean {:doc "remove build output" :task (fs/delete-tree "target")}
         build {:doc "build" :depends [clean] :task (shell "cc" "-O2" "-o" "target/app" "app.c")}
         test  {:doc "run the tests" :main-opts ["-m" "app.test-runner"]}}}
```

When a project has both, the two `:tasks` maps merge with `bb.edn` last, so a
name in both is babashka's.

```bash
jolt tasks                 # list them, with their :doc
jolt build                 # run one — `jolt run build` does the same
jolt run --parallel build  # …with independent :depends run concurrently
jolt test -v               # arguments after the name are *command-line-args*
```

A task's value is either a map or the body on its own. The map keys are `:doc`,
`:task` (the body), `:depends`, `:requires`, `:private` (kept out of the
listing), `:extra-paths` / `:extra-deps` (roots and dependencies for that task
alone), and `:override-builtin` (take a jolt command's name deliberately). The
`:tasks` map itself takes `:init` (evaluated once before any task), `:requires`
(for every task), and `:enter` / `:leave` (around each one).

Bodies are evaluated in the `user` namespace with `clojure.core` and
[`babashka.tasks`](https://book.babashka.org/#tasks) referred, so `shell`,
`jolt`, `clojure`, `run` and `current-task` need no require. A `shell` that
exits non-zero fails the task, and `jolt` exits with the child's status.

Each dependency runs at most once per invocation, and a cycle is an error
rather than a hang.

Because a task can shell out and `deps.edn` already describes the project, a
project that needs a build step — compiling a C shim named by
[`:jolt/native`](/docs/native-interop.html), generating a source file — can
carry it here instead of in a makefile or a script beside it. A task run does
not require the project's `:jolt/native` libraries to be loadable, precisely so
that the task which builds one can run on a fresh checkout.

Two forms are jolt's rather than babashka's. A **string** body is a shell
command line — `{clean "rm -rf target"}` — where babashka would evaluate it as
an expression, which does nothing. And a map with **`:main-opts`** runs them
like an alias's, which is how a `deps.edn` task names a `-main` to call. Both
work in either file.

`jolt` re-invokes this CLI — `(jolt "-M:test")`. `clojure` is the same
function under babashka's name for it, so a bb.edn that calls
`(clojure "-M:test")` runs here: on this host jolt is the Clojure, and the
point of the exercise is not to need a JVM. Prefer `jolt` in new task maps;
`(shell "clojure" "-M:test")` still reaches the real Clojure CLI.

`:pods` are not supported and say so when a `bb.edn` declares them.

### bb.edn and deps.edn together

With no `deps.edn`, `bb.edn` **is** the project config: its `:paths` and
`:deps` drive `run`, `repl`, `path` and `build` like a `deps.edn` would.

With both files, `deps.edn` is the project — it alone answers every command —
and `bb.edn` contributes its `:tasks`. Its `:paths` and `:deps` join the
resolution for a task run only, so a `bb.edn` `:paths ["script"]` cannot
displace the app's own source roots on every other command. A task name
declared in both files is babashka's.

## Declaring the Jolt a project needs

`:jolt/min-version` is the oldest Jolt a project — or a library — works on, and a
runtime below it refuses to load rather than run:

```clojure
{:paths ["src"]
 :jolt/min-version "0.8.0"}
```

Not every breaking change is visible at the call site. `jolt.ffi/write`'s
argument order moved in 0.8.0, and the old and new spellings are both integers,
so an older runtime writes to the wrong place and reports nothing — exactly the
failure a declared floor turns into a message.

A **library** is the natural declarer: it knows which Jolt its FFI bindings or
host shims need, and the application pulling it in does not. The floor is read
from every dependency's `deps.edn` as well as the project's, and an unmet one
names what is needed, what is running, and which dependency asked.

The key is honoured by the Jolt that reads it, so it protects from 0.8.0 onward
and not before — an older Jolt ignores it, as it ignores every key it does not
know. A runtime that names no version (a source build answers `dev`) is never
refused, and `JOLT_SKIP_MIN_VERSION=1` runs anyway.

## Native libraries

A library that binds C declares the shared objects it needs under `:jolt/native`,
so `jolt.main` loads them before the namespace is required and its `foreign-fn`
bindings resolve. Each entry is a map
(`{:name "sqlite3" :darwin ["libsqlite3.0.dylib" …] :linux ["libsqlite3.so.0" …]}`) with optional
`:optional true` (absence is fine, a feature-gated dep) and `:process true` (use
the running process's own symbols, e.g. libc sockets, no external file). A
project inherits its dependencies' `:jolt/native`.

### Static vs dynamic linking

When you `jolt build`, a native lib is **statically linked** into the binary by
default if the spec carries a `:static` archive, so the executable calls the C
code with no shared object present at runtime. Add `:static` alongside the runtime
candidates:

```clojure
{:name "sqlite3"
 :static {:archive "/opt/homebrew/lib/libsqlite3.a"}  ; or {:lib "sqlite3" :libdir "/usr/lib"}
 :darwin ["libsqlite3.0.dylib"]   ; still used by `run`/`repl` and by --dynamic
 :linux  ["libsqlite3.so.0"]}
```

`:static {:archive PATH}` force-loads the whole `.a` and is the reliable
cross-platform form. `:static {:lib NAME :libdir DIR}` links `-lNAME` (with a
`-Bstatic` preference on Linux); on macOS, which has no `-Bstatic`, prefer the
archive form. A spec with no `:static` (or a build passed `--dynamic`, or
`:jolt/build {:dynamic-natives true}`) loads the shared object dynamically at
startup via `load-shared-object`.

Static linking needs a C compiler (`cc`) on `PATH` at build time (plus the C libs
the Chez kernel links: lz4, zlib, ncurses). The distributed `jolt` bundles the
Chez kernel, so it re-links the launcher stub with the archive baked in; no
external Chez, just `cc`. Without a `cc`, a `:static` lib fails with a message
pointing you to install one or pass `--dynamic`. Keep a `:darwin`/`:linux`
candidate on any `:static` spec so `run`/`repl` (which have no static binary) can
still load it.

## Standalone binaries

`jolt build -m NS` compiles the app and every library into one executable (the
runtime + compiler are baked in). Resolved `:jolt/native` libs are statically
linked in (or loaded at startup; see [Native libraries](#native-libraries)), so
an FFI app (sockets, SQLite) runs with no jolt or Chez on the path.

Output goes under the project's `target/`, cargo-style: `target/release/<project>`
by default and with `--opt`, `target/debug/<project>` with `--dev` (the
`<name>.build` scratch dir sits beside it). `-o PATH` overrides: absolute as-is,
relative against the project dir. Paths resolve against the project (`JOLT_PWD`),
not the CLI's cwd, since `bin/jolt` runs from the jolt repo.

`:jolt/build {:embed ["resources" …]}` bakes those directories' files into the
binary; `io/resource` serves them from the image with no files on disk. Resources
not embedded resolve at runtime against `JOLT_PWD` (or the cwd), so the
ship-the-binary-with-its-`resources/`-dir model also works. Files read through
`io/file` (e.g. a `config.edn` a config library loads) stay external by design;
edit them without rebuilding.

A standalone build needs Chez's kernel dev files (`libkernel.a`, `scheme.h`) and
a C compiler; `JOLT_CHEZ_CSV` overrides the auto-detected `csv<ver>/<machine>`
dir. `--opt` turns on the inference/flatten/scalar-replace passes; the default
`release` mode is const-fold only.

`--direct-link` (or `:jolt/build {:direct-link true}`) opts into a closed world: a
call between the app's own functions binds to its target directly, skipping the var
lookup and generic dispatch a runtime call pays, at the cost of runtime
redefinition of those vars and `eval`/`load-string`. It's off by default, so
ordinary builds (including `release` and `--opt`) stay dynamically linked. A var
marked `^:redef` or `^:dynamic` stays indirect even under `--direct-link`, and calls
into `clojure.core` stay indirect in every mode.

## Tree-shaking

`--tree-shake` (or `:jolt/build {:tree-shake true}`) ships only the code reachable
from `-main`. The build constructs one call graph spanning the app, every resolved
library, and the `clojure.core`/stdlib prelude, then keeps `-main`, every
side-effecting top-level form (so a `defmethod`/`defrecord`/protocol registration
keeps its targets live), and everything reachable from those, dropping the rest. A
reference counts whether it's a call or a value (`#'x`, a fn passed to `map`, a fn
stored in a map): any reference keeps its target live, so nothing reachable is ever
dropped. An app that never compiles at runtime (no reachable `eval`/`load-string`)
also drops the analyzer and back end from the binary. Typical savings are 1–2 MB;
behaviour is unchanged.

**It bails (keeps everything) when reachable code resolves a var by name at
runtime** (`eval`, `resolve`, `ns-resolve`, `requiring-resolve`, `find-var`,
`intern`, `load-string`, `load-file`). A static call graph can't follow a runtime
`resolve`, so dropping anything would be unsound. The build prints which definitions
forced the bail:

```
jolt build: tree-shake skipped (reachable code resolves vars at runtime):
  selmer.filters/generate-json -> clojure.core/resolve
  clojure.tools.logging/call-str -> clojure.core/ns-resolve
```

These are almost always libraries, not your code. `resolve` is how mature Clojure
libraries implement plugin systems and optional integrations (a logging backend
chosen at runtime, a template filter that lazily loads an optional dependency). On
the JVM that costs nothing; in a closed-world binary it defeats reachability. To make
an app tree-shakeable, keep runtime resolution off the *reachable* path: a backend
that's fixed on jolt can be referenced directly rather than resolved (the jolt
`tools.logging` port dropped the JVM's dynamic factory selection for exactly this),
and an optional integration you don't use can be dropped or hard-wired. Unreached
`resolve`-using code is shaken away like anything else; only resolution on the live
path triggers the bail.

The closed-world soundness model follows Stalin's dead-code analysis: in a program
with no `eval`, a definition is live iff it is referenced (called or as a value) from
a root, transitively.

## Limitations

- Pure `clj`/`cljc` only: JVM interop, host classes, and unimplemented
  `clojure.core` corners fail. Coverage is per-function: a namespace can load with
  most functions working and a few not.
- Source only; compiled `.class` files in a git dep are ignored.
- git `:git/sha` must be a full SHA (`git fetch` can't resolve a short one).

## Stack traces

An uncaught error prints the message, the top-level source location, and a
`trace:` backtrace whose frames map to `ns/name (file:line)`:

```
Unhandled exception: Divide by zero
  trace:
    app.core/boom (src/app/core.clj:3)
    app.core/-main (src/app/core.clj:6)
```

`.printStackTrace` on a caught exception prints the same backtrace, under a
`class: message` header, to stderr or to a `PrintWriter`/`PrintStream` given as
its argument.

Each line is the one **reached inside that frame**: where the innermost function
threw, and where every frame above it made its call, the same thing a JVM stack
trace reports per frame.

Two sources of frames sit behind this. Chez's live continuation gives the exact
call spine, but tail-call optimization erases tail-called frames from it, and
`-main` tail-calling a function that throws is the ordinary shape, which leaves
nothing at all. So a **tail-frame history** recovers them: each compiled function
records itself on entry into a bounded ring-of-rings buffer, which keeps the
TCO-erased frames (including the immediate error site) while a tight tail loop
stays bounded and its non-tail caller context survives.

The per-frame line rides along with it: the compiler sets the current line before
each call, and a function's entry records whatever line its *caller* was on. A
frame's own line is therefore the one recorded by the frame below it, and the
innermost frame's is the live line at the throw. Because the buffer is a history
rather than a stack, frames from an earlier call that already returned can still
appear below the current ones; the innermost frames are the accurate part.

The history is **on by default when running from source**: `jolt run`, `-m`,
`-M`, `-e`, and a `repl` or nREPL session. Set `JOLT_TRACE=0` to opt out.

Because the recording is baked in when a function is compiled, it covers code
compiled at runtime, which is your own namespaces; `clojure.core` ships
precompiled and carries none. That is also why a workload doing real work per call
measures the same either way, while code that is almost nothing but user-level
calls pays for it; a `fib` microbenchmark runs about 7x slower, and that is the
case to set `JOLT_TRACE=0` for. In a live REPL session only code compiled *since*
the session started is traced, so reload a namespace to trace code loaded before
it (an app's initial `-M:run` load, say, before its nREPL started).

A `jolt build` binary is never traced this way: its prologues are decided at build
time, so it carries no per-call cost, and its traces come from the live
continuation and the AOT source map alone.

## Conformance

The known-working libraries (see [libraries.md](/docs/libraries.html)) and the
[examples](https://github.com/jolt-lang/examples) exercise real pure-`cljc` git
libraries end to end: resolving them from git, loading their namespaces, and
running sample calls. A library fails when it relies on something Jolt doesn't
provide: JVM interop, or a regex feature like Unicode property classes
(`\p{…}`).
