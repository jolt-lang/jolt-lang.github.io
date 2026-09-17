Most Clojure runs at the same speed on Jolt whether or not you think about the runtime underneath. A few shapes don't: they are cheap on the JVM or in JavaScript because of a representation trick those runtimes have and Chez Scheme does not, and they show up as the whole cost of a program that is otherwise fine. This page is the short list, each with the reason, the idiom that avoids it, and what it measured. All numbers are one machine (Apple M1 Max) in one `--opt` binary; the ratios are what matter.

Everything here is about speed only. None of it changes a result.

## Build strings with a `StringBuilder`, not `str` in a loop

A Chez string is one flat array of 32-bit code points. `(str a b)` allocates a new string of `(+ (count a) (count b))` characters and copies both in — there is no rope, no shared tail, no in-place append. So an accumulator that grows by `str` copies everything it has so far on every step, and building an *n*-character string that way is O(n²) in bytes copied, at four bytes per character:

```clojure
;; O(n^2): each swap! copies the whole line so far
(swap! line-txt str token)

;; O(n): the builder keeps chunks and joins once when read
(.append line-txt token)          ; line-txt is a (StringBuilder.)
(.toString line-txt)
```

V8 hides this in JavaScript: `s += t` builds a cons-string (a rope) and flattens it once, on demand. The JVM copies like Chez does, but at one or two bytes per character. On Jolt the copy is the whole cost of a formatter's output loop — 57% of its time on an ordinary source file, 93% on a file that is one 48 KB line.

Joining 2,000 six-character tokens into one 12 KB string:

| shape | time | allocated |
| --- | ---: | ---: |
| `(reduce str "" parts)` | 1,978 µs | 46.9 MB |
| `StringBuilder` `.append` + `.toString` | 115 µs | 266 KB |
| `(str/join parts)` / `(apply str parts)` | 120 µs | 425 KB |

`str/join` and `(apply str ...)` are fine: they size the result once. The trap is specifically an accumulator that is *read back as a string between appends*. Jolt's `StringBuilder` is chunked — `append` is O(1) and the chunks are joined the first time something reads the buffer — so `.length`, `.append` and a final `.toString` are the pattern. `.charAt`, `.setLength` and `.toString` each force one join; a loop that reads `charAt` after every append is the O(n²) shape again, so trim or inspect once per line, not per token.

## Match at a position with a region matcher, not `subs`

`(subs s i j)` copies its range — again four bytes per character. A tokenizer that tries a regex at a position by slicing the remaining input first pays that copy on every attempt whether or not the pattern matches. JavaScript's `substring` is a view onto the parent string, and a sticky `RegExp` matches at `lastIndex` without any slice; the Jolt equivalent of a sticky regex is a `Matcher` with a region:

```clojure
(let [m (re-matcher re txt)]          ; once per input
  (.region m pos (count txt))         ; per attempt: no copy
  (re-find m))                        ; or (.lookingAt m), (.find m)
```

`^` anchors at the region start (the JVM's anchoring-bounds default), so an anchored pattern tries exactly once at `pos`. `re-find` on the matcher returns the same groups vector `(re-find re s)` would; `.start`/`.end`/`.group` read the match after `.lookingAt` or `.find`.

Trying an anchored pattern at 1,000 positions of a 12 KB string:

| shape | time | allocated |
| --- | ---: | ---: |
| `(re-find re (subs txt pos (+ pos 2048)))` | 850 µs | 8.4 MB |
| `(.region m pos len)` + `(re-find m)` | 463 µs | 940 KB |

A matcher is one small object per input; share it across attempts, not across threads (each holds its own cursor).

## Prefer keyword keys and literal maps

Two things the compiler does for maps with **constant keyword keys**:

- A literal like `{:id id :name name :text text}` is built straight into its slot vector: 12 ns for ten keys. A map with a computed key, a non-keyword key, or more than 64 entries goes through the checked constructor, which also has to look for duplicate keys — 134 ns for the same ten. `(hash-map ...)` always builds the hashed form and is the slowest of the three; write it only when you mean a hash map.
- A lookup site `(:k m)` remembers which slot it found `:k` in last time. Maps built by the same literal put a key at the same slot, so the next lookup is one identity test and one vector read — 2 ns — where a scan of a ten-key map is 6–11 ns depending on the slot. Nothing is required of you beyond a keyword key in call position; `(get m :k)` and `(m :k)` take the ordinary scan.

Small maps are array maps (up to 8 keys of any kind, up to 64 when every key is a keyword), and `assoc` on one copies its slot vector, exactly like `PersistentArrayMap` on the JVM. A loop that `assoc`es several fields onto the same map one at a time copies it several times; build it in one literal, or use `transient`/`persistent!` around a batch of updates.

## What is not a lever

- **GC settings.** The collector is generational and its cost is the survivors, not the nursery size. Raising the nursery from 16 MB to 256 MB on an allocation-heavy formatter (475 MB per run) left the wall clock flat while the process high-water mark went from 143 MB to 362 MB. Reduce allocation instead; the two idioms above are where most of it was.
- **Escape continuations.** `jolt.continuations/letcc` is 34 ns and 144 bytes per capture-and-escape; using it for early exit inside a parser is fine. See [Continuations](/docs/api/continuations.html).
- **`clojure.string/index-of`, `last-index-of`, `starts-with?`, `ends-with?` and `includes?`** are native scans on Jolt and allocate nothing, including the `from` arities.

## Measuring

Bytes allocated is the most useful single number, and it is deterministic where nanoseconds are not. From Clojure:

```clojure
(require '[jolt.scheme :as scm])
(defn allocated [] (scm/eval-string "(sstats-bytes (statistics))"))

(let [b (allocated)] (f) (- (allocated) b))   ; bytes one call allocated
```

`jolt.host/gc-count`, `jolt.host/gc-cpu-nanos` and `jolt.host/maximum-memory-bytes` give the collector's share and the footprint. For time, `System/nanoTime` around a warm loop of a few hundred iterations, minimum of several rounds — a single cold run of a built binary includes the first collections and, for regexes, the first compile of each pattern.
