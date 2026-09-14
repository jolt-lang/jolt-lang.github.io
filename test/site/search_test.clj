(ns site.search-test
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [site.search :as search]))

(def sample-html
  "<p>Jolt is a Clojure dialect hosted on Scheme.</p>
   <h2 id=\"getting-started\">Getting Started</h2>
   <p>Install with <code>brew install jolt</code> and run the REPL.</p>
   <h2 id=\"ffi\">Calling <code>C</code> Libraries</h2>
   <p>Use <code>defcfn</code> to bind a C function.</p>
   <pre><code class=\"language-clojure\">(defcfn printf)</code></pre>
   <h3 id=\"ffi-arenas\">Arenas</h3>
   <p>Allocate through an arena.</p>")

(deftest doc-sections-splits-on-h2
  (let [sections (search/doc-sections "getting-started.md" "Getting Started" sample-html)]
    (is (= 4 (count sections)))
    (let [{:keys [title heading href text]} (first sections)]
      (is (= "Getting Started" title))
      (is (= "Getting Started" heading))
      (is (= "/docs/getting-started.html" href))
      (is (= "Jolt is a Clojure dialect hosted on Scheme." text)))
    (let [{:keys [heading href text]} (second sections)]
      (is (= "Getting Started" heading))
      (is (= "/docs/getting-started.html#getting-started" href))
      (is (= "Install with brew install jolt and run the REPL." text)))))

(deftest doc-sections-strips-inline-markup-in-headings
  (let [sections (search/doc-sections "getting-started.md" "Getting Started" sample-html)
        {:keys [heading href text]} (nth sections 2)]
    (is (= "Calling C Libraries" heading))
    (is (= "/docs/getting-started.html#ffi" href))
    (is (re-find #"\(defcfn printf\)" text))
    (is (not (re-find #"<code>" text)))))

(deftest doc-sections-nests-h3-under-parent-h2
  (let [sections (search/doc-sections "getting-started.md" "Getting Started" sample-html)
        {:keys [heading href text]} (last sections)]
    (is (= "Calling C Libraries > Arenas" heading))
    (is (= "/docs/getting-started.html#ffi-arenas" href))
    (is (= "Allocate through an arena." text))))

(deftest build-documents-emits-flat-json-array
  (let [docs {:topics []
              :docs-by-topic {"getting-started.md" "Getting Started"
                              "fibers.md" "Fibers"}
              "getting-started.md" {:content sample-html}
              "fibers.md"
              {:content "<h2 id=\"overview\">Overview</h2><p>Fibers are lightweight concurrency.</p>"}}
        documents (json/read-str (search/build-documents docs))]
    (is (vector? documents))
    (is (= 5 (count documents)))
    (is (every? #(set/subset? #{"title" "heading" "href" "text"} (set (keys %)))
                documents))
    (is (= (mapv #(% "href") documents)
           (mapv #(% "href") (sort-by #(% "href") documents)))
        "document order is deterministic (sorted by href)")
    (let [overview (some #(when (= "/docs/fibers.html#overview" (% "href")) %)
                         documents)]
      (is (= "Fibers" (overview "title")))
      (is (= "Overview" (overview "heading")))
      (is (= "Fibers are lightweight concurrency." (overview "text"))))))
