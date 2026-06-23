(ns site.util
  (:require [markdown.core :as md]
            [crouton.html :as html]
            [hiccup.core :as hiccup]
            [clojure.edn :as edn]
            [clojure.java.io :refer [resource]]
            [clojure.string :as s]))

(defn remove-div-spans [text state]
  (let [opener #"&lt;(boot|lein)-div&gt;"
        closer #"&lt;/(boot|lein)-div&gt;"]
    (if (or (:codeblock state)
            (:code state))
      [(-> text
           (s/replace opener "<div class=\"$1\">")
           (s/replace closer "</div>"))
       state]
      [text state])))

(defn format-time
  "formats the time using SimpleDateFormat, the default format is
   \"dd MMM, yyyy\" and a custom one can be passed in as the second argument"
  ([time] (format-time time "dd MMM, yyyy"))
  ([time fmt]
   (.format (new java.text.SimpleDateFormat fmt) time)))

(defn slurp-resource
  "reads a markdown file from resources/md and returns an HTML string"
  [filename]
  (->> filename resource slurp))

(defn load-doc-pages []
  (edn/read-string (slurp (resource "docpages.edn"))))

(defn parse-doc [name]
  (md/md-to-html-string
   (slurp-resource (str "md/" name))
   :heading-anchors true
   :code-style #(str "class=\"" % "\"")
   :replacement-transformers (conj markdown.transformers/transformer-vector remove-div-spans)))

(defn get-headings [content]
  (reduce
    (fn [headings {:keys [tag attrs content] :as elm}]
      (if (and (some #{tag} [:h1 :h2 :h3]) (:id attrs))
        (conj headings elm)
        (if-let [more-headings (get-headings content)]
          (into headings more-headings)
          headings)))
    [] content))

(defn make-links [headings]
  (when (not-empty headings)
    (let [items
          (loop [remaining headings
                 result    []]
            (if (empty? remaining)
              result
              (let [{:keys [tag attrs content]} (first remaining)
                    {id :id} attrs
                    [title]  content]
                (if (= tag :h3)
                  (let [sub-items  (take-while #(= :h3 (:tag %)) remaining)
                        rest-items (drop (count sub-items) remaining)
                        sub-links  (for [{{sid :id} :attrs [stitle] :content} sub-items]
                                     [:li [:a {:href (str "#" sid)} stitle]])]
                    (if (seq result)
                      (recur rest-items
                             (update result (dec (count result))
                                     conj (into [:ol] sub-links)))
                      (recur rest-items
                             (into result sub-links))))
                  (recur (rest remaining)
                         (conj result [:li [:a {:href (str "#" id)} title]]))))))]
      (hiccup/html (into [:ol.contents] items)))))

(defn generate-toc [content]
  (when content
    (-> content
        (.getBytes)
        (java.io.ByteArrayInputStream.)
        html/parse
        :content
        get-headings
        make-links)))

(defn generate-docs
  "generate HTML document pages from Markdown"
  []
  (let [pages (load-doc-pages)]
    (reduce
      (fn [docs id]
        (let [doc (parse-doc id)]
          (assoc docs id {:toc (generate-toc doc) :content doc})))
      {:topics pages :docs-by-topic (reduce (fn [out [k v _]]
                                              (assoc out k v)) {} pages)}
      (map first pages))))
