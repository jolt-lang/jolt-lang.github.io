# RFC 0012 — There is no interpreter

- **Status**: Implemented
- **Champions**: jolt maintainers

## Summary

Every jolt entry point — `jolt -e`, the REPL, nREPL, `eval`, `load`,
`load-string`, and `jolt build` — compiles Clojure source to Scheme and hands
it to Chez's native compiler. There is no tree-walking evaluator, no bytecode
VM, and no interpreted fallback path anywhere in the system.

## Why

JVM Clojure keeps an interpreter next to its compiler (for `eval` at runtime
and for interpreter/compiled parity in dev workflows), which means two
execution engines must be kept semantically identical and separately
optimized. jolt never built the second engine.

The bottom of the stack makes this cheap: Chez Scheme itself has no
interpreter — it incrementally compiles everything to native machine code,
including forms evaluated one at a time. So "evaluate this form" in jolt is
"compile this form's Scheme emission and run the native code," whether at the
REPL or inside a release binary.

## The spine

The in-process pipeline, per top-level form (RFC 0007):

```
source → read → analyze (→ IR) → emit (→ Scheme) → eval
```

`jolt build` replaces the per-form `eval` with accumulate-then-compile: emit
the whole program's Scheme, compile it as a unit, and link a native
executable. The front half — read, analyze, emit — is the same code in both
cases. Compilation *modes* (dev / release / optimized, RFC 0007) select which
optional passes run between `analyze` and `emit`; they never select between
compiling and not compiling.

## Consequences

- **The program you develop is the program you ship.** No behavior differences
  between REPL-evaluated definitions and built-binary definitions, because
  both are native code produced by the same emitter.
- **`eval` is compiled code.** An `eval`'d form pays the same compile cost and
  gets the same runtime representation as any other form; there is no slower
  path it can silently take. Under `--opt`, runtime `eval` is what keeps the
  compiler image in the binary (RFC 0007).
- **Startup tiers differ by caching, not by execution model.** The script
  launcher, the dev-cache boot file, and the linked release binary are all
  native Chez code; the tiers change how much is compiled when, never whether.

## Compatibility

Neutral for JVM code: everything that compiles on JVM Clojure compiles here,
and `eval`'s observable semantics are Clojure's. The only divergence is
performance shape — jolt code may assume there is never an interpreted tier to
fall into.

## Related

RFC 0007 (compilation modes + binary output) — what the always-on compiler is
parameterized by; RFC 0011 (tail calls) — a guarantee the compiled back end
provides and an interpreter could not uniformly honor.
