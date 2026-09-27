(ns dvergr.benchmarks.frozen-web
  "The frozen web, now in core (`dvergr.io.frozen-web`); kept for the
   benchmarks that name it here."
  (:require [dvergr.io.frozen-web :as web]))

(def search-url web/search-url)
(def transport web/transport)
