# RFC 0015 — Sampling profiles and profile-guided builds

- **Status**: Part 1 implemented; part 2 is a draft
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
   recompilation and costs one to three percent while it runs.
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

**As built.** A thread with only a preemption registered is *solo*: its state
register reads 0, and the preempt demand lives in the hardware timer itself, so
arming is the register read plus the `set-timer` and a yield measured the same
before and after. Registering any other client moves the thread to a state
vector, and the last one leaving moves it back. Two things the shared timer
needed that the hand-off did not:

- The preempt handler now masks interrupts across its enqueue and switch, as a
  yield already did. Another client's demand can be armed while it runs, and a
  fire between the enqueue and the switch would queue the fiber twice.
- A poll that is registered runs whenever a preemption is due, even before its
  own deadline. A preemption parks the fiber, the park cancels the poll and the
  resume registers it again, so on a fiber whose quantum is shorter than the
  poll interval a poll that waited for its deadline would never run.

### Taking a sample

The sampler registers a demand of 10,000 ticks to start with. Each time it is
due, it reads the thread's CPU clock (`(current-time 'time-thread)`) and takes a
sample only once the configured interval has passed, 1 ms by default. Ticks
count calls rather than time, so this check is what makes the profile
time-weighted. The next demand is sized from the ticks per nanosecond the thread
just ran at, so a check lands about when the interval ends and most firings are
a clock read.

A sample is:

- **the stack.** The handler captures its continuation and walks the frames, up
  to a depth cap (64 by default; deeper stacks keep the innermost frames and are
  marked truncated). A frame is identified by its code object and return
  offset. The first time a thread sees one it reads the frame's name and source
  position and caches them as a *site*; after that a frame costs two hash
  lookups. Namespaces and Clojure lines are resolved at report time, through the
  same source registry that error reports use. Nothing retains the
  continuation, so a sample does not keep the stack's heap alive.
- **the weight.** The thread CPU time since the previous sample. Time a thread
  spends in a foreign call shows up on its return, in the frame that made the
  call, which is where it belongs.
- **the thread.** The thread id. On a carrier, the scheduler frames below the
  fiber's own stack are dropped by the same naming rule that keeps them out of
  error traces. The fiber itself is not recorded.

Collector time is reported once, for the whole run, from the `sstats` delta.
Charging it per sample would count one collection on every thread at once. A
collection is also in the CPU time of the thread that ran it.

Samples are counted in a per-thread table keyed by stack, so memory grows with
the number of distinct stacks rather than with the length of the run, and
nothing is dropped. Each table has its own lock, contended only by a reader.

**Which threads.** A thread joins when sampling starts on it, when it is forked
while sampling is on, and otherwise at its next timer event of any kind: a
fiber's quantum or a `run-interruptible` poll. A thread that was already running
and has none of those is not sampled until it does.

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
- **Lines inside a loop with no calls.** Lines come from the markers the back
  end places before call sites, so a frame stopped in a loop that makes no calls
  reads at its fn's `defn` line.
- **Inlined fns in a build.** A fn the build inlined has no frame of its own and
  is charged to the fn it was inlined into.

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
  (my.app/run))                   ; => {:result ... :counts ...}
```

`sample-counts` returns `{:fn :file :line :self-ms :total-ms :samples}` per fn,
`sample-lines` the self time per line, and `samples` each distinct stack.
`write-folded` writes the collapsed-stack format (`a;b;c 123`) that
`flamegraph.pl` and speedscope read. For a whole run,
`JOLT_PROFILE=sample jolt run -m my.app` prints the report on stderr at exit,
like `JOLT_PROFILE=1`. The same variable works on a built binary, because the
sampler needs the runtime and not the compiler. A binary includes `jolt.profile`
only when the app requires it, so without it the runtime prints the fn tables
itself.

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
  identical unit text. (P1 found nothing to fix: cold and warm caches, and the
  default, `--opt` and `--tree-shake` modes, already give identical text. Unit
  file names still depend on the output path, so P2 compiles each unit under a
  fixed name.) It also adds the profile's digest for each unit to that
  unit's cache key, so a profile-guided unit is never served from the plain
  cache or the other way round.

**The dump format.** Chez's `profile-dump-data` writes every block with its
whole source descriptor and record type, about 370 bytes a block. The runtime
has 680,000 blocks, so that is 255 MB a run, and it needs the compiler in the
binary. P2 writes its own format instead, three numbers a block (9 MB), and
converts one unit's data to Chez's format just before that unit compiles.
Loading the runtime's data still costs Chez about 20 seconds, which the
runtime cache keeps to the first build with a given profile.

The runtime and `clojure.core` are compiled as part of the build, so they are
profiled and optimized for the application's workload too. The source-level
counters in `jolt.profile` cannot do that, because they stop at the boundary of
code compiled at runtime.

**Stale profiles.** When the sources change, a profile from the old build no
longer matches. Chez does not raise for that: it matches data to code by the
checksum of the text and compiles text with no match as if there were no
profile. It raises only when the text matches and the blocks do not line up.
So `--profile-use` loads each unit's data itself and fails, naming the unit,
when there is none, and the dump records the build mode so a profile from
another mode is refused before anything compiles. A profile that is
silently partly applied would make the build's speed depend on how stale it is,
which is harder to diagnose than a failed build.

**Order of rounds.** Block profiles come first, because they need one
generate/use cycle. Using source and block weights together needs the source
profile loaded before *both* the block-generating compile and the final one (the
five-step sequence in the Chez User's Guide). It is a second round, and it ships
only if the benchmarks show the unrolling and `case` ordering are worth the
extra cycle.

P3 decided against it for now. Block profiles alone gave about 5% (below), and
what source weights add overlaps with tier 2: P4 orders `case` by jolt's own
site counts and sets the inlining budget, with a profile that survives edits.
A second Chez cycle would also add another load of the runtime's data to a
build that already spends most of its time there. Source weights come back as
an option if P4 leaves hot loops on the table.

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
| S1 (done) | Per-thread tick scheduler; fibers and `run-interruptible` become clients | fiber, gosm and interrupt suites unchanged; yield A/B |
| S2 (done) | Sampler, per-thread stack tables, `sample-report`, `write-folded` | exact-attribution fixtures (a known hot fn holds the majority of samples); overhead under 2% at 1 ms |
| S3 (done) | `JOLT_PROFILE=sample`, built binaries, fibers, Gambit degradation | smoke on a built binary; capability-tier rows |
| P1 (done) | Reproducible build text | build twice, compare every unit |
| P2 (done) | `--profile-gen` / `--profile-use` with block profiles, profile in the unit cache key, stale-profile error | round trip on a fixture app; stale profile fails with the unit named |
| P3 (done) | Benchmarks; decide on source + block | both columns published |
| P4 | jolt site profile, inlining budget, `case` order | stale-site counts; byte-identical output with and without the profile |
| P5 | Receiver and number-kind profiles from inline caches, guarded speculation | divergence corpus run with a deliberately wrong profile |

S1 measured a yield at 114 ns before and after (median of seven runs each,
alternated). S2's overhead at 1 ms measured 1.4% on a loop, 1.6% on a recursive
fib and 2.9% on an allocating `reduce`, so the allocation case misses the 2%
target.

P3 measured block profiles with `ci/pgo-bench.sh` on GitHub's ubuntu-latest
runners: each of the 46 `bench/run.sh` programs, the ray tracer's two variants
and a honey.sql format loop, built plain and with `--profile-use`, in the
release and `--opt` modes, timed alternately on one runner, min of three runs a
side. The profile was trained on the timed input, which is the best case. Two
full runs gave geomean ratios (profiled over plain) of 0.91 to 0.98 per shard,
about 0.95 overall, in both modes. The gains that repeated were in call- and
branch-heavy code: fib 0.74 to 0.78, tak and `loop`/`recur` 0.87, binary-trees
0.83 to 0.94, the ray tracer 0.79 to 0.94, protocol and collection dispatch
0.86 to 0.93, and honey.sql 0.93 to 0.98. Allocation- and IO-bound programs
were at 1.00, and single rows moved by up to 0.1 between runs. One benchmark,
`nth-access`, read 1.11 to 1.13 in every run that landed on a runner where its
plain build took about 51 ms (three of five), and 0.99 on the faster runners,
where it took 27 ms (eleven runs a side in one of them). On an Apple M-series
machine it read 1.00 every time. So it is slower under a profile on one CPU
class, which by the rule above is a bug in the consumer: it is open, and P4
looks at it first, since `nth` is runtime dispatch a block profile reorders. A `--profile-use` build took 25 to 48 seconds
against 2 to 28 plain, almost all of it Chez loading the runtime's block data.

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
