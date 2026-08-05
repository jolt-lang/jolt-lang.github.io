`jolt.image` writes a running program's state to a file and reads it back in another process — later, on another machine, or on a different CPU architecture. An image written on an arm64 Mac restores on an x86-64 Linux box.

What travels is **state, not execution**: the values your vars hold, with cycles, shared structure, records and metadata intact. There are no thread stacks or suspended calls, and code never enters the image — a named function is written as its var's name and resolved on the way back in, so the process reading an image must be the same build of your application. Think Smalltalk image or Common Lisp `save-lisp-and-die`, scoped to data.

```clojure
(require '[jolt.image :as image])
```

## Saving the whole program

`dump-world!` walks the var table and writes every data var's root — nothing in your application has to enumerate what its state consists of. `restore-world!` rebinds those vars in the current process and returns how many.

```clojure
(image/dump-world! "app.jimg")              ; every application namespace
(image/dump-world! "app.jimg" ["app.core"]) ; or name them
(image/restore-world! "app.jimg")           ; => number of vars rebound
```

`clojure.*` and `jolt.*` vars are left alone — reader and printer settings belong to the process being restored into. `user` is kept: at a REPL, that is where your work lives.

Hooks bracket the cycle — quiesce in `before-dump`, rebuild what could not travel in `after-restore`:

```clojure
(image/add-before-dump-hook! stop-workers!)
(image/add-after-restore-hook! rebuild-derived-cells!)
```

## Saving one value

`dump!` writes a single value you name; `read-image` reads it back.

```clojure
(image/dump! "state.jimg" @state)
(reset! state (image/read-image "state.jimg"))
```

Hand it the value, not the container: `@state`, not the atom. An atom drags its watches along, and a watch is usually an anonymous function with no name to write.

## Post-mortem debugging

Because an image moves between machines, a crash site can dump its state for you to open later, anywhere, in a REPL:

```clojure
(try
  (process-batch! batch)
  (catch Exception e
    (jolt.image/dump! "crash.jimg"
                      {:error   (Throwable->map e)
                       :batch   batch
                       :pending @work-queue})
    (throw e)))
```

Copy `crash.jimg` to your machine and poke at it:

```
$ jolt repl
user=> (require '[jolt.image :as image])
user=> (def crash (image/read-image "crash.jimg"))
user=> (-> crash :error :cause)
"item 4182 has no :price"
user=> (filter #(nil? (:price %)) (:batch crash))
({:id 4182, :sku "B-77", ...})
```

The restored value is ordinary data — keywords look up, `=` holds, records are their types — so everything you normally do at a REPL applies. To capture the entire program instead of an enumerated payload, call `(image/dump-world! "crash.jimg")` in the catch and `restore-world!` at the REPL; your application's vars are then live in the REPL process for inspection.

## What can't be written

`dump!` refuses rather than write a subtly incomplete image, and names the path through your data to the offending object:

```clojure
(image/dump! "app.jimg" {:handlers {:go (fn [x] x)}})
;; ExceptionInfo: image: cannot write #<procedure> at :handlers -> :go
```

`scan` (and `scan-world`) answer the same question without writing anything — one `{:path :object}` map per refusal, an empty vector when the value is clean. `dumpable?` is the boolean form.

Refused, by design:

- **Anonymous functions.** A function travels as the name of its var, so a `defn` round-trips and stays callable; a bare `(fn [x] ...)` sitting in your state has no name to write. Store a named function, or the data you would rebuild one from.
- **Sorted maps and sorted sets.** Ordinary maps and sets are fine.
- **Open resources** — ports, threads — unless you register a handler that turns yours into plain data and back: `(image/register-handler! pred dump-fn restore-fn)`.

An image survives a change of machine and architecture, but not a Chez upgrade or an incompatible rebuild of your application — `read-image` and `restore-world!` check the header and refuse with the reason, rather than reading stale data.

Design detail is in [RFC 0009](/docs/rfc/0009-program-image-dump-restore.html).
