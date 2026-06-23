(defproject jolt-docs "0.1"
  :description "Website and documentation for the Jolt language"
  :url "https://jolt-lang.github.io/"
  :license {:name "Eclipse Public License 1.0"
            :url  "https://opensource.org/licenses/EPL-1.0"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [hiccup "1.0.5"]
                 [markdown-clj "1.11.2"]
                 [crouton "0.1.2"]
                 [selmer "1.12.53"]
                 [me.raynes/fs "1.4.6"]
                 [ring/ring-core "1.12.2"]
                 [ring/ring-jetty-adapter "1.12.2"]]
  :min-lein-version "2.0.0"
  :main site.core)
