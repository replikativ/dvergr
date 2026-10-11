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
            [datahike.api :as dh]
            [dvergr.actors :as actors]
            [dvergr.agent.persona]
            [dvergr.agent.turn :as turn]
            [dvergr.chat.schema :as schema]
            [dvergr.chat.context :as chat-context]
            [dvergr.authority :as authority]
            [dvergr.discourse :as d]
            [dvergr.effects :as effects]
            [dvergr.intake.mail]
            [dvergr.orchestration.tasks :as tasks]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store.memory :as memory]
            [dvergr.runtime.ctx :as runtime-ctx]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.deps]
            [dvergr.sandbox.ns.agent :as agent-ns]
            [dvergr.sandbox.ns.io :as ns-io]
            [dvergr.sandbox.ns.kb :as ns-kb]
            [dvergr.tools :as tools]
            [dvergr.scheduler.core]
            [dvergr.tools.llm-call :as llm-call]
            [sci.core :as sci]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.yggdrasil]))

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
      (with-redefs [effects/registry {:canned (fn [_ctx v] (fn [_ _] (effects/answer :canned v)))}]
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
                             (gen/return [:authority])
                             (gen/fmap (fn [hs] [:allow-hosts hs]) (gen/set (gen/elements ["a.com" "docs.a.com" "b.com"]) {:min-elements 1}))
                             (gen/fmap (fn [hs] [:deny-hosts hs]) (gen/set (gen/elements ["a.com" "b.com" "c.com"])))
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
  (testing "a predicate filter composes with itself to one"
    (is (= [[:admit #{:read}] [:authority]]
           (effects/normalize [[:authority] [:read-only] [:authority]]))))
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

(deftest every-routed-capability-is-decided-before-it-runs
  (let [sink (effects/make-sink)
        ro (boundary sink [[:read-only]])
        touched (atom [])]
    (testing "rooms: posting, forking, creating are refused; reading is not"
      (let [room (d/make-room {:id :effects-rooms :store (memory/make)})
            ops (ns-kb/room-ops-map (:ctx room) nil (select-keys room [:id :incarnation])
                                    {:effects ro})]
        (is (thrown-with-msg? Exception #"read-only" (('post! ops) (:id room) {:content "x"})))
        (is (empty? (d/messages room {})) "nothing was posted")
        (is (thrown-with-msg? Exception #"read-only" (('fork! ops) (:id room))))
        (is (thrown-with-msg? Exception #"read-only" (('create! ops) {:slug "new-room"})))
        (is (= (:id room) (:id (('get ops) (:id room)))) "a read runs")
        (is (= [:room/post :denied] ((juxt :effect :decision) (first (filter #(= :room/post (:effect %)) @sink)))))))
    (testing "schedules"
      (let [sci-ctx (sci/init {})]
        (agent-ns/add-scheduler-ns! sci-ctx ro)
        (is (thrown-with-msg? Exception #"read-only"
                              (sci/eval-string* sci-ctx "(dvergr.scheduler/every :day \"09:00\" :var \"sweep\")")))
        (is (thrown-with-msg? Exception #"read-only"
                              (sci/eval-string* sci-ctx "(dvergr.scheduler/cancel \"s1\")")))))
    (testing "model calls, the shell and process directives"
      (with-redefs [llm-call/cheap-llm-call (fn [& _] (swap! touched conj :model) {:text "x"})]
        (let [sci-ctx (sci/init {})]
          (ns-kb/add-llm-ns! sci-ctx nil nil ro)
          (is (thrown-with-msg? Exception #"read-only" (sci/eval-string* sci-ctx "(llm/call \"s\" \"c\")")))))
      (let [sci-ctx (sci/init {})]
        (ns-io/add-bash-ns! sci-ctx ::chat ro)
        (ns-io/add-process-ns! sci-ctx ::chat ro)
        (is (thrown-with-msg? Exception #"read-only" (sci/eval-string* sci-ctx "(dvergr.shell/run \"ls\")")))
        (is (thrown-with-msg? Exception #"read-only"
                              (sci/eval-string* sci-ctx "(processes/directive! 1 {:type :abort})"))))
      (is (empty? @touched) "no refused capability ran"))
    (testing "and each refusal is receipted"
      (is (every? #(= :denied (:decision %)) (remove #(= :room/read (:effect %)) @sink)))
      (is (= #{:room/post :room/fork :room/create :room/read :schedule/create :schedule/cancel
               :model/call :process/run :process/directive}
             (set (map :effect @sink)))))))

(deftest evals-are-metered
  (testing "an eval reports its CPU and wall time"
    (let [ec (ctx/create-execution-context)
          sci-ctx (sandbox/fork-for-session ec)]
      (try
        (doseq [opts [{} {:timeout-ms 5000}]]
          (let [r (apply sandbox/eval-code sci-ctx "(reduce + (range 2000000))" (mapcat identity opts))]
            (is (:success r))
            (is (nat-int? (get-in r [:meter :cpu-ms])) (str opts))
            (is (<= (get-in r [:meter :cpu-ms]) (+ 50 (get-in r [:meter :wall-ms]))))))
        (finally (ctx/stop-context! ec)))))
  (testing "clojure_eval records it on the working context's receipts"
    (let [ec (ctx/create-execution-context)]
      (try
        (let [cctx (turn/new-working-ctx {:execution-ctx ec :title "meter" :durable? false})
              r (binding [rtc/*execution-context* ec]
                  (tools/execute "clojure_eval" {:code "(+ 1 2)"}
                                 (tools/make-context {:sci-ctx (chat-context/sci-context-in cctx ec)
                                                      :chat-ctx cctx :execution-ctx ec
                                                      :isolation :sci
                                                      :cwd (System/getProperty "java.io.tmpdir")})))]
          (is (= :success (:type r)))
          (is (= :eval/run (:effect (last @(:receipts cctx)))))
          (is (nat-int? (get-in (last @(:receipts cctx)) [:resource :wall-ms]))))
        (finally (ctx/stop-context! ec))))))

(deftest database-writes-pass-the-boundary
  (with-world-sandbox {:agent-id :var}
    (fn [{:keys [sink eval]}]
      (is (:ok (eval "(let [cfg {:store {:backend :mem :id \"scratch\"} :schema-flexibility :read}]
                        (datahike.api/create-database cfg)
                        (datahike.api/transact (datahike.api/connect cfg) [{:note \"a\"} {:note \"b\"}])
                        true)")))
      (is (= [[:db/create {:name "scratch"}] [:db/transact {:datoms 2}]]
             (mapv (juxt :effect :resource) (filter #(#{"db"} (namespace (:effect %))) @sink))))))
  (with-world-sandbox {:effects {:handlers [[:read-only]]}}
    (fn [{:keys [eval]}]
      (is (re-find #"read-only" (str (:err (eval "(datahike.api/create-database {:store {:backend :mem :id \"x\"}})"))))))))

(deftest an-agent-acts-on-its-own-rooms-and-those-it-takes-part-in
  (let [ec (ctx/create-execution-context)
        sink (effects/make-sink)]
    (try
      (binding [rtc/*execution-context* ec]
        (let [home (d/make-room {:id :authority-home :ctx ec :store (memory/make)})
              other (d/make-room {:id :authority-other :ctx ec :store (memory/make)})
              fx (effects/boundary-resolver (constantly {:agent-id :var :room-runtime-id (:id home)})
                                            sink
                                            {:relations #(authority/relations (rreg/list-rooms))})
              ops (ns-kb/room-ops-map ec nil (select-keys home [:id :incarnation])
                                      {:acting-agent (constantly :var) :effects fx})
              post! ('post! ops)]
          (testing "its own room"
            (is (:posted-to (post! (:id home) {:content "here"}))))
          (testing "another room it takes no part in: refused, and receipted as authority's"
            (is (thrown-with-msg? Exception #"may not write" (post! (:id other) {:content "there"})))
            (is (empty? (d/messages other {})))
            (is (= [:denied :authority] ((juxt :decision :by) (last @sink)))))
          (testing "a fork of its room"
            (let [fork (('fork! ops) (:id home))]
              (is (:posted-to (post! (:id fork) {:content "in the fork"})))
              (is (some? (('discard! ops) (:id fork))) "and it may discard it")))
          (testing "a room it joins"
            (d/join other (d/participant {:id :var :on-message (constantly nil)}))
            (is (:posted-to (post! (:id other) {:content "now a participant"})))
            (is (thrown-with-msg? Exception #"may not admin" (('delete! ops) (:id other)))
                "but it does not own it"))
          (testing "an MCP connection keeps its reach"
            (let [mcp (ns-kb/room-ops-map ec nil (select-keys home [:id :incarnation])
                                          {:effects (effects/boundary-resolver
                                                     (constantly {:agent-id :mcp/code}) sink
                                                     {:relations #(authority/relations (rreg/list-rooms))})})]
              (is (some? (('get mcp) (:id other))))))))
      (finally (ctx/stop-context! ec)))))

;; ---------------------------------------------------------------------------
;; Answering handlers: record, replay, faults
;; ---------------------------------------------------------------------------

(def ^:private gen-program
  "A run as the effects it performs."
  (gen/vector (gen/let [kind (gen/elements [:fs/read :fs/write :http/request :git/read])
                        path (gen/elements ["a" "b" "c"])]
                {:effect kind :resource {:path path}})
              0 12))

(defn- run-program
  "Perform `program` under `specs`; `world` answers what reaches it. Returns
   each effect's outcome."
  [specs program world]
  (let [b (constantly {:handlers (effects/handlers specs)})]
    (mapv (fn [e]
            (try [:ok (effects/perform! b e #(world e))]
                 (catch clojure.lang.ExceptionInfo x [:err (:type (ex-data x))])))
          program)))

(defn- counting-world
  "A world whose answer depends on how often it was asked: replay must give
   back exactly what it answered, not recompute it."
  []
  (let [n (atom 0)] (fn [e] [(:effect e) (:resource e) (swap! n inc)])))

(defspec replay-reproduces-a-recording-without-the-world 200
  (prop/for-all [program gen-program]
                (let [r (effects/recording!)
                      live (run-program [[:record {:id r}]] program (counting-world))
                      p (effects/replay! (effects/recorded r))
                      replayed (run-program [[:replay {:id p}]] program
                                            (fn [_] (throw (ex-info "the world was asked" {}))))]
                  (effects/release! r) (effects/release! p)
                  (= live replayed))))

(defspec a-fault-rate-of-zero-is-the-identity 200
  (prop/for-all [program gen-program seed gen/small-integer]
                (let [f (effects/faults! {:seed seed :rate 0.0})
                      w (fn [e] [(:effect e) (:resource e)])
                      r (= (run-program [] program w) (run-program [[:faults {:id f}]] program w))]
                  (effects/release! f)
                  r)))

(defspec the-same-seed-gives-the-same-faults 200
  (prop/for-all [program gen-program seed gen/small-integer]
                (let [run #(let [f (effects/faults! {:seed seed :rate 0.5 :kinds [:error :timeout :rate-limit]})
                                 out (run-program [[:faults {:id f}]] program (fn [e] (:resource e)))]
                             (effects/release! f)
                             out)]
                  (= (run) (run)))))

(deftest answering-handlers
  (testing "a replay that is asked for something unrecorded says so"
    (let [p (effects/replay! [])]
      (is (= [[:err :effect/replay-divergence]]
             (run-program [[:replay {:id p}]] [{:effect :fs/read :resource {:path "x"}}] identity)))))
  (testing "a recorded error is replayed as an error"
    (let [r (effects/recording!)
          e {:effect :fs/read :resource {:path "gone"}}]
      (run-program [[:record {:id r}]] [e] (fn [_] (throw (ex-info "No such file" {}))))
      (let [p (effects/replay! (effects/recorded r))]
        (is (= [[:err :effect/replayed-error]] (run-program [[:replay {:id p}]] [e] identity))))))
  (testing "rate 1: every effect in scope faults, others reach the world"
    (let [f (effects/faults! {:seed 1 :rate 1.0 :only #{:http/request} :kinds [:rate-limit]})
          out (run-program [[:faults {:id f}]]
                           [{:effect :http/request :resource {:method :get :url "u"}}
                            {:effect :fs/read :resource {:path "a"}}]
                           (constantly {:status 200 :headers {} :body "ok"}))]
      (is (= [[:ok {:status 429 :headers {"retry-after" "1"} :body "rate limited"}]
              [:ok {:status 200 :headers {} :body "ok"}]]
             out))
      (is (effects/valid-result? :http/request (second (first out))) "an injected answer has the operation's shape")))
  (testing "answering handlers do not commute: recording outside faults records them"
    (let [program [{:effect :fs/read :resource {:path "a"}}]
          outside (effects/recording!) inside (effects/recording!)
          f1 (effects/faults! {:seed 1 :rate 1.0}) f2 (effects/faults! {:seed 1 :rate 1.0})]
      (run-program [[:record {:id outside}] [:faults {:id f1}]] program identity)
      (run-program [[:faults {:id f2}] [:record {:id inside}]] program identity)
      (is (= 1 (count (effects/recorded outside))))
      (is (empty? (effects/recorded inside)) "the fault answered before anything reached the recording")))
  (testing "a receipt names who answered"
    (let [sink (effects/make-sink)
          f (effects/faults! {:seed 1 :rate 1.0 :kinds [:timeout]})
          b (constantly {:handlers (into [(effects/receipts sink nil)] (effects/handlers [[:faults {:id f}]]))})]
      (is (thrown? Exception (effects/perform! b {:effect :fs/read :resource {:path "a"}} (constantly "x"))))
      (is (= :faults (:by (last @sink)))))))

(deftest a-world-configures-every-sandbox-in-it
  (testing "handlers installed on the world apply to a sandbox whose binding has none"
    (let [ec (ctx/create-execution-context)
          sci-ctx (sandbox/fork-for-session ec)
          root (.getAbsoluteFile (doto (io/file (System/getProperty "java.io.tmpdir") (str "world-" (random-uuid))) .mkdirs))]
      (try
        (effects/install-world! ec [[:read-only]])
        (binding [rtc/*execution-context* ec]
          (sandbox/setup-agent-namespaces! sci-ctx ec :base-path (str root))
          (is (re-find #"read-only" (str (get-in (sandbox/eval-code sci-ctx "(spit \"x\" \"y\")") [:error :message])))))
        (finally (ctx/stop-context! ec) (doseq [f (reverse (file-seq root))] (.delete f))))))
  (testing "a fork inherits its world's handlers and adds its own without touching the parent"
    (let [ec (ctx/create-execution-context)]
      (try
        (binding [rtc/*execution-context* ec]
          (let [home (d/make-room {:id :world-effects-home :ctx ec :store (memory/make)})
                _ (effects/install-world! (:ctx home) [[:admit #{:read :write}]])
                ops (ns-kb/room-ops-map ec nil (select-keys home [:id :incarnation]))
                fork (('fork! ops) (:id home))]
            (is (= [[:admit #{:read :write}]] (effects/world-handlers (:ctx fork))))
            (effects/install-world! (:ctx fork) [[:read-only]])
            (is (= [[:admit #{:read}]] (effects/world-handlers (:ctx fork))))
            (is (= [[:admit #{:read :write}]] (effects/world-handlers (:ctx home))) "the parent is unchanged")
            (('discard! ops) (:id fork))))
        (finally (ctx/stop-context! ec)))))
  (testing "an environment's :effects become specs and host state"
    (let [{:keys [specs recording release]} (effects/environment-handlers!
                                             {:faults {:seed 3 :rate 0.2} :record true :read-only true})]
      (is (= [:read-only :record :faults] (mapv first specs)))
      (is (uuid? recording))
      (release)
      (is (thrown? Exception (effects/recorded recording)) "released"))))

(deftest a-task-s-answer-sources-are-blocked
  (let [sink (effects/make-sink)
        get! (fn [specs url]
               (try (effects/perform! (constantly {:handlers (into [(effects/receipts sink nil)]
                                                                   (effects/handlers specs))})
                                      {:effect :http/request :resource {:method :get :url url}}
                                      (constantly {:status 200 :headers {} :body "ok"}))
                    (catch clojure.lang.ExceptionInfo e (:by (ex-data e)))))]
    (testing "the host and its subdomains are refused, receipted as blocked; others pass"
      (is (= :blocked (get! [[:deny-hosts #{"simm.is"}]] "https://simm.is/blog/x")))
      (is (= :blocked (get! [[:deny-hosts #{"simm.is"}]] "https://www.simm.is/")))
      (is (= :blocked (get! [[:deny-hosts #{"simm.is"}]] "https://docs.simm.is/a")))
      (is (= {:status 200 :headers {} :body "ok"} (get! [[:deny-hosts #{"simm.is"}]] "https://notsimm.is/")))
      (is (= [:denied :blocked] ((juxt :decision :by) (first (filter #(= :denied (:decision %)) @sink))))))
    (testing "host filters compose by union, and sit outside answering handlers"
      (is (= [[:deny-hosts #{"a.com" "b.com"}]] (effects/normalize [[:deny-hosts #{"a.com"}] [:deny-hosts #{"B.com"}]])))
      (is (= [[:deny-hosts #{"simm.is"}] [:faults {:id 1}]]
             (effects/normalize [[:faults {:id 1}] [:deny-hosts #{"simm.is"}]]))))
    (testing "a replay cannot answer a blocked request either"
      (let [p (effects/replay! [{:key [:http/request {:method :get :url "https://simm.is/"}] :value {:status 200 :headers {} :body "the answer"}}])]
        (is (= :blocked (get! [[:replay {:id p}] [:deny-hosts #{"simm.is"}]] "https://simm.is/")))
        (effects/release! p)))))

(deftest a-world-sees-what-was-refused-in-it
  (let [ec (ctx/create-execution-context)
        own (effects/make-sink)
        world (effects/make-sink)]
    (try
      (effects/install-world! ec [[:deny-hosts #{"simm.is"}]])
      (effects/set-world-sink! ec world)
      (let [b (effects/boundary-resolver nil own {:world #(effects/world-handlers ec)
                                                  :world-sink #(effects/world-sink ec)})]
        (is (thrown? Exception (effects/perform! b {:effect :http/request :resource {:method :get :url "https://simm.is/"}}
                                                 (constantly nil))))
        (effects/perform! b {:effect :fs/read :resource {:path "a"}} (constantly "x"))
        (is (= 2 (count @own) (count @world)) "both sinks have every receipt")
        (is (= {:blocked 1} (effects/denials @world))))
      (finally (ctx/stop-context! ec)))))

(deftest writes-are-bounded-by-a-quota
  (let [q (effects/quota! {:bytes 10})
        b (constantly {:handlers (effects/handlers [[:quota {:id q}]])})
        write (fn [n] (try (effects/perform! b {:effect :fs/write :resource {:path "a"} :bytes n} (constantly :ok))
                           (catch clojure.lang.ExceptionInfo e (:by (ex-data e)))))]
    (is (= :ok (write 6)))
    (is (= :quota (write 5)) "6 + 5 > 10: refused, and not counted")
    (is (= :ok (write 4)))
    (is (= 10 (effects/quota-used q)))
    (is (= :ok (effects/perform! b {:effect :fs/read :resource {:path "a"}} (constantly :ok))) "reads are free")
    (effects/release! q))
  (testing "a sandbox's spit counts against it, and is refused past it"
    (let [q (effects/quota! {:bytes 100})]
      (with-world-sandbox {:effects {:handlers [[:quota {:id q}]]}}
        (fn [{:keys [root eval]}]
          (is (:ok (eval "(spit \"a.txt\" (apply str (repeat 60 \"x\")))")))
          (is (re-find #"quota" (str (:err (eval "(spit \"b.txt\" (apply str (repeat 60 \"y\")))")))))
          (is (not (.exists (io/file root "b.txt"))) "the refused write never happened")
          (is (= 60 (effects/quota-used q)))))
      (effects/release! q))))
(deftest registry-tools-pass-the-same-boundary
  (let [ec (ctx/create-execution-context)
        root (.getAbsoluteFile (doto (io/file (System/getProperty "java.io.tmpdir") (str "tools-fx-" (random-uuid))) .mkdirs))]
    (try
      (spit (io/file root "notes.md") "inside")
      (let [cctx (turn/new-working-ctx {:execution-ctx ec :title "tools" :durable? false :agent-id :mcp/code
                                        :effects [[:read-only]]})
            tctx (tools/make-context {:cwd (str root) :chat-ctx cctx :execution-ctx ec})
            run #(binding [rtc/*execution-context* ec] (tools/execute %1 %2 tctx))]
        (testing "a read runs; a write is refused by the world's read-only mode and never happens"
          (is (re-find #"inside" (str (:content (run "read_file" {:path "notes.md"})))))
          (let [w (run "write_file" {:path "out.md" :content "x"})]
            (is (= :error (:type w)))
            (is (re-find #"read-only" (str (:error w)))))
          (is (not (.exists (io/file root "out.md")))))
        (testing "both are receipted on the chat"
          (is (= [:fs/read :fs/write] (mapv :effect (filter #(#{:fs/read :fs/write} (:effect %)) @(:receipts cctx)))))))
      (finally
        (ctx/stop-context! ec)
        (doseq [f (reverse (file-seq root))] (.delete f))))))

(defn- with-sys-conn
  "A fresh in-memory database with dvergr's schema: what the system DB, a
   chat DB and a room KB all are."
  [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/install-schema! conn)
      (try (f conn) (finally (dh/release conn) (dh/delete-database cfg))))))

(defn- denied? [r] (and (= :error (:type r)) (re-find #"read-only" (str (:error r)))))

(deftest read-only-denies-every-registry-tool-that-writes
  (require 'dvergr.scheduler.tools 'dvergr.intake.mail)
  (let [ec (ctx/create-execution-context)
        root (.getAbsoluteFile (doto (io/file (System/getProperty "java.io.tmpdir") (str "tools-ro-" (random-uuid))) .mkdirs))
        touched (atom [])
        touch (fn [k] (fn [& _] (swap! touched conj k) true))]
    (try
      (spit (io/file root "x.clj") "(ns x)\n\n(def x 1)\n")
      (with-sys-conn
        (fn [conn]
          (let [task-id (random-uuid)
                _ (dh/transact conn [{:task/id task-id :task/title "seed" :task/status :pending
                                      :task/priority :medium :task/created-at (java.util.Date.)}
                                     {:entity/id (random-uuid) :entity/title "Seed" :entity/mention-count 1
                                      :entity/contexts [] :entity/created-at (java.util.Date.)}])
                before @conn
                cctx (turn/new-working-ctx {:execution-ctx ec :title "tools-ro" :durable? false :agent-id :mcp/code
                                            :effects [[:read-only]]})
                tctx (tools/make-context {:cwd (str root) :chat-ctx cctx :execution-ctx ec :db-conn conn
                                          :isolation :native})
                run #(binding [rtc/*execution-context* ec] (tools/execute %1 %2 tctx))]
            (with-redefs [dvergr.agent.persona/write-prompt! (touch :profile)
                          llm-call/cheap-llm-call (fn [& _] (swap! touched conj :model) {:text "x"})
                          dvergr.scheduler.core/create-schedule! (touch :schedule)
                          dvergr.scheduler.core/cancel-schedule! (touch :unschedule)
                          dvergr.intake.mail/sync-inbox! (touch :mail)
                          dvergr.intake.mail/list-inbox (touch :inbox)
                          dvergr.intake.mail/account-open? (constantly false)]
              (testing "file writers: structural edits too"
                (is (denied? (run "clojure_edit" {:file_path "x.clj" :form_type "def" :form_name "x"
                                                  :operation "replace" :new_source "(def x 2)"})))
                (is (denied? (run "edit_file" {:path "x.clj" :old_string "(def x 1)" :new_string "(def x 3)"})))
                (is (denied? (run "write_file" {:path "y.clj" :content "(def y 1)"})))
                (is (= "(ns x)\n\n(def x 1)\n" (slurp (io/file root "x.clj"))) "the file is unchanged")
                (is (not (.exists (io/file root "y.clj")))))
              (testing "database writers"
                (is (denied? (run "knowledge_add" {:title "New" :summary "s"})))
                (is (denied? (run "knowledge_add" {:title "Seed" :context "more"})))
                (is (denied? (run "task_create" {:title "t2"})))
                (is (denied? (run "task_update" {:id (str task-id) :status "completed"})))
                (is (= (dh/q '[:find ?e ?a ?v :where [?e ?a ?v]] before)
                       (dh/q '[:find ?e ?a ?v :where [?e ?a ?v]] @conn))
                    "no datom was written"))
              (testing "the prompt, model calls, schedules and mail"
                (is (denied? (run "update_agent_profile" {:agent-name "code" :content "# me"})))
                (is (denied? (run "llm_call" {:prompt "p" :content "c"})))
                (is (denied? (run "schedule_create" {:agent_id "var" :task "t" :interval_minutes 5})))
                (is (denied? (run "schedule_cancel" {:id "s1"})))
                (is (denied? (run "spawn_agent" {:task "t"})))
                (is (denied? (run "propose_change" {:task "t"})))
                (let [r (run "mail_sync" {})]
                  (is (re-find #"read-only" (str (:error r) (:result r))) (pr-str r)))
                (is (denied? (run "mail_inbox" {})) "a read that first opens the store writes"))
              (testing "native evaluation runs past every capability, so it is the effect"
                (is (denied? (run "clojure_eval" {:code "(spit \"z.txt\" \"x\")"})))
                (is (not (.exists (io/file root "z.txt")))))
              (is (empty? @touched) "no refused writer ran")
              (testing "reads still run"
                (is (re-find #"def x 1" (str (:content (run "read_file" {:path "x.clj"})))))
                (is (re-find #"x.clj" (str (:content (run "glob" {:pattern "*.clj"})))))
                (is (re-find #"seed" (str (:content (run "task_list" {})))))
                (is (re-find #"Seed" (str (:content (run "knowledge_search" {:operation "top"})))))
                (is (= :success (:type (run "budget" {})))))))))
      (finally
        (ctx/stop-context! ec)
        (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest the-workspace-write-gate-is-one-function
  (let [root (.getAbsoluteFile (doto (io/file (System/getProperty "java.io.tmpdir") (str "ws-gate-" (random-uuid))) .mkdirs))
        sink (effects/make-sink)]
    (try
      (testing "a caller without a chat passes its own boundary"
        (is (thrown-with-msg? Exception #"read-only"
                              (tools/workspace-write! {:cwd (str root) :effect-boundary (boundary sink [[:read-only]])}
                                                      "a.md" "x")))
        (is (not (.exists (io/file root "a.md"))))
        (tools/workspace-write! {:cwd (str root) :effect-boundary (boundary sink [])} "sub/b.md" "hello")
        (is (= "hello" (slurp (io/file root "sub/b.md")))))
      (testing "each write is an :fs/write receipt with its size"
        (is (= [[:fs/write :denied] [:fs/write :allowed]] (mapv (juxt :effect :decision) @sink))))
      (testing "the clamp still holds"
        (is (thrown? Exception (tools/workspace-write! {:cwd (str root) :effect-boundary (boundary sink [])}
                                                       "../escape.md" "x"))))
      (finally (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest every-registered-tool-has-an-effect-classification
  (require 'dvergr.tools.llm-call 'dvergr.scheduler.tools 'dvergr.intake.mail)
  (testing "a tool without one cannot be registered unnoticed"
    (doseq [t (tools/all-tools)]
      (is (some? (tools/effect-classification t)) (str (:name t) " declares no effect"))))
  (testing "the renewal arena's tool, registered on demand"
    (is (some? (tools/effect-classification @(requiring-resolve 'dvergr.agent.arenas.renewal/renewal-plan-tool)))))
  (testing "a tool handed to an agent that declares nothing is assumed to write and reach out"
    (let [ec (ctx/create-execution-context)
          ran (atom [])
          root (.getAbsoluteFile (doto (io/file (System/getProperty "java.io.tmpdir") (str "unclassified-" (random-uuid))) .mkdirs))]
      (try
        (let [cctx (turn/new-working-ctx {:execution-ctx ec :title "unclassified" :durable? false :agent-id :mcp/code
                                          :effects [[:read-only]]})
              tool (fn [n & {:as more}]
                     (merge {:name n :execute (fn [_ _] (swap! ran conj n) {:type :success :content "did it"})} more))
              tctx (tools/make-context {:cwd (str root) :chat-ctx cctx :execution-ctx ec
                                        :tools {"mystery" (tool "mystery")
                                                ;; a replacement under a built-in's name does
                                                ;; not inherit the built-in's classification
                                                "write_file" (tool "write_file")
                                                "nil-effect" (tool "nil-effect" :effect (fn [_] nil))
                                                "bogus" (tool "bogus" :effect :bogus)
                                                "reads" (tool "reads" :effect (fn [_] :reads))}})
              run #(binding [rtc/*execution-context* ec] (tools/execute % {} tctx))]
          (doseq [n ["mystery" "write_file" "nil-effect" "bogus"]]
            (is (denied? (run n)) n))
          (is (= :success (:type (run "reads"))) "a tool that says it reads runs")
          (is (= ["reads"] @ran)))
        (finally (ctx/stop-context! ec) (doseq [f (reverse (file-seq root))] (.delete f)))))))

(deftest native-evaluation-needs-every-class
  (let [ec (ctx/create-execution-context)
        root (.getAbsoluteFile (doto (io/file (System/getProperty "java.io.tmpdir") (str "native-" (random-uuid))) .mkdirs))]
    (try
      (let [cctx (turn/new-working-ctx {:execution-ctx ec :title "native" :durable? false :agent-id :mcp/code
                                        :effects [[:admit #{:process :global}]]})
            tctx (tools/make-context {:cwd (str root) :chat-ctx cctx :execution-ctx ec :isolation :native})
            r (binding [rtc/*execution-context* ec]
                (tools/execute "clojure_eval" {:code "(+ 1 2)"} tctx))]
        (is (= :error (:type r)))
        (is (re-find #"not granted" (str (:error r)))))
      (finally (ctx/stop-context! ec) (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest read-only-denies-every-sandbox-writer
  (testing "actors, tasks and skills: the global registry surface"
    (with-sys-conn
      (fn [conn]
        (actors/spawn-agent! conn {:id :var :name "Var"})
        (let [task (tasks/create-task! conn {:actor-id :alice :room-id :ops :content "b"})
              sink (effects/make-sink)
              ro (boundary sink [[:read-only]])
              sci-ctx (sci/init {})
              ev #(sci/eval-string* sci-ctx %)
              before @conn]
          (agent-ns/add-actors-ns! sci-ctx conn nil ro)
          (agent-ns/add-tasks-ns! sci-ctx conn nil ro)
          (agent-ns/add-skills-ns! sci-ctx conn ro)
          (doseq [code ["(dvergr.actors/spawn-agent! {:id :scribe :name \"Scribe\"})"
                        "(dvergr.actors/spawn-human! {:id :eve :external-refs {:telegram 1}})"
                        "(dvergr.actors/update! :var {:name \"Mallory\"})"
                        "(dvergr.actors/add-skill! :var :prose)"
                        "(dvergr.actors/remove-skill! :var :prose)"
                        "(dvergr.actors/dismiss! :var)"
                        (str "(dvergr.tasks/accept! #uuid \"" (:id task) "\")")
                        (str "(dvergr.tasks/complete! #uuid \"" (:id task) "\" \"done\")")
                        (str "(dvergr.tasks/ignore! #uuid \"" (:id task) "\")")
                        "(dvergr.skills/author! \"s\" {} \"body\")"
                        "(dvergr.skills/lift! \"s\" \"https://x\" \"body\")"
                        "(dvergr.skills/promote! \"s\" \"me\" \"2026-10-11\")"
                        "(dvergr.skills/dispatch! :research {:task \"t\"})"]]
            (is (thrown-with-msg? Exception #"read-only" (ev code)) code))
          (is (= (dh/q '[:find ?e ?a ?v :where [?e ?a ?v]] before)
                 (dh/q '[:find ?e ?a ?v :where [?e ?a ?v]] @conn))
              "the system DB is unchanged")
          (testing "reads still run"
            (is (= "Var" (:name (ev "(dvergr.actors/lookup :var)"))))
            (is (= 1 (count (ev "(dvergr.tasks/list)"))))
            (is (map? (ev "(dvergr.skills/all)"))))))))
  (testing "Runs: hiring and cancelling"
    (let [ec (ctx/create-execution-context)
          sci-ctx (sci/init {})
          ro (boundary (effects/make-sink) [[:read-only]])]
      (try
        (agent-ns/add-programming-ns! sci-ctx nil ec nil nil ro)
        (doseq [code ["(dvergr.agent/hire! (dvergr.agent/roster) :x {})"
                      "(dvergr.agent/run-experiment! (dvergr.agent/roster) {})"
                      "(dvergr.agent/cancel! #uuid \"00000000-0000-0000-0000-000000000001\")"]]
          (is (thrown-with-msg? Exception #"read-only" (sci/eval-string* sci-ctx code)) code))
        (finally (ctx/stop-context! ec)))))
  (testing "a sandbox's room GC, dependency loading, mail sync and model calls"
    (let [touched (atom [])
          touch (fn [k] (fn [& _] (swap! touched conj k) :done))]
      (with-redefs [dvergr.sandbox.deps/add-libs! (touch :add-libs)
                    dvergr.sandbox.deps/sync-deps! (touch :sync-deps)
                    dvergr.intake.mail/sync-inbox! (touch :mail)
                    dvergr.intake.mail/list-inbox (touch :inbox)
                    dvergr.intake.mail/account-open? (constantly false)
                    org.replikativ.spindel.yggdrasil/gc! (touch :gc)
                    llm-call/cheap-llm-call (fn [& _] (swap! touched conj :model) {:text "x"})]
        (with-world-sandbox {:effects {:handlers [[:read-only]]}}
          (fn [{:keys [eval]}]
            (doseq [code ["(dvergr.room/gc!)"
                          "(dvergr.room/gc! {:remove-before (java.util.Date.)})"
                          "(clojure.repl.deps/add-libs '{foo/bar {:mvn/version \"1.0\"}})"
                          "(clojure.repl.deps/sync-deps)"
                          "(intake.mail/sync!)"
                          "(intake.mail/inbox)"
                          "(llm/call \"s\" \"c\")"]]
              (is (re-find #"read-only" (str (:err (eval code)))) code))
            (testing "reads still run"
              (is (contains? (eval "(dvergr.room/databases)") :ok) "a read is not refused")))))
      (is (empty? @touched) "no refused writer ran"))
    (testing "reading an open mail store is not an effect"
      (let [touched (atom [])]
        (with-redefs [dvergr.intake.mail/list-inbox (fn [& args] (swap! touched conj (vec args)) [])
                      dvergr.intake.mail/account-open? #(= :datahike-contact %)]
          (with-world-sandbox {:effects {:handlers [[:read-only]]}}
            (fn [{:keys [eval]}]
              (is (= [] (:ok (eval "(intake.mail/inbox)"))))
              (is (= [] (:ok (eval "(intake.mail/inbox {:limit 3})"))) "options as a map")
              (testing "but another account, however the options are given, opens a store"
                (is (re-find #"read-only" (str (:err (eval "(intake.mail/inbox {:account :other})")))))
                (is (re-find #"read-only" (str (:err (eval "(intake.mail/inbox :account :other)")))))))))
        (is (= [[] [:limit 3]] @touched) "the read gets the options decided on")))))

(deftest skill-writes-count-against-a-quota
  (with-sys-conn
    (fn [conn]
      (let [q (effects/quota! {:bytes 100})
            sci-ctx (sci/init {})]
        (agent-ns/add-skills-ns! sci-ctx conn (constantly {:handlers (effects/handlers [[:quota {:id q}]])}))
        (is (thrown-with-msg? Exception #"quota"
                              (sci/eval-string* sci-ctx (str "(dvergr.skills/author! \"s\" {:note \""
                                                             (apply str (repeat 200 "a")) "\"} \"b\")")))
            "the frontmatter is part of what is written")
        (is (thrown-with-msg? Exception #"quota"
                              (sci/eval-string* sci-ctx (str "(dvergr.skills/lift! \"s\" \""
                                                             (apply str (repeat 200 "a")) "\" \"b\")"))))
        (is (zero? (effects/quota-used q)))
        (effects/release! q)))))

(def ^:private gen-host
  (gen/elements ["a.com" "docs.a.com" "x.docs.a.com" "b.com" "c.org" "docs.c.org"]))

(defspec allowlists-meet-by-what-both-allow 300
  (prop/for-all [a (gen/set gen-host) b (gen/set gen-host) h gen-host]
                (= (effects/blocked-host? (effects/meet-hosts a b) h)
                   (and (effects/blocked-host? a h) (effects/blocked-host? b h)))))

(deftest a-task-reads-only-its-allowed-sources
  (let [get! (fn [specs url]
               (try (effects/perform! (constantly {:handlers (effects/handlers specs)})
                                      {:effect :http/request :resource {:method :get :url url}}
                                      (constantly :ok))
                    (catch clojure.lang.ExceptionInfo e (:by (ex-data e)))))]
    (is (= :ok (get! [[:allow-hosts #{"dust.tt"}]] "https://docs.dust.tt/x")))
    (is (= :not-allowed (get! [[:allow-hosts #{"dust.tt"}]] "https://evil.com/")))
    (testing "an allowlist and a blocklist together: allowed and not blocked"
      (is (= :blocked (get! [[:allow-hosts #{"simm.is" "dust.tt"}] [:deny-hosts #{"simm.is"}]] "https://simm.is/"))))
    (testing "two allowlists meet"
      (is (= [[:allow-hosts #{"docs.a.com"}]]
             (effects/normalize [[:allow-hosts #{"a.com"}] [:allow-hosts #{"docs.a.com" "b.com"}]]))))
    (testing "other effects pass"
      (is (= :ok (effects/perform! (constantly {:handlers (effects/handlers [[:allow-hosts #{"a.com"}]])})
                                   {:effect :fs/read :resource {:path "x"}} (constantly :ok)))))))
