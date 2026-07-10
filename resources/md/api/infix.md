Jolt ships an infix-notation layer in `jolt.infix` for arithmetic, comparison, and boolean expressions. It is a thin reader/rewriter on top of ordinary Clojure — infix forms compile down to the same prefix calls you would write by hand, so there is no separate value type and no runtime cost for the macro form. It is handy for math-heavy code, configuration formulas, and user-supplied expressions you need to evaluate safely.

There are two entry points. `infix` (and its alias `$=`) is a **macro** that rewrites a literal infix form at compile time. `from-string` **parses a string at runtime**, returning a function you can call with bound variables.

```clojure
(require '[jolt.infix :refer [infix $= from-string]])

(infix 3 + 5 * 8)            ; => 43   (compiles to (+ 3 (* 5 8)))
($= (3 + 5) * 8)             ; => 64

(let [x 4 y 3]
  (infix x ** y))            ; => 64.0  (** is right-associative pow)

((from-string [x] "x + 3 * y" ...))   ; see from-string below
```

## The `infix` macro

```clojure
(infix & expr)
($=   & expr)        ; alias, identical behavior
```

`infix` takes alternating operands and operators and rewrites them to prefix Clojure at macroexpansion time, applying [operator precedence](#precedence-and-associativity) and resolving [aliases](#aliased-operators-and-functions). Bare names resolve as ordinary Clojure locals or vars; the standard math operators (`+ - * /` etc.) pass straight through to `clojure.core`.

```clojure
(infix 3 + 4)                ; => (+ 3 4)  => 7
(infix 1 - 2 + 3)            ; => 2  (left-associative: ((1 - 2) + 3))
(infix 2 ** 2 ** 2 ** 2)     ; => 65536.0  (** is right-associative)
(infix 16 | 32)              ; => 48       (bit-or)
(infix true && false)        ; => false    (and)
(infix 5 == 5.0)             ; => true     (== is numeric equality)
(infix 5 = 5.0)              ; => false    (= is strict value equality)
```

Function application uses prefix-style calls, and unary functions bind tighter than binary operators:

```clojure
(infix abs (3 ** 6))         ; => 729.0
(let [t 0.324]
  (infix sin (2 * t) + 3 * cos (4 * t)))   ; => 1.4176457261295824
(infix √ (5 * 5))            ; => 5.0      (√ is an alias for sqrt)
```

Note the two equality operators: `==` is Clojure's numeric equality (`5 == 5.0` is true); `=` is strict value equality (`5 = 5.0` is false).

## Parsing strings at runtime: `from-string`

```clojure
(from-string expr)                          ; expr :: String
(from-string [bindings...] expr)
(from-string [bindings...] env expr)        ; env :: {keyword fn}
```

`from-string` parses `expr` according to the [grammar](#grammar) and returns a **function**. Call that function with values for the declared bindings to evaluate the expression:

```clojure
((from-string "5 * 2"))                     ; => 10
((from-string [x] "x + 3") 4)               ; => 7
((from-string [x y] "x + 3 * y") 2 4)       ; => 14
((from-string [t] "(t > 100) ? 1 : 0") 150) ; => 1   (ternary)
```

The returned function carries metadata recording its source and parameters, which is useful when expressions come from configuration or user input:

```clojure
(meta (from-string [x y] "sqrt(x**2 + y**2)"))
; => {:params [:x :y] :doc "sqrt(x**2 + y**2)"}
```

### The environment

`from-string` resolves every name against an **environment** — a map from keyword to function/value. The default is `jolt.infix.core/base-env`, which provides arithmetic, comparison, boolean, bitwise, and math functions (see [The default environment](#the-default-environment)). Declared bindings are merged on top of the base environment, shadowing it:

```clojure
(require '[jolt.infix.core :refer [base-env]])

((from-string [] "sin(pi / 2)"))            ; => 1.0   (uses base-env)

;; Override an operator: map :+ to -, so "x + 3" computes (- x 3)
((from-string [x] {:+ -} "x + 3") 4)        ; => 1

;; Add your own named function alongside the built-ins
((from-string [] {:x 6 :+ +} "x + 1"))      ; => 7
```

### Errors

- A malformed expression throws an `ex-info` carrying the parse location, e.g. `Failed to parse text at line: 1, col: 3`.
- Calling the returned function with the wrong number of arguments throws an `ArityException`.
- Referencing a name that is not bound throws `IllegalStateException: <name> is not bound in environment`.

### Performance

`from-string` parses the string **each time the `from-string` form is evaluated**, not each time the returned function is called. The parsed expression is captured in the closure, so the returned function can be called many times cheaply. Hoist the parser out of hot loops:

```clojure
;; Good: parse once, call many times
(def bytebeat (from-string [t] "(t*(t>>5|t>>8))>>(t>>16)"))
(bytebeat 3425)                             ; => 380175

;; Avoid: re-parses on every iteration
#_(doseq [t (range 1000)]
    ((from-string [t] "(t*(t>>5|t>>8))>>(t>>16)") t))
```

## Aliased operators and functions

The `infix` macro resolves a set of symbols and names to their Clojure/`Math` equivalents before rewriting. These aliases are the friendly surface for math and logic:

**Logical and bitwise**

- `&&` → `and`
- `||` → `or`
- `!` → `not`
- `&` → `bit-and`
- `|` → `bit-or`
- `<<` → `bit-shift-left`
- `>>` → `bit-shift-right`
- `>>>` → `unsigned-bit-shift-right`

**Arithmetic**

- `%` → `mod`
- `**` → `Math/pow` (right-associative)
- `.` → `*` (implicit multiplication: `x . y` == `(* x y)`)
- `÷` → `divide` (division that returns `±Infinity` on divide-by-zero instead of throwing)

**Equality and comparison** (usable inline in both forms)

- `==` → numeric equality (`clojure.core/==`; `5 == 5.0` is true)
- `=` → strict value equality (`clojure.core/=`; `5 = 5.0` is false)
- `!=` → `not=`
- `<`, `>`, `<=`, `>=` → as in `clojure.core`

**Math — exponential/log/power**

- `sqrt`, `√` → `Math/sqrt`
- `exp` → `Math/exp`
- `log` → `Math/log`
- `abs` → `Math/abs`
- `signum` → `Math/signum`
- `pow` → `Math/pow` (also the function form `pow(2.53, 3.1)`)

**Math — trigonometric** (angles in radians)

- `sin`, `cos`, `tan`
- `asin`, `acos`, `atan`, `atan2`
- `sinh`, `cosh`, `tanh`
- `sec`, `csc`, `cot` (secant, cosecant, cotangent — not in `java.lang.Math`)
- `asec`, `acsc`, `acot` (their inverses)

**Number theory and aggregation**

- `gcd` — greatest common divisor
- `lcm` — least common multiple
- `fact` — factorial
- `root(a, b)` — the `a`-th root of `b`, i.e. `b ** (1 / a)`
- `sum`, `∑` — variadic sum, e.g. `sum(1, 2, 5.7, 4)`
- `product`, `∏` — variadic product

**Constants**

- `e` → `Math/E`
- `π`, `pi` → `Math/PI`
- `φ` → golden ratio `(1 + √5) / 2`

**Random**

- `rand()` → a random double in `[0, 1)`
- `randInt(n)` → a random integer in `[0, n)`

In `from-string` these resolve by name from `base-env`; in the `infix` macro the symbolic ones (`&&`, `**`, `√`, `π`, …) are rewritten to their target symbols at compile time.

## Precedence and associativity

Expressions are built from three precedence classes (tightest first), plus ternary:

- `**` (exponentiation) — **right**-associative, so `2 ** 2 ** 2 ** 2` is `2 ** (2 ** (2 ** 2))` → `65536.0`.
- multiplicative: `* / ÷ % << >> >>>` — left-associative.
- additive: `+ - | & || &&` — left-associative, so `1 - 2 + 3` is `(1 - 2) + 3` → `2`.
- unary functions (`sqrt`, `sin`, `abs`, `√`, …) bind tighter than any binary operator.
- the ternary `(cond) ? a : b` is a separate form; the condition must be parenthesized.

Comparison and equality operators (`< > <= >= = == !=`) may also appear inline within an expression.

Use parentheses to override any of the above.

## The default environment

`jolt.infix.core/base-env` is the keyword→function map `from-string` evaluates against by default. It provides everything in the [alias table](#aliased-operators-and-functions) plus the core arithmetic and comparison operators. To see what is available, inspect it directly:

```clojure
(require '[jolt.infix.core :refer [base-env]])
(keys base-env)
; => (:== := :!= :+ :- :* :/ :% :&& :|| :> :< :>= :<=
;     :abs :signum :sqrt :√ :exp :log :pow :** :divide :÷ :root
;     :gcd :lcm :fact :sum :∑ :product :∏ :rand :randInt
;     :sin :cos :tan :asin :acos :atan :atan2
;     :sinh :cosh :tanh :sec :csc :cot :asec :acsc :acot
;     :| :& :¬ :>> :>>> :<< ...)
```

Pass your own env (third argument) to add to or override it; pass a merged map as the second-arity `env` to swap the operator meanings entirely.

## Extending and overriding

The alias table lives in the `jolt.infix.core/operator-alias` atom. `suppress!` removes an alias so the symbol falls back to its ordinary Clojure meaning — useful when a name like `e` clashes with a local:

```clojure
(require '[jolt.infix.core :refer [suppress!]])

(suppress! 'e)            ; 'e is no longer rewritten to Math/E
(let [e 9]
  (infix e * 3))          ; => 27  (e is now your local)
```

You can also `swap!` onto `operator-alias` to add your own aliases for the `infix` macro, and extend `base-env` (or pass an explicit env to `from-string`) to teach the string parser new names.

## Grammar

`from-string` parses according to this EBNF (whitespace between tokens is ignored):

```
<expression> ::= term { addop term }.
<term>       ::= factor { mulop factor }.
<factor>     ::= base { expop base }.
<base>       ::= "(" expression ")" | boolean | number | var | function | ternary.
<addop>      ::= "+" | "-" | "|" | "&" | "||" | "&&".
<mulop>      ::= "*" | "/" | "÷" | "%" | ">>" | ">>>" | "<<".
<expop>      ::= "**".
<function>   ::= envref expression | envref "(" <empty> | expression { "," expression } ")".
<ternary>    ::= "(" expression ")" "?" expression ":" expression.
<envref>     ::= letter | "_" { letter | digit | "_" | "." }.
<var>        ::= envref.
<boolean>    ::= "true" | "false".
<number>     ::= integer | decimal | rational | binary | hex.
<binary>     ::= [ "-" ] "0b" { "0" | "1" }.
<hex>        ::= [ "-" ] ( "0x" | "#" ) { digit | "A".."F" | "a".."f" }.
<integer>    ::= [ "-" ] digits.
<decimal>    ::= [ "-" ] digits "." digits.
<rational>   ::= integer "/" digits.
<letter>     ::= "A".."Z" | "a".."z".
<digit>      ::= "0".."9".
<digits>     ::= digit { digit }.
```

Numbers accept decimals (`1.7`), rationals (`1/7`), binary (`0b011011`), and hex (`0xCAFEBABE` or `#DEADBEEF`), each with an optional leading `-`. Names (`envref`) may contain letters, digits, `_`, and `.`, and resolve to a keyword key in the evaluation environment.
