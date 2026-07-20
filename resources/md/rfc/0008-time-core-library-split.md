# RFC 0008 — Splitting time between core and the library

- **Status**: Implemented (jolt-lang/jolt#431)
- **Champions**: jolt maintainers

## Summary

Date and time on jolt is split across two places: a shim baked into the core
image (`host/chez/java/inst-time.ss`) and the [jolt-lang/time](https://github.com/jolt-lang/time)
library. Today that split is ragged. Core carries a partial `java.time.*`
surface, so some `java.time` calls work with no dependency and some do not, with
no rule a user can predict. `(java.time.Instant/now)` works out of the box but
`(java.time.LocalDate/now)` and `(java.time.ZonedDateTime/now)` need the library,
and nothing tells you why.

This RFC proposes one rule for the boundary and the migration to make the code
match it:

> Core owns the `#inst` reader literal and the legacy `java.util` / `java.text`
> date layer it round-trips and formats (`java.util.Date`, `java.sql.Date`/
> `Timestamp`, `java.util.Calendar`, `java.util.TimeZone`, `java.text.SimpleDateFormat`),
> plus the irreducible libc FFI seams. The entire `java.time.*` namespace is the
> jolt-lang/time library, loaded on demand.

The line is drawn at a namespace boundary, not at "portable versus FFI-backed,"
because a per-namespace rule is one a user can state in a sentence and never be
surprised by, while a per-capability rule splits single classes across two homes
and reintroduces exactly the confusion this RFC exists to remove.

## The current state

Two files in the core image carry time.

`host/chez/java/inst-time.ss` (baked into the seed) registers, through the
host value-model seams (`register-class-statics!`, `register-host-methods!`,
`register-class-ctor!`, and the `register-{eq,hash,compare,class,pr,instance-check}-arm!`
family):

- The `#inst` literal — parse (`jolt-inst-from-string`), the `jinst` value type,
  and its equality / hashing / comparison / printing / `instance?` integration.
  This is intrinsic: the reader and printer must round-trip `#inst` with no
  library present, so this can only live in core.
- The legacy `java.util` / `java.text` date layer: `java.util.Date`,
  `java.sql.Date` / `Timestamp`, `java.util.Calendar`, `java.util.TimeZone`,
  `java.text.SimpleDateFormat`, `java.util.Locale`, and the UTC/GMT `format-ms` /
  `parse-ms` pattern engine that backs them (and HTTP date headers).
- A partial `java.time.*` surface: `Instant` (`ofEpochMilli`, `now`, `parse`,
  `from`), `ZoneId` (`systemDefault`, `of`), `LocalDateTime` (`ofInstant`,
  `now`, `parse`), `DateTimeFormatter` (`ofPattern`, the `ISO_*` constants,
  `ofLocalized*`), and `FormatStyle`. `LocalDate` and `ZonedDateTime` values
  exist only as conversion *targets* (`.toLocalDate`, `.atZone`) — there is no
  `LocalDate/now` and no `ZonedDateTime/now`.

`host/chez/java/tz-primitives.ss` (also baked in) exposes three host seams:
`jolt.host/tz-offset-seconds` (a named IANA zone's offset at an instant, via
libc `localtime`/`tzset`), `jolt.host/locale-name` (localized month/day names via
`strftime`), and `jolt.host/tz-backend` (`:libc` or `:fallback`). These are thin
FFI wrappers with graceful fallback, not time logic.

The [jolt-lang/time](https://github.com/jolt-lang/time) library provides the full
`java.time.*` surface as portable Clojure — `Instant`, `LocalDate`/`LocalTime`/
`LocalDateTime`, `ZonedDateTime`/`OffsetDateTime`, `Duration`/`Period`,
`Year`/`YearMonth`, `ZoneId`/`ZoneOffset`, `DateTimeFormatter`, and the temporal
machinery — then layers tick on top. It registers through the *same* host seams,
which are last-write-wins, so when the library loads it **overrides** core's
partial surface and becomes authoritative. It reads zones and locale names from
the core FFI seams, and it installs `jolt.host/set-instant-ctor!` so a `Date` or
`#inst` converted with `.toInstant` yields the library's `Instant`, not a second
representation.

So the java.time pieces in core are, in practice, a fallback that only matters
when the library is absent. When the library is present they are dead.

## The problem

Because core registers *some* of `java.time` and not the rest, the surface a
program sees with no dependency is arbitrary:

```clojure
(java.time.Instant/now)        ; works — core registers Instant/now
(java.time.LocalDateTime/now)  ; works — core registers LocalDateTime/now
(java.time.LocalDate/now)      ; fails — core has no LocalDate/now
(java.time.ZonedDateTime/now)  ; fails — needs zone-aware construction
```

The two that work are the anomaly. They are `now` constructors that crept into
core next to the `#inst` formatting-and-conversion helpers, even though core does
not need them to read, print, or format a `#inst`. Their siblings never got the
same treatment, and there is no signpost. A user reasonably concludes `java.time`
is supported, hits the first missing piece, and cannot tell whether it is a bug,
a gap, or a missing dependency.

This is the confusion reported in the field: `Instant/now` worked, `LocalDate/now`
and `ZonedDateTime/now` did not, and the docs had to be dug through to learn that
`java.time` is really the jolt-lang/time library.

## The rule

`java.time.*` belongs to the library. Core owns `#inst` and the `java.util` /
`java.text` layer, and the FFI seams. Stated for a user:

- To read, print, compare, or format a `#inst` / `java.util.Date` — including
  HTTP-style dates with `java.text.SimpleDateFormat` — you need nothing. It is in
  core.
- To touch anything under `java.time.*` — `Instant`, `LocalDate`,
  `ZonedDateTime`, `Duration`, `DateTimeFormatter`, all of it, uniformly — add
  jolt-lang/time. It is one dependency, and it is all-or-nothing.

There is no third category and no "works sometimes." That predictability is the
whole point.

### Why the namespace, not FFI-dependence

The tempting alternative is to split by capability: keep the portable parts of
`java.time` in core (`Instant`, `LocalDate`, `LocalDateTime`, `Duration`,
`Period` — all computable from epoch arithmetic with no system library) and put
only the FFI-backed parts in the library (named-zone/DST offsets, localized
names, so `ZonedDateTime` with a real zone). That matches an intuition that "core
is the portable shims, the library is the stuff that needs libc."

It is rejected, for three reasons.

1. **It splits single classes.** `ZonedDateTime` at a fixed offset is pure
   arithmetic; `ZonedDateTime` in `Europe/Berlin` needs libc. Under a
   capability split they live in different repos, and a user cannot know which
   `ZonedDateTime` they have. `DateTimeFormatter` with a pattern is portable;
   with a locale it needs `strftime`. Splitting a class across the boundary is
   the confusion, not the cure.
2. **Footprint.** A complete portable `java.time` — the Temporal machinery,
   `LocalDate`/`ZonedDateTime`/`Period`/`Duration` arithmetic, formatter parsing
   — is large (the library is a dozen namespaces). Baking it into the always-loaded
   seed grows the live heap that every major GC scans, for a feature most
   programs never touch. Keeping `java.time` lazy is the right footprint call,
   and lazy means library.
3. **The FFI dependence already lives on the right side of the line.** The libc
   calls are a *host capability* (`jolt.host/tz-offset-seconds` and friends),
   sitting beside `jolt.host/sh` and `getenv`. The time *functionality* built on
   them — zone rules, DST, localized formatting — is already in the library. So
   the "FFI-backed functionality is the library" intuition is honored by the
   proposed boundary; only the raw seam stays in core, which is where a host
   capability belongs.

## What moves

The change is almost entirely a deletion from core; the library already provides
the superset and overrides core when loaded, so nothing new has to be written on
the library side.

**Remove from `host/chez/java/inst-time.ss`** the `java.time.*` registrations:
`Instant`, `ZoneId`, `LocalDateTime`, `DateTimeFormatter`, `FormatStyle`, and the
`mk-local` / `mk-zoned` / `mk-local-date` / `mk-formatter` constructors and their
methods. Core defines no `java.time` value of its own after this.

The one `java.time` touchpoint that stays is the `Date`/`FileTime` `.toInstant`
bridge (`java.util.Date.toInstant`, `java.sql.Date/Timestamp.toInstant`, and
`java.nio.file.attribute.FileTime.toInstant` in `nio-file.ss`, all real Java
methods). Rather than removing those and having the library re-register them on
core's values, they route through `mk-instant`, which delegates to the library's
`Instant` constructor via the existing `set-instant-ctor!` hook. With no library
loaded there is no `Instant` to build, so `mk-instant` throws a message naming
the dependency — the bridge requires the library, and core still produces no
`java.time` value on its own. So `set-instant-ctor!` is kept, not retired: it is
the seam through which the library owns `Instant` construction.

The `Date.toLocalDate` / `toLocalDateTime` methods core had are dropped outright.
A `java.util.Date` has no such methods on the JVM (they were a jolt invention),
so there is nothing to bridge.

**Keep in core**: the `#inst` value model and reader, `java.util.Date` /
`java.sql.Date` / `Timestamp` with their `java.util` methods (`getTime`,
`before`/`after`, the deprecated field accessors), `java.util.Calendar`,
`java.util.TimeZone`, `java.text.SimpleDateFormat`, `java.util.Locale`, and the
`format-ms` / `parse-ms` engine. These are the legacy `java.util` / `java.text`
layer and the HTTP date path; none of them is `java.time`.

**Keep in core**: `tz-primitives.ss`. The libc seams stay a host capability. The
library remains pure Clojure and reaches them through `jolt.host/*`, the way it
does now.

**Tests move with the code.** The `insttime` rows in `test/chez/unit.edn` that go
through `java.time` (`Instant/ofEpochMilli`, `Instant/now`, `DateTimeFormatter/ofPattern`
on a `#inst`, `FormatStyle`, `ZoneId/systemDefault`) move to the jolt-lang/time
test suite, which is where that behavior will live. The rows that exercise only
`#inst` / `java.util.Date` stay in core.

### Compatibility

This is a behavior change for one case: a program that today calls
`java.time.Instant/now` (or `LocalDateTime/now`, or formats a `#inst` with
`DateTimeFormatter`) with no dependency will, after the change, need
jolt-lang/time. That is the cost of the boundary being consistent. It is
mitigated three ways: the library provides a strict superset, so adding it never
removes capability; a program that formats a `Date` without `java.time` can use
`java.text.SimpleDateFormat`, which stays in core; and the compile error for an
unregistered `java.time` class can name the dependency to add (see below).

## Making the boundary teach itself

The docs should state the one rule wherever `deps.edn` or host interop is
introduced: `#inst` and `java.util.Date` are core, all of `java.time` is
jolt-lang/time. Beyond docs, the interop layer can turn the boundary into a
pointer at the moment it bites. When a `java.time.*` class is referenced and no
registration is installed (the library is not loaded), the "unresolved class"
path can special-case the `java.time` package and say so:

```
java.time.LocalDate is provided by the jolt-lang/time library, not core.
Add it to deps.edn:
  io.github.jolt-lang/time {:git/sha "26ae332cbe4b6515ae2386c50ed0ae34cafa483a"}
```

This is the same philosophy as the "did you mean?" symbol diagnostics: the error
carries the fix, and it removes the digging that surfaced the problem in the
first place. It is implemented: `host-static.ss` recognizes a `java.time.*` class
(fully qualified, or a distinctive short name) that no registration resolves and
reports that it comes from jolt-lang/time instead of a bare "Unknown class". The
`.toInstant` bridge throws the same pointer when the library is absent.

## Guidance for library authors

A portable Clojure library that wants to run on jolt without forcing the time
dependency on its users should treat `java.time.*` as a jolt add-on, the same way
it already treats platform specifics for Babashka. jolt's reader-conditional
feature set is `{:jolt :clj :default}` (see `host/chez/reader.ss`), and the first
matching clause wins, so a `:jolt` branch placed before `:clj` lets a library
special-case jolt precisely:

```clojure
(defn today []
  #?(:jolt (java.util.Date.)            ; core, no dependency
     :clj  (java.time.LocalDate/now)))
```

For a library like Lasertag whose only jolt gap is a couple of `java.time`
constructors, the lightest-touch options, in order of preference:

1. **Prefer the core layer on the reachable path.** If the value is only used as
   an instant or for formatting, `java.util.Date` in core covers it with no
   dependency and no reader conditional.
2. **Guard the `java.time` call with a `:jolt` reader branch**, as above, when a
   `java.time` type is genuinely wanted. This keeps the library dependency-free
   on jolt while unchanged on the JVM and Babashka.
3. **Depend on jolt-lang/time** if the library leans on `java.time` broadly. That
   is the supported way to get the full surface, and it is a single git
   coordinate.

The direction of travel answers the question directly: `LocalDate/now` and
`ZonedDateTime/now` will *not* be added to core. The `Instant/now` and
`LocalDateTime/now` that work today are being removed from core, not kept, so
that `java.time` is uniformly the library. A library author can rely on that
rule: on jolt, `#inst` and `java.util.Date` are always present, and `java.time`
is present exactly when jolt-lang/time is on the classpath.

## Open questions

- **`java.util.Locale`.** It is `java.util`, so it stays in core by the rule, but
  its only core consumer is `SimpleDateFormat`, whose `format-ms` engine is
  English-only. The large `Locale` constant table (lines 481–509 of
  `inst-time.ss`) may be more than core needs; it could shrink to the handful the
  legacy layer actually reads, with the rest living in the library alongside the
  localized formatter that uses them.
- **Deprecating the fallback rule tables.** With `java.time` fully in the
  library, the US/EU/AU/NZ zone-rule fallback (used when libc is unusable, e.g.
  Windows) is entirely a library concern. Nothing in core references it, which is
  already the case, but it is worth stating that core's `tz-backend` seam only
  reports capability and the library owns the fallback policy.
- **The library's parallel `java.util` types.** The library registers its own
  `java.util.Locale` and `java.util.Date/from`, which core also owns, so those
  two still register twice (silently, behind `JOLT_DEBUG`). It is a minor
  follow-up for the library to consume core's `Locale`/`Date` rather than shadow
  them; core keeping the `java.util` layer is correct by the rule.
