# RFC 0007 — Compilation modes and binary output

- **Status**: Implemented
- **Champions**: jolt maintainers

## Summary

`jolt build` emits a standalone executable, and a three-mode model trades
dynamism for speed:

- **dev** — open/indirect linking, redefinition works, minimal optimization.
  What `repl`/`-e`/`nrepl` are.
- **release** (default for a built program) — const-folding and numeric
  lowering, a recognizable Clojure runtime.
- **optimized** (`--opt`) — type inference, devirtualization, and inline
  caches; add `--direct-link` for the inline + scalar-replace fixpoint and
  `--tree-shake` to drop unreachable code. Fastest, gives up runtime
  redefinition of direct-linked vars.

The [Building & Running](/docs/building-and-deps.html) guide has the CLI and the
per-flag pass matrix; this RFC records the design the flags express.

## The dynamism–speed axis

The modes are points on one axis — how much the back end may assume is fixed —
not three separate code paths. Two independent knobs set each point:

- **direct-link?** — may a call to a var compile to a direct procedure
  reference instead of a `var-deref`? A direct call skips the var lookup and
  generic dispatch, and it enables inlining and call-site folding. The opt-out
  is per-target: a `^:redef` or `^:dynamic` var always links indirectly, so it
  can still be redefined.
- **whole-program?** — does inference see the whole reachable program at once
  (closed world), so a record parameter's callers in other namespaces are
  visible and its field reads specialize? Without it, inference is
  per-namespace and a cross-namespace parameter widens to `:any`. A declared
  `^RecordType` hint is the open-world escape hatch that recovers the
  specialization without closing the world.

```
dev:        direct-link? = false   whole-program? = false
release:    direct-link? = false   whole-program? = false   (+ const-fold, numeric lowering)
optimized:  direct-link? = true    whole-program? = true    (with --direct-link / --tree-shake)
```

## The optimization passes

Every build runs `analyze → emit`; the modes decide which passes run in between.

- **const-fold** (`jolt/passes/fold.clj`) — constant folding, including
  predicate folding against inferred types. On in every mode.
- **numeric `fl*`/`fx*` lowering** (`jolt/passes/numeric.clj`) — a `^double` or
  `^long` hint drives unchecked flonum/fixnum Scheme ops (`fl+`/`fx*`/…) with
  coercion at parameter entry and function return, in place of the arbitrary-
  precision numeric tower. On in every mode; the hints are contracts the pass
  enforces, so unhinted integer code keeps bignum semantics. See RFC 0004.
- **structural type inference** (`jolt/passes/types.clj`) — collection- and
  record-type inference (RFC 0005) plus success-type checking (RFC 0006). On
  under `--opt`. Whole-program inference (closed world) proves the record and
  numeric types that let the back end drop dispatch soundly.
- **inline + scalar-replace** (`jolt/passes/inline.clj`) — inlining, let-
  flattening, and scalar replacement of short-lived records. On under `--opt
  --direct-link`, since a direct call is what makes a callee's body available
  to splice.
- **devirtualization and inline caches** — a monomorphic protocol call resolves
  its implementation once and caches it per call site; a var reference caches
  the resolved var cell. On under `--opt`.

`--tree-shake` adds whole-program dead-code elimination: reachability from
`-main` plus the non-prunable forms (protocol/record/multimethod registrations
and their dispatch targets stay live), dropping unreached `defn`s. It bails to
keep-everything if reachable code calls `resolve`/`eval`/`load-string` or the
other openings that defeat static reachability.

## Emission pipeline

The in-process spine, per top-level form, is:

```
source → read → analyze (→ IR) → emit (→ Scheme) → eval
```

`jolt build` keeps everything up to `emit` and replaces the per-form `eval`
with accumulate-then-compile:

1. **Assemble.** From the entry namespace's `-main`, load the transitive
   `require` graph and collect every reachable top-level form, in dependency
   order, with its compile namespace.
2. **Dead-code elimination** (`--tree-shake`) — as above.
3. **Emit to a file.** Run `analyze → emit` for each surviving form under the
   mode's knobs, concatenating the Scheme into one program source (the
   `clojure.core` overlay prelude first, in tier order).
4. **Compile and link.** Feed that source to Chez's native compiler and link a
   boot file plus the compiled program into a single executable. App libraries
   are baked in — no source roots are needed at runtime.

## Binaries, FFI, and `eval`

- **FFI.** `jolt.ffi` loads native libraries at runtime via `dlopen`. A build
  bakes the Clojure side of a binding and keeps the dynamic load at run time,
  so a closed-world binary still binds native code. `:jolt/build
  {:dynamic-natives true}` keeps optional native loads out of the image so a
  missing library is skipped instead of aborting startup.
- **`eval` at runtime.** release and optimized builds are closed-world, but an
  app that calls `eval`/`load-string` needs the compiler present. The build
  keeps the compiler image when it detects those calls and drops it otherwise
  (the tree-shake bail-out detects the same openings), so an app that never
  evaluates code at runtime ships a smaller binary.
- **`--library`** builds a managed-runtime shared library instead of an
  executable, for embedding jolt in a host process.

## Related

The optimizations these modes turn on are specified in RFC 0004 (type hints),
RFC 0005 (structural inference), and RFC 0006 (success checking). The linking
model — direct linking as a per-unit property with `^:redef`/`^:dynamic` as the
only opt-out — is described under [Building & Running](/docs/building-and-deps.html).
