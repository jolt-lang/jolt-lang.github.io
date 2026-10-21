# RFC 0015 — Sampling profiles and profile-guided builds

- **Status**: Draft
- **Champions**: jolt maintainers
- **Builds on**: `jolt.profile` (execution counts), RFC 0007 (compilation modes)

## Summary

`jolt.profile` counts how often each line ran. Counts tell you where the code
goes but not where the time goes, they only cover code compiled with
instrumentation, and they slow down the code they measure. This RFC adds two
things on top:

1. **A sampling profiler.** Every thread is interrupted at a fixed CPU-time
   interval and its stack is recorded. It sees all code, including the runtime,
   `clojure.core`, AOT-cached namespaces and built binaries. It needs no
   recompilation and costs about one percent while it runs.
2. **Profile-guided builds.** `jolt build --profile-gen` makes an instrumented
   binary that writes a profile when it exits. `jolt build --profile-use` feeds
   that profile back into the compiler. The first tier uses Chez Scheme's own
   block-profile optimizations: basic-block layout and register allocation. A
   second tier lets jolt's own passes read the profile, keyed by Clojure source.

A profile only ever changes how fast the code runs. It never changes what the
code does.

## Part 1: sampling

### Where a sample can come from

Chez gives a running thread three ways to be interrupted, and all three are
polled at the same safe points: procedure calls and loop back-edges.

| Mechanism | Reaches | Already used by |
| --- | --- | --- |
| Engine timer (`set-timer`, `timer-interrupt-handler`) | the thread that armed it | fiber preemption, `run-interruptible` |
| Signals (`register-signal-handler`) | the **main thread only**; Chez forwards a signal that lands on any other thread to the main one | — |
| Keyboard interrupt | the thread whose pending flag is set | SIGINT handling |

The only mechanism that reaches every thread is the engine timer. A SIGPROF
profiler would see nothing a future, an agent, a `go` block or an nREPL session
runs. Setting another thread's pending flags directly works the way the Chez
collector does it, but it relies on internal fields and would compete with
SIGINT. So sampling rides the engine timer.

### The timer gets one owner

The timer is a single per-thread resource that two clients already share by
hand. `run-interruptible` saves the scheduler's handler, installs its own, counts
the ticks it borrows so a fiber's quantum still falls due, and hands everything
back through a `dynamic-wind` so that a park cannot leak the borrow. The comments
around that code record three separate bugs in that handoff. A third client written the same way would need a third copy of the
accounting.

So the first round adds no profiler. It replaces the ad-hoc borrowing with a
**per-thread tick scheduler** that owns `timer-interrupt-handler` and
`set-timer`:

- A client registers a *demand*: a callback and a number of ticks until it is
  next due. The scheduler arms the smallest outstanding demand.
- When the timer fires, the scheduler subtracts the ticks that elapsed from every
  demand and runs the clients that are due, in a fixed order. The sampler runs
  first because it only records and always returns. The interrupt check comes
  next, and it may escape. Preemption comes last, and it may switch fibers.
- Refusing is part of the protocol. A client that cannot act yet (a lock is held,
  a transition is committed) re-registers with a short retry, as the preemption
  handler already does.
- The fiber scheduler, `run-interruptible` and the sampler all become clients.
  The `borrowed`/`armed` accounting in `jolt-run-interruptible` goes away,
  because the scheduler does that accounting once for everyone.

**Cost rule.** With only the preemption client registered, arming must stay a
single `set-timer`. The current yield cost (243 ns with preemption on) is the
ceiling. This round is a refactor with no behavior change, and the fiber, gosm
and interrupt suites gate it unchanged.

### Taking a sample

The sampler registers a short demand: on the order of 10,000 ticks, which is
about 5 µs on the machine the fiber quantum was measured on. Each time it is
due, it reads the thread's CPU clock (`(current-time 'time-thread)`). It takes a
sample only once the configured interval has passed, 1 ms by default. Ticks
count calls rather than time, so this check is what makes the profile
time-weighted. Most firings are just a clock read and return.

A sample is:

- **the stack.** The handler captures its continuation and walks the frames, up
  to a depth cap (64 by default; deeper stacks keep the innermost frames and are
  marked truncated). It records each frame's code object and return offset, not
  names. Names, namespaces and lines are resolved at report time, through the
  same source registry that error reports use. Nothing retains the
  continuation, so a sample does not keep the stack's heap alive.
- **the weight.** The thread CPU time since the previous sample. Time a thread
  spends in a foreign call shows up on its return, in the frame that made the
  call, which is where it belongs.
- **GC time.** Collector CPU time from the `sstats` delta since the previous
  sample, charged to a `[gc]` pseudo-frame.
- **the thread.** The thread id and, on a carrier, the fiber. Scheduler frames
  below the fiber's own stack are dropped.

Samples go into a per-thread buffer: a preallocated ring with no locks and
nothing shared between threads. Buffers are merged when the profile is read. When
a ring is full, the oldest samples are overwritten and the count of dropped
samples is reported.

### What a sampling profile does not show

- **Tail-called frames.** Proper tail calls (RFC 0011) erase the caller's frame,
  so a stack shows the non-tail spine, as `perf` shows it on optimized C. The
  call-site tables that rebuild tail chains for error reports could later fill
  these gaps in reports too. That is not in the first version.
- **Blocked threads.** This is a CPU profile. A thread waiting on a lock, a
  channel or a socket uses no CPU and gets no samples. A wall-clock mode that
  also samples blocked threads is a separate design.
- **Time between safe points.** A sample lands at the next poll after the
  interval ends. Chez polls at every non-leaf call and every loop back-edge, so
  the skew is small. It does mean a hot leaf is attributed to the frame that
  called it.

### API

The sampler lives in `jolt.profile`, next to the counters. The data is in the
same shapes, so the two can be compared directly.

```clojure
(prof/sample! {:interval-ms 1})   ; start sampling every thread
(my.app/run)
(prof/stop-sampling!)
(prof/sample-report)              ; fns and lines by self and total time
(prof/write-folded "app.folded")  ; collapsed stacks for flame graph tools

(prof/with-sampling {:interval-ms 1}
  (my.app/run))                   ; => {:result ... :samples ...}
```

`sample-counts` returns `{:fn :file :line :self-ms :total-ms :samples}` per fn.
`write-folded` writes the collapsed-stack format (`a;b;c 123`) that
`flamegraph.pl` and speedscope read. For a whole run,
`JOLT_PROFILE=sample jolt run -m my.app` prints the report on stderr at exit,
like `JOLT_PROFILE=1`. The same variable works on a built binary, because the
sampler needs the runtime and not the compiler.

On a host without an engine timer, `sample!` throws `{:host :unsupported}` in
the capability-profile tier, the way `instrument!` does.

## Part 2: profile-guided builds

### Tier 1: Chez block profiles for `jolt build`

Chez can instrument every basic block (`compile-profile 'block`), write the
counts (`profile-dump-data`), and later load them (`profile-load-data`) so that
the next compile of the **same** code pushes cold blocks out of line and gives
registers to the variables used on hot paths. Source-level weights also raise
the loop unrolling limit for hot loops and order `case` clauses by how often
they matched.

```sh
jolt build --profile-gen -m my.app -o app-gen
./app-gen < workload-1 ; ./app-gen < workload-2   # each run writes app.jpd.N
jolt build --profile-use app.jpd.* -m my.app -o app
```

The constraint that decides the design: Chez matches profile data to code by
source file descriptor (CRC, length and file name), and block data only lines up
when the code being recompiled is identical. Two consequences:

- **The runtime eval path cannot take part.** It names each compiled form
  `jolt-eval-src-N` from a per-process counter, so nothing it compiles has the
  same descriptor twice. Tier 1 is for `jolt build` only.
- **The build has to be byte-reproducible, and it nearly is.** The build already
  caches one compiled unit per namespace keyed on the unit's text, which is why
  its name counters are per-namespace and per-phase rather than per-process. Tier 1 adds a gate: building the same sources twice produces
  identical unit text. It also adds the profile's digest for each unit to that
  unit's cache key, so a profile-guided unit is never served from the plain
  cache or the other way round.

The runtime and `clojure.core` are compiled as part of the build, so they are
profiled and optimized for the application's workload too. The source-level
counters in `jolt.profile` cannot do that, because they stop at the boundary of
code compiled at runtime.

**Stale profiles.** When the sources change, a profile from the old build no
longer lines up, and Chez raises. `--profile-use` turns that into a build error
that names the unit and says to regenerate the profile. A profile that is
silently partly applied would make the build's speed depend on how stale it is,
which is harder to diagnose than a failed build.

**Order of rounds.** Block profiles come first, because they need one
generate/use cycle. Using source and block weights together needs the source
profile loaded before *both* the block-generating compile and the final one (the
five-step sequence in the Chez User's Guide). It is a second round, and it ships
only if the benchmarks show the unrolling and `case` ordering are worth the
extra cycle.

### Tier 2: jolt's passes read the profile

Chez sees Scheme. Some of the most useful decisions are made before Scheme
exists: what to inline, which dispatch to specialize, which arithmetic to type.
Tier 2 writes a jolt profile keyed by **Clojure source**, not by emitted text:

```clojure
{:version 1
 :sites {["my/app.clj" 12 5 form-hash] {:hits 1973 :receivers {"my.app.Circle" 1973}}}}
```

A site is identified by its file, line, column, and a hash of the form as read.
An edit elsewhere in the file leaves the site's entry valid. An edit to the form
itself invalidates only that entry. `--profile-use` reports how many sites
matched and how many were stale, and ignores the stale ones. Unlike tier 1, a
partly stale profile is safe here, because each site's decision is independent.

The data comes from what already exists: `jolt.profile` counts give `:hits`, and
the per-site inline caches that protocol dispatch, `instance?` and keyword
lookup already keep give the observed receiver types. Dumping cache states at
exit produces a type profile at no extra cost while the program runs.

Consumers, in the order they would be built:

1. **Inlining budget.** Hot call sites get a larger budget and cold ones get
   none. The inliner roughly doubles build memory today, so this should make
   builds smaller as well as faster.
2. **`case` dispatch order.** Clojure `case` tests constants with no side
   effects, so ordering the tests by hit count is invisible to the program.
   `cond` keeps its order: its tests can have effects.
3. **Guarded speculation.** A protocol call, keyword lookup or arithmetic site
   that only ever saw one receiver type or one number kind compiles to a guard,
   then the specialized path, then the generic path as fallback. Whole-program
   inference already does this where it can *prove* the type. The profile
   extends it to sites where the type was only *observed*, and the guard keeps
   the program correct when the observation is wrong.

Every tier-2 transformation stays correct for inputs the profile never saw. That
is a review rule for each consumer: a decision the profile drives must still be
correct if the profile is wrong.

## Measurement

Each round reports both columns in one session (with and without, A/B/A),
against `bench/run.sh` plus the ray tracer and honeysql workloads, and the gates
check scaling ratios within one process instead of absolute times. Two rules come
from the existing performance ceiling:

- The timer refactor must not slow a yield or a preemption by more than 1.1x.
- `--profile-use` must not make any benchmark slower than the plain build. A
  profile that makes code slower is a bug in the consumer, not noise.

Expectations are modest on purpose. Block-level PGO usually buys a few percent;
the larger wins, if there are any, are in tier 2's inlining and speculation, and
they are only claimed once measured.

## Rounds

| Round | Delivers | Gate |
| --- | --- | --- |
| S1 | Per-thread tick scheduler; fibers and `run-interruptible` become clients | fiber, gosm and interrupt suites unchanged; yield A/B |
| S2 | Sampler, per-thread rings, `sample-report`, `write-folded` | exact-attribution fixtures (a known hot fn holds the majority of samples); overhead under 2% at 1 ms |
| S3 | `JOLT_PROFILE=sample`, built binaries, fibers, Gambit degradation | smoke on a built binary; capability-tier rows |
| P1 | Reproducible build text | build twice, compare every unit |
| P2 | `--profile-gen` / `--profile-use` with block profiles, profile in the unit cache key, stale-profile error | round trip on a fixture app; stale profile fails with the unit named |
| P3 | Benchmarks; decide on source + block | both columns published |
| P4 | jolt site profile, inlining budget, `case` order | stale-site counts; byte-identical output with and without the profile |
| P5 | Receiver and number-kind profiles from inline caches, guarded speculation | divergence corpus run with a deliberately wrong profile |

## Alternatives not taken

- **SIGPROF with `setitimer`.** It needs no timer work, but Chez only delivers
  signals to the main thread, so most of what a server or a core.async program
  does would never be sampled.
- **An OS profiler (`perf`, Instruments) with a symbol map.** It sees native
  frames and blocked threads, but Chez code objects move during collection and
  carry no symbols the OS can read. Keeping a symbol map current across every GC
  costs more than the in-process sampler, and it still cannot name Clojure lines.
- **Feeding Chez source profiles from the runtime eval path.** The per-form
  descriptor names differ in every process, so the profile database would never
  match. Tier 2 keys by Clojure source because that is what stays the same
  between runs.
