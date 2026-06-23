Jolt runs on Chez Scheme, not the JVM, so there are no real Java classes behind interop forms. Instead the runtime ships shims for the slice of the JVM standard library that portable Clojure code reaches for, so libraries written against `clojure.core` and common `java.*` classes run unchanged. The Clojure interop syntax works against these shims:

```clojure
(Math/sqrt 2)                  ; static call
Math/PI                        ; static field
(StringBuilder.)               ; constructor
(.append sb "x")               ; instance method
(instance? String "hi")        ; class token
```

A class token (`String`, `java.util.UUID`, …) resolves to a name; there is no reflection and no class hierarchy. `(class x)` returns the JVM class name for the scalar and collection types Clojure programs compare against.

## What's shimmed

This is the surface today, not the whole JVM. Methods not listed generally aren't implemented; a few are accepted but no-ops.

### Numbers and language

- **`java.lang.Math`** — `sqrt` `cbrt` `pow` `exp` `log` `log10` `floor` `ceil` `round` `abs` `max` `min` `sin` `cos` `tan` `asin` `acos` `atan` `signum` `random`; `PI`, `E`. (`clojure.math` mirrors these as functions.)
- **`Long` / `Integer`** — `parseLong`/`parseInt`/`valueOf` (optional radix), `MAX_VALUE`, `MIN_VALUE`.
- **`Double` / `Float`** — `parseDouble`, `valueOf`, `toString`, `isNaN`, `isInfinite`, the value/infinity/NaN fields.
- **`Boolean`** — `parseBoolean`, `TRUE`, `FALSE`.
- **`Character`** — `isUpperCase` `isLowerCase` `isDigit` `isWhitespace` (ASCII).
- **Boxed-number methods** — every number answers `.intValue` `.longValue` `.doubleValue` `.byteValue` `.shortValue` `.toString` `.hashCode`.
- **`java.lang.System`** — `currentTimeMillis` `nanoTime` `exit` `getProperty` `setProperty` `getProperties` `getenv`.
- **`java.lang.Thread`** — `sleep` (real), `yield`/`interrupted` (no-ops), `currentThread`.

### Strings, collections, I/O

- **`String`** statics — `valueOf`, `format` (the `clojure.core/format` engine).
- **`StringBuilder`** — `append` `toString` `length` `charAt` `setLength`.
- **`java.util.ArrayList` / `HashMap`** — the mutable `add`/`get`/`put`/`size`/`remove`/`keySet`/… surface.
- **`java.util.regex.Pattern`** — `compile` (with `Pattern/MULTILINE`), `quote`, `.split`, `.pattern`.
- **`java.io.File`** — construction plus `getPath` `getName` `getAbsolutePath` `exists` `isDirectory` `listFiles` …
- **`StringReader` / `StringWriter` / `PushbackReader`** — the reader/`with-out-str` surface.

### Time, net, encoding

- **`java.util.Date`** and **`java.time`** (`Instant`, `LocalDateTime`, `ZoneId`, `DateTimeFormatter`), **`java.text.SimpleDateFormat`** / **`NumberFormat`**.
- **`java.net.URL`** / **`URI`** — construction and component accessors.
- **`java.util.UUID`** — `randomUUID`, `fromString`.
- **`java.util.Base64`**, **`java.nio.charset.Charset`**.
- **Exceptions** — `Throwable` `Exception` `RuntimeException` `IllegalArgumentException` `IllegalStateException` `IOException` `NumberFormatException` `ArithmeticException` `NullPointerException` and friends, each with the `(E.)` / `(E. msg)` / `(E. msg cause)` constructors.

What's deliberately absent: STM, reflection, `gen-class`/`proxy` of Java classes, and `BigDecimal`.

## Adding your own shim from a library

The built-in shims above are baked into the runtime. A library or project can register its **own** host classes at load time — no rebuild, no host edits. Put the registration calls at the top level of a namespace your code requires. Four functions (in `clojure.core`) plus the tagged-table seam (in `jolt.host`) cover it.

`__register-class-ctor!` makes `(Name. …)` work; `__register-class-statics!` makes `Name/field` and `(Name/method …)` work; `__register-class-methods!` attaches instance methods to a tagged value; `__register-instance-check!` teaches `instance?` about your class. **Method and static names are strings** — they match the literal name in the interop form.

A stateful object is a *tagged table*: `jolt.host/tagged-table` creates one, and `ref-put!`/`ref-get` set and read its fields. Read the tag back with `jolt.host/ref-get` (or test it with `jolt.host/table?`); a plain `get` or keyword lookup deliberately can't see a wrapper's own `:jolt/type`.

```clojure
(ns mylib.greeter
  (:require [jolt.host :as host]))

;; (Greeter. name) -> a tagged value carrying its name
(__register-class-ctor! "Greeter"
  (fn [name] (-> (host/tagged-table :greeter)
                 (host/ref-put! :name name))))

;; (.hello g) -> instance method, keyed by the literal method name
(__register-class-methods! :greeter
  {"hello" (fn [self] (str "hi " (host/ref-get self :name)))})

;; Greeter/VERSION (field) and (Greeter/make x) (static method)
(__register-class-statics! "Greeter"
  {"VERSION" "1.0"
   "make"    (fn [name] (Greeter. name))})

;; (instance? Greeter x)
(__register-instance-check!
  (fn [class-name v]
    (when (= class-name "Greeter")
      (and (host/table? v) (= :greeter (host/ref-get v :jolt/type))))))
```

```clojure
(.hello (Greeter. "ada"))            ;=> "hi ada"
Greeter/VERSION                      ;=> "1.0"
(.hello (Greeter/make "bob"))        ;=> "hi bob"
(instance? Greeter (Greeter. "x"))   ;=> true
```

An instance-check predicate returns `true`/`false` to decide, or `nil` to defer to the next registered check and the built-ins — so several libraries can register checks without clobbering each other. This is the mechanism Jolt's HTTP client library uses to emulate `java.net.URL` and `HttpURLConnection` so `clj-http-lite` runs unchanged.
