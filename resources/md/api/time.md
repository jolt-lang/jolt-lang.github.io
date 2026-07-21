Date and time on Jolt is split between the core runtime and the
[jolt-lang/time](https://github.com/jolt-lang/time) library. Core owns the
java.time value types; formatting and zones are the library (see RFC 0008).

Core carries the `#inst` reader literal, the `java.util` / `java.text` date layer
(`Date`, `sql.Date`/`Timestamp`, `Calendar`, `TimeZone`, `SimpleDateFormat`), and
the **base `java.time` value types** that compute from epoch arithmetic alone:
`Instant`, `LocalDate`/`LocalTime`/`LocalDateTime`, `Duration`, `Period`,
`Year`/`YearMonth`/`MonthDay`, and the `Month`/`DayOfWeek`/`ChronoUnit`/
`ChronoField` enums. These work with **no dependency** — they autoload the first
time a program touches one, so date-free programs pay nothing.

The jolt-lang/time library owns the single implementation of everything that
formats or names a zone: `DateTimeFormatter` and `FormatStyle`, `ZoneOffset` and
`ZoneId`, `ZonedDateTime`/`OffsetDateTime`, `java.util.Locale`, named-zone offset
resolution and DST, localized formatting, and it pulls
[juxt/tick](https://github.com/juxt/tick) for the idiomatic API on top. There is
no second copy of any of these in core.

## The base value types, no dependency

```clojure
(java.time.Instant/now)                                   ; base, in core
(java.time.LocalDate/of 2020 3 5)
(java.time.LocalDateTime/parse "2020-03-05T13:45:30")
(java.time.Duration/between (java.time.Instant/ofEpochMilli 0)
                            (java.time.Instant/ofEpochMilli 5000))
(java.time.Period/of 1 2 3)
(java.time.Year/of 2024)
```

A `#inst` / `java.util.Date` converts into the base with `.toInstant`, one value:

```clojure
(.toInstant #inst "2020-01-01T00:00:00Z")                 ; => a java.time.Instant
```

Reach for a formatting or zone class without the library and the error names the
fix rather than leaving a bare "Unknown class":

```
java.time.format.DateTimeFormatter is provided by the jolt-lang/time library, not
core (RFC 0008). Add io.github.jolt-lang/time to your deps.edn.
```

## Formatting, zones, and tick

```clojure
;; deps.edn
{:deps {io.github.jolt-lang/time {:git/url "https://github.com/jolt-lang/time.git"
                                  :git/sha "99414a5..."}}}
```

Requiring `jolt.time` adds `DateTimeFormatter`, `ZoneOffset`/`ZoneId`,
`ZonedDateTime`/`OffsetDateTime`, `Locale`, and the zone/localized layer over the
base; require `tick.core` for the tick functions.

```clojure
(require '[jolt.time]                  ; adds formatting, zones, ZonedDateTime, Locale
         '[tick.core :as t])

(t/now)                               ; => an Instant
(t/date "2020-01-01")                 ; => a LocalDate
(t/>> (t/date "2020-01-01") (t/new-period 3 :months))   ; => 2020-04-01
(t/zone "Europe/Berlin")

(.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd")
         (java.time.LocalDate/now))
(java.time.ZonedDateTime/parse "2020-07-06T10:59:13.417Z")
```

## How it works

java.time values are opaque host objects registered through Jolt's
`__register-class-*` seams, so they construct, compare, hash, print, sort, and
answer `instance?` exactly like the real classes — and protocols extended to a
`java.time` class (as tick does) dispatch on them. There is one implementation of
each class. The base value types are portable Clojure under `stdlib/jolt/time/` in
core, autoloaded on first use; the library's `fmt`/`zones`/`zoned` namespaces
require that base and add formatting and the zone layer. Named-zone offsets and
DST come from the OS through a small libc primitive in core (`localtime`/`tzset`
reading `/usr/share/zoneinfo`), with a built-in US/EU/AU/NZ rule table as the
fallback; localized month and day names come from `strftime`.

## Coverage

Base value-type conformance is covered by core's suite; tick's full test suite and
the formatting/zone/DST/localized coverage run in the library's suite.
`java.util.zip`-based helpers are the only gap — there is no `java.util.zip` shim
yet.
