`jolt.loader` gives a program **loading contexts**: a loader owns a list of source roots and a delegate, and the
namespaces, vars and resources it links. Two contexts can hold different versions of one library, a context can be
hermetic, and unloading one stops new loads through it without pulling definitions out from under code that already
resolved them. It is the tool for a plugin host, a per-tenant sandbox, or a test runner that wants a fresh copy of the code
under test.

It is shaped like `java.lang.ClassLoader` without being compatible with it: `find` locates, `resolve` reads what is
already linked, and `load` is the only call that reads and compiles.

```clojure
(require '[jolt.loader :as loader])
```

## A context over source roots

`classpath` builds a loader over `roots` (directories, jars, or [embedded roots](#embedded_roots)), searched in order. `:parent` is
its delegate, asked first; `:id` names it in `status` and in errors.

```clojure
;; plugins/greet/core.clj: (ns greet.core) (defn hello [] "hello from the plugin")
(def ctx (loader/classpath ["plugins"] {:parent (loader/isolated) :id "plugins"}))

(loader/load ctx {:kind :ns :name "greet.core"})
;; => {:handle greet.core, :vars {hello #'greet.core/hello}}

(@(loader/resolve ctx {:kind :var :name "greet.core/hello"}))   ; => "hello from the plugin"
```

Requests are maps with a `:kind` (`:ns`, `:var`, `:class` or `:resource`) and a string `:name`. `find` answers an ordered
vector of hits, `[]` on a miss, and throws on a denial; `resolve` answers what is already linked, or `nil`, with no I/O.

`eval-in` evaluates a string of forms in a namespace of the context. A `require`, `resolve` and the rest inside it go
through the context's roots and policies, not the process's global roots:

```clojure
(loader/eval-in ctx "plugin.user" "(require 'greet.core) (greet.core/hello)")
;; => "hello from the plugin"
```

That holds for the code's whole life: a function compiled in a context loads, requires and resolves through the context
that **defined** it, whoever calls it and on whatever thread. `load` and `load-file` are refused inside a context, which
reads namespaces through its loader and resources through `io/resource`.

`unload!` stops new loads through the loader and answers a report. Code that already resolved a var keeps it; a later
`find`, `resolve` or `load` throws `ex-info` with `:loader/unloaded`.

```clojure
(loader/unload! ctx)
;; => {:unloaded true, :already false, :released {:namespaces 1, :registrations 0},
;;     :evicted [], :in-flight 0, :raced false, :errors []}
```

`status` answers a diagnostics map (roots, loaded counts, in-flight loads, one level of delegate). Its keys are for
reading, not for deciding: use `unloaded?` for that.

## Delegates and policies

The delegate decides what a context shares with the rest of the process. A namespace served through the delegate is
shared by reference; one found on the context's own roots is private to it.

- `(root)`: the host itself as a loader, with the process's namespaces and roots.
- `(isolated)`: a delegate that answers nothing, for a hermetic context that sees only its own roots.
- `(delegating a b & more)`: try each in order. `(pool loaders)` is the same over a vector, for siblings sharing.
- `(allow l names)` / `(deny l names)`: `l` restricted to, or minus, a list of names. A name matches exactly or as a prefix at a
  `.` or `/` boundary. A denial throws `ex-info` carrying `:loader/denied` and never falls through to the context's own roots.
- `(self-first l prefixes)`: consult `l`'s own roots before its delegate for the names under `prefixes`, the usual
  parent-first order inverted for what a context means to shadow.
- `(->loader f)`: lift a plain `(fn [request] hits-or-nil)` into a delegate.

```clojure
(def ctx (loader/classpath ["plugins"]
                           {:parent (loader/deny (loader/root) ["jolt.process"])}))

(loader/eval-in ctx "plugin.user" "(require '[clojure.string :as str]) (str/upper-case \"ok\")")
;; => "OK", clojure.string is the host's, shared

(loader/eval-in ctx "plugin.user" "(require 'jolt.process)")
;; throws: loader root denied :ns jolt.process
```

`(with-loader l body…)` makes `l` the **ambient** loader for `body`, the one resource lookups follow; `current-loader`
reads it. It conveys like a dynamic binding, into futures, agents, go blocks and fibers.

```clojure
(loader/with-loader ctx
  (slurp (clojure.java.io/resource "greet/msg.txt")))   ; read from plugins/
```

`as-classloader` answers a host view of a loader with `ClassLoader`'s resource methods, for code that asks a class loader
for resources.

## Embedded roots

A root spelled `"embed:<prefix>"` names a prefix in the resources a binary carries (what deps.edn
`:jolt/build {:embed [dirs]}` bakes in) instead of a directory, so a built binary can run a library it ships with no files on
disk. `embedded-root?` tests the spelling, and its presence is the feature probe.

## Compiled namespaces are cached

A namespace a context reads from its own roots is compiled once and the compiled form goes to the AOT cache, the same cache
the host's own namespaces use. A second context over the same roots, a `:reload` of unchanged source, and the next process
load the cached artifact instead of compiling again: on a tree of 20 small files that is about 145 ms per fresh context instead
of about 4.4 s.

Each context still gets its own var cells. A cached artifact links its vars by name as it loads, after the context's own
dependencies are in place, so it links that context's cells exactly as compiling there would. Code from the artifact also
keeps its defining context: the context is captured with [`jolt.host/load-time-value`](/docs/host-interop.html#load-time_values) as the
artifact loads, rather than baked into the compiled code.

The cache key covers the source and the name it is read under, every namespace the compile loaded (transitively), the files
it read (a macro that `slurp`s a file at compile time), and the data readers. Editing any of them recompiles the namespaces
that depend on it. `JOLT_AOT_CACHE=0` turns the cache off, for contexts and the host alike.

## Shadowing host namespaces

The process has one namespace registry, so a context's private copy of a namespace takes the name. When the host had already
loaded a namespace under that name, loading the context's copy **evicts** the host's: live host code keeps the vars it
resolved, but the name is gone from the host's view, and a plain `require` will not bring it back.

`evicted` names the host namespaces a loader evicted, sorted; `status` and `unload!` report them as `:evicted`.
`restore-evicted!` reloads exactly that set from the host's roots and answers `{:restored [...] :skipped [...]}`. A name a
context still owns is skipped and stays recorded, since restoring it would evict that context's copy in turn, so the usual
order is `unload!` and then restore.

```clojure
(ns app.main
  (:require [jolt.loader :as loader]
            [greet.core]))                ; the host's greet.core, from src/

(defn -main []
  (let [ctx (loader/classpath ["plugins"] {:parent (loader/isolated)})]
    (loader/load ctx {:kind :ns :name "greet.core"})   ; the plugin's copy
    (loader/evicted ctx)                  ; => ["greet.core"]
    (:evicted (loader/unload! ctx))       ; => ["greet.core"]
    (loader/restore-evicted! ctx)         ; => {:restored ["greet.core"], :skipped []}
    ((requiring-resolve 'greet.core/hello))))   ; => "hello from the host"
```

## Limits

- Data readers are the host's. The reader resolves a `#tag` before the loader sees the form, so a context cannot bring its own
  `data_readers.clj`; a source using a tag the host does not know fails with the tag named.
- `:class` requests have no backend yet.
- Every loader ever built keeps a small entry so code that still carries its id fails with `:loader/unloaded` rather than a
  confusing error. `unload!` shrinks the entry to a stand-in and lets the loader's graph be collected, but a process that makes
  a context per request accumulates the stand-ins.
