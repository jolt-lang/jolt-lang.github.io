# RFC 0011 — Tail calls are guaranteed

- **Status**: Implemented
- **Champions**: jolt maintainers

## Summary

jolt guarantees proper tail calls. Any call in tail position — a self-recursive
call, a mutual call, or a tail call into another function entirely — compiles
to a jump and runs in constant stack. `recur` remains, unchanged, as the
explicit JVM-compatible spelling of the same jump.

## Why the JVM couldn't make this promise

JVM Clojure declines implicit tail-call optimization on purpose. Rich Hickey,
on why `recur` exists instead:

> While making self tail-calls into jumps would be easy (after all, that's
> what recur does), doing so implicitly would create the wrong expectations
> for those coming from, e.g., Scheme, which has full TCO. … Essentially it
> boils down to the difference between a mere optimization and a semantic
> promise. **Until I can make it a promise, I'd rather not have partial TCO.**

The JVM cannot honor the promise: tail calls to other functions would require
unrestricted goto-like stack manipulation that fails bytecode verification,
and trampoline-based workarounds return values a Java caller cannot
interpret. A partial guarantee would be a trap, so Clojure made tail calls
explicit and bounded (`recur`, `trampoline` for mutual recursion).

Chez Scheme imposes no such constraint — its compiler eliminates tail calls
natively, and "tail call" means any call in tail position, not just
self-calls. Compiling to Chez, jolt inherits the guarantee the JVM could not
make.

## The guarantee, concretely

Self-recursion without `recur` runs in constant stack:

```clojure
(defn f [n] (if (zero? n) :done (f (dec n))))
(f 5000000)  ;=> :done
```

Mutual recursion, which on the JVM requires `trampoline`, is a plain call
chain:

```clojure
(declare odd2)
(defn even2 [n] (if (zero? n) true  (odd2 (dec n))))
(defn odd2  [n] (if (zero? n) false (even2 (dec n))))
(even2 2000000)  ;=> true
```

`loop`/`recur` and `fn`/`recur` behave exactly as on the JVM — `recur` still
enforces tail position and arity at compile time; it is simply no longer the
only way to get a constant-stack loop.

Non-tail recursion still consumes stack, as it does on the JVM. The guarantee
covers tail positions, nothing else.

## Compatibility

- **JVM Clojure code runs unchanged.** Code that uses `recur` compiles to the
  same jump it always did. Code that recurses implicitly by name — which
  overflows the JVM stack at depth — now simply works.
- **jolt code may rely on the guarantee.** Mutual recursion without
  `trampoline`, implicit self-recursion in tail position: legal jolt, and a
  superset of JVM Clojure, where the same source overflows.

This is the standing pattern for jolt's superset features: never change what
existing Clojure code means, only add behaviors the JVM platform foreclosed.

## Related

RFC 0012 (there is no interpreter) — the back end making this guarantee real;
RFC 0007 (compilation modes) — the passes layered above it. `jolt.continuations`
is the control-flow sibling of this RFC: the same Chez capability, exposed as
one-shot escape continuations (`call-cc` / `letcc`) that unwind out of any
depth, including out of a callback a library invoked. Like this guarantee it is
something the JVM platform forecloses, so code using it is jolt-only by design.
