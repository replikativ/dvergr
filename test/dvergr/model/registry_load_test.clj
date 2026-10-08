(ns dvergr.model.registry-load-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.model.registry :as registry]))

(deftest a-model-from-models-edn-is-found-without-loading-it-first
  ;; a benchmark naming a Fireworks model looked it up before anything loaded
  ;; models.edn, and failed with "Model not found in registry"
  (let [id "accounts/fireworks/models/glm-5p3-flash"]
    (registry/unregister-model! id)
    (reset! @#'registry/models-loaded? false)
    (is (= :fireworks (:provider (registry/get-model! id))))))
