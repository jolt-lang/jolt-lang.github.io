# Fibers: green threads for core.async

Jolt can run a `go` body on a **fiber** — a green thread scheduled inside the
process — instead of on an operating-system thread. Fibers are cheap enough to
have hundreds of thousands of them, and they share channels with ordinary
threads, so the two kinds of process interoperate freely.

This is **opt-in**. By default `go` still spawns a real OS thread, which is what
it has always done in Jolt.

```clojure
(require '[clojure.core.async :as a])

;; the default: one OS thread per go block
(a/go (a/<! ch))

;; on a fiber
(binding [a/*go-backend* :fiber]
  (a/go (a/<! ch)))
```

`*go-backend*` is read when a `go` spawns, not when it is compiled, so the
binding covers every `go` that runs inside its scope — including ones inside
functions it calls.

## core.async on Jolt

The whole library is here: `chan` with the three buffer kinds, `go`/`go-loop`,
`<!`/`>!`/`<!!`/`>!!`, `alts!`/`alts!!`, `poll!`/`offer!`, `timeout`,
`promise-chan`, `close!`, `thread`, `pipe`, `pipeline`/`pipeline-blocking`/
`pipeline-async`, `split`, `reduce`/`transduce`/`into`/`take`, `mult`/`tap`,
`mix`, `pub`/`sub`, `map`, `merge`, and the deprecated `map<`/`filter>`/… set.
Everything below runs the same on either backend.

A squaring stage between two channels:

```clojure
(binding [a/*go-backend* :fiber]
  (let [in (a/chan 8), out (a/chan 8)]
    (a/go-loop []
      (when-let [v (a/<! in)]
        (a/>! out (* v v))
        (recur)))
    (a/go (doseq [i (range 5)] (a/>! in i))
          (a/close! in))
    (a/<!! (a/into [] (a/take 5 out)))))         ;=> [0 1 4 9 16]
```

`alts!`, with a timeout, a put spec, and a `:default` probe that never parks:

```clojure
(binding [a/*go-backend* :fiber]
  (let [ch (a/chan), out (a/chan 1)]
    (a/go (let [[v port] (a/alts! [ch (a/timeout 50)])]
            (if (= port ch) [:value v] :timed-out)))   ;=> :timed-out
    (a/<!! (a/go (a/alts! [[out :v]])))                ;=> [true #<channel>]
    (a/<!! (a/go (a/alts! [ch] :default :none)))))     ;=> [:none :default]
```

A parallel transform, fan-out to two consumers, and topic routing:

```clojure
(binding [a/*go-backend* :fiber]
  (let [out (a/chan 10)]
    (a/pipeline 4 out (map inc) (a/to-chan! (range 10)))
    (a/<!! (a/into [] out))))                    ;=> [1 2 3 ... 10]

(binding [a/*go-backend* :fiber]
  (let [src (a/chan 4), m (a/mult src), a1 (a/chan 4), a2 (a/chan 4)]
    (a/tap m a1)
    (a/tap m a2)
    (a/go (a/>! src :x) (a/close! src))
    [(a/<!! a1) (a/<!! a2)]))                    ;=> [:x :x]

(binding [a/*go-backend* :fiber]
  (let [src (a/chan 4), p (a/pub src :topic), evens (a/chan 4)]
    (a/sub p :even evens)
    (a/go (a/>! src {:topic :even :n 2})
          (a/>! src {:topic :odd  :n 3}))
    (a/<!! evens)))                              ;=> {:topic :even :n 2}
```

A `go` body that throws closes its channel like any other, so the reader cannot
tell it apart from one that returned nil. `fiber-monitor` can:

```clojure
(let [g (a/go (throw (ex-info "boom" {})))]
  (a/<!! g)                                    ;=> nil, like a body that returned nil
  (a/<!! (a/fiber-monitor g)))                 ;=> the throwable, or nil if it lived
```

It answers the same way on either backend. Monitoring a channel that is not a
`go` channel gives nil rather than an error.

`(timeout ms)` is served by one shared timer thread, so `(<! (timeout 100))`
parks the fiber and costs no thread. `(Thread/sleep 100)` does not — see
[what parks and what pins](#what_parks_and_what_pins) below.

## Parking works through function calls

On the JVM, `go` is a macro that rewrites its body into a state machine, and a
parking operation has to appear **lexically** inside the body. You cannot put
`<!` in a helper function and call it from a `go` block.

Jolt's fibers capture a continuation, so there is no such restriction:

```clojure
(defn read-one [ch] (a/<! ch))          ; parks, several frames deep
(defn read-two [ch] [(read-one ch) (read-one ch)])

(binding [a/*go-backend* :fiber]
  (a/go (read-two ch)))                  ; works
```

This is the main thing fibers buy you beyond cost, and it means ordinary
functions can do channel work without being written specially. A park reached
through a callback, a higher-order function, or `eval` works for the same
reason.

## `<!` and `<!!` are the same thing on a fiber

Off a fiber they differ as always: `<!` is the parking take, `<!!` the blocking
one. On a fiber, **both park**. Parking preserves what a blocking take means —
your code does not proceed until a value arrives — without holding the OS thread,
so there is no reason to make one of them worse. The JVM throws if you use `<!!`
in a `go` block; Jolt does not need to. The same goes for `alts!` and `alts!!`.

## Two ways to park, picked for you

Capturing a continuation costs a stack segment, held for as long as the process
is parked. Jolt avoids it where it can see how: when a parking operation appears
directly in the `go` body, the compiler rewrites the rest of the body into a
closure, and the channel operation stores that closure instead of capturing
anything. Such a process is about **5.5x smaller while parked** (measured below).

The choice is made per parking site, not per body, and it never changes what your
code does:

```clojure
(binding [a/*go-backend* :fiber]
  (a/go (let [v (a/<! in)]      ; rewritten — no continuation captured
          (a/>! out (f v))))    ; rewritten

  (a/go (read-two ch)))         ; parks inside a call: captures, as before
```

A body can mix the two freely — the parks it can see get the cheap form, the rest
keep working exactly as they do today. Nothing needs annotating, and there is no
case where you have to know which one you got.

Parking still captures when it happens inside a called function, inside a `try`,
inside a nested `fn`, through `eval`, or in an `alts!`. `binding`, `dosync` and
`locking` hand their body over as a function, so a park inside one of those
captures too. All of these cost memory, not correctness.

## A compute-bound body does not starve its carrier

Fibers on one carrier take turns whether or not they reach a channel operation.
The scheduler is preemptive: Chez polls a timer at procedure calls and loop back
edges, so even a tight arithmetic loop yields, at a quantum of roughly 0.45 ms.

```clojure
(alter-var-root #'a/*fiber-carrier-count* (constantly 1))   ; one carrier, on purpose

(binding [a/*go-backend* :fiber]
  (let [done (a/chan 2)]
    (a/go (loop [i 0]                        ; pure computation, never parks
            (if (< i 80000000) (recur (inc i)) (a/>! done :spinner))))
    (a/go (a/>! done :queued-behind-it))
    (a/<!! done)))                           ;=> :queued-behind-it
```

The queued fiber runs while the spinner is still spinning. There is no value of
the quantum that turns preemption off: cooperative-only is an unbounded
starvation window, because a fiber cannot migrate to another carrier to escape
one. Code that wants effectively cooperative behaviour asks for a very long
quantum instead.

Preemption stops at the runtime's edge. A fiber inside a blocking foreign call
is not running Scheme, so no timer fires for it.

## Locks work across a park

A lock is a lock. You can hold a monitor across a `<!`, run a transaction that
parks in the middle, and force a `delay` whose body waits on a channel —
exclusion holds in every case, because those locks carry ownership on the fiber
rather than on the OS thread it happens to be running on.

```clojure
(binding [a/*go-backend* :fiber]
  (let [lock (Object.), ch (a/chan), log (atom [])]
    (a/go (locking lock
            (swap! log conj :a-in)
            (a/<! ch)                        ; parks, still holding the monitor
            (swap! log conj :a-out)))
    (a/go (locking lock (swap! log conj :b)))
    ;; while the first fiber is parked inside the monitor: @log is [:a-in]
    ;; after it is resumed and leaves:                     @log is [:a-in :a-out :b]
    ))
```

## What parks and what pins

A fiber runs on a **carrier**, an OS thread shared by many fibers. Parking frees
the carrier for other fibers. Blocking does not — it holds the carrier, and Jolt
cannot move the queued fibers elsewhere, because a captured continuation can only
be resumed on the thread that captured it. Adding carriers does not help; the
stranded fibers are already bound to the blocked one. (This is the one place the
JVM has an easier time — its virtual threads remount on any carrier.)

| operation | on a fiber |
| --- | --- |
| channel ops, `alts!`, `(<! (timeout ms))` | park |
| socket read / write / `accept` / `connect` | park (readiness goes through `kqueue`/`epoll`) |
| file IO, `slurp`, `Thread/sleep`, an FFI call | **pin the carrier** |

So real blocking work goes on a thread, and the fiber waits for it on a channel:

```clojure
;; wrong: pins the carrier and stalls every fiber behind it
(binding [a/*go-backend* :fiber]
  (a/go (slurp "big-file.txt")))

;; right
(binding [a/*go-backend* :fiber]
  (a/go (a/<! (a/thread (slurp "big-file.txt")))))
```

`thread` always spawns a real OS thread, whatever `*go-backend*` says. It is the
documented escape, and it is the correct answer for anything genuinely blocking.

Sockets need no such care: `jolt.socket` sets `O_NONBLOCK` and registers the fd
with a per-process poller, so a socket read parks the fiber when there is one and
does a plain blocking wait when there is not. The same socket code works either
way.

## Tuning

Both knobs are read **once, when the carrier pool starts** — set them before the
first `:fiber` `go`. Neither is dynamic, so `alter-var-root` rather than
`binding`; a `binding` would be thread-local and the pool that reads them is on
another thread.

| var | default | effect |
| --- | --- | --- |
| `a/*fiber-carrier-count*` | processor count | how many OS threads carry fibers |
| `a/*fiber-preempt-ticks*` | ~0.45 ms | the preemption quantum, in Chez engine ticks |

```clojure
(alter-var-root #'a/*fiber-carrier-count* (constantly 1))
```

Pinning the pool to one carrier is worth knowing about: two fibers ping-ponging
on the same carrier never leave the thread, which is the fastest channel handoff
Jolt has.

## What it costs

Measured on an Apple M1 Max (10 cores, 64 GiB), Chez Scheme 10.4.1. Reproduce
with `make fibersbench` in the Jolt repo.

**Creating processes** that immediately park:

| processes | fiber | OS thread |
| --- | --- | --- |
| 1,000 | 2.8 ms | 74 ms |
| 10,000 | 7.2 ms | 7.2 s |
| 100,000 | 69 ms | **did not get there** — 15,519 threads in 20 s, machine swapping |

**Memory per parked process**, measured as live bytes after a forced full
collection, with peak RSS as a cross-check:

| | live bytes | peak RSS |
| --- | --- | --- |
| fiber | 3,995 B | 5,961 B |
| fiber parked on a channel | 4,129 B | 6,403 B |
| OS thread | 68,720 B | 290,365 B |

The two ways to park, measured against each other in one run — same process
shape, same channel, same retention, so the only difference is the mechanism:

| parked process | live bytes | peak RSS |
| --- | --- | --- |
| rewritten body (no continuation) | 864 B | 1,244 B |
| captured continuation | 4,762 B | 7,368 B |

That is **5.5x by live bytes and 5.9x by RSS**. Both figures include the fiber's
own record and its result channel, which is why the cheap form is a few hundred
bytes rather than the size of the closure alone. Time per park-and-resume round
trip is the same either way as far as this can be measured: 903 ns for the
rewritten form against 858 ns for the capture, over three runs each whose own
spread is 38 and 75 ns.

A fiber is roughly **17x smaller in live bytes and 45x smaller in RSS** than an
OS thread. A million parked fibers is a few GiB, which is the same order as the
BEAM's default footprint for a million Erlang processes.

**Switching**: a bare continuation switch is 9.3 ns. A full scheduler round trip
— park, scheduler, resume, including saving and restoring the fiber's dynamic
state — is 108 ns. Handing a value between two OS threads through a channel is
about 3,163 ns.

**Channel throughput** is the one place the answer is not simply "fibers win":

| workload | fiber | OS thread |
| --- | --- | --- |
| ping-pong, two processes on different carriers | 7.8 µs/round trip | 6.3 µs/round trip |
| ping-pong, both on the same carrier | **1.8 µs/round trip** | — |
| fan-in, 8 producers → 1 consumer | 102,777 values/s | 60,487 values/s |

Two fibers on *different* carriers are slightly slower than two OS threads,
because each handoff has to wake the other carrier — the same cost as waking a
thread, plus scheduling. Two fibers on the *same* carrier never leave the thread
and are about 3.5x faster. Fan-in, where the work is spread over many producers,
favours fibers clearly.

**Scaling** a CPU-bound workload (40 fibers) across carriers: 465 ms on one
carrier, 233 ms on two, 116 ms on four, 92 ms on ten. Because fibers do not
migrate, uneven work does not spread out — the same workload with one fiber doing
ten times the work takes 116 ms instead of 92 ms, since the long fiber holds its
carrier while others finish.

## Choosing

Use fibers when you have **many mostly-idle processes doing channel or socket
work** — thousands of connections, a large pipeline, per-item processes. That is
where the spawn cost and the memory difference are decisive, and where fan-in
throughput favours them.

Stay on threads when your `go` bodies **block on something the poller does not
cover** — file IO, FFI, `Thread/sleep` — or when you want a small number of
genuinely parallel workers with no carrier to strand. `thread` is always
available for the blocking part of an otherwise fiber-shaped program.

The default is `:thread`, and it stays there while the fiber backend accumulates
mileage.
