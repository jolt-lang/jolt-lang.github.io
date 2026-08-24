Jolt ships `clojure.test`, and the official [Cognitect test-runner](https://github.com/cognitect-labs/test-runner) runs against it unmodified; the same `deps.edn` alias you would use on the JVM.

## Writing tests

Test namespaces are ordinary Clojure. By convention they live under a `test/` directory and their names end in `-test`, which is what test runners look for by default.

```clojure
;; test/my_lib/core_test.clj
(ns my-lib.core-test
  (:require [clojure.test :refer [deftest is are testing use-fixtures]]
            [my-lib.core :as sut]))

(deftest addition
  (testing "small numbers"
    (is (= 3 (sut/add 1 2))))
  (testing "a table of cases"
    (are [x y] (= x y)
      2 (sut/add 1 1)
      0 (sut/add 0 0))))

(deftest failure-modes
  (is (thrown? clojure.lang.ExceptionInfo (sut/add nil 1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a number" (sut/add :x 1))))
```

`deftest`, `is`, `are`, `testing`, `thrown?` and `thrown-with-msg?` all behave as they do on the JVM. `thrown?` matches subclasses, so catching `Exception` catches an `ExceptionInfo`.

### Fixtures

`use-fixtures` takes `:each` (around every test) or `:once` (around the namespace's whole run):

```clojure
(def ^:dynamic *db* nil)

(use-fixtures :once (fn [t] (with-open [conn (connect)] (binding [*db* conn] (t)))))
(use-fixtures :each (fn [t] (reset-tables!) (t)))
```

### Custom assertions

`clojure.test/assert-expr` is a multimethod, so a library can teach `is` a new form. Register the method in its own top-level form; `is` resolves it when the assertion is compiled, so the two cannot sit in the same form:

```clojure
(defmethod clojure.test/assert-expr 'roughly? [msg form]
  `(let [[_# a# b#] '~form]
     (clojure.test/do-report
       {:type (if (< (abs (- ~(nth form 1) ~(nth form 2))) 1e-6) :pass :fail)
        :message ~msg :expected '~form :actual nil})))

(deftest close-enough (is (roughly? 1.0 1.0000001)))
```

`clojure.test/report` is a multimethod too, which is how test.check hooks its `::trial` and `::shrunk` events in.

## Running them with the official runner

Add the runner as a test-only dependency and give it an alias:

```clojure
{:paths ["src"]
 :deps  {}
 :aliases
 {:test   {:extra-paths ["test"]
           :extra-deps  {io.github.cognitect-labs/test-runner
                         {:git/tag "v0.5.1" :git/sha "dfb30dd"}}}
  :runner {:main-opts ["-m" "cognitect.test-runner"]}}}
```

Then:

```bash
jolt -M:test:runner
```

```
Running tests in #{test}

Ran 24 tests. 91 assertions passed, 0 failures, 0 errors.
```

It exits non-zero when anything fails or errors, so it drops straight into CI.

By default it scans the `test` directory and runs every namespace whose name ends in `-test`. The options narrow that:

| Option | Effect |
|---|---|
| `-d DIR` | scan `DIR` instead of `test` (repeatable) |
| `-n NS` | run only this namespace (repeatable) |
| `-r REGEX` | run namespaces matching this regex (repeatable) |
| `-v NS/VAR` | run only this test var (repeatable) |
| `-i KEYWORD` | run only tests with this metadata key |
| `-e KEYWORD` | skip tests with this metadata key |

`-i` and `-e` read metadata on the test var, so tagging works the usual way:

```clojure
(deftest ^:integration talks-to-the-database ...)
```

```bash
jolt -M:test:runner -e :integration      # everything but those
jolt -M:test:runner -i :integration      # only those
jolt -M:test:runner -v my-lib.core-test/addition
```

Namespace selection needs jolt **0.5.4** or newer; earlier versions found no namespaces at all and reported `Ran 0 tests`, and the var-level options (`-v`, `-i`, `-e`) silently selected everything.

## Running them without a runner

A runner namespace is enough when you would rather not take the dependency. `run-tests` takes the namespaces to run and returns that call's summary:

```clojure
;; test/my_lib/test_runner.clj
(ns my-lib.test-runner
  (:require [clojure.test :as t]
            my-lib.core-test
            my-lib.util-test))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'my-lib.core-test 'my-lib.util-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
```

This replaces the `:test`/`:runner` pair above; one alias carries both the test path and the entry point, and the runner dependency is gone:

```clojure
:aliases {:test {:extra-paths ["test"]
                 :main-opts   ["-m" "my-lib.test-runner"]}}
```

```bash
jolt -M:test
```

The trade-off is that you list the namespaces by hand; the Cognitect runner finds them for you by scanning the directory.

## Notes

- Test sources belong on `:extra-paths` in a test alias, not on the project's `:paths`; that keeps them out of what a library's consumers load.
- Aliases combine, so `-M:test:runner` selects the dependencies from `:test` and the entry point from `:runner`. `:extra-paths`/`:extra-deps` accumulate; `:main-opts` is last-wins. See [Building &amp; Running](/docs/building-and-deps.html).
- A library whose tests reach for `java.time` needs [jolt-lang/time](/docs/api/time.html) as a test dependency; those classes live in that library rather than core, by [RFC 0008](/docs/rfc/0008-time-core-library-split.html).
- `run-tests` with no arguments runs every registered test rather than the current namespace's. That is a deliberate superset of the JVM's behavior; pass namespaces explicitly if you want the narrower one.
