`jolt.profile` counts how many times each line and function of your code ran. It reads back the counters Chez Scheme's `compile-profile` puts on every expression of the code it compiles, and maps them to Clojure files, lines and fns. The counts are exact, not sampled.

```clojure
(require '[jolt.profile :as prof])
```

## Profiling at the REPL

Instrumentation is a compile-time switch: it applies to code compiled after you turn it on. Turn it on, then load or reload what you want to measure.

```clojure
(prof/instrument!)
(require 'my.app :reload)
(prof/instrument! false)   ; optional: stop instrumenting newly compiled code
(prof/clear!)              ; drop the counts loading made
(my.app/run)
(prof/report)
```

`report` prints two tables: the fns that did the most work, and the lines that ran most.

```
jolt.profile: fns by work
       evals        hits  fn
       42405        1973  my.app/fib                       my/app.clj:3
        1405         101  my.app/sum-to                    my/app.clj:8

jolt.profile: lines by hits
        hits  line
        1973  my/app.clj:4                              (if (< n 2)
         986  my/app.clj:6                              (+ (fib (- n 1)) (fib (- n 2)))))
         101  my/app.clj:10                             (if (< i n)
         100  my/app.clj:11                             (recur (inc i) (+ acc i))
```

- **hits** is how many times a line ran. For a fn it is its most-run line, which is its call count unless it loops.
- **evals** is how many expressions the fn evaluated in all, the closest measure of how much work it did.

`(prof/report {:limit 50})` shows more rows.

`annotate` prints a file with each line's count in the margin:

```clojure
(prof/annotate "src/my/app.clj")
```

```
       | (defn fib [n]
  1973 |   (if (< n 2)
       |     n
   986 |     (+ (fib (- n 1)) (fib (- n 2)))))
```

The data behind the tables is available as maps: `line-counts` returns `{:file :line :hits :evals :fn}` per line, most-run first, and `fn-counts` returns `{:fn :file :line :hits :evals}` per fn, most work first.

## Profiling a whole run

Set `JOLT_PROFILE=1` and the whole run is instrumented. The report prints on stderr when the process exits.

```sh
JOLT_PROFILE=1 jolt run -m my.app
```

Code that runs as a namespace loads (each `defn` form, top-level calls) counts toward its lines, but not toward any fn.

## What is counted

Only code jolt compiles at runtime is instrumented: your namespaces and the libraries they load from source. The runtime and the stdlib compiled into the jolt binary are not, so a call into `clojure.core` counts as one evaluation of the expression that makes it.

- While instrumentation is on, namespaces load from source instead of the AOT cache, and instrumented code runs slower than normal code. Use the counts to compare parts of a program, not to time it.
- Counters are not atomic. Counts are exact for code running on one thread, but code running on several threads at once loses increments, often most of them.
- A method of a `defrecord`, `deftype`, `extend-type` or `extend-protocol` is reported as `my.app/Type.method`, and a `defmethod` body as `my.app/multi[:dispatch-value]`.
- Code that is compiled and then dropped, such as an `eval` in a loop, keeps its counts after the collector reclaims it.
- Counters are available on the Chez backend. On a host without them, `instrument!` throws.

A sampling profiler, which measures time rather than counts and needs no recompilation, and profile-guided builds are designed in [RFC 0015](/docs/rfc/0015-sampling-and-profile-guided-builds.html). Neither is available yet.

## Measuring time and allocation

`measure` evaluates its body and returns the result with what it cost:

```clojure
(prof/measure (into [] (range 1000000)))
;; => {:result [0 1 2 ...]
;;     :real-ms 94.064 :cpu-ms 42.797167
;;     :allocated-bytes 82318032
;;     :gc-count 5 :gc-ms 7.903}
```

`:allocated-bytes` counts every byte allocated while the body ran, including what was collected again. `measure*` takes a no-argument fn instead of a body.
