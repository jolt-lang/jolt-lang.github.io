Date and time on Jolt is split between the core runtime and the
[jolt-lang/time](https://github.com/jolt-lang/time) library, along the line of
"does it need the OS timezone database or locale data?" (see RFC 0008).

Core carries the `#inst` reader literal, the `java.util` / `java.text` date layer
(`Date`, `sql.Date`/`Timestamp`, `Calendar`, `TimeZone`, `Locale`,
`SimpleDateFormat`), and a **base `java.time` API** that computes from epoch
arithmetic alone: `Instant`, `LocalDate`/`LocalTime`/`LocalDateTime`, `Duration`,
`Period`, `Year`/`YearMonth`, `ZoneOffset`, `ZoneId` construction, and
`DateTimeFormatter` pattern/ISO formatting. The base works with **no dependency**
— it autoloads the first time a program touches a `java.time.*` class, so
date-free programs pay nothing.

The jolt-lang/time library adds what genuinely needs the OS: named-zone offset
resolution and DST (`ZoneId` rules), `ZonedDateTime`/`OffsetDateTime`, localized
formatting (`ofLocalized*` and locale month/day names), and it pulls
[juxt/tick](https://github.com/juxt/tick) to expose tick's idiomatic API on top.

## The base, no dependency

```clojure
(java.time.Instant/now)                                   ; base, in core
(java.time.LocalDate/of 2020 3 5)
(java.time.Duration/between (java.time.Instant/ofEpochMilli 0)
                            (java.time.Instant/ofEpochMilli 5000))
(.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd")
         (java.time.LocalDate/now))
java.time.ZoneOffset/UTC
(java.time.ZoneId/of "UTC")
```

A `#inst` / `java.util.Date` converts into the base with `.toInstant`, one value:

```clojure
(.toInstant #inst "2020-01-01T00:00:00Z")                 ; => a java.time.Instant
```

Reach for a zone-layer class without the library and the error names the fix
rather than leaving a bare "Unknown class":

```
java.time.ZonedDateTime is provided by the jolt-lang/time library, not core
(RFC 0008). Add io.github.jolt-lang/time to your deps.edn.
```

## The zone/locale layer and tick

```clojure
;; deps.edn
{:deps {io.github.jolt-lang/time {:git/url "https://github.com/jolt-lang/time.git"
                                  :git/sha "7b6389c..."}}}
```

Requiring `jolt.time` adds the zone layer over the base; require `tick.core` for
the tick functions.

```clojure
(require '[jolt.time]                  ; adds ZonedDateTime, named zones, localized fmt
         '[tick.core :as t])

(t/now)                               ; => an Instant
(t/date "2020-01-01")                 ; => a LocalDate
(t/>> (t/date "2020-01-01") (t/new-period 3 :months))   ; => 2020-04-01
(t/zone "Europe/Berlin")

(java.time.ZonedDateTime/parse "2020-07-06T10:59:13.417Z")
```

## How it works

java.time values are opaque host objects registered through Jolt's
`__register-class-*` seams, so they construct, compare, hash, print, sort, and
answer `instance?` exactly like the real classes — and protocols extended to a
`java.time` class (as tick does) dispatch on them. There is one implementation:
the base is portable Clojure under `stdlib/jolt/time/` in core, autoloaded on
first use; the library's `zones`/`zoned`/`fmt` namespaces require that base and
add only the zone/locale layer on top of it. Named-zone offsets and DST come from
the OS through a small libc primitive in core (`localtime`/`tzset` reading
`/usr/share/zoneinfo`), with a built-in US/EU/AU/NZ rule table as the fallback;
localized month and day names come from `strftime`.

## Coverage

Base `java.time` conformance is covered by core's suite; tick's full test suite
and the zone/DST/localized coverage run in the library's suite.
`java.util.zip`-based helpers are the only gap — there is no `java.util.zip` shim
yet.
