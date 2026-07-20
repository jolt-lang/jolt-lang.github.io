# Building and dependencies

How to run Jolt from source and how to pull Clojure libraries into a project.

## Running

```bash
git clone https://github.com/jolt-lang/jolt.git
cd jolt
git submodule update --init   # vendor/sci (used by the SCI bootstrap tests)
bin/joltc -e '(println "hello")'
```

There is **no build step**. `bin/joltc` (`host/chez/cli.ss`) loads the
checked-in bootstrap seed (`host/chez/seed/{prelude,image}.ss`) plus the spine
and compiles+evals on Chez (read → analyze → IR → emit → eval), so a fresh
clone runs immediately. The whole `.clj` standard library
(`clojure.string`/`set`/`walk`/`edn`/`pprint`/…) and `clojure.core` are part of
the overlay, so they're always available.

`bin/joltc` is both the runtime (REPL, file/expr runner) and the dependency
front-end (`deps.edn` resolution, see below). A run with no `deps.edn` never
touches the resolver.

The bootstrap seed is **checked in**. After changing a seed source — the reader
(`host/chez/reader.ss`), the analyzer/IR/backend (`jolt-core/jolt/*.clj`), or the
`clojure.core` overlay (`jolt-core/clojure/core/*.clj`) — re-mint the seed with
`make remint` (it iterates `host/chez/bootstrap.ss` to a byte-fixpoint), or
`make selfhost` fails. Runtime-only `host/chez/*.ss` shims don't need a re-mint.

## How namespaces are found

`(require ...)` resolves a namespace to a file by searching an ordered list of
source roots — the stdlib first, then any extra roots — trying `<ns>.clj` then
`<ns>.cljc` (dots become directories, dashes become underscores). Extra roots
come from:

- `JOLT_PATH` — a colon-separated list of directories (like a classpath), applied
  at runtime;
- the `:paths` option to `init` when embedding Jolt as a library.

If a namespace isn't found on any root, the loader falls back to the stdlib in
the overlay — that's how `clojure.string` and friends resolve when you run
outside the source tree.

So you can point Jolt at a directory of Clojure source with no deps machinery at
all:

```bash
JOLT_PATH=/path/to/lib/src bin/joltc run myfile.clj
```

## Dependencies via deps.edn

`bin/joltc` reads a `deps.edn` in the current directory, fetches its
dependencies, and prepends the resolved source directories to the source roots
for the run. The CLI commands (`jolt.deps` + `jolt.main`):

```bash
bin/joltc run -m NS [args]      # resolve deps.edn, load NS, call its -main
bin/joltc run FILE              # resolve deps.edn, load a Clojure file
bin/joltc -M:alias [args]       # run the alias's :main-opts
bin/joltc -A:alias [args]       # add the alias's paths/deps, then run the rest
bin/joltc repl                  # start a line REPL (project deps + native libs loaded)
bin/joltc nrepl-server [port]   # start an nREPL server (default 7888) for editors
bin/joltc path                  # print the resolved source roots (':'-joined)
bin/joltc <task>                # run a deps.edn :tasks entry
```

Example `deps.edn`:

```clojure
{:paths ["src"]
 :deps {weavejester/medley {:git/url "https://github.com/weavejester/medley"
                            :git/sha "<full-sha>"}
        my/helpers          {:local/root "../helpers"}}}
```

```bash
bin/joltc run -m myapp.main
```

### What's supported

- **git deps** — `{:git/url … :git/sha …}` (use a full SHA; `git fetch` can't
  resolve a short one), with an optional `:deps/root` for a subdirectory.
  Transitive deps from each dependency's own `deps.edn` are resolved too.
- **local deps** — `{:local/root "../path"}`.
- **Maven deps** — `{:mvn/version "…"}`. A Clojure library's JAR carries its
  `.clj`/`.cljc` source, so the coordinate resolves by fetching the JAR
  (Clojars, then Maven Central) and using its extracted source as a root; the
  POM supplies transitive deps. JARs live in the standard `~/.m2/repository`,
  shared with the JVM toolchain in both directions (`:mvn/local-repo` in
  `deps.edn` relocates it, `JOLT_LOCAL_REPO` overrides from the environment).
  A pure-Java JAR has no source to run and contributes nothing.
- The project's own `:paths` (default `["src"]`) are included.
- **aliases** — `:aliases {:dev {:extra-paths ["dev"] :extra-deps {…}
  :main-opts ["-e" "…"]}}`, selected with `-A:dev` (or several: `-A:dev:test`).
  `:extra-paths`/`:extra-deps` accumulate across selected aliases;
  `:main-opts` is last-wins and runs via `-M:alias`.
- **tasks** — `:tasks {clean "rm -rf target" test {:main-opts ["-m" "…"]}}`.
  A string task is a shell command; a map task runs jolt with its `:main-opts`.
  Run one with `bin/joltc <taskname>`.

Resolution is breadth-first, so a top-level coordinate always beats a transitive
one for the same lib.

Git clones land in a global, sha-immutable cache shared across projects —
`$JOLT_GITLIBS`, else `~/.jolt/gitlibs`.

### What's not

- **Pure `clj`/`cljc` only.** A library that needs the JVM (Java interop, host
  classes) or a `clojure.core` feature Jolt doesn't implement will fail to load
  or fail at a call. Coverage is per-function: a namespace can load with most
  functions working and a few not. This applies to Maven deps too — the JAR's
  Clojure source is what runs; compiled `.class` files are ignored.

See [deps.edn internals](/docs/tools-deps.html) for the design rationale.

### Adding deps from a script

A single-file script can declare its dependencies inline with
`jolt.deps/add-deps` (mirrors `babashka.deps/add-deps`) instead of a
`deps.edn`:

```clojure
(when (System/getProperty "jolt.version")
  ((requiring-resolve 'jolt.deps/add-deps)
   '{:deps {org.clojure/data.json {:mvn/version "2.5.0"}}}))

(ns main (:require [clojure.data.json :as json]))
```

The `jolt.version` property guard makes the script portable: on jolt it's
always set, elsewhere the form is skipped — the same idiom babashka scripts
use with `babashka.version`. See [Dependencies (jolt.deps)](/docs/api/deps.html)
for the full API.

## Building binaries

`jolt build` compiles a namespace and its dependencies into a standalone binary:

```bash
JOLT_PWD=/path/to/project bin/joltc build -m my.app
```

The binary contains the runtime + app forms + native launcher — no Jolt source or Chez
install needed on the target machine (a C compiler and Chez kernel dev files are needed
at build time only).

### How AOT compilation works

`jolt build` does not bundle source or an interpreter. At build time each reachable
namespace is taken through the same `analyze → emit` pipeline the REPL uses, but the
final `eval` is replaced by *accumulate-then-compile*: every form is analyzed and
emitted to Scheme, the emitted Scheme is concatenated into one program, and that program
is handed to Chez's native compiler and linked into a boot file embedded in the
executable. The result is compiled Chez native code (a fasl boot image + native
launcher), not Clojure source — at runtime there is nothing to read or recompile, and no
source roots are consulted. This is the same machinery `joltc` itself uses to bake its
own runtime + compiler into the distributed binary (that is why a built `joltc` boots in
a fraction of a second instead of recompiling its standard library every run).

The build pipeline runs four steps, in order:

1. **Assemble.** Starting from the entry namespace's `-main`, load the transitive
   `require` graph and collect every reachable top-level form, in dependency order, with
   its compile namespace. `:tree-shake` (below) prunes unreachable forms in this step.
2. **Emit.** Run `analyze → emit` for each surviving form under the selected mode's
   optimization knobs (the `clojure.core` overlay prelude first, in tier order), emitting
   Scheme and concatenating it into a single program source. This step is *strict*: a
   form that fails to compile fails the build rather than being skipped.
3. **Inline the runtime.** Textually splice the compiler/stdlib runtime (the `cli.ss`
   load sequence, itself already cross-compiled) ahead of the emitted app forms, and
   append a launcher that calls the entry's `-main`.
4. **Compile and link.** Feed the inlined source to Chez's native compiler
   (`compile-file` → `make-boot-file`), embed the resulting boot as C bytes, and
   `cc`-link it against the Chez kernel (`libkernel.a`) into one self-contained
   executable. App libraries are baked in here, so the binary carries no on-disk source
   dependency.

Two consequences are worth knowing. First, an app that never calls `eval`/`load-string`
ships *without* the compiler image — the build detects those calls and drops the compiler
when it can, so a closed-world binary is smaller. Second, because the whole program is
visible at once, whole-program type inference runs across namespaces (field reads
specialize, protocol calls devirtualize) — something the per-form REPL path can't do.
The modes below control how far that optimization goes.

### Build modes

Three modes control which optimization passes apply. A mode is selected by the CLI flag
`--opt`, `--dev`, or by the `:jolt/build {:opt true}` key in `deps.edn`; the default is
`release`. CLI flags win over `deps.edn`.

| Mode | `--opt` / `{:opt true}` | `--dev` | Release (default) |
|------|--------------------------|---------|-------------------|
| const-fold | yes | yes | yes |
| numeric-annotate | yes | yes | yes |
| type inference (run-inference) | yes | - | yes |
| record-shape + protocol-method caches | yes | - | yes |
| inline + scalar-replace fixpoint | with `--direct-link` | - | - |

`--opt` enables the annotation-producing passes (type inference, PIC/devirtualization,
record-ctor caches) for better runtime performance without committing to a closed world.
Add `--direct-link` to also enable the inline + scalar-replace fixpoint — this gives the
best performance but gives up runtime redefinition of direct-linked vars. For fully
closed-world binaries, combine `--opt --direct-link --tree-shake` to drop dead code.

`--dev` produces a debug binary under `target/debug/` (const-fold + numeric annotate
only), typically used during development for faster build times.

### Typed arithmetic and inference

Numeric code compiles to raw Chez flonum/fixnum operations (`fl*`, `fx+`) when
the compiler can prove every operand's type. Three things prove types, in order
of preference:

1. **Inference.** Whole-program builds (`build`, or running a program with
   `-m`) infer types with no annotations: float literals and their arithmetic,
   `^double`/`^long` signatures across call sites, record fields whose every
   constructor site passes a flonum, protocol-method returns, and reduce/HOF
   accumulators all propagate. Most hot float code needs nothing else.
2. **`^double` / `^long` hints** on fn params, returns, loop bindings, and
   record fields. A hint is a contract enforced by coercion at the boundary:
   a `^double` param converts its argument on entry, a `^long` param is a
   fixnum promise — arithmetic on it raises on 61-bit overflow instead of
   promoting to bignum. Use `^long` only where overflow is impossible.
3. **`(double x)` / `(long x)` casts** where inference can't see — a value
   from I/O, an untyped map, a dynamic call. The cast keeps its full Clojure
   semantics (throws on non-numbers, `(long 1.5)` truncates) and types the
   result like a hint. Portable: the same code speeds up on the JVM.

Inference stays sound by widening: a conflicting, escaping, or unprovable type
falls back to the generic (boxed, correct) path, never a wrong answer.
Interactive modes (`repl`, `-e`, `nrepl-server`) skip whole-program passes so
redefinition keeps working.

### deps.edn build options

The `:jolt/build` map in `deps.edn` accepts these keys:

- **`:opt true`** — build in optimized mode (like `--opt`)
- **`:direct-link true`** — closed-world direct linking (like `--direct-link`)
- **`:tree-shake true`** — drop unreachable library code (like `--tree-shake`)
- **`:embed [dirs]`** — bake resource files into the binary so `io/resource` resolves
  with no files on disk
- **`:dynamic-natives true`** — load native shared objects at runtime instead of
  statically linking

Example:

```clojure
{:paths ["src"]
 :jolt/build {:opt true
              :direct-link true
              :tree-shake true
              :embed ["resources"]}}
```
