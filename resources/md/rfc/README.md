# RFCs

Design notes for non-obvious language and compiler decisions. An RFC records *why*
a thing is built the way it is; the code is the source of truth for *how*.

| # | Title | Status | Governs |
| --- | --- | --- | --- |
| [0001](/docs/rfc/0001-language-specification.html) | A Specification for the Clojure Language | Draft | The conformance target — what "is Clojure" means for jolt. |
| [0002](/docs/rfc/0002-reader-conditional-features.html) | Reader-Conditional Feature Set | Accepted | `#?(...)` feature keys (`:jolt`, `:clj`, `:default`). |
| [0003](/docs/rfc/0003-transients.html) | Transients | Accepted | `transient`/`persistent!` semantics + the Chez mutable backing. |
| [0004](/docs/rfc/0004-type-hints.html) | Type hints + keyword-lookup specialization | Accepted | `^Type`/`^:struct` hints → the bare-`get` fast path. |
| [0005](/docs/rfc/0005-structural-type-inference.html) | Structural collection-type inference | Implemented | The `:struct`/`:vec`/`:set` lattice in `passes/types`. |
| [0006](/docs/rfc/0006-success-type-checking.html) | Success typing (provably-wrong-code detection) | Implemented | The error-domain checker in `passes/types`. |
| [0007](/docs/rfc/0007-compilation-modes-and-binary-output.html) | Compilation modes + binary output | Implemented (doc lags) | `release`/`--opt`/`--dev`, `--direct-link`, `--tree-shake`. |

RFC 0007's own status line still says "Draft, no code yet" — that is stale:
direct-linking and tree-shaking shipped (see [tools-deps.md](/docs/tools-deps.html) and
`backend_scheme.clj` / `build.ss`). Two compiler features that grew alongside it —
**IR inlining** (`passes/inline.clj`, under `--opt`) and **numeric `fl*`/`fx*`
lowering** from `^double`/`^long` hints (`passes/numeric.clj`) — are not yet written
up as RFCs; their touch points are in [MODULES.md](/docs/MODULES.html).
