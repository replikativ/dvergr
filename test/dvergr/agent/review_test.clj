(ns dvergr.agent.review-test
  "The review stage over retained worlds, with a scripted reviewer: checks
   first (a failed one never asks the reviewer), the verdict, spot checks,
   merges pinned to the reviewed state, escalation and the stored record."
  (:require [datahike.api :as dh]
            [dvergr.catalog.kb-pages-test :as kb-pages]
            [dvergr.system.rooms :as rooms]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.agent.review :as review]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [org.replikativ.spindel.core :as sp]
            [dvergr.agent.run :as run]
            [dvergr.resource :as resource]
            [dvergr.room.store :as store]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.catalog :as catalog]
            [dvergr.catalog.wiki-gen :as gen]
            [dvergr.catalog.wiki-v4 :as v4]
            [dvergr.catalog.workspace]
            [dvergr.discourse :as d]
            [muschel.fs]
            [dvergr.ops :as ops]
            [dvergr.rooms.forks]
            [dvergr.room.registry :as rreg]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.principal :as principal]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]))

(def ^:dynamic *daemon* nil)

(use-fixtures :once
  (fn [f]
    (let [prev-home (paths/home)]
      (paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-review-test-" (random-uuid)))
      (sdb/reset-conn!)
      (let [dmn (daemon/start! {:agents {}})]
        (try
          (binding [*daemon* dmn] (f))
          (finally
            (try (daemon/stop! dmn) (catch Exception _))
            (sdb/reset-conn!)
            (paths/set-home! prev-home)))))))

(defn- op [k args] (ops/invoke *daemon* principal/local-principal k args))

(defn- texts [m] (into {} (map (fn [[p t]] [p {:text t}])) m))

(defn- room-with-fork
  "A room with `base` files committed, and a fork of it with `changes`
   committed: `[parent fork-id]`."
  [slug base changes]
  (let [room (:id (op :room/create {:title slug :slug slug}))]
    (when (seq base) (op :room/write {:room room :files (texts base)}))
    (let [fork (:id (op :room/fork {:room room}))]
      (when (seq changes) (op :room/write {:room fork :files (texts changes)}))
      [(ops/resolve-room *daemon* room) fork])))

(defn- fork-id [fork] (:id (ops/resolve-room *daemon* fork)))

(defn- scripted
  "A reviewer that returns `verdict` and counts its calls."
  [calls verdict]
  (fn [input] (swap! calls conj input) verdict))

(def ^:private wiki-only
  {:review/checks [:fork/review-tier [:diff/only-paths ["wiki/"]]]
   :review/reviewer {:instructions "Approve faithful wiki updates."}})

(defn- escalations
  "The room's durable escalation messages."
  [room]
  (filter #(= :review/escalated (get-in % [:metadata :kind]))
          (d/messages (ops/resolve-room *daemon* (name (:id room))))))

(defn- raw-write!
  "Write a file into a room's workspace without committing it, as a tool does."
  [room path text]
  (muschel.fs/write-string! (dvergr.catalog.workspace/room-fs (ops/resolve-room *daemon* room)) path text false))

(deftest an-approved-world-is-merged-and-recorded
  (let [[parent fork] (room-with-fork "rv-approve" {"docs/a.md" "A."} {"wiki/a.md" "# A\n"})
        calls (atom [])
        rv (review/review! parent (fork-id fork) wiki-only
                           :reviewer (scripted calls {:review/verdict :approve :review/reasons ["faithful"]})
                           :invoke op)]
    (is (= :merged (:review/decision rv)))
    (is (= 1 (count @calls)))
    (is (= ["wiki/a.md"] (get-in (first @calls) [:review/diff :files])) "the reviewer sees what changed")
    (is (every? :pass? (:review/checks rv)))
    (is (= "# A\n" (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/wiki") "/wiki/a.md")) "the parent has the merged page")
    (is (= [rv] (review/reviews parent)) "the Review is stored in the room")
    (let [[listed] (op :review/list {:room "rv-approve"})]
      (is (= "merged" (:decision listed)))
      (is (= (:review/state-token rv) (:state_token listed)))
      (is (= "approve" (get-in listed [:verdict :review/verdict]))))))

(deftest a-failed-check-escalates-without-asking-the-reviewer
  (let [[parent fork] (room-with-fork "rv-scope" {} {"wiki/a.md" "# A\n" "schedules.edn" "{:every 1}"})
        calls (atom [])
        rv (review/review! parent (fork-id fork) wiki-only
                           :reviewer (scripted calls {:review/verdict :approve}))]
    (is (= :escalated (:review/decision rv)))
    (is (empty? @calls) "the reviewer is never asked")
    (is (= ["schedules.edn"] (get-in (second (:review/checks rv)) [:evidence :outside])))
    (is (some? (ops/resolve-room *daemon* fork)) "the world is kept for a person")
    (let [[m] (escalations parent)]
      (is (= (:review/id rv) (get-in m [:metadata :object :id])) "the message names the Review")
      (is (str/includes? (str (:content m)) "needs a person")))
    (is (instance? dvergr.discourse.Message (last (filter #(= :review/escalated (get-in % [:metadata :kind]))
                                                          (d/log (ops/resolve-room *daemon* (name (:id parent)))))))
        "a room message like any other: conversation listeners see it")
    (is (nil? (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/") "/wiki/a.md")) "nothing merged")
    (is (= 1 (count (op :review/list {:room "rv-scope" :decision "escalated"}))))))

(deftest the-reviewer-can-escalate
  (let [[parent fork] (room-with-fork "rv-esc" {} {"wiki/a.md" "# A\nThe river flows west.\n"})
        rv (review/review! parent (fork-id fork) wiki-only
                           :reviewer (scripted (atom []) {:review/verdict :escalate
                                                          :review/reasons ["contradicts the charter"]}))]
    (is (= :escalated (:review/decision rv)))
    (is (some #{"contradicts the charter"} (:review/reasons rv)))
    (is (= :escalate (get-in rv [:review/verdict :review/verdict])))))

(deftest spot-checks-sample-approved-reviews-deterministically
  (let [id (random-uuid)]
    (is (= (review/spot-checked? id {:rate 0.5 :seed 1}) (review/spot-checked? id {:rate 0.5 :seed 1})))
    (is (not (review/spot-checked? id {:rate 0.0 :seed 1})))
    (is (review/spot-checked? id {:rate 1.0 :seed 1}))
    (let [n (count (filter (fn [_] (review/spot-checked? (random-uuid) {:rate 0.1 :seed 3})) (range 2000)))]
      (is (< 120 n 280) (str n " of 2000 at rate 0.1"))))
  (let [[parent fork] (room-with-fork "rv-spot" {} {"wiki/a.md" "# A\n"})
        rv (review/review! parent (fork-id fork) (assoc wiki-only :review/spot-check {:rate 1.0 :seed 1})
                           :reviewer (scripted (atom []) {:review/verdict :approve}))]
    (is (= :escalated (:review/decision rv)))
    (is (:review/spot-check? rv))))

(deftest a-world-changed-after-review-is-not-merged
  (let [[parent fork] (room-with-fork "rv-race" {} {"wiki/a.md" "# A\n"})
        ;; the world changes while the reviewer reads it
        sneaky (fn [_] (op :room/write {:room fork :files (texts {"wiki/b.md" "# B\n"})})
                 {:review/verdict :approve})
        rv (review/review! parent (fork-id fork) wiki-only :reviewer sneaky :invoke op)]
    (is (= :escalated (:review/decision rv)))
    (is (some #(str/includes? % "changed since") (:review/reasons rv)) (pr-str (:review/reasons rv)))
    (is (nil? (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/") "/wiki/a.md")))))

(deftest on-fail-discard-and-propose
  (testing "a failed check discards the world under :review/on-fail :discard"
    (let [[parent fork] (room-with-fork "rv-discard" {} {"notes.md" "x"})
          rv (review/review! parent (fork-id fork) (assoc wiki-only :review/on-fail :discard)
                             :reviewer (scripted (atom []) {:review/verdict :approve}))]
      (is (= :discarded (:review/decision rv)))
      (is (empty? (escalations parent)))))
  (testing ":review/on-pass :propose leaves the merge to a person"
    (let [[parent fork] (room-with-fork "rv-propose" {} {"wiki/a.md" "# A\n"})
          rv (review/review! parent (fork-id fork) (assoc wiki-only :review/on-pass :propose)
                             :reviewer (scripted (atom []) {:review/verdict :approve}))]
      (is (= :escalated (:review/decision rv)))
      (is (nil? (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/") "/wiki/a.md")))))
  (testing "a spec that names a reviewer needs one"
    (let [[parent fork] (room-with-fork "rv-none" {} {"wiki/a.md" "# A\n"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names a reviewer"
                            (review/review! parent (fork-id fork) wiki-only))))))

(deftest the-wiki-v4-check-scores-the-world
  (let [w (gen/world 1)
        docs (into {} (map (fn [[p t]] [(subs p 1) t])) (gen/documents w))
        wiki (into {} (map (fn [[p t]] [(subs p 1) t])) (v4/reference-wiki w))
        spec {:review/checks [:fork/review-tier [:diff/only-paths ["wiki/"]] :wiki/v4]}]
    (testing "the reference wiki passes and is merged with no reviewer"
      (let [[parent fork] (room-with-fork "rv-v4-good" docs wiki)
            rv (review/review! parent (fork-id fork) spec)]
        (is (= :merged (:review/decision rv)) (pr-str (:review/checks rv)))
        (is (= 1.0 (get-in rv [:review/checks 2 :evidence :reward])))))
    (testing "an unquoted wiki fails the check"
      (let [unquoted (into {} (map (fn [[p t]] [p (str/replace t #"(?m)^> .*\n" "")])) wiki)
            [parent fork] (room-with-fork "rv-v4-bad" docs unquoted)
            rv (review/review! parent (fork-id fork) spec)]
        (is (= :escalated (:review/decision rv)))
        (is (some #{:every-claim-quoted?} (get-in rv [:review/checks 2 :evidence :failed])))))))

(deftest a-reviewer-must-approve-explicitly
  (doseq [[slug reviewer why] [["rv-nil" (fn [_] nil) "no verdict"]
                               ["rv-odd" (fn [_] {:review/verdict :sure}) "no verdict"]
                               ["rv-throw" (fn [_] (throw (ex-info "provider down" {}))) "failed: provider down"]]]
    (let [[parent fork] (room-with-fork slug {} {"wiki/a.md" "# A\n"})
          rv (review/review! parent (fork-id fork) wiki-only :reviewer reviewer)]
      (is (= :escalated (:review/decision rv)) slug)
      (is (some #(str/includes? % why) (:review/reasons rv)) (pr-str (:review/reasons rv)))
      (is (= [rv] (review/reviews parent)) "recorded")
      (is (nil? (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/") "/wiki/a.md"))))))

(deftest uncommitted-files-are-reviewed-and-pinned
  (testing "an uncommitted file under the declared paths is reviewed and merged"
    (let [[parent fork] (room-with-fork "rv-dirty" {} {})]
      (raw-write! fork "/wiki/a.md" "# A\n")
      (let [calls (atom [])
            rv (review/review! parent (fork-id fork) wiki-only :reviewer (scripted calls {:review/verdict :approve}) :invoke op)]
        (is (= :merged (:review/decision rv)) (pr-str rv))
        (is (= ["wiki/a.md"] (get-in (first @calls) [:review/diff :files])))
        (is (= "# A\n" (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/wiki") "/wiki/a.md"))))))
  (testing "an uncommitted write while the reviewer reads is not merged"
    (doseq [[slug invoke] [["rv-dirty-race" op] ["rv-dirty-race-direct" nil]]]
      (let [[parent fork] (room-with-fork slug {} {"wiki/a.md" "# A\n"})
            sneaky (fn [_] (raw-write! fork "/schedules.edn" "{:every 1}") {:review/verdict :approve})
            rv (review/review! parent (fork-id fork) wiki-only :reviewer sneaky :invoke invoke)]
        (is (= :escalated (:review/decision rv)) slug)
        (is (some #(str/includes? % "changed since") (:review/reasons rv)) (pr-str (:review/reasons rv)))
        (is (nil? (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/") "/schedules.edn")))))))

(deftest a-database-change-is-outside-the-declared-paths
  (let [changed? #'review/system-changed?]
    (is (changed? {:summary {:added-datoms 0 :removed-datoms 3}}) "retractions count")
    (is (changed? {:summary {:added-datoms 2}}))
    (is (not (changed? {:summary {:added-datoms 0 :removed-datoms 0}})))
    (is (changed? {:error "diff failed"}))
    (is (changed? {:intents [{:op :book}]}))
    (is (changed? {:something :unknown}) "unclear goes to a person")))

(deftest the-state-token-covers-uncommitted-names-contents-and-modes
  (let [[_ fork] (room-with-fork "rv-token" {} {})
        f #(ops/resolve-room *daemon* fork)
        token #(dvergr.rooms.forks/state-token (f))]
    (raw-write! fork "/wiki/a.md " "one")
    (let [t1 (token)]
      (is (= ["wiki/a.md "] (map :path (dvergr.rooms.forks/uncommitted (f)))) "the exact name, its space kept")
      (raw-write! fork "/wiki/a.md " "two")
      (let [t2 (token)]
        (is (not= t1 t2) "a changed content changes the token")
        (muschel.fs/chmod (dvergr.catalog.workspace/room-fs (f)) "/wiki/a.md " 0755)
        (is (not= t2 (token)) "a changed mode too")))))

(deftest an-escalation-reaches-room-message-listeners
  (let [[parent fork] (room-with-fork "rv-listen" {} {"notes.md" "x"})
        p (ops/resolve-room *daemon* (name (:id parent)))
        seen (promise)]
    (d/on-each-message p (fn [m] (when (= :review/escalated (get-in m [:metadata :kind])) (deliver seen m))))
    (let [rv (review/review! parent (fork-id fork) {:review/checks [[:diff/only-paths ["wiki/"]]]})
          m (deref seen 10000 nil)]
      (is (= :escalated (:review/decision rv)))
      (is (some? m) "on-each-message sees the escalation")
      (is (= (:review/id rv) (get-in m [:metadata :object :id]))))))

(deftest sibling-worlds-reviewed-in-turn-both-merge
  ;; simmis: fork A, review it, post; fork B (it inherits A's Review), review,
  ;; post; merge A, then B. B's merge replayed the inherited Review and failed
  ;; on its unique id, leaving B half-released
  (let [room (:id (op :room/create {:title "rv-sib" :slug "rv-sib"}))
        r #(ops/resolve-room *daemon* room)
        spec {:review/checks [:fork/review-tier] :review/on-pass :propose}
        post! #(d/post! (r) (d/message :someone (d/room-target (r)) % nil {:role :system}))
        a (:id (op :room/fork {:room room}))
        _ (op :room/write {:room a :files (texts {"wiki/a.md" "a\n"})})
        ra (review/review! (r) (fork-id a) spec)
        _ (post! "after A")
        b (:id (op :room/fork {:room room}))
        _ (op :room/write {:room b :files (texts {"wiki/b.md" "b\n"})})
        rb (review/review! (r) (fork-id b) spec)
        _ (post! "after B")]
    (is (:merged (op :room/merge {:room a :expect-state (:review/state-token ra)})))
    (is (:merged (op :room/merge {:room b :expect-state (:review/state-token rb)})))
    (is (= {"/wiki/a.md" "a\n" "/wiki/b.md" "b\n"} (catalog/read-tree (r) "/wiki")))
    (is (= #{(:review/id ra) (:review/id rb)} (set (map :review/id (review/reviews (r)))))
        "each Review once")))

(deftest kb-review-reads-the-merge-base-and-composes-with-paths
  (let [room (:id (op :room/create {:title "rv-kb" :slug "rv-kb"}))
        parent (ops/resolve-room *daemon* room)
        rid (:room/id (sdb/room-by-slug "rv-kb"))
        registry-id (binding [ec/*execution-context* (:ctx parent)]
                      (let [conn (rooms/room-kb-conn rid)]
                        (dh/transact conn kb-pages/schema)
                        (dh/transact conn (mapv #(cond-> (update % :db/id + 10000)
                                                   (:block/parent %) (update :block/parent + 10000))
                                                kb-pages/fixture))
                        (:system/id (:system (first (sdb/systems-for-room-of-type rid :kb))))))
        fork-name (:id (op :room/fork {:room room}))
        fork (ops/resolve-room *daemon* fork-name)
        spec {:review/checks [[:diff/only-paths ["wiki/"]]
                              [:diff/only-kb-pages {:system registry-id
                                                    :kinds #{:wiki/atom :wiki/overview :wiki/index}}]]
              :review/on-pass :propose}]
    (binding [ec/*execution-context* (:ctx parent)]
      (dh/transact (rooms/room-kb-conn rid) [{:db/id 10300 :S.Page/title "Concurrent notes" :S.Page/kind :note}]))
    (binding [ec/*execution-context* (:ctx fork)]
      (dh/transact (rooms/room-kb-conn rid) [{:db/id 10104 :block/content "<p>Edited</p>" :block/references 10100}]))
    (let [rv (review/review! parent (:id fork) spec)]
      (is (every? :pass? (:review/checks rv)) (pr-str (:review/checks rv))))
    (binding [ec/*execution-context* (:ctx fork)]
      (dh/transact (rooms/room-kb-conn rid) [{:db/id 10100 :grant/permission :owner}]))
    (let [rv (review/review! parent (:id fork) spec)]
      (is (not (get-in rv [:review/checks 1 :pass?])))
      (is (= [{:entity 10100 :attribute :grant/permission}]
             (get-in rv [:review/checks 1 :evidence :offenders]))))
    (is (not (:pass? (review/run-check (second (:review/checks spec))
                                       {:fork fork :other-systems ["another-kb"]}))))))
(defn- model-review! [slug answer & {:keys [propose? attack? deny-merge? exhaust?]}]
  (let [[parent world] (room-with-fork slug {"docs/a.md" "A."} {"wiki/a.md" "# A\n"})
        fork (ops/resolve-room *daemon* world)
        spec (cond-> (assoc-in wiki-only [:review/reviewer :model] "claude-haiku-4-5")
               propose? (assoc :review/on-pass :propose))
        _ (resource/mint! parent {:id (random-uuid) :resources {resource/microdollars 1000000M}})
        calls (atom [])
        authorized (atom [])
        dmn (assoc-in *daemon* [:config :auth :authorize]
                      (fn [p request]
                        (swap! authorized conj [p request])
                        (not (and deny-merge? (= :room/merge (:op request))))))
        state #(binding [ec/*execution-context* (:ctx fork)] (dvergr.rooms.forks/state-token fork))
        before (state)
        reviewer (review/model-reviewer dmn parent (:id fork) spec {:principal/delegation {:id "delegation-1"}})]
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  chat-agent/messages->api-format (fn [messages _ _] messages)
                  model-chat/chat
                  (fn [messages opts]
                    (swap! calls conj {:messages messages :opts opts})
                    (if (or exhaust? (and attack? (= 1 (count @calls))))
                      {:content "" :tool-calls [{:id (str "write-" (count @calls)) :name "write_file"
                                                 :input {:path "/wiki/a.md" :content "corrupted"}}]
                       :usage (if exhaust? {:input-tokens 1000000 :output-tokens 1000000}
                                  {:input-tokens 100 :output-tokens 20})
                       :stop-reason :tool-use}
                      {:content answer :tool-calls nil :usage {:input-tokens 100 :output-tokens 20}
                       :stop-reason :end-turn}))]
      (let [rv (review/review! parent (:id fork) spec :reviewer reviewer)
            runs (run/runs parent)
            [r] runs]
        (is (= 1 (count runs)) "the reviewer is its own durable Run")
        (is (= (if exhaust? :waiting :completed) (:run/status r)))
        (is (= :discard (:run/settlement-policy r)))
        (is (= :discarded (:run/settlement-status r)))
        (is (nil? (binding [ec/*execution-context* (:ctx parent)] (rreg/lookup (:run/world r))))
            "the reviewer world is released")
        (when exhaust? (is (= 1 (count @calls)) "the reviewer stops at its budget limit"))
        (is (every? #(empty? (get-in % [:opts :tools])) @calls) "the model has no callable world tools")
        (let [messages (:messages (first @calls))
              task (some #(when (= :user (or (:role %) (:message/role %)))
                            (or (:content %) (:message/content %))) messages)
              evidence (edn/read-string {:default (fn [_ value] value)} task)]
          (is (= "# A\n" (get-in evidence [:review/files "wiki/a.md" :text])))
          (is (every? :pass? (:review/checks evidence)))
          (is (map? (:review/fork-diff evidence)))
          (is (= "Approve faithful wiki updates." (:review/instructions evidence))))
        (when attack? (is (= 2 (count @calls)) "the unavailable write tool is refused"))
        (when propose?
          (is (= before (state)) "the reviewer leaves the retained fork's state token unchanged")
          (is (= {"/wiki/a.md" "# A\n"} (catalog/read-tree fork "/wiki"))))
        (is (= (if (or propose? (not= :approve (get-in rv [:review/verdict :review/verdict]))
                       (seq (get-in rv [:review/verdict :review/concerns])))
                 #{:room/diff :room/review}
                 #{:room/diff :room/review :room/merge})
               (set (map (comp :op second) @authorized))))
        (doseq [[p _] @authorized]
          (is (= (name (:id fork)) (:principal/room p)))
          (is (= #{"room_diff" "room_review" "room_merge"} (:principal/tools p)))
          (is (= {:id "delegation-1"} (:principal/delegation p))))
        (when (and (not propose?) (empty? (get-in rv [:review/verdict :review/concerns])) (= :approve (get-in rv [:review/verdict :review/verdict])) (not deny-merge?))
          (is (= (:id parent)
                 (some (fn [[_ request]]
                         (when (= :merge (:action request))
                           (keyword (get-in request [:resolved :room])))) @authorized)))
          (is (= (name (:id fork))
                 (some (fn [[_ request]]
                         (when (= :merge (:action request)) (get-in request [:resolved :via-room]))) @authorized))))
        (when-let [[p request] (first @authorized)]
          (is (false? (:allow? (principal/decide dmn p request))) "the reviewer token is revoked"))
        rv))))

(deftest model-approval-merges
  (let [rv (model-review! "rv-model-approve"
                          (pr-str {:review/verdict :approve :review/reasons ["faithful"] :review/concerns []}))]
    (is (= :merged (:review/decision rv)) (pr-str rv))))

(deftest model-escalation-keeps-its-reasons
  (let [rv (model-review! "rv-model-escalate"
                          (pr-str {:review/verdict :escalate :review/reasons ["contradicts the source"]
                                   :review/concerns ["unsupported claim"]}))]
    (is (= :escalated (:review/decision rv)))
    (is (some #{"contradicts the source"} (:review/reasons rv)))
    (is (= ["unsupported claim"] (get-in rv [:review/verdict :review/concerns])))))

(deftest model-garbage-escalates
  (doseq [[suffix answer] [["garbage" "looks good"]
                           ["trailing" "{:review/verdict :approve :review/reasons [] :review/concerns []} garbage"]
                           ["sentinel" "{:review/verdict :approve :review/reasons [] :review/concerns []} :dvergr.agent.review/eof arbitrary trailing text"]
                           ["shape" "{:review/verdict :approve :review/reasons \"yes\" :review/concerns []}"]]]
    (is (= :escalated (:review/decision (model-review! (str "rv-model-" suffix) answer))))))

(deftest model-cannot-write-the-reviewed-world
  (is (= :escalated (:review/decision
                     (model-review! "rv-model-readonly"
                                    (pr-str {:review/verdict :approve :review/reasons [] :review/concerns []})
                                    :propose? true :attack? true)))))

(deftest model-merge-respects-the-host-authorizer
  (is (= :escalated (:review/decision
                     (model-review! "rv-model-denied"
                                    (pr-str {:review/verdict :approve :review/reasons [] :review/concerns []})
                                    :deny-merge? true)))))

(deftest failed-checks-never-call-the-model-and-revoke-the-token
  (let [[parent world] (room-with-fork "rv-model-check-fails" {} {"schedules.edn" "{}"})
        spec (assoc-in wiki-only [:review/reviewer :model] "claude-haiku-4-5")
        mint principal/mint-run-token!
        token (atom nil)]
    (with-redefs [principal/mint-run-token! (fn [opts] (reset! token (mint opts)))
                  model-chat/chat (fn [& _] (throw (AssertionError. "model must not be called")))]
      (let [reviewer (review/model-reviewer *daemon* parent (fork-id world) spec {})
            rv (review/review! parent (fork-id world) spec :reviewer reviewer)]
        (is (= :escalated (:review/decision rv)))
        (is (empty? (run/runs parent)))
        (is (nil? (principal/run-token-principal @token)))))))

(deftest an-exhausted-model-reviewer-discards-its-world
  (let [rv (model-review! "rv-model-exhausted" "" :exhaust? true :propose? true)]
    (is (= :escalated (:review/decision rv)))
    (is (some #(str/includes? % "Reviewer Run did not complete") (:review/reasons rv)))))

(deftest approval-with-concerns-needs-a-person
  (let [rv (model-review! "rv-model-concerns"
                          (pr-str {:review/verdict :approve :review/reasons ["mostly faithful"]
                                   :review/concerns ["verify the date"]}))]
    (is (= :escalated (:review/decision rv)))
    (is (some #{"verify the date"} (:review/reasons rv)))))

(deftest model-reviewer-debits-the-payer-and-returns-the-remainder
  (doseq [parent? [false true]]
    (let [[room world] (room-with-fork (str "rv-paid-" parent?) {} {"wiki/a.md" "# A\n"})
          spec (-> wiki-only
                   (assoc :review/on-pass :propose)
                   (assoc-in [:review/reviewer :model] "claude-haiku-4-5"))
          parent-handle (atom nil)
          seen (atom nil)]
      (binding [ec/*execution-context* (:ctx room)]
        (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 1000000M}})
        (when parent?
          (reset! parent-handle
                  (program/hire! room
                                 (roster/make-agent (roster/make-roster {:id :payer})
                                                    {:id :payer :program {:kind :echo :delay-ms 600000}})
                                 :payer {:task "pay for review" :settlement :discard
                                         :resources {resource/microdollars 750000M}})))
        (let [parent-id (some-> @parent-handle program/run-id)]
          (try
            (with-redefs [providers/ensure-initialized! (constantly nil)
                          chat-agent/messages->api-format (fn [messages _ _] messages)
                          dvergr.chat.accounting/get-model-pricing (fn [_] {:input 1 :output 2})
                          model-chat/chat
                          (fn [_ _]
                            (let [r (first (remove #(= parent-id (:run/id %)) (run/runs room)))]
                              (reset! seen {:id (:run/id r)
                                            :wallet (resource/wallet-microdollars room (:run/id r))
                                            :payer (if parent-id
                                                     (resource/wallet-microdollars room parent-id)
                                                     (get (resource/balance room) resource/microdollars))}))
                            {:content "{:review/verdict :approve :review/reasons [] :review/concerns []}"
                             :usage {:input-tokens 100 :output-tokens 20} :stop-reason :end-turn})]
              (let [reviewer (review/model-reviewer *daemon* room (fork-id world) spec
                                                    (cond-> {} parent-id (assoc :principal/run parent-id)))
                    rv (review/review! room (fork-id world) spec :reviewer reviewer)
                    id (:id @seen)
                    receipt #(store/-resource-receipt (:store room) %)]
                (is (= :escalated (:review/decision rv)))
                (is (= 500000M (:wallet @seen)) "the model runs with its allocated wallet")
                (is (= (if parent? 250000M 500000M) (:payer @seen)) "the intended payer funded it")
                (is (= parent-id (:run/parent (run/run room id))))
                (when parent?
                  (is (= 250000M (get (resource/balance room) resource/microdollars))
                      "the room root is not charged for its child Run’s review"))
                (is (= 0M (resource/wallet-microdollars room id)))
                (is (= (if parent? 749860M 999860M)
                       (if parent-id (resource/wallet-microdollars room parent-id)
                           (get (resource/balance room) resource/microdollars))))
                (is (= {resource/microdollars 499860M} (:resources (receipt (resource/return-id id))))
                    "the real model usage debits 140 and the unused allocation returns")
                (is (= {resource/microdollars 500000M} (:resources (receipt (resource/allocation-id id)))))))
            (finally
              (when-let [handle @parent-handle]
                (program/cancel! room handle)
                (deref handle 10000 nil)))))))))

(defn- await-condition [f]
  (let [deadline (+ (System/nanoTime) 30000000000)]
    (loop []
      (cond (f) true
            (> (System/nanoTime) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

(deftest a-blocked-reviewer-is-cancelled-and-discarded-after-the-caller-leaves
  (doseq [interrupt? [false true]]
    (let [[room world] (room-with-fork (str "rv-blocked-" interrupt?) {} {"wiki/a.md" "# A\n"})
          fork (ops/resolve-room *daemon* world)
          state #(binding [ec/*execution-context* (:ctx fork)] (dvergr.rooms.forks/state-token fork))
          before (state)
          spec (assoc wiki-only :review/reviewer {:instructions "Review" :model "claude-haiku-4-5"
                                                  :timeout-ms (if interrupt? 30000 2000)})
          entered (promise)
          interrupted (promise)
          release (promise)
          finished (promise)]
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 1000000M}})
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat
                    (fn [_ _]
                      (deliver entered true)
                      ;; A provider can take time to acknowledge cancellation. Keep
                      ;; it physically live until the test releases it.
                      (loop []
                        (when-not (try @release true
                                       (catch InterruptedException _ (deliver interrupted true) false))
                          (recur)))
                      {:content "{:review/verdict :approve :review/reasons [] :review/concerns []}"
                       :usage {:input-tokens 100 :output-tokens 20} :stop-reason :end-turn})]
        (let [reviewer (review/model-reviewer *daemon* room (:id fork) spec {})
              caller (Thread. ^Runnable (bound-fn []
                                          (deliver finished (review/review! room (:id fork) spec :reviewer reviewer))))]
          (try
            (.start caller)
            (is (= true (deref entered 10000 nil)) "the scripted model is blocked")
            (when interrupt? (.interrupt caller))
            (let [rv (deref finished (if interrupt? 2000 3000) nil)
                  [r] (run/runs room)]
              (is (= :escalated (:review/decision rv)) "the boundary returns without provider quiescence")
              (when-not interrupt?
                (is (some #(str/includes? % "timed out") (:review/reasons rv))))
              (is (= true (deref interrupted 2000 nil)) "the Run interrupts its provider worker")
              (is (run/cancel-requested? (:run/id r)))
              (is (not= :discarded (:run/settlement-status r)) "discard waits for quiescence")
              (deliver release true)
              (is (await-condition #(= :discarded (:run/settlement-status (run/run room (:run/id r))))))
              (let [settled (run/run room (:run/id r))]
                (is (= :cancelled (:run/status settled)))
                (is (= :discard (:run/settlement-policy settled)))
                (is (nil? (binding [ec/*execution-context* (:ctx room)] (rreg/lookup (:run/world settled))))))
              (is (= before (state)) "the retained fork is untouched"))
            (finally (deliver release true) (.join caller 10000))))))))

(deftest a-parent-funded-timeout-waits-to-return-funds-until-the-provider-quiesces
  (let [[room world] (room-with-fork "rv-parent-timeout" {} {"wiki/a.md" "# A\n"})
        spec (assoc wiki-only :review/reviewer {:instructions "Review" :model "claude-haiku-4-5"
                                                :timeout-ms 2000})
        team (roster/make-agent (roster/make-roster {:id :payer})
                                {:id :payer :program {:kind :echo}})
        finish-parent (promise)
        parent-finished (promise)
        parent-supervisor (promise)
        entered (promise)
        interrupted (promise)
        release (promise)
        finished (promise)
        execute #'program/execute-agent-program
        original @execute]
    (binding [ec/*execution-context* (:ctx room)]
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 1000000M}})
      (with-redefs-fn
        {execute (fn [control work id chat agent task trigger supervisor]
                   (if (= :payer (:agent/id agent))
                     (sp/spin
                      (deliver parent-supervisor supervisor)
                      (sp/await
                       (:done (#'program/start-worker! supervisor
                                                       #(do @finish-parent
                                                            (deliver parent-finished true)
                                                            {:dvergr.agent.program/status :completed
                                                             :dvergr.agent.program/value :done})))))
                     (original control work id chat agent task trigger supervisor)))
         #'providers/ensure-initialized! (constantly nil)
         #'chat-agent/messages->api-format (fn [messages _ _] messages)
         #'dvergr.chat.accounting/get-model-pricing (fn [_] {:input 1 :output 2})
         #'model-chat/chat
         (fn [_ _]
           (deliver entered true)
           (loop []
             (when-not (try @release true
                            (catch InterruptedException _ (deliver interrupted true) false))
               (recur)))
           {:content "{:review/verdict :approve :review/reasons [] :review/concerns []}"
            :usage {:input-tokens 100 :output-tokens 20} :stop-reason :end-turn})}
        (fn []
          (let [parent (program/hire! room team :payer
                                      {:task :pay :settlement :discard
                                       :resources {resource/microdollars 750000M}})
                parent-id (program/run-id parent)
                reviewer (review/model-reviewer *daemon* room (fork-id world) spec
                                                {:principal/run parent-id})
                caller (Thread. ^Runnable (bound-fn []
                                            (deliver finished
                                                     (review/review! room (fork-id world) spec :reviewer reviewer))))
                returns (atom [])
                return-resources resource/return!]
            (try
              (with-redefs [resource/return!
                            (fn [& args]
                              (let [result (apply return-resources args)]
                                (swap! returns conj (second args))
                                result))]
                (.start caller)
                (is (= true (deref entered 10000 nil)))
                (let [rv (deref finished 3000 nil)
                      child-id (:run/id (first (remove #(= parent-id (:run/id %)) (run/runs room))))
                      receipt #(store/-resource-receipt (:store room) (resource/return-id %))]
                  (is (= :escalated (:review/decision rv)))
                  (is (some #(str/includes? % "timed out") (:review/reasons rv)))
                  (is (= true (deref interrupted 2000 nil)))
                  (deliver finish-parent true)
                  (is (= true (deref parent-finished 2000 nil)) "the parent body has finished")
                  (is (await-condition #(some-> @parent-supervisor :state deref :sealed?))
                      "the parent has sealed its supervisor")
                  (is (= ::pending (deref parent 200 ::pending)) "parent settlement waits for its owned reviewer")
                  (is (nil? (receipt parent-id)))
                  (is (= 250000M (get (resource/balance room) resource/microdollars)))
                  (is (= 250000M (resource/wallet-microdollars room parent-id)))
                  (is (= 500000M (resource/wallet-microdollars room child-id)))
                  (deliver release true)
                  (is (= :completed (:run/status (deref parent 10000 nil))))
                  (is (await-condition #(= :discarded (:run/settlement-status (run/run room child-id)))))
                  (is (= :cancelled (:run/status (run/run room child-id))))
                  (let [refund (get-in (receipt child-id) [:resources resource/microdollars])
                        balance (resource/balance room)]
                    (is (<= 499860M refund 500000M) "only any recorded provider usage is spent")
                    (is (= (+ 500000M refund) (get balance resource/microdollars)))
                    (is (= (+ 250000M refund) (get-in (receipt parent-id) [:resources resource/microdollars])))
                    (is (= {parent-id 1 child-id 1} (frequencies @returns)) "each Run returns its funds exactly once"))
                  (doseq [r (run/runs room)]
                    (is (= {} (resource/run-balance room (:run/id r))) "every Run wallet is empty"))))
              (finally
                (deliver finish-parent true)
                (deliver release true)
                (.join caller 10000)
                (program/cancel! room parent)
                (deref parent 10000 nil)))))))))
