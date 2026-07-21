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
