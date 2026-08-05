`jolt.image` writes a running program's state to a file and reads it back in another process — later, on another machine, or on a different CPU architecture. An image written on an arm64 Mac restores on an x86-64 Linux box.

What travels is **state, not execution**: the values your vars hold, with cycles, shared structure, records and metadata intact — including functions. A named function is written as its var's name and resolves to the live one; an anonymous closure travels as its **source form plus captured values** and is compiled back on restore, captured environment intact. Sorted maps and sets travel with their comparators. There are no thread stacks or suspended calls, and the process reading an image must be the same build of your application. Think Smalltalk image or Common Lisp `save-lisp-and-die`, scoped to state.

Because restore compiles the fn sources an image carries, **treat an image like code**: load ones you trust, the way you would load a source file.

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

Hand it the value, not the container: `@state`, not the atom. An atom drags its watches and validator along — usually UI wiring you want rebuilt fresh, not carried.

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

## Resources and stubs

An open resource — a port, a thread — cannot travel as itself. Three ways to handle one:

- **A handler** (full fidelity, both directions known up front): `(image/register-handler! pred dump-fn restore-fn)` turns yours into plain data and back.
- **Stubs**: `dump-world!` dumps unhandled resources as placeholder records **by default** — a whole-program capture shouldn't die on a logger's file port — and reports what it stubbed; `dump!` does so with `{:unwritable :stub}`. A stub carries its kind, a description, and the route to it (file ports record direction and path). On restore, a registered resolver `(image/register-stub-resolver! kind-or-pred f)` replaces matching stubs with live values; the rest come back inert, printing as `#image/stub{...}`. After a world restore, `(image/stubs)` lists them with their owning vars and `(image/resolve-stub! id value)` swaps a live value in — reopen the log, reattach the connection, from the REPL.
- **After-restore hooks** for anything derived: rebuild what could not travel.

## What still refuses

`dump!` (strict by default) refuses rather than write a subtly incomplete image, naming the path through your data to each offending object; `scan` answers without writing — one `{:path :object :disposition}` map per finding, where `:would-stub` marks what stub mode would carry.

- **A closure over compile-time constants.** `(let [a 5] (fn [x] (+ a x)))` folds `a` into the compiled code, so its value cannot be recovered from the live closure while the stored source still needs it. The message names the capture. Closures over runtime-computed values travel fine.
- **Closures made by `partial`, `comp`, `memoize` and friends** — their literals live in `clojure.core`, which is not source-registered. Store what you composed instead.
- **Restoring closures needs the compiler**: a tree-shaken build that dropped it refuses closure-bearing images up front.

An image survives a change of machine and architecture, but not a Chez upgrade or an incompatible rebuild of your application — `read-image` and `restore-world!` check the header and refuse with the reason, rather than reading stale data.

Design detail is in [RFC 0009](/docs/rfc/0009-program-image-dump-restore.html).
