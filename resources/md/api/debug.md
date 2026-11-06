`jolt.debug` reads a throwable's stack as data, with each frame's locals, and runs a hook before that stack unwinds.

```clojure
(require '[jolt.debug :as debug])
```

## Frames

`frames` returns the frames `.getStackTrace` reports, innermost first, as maps of `:fn` (the fn's symbol), `:file`, `:line` and `:locals`, a map of each local's symbol to its value:

```clojure
(try (my.app/run)
  (catch Throwable t (debug/frames t)))
;; => [{:fn my.app/inner :file "my/app.clj" :line 4 :locals {x 21 y 42}}
;;     {:fn my.app/outer ...} ...]
```

`:file` is the path relative to its source root, as in a stack trace. A throwable that has not been thrown has no frames.

## Seeing every local

A frame holds every param and local in scope only when its code was compiled under `jolt.profile`'s debug mode. The mode is a compile-time switch, so turn it on and then load what you want to debug:

```clojure
(require '[jolt.profile :as prof])
(prof/instrument! :debug)
(require 'my.app :reload)
```

Or set `JOLT_PROFILE=debug` for a whole run. `(prof/instrument-mode)` answers `:debug`, `:counts` or nil, and `(prof/instrument! false)` turns the mode off for code compiled after that. Code already compiled keeps what it was compiled with. Only one mode is on at a time, so debug-compiled code adds nothing to `jolt.profile`'s counts, and `JOLT_PROFILE=debug` prints no report at exit. On a host that cannot keep locals, `instrument!` throws.

With this code loaded under the debug mode:

```clojure
(ns my.app)
(defn inner [x]
  (let [y (* 2 x)]
    (/ y 0)))
(defn outer [a]
  (let [b (inc a)]
    (+ 1 (inner b))))
```

each frame shows its params and locals:

```clojure
(try (my.app/outer 20)
  (catch Throwable t
    (mapv (juxt :fn :locals) (debug/frames t))))
;; => [[my.app/inner {x 21 y 42}]
;;     [my.app/outer {a 20 b 21}] ...]
```

Other code shows only what Chez kept live in the frame: the locals it still reads after the call it is paused in returns. A frame a tail call erased is not on the stack.

Debug-compiled code makes every call except `recur` a real call, as the JVM does, which is what keeps its frames on the stack. Recursion that is not `recur` can run out of stack there where it would not otherwise. The code is also slower, and while the mode is on namespaces load from source rather than from the AOT cache.

A defmethod's body or a record's method compiled under the debug mode gets a frame named as [`jolt.profile`](/docs/api/profile.html) names it (`ns/Type.method`, `ns/mm[dispatch]`).

## A hook before the stack unwinds

`with-break-handler` runs its body. When a throw escapes it, `(handler throwable frames)` runs on the throwing thread before anything unwinds, and the throw then continues:

```clojure
(debug/with-break-handler
  (fn [t frames] (show-to-user t frames))
  (my.app/run))
```

The handler may block, for instance while a tool shows the frames and waits for the user. It cannot resume the throw or change a local. If it throws, that throw propagates instead.

A throw that a catch inside the body handles never reaches the handler. One that a catch clause there does not match does, after that try has unwound to rethrow it, with the frames from where it was thrown.

The handler covers the thread its body runs on. A throw inside a `future` reaches a handler established inside that future, on the future's thread. A handler around the code that started the future sees only the `ExecutionException` that `deref` rethrows.

`call-with-break-handler` is the same thing taking a function of no arguments, for when the body is already a function.

## Keeping the frames of each failed REPL evaluation

[`jolt.nrepl/*eval-wrapper*`](/docs/repl-driven-development.html) calls its function with a thunk of each evaluation, which is the shape `call-with-break-handler` takes. This keeps the frames of the last evaluation that threw, and the error still reaches the client as usual:

```clojure
(def last-frames (atom nil))

(defn wrap-eval [thunk]
  (debug/call-with-break-handler
    (fn [t frames] (reset! last-frames frames))
    thunk))

(binding [jolt.nrepl/*eval-wrapper* wrap-eval]
  (jolt.nrepl/evaluate "(my.app/outer 20)" "user"))

(map (juxt :fn :locals) @last-frames)
;; => ([my.app/inner {x 21 y 42}] [my.app/outer {a 20 b 21}] ...)
```
