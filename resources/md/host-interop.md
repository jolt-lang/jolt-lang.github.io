Jolt runs on Scheme, not the JVM, so there are no real Java classes behind interop forms. Instead the runtime ships shims for the slice of the JVM standard library that portable Clojure code reaches for, so libraries written against `clojure.core` and common `java.*` classes run unchanged. The Clojure interop syntax works against these shims:

```clojure
(Math/sqrt 2)                  ; static call
Math/PI                        ; static field
(StringBuilder.)               ; constructor
(.append sb "x")               ; instance method
(instance? String "hi")        ; class token
```

A class token (`String`, `java.util.UUID`, …) evaluates to a `java.lang.Class` object, and `(class x)` returns one for the scalar and collection types Clojure programs compare against. The modeled class hierarchy answers a minimal reflective surface: `.getSuperclass` (nil for `Object` and for interfaces, as on the JVM), `.getInterfaces`, `.isAssignableFrom`, `.isInterface`, `.isInstance`, plus the naming trio `.getName`/`.getSimpleName`/`.getCanonicalName`. `isa?`, `supers`, `ancestors` and `bases` derive from the same graph, and a class a library registers (`jolt.host/register-class-supers!`) reflects like a built-in. There is no method/field enumeration and no `clojure.reflect` — the hierarchy is modeled, the members are not.

## What's shimmed

This is the surface today, not the whole JVM. Methods not listed generally aren't implemented; a few are accepted but no-ops.

### Numbers and language

- **`java.lang.Math`** — `sqrt` `cbrt` `pow` `exp` `log` `log10` `floor` `ceil` `round` `abs` `max` `min` `sin` `cos` `tan` `asin` `acos` `atan` `signum` `random`; `PI`, `E`. (`clojure.math` mirrors these as functions.)
- **`Long` / `Integer`** — `parseLong`/`parseInt`/`valueOf` (optional radix), `MAX_VALUE`, `MIN_VALUE`.
- **`Double` / `Float`** — `parseDouble`, `valueOf`, `toString`, `isNaN`, `isInfinite`, the value/infinity/NaN fields.
- **`Boolean`** — `parseBoolean`, `TRUE`, `FALSE`.
- **`Character`** — `isUpperCase` `isLowerCase` `isDigit` `isWhitespace` (ASCII).
- **Boxed-number methods** — every number answers `.intValue` `.longValue` `.doubleValue` `.byteValue` `.shortValue` `.toString` `.hashCode`.
- **`java.lang.System`** — `currentTimeMillis` `nanoTime` `exit` `getProperty` `setProperty` `getProperties` `getenv`. The built-in property keys: `os.name` (`"Mac OS X"`/`"Linux"`/`"Windows"`), `os.arch` in the JVM's spelling (`aarch64`/`amd64`), `os.version` (macOS product version / kernel release), `user.name`, `user.dir`, `user.home`, `java.io.tmpdir`, `java.class.path`, `line.separator`, `file.separator`, `path.separator`, and `jolt.version`. `java.version` is nil **deliberately** — jolt has no JDK to report, and claiming one would flip JVM-only code paths in libraries that parse it; branch on `jolt.version` (or a `:jolt` reader conditional) instead.
- **`java.lang.Thread`** — `sleep` (real), `yield`/`interrupted` (no-ops), `currentThread`.

### Strings, collections, I/O

- **`String`** statics — `valueOf`, `format` (the `clojure.core/format` engine).
- **`StringBuilder`** — `append` `toString` `length` `charAt` `setLength`.
- **`java.util.ArrayList` / `HashMap`** — the mutable `add`/`get`/`put`/`size`/`remove`/`keySet`/… surface.
- **`java.util.regex.Pattern`** — `compile` (with `Pattern/MULTILINE`), `quote`, `.split`, `.pattern`.
- **`java.io.File`** — construction plus `getPath` `getName` `getAbsolutePath` `exists` `isDirectory` `listFiles` …
- **`StringReader` / `StringWriter` / `PushbackReader`** — the reader/`with-out-str` surface.

### Time, net, encoding

- **`java.util.Date`** — the `#inst` reader literal and `java.util.Date` / `java.sql.Date` / `Calendar`, plus `java.text.SimpleDateFormat` / `NumberFormat`. The base **`java.time`** value types (`Instant`, `LocalDate`/`LocalTime`/`LocalDateTime`, `Duration`, `Period`, `Year`/`YearMonth`, the `Month`/`DayOfWeek`/`Chrono*` enums) are in core too, autoloaded on first use with no dependency (RFC 0008). What formats or names a zone — `DateTimeFormatter`, `ZoneOffset`/`ZoneId`, `ZonedDateTime`/`OffsetDateTime`, localized formatting, `java.util.Locale` — lives in the [time](https://github.com/jolt-lang/time) library.
- **`java.net.URL`** / **`URI`** — construction and component accessors.
- **`java.net.Socket` / `ServerSocket` / `InetSocketAddress` / `InetAddress`** — blocking IPv4 TCP over POSIX sockets, gated behind `(require 'jolt.socket)` (the require registers the classes; nothing else to configure). The usual surface works like Java: streams via `.getInputStream`/`.getOutputStream` (a write to a peer-closed socket throws `IOException`), `ServerSocket` binds the wildcard address (or a `bindAddr`), port 0 gets a kernel-assigned port that `.getLocalPort` reports, accepted sockets know their peer, and `InetAddress/getByName` resolves. Deliberate gaps — `available()` answers 0, a recv error reads as EOF, connect timeouts are ignored — are tracked in `known-divergences.edn`.
- **`java.util.UUID`** — `randomUUID`, `fromString`.
- **`java.util.Base64`**, **`java.nio.charset.Charset`**.
- **Exceptions** — `Throwable` `Exception` `RuntimeException` `IllegalArgumentException` `IllegalStateException` `IOException` `NumberFormatException` `ArithmeticException` `NullPointerException` and friends, each with the `(E.)` / `(E. msg)` / `(E. msg cause)` constructors.

  Every exception class inherits the `java.lang.Throwable` methods, as it does on
  the JVM: `.getMessage` `.getLocalizedMessage` `.getCause` `.toString`
  `.printStackTrace` (both overloads) `.getStackTrace` `.getSuppressed`
  `.fillInStackTrace`. This holds equally for an `ex-info`, a constructed
  `(Exception. "…")`, and an error the host itself raised — the value a `catch`
  binds answers the same set whichever it is. `.printStackTrace` prints a real
  backtrace (see [Stack traces](/docs/tools-deps.html#stack-traces)).
  `.getStackTrace` is empty: Jolt reifies no `StackTraceElement` array, because
  tail-call optimization means there is no faithful per-frame array to hand back.

What's deliberately absent: member-level reflection (method/field enumeration, `clojure.reflect` — the hierarchy half is modeled, see above), `gen-class`/`proxy` of Java classes, and `BigDecimal`. (STM — `ref`/`dosync`/`alter`/`commute`/`ref-set`/`ensure` — is present, with commit-log transactions.)

## Using a JVM library that needs a class Jolt doesn't ship

Most Clojure libraries run on Jolt unchanged. When one reaches for a `java.*`
class outside the built-in set above, you don't have to wait for a Jolt release —
you can **register the shim yourself, at load time**, from ordinary Jolt code. No
rebuild, no host edits. Put the registration calls at the top level of a
namespace your app requires before the library is used (a small `myapp.shims`
namespace is a good home).

The built-in shims are written the same way, just in the runtime instead of your
project — so anything Jolt does for `AtomicReference`, `ByteBuffer`, or `URI`,
you can do for a class it's missing.

### The workflow: let the error tell you what to add

Run the library and read the exception. Each shape of "missing host" error maps
to one registration function:

| The error you hit | What to register |
| --- | --- |
| `Unknown class java.util.StringJoiner` (a static/field ref, or the class is wholly unknown) | `__register-class-statics!` / `__register-class-ctor!` |
| `No constructor for class java.util.StringJoiner` | `__register-class-ctor!` |
| `No method add on host …` (a `(.method obj …)` call) | `__register-class-methods!` |
| `(instance? SomeClass x)` returns `false` when it shouldn't | `__register-instance-check!` |
| `isa?` / `ancestors` / `instance?`-through-a-parent is wrong | `jolt.host/register-class-supers!` |

Add the one it names, re-run, repeat until the library is happy. **Method,
static, and class names are strings that match the literal name in the interop
form** — `"add"` shims `(.add x …)`, `"java.util.StringJoiner"` shims
`(java.util.StringJoiner. …)`.

### A worked example: `java.util.StringJoiner`

Say a library builds strings with `java.util.StringJoiner`, which Jolt doesn't
ship. A stateful object is a **tagged table**: `jolt.host/tagged-table` creates
one carrying a `:jolt/type` tag, and `ref-put!` / `ref-get` set and read its
fields. Instance methods are keyed by that tag.

```clojure
(ns myapp.shims
  (:require [jolt.host :as host]
            [clojure.string :as str]))

(defn- joined [self]
  (str/join (host/ref-get self :delim) (host/ref-get self :parts)))

;; (StringJoiner. delim) -> a tagged value holding the delimiter and the parts
(__register-class-ctor! "java.util.StringJoiner"
  (fn [delim] (-> (host/tagged-table :string-joiner)
                  (host/ref-put! :delim delim)
                  (host/ref-put! :parts []))))

;; instance methods: (.add sj s), (.toString sj), (.length sj)
(__register-class-methods! :string-joiner
  {"add"      (fn [self s] (host/ref-put! self :parts
                             (conj (host/ref-get self :parts) (str s)))
                           self)                       ; .add returns the joiner
   "toString" (fn [self] (joined self))
   "length"   (fn [self] (count (joined self)))})

;; (instance? StringJoiner x)
(__register-instance-check!
  (fn [class-name v]
    (when (= class-name "java.util.StringJoiner")
      (and (host/table? v) (= :string-joiner (host/ref-get v :jolt/type))))))
```

```clojure
(let [sj (-> (java.util.StringJoiner. ", ") (.add "a") (.add "b") (.add "c"))]
  (.toString sj))                                   ;=> "a, b, c"
(.length (-> (java.util.StringJoiner. ", ") (.add "a") (.add "b")))  ;=> 4
(instance? java.util.StringJoiner (java.util.StringJoiner. ","))     ;=> true
```

One subtlety: `(str a-tagged-value)` and `pr-str` show its raw wrapper form, not
your `toString` shim — so compute a length or a display string from the fields
(as `joined` does above), rather than `(count (str self))`.

### Static-only classes

A utility class with no instances (`java.lang.Math`, `java.util.Base64`) needs
only statics — fields and static methods, again keyed by string name:

```clojure
(__register-class-statics! "java.util.Base64"
  {"getEncoder" (fn [] my-encoder)})         ; Base64/getEncoder
;; then Name/FIELD reads a field, (Name/method …) calls a static method
```

### Fitting a class into the hierarchy

`instance?` on an exact class works from the instance-check above. To make
`isa?`, `ancestors`, and `instance?`-through-a-supertype hold, declare the
class's supers (its superclass and interfaces) with the `jolt.host` seam:

```clojure
(host/register-class-supers! "java.util.StringJoiner" ["java.lang.CharSequence"
                                                       "java.lang.Object"])
;; now (isa? java.util.StringJoiner java.lang.CharSequence) => true, and a
;; protocol/multimethod that dispatches on CharSequence sees a StringJoiner.
```

Your own `deftype`/`defrecord` classes join the same graph **automatically** at
definition — you only need `register-class-supers!` for a shim of an outside
class. A record's ancestry carries the record interfaces (`clojure.lang.IRecord`,
`IPersistentMap`, `Associative`, …), a bare deftype carries `clojure.lang.IType`,
and every protocol the type implements inline appears as an implemented
interface — so `(ancestors MyRecord)`, `(isa? MyRecord clojure.lang.IPersistentMap)`,
and hierarchy relationships `derive`d on a class's supers all answer like the JVM.

A deftype *implementing* a `clojure.lang` collection interface drives the core
functions through its methods, like the JVM: `Indexed` → `nth`, `Counted` →
`count`, `ILookup` → `get`/keyword lookup, `Associative` → `assoc`,
`ISeq`/`Seqable` → `seq`/`first`/`rest`, `IPersistentCollection` → `conj`,
`Reversible` → `rseq`, `Sorted` → `subseq`/`rsubseq`, `IDeref` → `deref`/`@`,
`IFn` → the value is callable, `IReduceInit` → `reduce`. Methods can be
arity-overloaded across interfaces (`seq [this]` and `seq [this ascending]`), and
a marker protocol with no methods still answers `satisfies?`/`instance?`.

Extending a *built-in* class instead — adding a method to core's `String` shim,
say — means editing the runtime's `host/chez/*.ss` and rebuilding; that's a
contribution to Jolt itself rather than a project-level shim (see
[Building &amp; Running](/docs/building-and-deps.html)).

### Instance checks compose

An instance-check predicate returns `true`/`false` to decide, or `nil` to **defer**
to the next registered check and the built-ins — so several libraries can
register checks without clobbering each other. This is the mechanism Jolt's HTTP
client library uses to emulate `java.net.URL` and `HttpURLConnection` so
`clj-http-lite` runs unchanged.

### The registration API at a glance

All four `__register-*` functions live in `clojure.core` (no require); the
tagged-table and hierarchy seams live in `jolt.host`:

- `(__register-class-ctor! "pkg.Name" (fn [args…] …))` — `(pkg.Name. args…)`
- `(__register-class-statics! "pkg.Name" {"FIELD" v, "method" (fn […] …)})` — `pkg.Name/FIELD`, `(pkg.Name/method …)`
- `(__register-class-methods! :your-tag {"method" (fn [self args…] …)})` — `(.method obj args…)` on a value tagged `:your-tag`
- `(__register-instance-check! (fn [class-name-str v] true|false|nil))` — `(instance? pkg.Name v)`
- `(jolt.host/register-class-supers! "pkg.Name" ["pkg.Super" "pkg.Iface" …])` — hierarchy for `isa?`/`ancestors`
- `jolt.host/tagged-table`, `jolt.host/ref-put!`, `jolt.host/ref-get`, `jolt.host/table?` — build and read a stateful wrapper

If a shim would be useful to everyone, it's also a great contribution to the
runtime itself — the built-in shims use exactly these registries; see
[Writing Libraries](/docs/writing-libraries.html) and Jolt's `host/chez/java`
sources.

These registries add a **class**. If instead you want to supply **data** to
something Jolt already implements — per-locale currency symbols, month names,
number separators — that is an
[extension point](/docs/extension-points.html): core declares the contract and
answers for one root key, and your library registers the rest.

## Running work on the main thread

Some native calls must run on the process's **main** (primordial) thread. A GUI
toolkit is the usual case: on macOS a Cocoa/GTK-quartz call off the main thread
aborts the process ("setting the main menu on a non-main thread"). Jolt has no
JVM AWT thread, so it exposes a small marshalling API in `jolt.host` that a GUI
library builds on. Most applications never call these directly — you use a UI
library (e.g. [glimmer](https://github.com/jolt-lang/glimmer)) that does — but if
you bind a toolkit yourself, this is the seam.

The model is a **main-thread pump**: one thread becomes a queue-draining event
loop, and other threads hand it thunks to run there.

- `(jolt.host/call-on-main-thread thunk)` — run `thunk` on the pump thread and
  **block** until it finishes, returning its value (or re-raising its error). If
  no pump is active it runs `thunk` inline on the calling thread, and a call
  already on the pump runs inline too (reentrant).
- `(jolt.host/call-on-main-thread-async thunk)` — schedule `thunk` on the pump
  and **return immediately** (`nil`), without waiting. This is what lets a GUI
  library's `run` start the toolkit's event loop — which blocks the pump for the
  app's whole lifetime — while the call that started it returns, so an nREPL
  session stays live for reactive edits. With no pump active it runs inline. *(New
  in 0.4.14.)*
- `(jolt.host/run-main-pump)` — turn the calling thread **into** the pump: drain
  and run queued jobs FIFO, blocking until `stop-main-pump`. Call this on the
  main thread of a program whose main thread should service GUI work.
- `(jolt.host/stop-main-pump)` — tell a running pump to finish draining and
  return.
- `(jolt.host/park-until-interrupt)` — park the calling (main) thread until a
  keyboard interrupt (`^C`), running shutdown hooks and exiting when it arrives,
  **and** own the pump while parked. This is the variant a foreground server uses:
  the nREPL server parks the primordial thread here so a UI event loop evaluated
  from the REPL runs on the real main thread (not off-main, which would abort),
  while `^C` still shuts the server down cleanly. Unlike `run-main-pump`'s bare
  wait, it idles in an interrupt-checked poll so `^C` is delivered.

Because both blocking and async calls fall back to running inline when no pump is
active, code written against this API also works in a plain `-M:run` launch,
where the main thread is already the caller — the library resolves the seam at
call time and needs no separate non-GUI path.

## Calling Scheme directly: `jolt.scheme`

The layers above — the Java-shaped shims, `jolt.ffi` for C, the `jolt.host`
seams — are the portable interop story. `jolt.scheme` is the escape hatch
under them: call a host Scheme procedure by name, or evaluate Scheme text,
from any Jolt program.

```clojure
(require '[jolt.scheme :as scheme])

(scheme/call "expt" 2 10)                        ;=> 1024
(scheme/eval-string "(let ((x 3)) (* x 14))")    ;=> 42

(scheme/defsfn fx+ "fx+")                        ; def a named binding
(fx+ 1 2)                                        ;=> 3

(let [v (scheme/call "vector" 1 2 3)]            ; a host value, opaque to jolt
  (scheme/call "vector-ref" v 0))                ;=> 1
```

`call` resolves the top-level procedure at run time and applies it; `proc`
returns it as a value (usable with `map`, stored in a var by `defsfn`);
`eval-string` evaluates Scheme text and returns the last form's value, with
definitions persisting. An unbound name or a Scheme error surfaces as a
catchable exception.

The contract is **raw**: numbers, strings, booleans and characters are the
same representations on both sides and cross untouched; everything else
crosses as whatever it is on the other side. A Scheme vector arriving in Jolt
is an opaque host value — hand it back to Scheme to use it; a Jolt collection
handed to Scheme is Jolt's representation, not a Scheme list. `nil` is Jolt's
nil, not `'()` and not `#f`.

Two caveats worth their weight. This is **host-specific by design**: code
using `jolt.scheme` is tied to the Chez runtime and its primitives, unlike
everything else on this page. And names resolve at **run time**, so a
tree-shaken binary (`--tree-shake`) only finds what the kept runtime still
carries — an exotic primitive a build shook out is an unbound-name error, not
a silent nil.
