# RFCs

Design notes for non-obvious language and compiler decisions. An RFC records *why*
a thing is built the way it is; the code is the source of truth for *how*.

| # | Title | Status | Governs |
| --- | --- | --- | --- |
| [0001](/docs/rfc/0001-language-specification.html) | A Specification for the Clojure Language | Draft | The conformance target — what "is Clojure" means for jolt. |
| [0003](/docs/rfc/0003-transients.html) | Transients | Accepted | `transient`/`persistent!` semantics + the Chez mutable backing. |
| [0004](/docs/rfc/0004-type-hints.html) | Type hints + keyword-lookup specialization | Accepted | `^Type`/`^:struct` hints → the bare-`get` fast path. |
| [0005](/docs/rfc/0005-structural-type-inference.html) | Structural collection-type inference | Implemented | The `:struct`/`:vec`/`:set` lattice in `passes/types`. |
| [0006](/docs/rfc/0006-success-type-checking.html) | Success typing (provably-wrong-code detection) | Implemented | The error-domain checker in `passes/types`. |
| [0007](/docs/rfc/0007-compilation-modes-and-binary-output.html) | Compilation modes + binary output | Implemented | `release`/`--opt`/`--dev`, `--direct-link`, `--tree-shake`, numeric lowering, inlining. |
| [0008](/docs/rfc/0008-time-core-library-split.html) | Splitting time between core and the library | Implemented | The base `java.time` value types are in core; formatting and zones are jolt-lang/time. |
| [0009](/docs/rfc/0009-program-image-dump-restore.html) | Program image dump and restore | Implemented | `jolt.image` — writing program state to a file and restoring it on another machine or architecture. |
| [0010](/docs/rfc/0010-portable-scheme-layer.html) | The portable Scheme layer | Implemented | Contract + adapter isolating the host from Chez, with capability degradations — the boundary a second target (Guile, WASM) ports against. |
| [0011](/docs/rfc/0011-tail-calls-are-guaranteed.html) | Tail calls are guaranteed | Implemented | Proper tail calls for any tail-position call; `recur` stays as the JVM-compatible spelling. |
| [0012](/docs/rfc/0012-no-interpreter.html) | There is no interpreter | Implemented | Every entry point compiles to native code — `-e`, REPL, nREPL, `eval`, builds. |
| [0013](/docs/rfc/0013-numeric-tower.html) | The numeric tower | Implemented | Host-native integers/ratios with a JVM-shaped class surface; lowering opts out per-site. |
| [0014](/docs/rfc/0014-host-class-providers.html) | Host classes are provided by declaration | Draft | `:jolt/provides` — a library declares the host classes it supplies; core names no library. |
