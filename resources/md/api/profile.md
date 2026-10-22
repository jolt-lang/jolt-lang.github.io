`jolt.profile` has two profilers:

- **Counts.** How many times each line and fn of your code ran, exact, from the counters Chez Scheme's `compile-profile` puts on every expression it compiles. The code has to be compiled with them, and it runs slower while it carries them.
- **Samples.** Where the CPU time goes. Every thread is interrupted at an interval of its own CPU time and its stack is recorded. Nothing is recompiled, so it sees all code, including the runtime, `clojure.core` and built binaries, and costs a few percent while it runs.

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

## Sampling

```clojure
(prof/sample!)             ; every 1 ms of each thread's CPU time
(my.app/run)
(prof/stop-sampling!)
(prof/sample-report)
```

`sample-report` prints the fns by self time (in the fn itself), the fns by total time (in it and everything it called), and the lines by self time:

```
jolt.profile: 158 samples, 159.0 ms of CPU time, 0.0 ms in the collector

fns by self time
     self ms    self    total ms  fn
       159.0  100.0%       159.0  my.app/spin                      my/app.clj:3
         0.0    0.0%       159.0  my.app/driver                    my/app.clj:13
         0.0    0.0%       118.7  my.app/hot                       my/app.clj:9
         0.0    0.0%        40.3  my.app/cold                      my/app.clj:11
```

`(prof/sample! {:interval-ms 5 :depth 32})` samples less often or keeps fewer frames per stack. A new `sample!` drops the samples of the last run.

The rows are available as data. `sample-counts` returns `{:fn :file :line :self-ms :total-ms :samples}` per fn, most self time first. `sample-lines` returns `{:file :line :fn :self-ms :samples}` per line. `samples` returns each distinct stack with its `:thread`, `:count` and `:ms`.

`with-sampling` samples while its body runs:

```clojure
(prof/with-sampling {:interval-ms 1} (my.app/run))
;; => {:result ... :counts [{:fn my.app/spin :self-ms 159.0 ...} ...]}
```

### Flame graphs

`write-folded` writes the stacks in the collapsed format that flame graph tools read, one stack per line, root first, with its CPU time in microseconds:

```clojure
(prof/write-folded "app.folded")
```

```sh
flamegraph.pl app.folded > app.svg     # or open app.folded in speedscope.app
```

### Sampling a whole run

```sh
JOLT_PROFILE=sample jolt run -m my.app
JOLT_PROFILE=sample ./my-app           # a binary made by jolt build
```

The report prints on stderr when the process exits. A built binary that does not include `jolt.profile` prints the two fn tables.

### What a sample shows

- Every thread is sampled: futures, agents, `go` blocks and nREPL sessions as well as the main thread. A thread started while sampling is on is sampled from its start. A thread that was already running joins at its next timer event, which a fiber gets every quantum and an interruptible eval every poll.
- Time is CPU time. A thread blocked on a lock, a channel or a socket uses none and gets no samples.
- A tail call replaces its caller's frame, so the caller is not on the stack.
- Frames without a jolt fn behind them show their Scheme name. On a whole run you will see Chez's compiler passes, which are the time spent compiling code.
- A frame stopped in a loop that makes no calls shows its fn's `defn` line, because lines are found from the calls a fn makes.
- In a built binary, a fn the build inlined is charged to the fn it was inlined into.
- Collector time is reported for the whole run. A collection is also counted in the CPU time of the thread that ran it, against the frame where it started.

Sampling runs on the Chez backend. On a host without it, `sample!` throws.

Profile-guided builds are designed in [RFC 0015](/docs/rfc/0015-sampling-and-profile-guided-builds.html) and are not available yet.

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
