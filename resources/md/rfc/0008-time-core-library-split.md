# RFC 0008 — Splitting time between core and the library

- **Status**: Implemented (supersedes the "remove all `java.time` from core"
  approach that jolt-lang/jolt#431 first took)
- **Champions**: jolt maintainers

> **Implementation note.** As shipped, the core base is narrower than the boundary
> sketched below: it is the java.time **value types only** — `Instant`,
> `LocalDate`/`LocalTime`/`LocalDateTime`, `Duration`, `Period`,
> `Year`/`YearMonth`/`MonthDay`, and the `Month`/`DayOfWeek`/`Chrono*` enums.
> Everything that formats or names a zone — `DateTimeFormatter`, `FormatStyle`,
> `ZoneOffset`, `ZoneId`, `ZonedDateTime`/`OffsetDateTime`, localized formatting,
> and `java.util.Locale` — is the jolt-lang/time library, as the single
> implementation. Putting `DateTimeFormatter` and `ZoneOffset` in core (as the
> boundary below proposed) meant core carried a reduced pattern engine and fixed
> zones that the library then had to override with fuller versions under the same
> namespace names: one implementation split into two source files with subtly
> different behavior depending on what was loaded. Narrowing the base to value
> types keeps the "one implementation" rule the RFC is built on. The trade-off is
> that formatting a date or using `ZoneOffset/UTC` now needs the dependency; the
> interop error names it, so the failure carries its own fix. The rest of the RFC
> is the reasoning that led here and is kept as written.

## Summary

Date and time on jolt is split across `host/chez/java/inst-time.ss` in the core
runtime and the [jolt-lang/time](https://github.com/jolt-lang/time) library. The
split was ragged: core carried a partial, UTC-only `java.time.*` shim in Scheme,
so some calls worked with no dependency and some didn't (`Instant/now` yes,
`LocalDate/now` no), and that partial shim *duplicated* logic the library already
implements in Clojure.

The boundary this RFC settles on:

> Core owns the `#inst` literal, the `java.util` / `java.text` date layer, and a
> **base `java.time` API** most JVM libraries expect — the portable, no-system-
> dependency part: `Instant`, `LocalDate` / `LocalTime` / `LocalDateTime`,
> `Duration`, `Period`, `Year` / `YearMonth`, `ZoneOffset` (fixed offsets),
> `ZoneId` construction, and `DateTimeFormatter` pattern/ISO formatting. The
> jolt-lang/time library adds what depends on the OS timezone database and
> locale data: named-zone offset resolution and DST (`ZoneId` rules),
> `ZonedDateTime` / `OffsetDateTime`, localized formatting, and the tick API.

The line was chosen empirically by surveying `conformance-libraries/` (see
below): the base is what real libraries reach for incidentally, and it computes
from epoch arithmetic alone, so it should never require an extra dependency. Only
named-zone/DST and localized names genuinely need system libraries, and those are
the library's reason to exist.

There must be **one** implementation, not two. The base is implemented once — as
the library's existing Clojure namespaces — and *moves into core* so it loads
without a dependency; the library keeps only the zone/locale layer on top. The
earlier idea of a Scheme base in core is dropped precisely because it duplicated
the Clojure base.

## The current state

Two files in the core image carry time.

`host/chez/java/inst-time.ss` (loaded by the core runtime at boot, not baked into
the seed) registers, through the host value-model seams (`register-class-statics!`, `register-host-methods!`,
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

## The base API, from the conformance survey

The boundary is set by what real libraries use. A sweep of `conformance-libraries/`
(58 libraries) for date/time references, excluding `tick` itself, shows the
common surface is a portable base plus a thin system-dependent tail:

- Heavily used and portable (pure epoch arithmetic): `Instant` (`now`, `parse`,
  `ofEpochMilli`, `ofEpochSecond`, `from`, `toEpochMilli`), `LocalDate` /
  `LocalDateTime` / `LocalTime` (`now`, `of`, `parse`), `Duration` (`ofMillis`,
  `between`, `ofSeconds`), `ZoneOffset/UTC`, and `DateTimeFormatter` patterns +
  the `ISO_*` constants. Also `java.util.Date` (`from`, `valueOf`) and `Calendar`.
- The narrow system-dependent tail: `ZoneId/systemDefault` and named-zone offset
  lookups (need the OS tz database), and `DateTimeFormatter/ofLocalized*` (need
  locale month/day names via `strftime`).

Mapped onto the library's namespaces, only `zones.clj` (tz rules) and the
localized branch of `fmt.clj` touch the libc seams; `util`, `impl`, `enums`,
`local`, `amount`, `temporal`, `year`, `instant`, and the pattern engine are all
system-free.

## The boundary

Core carries the base API so basic `java.time` works with no dependency; the
library adds what genuinely needs the OS. For a user:

- **In core, no dependency:** `#inst` and the `java.util` / `java.text` layer
  (`Date`, `sql.Date`/`Timestamp`, `Calendar`, `TimeZone`, `SimpleDateFormat`),
  plus the base `java.time`: `Instant`, `LocalDate` / `LocalTime` /
  `LocalDateTime`, `Duration`, `Period`, `Year` / `YearMonth`, `ZoneOffset`,
  `ZoneId` construction, and `DateTimeFormatter` pattern/ISO formatting.
- **In jolt-lang/time:** named-zone offset resolution and DST (`ZoneId` rules),
  `ZonedDateTime` / `OffsetDateTime`, localized formatting, and the tick API.

The line is "does it need the OS timezone database or locale data?" — which is
the reason the library exists. A per-class rule keeps it predictable: the base
classes always resolve; a `ZonedDateTime` or a named-zone lookup is what asks for
the dependency.

## What moves and how (dedupe)

The base must not be implemented twice. It exists once — as the library's
Clojure namespaces — so the base **moves into core** and the library keeps only
the zone/locale layer on top. (This supersedes jolt#431, which deleted core's
`java.time` outright; and it drops the idea of a parallel Scheme base in core,
whose whole problem was that it duplicated the Clojure one.)

1. **Base namespaces move library → core**, becoming part of the runtime:
   `jolt.time.{util,impl,enums,local,amount,temporal,year,instant}`. These have
   no zone/locale dependency. `DateTimeFormatter` needs `fmt.clj` split into a
   pattern/ISO engine (core) and the localized-name branch (library, which reads
   `jolt.host/locale-name`). `ZoneOffset` (fixed) and `ZoneId` construction come
   over too; `ZoneId`'s rule lookups stay behind in `zones.clj`.
2. **The library keeps** `zones.clj` (named-zone rules, DST, the IANA fallback
   tables), `zoned.clj` (`ZonedDateTime`/`OffsetDateTime`), the localized
   formatting branch, and tick. Its remaining namespaces `require` the base,
   which now resolves from core.
3. **Boot availability.** `(java.time.Instant/now)` must work with no `require`,
   but stdlib Clojure loads lazily and only the Scheme runtime runs at boot. The
   base is exposed by *autoloading on first use*: when interop resolves an
   unregistered `java.time.*` class, the runtime loads the core base namespace
   that provides it, then retries — so date-free programs pay nothing and a
   library that touches `java.time` gets it transparently. (A boot-eager
   `require` of the base is the simpler alternative but makes every run, even
   `joltc -e '(+ 1 2)'`, carry the base in the live heap; autoload keeps the
   footprint at zero until first use.)

The `Date`/`FileTime` `.toInstant` bridge keeps routing through `mk-instant` and
the `set-instant-ctor!` hook, but now the hook is always satisfiable because the
core base provides `Instant` — `.toInstant` no longer needs the library. The
`#inst` / `java.util` / `java.text` layer and the `tz-primitives.ss` libc seams
stay in core as they are.

**Tests.** The base `java.time` conformance moves into core's suite alongside
`#inst`; the library's suite keeps the zone/DST/localized/tick coverage.

### Compatibility

The base is a strict addition to core, so the common surface only gains coverage:
`Instant`, `LocalDate` / `LocalDateTime`, `Duration`, `DateTimeFormatter`
patterns, and the rest of the base resolve with no dependency. What still needs
jolt-lang/time is the zone layer — a `ZonedDateTime`, a named-zone offset
(`ZoneId/systemDefault` and rule lookups), or a localized formatter. A program
touching those without the dependency gets an error naming it, rather than a
silent wrong answer.

## Making the boundary teach itself

Beyond docs, the interop layer turns the boundary into a pointer at the moment it
bites. When a zone-layer `java.time` class is referenced and the library is not
loaded, the "unresolved class" path names the dependency instead of a bare
"Unknown class":

```
java.time.ZonedDateTime is provided by the jolt-lang/time library, not core
(RFC 0008). Add io.github.jolt-lang/time to your deps.edn.
```

This is the same philosophy as the "did you mean?" symbol diagnostics: the error
carries the fix. `host-static.ss` already recognizes an unresolved `java.time.*`
class and points at jolt-lang/time; once the base is in core the message narrows
to the classes that genuinely live in the library (`ZonedDateTime`,
`OffsetDateTime`, named-zone `ZoneId` operations), since the base now resolves.

## Guidance for library authors

The base `java.time` a library reaches for incidentally — `Instant`, `LocalDate`,
`LocalDateTime`, `Duration`, `DateTimeFormatter` patterns — works on jolt with no
dependency, so most libraries need no special handling. Only the zone layer asks
for jolt-lang/time.

If a library uses a `ZonedDateTime`, a named zone, or localized formatting and
wants to stay dependency-free on jolt, jolt's reader-conditional feature set is
`{:jolt :clj :default}` (see `host/chez/reader.ss`) and the first matching clause
wins, so a `:jolt` branch before `:clj` can substitute the base:

```clojure
(defn stamp []
  #?(:jolt (java.time.Instant/now)      ; base, in core, no dependency
     :clj  (java.time.ZonedDateTime/now)))
```

For a library like Lasertag whose java.time use is a couple of constructors, the
base almost certainly covers it now with no change. Reach for a `:jolt` branch
only for the zone layer, or depend on jolt-lang/time if the library leans on
zoned/localized time broadly (a single git coordinate).

## Open questions

- **`LocalDate/now` and the "current" zone.** `now()` for the local types uses
  the system default zone to decide the wall-clock date. Core has the
  `jolt.host/tz-offset-seconds` libc seam, so the base can honor the real zone
  where available and fall back to UTC otherwise. Whether the base's `now()`
  should consult the seam or fix on UTC (leaving zone-accurate `now` to the
  library) is worth pinning down; UTC is the safe default and the library can
  refine it.
- **The autoload trigger.** Autoloading the core base on first `java.time.*`
  reference is a new interop capability. It has to fire in both the compile path
  (a static call site) and any runtime `resolve` of a `java.time` class, and be a
  no-op once loaded. Scope it to the `java.time.` package so it never masks a
  genuine unknown class.
- **The library's parallel `java.util` types.** The library registers its own
  `java.util.Locale` and `java.util.Date/from`, which core also owns. Once the
  base is shared these should collapse to core's, removing the last duplicate
  registration.
