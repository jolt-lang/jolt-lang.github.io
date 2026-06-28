Jolt is built for the Clojure workflow: keep a process running, connect your editor to it, and grow the program by evaluating one form at a time. Because the REPL and the nREPL server run in **dev mode** — every call derefs its var, so a redefinition takes effect on the next call — you can redefine a function or restart a component without bouncing the process.

## The line REPL

`bin/joltc repl` starts a plain REPL in the current directory. It resolves the `deps.edn` first, so your project's source roots and native libraries are already on the load path:

```bash
$ bin/joltc repl
user=> (require '[myapp.core :as app])
nil
user=> (app/greeting "world")
"Hello, world"
```

This is handy for a quick poke at a namespace, but the real workflow is driving the same kind of session from your editor.

## Starting an nREPL server

`bin/joltc --nrepl-server [port]` starts an [nREPL](https://nrepl.org/) server your editor connects to. It defaults to port 7888 (override with the argument or `JOLT_NREPL_PORT`), resolves the project's `deps.edn`, loads the source roots and native libraries, and writes a `.nrepl-port` file in the project directory so editors auto-detect the port.

```bash
$ cd myapp
$ bin/joltc --nrepl-server
nREPL server started on port 7888 (127.0.0.1) — .nrepl-port written
;; connect your editor; ^C to stop
```

Leave it running. Everything you do from here on happens in your editor, against this live process.

### Connecting your editor

The server speaks bencode over a loopback TCP socket and writes `.nrepl-port`, so the usual Clojure tooling connects with no extra configuration:

- **CIDER** (Emacs) — `cider-connect` to `localhost:7888`, or `cider-connect-clj` and let it read `.nrepl-port`.
- **Calva** (VS Code) — *Connect to a running REPL in your project*, pick *Generic* / *deps.edn*; it reads `.nrepl-port` automatically.
- **Cursive** (IntelliJ) — a *Remote* nREPL run configuration pointing at the port.

The built-in handler implements `clone`, `describe`, `eval`, `load-file`, and `close` — enough to connect and evaluate. Heavier features (sessions, interruptible eval, completion, lookup) are added as nREPL middleware; see [Middleware](#middleware) below.

## The develop loop

Once connected, you edit a namespace and evaluate forms straight from the buffer. Say `myapp/core.clj` holds:

```clojure
(ns myapp.core)

(defn greeting [name]
  (str "Hello, " name))

(defn -main [& _]
  (println (greeting "world")))
```

Load the file (CIDER `C-c C-k`, Calva *Load Current File*), then evaluate a call in the buffer:

```clojure
(greeting "Jolt")   ;; => "Hello, Jolt"
```

Now change `greeting` to `(str "Hey, " name "!")` and re-evaluate that one form (CIDER `C-c C-c`, Calva *Evaluate Top Level Form*). The var is redefined in place — the next call sees the new definition, with no restart:

```clojure
(greeting "Jolt")   ;; => "Hey, Jolt!"
```

You never leave the running process. New `require`s work the same way — evaluate `(require '[clojure.string :as str])` and the namespace loads off the source roots on the spot.

## Running an app from the REPL

For a long-running app — a server, a worker, anything with state — keep the live bits in a var you can stop and restart, and drive its lifecycle from the REPL instead of from `-main`. A small `start!`/`stop!` pair over an atom is enough:

```clojure
(ns myapp.core)

(defonce system (atom nil))

(defn handler [req]
  {:status 200 :body "ok"})

(defn start! []
  (reset! system (run-server #'handler {:port 3000}))   ;; your server of choice
  :started)

(defn stop! []
  (when-let [s @system] (.close s))
  (reset! system nil)
  :stopped)
```

From the editor's REPL:

```clojure
(require '[myapp.core :as app])
(app/start!)        ;; bring the system up
;; ... edit handler, re-evaluate the defn ...
;; because handler is passed as #'handler, the running server
;; picks up the new definition with no restart
(app/stop!)         ;; tear it down when you're done
```

`defonce` keeps the atom from being clobbered when you reload the file, and passing the handler as a var (`#'handler`) means redefining it is picked up live. When you change something structural — the server config, the lifecycle itself — call `(app/stop!)` then `(app/start!)` to cycle it.

`-main` stays the production entry point: it just calls `start!` and blocks. In development you skip it and steer the system by hand.

## Reloading a whole file

To reload a file from disk rather than evaluating form by form, use the nREPL `load-file` op — `C-c C-k` in CIDER, *Load Current File* in Calva. It re-reads the file in its namespace, so every changed definition lands at once. This is the usual move after a batch of edits.

## Middleware

The core server is intentionally small. A library can add the heavier nREPL ops as middleware — a `(fn [handler] (fn [request] ...))` — listed in `deps.edn`:

```clojure
{:paths ["src"]
 :nrepl/middleware [my.nrepl/wrap-completion
                    my.nrepl/wrap-interrupt]}
```

Jolt composes the listed middleware over the built-in handler when the server starts, so completion, interruptible eval, and lookup are opt-in per project rather than baked into every server.

## Dev mode vs a compiled binary

The REPL and nREPL server are dynamic on purpose: calls go through the var, so redefinition works. A `bin/joltc build` binary is the other end of that trade — a `--direct-link` build binds calls directly and gives up runtime redefinition for speed. Develop against the REPL; ship the binary. See [Getting Started](/docs/getting-started.html#compiling_a_standalone_binary) for the build modes.
