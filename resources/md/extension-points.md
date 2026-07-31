# Extension Points

An extension point is a named contract that lets **Jolt core define behaviour
whose data it does not carry**, and lets a library supply that data later.

The class-shim registries in [Host Interop](/docs/host-interop.html) let a
library add a *class*. Extension points are the other half: they let a library
add *data* to something core already implements. Per-locale currency symbols,
month names and number separators are the working examples — core knows how to
format, it just cannot ship CLDR.

## The problem it solves

`java.text.NumberFormat/getCurrencyInstance` needs a currency symbol, decimal and
grouping separators, and a fraction-digit count, per locale. Core carries none of
that. Two obvious options are both bad:

- **Guess.** Format a German amount with US separators. The number comes out
  wrong and *looks* right, which is the worst kind of failure.
- **Refuse entirely.** Leave the method unimplemented, so nothing that touches it
  works even for the one locale core can answer correctly.

An extension point takes the third path: core declares the shape, answers for the
one key it genuinely knows, and says clearly what is missing for any other.

## Declaring a point

Core declares; you normally only *register* against a point. The declaration is
what makes the contract checkable:

```clojure
(jolt.host/register-extension-point! :currency-data
  {:key      :string
   :root     ""
   :fields   {:symbol :string, :symbol-sep :string, :symbol-first? :boolean,
              :decimal-sep :string, :grouping-sep :string, :frac-digits :long}
   :default  {:symbol "¤", :symbol-sep " ", :symbol-first? true,
              :decimal-sep ".", :grouping-sep ",", :frac-digits 2}
   :fallback :strict
   :hint     "The jolt-lang/time library carries per-locale currency data."})
```

| key | meaning |
| --- | --- |
| `:key` | type of the lookup key — `:string` or `:keyword` |
| `:root` | the one key `:default` is correct for |
| `:fields` | the value type. `:string` `:long` `:double` `:boolean` `:keyword` `:any` |
| `:default` | must be **total** over `:fields` — every field present |
| `:fallback` | `:strict` or `:default`; what an unregistered key means |
| `:hint` | appended to a `:strict` miss, so the error ends in an action |

Registering the same point twice with an identical contract is a no-op.
Registering it with a *different* contract raises — two sources disagreeing about
one contract is drift, not a merge.

## Registering data

This is what a library does:

```clojure
(jolt.host/register-extension! :currency-data "de-DE"
  {:symbol "€" :symbol-first? false :decimal-sep "," :grouping-sep "."})
```

A provider may be **partial**. Lookup is `(merge default provider)`, so you state
only what differs — here `:symbol-sep` and `:frac-digits` come from the default.
Every field you *do* supply is type-checked at registration, so a typo fails
where you wrote it rather than at some later call site.

## Reading a value

```clojure
(jolt.host/extension-value :currency-data "de-DE")
;; => {:symbol "€" :symbol-sep " " :symbol-first? false
;;     :decimal-sep "," :grouping-sep "." :frac-digits 2}

(jolt.host/extension-has? :currency-data "xx")   ; would it resolve without raising?
(jolt.host/extension-epoch)                      ; bumped by every mutation
```

## Choosing `:fallback`

This is the decision that matters, and it is not a style preference.

**`:strict`** — the default is correct *only* for `:root`. Any other unregistered
key raises, naming the point, the key and the hint:

```
No :currency-data provider for key "de". The point carries a default for its
root key "" only; a library must register this key. The jolt-lang/time library
carries per-locale currency data.
```

Use it when a wrong value would be silently wrong. Currency separators qualify:
`1.234,50` and `1,234.50` are both plausible renderings of different numbers.

**`:default`** — the default answers any key. Use it when falling back is the
*correct* behaviour, not merely a convenient one.

`:date-names` and `:number-symbols` are `:default` for a concrete reason: the
JVM's own contract for an unrecognised locale is to fall back to the root locale
rather than fail. `:default` reproduces that mechanism exactly, and `:strict`
would raise where the JVM returns a value.

So: match the platform you are emulating. If it has no answer either, `:strict`
tells the truth; if it falls back, so should you.

## Refining the type

A point's value type can grow without breaking providers already registered:

```clojure
(jolt.host/refine-extension! :currency-data
  {:fields  {:currency-code :string}
   :default {:currency-code "XXX"}})
```

The new field needs a default, so `:default` stays total and every existing
provider picks the new field up through the merge. A field already declared may
be repeated at the same type (an idempotent second load) but not at a different
one.

That gives two independent axes of refinement — a **value** at a key via
`register-extension!`, and the **schema** itself via `refine-extension!`.

## Points core declares today

| point | key | fields | fallback |
| --- | --- | --- | --- |
| `:currency-data` | locale id | symbol, symbol-sep, symbol-first?, decimal-sep, grouping-sep, frac-digits | `:strict` |
| `:date-names` | locale id | months, months-short, days, days-short | `:default` |
| `:number-symbols` | locale id | decimal-sep, grouping-sep | `:default` |

All three are keyed by a **locale id string**, not a `java.util.Locale`. Core has
no `Locale` class — that lives in
[jolt-lang/time](https://github.com/jolt-lang/time) — so core renders whatever
object it is handed through `jolt.host/…` string rendering and uses the result as
the key. A library's own `Locale` type reaches it by registering a `:str` that
yields its id. That is the general pattern: **key a point on a plain value, so
core never has to name a class a library owns.**

Day and month tables are Monday-first, matching CLDR.

## Guarding against an older Jolt

A library may run against a Jolt that predates a point. Registering against an
undeclared point raises, so guard it — narrowly, so a real error still surfaces:

```clojure
(defn- register-point! [point entries]
  (try
    (doseq [[id data] entries] (jolt.host/register-extension! point id data))
    true
    (catch Exception e
      (if (re-find #"no extension point" (or (ex-message e) ""))
        false                                    ; older Jolt: skip this point
        (throw e)))))                            ; a real provider error
```

## Notes

- Registration is a side-effecting top-level form, so tree-shaking keeps it and
  an AOT build sees providers registered at load.
- Every mutation bumps `extension-epoch`, so a library required *after* a call
  site has already resolved a value invalidates it rather than stranding a stale
  one.
- Keep providers to measured values. All three built-in point tables were
  generated from reference JVM output and are verified by re-deriving them and
  diffing — a locale table is exactly the kind of data where a plausible guess is
  indistinguishable from a correct value until someone in that locale reads it.
