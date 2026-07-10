`jolt.parser` is a monadic parser-combinator library — the same machinery `jolt.infix` uses to parse expression strings. A **parser** is an ordinary function from input to a seq of `[value remaining]` results; combinators build bigger parsers out of smaller ones; `do*` threads them together monadically. It is adapted from [rm-hull/jasentaa](https://github.com/rm-hull/jasentaa) (MIT).

The library is split across four namespaces:

- `jolt.parser` — the top-level driver: `parse-all` runs a parser and throws on leftover input.
- `jolt.parser.monad` — the monadic core (`do*`, `>>=`, `return`, `failure`).
- `jolt.parser.basic` — primitive single-character parsers.
- `jolt.parser.combinators` — sequencing, repetition, and choice combinators.
- `jolt.parser.position` — source-location tracking and parse-error reporting.

```clojure
(require '[jolt.parser :refer [parse-all]]
         '[jolt.parser.basic :as b]
         '[jolt.parser.combinators :as c])

;; a comma-separated list of digits, parsed to completion
(parse-all (c/sep-by (c/plus c/digit) (b/match ",")) "1,22,333")
; => [[\1] [\2 \2] [\3 \3 \3]]
```

## The model

A parser `p` is a function `(p input) -> seq of [value remaining]`. A success produces at least one `[value remaining]` pair; a failure produces an empty seq (`'()`). Combinators are higher-order functions that take parsers and return parsers. `do*` is the monadic bind that sequences them, binding each step's result to a name:

```clojure
(m/do*
 (a <- (b/match "a"))
 (b <- (b/match "b"))
 (m/return [a b]))              ; parses "ab", yields [\a \b]
```

A bare form with no `<-` (like `spaces`) is sequenced for its effect and its result discarded. `m/return` lifts a plain value into a parser that always succeeds and consumes nothing.

## Running a parser

`jolt.parser` exposes the driver that turns a parser into a result or an error:

- `parse-all` `parser input` — run `parser` over `input`, requiring the whole input to be consumed. Returns the parsed value on success; throws an `ex-info` (carrying `:offset`/`:line`/`:col`) on a parse failure or leftover input.
- `apply` `parser input` — lower-level; runs `parser` after skipping leading space and returns the raw seq of `[value remaining]` results (does not throw).

`parse-all` is what you want at the boundary of a parser; `apply` is for building combinators.

## Primitive character parsers — `jolt.parser.basic`

- `any` — consume one character; fails at end of input.
- `eof` — succeed (consuming nothing) only at end of input; the dual of `any`.
- `match` — recognize a specific single character (a parser, not a function: `(match \a)`). Give it a one-char string.
- `none-of` — recognize any single character *except* the given one.
- `sat` `pred` — satisfy a predicate over the next character.
- `from-re` `re` — match a single character against a regex.
- `fwd` `p` — delay a forward-declared parser (for mutually recursive grammars).

## Combinators — `jolt.parser.combinators`

**Sequencing and choice**

- `and-then` `p1 p2` — `p1` then `p2`, joining the results.
- `or-else` `p1 p2` — non-deterministic choice: apply both, concatenate results.
- `choice` `p1 p2` — deterministic choice: at most one result (the `+++` operator).
- `any-of` `& ps` — reduce a list of parsers with `or-else`.

**Repetition**

- `many` `p` — zero or more `p`.
- `plus` `p` — one or more `p`.
- `optional` `([p] [p default])` — zero or one `p`; `default` (else `nil`) is the result when `p` does not match.

**Tokens**

- `string` `s` — match the literal string `s`.
- `token` `p` — parse `p`, then consume any trailing space.
- `symb` — match a symbolic token (a string), then trailing space (comp of `token` and `string`).
- `space` — one space, tab, newline, or carriage-return.
- `spaces` — zero or more whitespace characters.

**Repetition with separators / wrappers**

- `separated-by` `p sep` — one or more `p` separated by `sep` (sep's results discarded).
- `sep-by` `p sep` — zero or more `p` separated by `sep`; `nil` when none match.
- `between` `open close p` — `p` wrapped by `open` and `close` (both discarded).

**Left/right associative folding**

- `chain-left` `([p op] [p op a])` — fold repeated `p`/`op` left-associatively.
- `chain-right` `([p op] [p op a])` — fold right-associatively.

**Character classes** (single-character parsers)

- `digit` — one decimal digit `0-9`.
- `letter` — one ASCII letter `a-z`/`A-Z`.
- `alpha-num` — one letter or digit.

## The monadic core — `jolt.parser.monad`

- `do*` `& forms` — the monadic sequencing macro. Use `name <- parser` to bind a step's result; a bare `parser` is run for its effect. The last form is the result.
- `return` `v` — a parser that always succeeds, yielding `v`, consuming nothing.
- `>>=` `m f` — the bind operator `do*` desugars to.
- `failure` — the always-failing parser (yields `'()`); a parser that fails is one that returns an empty result seq.

## Source locations and errors — `jolt.parser.position`

When you want line/column tracking, feed the parser *augmented* input rather than a raw string. The position machinery is what `jolt.parser`'s driver uses internally to produce line/column errors.

- `augment-location` `([text] [text line col offset full-text])` — lazily annotate each character with a `Location` (char, line, col, offset, full-text). A parser built from `basic`/`combinators` threads these `Location`s through transparently.
- `strip-location` `input` — turn augmented input back into plain chars / strings (collapse a seq of `Location`s, or pull `:char` off one).
- `parse-exception` `location` — build a jolt `ex-info` for a parse failure, with a message like `Failed to parse text at line: 1, col: 3` plus a `^` caret pointer, and data `{:type :parse-error :offset :line :col}`. Pass `nil` for an unknown location (`Unable to parse text`).
- `show-error` `location` — the string snippet + caret pointer for a location (or `nil`).
- `Location` record — fields `char`, `line`, `col`, `offset`, `full-text`; `->Location` constructor.
