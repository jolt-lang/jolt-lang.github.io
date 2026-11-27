`jolt.reload` reloads the namespaces you changed, plus every namespace that depends on them, in
dependency order. It is a port of Nikita Prokopov's [clj-reload](https://github.com/tonsky/clj-reload)
(MIT) and ships with jolt, so there is nothing to add to `deps.edn`.

```clojure
(require '[jolt.reload :as reload])

(reload/init {:dirs ["src" "dev" "test"]})
;; ... edit some files ...
(reload/reload)
;; Reloaded 3 namespaces in 21 ms
;; => {:unloaded [app.core app.db app.util], :loaded [app.util app.db app.core]}
```

Evaluating one form at a time stops working once state is involved. If you change `app.db`, every
namespace that required it still holds its old definitions. `reload` scans your source dirs and
builds the require graph from the `ns` forms without loading anything. It checks file modification
times, then unloads the changed namespaces and everything downstream of them, last dependent first.
After that it loads them back in topological order. Namespaces you didn't touch, and that don't
depend on what you touched, are left alone.

## `init`

```clojure
(init opts)
```

`init` sets what `reload` watches and records the current state of those files. Later calls to
`reload` compare against that state. The options:

| Option | Default | Meaning |
|---|---|---|
| `:dirs` | every source dir on the class path | Directories to scan, relative to the working directory. |
| `:files` | `.clj`, `.cljc` and `.jolt` files | A regex that file names must match to count as source. See below. |
| `:no-reload` | `#{}` | Namespaces never unloaded or reloaded, such as `user` or a namespace that owns a running server. |
| `:no-unload` | `#{}` | Namespaces that are reloaded on top of their current state but never unloaded first. Their `defonce`s survive. |
| `:unload-hook` | `'before-ns-unload` | A function with this name in a namespace is called just before that namespace is unloaded. `nil` turns hooks off. |
| `:reload-hook` | `'after-ns-reload` | A function with this name is called right after the namespace is loaded back. |
| `:output` | `:quieter` | `:verbose` prints each namespace as it unloads and loads, `:quieter` prints one summary line, and `:quiet` prints nothing. |

To reload other file types as well, pass your own pattern. Write out the extensions jolt reads too,
since your pattern replaces the default:

```clojure
(reload/init {:dirs ["src"] :files #".*\.(clj|cljc|jolt|repl)"})
```

Calling `init` again replaces the configuration and starts tracking from scratch.

### Without `init`

When `jolt.reload` loads, it initializes itself with every directory on the class path. That covers
your `deps.edn` `:paths` and the source dirs of any `:local/root` or git dependencies. So in a fresh
REPL, `(reload/reload)` works without any setup. To turn this off, set the environment variable
`JOLT_RELOAD_AUTO_INIT=false` or the system property `jolt.reload.auto-init=false`. clj-reload's
`CLJ_RELOAD_AUTO_INIT` and `clj-reload.auto-init` work too.

## `reload`

```clojure
(reload)
(reload opts)
```

`reload` unloads what changed, loads it back, and returns what it did:

```clojure
{:unloaded [<symbol> ...]    ; in the order they were unloaded
 :loaded   [<symbol> ...]}   ; in the order they were loaded
```

| Option | Default | Meaning |
|---|---|---|
| `:only` | `:changed` | What to reload; see below. |
| `:throw` | `true` | When `false`, a failed load is returned instead of thrown. |
| `:log-fn` | `println` | Receives each log line's parts in place of `println`. `nil` silences the log. |

`:only` chooses the namespaces:

- **`:changed`** (the default) reloads namespaces that are already loaded and whose files changed,
  plus their dependents.
- **`:loaded`** reloads every loaded namespace under `:dirs`, changed or not.
- **`:all`** loads every namespace found under `:dirs`, whether or not it was loaded before.
- **A regex** reloads what changed, then also loads every namespace whose name matches the regex,
  even if it was never loaded. `#".*-test"` is a quick way to pull in all your test namespaces.

### When a load fails

If a namespace fails to load, `reload` stops there and throws an `ex-info` with the cause attached:

```clojure
(ex-data e)
;; => {:unloaded [app.core app.db], :loaded [app.db], :failed app.core}
```

With `{:throw false}`, the same map comes back as the return value, with the exception under
`:exception`. Either way, nothing is lost. Fix the code and call `reload` again, and it picks up from
the failed namespace onward.

A changed file that doesn't parse makes `reload` throw before anything is unloaded, and the log
names the file. Fix the file and call `reload` again.

## `unload`

```clojure
(unload)
(unload opts)
```

`unload` does the first half of `reload`: it unloads everything that changed, along with the
namespaces that depend on it, and loads nothing back. It returns `{:unloaded [...]}`. The next
`reload` loads them. Use it to clear out broken code before you fix it.

## `find-namespaces`

```clojure
(find-namespaces)
(find-namespaces regex)
```

Returns the set of namespaces found under `:dirs`, or only those whose names match `regex`. It reads
the files without loading them.

```clojure
(reload/find-namespaces #".*-test")
;; => #{app.core-test app.db-test}
```

`classpath-dirs` returns the directories `init` would use when `:dirs` is not given.

## Keeping state across a reload

A reload removes the whole namespace and its vars with it. That is how stale definitions are
cleared, but it would also throw away a database connection or a cache. Some forms survive a
reload:

- **`defonce`** keeps its value automatically.
- **`def`, `defn`, `deftype`, `defrecord` and `defprotocol`** keep theirs when marked with
  `^:jolt.reload/keep`:

```clojure
(ns app.db)

(defonce conn (connect!))              ; kept, as always

^:jolt.reload/keep
(def cache (atom {}))                  ; kept

(def ^:jolt.reload/keep pool (pool!))  ; the marker can also go on the name

^:jolt.reload/keep
(defrecord Row [id data])              ; keeps its class, so old Rows still match
```

When the namespace loads back, each kept form is replaced with a binding to its previous value, and
everything else in the file is evaluated again. A kept protocol keeps its identity, so records and
types that extended it before the reload still satisfy it afterwards.

To keep other `def`-like forms, extend the `keep-methods` multimethod. It dispatches on the form's
head symbol. The simplest extension borrows an existing implementation:

```clojure
(defmethod reload/keep-methods 'defstate [_]
  (reload/keep-methods 'def))
```

A marked form with no `keep-methods` implementation makes `reload` throw.

## Namespace metadata

A namespace can opt out on its own instead of being listed in `init`:

```clojure
(ns ^:jolt.reload/no-reload app.system)   ; never unloaded or reloaded
(ns ^:jolt.reload/no-unload app.state)    ; reloaded on top of its current state
```

Code written for clj-reload loads unchanged. `:clj-reload/keep`, `:clj-reload/no-reload` and
`:clj-reload/no-unload` mean the same things as their `:jolt.reload/` spellings.

## Hooks

When a namespace holds a resource, such as a server, a window or a thread, unloading it leaves that
resource running with nothing pointing to it. Define an unload hook to shut it down:

```clojure
(ns app.server)

(defonce server (atom nil))

(defn start! [] (reset! server (http/start app {:port 8080})))

(defn before-ns-unload []
  (some-> @server .close))

(defn after-ns-reload []
  (start!))
```

`reload` calls `before-ns-unload` before unloading the namespace and `after-ns-reload` after loading
it back. Rename either hook, or turn it off with `nil`, through `init`'s `:unload-hook` and
`:reload-hook`. An exception in an unload hook is logged and the unload continues. An exception in a
reload hook counts as a failed load.

## Holding on to old vars

Because a reload replaces the vars themselves rather than only their values, anything that captured
a var or a function from a reloaded namespace still points at the old one. From the REPL or `user`,
look things up again after a reload instead of capturing them:

```clojure
(ns user
  (:require [jolt.reload :as reload]))   ; not app.core: user would keep the old one

(reload/init {:dirs ["src" "dev"] :no-reload '#{user}})

(defn reset []
  ((requiring-resolve 'app.system/stop!))
  (reload/reload)
  ((requiring-resolve 'app.system/start!)))
```

For the editor side of this workflow, see [REPL-Driven Development](/docs/repl-driven-development.html).
