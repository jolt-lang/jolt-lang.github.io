Date and time on Jolt is the [jolt-lang/time](https://github.com/jolt-lang/time)
library. It provides the `java.time.*` surface as portable Clojure — `Instant`,
`LocalDate`/`LocalTime`/`LocalDateTime`, `ZonedDateTime`, `OffsetDateTime`,
`Duration`, `Period`, `Year`/`YearMonth`, `ZoneId`/`ZoneOffset`,
`DateTimeFormatter`, and the temporal machinery — and pulls
[juxt/tick](https://github.com/juxt/tick) through `deps.edn` to expose tick's
idiomatic API on top.

Core keeps only the `#inst` reader literal and the `java.util.Date` layer; the
full `java.time` surface is this library, so it isn't loaded until you ask for it.

## Use

```clojure
;; deps.edn
{:deps {io.github.jolt-lang/time {:git/url "https://github.com/jolt-lang/time.git"
                                  :git/sha "26ae332cbe4b6515ae2386c50ed0ae34cafa483a"}}}
```

Requiring `jolt.time` installs the `java.time` shim; require `tick.core` for the
tick functions.

```clojure
(require '[jolt.time]                 ; installs the java.time.* host shim
         '[tick.core :as t])

(t/now)                               ; => an Instant
(t/date "2020-01-01")                 ; => a LocalDate
(t/>> (t/date "2020-01-01") (t/new-period 3 :months))   ; => 2020-04-01
(t/zone "Europe/Berlin")

;; the underlying java.time is there too
(java.time.ZonedDateTime/parse "2020-07-06T10:59:13.417Z")
```

## How it works

java.time values are opaque host objects registered through Jolt's
`__register-class-*` seams, so they construct, compare, hash, print, sort, and
answer `instance?` exactly like the real classes — and protocols extended to a
`java.time` class (as tick does) dispatch on them. Named-zone offsets and DST
come from the OS through a small libc primitive in core (`localtime`/`tzset`
reading `/usr/share/zoneinfo`), with a built-in US/EU/AU/NZ rule table as the
fallback; localized month and day names come from `strftime`.

## Coverage

tick's full test suite passes, along with the `java.time` conformance rows
carried over from Jolt's core corpus. `java.util.zip`-based helpers are the only
gap — there is no `java.util.zip` shim yet.
