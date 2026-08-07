# RFC 0010 — The portable Scheme layer

- **Status**: Implemented (jolt-lang/jolt#446, rounds R1–R10)
- **Champions**: jolt maintainers

## Summary

jolt's host layer historically assumed Chez Scheme everywhere: unsafe `#3%`
primitives in emitted code, `foreign-procedure` at FFI sites, `fasl-write` for
images, `machine-type` for platform branches, Chez's inspector for backtraces.
That made a second target — Guile, or a WASM-hosted Scheme — undefinable: there
was no list of what a target must provide, and no seam to provide it through.

The portable Scheme layer replaces "Chez everywhere" with two explicit
surfaces:

- a **contract** — the names every target must provide, with pinned semantics
  (`host/scheme-adapter/CONTRACT.txt`), and
- an **adapter** — named `sa-*` capability entry points that own the
  genuinely non-portable operations
  (`host/chez/scheme-adapter-runtime.ss`).

A lint gate keeps the boundary honest: no host file may use a Chez-only name
directly, and the allowlist that records sanctioned uses may only name
**target-owned files** — the per-target implementations a port replaces
wholesale rather than migrates.

## Contract names vs forbidden names

The census (every handwritten host `.ss` read as data, ~2100 call sites at the
start) splits Chez dependence into two kinds:

**Contract names** are portable in meaning but not spelled by R6RS: SRFI-18
thread shapes, hashtable extensions, `format`, `sort`, boxes, `gensym`,
`interaction-environment` (R7RS `(scheme repl)`), `scheme-version`. On Chez
these are natives, so the Chez adapter is *assert-only* — zero wrappers, zero
cost. `CONTRACT.txt` records each name's call shape and, where jolt depends on
behavior a standard does not pin, the exact semantics a target must match:

- thread-parameters are **fork-inherited** — dynamic-binding conveyance into
  agent/async/future workers relies on this alone;
- mutexes are **non-recursive** — reentrancy is jolt's job (monitors carry an
  owner and a count), so a target's recursive locks must not be load-bearing;
- `condition-wait` may wake **spuriously** — every wait site loops on its
  predicate;
- a **blocking** foreign call must not stop other threads' garbage collection
  (Chez: `__collect_safe`).

**Forbidden names** are the never-portable core: FFI construction and foreign
memory, `compile-file`/boot assembly, fasl serialization, the inspector, GC
hooks, `machine-type`. No host file may call these directly. Each group is
owned by a *capability* with named entry points and a documented degradation.

## Capabilities and degradation

Degradation is explicit and honest — an absent capability raises or returns
empty; it never fakes.

| capability | entry points (examples) | a target without it |
|---|---|---|
| system | `sa-run-process`, `sa-real-time-ms`, `sa-file-mtime-ms`, GC/memory reads | subprocess spawn raises; GC hints may no-op; clocks are required |
| introspect | `sa-continuation-frames`, `sa-procedure-info`, `sa-stats` | backtraces render without frames (throw type + message survive); image closure capture refuses |
| ffi | `sa-foreign-alloc/free/ref/set!/sizeof`, `sa-load-shared-object`, `sa-foreign-procedure(-blocking)`, `sa-foreign-callable(-collect-safe)`, `sa-foreign-procedure-runtime` | `jolt.ffi/load-library` raises a clean jolt-level error; degradation raises must be message-carrying conditions |
| native-compile | `sa-compile-file` (target-neutral profile), `sa-make-boot-file` | the AOT cache quietly disables and code loads from source; `jolt build` reports the target cannot build native binaries |
| image | `sa-fasl-write`, `sa-fasl-read` | `jolt.image` dump/restore raise cleanly |

Platform identity is derived, never matched on: logic branches use
`sa-os-family` / `sa-arch` / `sa-endian`; the raw host tag (`sa-host-tag`)
appears only in names — release directories, image headers, telemetry.

Threads are their own tier: the contract's SRFI-18 shapes plus a written
degradation design (`host/scheme-adapter/THREADS.md`) for single-threaded
targets — synchronous agents and futures with an explicit rule per construct
for what must stay observably honest and what must raise.

## Emitted code

The compiler emits through the same boundary. The backend's unsafe-primitive
spellings (`#3%vector-ref` in the polymorphic inline cache, `#3%fl+` on
proven-double arithmetic) come from a per-target primitive table keyed by
meaning; a target without unsafe variants maps the prefix to the empty string
and every site degrades to the checked op — slower, never wrong. FFI special
forms (`jolt.ffi/defcfn`, callbacks) lower to the `sa-foreign-*` syntaxes, so
generated code is as portable as handwritten code. For the Chez target this is
proven byte-exact: the minted seed prelude is byte-identical before and after
the change.

## Enforcement

Four standing gates (all in `make test`):

- **portcheck** — reads every handwritten host `.ss` as data and fails on any
  blocklisted identifier outside the allowlist, on stale allowlist lines, and
  on any allowlist line naming a file that is not target-owned.
- **adaptercheck** — proves every contract name and `sa-*` entry point is
  bound (including syntax bindings).
- **census** — regenerates the full per-tier inventory.
- **degradedbacktrace** — runs with introspection forced off and proves a
  throw still surfaces type and message.

Today exactly two files are target-owned: the adapter runtime itself and
`hasheq.ss` (the Chez-tuned hash implementation, whose exported surface a
target reimplements with safe ops).

## What porting looks like

A new target starts from `host/scheme-adapter/guile.ss` — a structural stub
mirroring the Chez adapter section by section. The work is: satisfy the
contract names (most exist under other spellings), implement or explicitly
degrade each capability's `sa-*` entry points, reimplement the two
target-owned files, give the backend a primitive table entry, and match the
pinned semantics — the stub marks each place where "Guile has this" still
needs "and it behaves the same" verified. `TARGET-CONTRACT.md` in
`host/scheme-adapter/` is the porting document.

## Non-goals

This work defines the boundary; it does not ship a second target. Nothing
here changes behavior on Chez: entry points are transparent one-liners the
compiler inlines (measured on the per-byte FFI hot paths: within noise), and
the seed, the corpus, the conformance fleet, and the release gates are
unchanged.
