# Fibers: green threads for core.async

Jolt can run a `go` body on a **fiber** — a green thread scheduled inside the
process — instead of on an operating-system thread. Fibers are cheap enough to
have millions of them, and they share channels with ordinary threads, so the two
kinds of process interoperate freely.

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
functions can do channel work without being written specially.

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
          (a/>! out (f v)))))   ; rewritten

  (a/go (read-two ch)))         ; parks inside a call: captures, as before
```

A body can mix the two freely — the parks it can see get the cheap form, the rest
keep working exactly as they do today. Nothing needs annotating, and there is no
case where you have to know which one you got.

Parking still captures when it happens inside a called function, inside a `try`,
inside a nested `fn`, through `eval`, or in an `alts!`. Those are the cases the
rewrite does not cover yet; they cost memory, not correctness.

## The one rule that matters: blocking work belongs on `thread`

A fiber runs on a **carrier**, an OS thread shared by many fibers. Channel
operations park the fiber and free the carrier for other fibers. Anything else
that blocks — a file read, an FFI call, `Thread/sleep` — blocks the *carrier*,
and every fiber queued behind it waits.

Jolt cannot move a fiber to another carrier to rescue it: a captured continuation
can only be resumed on the thread that captured it. Adding carriers does not
help, because the stranded fibers are already bound to the blocked one. (This is
the one place the JVM has an easier time — its virtual threads can be remounted
on any carrier, so it can compensate by adding one.)

So:

```clojure
;; wrong: pins the carrier and stalls every fiber behind it
(binding [a/*go-backend* :fiber]
  (a/go (slurp "big-file.txt")))

;; right: real blocking work goes on a thread, and the fiber waits on a channel
(binding [a/*go-backend* :fiber]
  (a/go (a/<! (a/thread (slurp "big-file.txt")))))
```

`thread` always spawns a real OS thread, whatever `*go-backend*` says. It is the
documented escape, and it is the correct answer for anything genuinely blocking.

## `<!` and `<!!` are the same thing on a fiber

Off a fiber they differ as always: `<!` is the parking take, `<!!` the blocking
one. On a fiber, **both park**. Parking preserves what a blocking take means —
your code does not proceed until a value arrives — without holding the OS thread,
so there is no reason to make one of them worse. The JVM throws if you use `<!!`
in a `go` block; Jolt does not need to.

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

Use fibers when you have **many mostly-idle processes doing channel work** —
thousands of connections, a large pipeline, per-item processes. That is where the
spawn cost and the memory difference are decisive, and where fan-in throughput
favours them.

Stay on threads when your `go` bodies **block on something other than a channel**,
or when you want a small number of genuinely parallel workers. A `go` body on a
thread can block freely and runs in parallel with no carrier to strand.

The default is `:thread` for now, and it will stay there until Jolt converts
socket and file IO to park a fiber instead of blocking its carrier. Once that
lands, the main reason to prefer threads goes away.
