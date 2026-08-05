# RFC 0009 — Program image dump and restore

- **Status**: Implemented (v1)
- **Champions**: jolt maintainers

## Summary

`jolt.image` writes a program's state to a file and reads it back in another
process — on another machine, and on another CPU architecture.

What travels is **state, not execution**. You hand `dump!` the value that is your
state, and you get that value back. What does not travel is in-flight
computation: no thread stacks, no continuations, no suspended calls. This is a
state image, not a process image, and the distinction is load-bearing — it is
what makes the feature implementable on Chez at all, and what users have to
expect.

In practice an application keeps its state in one atom, so an image is that
atom's value, and restoring it is a `reset!`.

## API

```clojure
(require '[jolt.image :as image])

(image/dump! "app.jimg" @state)              ; write
(reset! state (image/read-image "app.jimg")) ; read back
```

`dump!` fails rather than writing a subtly incomplete image, and names the route
through the object graph to whatever it choked on:

```clojure
(image/dump! "app.jimg" {:handlers {:go (fn [x] x)}})
;; ExceptionInfo: image: cannot write #<procedure> at :handlers -> :go
```

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

`(image/runtime-version)` reports the runtime an image is pinned to.

## How it works

### The body is pure data

Code never enters an image as code. A function is written as a reference: if it
is some var's root, the image records that var's `"ns/name"` and resolves it
through the var table on the way back in, so the restored function is the live
one and stays callable. An anonymous closure has no name to record, so it is
refused rather than guessed at — see Limits.

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

### What to hand it

Hand it the value, not the container. Dumping an atom drags in whatever watches
are attached to it, and a watch is usually an anonymous function with no name to
write — so `dump!` refuses. Dumping `@atom` writes the data and nothing else.

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

## Limits

- **State, not execution.** Nothing suspended mid-call is preserved.
- **Anonymous closures cannot be written.** A function travels as the name of the
  var it is bound to, so a named `defn` round-trips and stays callable while a
  bare `(fn [x] ...)` sitting in your state is refused. Store a named function,
  or the data you would rebuild one from. Giving closures stable identities needs
  the compiler to assign every function literal a durable id; that is future work.
- **Sorted maps and sorted sets cannot be written.** Their comparator machinery
  is not encodable in this format version. Ordinary maps and sets are fine.
- **Images do not survive a Chez upgrade.** They do survive a change of machine
  and architecture.
- **Unwritable objects fail the dump**, by default and on purpose. `scan` finds
  them first.
