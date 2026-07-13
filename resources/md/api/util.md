`jolt.util` collects small general-purpose helpers. The main one is
`import-vars`, for putting a public face on another namespace — the tool for
wrapping a vendored library.

## `import-vars`

```clojure
(require '[jolt.util :refer [import-vars]])
(import-vars from-ns & {:keys [exclude]})
```

Re-export the public vars of `from-ns` into the current namespace as thin
delegating definitions. A **function** becomes a fn that applies the source var;
a **macro** becomes a macro that expands to a call of the source macro. Pass
`:exclude` a set of symbols to leave out.

`from-ns` must already be required.

```clojure
(ns my.fs
  (:require [babashka.fs]
            [jolt.util :refer [import-vars]]))

;; expose all of babashka.fs except the java.util.zip functions
(import-vars babashka.fs :exclude #{zip unzip gzip gunzip})

(my.fs/exists? "deps.edn")     ; => true  (delegates to babashka.fs/exists?)
```

## Why not `intern`?

The obvious way to mirror a namespace is to walk `ns-publics` at load time and
`intern` each var:

```clojure
(doseq [[sym v] (ns-publics 'babashka.fs)]     ; DON'T — breaks in a built binary
  (intern *ns* sym @v))
```

That works in the REPL but **cannot be reproduced by an AOT build**: the
`intern`s run as a runtime side effect over reflective state, so `jolt build`
emits nothing for them and the wrapper's vars are unbound in the binary.
`import-vars` instead emits static `def`/`defmacro` forms that resolve the
source at call time — they bake into a binary and do not depend on load order.

This is how [`jolt.fs`](/docs/api/fs.html) wraps the vendored babashka.fs, and
it is the pattern to use when you vendor a library and want to present it under
your own namespace.
