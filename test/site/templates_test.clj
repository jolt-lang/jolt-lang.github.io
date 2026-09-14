(ns site.templates-test
  (:require [clojure.test :refer [deftest is testing]]
            [selmer.parser :as parser]))

(parser/set-resource-path! (clojure.java.io/resource "templates"))

(defn render [& {:as params}]
  (parser/render-file "docs.html" (merge {:title "Test" :topics []} params)))

(def base (parser/render-file "base.html" {:nav "docs"}))

(deftest navbar-brand-is-logo-only
  (testing "brand shows the logo but not the redundant Jolt text"
    (is (re-find #"<img[^>]+brand-logo" base))
    (is (not (re-find #"brand-name" base)))))

(deftest navbar-links-can-wrap-without-label-breaks
  (testing "nav links wrap as whole items and labels never break mid-text"
    (is (re-find #"<ul class=\"links\">" base))
    (is (re-find #"\.site-nav \.links \{[^\}]*flex-wrap:\s*wrap" (slurp "resources/static/css/screen.css")))
    (is (re-find #"\.site-nav \.links a \{[^\}]*white-space:\s*nowrap" (slurp "resources/static/css/screen.css")))))

(deftest docs-sidebar-collapsible
  (let [html (render)]
    (testing "sidebar menu is toggleable and hidden by default on mobile"
      (is (re-find #"docs-sidebar-toggle" html))
      (is (re-find #"aria-expanded=\"false\"" html))
      (is (re-find #"aria-controls=\"docs-menu\"" html))
      (is (re-find #"id=\"docs-menu\"" html))
      (is (re-find #"docs-sidebar-toggle" (slurp "resources/static/js/docs.js"))))))

(deftest docs-sidebar-toc-stays-visible
  (testing "page contents (toc) remain outside the collapsible docs menu"
    (let [html (render :toc "<ol><li><a href=\"#x\">X</a></li></ol>")]
      (is (re-find #"class=\"toc\"" html))
      (is (re-find #"docs-menu" html)))))
