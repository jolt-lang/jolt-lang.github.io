Every error Jolt raises carries a **kind** — a namespaced keyword
identifying what went wrong, independent of the wording. The kind is
what to search for, and what tooling should key on: the message beside
it is free to improve, the kind is not.

```
error[analyze/invalid-def]: First argument to def must be a Symbol
  --> ./src/app.clj:3:1
   |
 3 | (def :foo 2)
   |      ^^^^ the name must be a symbol
```

Set `JOLT_DIAG=edn` to get the same diagnostic as a single line of EDN
— kind, position, the offending token and the surrounding source —
for editors and other tooling.

This page is generated from
[`test/conformance/error-kinds.edn`](https://github.com/jolt-lang/jolt/blob/main/test/conformance/error-kinds.edn),
which the build gates against the compiler's actual raise sites.

## Reader

Raised while reading source text into forms, before anything is compiled.

### `read/duplicate-key`

The same key twice in a map literal, or the same element twice in a set
literal. An IllegalArgumentException on both runtimes.

### `read/invalid-character`

A character literal that names nothing — an unknown `\name`, or an octal escape
outside `[0, 377]`.

### `read/invalid-data-reader`

A `#tag` literal whose reader function is not registered in `*data-readers*`.

### `read/invalid-syntax`

A read error with no more specific kind yet — the default for the reader's
positioned raise. Narrowing one of these to its own kind is always an
improvement; nothing depends on it staying general.

### `read/invalid-token`

A token that is not a valid symbol or keyword: a leading or trailing slash, a
bare `:` or `::`, or a keyword name beginning with a colon (`:::c`).

### `read/invalid-unicode`

A \uXXXX escape that is not four hex digits, is not a valid code point, or is
an unpaired surrogate.

### `read/odd-entries-in-map`

A map literal with an odd number of forms, so some key has no value.

## Analysis

Raised while analyzing a form — the compile-time errors.

### `analyze/internal-failure`

Anything else raised while analyzing a form, carrying the position of the
innermost form under analysis. A diagnostic that reaches a user with this kind
is a gap in the list above, not a category of its own.

### `analyze/invalid-arity`

A special form applied to the wrong number of arguments.

### `analyze/invalid-binding`

A `let*`/`loop*` binding whose left-hand side is not a symbol. Destructuring is
a macro over these, so a pattern that reaches the special form directly is one
the desugarer did not rewrite.

### `analyze/invalid-def`

A `def` whose first argument is not a symbol.

### `analyze/invalid-fn-parameters`

A `fn*` parameter list that is not a vector of symbols, or a rest parameter
that is not a symbol.

### `analyze/invalid-if`

An `if` with fewer than two or more than three arguments.

### `analyze/invalid-macro-value`

A macro's var used as a value. Macros exist only at compile time.

### `analyze/invalid-member-access`

A host interop form that does not parse: (. target member ...), (.method
target ...) or (.-field target) with a missing or non-symbol member.

### `analyze/invalid-method-reference`

A qualified instance method (Class/.method) used as a value rather than called.

### `analyze/invalid-recur`

A `recur` that cannot reach its target: no enclosing loop or fn, the wrong
argument count, across a `try`, or outside tail position.

### `analyze/invalid-set`

A `set!` whose target is not an assignable place — a local, a non-var, or a
form that is neither a symbol nor a field access.

### `analyze/invalid-try`

A `try` whose clauses are out of order (a body form after a catch, a finally
that is not last) or a catch clause that does not parse.

### `analyze/invalid-var-reference`

A `var` special form whose argument is not a symbol.

### `analyze/unresolved-symbol`

A symbol that names neither a local, an interned var, nor an imported class.
Carries :symbol, :suggestions and :ns alongside the position.

### `analyze/unresolved-var`

A `var` special form naming a var that does not exist.

### `analyze/unsupported-form`

A form the analyzer cannot classify at all — neither literal, symbol,
collection, nor list.

### `analyze/unsupported-special-form`

A special-form head jolt's analyzer has no arm for.

## Foreign interface

Raised while checking a jolt.ffi layout, type or signature. These are
compile-time like the analysis errors, but concern a foreign type description
rather than Clojure itself.

### `ffi/invalid-array`

A fixed array descriptor that is not `[:array element-type positive-count]`.

### `ffi/invalid-field`

A struct or union field that is not `[keyword type]`, whose name is not an
unqualified keyword, or that repeats a name already used.

### `ffi/invalid-layout`

A jolt.ffi layout that is not `[:struct [[field type] ...]]` or the `:union`
equivalent, or one declaring no fields at all.

### `ffi/invalid-option`

An unknown option key, an option key that is not an unqualified keyword, or an
option combination the backend cannot honour — :capture-native-error on a
:void or by-value return.

### `ffi/invalid-signature`

A foreign-fn or foreign-callable form whose shape does not parse.

### `ffi/invalid-type`

A field or parameter type that is not a fixed-size scalar, a nested struct or
union, or a fixed array.

