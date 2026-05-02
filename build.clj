(ns build
  "Build script for dvergr-mcp uberjar.

   Usage:
     clj -T:build uber          # Build uberjar
     clj -T:build clean         # Clean target directory"
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.replikativ/dvergr-mcp)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/dvergr-mcp.jar")

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                   :src-dirs ["src"]
                   :class-dir class-dir
                   :ns-compile '[dvergr.mcp.server]})
  (b/uber {:class-dir class-dir
            :uber-file uber-file
            :basis @basis
            :main 'dvergr.mcp.server})
  (println (str "Built: " uber-file)))
