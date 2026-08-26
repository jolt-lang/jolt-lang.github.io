`jolt.continuations` gives a Jolt program an **escape continuation**: a `return` you can invoke from any depth, including from inside a callback a library called on your behalf. Chez captures continuations natively and Jolt's runtime already runs on them — the fiber park/resume switch, the throw site a backtrace is walked from, the state image — so this namespace hands an existing capability to your code rather than adding machinery.

JVM Clojure has no equivalent and cannot have one: the JVM can't capture its stack. Code that uses this is Jolt-only by design, like [`jolt.scheme`](/docs/host-interop.html). It's purely additive — nothing about an existing Clojure program changes because this namespace exists.

```clojure
(require '[jolt.continuations :as k])

;; return the first value over 3, from inside a doseq
(k/letcc [return]
  (doseq [x (range 100)]
    (when (> x 3) (return x)))
  :never-reached)
; => 4
```

## The two forms

- `call-cc` `f` — call `f` with an escape continuation. Returns `f`'s value, or the value passed to the escape if `f` invokes it.
- `letcc` `[name] body...` — the same thing, macro-shaped, with the escape bound by name.
- `escape-fn?` `x` — true when `x` is an escape handed out by either.

```clojure
(k/call-cc (fn [escape] :fell-through))       ; => :fell-through
(k/call-cc (fn [escape] (escape :early) :no)) ; => :early
(k/letcc   [escape]     (escape :early) :no)  ; => :early
```

## What it's for

An escape is the `return` you would otherwise write with `reduced`, a `some` over a transformed seq, or an exception used for control flow. It differs from those in reach: it unwinds out of **any** depth, including out of a function you handed to someone else's higher-order code.

```clojure
;; run! gives you no way out — reduced doesn't apply, and there's no early exit
(k/letcc [found]
  (run! (fn [x] (when (= x :b) (found x))) [:a :b :c])
  :none)
; => :b
```

Reaching for it where `reduced` or `some` already fits is a step down in clarity, not up. It earns its place when the loop isn't yours.

## An escape is a real exit

Unwinding runs the winders between the capture and the escape, exactly as a thrown exception would: a `finally` in between runs, and a `binding` is restored.

```clojure
(k/letcc [ret]
  (try (ret :escaped)
       (finally (println "cleanup ran"))))
; cleanup ran
; => :escaped
```

This is the opposite of a fiber park, which deliberately *drops* those winders — a park is not an exit, and the computation is coming back. Parking between the capture and the escape is fine within one fiber: the scheduler captures and restores the fiber's whole stack segment, escape included.

## One-shot, and only inward

An escape is valid **at most once**, and only while its own `call-cc` is still running. Re-entrant continuations — resuming a computation that already finished, generators built by re-invoking a saved continuation — are not supported.

They also never half-work. Each misuse raises `IllegalStateException` naming the rule it broke:

```clojure
(let [e (k/letcc [e] e)] (e 1))
; IllegalStateException:
; jolt.continuations: this escape is no longer live — its call-cc already returned
```

The three rules: an escape can't be invoked twice, can't be invoked after its `call-cc` returned, and can't be invoked from a thread or fiber other than the one that captured it. The last is not pedantry — the raw host primitive *hangs the process* there, with no error at all, so the check is what turns a silent hang into a message.

## Cost

A capture is about 86ns, paid once per `call-cc` — not per iteration of whatever the capture wraps. Invoking the escape is cheap. That makes it a fine wrapper around a loop or a traversal and a poor one inside the loop body.
