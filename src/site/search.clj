(ns site.search
  (:require [clojure.data.json :as json]
            [clojure.string :as s]
            [crouton.html :as html]
            [site.util :as util]))

(defn- href [doc-id anchor]
  (str "/docs/" (s/replace doc-id #".md$" ".html") (when anchor (str "#" anchor))))

(defn- squeeze [text]
  (->> (s/split text #"\s+")
       (remove #(= "" %))
       (s/join " ")))

(defn- collect-text
  "Plain searchable text of a content node sequence. Inline and block code
   stay (their text is searchable); markup is stripped by node-text."
  [content]
  (-> (s/join " "
              (map (fn [node]
                     (cond
                       (string? node) node
                       (map? node) (util/node-text node)
                       :else ""))
                   content))
      s/trim
      squeeze))

(defn- h3? [node]
  (and (map? node) (= :h3 (:tag node))))

(defn- h2-sections [nodes]
  (reduce
    (fn [sections node]
      (if (and (map? node) (= :h2 (:tag node)))
        (conj sections {:id (get-in node [:attrs :id])
                        :heading (s/trim (util/node-text node))
                        :content []})
        (if-let [current (peek sections)]
          (conj (pop sections) (update current :content conj node))
          sections)))
    []
    nodes))

(defn- body-content
  "crouton parses fragments into a full document; page content lives under
   the <body> node."
  [nodes]
  (if-let [body (some #(when (and (map? %) (= :body (:tag %))) %) nodes)]
    (:content body)
    nodes))

(defn doc-sections
  "Split a rendered doc page into searchable sections: an intro section
   (content before the first heading) plus one section per h2, with h3
   subsections nested under their parent h2's heading path."
  [doc-id title html]
  (let [nodes (vec (body-content
                     (:content (html/parse (java.io.ByteArrayInputStream. (.getBytes html))))))
        split (count (take-while #(not (and (map? %) (#{:h1 :h2} (:tag %)))) nodes))
        intro (take split nodes)]
    (for [{:keys [anchor heading text]}
          (concat
            (when-not (s/blank? (collect-text intro))
              [{:anchor nil :heading title :text (collect-text intro)}])
            (mapcat
              (fn [{:keys [id heading content]}]
                (let [own (take-while (complement h3?) content)
                      parts (->> (drop (count own) content)
                                 (partition-by h3?)
                                 (partition-all 2))]
                  (concat
                    [{:anchor id :heading heading :text (collect-text own)}]
                    (for [[[h3] following] parts]
                      {:anchor (get-in h3 [:attrs :id])
                       :heading (str heading " > " (s/trim (util/node-text h3)))
                       :text (collect-text following)}))))
              (h2-sections (drop split nodes))))]
      {:title title
       :heading heading
       :href (href doc-id anchor)
       :text text})))

(defn build-documents
  "JSON string: one document per section, sorted by href for deterministic
   output. Non-page keys (:topics, :docs-by-topic) are skipped."
  [docs]
  (json/write-str
    (sort-by :href
             (mapcat (fn [[doc-id {:keys [content]}]]
                       (doc-sections doc-id (get-in docs [:docs-by-topic doc-id]) content))
                     (dissoc docs :topics :docs-by-topic)))))
