# Building and dependencies

How to run Jolt from source and how to pull Clojure libraries into a project.

## Running

```bash
git clone https://github.com/jolt-lang/jolt.git
cd jolt
git submodule update --init   # vendor/sci (used by the SCI bootstrap tests)
bin/jolt -e '(println "hello")'
```

There is **no build step**. `bin/jolt` (`host/chez/cli.ss`) loads the
checked-in bootstrap seed (`host/chez/seed/{prelude,image}.ss`) plus the spine
and compiles+evals on Chez (read → analyze → IR → emit → eval), so a fresh
clone runs immediately. The whole `.clj` standard library
(`clojure.string`/`set`/`walk`/`edn`/`pprint`/…) and `clojure.core` are part of
the overlay, so they're always available.

`bin/jolt` is both the runtime (REPL, file/expr runner) and the dependency
front-end (`deps.edn` resolution, see below). A run with no `deps.edn` never
touches the resolver.

The bootstrap seed is **checked in**. After changing a seed source (the reader
(`host/chez/reader.ss`), the analyzer/IR/backend (`jolt-core/jolt/*.clj`), or the
`clojure.core` overlay (`jolt-core/clojure/core/*.clj`)) re-mint the seed with
`make remint` (it iterates `host/chez/bootstrap.ss` to a byte-fixpoint), or
`make selfhost` fails. Runtime-only `host/chez/*.ss` shims don't need a re-mint.

## How namespaces are found

`(require ...)` resolves a namespace to a file by searching an ordered list of
source roots (the stdlib first, then any extra roots), trying `<ns>.clj` then
`<ns>.cljc` (dots become directories, dashes become underscores). Extra roots
come from:

- `JOLT_PATH`: a colon-separated list of directories (like a classpath), applied
  at runtime;
- the `:paths` option to `init` when embedding Jolt as a library.

If a namespace isn't found on any root, the loader falls back to the stdlib in
the overlay; that's how `clojure.string` and friends resolve when you run
outside the source tree.

So you can point Jolt at a directory of Clojure source with no deps machinery at
all:

```bash
JOLT_PATH=/path/to/lib/src bin/jolt run myfile.clj
```

## Dependencies via deps.edn

`bin/jolt` reads a `deps.edn` in the current directory, fetches its
dependencies, and prepends the resolved source directories to the source roots
for the run. The CLI commands (`jolt.deps` + `jolt.main`):

```bash
bin/jolt run -m NS [args]      # resolve deps.edn, load NS, call its -main
bin/jolt run FILE              # resolve deps.edn, load a Clojure file
bin/jolt FILE [args]           # the same with `run` left out — so a file whose
                               # first line is `#!/usr/bin/env jolt` is a script
bin/jolt -f FILE [args]        # load FILE even when its name is a command or task
bin/jolt -M:alias [args]       # run the alias's :main-opts
bin/jolt -A:alias [args]       # add the alias's paths/deps, then run the rest
bin/jolt -X:alias [k v ...]    # call the alias's :exec-fn with :exec-args
bin/jolt -T:alias [k v ...]    # like -X, with the project's paths/deps replaced
bin/jolt -Sdeps '<edn>' ...    # merge an extra deps.edn map, then run the rest
bin/jolt repl                  # start a line REPL (project deps + native libs loaded)
bin/jolt nrepl-server [port]   # start an nREPL server (default 7888) for editors
bin/jolt path                  # print the resolved source roots (':'-joined)
bin/jolt <task>                # run a deps.edn :tasks entry
```

### Inspecting a resolution

The report options answer something about the project and run nothing. Each
takes the aliases around it, so `-A:test -Spath` and `-Spath -M:test` both
report on the resolution that run would use:

```bash
bin/jolt -Spath                # the resolved source roots (':'-joined)
bin/jolt -Stree                # the dependency tree, tools.deps format
bin/jolt -Sgraph               # the dependency tree as an indented graph
bin/jolt -Soutdated            # the same graph, marking available updates
bin/jolt -Strace               # write the dependency expansion to trace.edn
bin/jolt -Sdescribe            # the environment as an edn map
bin/jolt -P                    # fetch every dependency, then stop
```

`-Stree` and `-Sgraph` answer different questions about the same resolution.
`-Stree` prints what `clojure -Stree` prints — the expansion trace, where every
candidate appears and the ones that lost are marked `X` with the reason:

```text
org.clojure/data.json 2.4.0
rewrite-clj/rewrite-clj 1.1.47
  X org.clojure/tools.reader 1.3.6 :use-top
org.clojure/tools.reader 1.3.6
```

That tells you how the resolution got where it did. `-Sgraph` answers the other
question — what does this program actually depend on — over the edges that were
selected. A library reached through two parents is expanded once and marked
`(already shown)` after that, and a coordinate that reaches itself is marked
`(cycle)`, so a wide graph stays readable:

```text
├── org.clojure/data.json 2.4.0
├── org.clojure/tools.reader 1.3.6
└── rewrite-clj/rewrite-clj 1.1.47
    └── org.clojure/tools.reader 1.3.6 (already shown)
```

`-Soutdated` renders that same graph and appends `-> VERSION` to every Maven
library with a newer release available:

```text
├── org.clojure/data.json 2.4.0 -> 2.5.2
└── rewrite-clj/rewrite-clj 1.1.47 -> 1.2.57
    └── org.clojure/tools.reader 1.3.6 -> 1.6.0
```

It is a separate option rather than a flag on `-Sgraph` because it is the only
one that goes to the network: it asks each library's repositories for their
metadata, which costs a round-trip apiece, while `-Sgraph` reads the resolution
jolt already has. A lookup that fails warns on stderr and leaves that library
unmarked rather than failing the report, so one unreachable repository does not
cost you the rest of it. `:git/url` and `:local/root` coordinates are printed
but have no newer version to report, and the `org.clojure/clojure` branch is
left out of both graphs.

Example `deps.edn`:

```clojure
{:paths ["src"]
 :deps {weavejester/medley {:git/url "https://github.com/weavejester/medley"
                            :git/sha "<full-sha>"}
        my/helpers          {:local/root "../helpers"}}}
```

```bash
bin/jolt run -m myapp.main
```

### deps.edn keys at a glance

Every top-level key Jolt reads, and where each is covered in full:

| key | what it does |
| --- | --- |
| `:paths` | source directories for the project itself (default `["src"]`) |
| `:deps` | dependency coordinates (git, local, or Maven; [below](#what's_supported)) |
| `:aliases` | named argument maps selected with `-A`/`-M`/`-X`/`-T` ([below](#what's_supported)) |
| `:tasks` | named shell commands or Jolt invocations, run as `jolt <task>` ([below](#what's_supported)) |
| `:mvn/repos` | extra Maven repositories, consulted after Clojars and Central |
| `:mvn/local-repo` | relocate the local Maven repository (default `~/.m2/repository`) |
| `:jolt/native` | shared libraries a project or library needs, loaded before its code ([Native interop](/docs/native-interop.html)) |
| `:jolt/build` | `jolt build` options (`:opt`, `:direct-link`, `:tree-shake`, `:boot`, `:embed`, `:dynamic-natives`; [below](#deps.edn_build_options)) |
| `:nrepl/middleware` | nREPL middleware a library contributes ([REPL-driven development](/docs/repl-driven-development.html)) |

A user-level `deps.edn` (`$CLJ_CONFIG`, else `$XDG_CONFIG_HOME/clojure`, else
`~/.clojure`) is merged underneath the project's, and `-Sdeps '{…}'` merges a map
on top of both, the same chain tools.deps uses. `JOLT_NO_USER_DEPS=1` skips the
user file.

`:deps/prep-lib` is recognized but not run: Jolt has no prep step, so a
dependency declaring one is named in a warning rather than silently contributing
a half-built source root.

### What's supported

- **git deps**: `{:git/url … :git/sha …}` with a full SHA, or `{:git/tag "v1.2"
  :git/sha "abc1234"}` where the tag resolves to its commit and the short SHA is
  verified as a prefix of it. An optional `:deps/root` selects a subdirectory.
  `:git/url` may be omitted when the lib name encodes a host:
  `io.github.OWNER/REPO`, `io.gitlab.…`, `io.bitbucket.…`, `ht.sr.~OWNER`.
  Transitive deps from each dependency's own `deps.edn` are resolved too.
- **local deps**: `{:local/root "../path"}`. The path may also be a `.jar`,
  which is extracted and used as a source root, its POM supplying transitive deps.
- **Maven deps**: `{:mvn/version "…"}`. A Clojure library's JAR carries its
  `.clj`/`.cljc` source, so the coordinate resolves by fetching the JAR
  (Clojars, then Maven Central, then any `:mvn/repos` you declare) and using its
  extracted source as a root; the POM supplies transitive deps. JARs live in the
  standard `~/.m2/repository`, shared with the JVM toolchain in both directions
  (`:mvn/local-repo` in `deps.edn` relocates it, `JOLT_LOCAL_REPO` overrides from
  the environment). A pure-Java JAR has no source to run and contributes nothing.
- **exclusions and version conflicts**: `:exclusions [some/lib]` on a coordinate
  prunes that dependency's subtree. When two dependencies want different versions
  of the same library, the newest wins (Maven versions compare by the usual
  ComparableVersion rules; git coordinates by commit ancestry), and a top-level
  coordinate always pins regardless of what transitive deps ask for.
- The project's own `:paths` (default `["src"]`) are included.
- **aliases**: selected with `-A:dev` (or several: `-A:dev:test`), combining
  with the same rules as tools.deps:

  | key | effect |
  | --- | --- |
  | `:extra-paths` / `:extra-deps` | added to the project's, accumulating across selected aliases |
  | `:replace-paths` / `:replace-deps` | used *instead of* the project's (`:paths`/`:deps` are accepted as the legacy spellings) |
  | `:override-deps` | pins a library's coordinate wherever it appears, including transitively |
  | `:default-deps` | supplies a coordinate where a dependency left one out |
  | `:main-opts` | last-wins across selected aliases; run with `-M:alias` |
  | `:exec-fn` / `:exec-args` | the function `-X:alias` calls and the map it receives |
  | `:ns-default` / `:ns-aliases` | qualify an unqualified or aliased `:exec-fn` symbol |

  Selecting an alias that isn't declared is an error rather than a silent no-op.
- **tasks**: `:tasks {clean "rm -rf target" test {:main-opts ["-m" "…"]}}`.
  A string task is a shell command; a map task runs jolt with its `:main-opts`.
  Run one with `bin/jolt <taskname>`.

`deps.edn` files merge like tools.deps: a user-level file (`$CLJ_CONFIG`, else
`$XDG_CONFIG_HOME/clojure`, else `~/.clojure`) sits under the project's, and
`-Sdeps '{…}'` merges an extra map on top of both. Set `JOLT_NO_USER_DEPS=1` to
ignore the user file; useful when it holds JVM-only aliases.

Git clones land in a global, sha-immutable cache shared across projects:
`$JOLT_GITLIBS`, else `~/.jolt/gitlibs`.

### Running a function directly

`-X` calls a function with a single map argument, like `clj -X`:

```clojure
{:aliases {:build {:ns-default myapp.build
                   :exec-fn deploy
                   :exec-args {:env "staging"}}}}
```

```bash
bin/jolt -X:build                       # (myapp.build/deploy {:env "staging"})
bin/jolt -X:build :env '"prod"' :n 3    # k v pairs merge over :exec-args
bin/jolt -X:build myapp.build/other     # an explicit ns/fn wins over :exec-fn
```

`-T` is the same, except the project's own `:paths` and `:deps` are replaced by
the alias's, for running a tool that shouldn't see your project's classpath.

### What's not

- **Pure `clj`/`cljc` only.** A library that needs the JVM (Java interop, host
  classes) or a `clojure.core` feature Jolt doesn't implement will fail to load
  or fail at a call. Coverage is per-function: a namespace can load with most
  functions working and a few not. This applies to Maven deps too; the JAR's
  Clojure source is what runs, and compiled `.class` files are ignored.

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
always set, elsewhere the form is skipped; the same idiom babashka scripts
use with `babashka.version`. See [Dependencies (jolt.deps)](/docs/api/deps.html)
for the full API, and [Running a script](/docs/getting-started.html#running_a_script)
for shebang lines, arguments and exit codes.

## Building binaries

`jolt build` compiles a namespace and its dependencies into a standalone binary:

```bash
JOLT_PWD=/path/to/project bin/jolt build -m my.app
```

The binary contains the runtime + app forms + native launcher, with no Jolt source or Chez
install needed on the target machine (a C compiler and Chez kernel dev files are needed
at build time only).

### How AOT compilation works

`jolt build` does not bundle source or an interpreter. At build time each reachable
namespace is taken through the same `analyze → emit` pipeline the REPL uses, but the
final `eval` is replaced by *accumulate-then-compile*: every form is analyzed and
emitted to Scheme, the emitted Scheme is concatenated into one program, and that program
is handed to Chez's native compiler and linked into a boot file embedded in the
executable. The result is compiled Chez native code (a fasl boot image + native
launcher), not Clojure source; at runtime there is nothing to read or recompile, and no
source roots are consulted. This is the same machinery `jolt` itself uses to bake its
own runtime + compiler into the distributed binary (that is why a built `jolt` boots in
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
   (`compile-file` → `make-boot-file`), convert the boot to Chez's vfasl format
   ([below](#the_boot_image)), embed the resulting boot as C bytes, and
   `cc`-link it against the Chez kernel (`libkernel.a`) into one self-contained
   executable. App libraries are baked in here, so the binary carries no on-disk source
   dependency.

Two consequences are worth knowing. First, an app that never calls `eval`/`load-string`
ships *without* the compiler image; the build detects those calls and drops the compiler
when it can, so a closed-world binary is smaller. Second, because the whole program is
visible at once, whole-program type inference runs across namespaces (field reads
specialize, protocol calls devirtualize), something the per-form REPL path can't do.
The modes below control how far that optimization goes.

### The boot image

Since 0.8.5 the embedded boot ships in Chez's **vfasl** format. An ordinary boot is a
fasl stream the kernel walks object by object, allocating as it goes; a vfasl boot is a
prebuilt image of what that walk would have produced, loaded straight into the static
generation. The load stops allocating and the compaction that ends startup has far less
to compact; together with no longer rebuilding the embedded source into the heap on
every run, that is what halved a built binary's startup in 0.8.5.

An image takes more room than the stream it replaces, so the default trades binary size
for startup. **`--boot`** chooses where on that trade to sit:

| `--boot` | boot image | for |
| --- | --- | --- |
| `fast` (default) | vfasl, LZ4-compressed | the fastest start |
| `small` | vfasl, gzip-compressed | the smallest binary that still loads as an image |
| `plain` | no vfasl | the pre-0.8.5 boot |

```bash
jolt build -m myapp.core --boot small
```

`JOLT_BOOT=small` in the environment and `:jolt/build {:boot :small}` in `deps.edn` do
the same thing; the environment variable is the one a CI job can set without editing the
build command. `--no-vfasl` (with `JOLT_NO_VFASL=1` and `:jolt/build {:no-vfasl true}`)
is an alias for `--boot plain`.

Where more than one of them says something, the command line wins over `deps.edn`, which
wins over the environment, and within each of those the explicit `--boot` spelling wins
over the `--no-vfasl` alias. A blank environment variable reads as unset, so
`JOLT_BOOT=` behaves as if it were not exported at all.

**For a mobile app, `small` is usually the one, not `plain`.** The size cost is mostly
the compression codec's rather than vfasl's, so a gzip image is smaller than the plain
boot *and* still faster to start than one. Two apps, two machine types — binary size and
warm start, against the plain boot as the baseline:

| app / target | `plain` | `fast` | `small` |
| --- | --- | --- | --- |
| hello, host `ta6le` | 25,919,203 · 495 ms | +5.5% · 249 ms | **−35.7% · 429 ms** |
| build-app, host `ta6le` | 26,062,746 · 502 ms | +5.8% · 250 ms | **−35.5% · 434 ms** |
| hello, target `tpb64l` | 24,873,035 | +5.8% | **−38.1%** |

`plain` remains available because a target that cannot vfasl at all still needs it — not
because it is the size answer.

Measure your own app rather than quoting those ratios, because they are a property of
what the image holds rather than of the machine. The same three encodings applied to
Chez's own boots, which carry no jolt runtime, cost `fast` +37% and gain `small` only
3–4%, with `small` there *slower* than `plain`.

#### The LZ4 entry ceiling

A Chez kernel cannot read back a large enough LZ4-compressed fasl entry: an integer
overflow in its length check, which jolt cannot patch, since the Chez it links against is
the one on your machine. It matters for boot images specifically: a vfasl boot is one
entry per input boot file rather than one per top-level form, so a large enough program
becomes a single oversized entry, and for one release that produced a binary that built
cleanly and then died on startup.

Where the limit falls is undefined behaviour in the kernel, so it is not the same
everywhere: 256 MiB on some platforms and 512 MiB on others, decided by what the C
compiler did with an overflowed multiplication. Builds re-encode at 256 MiB, the lower of
the two, so nothing they leave on LZ4 can fail to load. On a platform whose real limit is
512 MiB an image in between is re-encoded when it did not have to be, which costs
decompression speed and nothing else.

An over-ceiling image is re-encoded with gzip, which has no such limit, printing:

```
jolt build: note — the boot image is at or over Chez's LZ4 fasl ceiling;
  re-encoding it with gzip (slower to decompress, but it loads)
```

Nothing changes for an image under the ceiling. If you see that note, the binary is
correct and starts more slowly than it otherwise would — `--boot small` asks for the
same encoding deliberately, and `--boot plain` opts out of images entirely.

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
Add `--direct-link` to also enable the inline + scalar-replace fixpoint; this gives the
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
   fixnum promise; arithmetic on it raises on 61-bit overflow instead of
   promoting to bignum. Use `^long` only where overflow is impossible.
3. **`(double x)` / `(long x)` casts** where inference can't see (a value
   from I/O, an untyped map, a dynamic call). The cast keeps its full Clojure
   semantics (throws on non-numbers, `(long 1.5)` truncates) and types the
   result like a hint. Portable: the same code speeds up on the JVM.

Inference stays sound by widening: a conflicting, escaping, or unprovable type
falls back to the generic (boxed, correct) path, never a wrong answer.
Interactive modes (`repl`, `-e`, `nrepl-server`) skip whole-program passes so
redefinition keeps working.

### deps.edn build options

The `:jolt/build` map in `deps.edn` accepts these keys:

- **`:opt true`**: build in optimized mode (like `--opt`)
- **`:direct-link true`**: closed-world direct linking (like `--direct-link`)
- **`:tree-shake true`**: drop unreachable library code (like `--tree-shake`)
- **`:boot :fast|:small|:plain`**: how the boot image is encoded (like `--boot`) —
  startup against binary size ([above](#the_boot_image)). `:no-vfasl true` is an
  alias for `:boot :plain`.
- **`:embed [dirs]`**: bake resource files into the binary so `io/resource` resolves
  with no files on disk
- **`:dynamic-natives true`**: load native shared objects at runtime instead of
  statically linking

Example:

```clojure
{:paths ["src"]
 :jolt/build {:opt true
              :direct-link true
              :tree-shake true
              :embed ["resources"]}}
```
