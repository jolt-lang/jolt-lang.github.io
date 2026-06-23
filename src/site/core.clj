(ns site.core
  (:require
    [me.raynes.fs :as fs]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [site.util :as util]
    [selmer.parser :as parser]
    [selmer.filters :refer [add-filter!]]
    [markdown.core :refer [md-to-html-string]]
    [ring.adapter.jetty :as jetty]
    [ring.middleware.file :refer [wrap-file]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]))

(parser/set-resource-path! (clojure.java.io/resource "templates"))

(add-filter! :markdown (fn [content] [:safe (md-to-html-string content)]))

(def output-dir "docs")

(defn render-page [[url template params]]
  (let [path (str output-dir url)]
    (io/make-parents path)
    (spit path (parser/render-file (str template) (assoc params :page template)))))

(defn translated-topics [docs]
  (mapv
    (fn [[doc-id title type]]
      [(string/replace doc-id #".md$" ".html") title type])
    (:topics docs)))

(defn render-doc [docs doc-id]
  (let [translated-topics (translated-topics docs)]
    (merge
      {:title  (get-in docs [:docs-by-topic doc-id])
       :topics (filter #(= "topic" (nth % 2)) translated-topics)
       :libs   (filter #(= "lib" (nth % 2)) translated-topics)}
      (get docs doc-id))))

(defn doc-page [docs doc-id]
  [(str "/docs/" (string/replace doc-id #".md$" ".html"))
   "docs.html"
   (render-doc docs doc-id)])

(defn pages [docs]
  (into
    [["/index.html" "home.html"]
     ["/404.html" "404.html"]]
    (map (partial doc-page docs) (keys (dissoc docs :docs-by-topic :topics)))))

(defn generate! []
  (fs/delete-dir output-dir)
  (fs/copy-dir "resources/static" output-dir)
  (doseq [page (pages (util/generate-docs))]
    (render-page page)))

(defn wrap-index [handler]
  (fn [request]
    (let [uri (:uri request)]
      (handler
        (if (= "/" uri)
          (assoc request :uri "/index.html")
          request)))))

(defn app []
  (-> (constantly {:status 404 :headers {} :body "Not found"})
      (wrap-file output-dir {:index-files? false})
      wrap-content-type
      wrap-not-modified
      wrap-index))

(defn -main [& args]
  (generate!)
  (if (= (first args) "build")
    (println "Site generated in" output-dir)
    (let [port (Integer/parseInt (or (first args) "3000"))]
      (println (str "Site generated. Starting server at http://localhost:" port))
      (jetty/run-jetty (app) {:port port :join? true}))))

(comment
  (generate!))
