# Scheme Backends

Jolt is Clojure on Scheme, not Clojure on one Scheme. The compiler emits
portable Scheme through a small primitive table, and everything
host-specific in the runtime sits behind an explicit adapter contract. Two
backends exist today:

| Backend | Status | How it runs |
|---|---|---|
| **Chez** | The flagship. Every gate, library, and release runs here. | Native, incremental compiler, full capability set: FFI, OS threads, AOT binaries, program images. |
| **Gambit** | Demo-grade. Powers the live REPL on this site's front page. | Native (`gsi`) or compiled to a single JavaScript file (`gsc -target js`) that boots in the browser in about a second. |

The rest of this page explains how the porting seam works, what a new
target must provide, and what it may leave out. The authoritative porting
document lives in the repo at `host/scheme-adapter/TARGET-CONTRACT.md`,
with the machine-readable name inventory in
`host/scheme-adapter/CONTRACT.txt`; the design history is
[RFC 0010](/docs/rfc/0010-portable-scheme-layer.html).

## The architecture of a port

The runtime is written against three layers:

1. **Portable Scheme.** Most of `host/chez/*.ss` — the collections, seqs,
   reader, printer, vars, multimethods — is R6RS-flavored Scheme that a
   port reuses unchanged. A lint gate (`make portcheck`) keeps
   Chez-only names out of these files.

2. **The adapter contract.** Every host capability goes through `sa-*`
   entry points: `sa-real-time-ms`, `sa-file-mtime-ms`,
   `sa-foreign-procedure`, `sa-continuation-frames`, and so on.
   `CONTRACT.txt` lists ~68 names grouped into capability tiers:

   - `system` — clocks, environment, exit. Every target implements these.
   - `threads` — OS/green threads, mutexes, condition variables,
     thread-inherited parameters.
   - `introspect` — continuation frames for backtraces.
   - `ffi` — foreign procedures and callables.
   - `native-compile` — AOT compilation of emitted code.
   - `image` — heap image dump/restore.

   A target implements a tier or **degrades it honestly**: an absent
   capability raises a message-carrying error or returns empty — it never
   fakes a result. The Gambit backend runs with `ffi`, `native-compile`,
   and `image` degraded, and that is a supported configuration.

3. **Target-owned files.** Two files are owned by each target rather than
   shared: the adapter itself (`scheme-adapter-runtime.ss`) and the hash
   kernel (`hasheq.ss`), whose Chez build uses unsafe fixnum ops that
   other Schemes spell differently. A port also supplies whatever prelude
   shims its host needs — Gambit's (`host/gambit/prelude-shims.ss`) maps
   the R6RS record, hashtable, and `fx` surfaces onto Gambit natives.

## The compiler side

The backend emits Scheme once; per-target differences go through a
primitive table (`target-prims` in `jolt-core/jolt/backend_scheme.clj`).
The main entry is the unsafe-op prefix: on Chez, proven-safe sites emit
`#3%`-prefixed ops; a target that maps the prefix to the empty string gets
checked ops everywhere — safe, portable, and slower. FFI lowerings go
through four `sa-foreign-*` syntax forms the adapter defines.

The seed — `clojure.core` plus the compiler image, as emitted Scheme — is
**cross-minted on Chez** for each target: `make gambitseed` runs the
emitter with the backend set to `:gambit` and writes
`host/gambit/seed/{prelude,image}.ss`. A port never bootstraps itself from
scratch; the working Chez build mints it.

## What a host must provide

Hard requirements, learned the hard way and now pinned by gates:

- **R7RS-level basics** plus: `syntax-rules` macros, `call/cc`,
  proper tail calls, the full numeric tower (exact integers, bignums,
  ratios, flonums), string ports, and Unicode strings.
- **`eval` into a persistent top-level environment** — a define evaluated
  at runtime must be visible to later evals. This is the entire runtime
  compile path (`jolt-compile-eval` evaluates emitted text).
- **Records with parent types and inclusive predicates** (R6RS-style).
  If the host's native records can't express parents — Gambit's can't —
  the port implements records over another representation (Gambit uses
  tagged vectors with a type registry).
- **Hashtables** with eq/eqv/equal semantics and weak-key tables.
- **Threads** (for the full tier): parameters must inherit into new
  threads, and the port must map its condition-variable idiom onto the
  wait/signal shape the runtime assumes.

Things a standard does not give you that the contract pins explicitly:
error objects that carry their message and irritants; hash values that are
stable 32-bit ints (the hash kernel must match Chez's murmur3 output
bit-for-bit — the `gambitcheck` gate carries known-answer rows captured
from the Chez build); and the exact laziness of the seq tier.

## What the Gambit port looks like

`host/gambit/` is a worked example of the whole recipe, about 3k lines of
which the majority is generated or mechanical:

- `prelude-shims.ss` — records, hashtables, `fx`/`fl` spellings,
  conditions, threads mapped onto Gambit.
- `scheme-adapter-runtime.ss` — the adapter: system tier real, `ffi` /
  `native-compile` / `image` raising cleanly.
- `hasheq.ss` — the hash kernel in checked ops, byte-parity with Chez.
- `rt-core.ss` — the target's kernel (var cells, exceptions, dispatch
  registries), ported from `rt.ss`'s spec with the Chez FFI regions left
  out.
- `records-gambit.ss`, `seed/` — **generated on Chez**, never edited:
  pre-expanded macros and the cross-minted seed.
- Gates: `make gambitcheck` (adapter + shims), `make gambitkernel`
  (the booted kernel, 113 checks), `make gambiteval` (jolt source through
  the compiler, renders pinned to Chez captures).

### Choosing how much of the language to build

A backend that exists to be small needs a way to say what it leaves out.
`host/gambit/profiles.ss` names optional **feature groups** — regex, the
compiler, `clojure.core` itself — and profiles built from them; a generator
writes the boot file for a profile on Chez, because Gambit resolves its
includes at expansion time and the choice cannot be made at runtime.

```bash
make gambitweb PROFILE=repl    # clojure.core + compiler, no regex
```

Excluding a group leaves its files out **and binds every name it owned to a
raise that names the group**, derived by scanning the excluded files rather
than from a hand-kept list, so the error surface cannot drift from the code:

```
user=> (re-seq #"[a-z]+" "ab cd")
java.lang.UnsupportedOperationException: jolt-re-pattern is not in this build:
the regex feature group was excluded
```

A predicate over a type the build cannot hold answers `false` instead of
raising — a value simply is not a regex — while anything that would produce or
consume that type raises. This is the same rule the capability tiers follow one
level down, and it is why a reduced build is diagnosable rather than mysterious.

The measured cost of each group is in the repo's README. The short version: the
Gambit runtime plus jolt's kernel is about two thirds of the bundle and cannot
be dropped, so profiles trade features for the remaining third.

### Limitations of the Gambit backend

- **Demo-grade, not production.** The Chez backend is where jolt is
  gated, benchmarked, and released. Gambit exists to prove the porting
  seam and to run in the browser.
- **No FFI, AOT, or images** — those tiers raise. `jolt build`
  standalone binaries are Chez-only.
- **Single-threaded boot.** Gambit satisfies the threads contract
  (its parameters fork-inherit), but the demo boot stubs the concurrency
  tier: `future`/`agent`/`promise` raise.
- **Checked ops only** — no unsafe fast paths, and on the JavaScript
  target all fixnum ops are generic (JS fixnums are ~30 bits; 32-bit hash
  values overflow them).
- **Interpretation-speed eval.** The browser REPL compiles each form
  and evaluates it through Gambit's interpreter — fine for a REPL,
  not for benchmarks.

### JavaScript-target notes

Compiling the same boot to JS (`gsc -target js -exe`) surfaced a class of
issues that any Scheme-to-JS port should expect: names bound in the
interpreter but unresolved in compiled units (`let-values`,
`equal?-hash`, and every `(gambit)` export the unit redefines), no
filesystem at runtime (everything must compile into the unit), float
second clocks, and `eval` of `define-syntax` requiring an expander module
the executable doesn't embed (jolt registers function equivalents
instead). The commit history of `host/gambit/` documents each with its
fix.

## Adding a new backend

Start from `host/scheme-adapter/guile.ss`, the structural stub that
mirrors the Chez adapter section by section. The short version of the
recipe:

1. Satisfy the `CONTRACT.txt` names for the tiers you implement; degrade
   the rest honestly.
2. Write your prelude shims and `hasheq.ss`; get `gambitcheck`-style
   known-answer rows green first — hash parity is load-bearing for every
   collection.
3. Boot the portable kernel file by file; port `rt.ss`'s surface into
   your own `rt-core.ss` where its Chez regions don't apply.
4. Add your `target-prims` entry, cross-mint the seed on Chez, and wire
   the eval path.
5. Gate each layer as you go — the Gambit port's three gates are the
   template.

The Gambit port took the equivalent of a few focused days with the
contract in place; the contract itself (RFC 0010) took much longer. That
asymmetry is the point.
