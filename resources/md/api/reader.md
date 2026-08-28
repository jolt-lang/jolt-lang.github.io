Clojure's reader dispatch table is closed. After a `#`, the reader claims a fixed
set of characters, a letter starts a data-reader tag, and every other character
is a read error — `#$"x"` on the JVM is `No dispatch macro for: $`, and has been
in every version. That is a deliberate call ("why Clojure does not allow to
introduce new syntax like Common Lisp"), and it is the reason string
interpolation never shipped either: `clojure.core.strint`'s `<<` has sat in
core.incubator since 2010.

Jolt owns its reader, so the punctuation half of that table is open. Two things
come out of it: `#$"…"` string interpolation, ready to use, and `jolt.reader`
for putting your own reader on a character.

Both are Jolt-only. Every `#<punctuation>` sequence is a read error on the JVM,
so nothing that reads there reads differently here — but a file using either
will not read on Clojure.

## String interpolation

`#$` reads a string literal and splices the expressions written inside it.
`~{form}` and `~(form …)` both interpolate; the braces are there so a bare name
needs no parentheses.

```clojure
(let [x 3]
  #$"a ~{x} b ~(inc x)")
;=> "a 3 b 4"
```

It happens at **read** time: the literal above reads as
`(clojure.core/str "a " x " b " (inc x))` and compiles like any other `str`
call. A string with no marker reads as itself, so `#$"plain"` *is* the string
`"plain"` — no wrapper, nothing at runtime.

A `~` that is not followed by `{` or `(` is literal, so `#$"100~ ok"` is
`"100~ ok"`. To get a literal `~{`, interpolate one: `#$"~{\"~{\"}"`. Quotes
inside an interpolated form have to be escaped, since the whole thing is one
string literal.

The same grammar is available as a macro under its original name, for code
written against core.incubator:

```clojure
(require '[clojure.core.strint :refer [<<]])

(let [m {:a [1 2 3]}]
  (<< "The total for your order is ~(->> m :a (apply +))."))
;=> "The total for your order is 6."
```

`<<` takes string literals, since it splits them at macroexpansion. It shares
one implementation of the grammar with `#$`, so the two never drift apart.

## Adding your own

```clojure
(require '[jolt.reader :as reader])
```

`set-dispatch-macro!` puts a function on one character. The default tier is the
simple one: the next form is read normally and your function returns the form to
read in its place.

```clojure
(reader/set-dispatch-macro! \% (fn [form] (list 'clojure.core/vector form form)))

#%(+ 1 2)   ;=> [3 3]
```

The `{:raw true}` tier hands your function the source string and the index just
past the dispatch character, and takes back `[form end-index]` — the form to
read and where reading resumes. It is what a literal whose body is not Clojure
data needs: a raw string, a heredoc, an embedded query.

```clojure
(reader/set-dispatch-macro! \|
  (fn [src i]
    (let [end (.indexOf src "|" i)]
      [(subs src i end) (inc end)]))
  {:raw true})

#|C:\new|   ;=> "C:\\new"
```

`end-index` has to be at least the index your function was handed and no greater
than the length of the source. A reader that returns anything else raises,
rather than spinning on the same character or reading past the end.

`remove-dispatch-macro!` takes one off; `dispatch-macros` returns what is
registered, as a map of character to function. `#$` is a registration like any
other, so it appears there and can be removed or replaced.

## When a registration takes effect

Registration is an ordinary runtime call, and Jolt reads a file one top-level
form at a time. A `#<char>` is read through whatever is registered *at that
moment*, so a file can register a macro at the top and use it below — but not
inside the same top-level form, which was already read before the registration
ran.

Registration is process-wide, not per-namespace. Unlike a `:require`, and like
Common Lisp's `set-dispatch-macro-character`, it changes how everything read
afterwards reads. A library that registers one is changing the language for the
whole program, so say so in its README.

`jolt build` loads your application from source before it scans it, so a built
binary reads the same source the same way `jolt run` does — both tiers included.

## What you cannot put a reader on

Only punctuation. Three kinds of character raise at registration instead of
quietly shadowing what is there:

- **The ones the reader claims**: `#{`, `#(`, `#"`, `#_`, `#!`, `#'`, `#^`,
  `##`, `#=`, `#?`, `#:`.
- **Any letter or digit**, because those begin a `#tag`. A reader on `\s` would
  swallow every `#some/tag` in the program.
- **Anything the reader cannot see past**: whitespace, a comma, a semicolon,
  a backslash, and a closing delimiter.

`clojure.edn` never consults the table. edn is a closed grammar with no user
extension point, so `#$` in an edn document stays the unreadable tag it always
was — a config file that reads only under Jolt would be a bad trade.

## Tag readers are the other half

`#foo/bar form` is the part of the dispatch table Clojure already leaves open,
and it works here the way it does on the JVM: a `data_readers.clj` (or
`.cljc`, or `.jolt`) at the root of a source path maps a tag symbol to a
fully-qualified reader function.

```clojure
;; src/data_readers.clj
{my/rev app.readers/reverse-str}
```

Jolt loads the reader's namespace for you and applies it at load time. A reader
that returns a **form** has its result compiled in place, so a tag can expand to
code; one that returns a **value** is left as a call evaluated at startup, which
keeps a non-serializable constant out of an AOT build. A `*data-readers*` entry
holding the function itself, rather than a symbol naming it, works too.

A tag with no reader function is an error naming the tag, as on the JVM.
`read-string` is the exception: it returns an inert
[tagged literal](https://clojure.org/reference/reader#tagged_literals) rather
than raising, so data carrying an unknown tag can be inspected before you decide
what to do with it.
