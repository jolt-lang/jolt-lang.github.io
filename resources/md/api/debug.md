`jolt.debug` reads a throwable's stack as data, with each frame's locals, and runs a hook before that stack unwinds.

```clojure
(require '[jolt.debug :as debug])
```

## Frames

`frames` returns the frames `.getStackTrace` reports, innermost first, as maps of `:fn` (the fn's symbol), `:file`, `:line` and `:locals`, a map of each local's symbol to its value:

```clojure
(try (my.app/run)
  (catch Throwable t (debug/frames t)))
;; => [{:fn my.app/inner :file "src/my/app.clj" :line 4 :locals {x 21 y 42}}
;;     {:fn my.app/outer ...} ...]
```

A throwable with no stack has no frames.

## Seeing every local

A frame holds every param and local in scope only when its code was compiled under `jolt.profile`'s debug mode. The mode is a compile-time switch, so turn it on and then load what you want to debug:

```clojure
(require '[jolt.profile :as prof])
(prof/instrument! :debug)
(require 'my.app :reload)
```

Or set `JOLT_PROFILE=debug` for a whole run. `(prof/instrument-mode)` says which mode is on.

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

`call-with-break-handler` is the same thing taking a function of no arguments, for when the body is already a function.
