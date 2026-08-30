# RFC 0009 — Program image dump and restore

- **Status**: Implemented (v2)
- **Champions**: jolt maintainers

## Summary

`jolt.image` writes a program's state to a file and reads it back in another
process — on another machine, and on another CPU architecture.

There are two ways in. `dump-world!` saves the *program*: it walks the var table
and writes every var's root, so nothing in an application has to list what its
state consists of. That is the Smalltalk image / Common Lisp `save-lisp-and-die`
shape, and it is what you usually want. `dump!` saves a single value you name,
for when you want a file with exactly one thing in it.

What travels is **state, not execution**. What does not travel is in-flight
computation: no thread stacks, no continuations, no suspended calls. This is a
state image, not a process image, and the distinction is load-bearing — it is
what makes the feature implementable on Chez at all, and what users have to
expect.

## API

```clojure
(require '[jolt.image :as image])

;; the whole program
(image/dump-world! "app.jimg")             ; every var root, every namespace
(image/dump-world! "app.jimg" ["app.core"]) ; or name the namespaces
(image/restore-world! "app.jimg")           ; => number of vars rebound

;; or a single value
(image/dump! "app.jimg" @state)
(reset! state (image/read-image "app.jimg"))
```

The two are not interchangeable: `restore-world!` refuses a value image and
`read-image` refuses a world image, each saying so.

`dump!` fails rather than writing a subtly incomplete image, and names the route
through the object graph to whatever it choked on; `{:unwritable :stub}` dumps
such objects as resolvable placeholders instead (see Stubs).

`scan` answers the same question without writing anything, which is the better
habit — it returns one `{:path :object}` map per offending value, and an empty
vector when the value is writable:

```clojure
(image/scan @state)      ; => []
(image/dumpable? @state) ; => true
```

An open resource can be taught to the encoder. `dump-fn` turns it into plain
data, `restore-fn` turns that data back into a live object and should throw if
the data is not its own:

```clojure
(image/register-handler! pred dump-fn restore-fn)
```

Hooks bracket a world dump and restore — the same pair as Common Lisp's
`*save-hooks*` and `*init-hooks*`. `before-dump` is where an application
quiesces; `after-restore` is where it rebuilds what could not travel.

```clojure
(image/add-before-dump-hook! stop-workers!)
(image/add-after-restore-hook! rebuild-derived-cells!)
```

`(image/scan-world)` is `scan` for the world, and `(image/runtime-version)`
reports the runtime an image is pinned to.

## How it works

### The body is pure data

Code never enters an image as compiled code. A function that is some var's
root is written as a reference — the var's `"ns/name"`, resolved on the way
back in, so the restored function IS the live one. An anonymous closure
travels as its **source**: the compiler gives every function literal a stable
name and records its `fn*` form, defining namespace, and free-variable names;
the dump recovers the captured values from the live closure, and restore
compiles `(fn* [free-names…] form)` back in that namespace and applies it to
the restored values. Shared captures restore to one object, and a closure
that captures the atom containing it comes back still holding itself.

`clojure.core`'s own literals are recorded the same way, so the closures
`partial`, `comp`, `memoize`, `juxt` and friends return travel like any other.
Multimethods, `reify` instances and namespaces travel as their **name** and come
back as the live object, not a copy — they are code, or interned, and the
restoring build already has them.

Lazy sequences travel **unforced**. A core producer records what it is — the
producer and its arguments — rather than closing over them, so restore
re-applies it and what comes back is still lazy: an infinite sequence keeps
generating, and a side effect that had not run still has not. Forcing at dump
would do neither.

Because restore evaluates fn sources, **an image is code**: load images with
exactly the trust you would give a source file. (An image already rebinds
your vars, so this was never a data-only trust boundary.)

Because the body contains no code objects, it serializes as a
machine-independent Chez fasl stream. That is precisely what makes
cross-architecture restore work: an image written on arm64 is not stamped with
an architecture, so x86-64 can read it.

Chez's fasl handles the value core — cyclic and shared structure with identity
preserved, records, and every numeric type. jolt writes only a descriptor table
covering the objects Chez refuses: procedures, non-`eq` hashtables, and host
resources.

Records are matched by type on the way back in, so a record written by one build
is read as the same type by another only when that type's definition has not
moved underneath it. Changing a record's fields is a format change, and the
header check below is what turns that into a refusal instead of a bad read.

### Interned values

Two things cannot simply be copied, and both are silent failures rather than
errors if you get them wrong.

**Keywords are interned**, and jolt's map lookup compares them by identity. A
copied keyword is a key nothing can find: the restored map prints and counts
correctly while every `(:k m)` returns `nil` and `=` is `false`. Keywords are
therefore re-interned on the way in. Their cached hash is content-derived, so an
re-interned keyword hashes the same and the restored map's internal structure
stays valid. Symbols need none of this — they are not interned and compare by
namespace and name.

**Metadata** lives in a weak side table keyed by object identity, so it cannot
ride on the object. It travels in the same stream as an association list; because
sharing is preserved within one stream, those objects come back identical to the
ones in the graph and their metadata is re-attached.

### Saving the world

`dump-world!` walks the var table and writes every var's root. Two things make
that affordable on a runtime with no heap dump.

**Code does not travel.** A var whose root is a function is skipped outright: the
process reading the image is the same build, so it already has every `defn`,
protocol impl and multimethod before the image is opened. Only data moves. This
is also why an image is pinned to its build.

**The language's own namespaces are left alone.** `clojure.*` and `jolt.*` are
skipped, because their vars — `*ns*`, printer and reader settings — belong to the
process being restored *into*. Carrying them across would quietly reconfigure the
reader and printer of the program you just restored. `user` is deliberately kept:
at a REPL that is where the work lives, and an image that dropped it would lose
exactly what you typed.

### Teaching it about your own types

A handler is claimed at the **var root** and its payload is substituted before
the write, which is what lets the payload be ordinary application state — a map
holding functions and keywords behaves there exactly as it would anywhere else in
your program.

This is how a UI toolkit's reactive cells travel. A cell typically holds watch
closures and, for a derived cell, its body function; none of those have names to
write. So the handler writes the cell as its current value, and an after-restore
hook re-derives the live graph from the restored root:

```clojure
(image/register-handler! reactive-cell?
                         (fn [c] {:kind (:kind c) :value @c})
                         (fn [d] (make-cell (:value d))))
(image/add-after-restore-hook! rebuild-derived-cells!)
```

### What to hand `dump!`

Hand it the value, not the container. Dumping an atom drags in whatever watches
are attached to it; those travel as source like any other function, but they are
rarely what you meant to save. Dumping `@atom` writes the data and nothing else.

The same reasoning covers the rest: dump what your state *is*, and let the things
derived from it be derived again on the other side. An application whose cursors
and reactions hang off one root atom only has to restore that root; everything
downstream recomputes.

### Compatibility

The header records the jolt version, the image format version, the Chez fasl
version, the application build hash, and the namespaces that were loaded.
Restore refuses on a mismatch and names what differs, rather than failing
obscurely later or — worse — succeeding against stale definitions.

Architecture is recorded but not enforced; differing is the point. A **Chez
upgrade does invalidate existing images**, because the fasl format version moves
with it.

A function whose var does not exist in the restoring build is an error, never a
silent misbinding.

### Sorted collections

A sorted map or set travels as its kind, its ORIGINAL comparator, and its
entries in order; restore rebuilds through the public constructors. A named
comparator rides as a var reference, an anonymous one as source — the same
machinery as any other function.

### Stubs

An open resource with neither a handler nor any encoding — a port, a thread —
can dump as a **stub**: a placeholder recording its kind, a description, and
the route to it. `dump-world!` stubs by default (a whole-program capture
should not die on a logger's file port) and reports what it stubbed;
`dump!` requires `{:unwritable :stub}`. Registered **describers** add
per-kind detail on the way out; registered **resolvers** replace matching
stubs with live values during restore; whatever neither claims comes back as
an inert value that prints as `#image/stub{...}`. After a world restore,
`(image/stubs)` lists the unresolved ones with their owning vars and
`(image/resolve-stub! id value)` swaps a live value in.

## Limits

- **State, not execution.** Nothing suspended mid-call is preserved.
- **A closure over compile-time constants refuses to dump.** Constant folding
  bakes such captures into the compiled code, so their values cannot be
  recovered from the live closure while the stored source still needs them.
  The error names the capture. Closures over runtime-computed values travel.
- **Execution does not travel.** A future that has not completed refuses — its
  thread is not in the image, so a restored one would never finish — and so
  does a transient, which belongs to the thread that made it. `deref` the
  future and `persistent!` the transient first. An agent's state travels while
  its pending queue does not.
- **A function the runtime built rather than analyzed refuses.** There is no
  source form to rebuild it from. Functions you wrote travel, `clojure.core`'s
  included.
- **Restoring closures needs the compiler.** A tree-shaken build that dropped
  it refuses a closure-bearing image up front, by name.
- **Images do not survive a Chez upgrade.** They do survive a change of
  machine and architecture.
