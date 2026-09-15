# Embedding SCI

[SCI](https://github.com/babashka/sci) — the Small Clojure Interpreter behind
babashka — runs on Jolt as an ordinary dependency, and a Jolt program can use
it the way a JVM program does: to evaluate user-supplied code, plugins or
extensions in a sandboxed context. This page covers the one place where an
embedder has to know something Jolt-specific: **sharing a host protocol** with
the code SCI evaluates. Everything else — `sci/init`, `sci/eval-string*`,
`:classes`, `:namespaces`, `copy-var*` of functions and values — works as
documented by SCI.

```clojure
{:deps {org.babashka/sci {:mvn/version "0.13.53"}}}
```

## Sharing functions and values

`sci/copy-var*` and `:namespaces` work as on the JVM:

```clojure
(require '[sci.core :as sci])

(def my-ns (sci/create-ns 'my.api))
(def ctx (sci/init {:namespaces {'my.api {'greet (sci/copy-var* #'greet my-ns)}}}))
(sci/eval-string* ctx "(my.api/greet \"world\")")
```

## Sharing a protocol

SCI has one model of a protocol: a map `{:methods #{…} :ns <sci namespace>}`
whose methods are **multimethods dispatching on `sci.impl.types/type-impl`**.
That is what `defrecord`, `deftype`, `extend-type` and `extend-protocol`
evaluated inside SCI register into — one `defmethod` per method, keyed by the
SCI type. A host protocol var copied in as-is (`sci/copy-var*` on `#'P`) is not
that shape on any host: Jolt's protocol value has no `:ns` and its methods are
plain dispatch functions, and the JVM's has no `:ns` either. SCI cannot name
the methods it generates, and a record implementing the protocol fails in
analysis (`Unable to resolve symbol: m`). On babashka it *appears* to work only
because babashka's own `defprotocol` is SCI's.

The supported way to share a host protocol is the one babashka itself uses for
`clojure.core.protocols` (`babashka.impl.protocols`): one multimethod per
method whose `:default` answers through the host protocol, a SCI-side protocol
map naming them, and — for the other direction — the host protocol extended to
SCI's record and type classes, routing back through the multimethods. It is
host-agnostic: the same code runs on Jolt and on the JVM.

```clojure
(ns my.plugins
  (:require [sci.core :as sci]
            [sci.impl.types :as types]))

;; the host protocol
(defprotocol Shape
  (area [this])
  (scaled [this k]))

;; SCI-side methods: dispatch on SCI's notion of a value's type. A value SCI
;; did not build — a host record, a string — falls to the host protocol.
(defmulti sci-area types/type-impl)
(defmulti sci-scaled types/type-impl)
(defmethod sci-area :default [x] (area x))
(defmethod sci-scaled :default [x k] (scaled x k))

;; the other direction: a SCI record or type reaching the HOST protocol answers
;; through the method its defrecord/deftype registered. Only a method the type
;; registered counts — the :default is the host protocol itself, and answering
;; through it here would loop.
(defn- sci-method [mm this]
  (let [f (get-method mm (types/type-impl this))]
    (when-not (identical? f (get-method mm :default)) f)))
(defn- via-sci [mm]
  (fn [this & args]
    (if-let [f (sci-method mm this)]
      (apply f this args)
      (throw (IllegalArgumentException.
              (str "No implementation of method: " mm " for SCI type: "
                   (types/type-impl this)))))))
(doseq [c [sci.impl.records.SciRecord sci.impl.deftype.SciType]]
  (extend c Shape {:area (via-sci sci-area) :scaled (via-sci sci-scaled)}))

;; the SCI namespace: the protocol map plus its methods
(def shapes-ns (sci/create-ns 'shapes))
(def ctx
  (sci/init {:namespaces
             {'shapes {'Shape (sci/new-var 'shapes/Shape
                                           {:methods #{sci-area sci-scaled}
                                            :ns shapes-ns
                                            :name 'shapes/Shape
                                            :protocol Shape}
                                           {:ns shapes-ns})
                       'area (sci/copy-var* #'sci-area shapes-ns)
                       'scaled (sci/copy-var* #'sci-scaled shapes-ns)}}}))
```

With that in place, code evaluated in the context implements the protocol the
usual ways, and values it builds answer the host protocol when they come back:

```clojure
(sci/eval-string* ctx
  "(defrecord Sq [s] shapes/Shape (area [_] (* s s)) (scaled [_ k] (->Sq (* s k))))
   (deftype Rect [w h] shapes/Shape (area [_] (* w h)) (scaled [_ k] (->Rect (* w k) (* h k))))
   (extend-type String shapes/Shape (area [s] (count s)) (scaled [s k] (apply str (repeat k s))))
   (shapes/area (->Sq 3))")                                   ; => 9

(area (sci/eval-string* ctx "(->Rect 2 5)"))                   ; => 10, from the host side
(satisfies? Shape (sci/eval-string* ctx "(->Sq 1)"))           ; => true
```

Inside SCI, `satisfies?`, `extends?` and `instance?` on `shapes/Shape` read
the `:methods` set, so they answer for SCI records, types and `extend-type`
targets alike. A SCI record whose type does not implement the protocol is
refused by the host protocol with `IllegalArgumentException`, the same
exception the reference raises for a missing implementation.

`extend-type` on a host class *inside* SCI (`String` above) registers into the
SCI multimethod only; the host protocol called from host code on a string does
not see it. That is how babashka behaves too, and it keeps a sandboxed context
from extending the host's protocols behind its back.

This recipe is pinned by Jolt's `scifunctional` gate
(`test/chez/sci-functional-test.clj`), which runs it end to end on every
build.
