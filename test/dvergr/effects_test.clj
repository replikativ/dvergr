(ns dvergr.effects-test
  "The effect boundary (doc/effects.md): admission and mode decide, every
   effect is receipted, and sandbox files, git and HTTP pass through it with the
   boundary the runtime set, not one the code chose."
  (:require [clojure.java.io :as io]
            [clojure.set]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dvergr.agent.turn :as turn]
            [dvergr.effects :as effects]
            [dvergr.runtime.ctx :as runtime-ctx]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.core :as rtc]))

(defn- boundary
  "A stack as a world would have it: receipts into `sink`, then `specs`."
  [sink specs]
  (constantly {:handlers (into [(effects/receipts sink nil)] (effects/handlers specs))}))

(deftest the-stack-decides-and-receipts
  (let [sink (effects/make-sink)]
    (testing "no handlers: the world answers, and the read is receipted by digest, not body"
      (is (= "body" (effects/perform! (boundary sink [])
                                      {:effect :fs/read :resource {:path "a"}}
                                      (constantly "body"))))
      (let [r (last @sink)]
        (is (= [:fs/read :allowed :world #{:read}] ((juxt :effect :decision :by :class) r)))
        (is (= (effects/digest "body") (:digest r)))
        (is (not-any? #{"body"} (vals r)))))
    (testing "read-only: a write is refused before it runs, and receipted"
      (let [ran (atom false)]
        (is (thrown-with-msg? Exception #"read-only"
                              (effects/perform! (boundary sink [[:read-only]])
                                                {:effect :fs/write :resource {:path "a"}}
                                                #(reset! ran true))))
        (is (false? @ran))
        (is (= [:denied :read-only] ((juxt :decision :by) (last @sink))))))
    (testing "read-only: an HTTP GET reaches the network, so it is refused too"
      (is (thrown? Exception (effects/perform! (boundary sink [[:read-only]])
                                               {:effect :http/request} (constantly nil)))))
    (testing "admission: classes not granted are refused; egress is added per request"
      (is (= :ok (effects/perform! (boundary sink [[:admit #{:network}]])
                                   {:effect :http/request} (constantly :ok))))
      (is (thrown-with-msg? Exception #"not granted"
                            (effects/perform! (boundary sink [[:admit #{:network}]])
                                              {:effect :http/request :class #{:egress}}
                                              (constantly :ok)))))
    (testing "a handler that answers is named in the receipt; the world never runs"
      (with-redefs [effects/registry {:canned (fn [v] (fn [_ _] (effects/answer :canned v)))}]
        (is (= "recorded" (effects/perform! (boundary sink [[:canned "recorded"]])
                                            {:effect :fs/read :resource {:path "a"}}
                                            #(throw (ex-info "the world ran" {})))))
        (is (= :canned (:by (last @sink))))
        (testing "and a filter still refuses before it: authority is not replayed away"
          (is (thrown? Exception (effects/perform! (boundary sink [[:canned "x"] [:read-only]])
                                                   {:effect :fs/write :resource {:path "a"}}
                                                   (constantly nil)))))))
    (testing "a failing world is receipted with its error and rethrown"
      (is (thrown? ArithmeticException
                   (effects/perform! (boundary sink []) {:effect :fs/read} #(/ 1 0))))
      (is (= "java.lang.ArithmeticException" (:error (last @sink)))))
    (testing "the vocabulary is closed"
      (is (thrown-with-msg? Exception #"Unknown effect kind"
                            (effects/perform! nil {:effect :fs/teleport} (constantly nil)))))
    (testing "results have the operation's shape"
      (is (effects/valid-result? :fs/list ["a" "b"]))
      (is (not (effects/valid-result? :fs/list "a"))))
    (testing "the sink keeps the most recent receipts"
      (dotimes [_ 2100] (effects/perform! (boundary sink []) {:effect :fs/stat} (constantly true)))
      (is (= 2000 (count @sink))))))

;; ---------------------------------------------------------------------------
;; The algebra of configurations (dvergr.effects ns doc)
;; ---------------------------------------------------------------------------

(def ^:private gen-config
  (let [gen-classes (gen/fmap set (gen/vector (gen/elements (vec effects/all-classes))))]
    (gen/vector (gen/one-of [(gen/return [:read-only])
                             (gen/fmap (fn [s] [:admit s]) gen-classes)
                             (gen/fmap (fn [n] [:answer n]) gen/small-integer)])
                0 5)))

(defn- admitted [config]
  (or (some (fn [[k s]] (when (= :admit k) s)) (effects/normalize config))
      effects/all-classes))

(defn- filters [config] (filter #(#{:admit :read-only} (first %)) config))

(defspec normalize-is-idempotent 300
  (prop/for-all [a gen-config]
                (= (effects/normalize a) (effects/normalize (effects/normalize a)))))

(defspec compose-is-associative 300
  (prop/for-all [a gen-config b gen-config c gen-config]
                (= (effects/compose (effects/compose a b) c)
                   (effects/compose a (effects/compose b c)))))

(defspec the-empty-configuration-is-the-identity 300
  (prop/for-all [a gen-config]
                (= (effects/compose [] a) (effects/normalize a) (effects/compose a []))))

(defspec composing-never-admits-what-either-refused 300
  (prop/for-all [a gen-config b gen-config]
                (= (admitted (effects/compose a b))
                   (clojure.set/intersection (admitted a) (admitted b)))))

(defspec filters-commute 300
  (prop/for-all [a gen-config]
                (= (effects/normalize (filters a)) (effects/normalize (reverse (filters a))))))

(defspec a-filter-decides-as-its-normal-form 300
  (prop/for-all [a gen-config
                 kind (gen/elements (vec (keys effects/vocabulary)))
                 egress? gen/boolean]
                (let [run (fn [config]
                            (try (effects/perform! (constantly {:handlers (effects/handlers (filters config))})
                                                   {:effect kind :class (when egress? #{:egress})}
                                                   (constantly :ran))
                                 (catch clojure.lang.ExceptionInfo _ :denied)))]
      ;; the stack built from the raw filters and from their normal form agree
                  (= (run a)
                     (run (effects/normalize (filters a)))
                     (if (every? (admitted a) (effects/effect-classes {:effect kind :class (when egress? #{:egress})}))
                       :ran :denied)))))

(deftest configurations-compose-algebraically
  (testing "read-only is the filter admitting reads"
    (is (= (effects/normalize [[:read-only]]) (effects/normalize [[:admit #{:read}]]))))
  (testing "admitting every class is no filter"
    (is (= [] (effects/normalize [[:admit effects/all-classes]]))))
  (testing "answering handlers keep their order, after the filter"
    (is (= [[:admit #{:read}] [:answer 1] [:answer 2]]
           (effects/normalize [[:answer 1] [:read-only] [:answer 2]])))))

(defn- with-world-sandbox
  "A sandbox whose world binding the runtime set: `binding` is what code
   cannot change (mode, acting agent)."
  [binding f]
  (let [ec (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)
        root (.getAbsoluteFile (doto (io/file (System/getProperty "java.io.tmpdir")
                                              (str "effects-" (random-uuid)))
                                 .mkdirs))
        cap (random-uuid)
        sink (effects/make-sink)]
    (try
      (runtime-ctx/install-sandbox-binding! ec cap (assoc binding :capability-id cap))
      (binding [rtc/*execution-context* ec]
        (sandbox/setup-agent-namespaces! sci-ctx ec :base-path (str root)
                                         :capability-id cap :receipts sink))
      (f {:root root :sink sink
          :eval #(binding [rtc/*execution-context* ec]
                   (let [r (sandbox/eval-code sci-ctx %)]
                     (if (:success r) {:ok (:value r)} {:err (get-in r [:error :message])})))})
      (finally
        (ctx/stop-context! ec)
        (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest sandbox-files-pass-the-boundary
  (with-world-sandbox {:agent-id :var :room-runtime-id :ops}
    (fn [{:keys [root sink eval]}]
      (testing "a live sandbox writes and reads, and every op is receipted"
        (is (:ok (eval "(spit \"notes.md\" \"hello\")")))
        (is (= "hello" (:ok (eval "(slurp \"notes.md\")"))))
        (is (= ["notes.md"] (:ok (eval "(babashka.fs/list-dir \".\")"))))
        (is (= [:fs/write :fs/read :fs/list] (mapv :effect @sink)))
        (is (every? #(= {:agent :var :room :ops} (:subject %)) @sink)
            "the subject is the acting identity from the runtime")
        (is (= {:path "notes.md"} (:resource (first @sink)))
            "receipts name the path as given, never the host path"))
      (testing "a refused path is receipted with its error"
        (is (:err (eval "(slurp \"/etc/hostname\")")))
        (is (= [:fs/read "/etc/hostname"] ((juxt :effect (comp :path :resource)) (last @sink))))
        (is (:error (last @sink))))
      (is (.exists (io/file root "notes.md"))))))

(deftest a-read-only-world-denies-writes-and-the-network
  (with-world-sandbox {:effects {:handlers [[:read-only]]}}
    (fn [{:keys [root sink eval]}]
      (spit (io/file root "data.edn") "{:a 1}")
      (is (= "{:a 1}" (:ok (eval "(slurp \"data.edn\")"))) "reads run")
      (is (re-find #"read-only" (str (:err (eval "(spit \"out.md\" \"x\")")))))
      (is (not (.exists (io/file root "out.md"))) "the write never ran")
      (is (re-find #"read-only" (str (:err (eval "(babashka.fs/delete \"data.edn\")")))))
      (is (.exists (io/file root "data.edn")))
      (is (re-find #"read-only" (str (:err (eval "(babashka.http-client/get \"https://example.com\")")))))
      (testing "the code cannot lift the mode: it lives in the world binding"
        (is (:err (eval "(dvergr.runtime.ctx/install-sandbox-binding! nil nil {})"))))
      (is (= [:allowed :denied :denied :denied] (mapv :decision @sink))))))

(deftest a-world-narrows-and-never-widens
  (let [ec (ctx/create-execution-context)]
    (try
      (let [cctx (turn/new-working-ctx {:execution-ctx ec :title "effects" :agent-id :var
                                        :durable? false
                                        :effects [[:admit #{:read :write}]]})
            cap (:capability-id cctx)
            handlers #(get-in (runtime-ctx/sandbox-binding ec cap) [:effects :handlers])]
        (is (= [[:admit #{:read :write}]] (handlers)))
        (turn/rebind-working-ctx! cctx {:execution-ctx ec :effects [[:read-only]]})
        (is (= [[:admit #{:read}]] (handlers)) "a rebind composes: read-only within read+write")
        (turn/rebind-working-ctx! cctx {:execution-ctx ec :effects [[:admit effects/all-classes]]})
        (is (= [[:admit #{:read}]] (handlers)) "admitting everything later does not widen")
        (is (some? (:receipts cctx)) "the working context owns its receipts"))
      (finally (ctx/stop-context! ec)))))
