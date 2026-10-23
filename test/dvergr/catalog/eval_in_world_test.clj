(ns dvergr.catalog.eval-in-world-test
  "An attempt builds a model the way a modelling agent does: it writes a
   file, loads it with clojure_eval, edits it and loads it again with the
   same call, in a catalog attempt's world on a durable room. The repeated
   load is progress, not a doom loop: each one sees the edit."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.principal :as principal]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]))

(def ^:dynamic *daemon* nil)

(use-fixtures :once
  (fn [f]
    (let [prev-home (paths/home)]
      (paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-eval-world-test-" (random-uuid)))
      (sdb/reset-conn!)
      (let [d (daemon/start! {:agents {}})]
        (try
          (binding [*daemon* d] (f))
          (finally
            (try (daemon/stop! d) (catch Exception _))
            (sdb/reset-conn!)
            (paths/set-home! prev-home)))))))

(def load-code "(load-string (slurp \"/out/model/m.clj\")) v")

(def steps
  [[{:id "write" :name "write_file" :input {:path "/out/model/m.clj" :content "(def v 1)\n"}}]
   [{:id "eval-1" :name "clojure_eval" :input {:code load-code}}]
   [{:id "edit" :name "edit_file" :input {:path "/out/model/m.clj" :old_string "(def v 1)" :new_string "(def v 2)"}}
    {:id "eval-2" :name "clojure_eval" :input {:code load-code}}]
   [{:id "eval-3" :name "clojure_eval" :input {:code load-code}}]
   [{:id "eval-rel" :name "clojure_eval" :input {:code "(slurp \"out/model/m.clj\")"}}]
   [{:id "eval-inputs" :name "clojure_eval" :input {:code "(pos? (count (slurp \"/docs/company.md\")))"}}]])

(defn- scripted [results]
  (fn [messages _opts]
    (let [role #(or (:role %) (:message/role %))
          rs (filter #(= :tool-result (role %)) messages)]
      (doseq [m rs]
        (swap! results assoc (str (or (:tool-use-id m) (:message/tool-use-id m)))
               (str (or (:content m) (:message/content m)))))
      (let [n (count (distinct (map #(or (:tool-use-id %) (:message/tool-use-id %)) rs)))
            step (loop [i 0 seen 0] (cond (= i (count steps)) nil
                                          (>= seen n) i
                                          :else (recur (inc i) (+ seen (count (nth steps i))))))]
        (if step
          {:content "" :tool-calls (nth steps step)
           :usage {:input-tokens 100 :output-tokens 10} :stop-reason :tool-use}
          {:content "Done." :tool-calls nil
           :usage {:input-tokens 100 :output-tokens 10} :stop-reason :end-turn})))))

(deftest a-model-file-is-loaded-edited-and-reloaded-in-an-attempt-world
  (let [results (atom {})]
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  chat-agent/messages->api-format (fn [messages _ _] messages)
                  model-chat/chat (scripted results)]
      (let [started (ops/invoke *daemon* principal/local-principal :catalog/start
                                {:workflow "wiki/v1" :attempts 1 :models ["claude-haiku-4-5"]})
            done (loop [n 0]
                   (let [st (ops/invoke *daemon* principal/local-principal :job/status
                                        {:job (:id started) :wait-ms 20000})]
                     (if (or (not= "running" (:status st)) (< 6 n)) st (recur (inc n)))))]
        (is (= "completed" (:status done)) (pr-str (dissoc done :result)))
        (is (= {"eval-1" "=> 1" "eval-2" "=> 2" "eval-3" "=> 2"
                "eval-rel" "=> \"(def v 2)\\n\"" "eval-inputs" "=> true"}
               (select-keys @results ["eval-1" "eval-2" "eval-3" "eval-rel" "eval-inputs"]))
            "an edit in the same turn is seen by the load beside it, and every step ran")))))
