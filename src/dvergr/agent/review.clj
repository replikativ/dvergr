(ns dvergr.agent.review
  "Review of a retained world: the stage between a work Run that kept its world
   (`:settlement :review`) and adopting it. Deterministic checks first, then an
   optional reviewer, then a decision: merge (pinned to the state that was
   reviewed), escalate to a person, or discard. Every review leaves one
   immutable Review record in the room's store (`store/PReviewStore`) and an
   escalation posts a message of kind `:review/escalated` to the room.

   A spec (portable data, e.g. on a workflow bundle):

     {:review/checks     [:fork/review-tier [:diff/only-paths [\"wiki/\"]] [:diff/max-files 40] :wiki/v4]
      :review/reviewer   {:instructions \"…\"}   ; optional: a reviewer is asked
      :review/on-pass    :merge                  ; | :propose (escalate with the verdict)
      :review/on-fail    :escalate               ; | :discard
      :review/spot-check {:rate 0.1 :seed 7}}    ; approved reviews sampled for a person

   Checks (`run-check`, open for extension): `:fork/review-tier` (no conflicts),
   `[:diff/only-paths prefixes]` (every changed or uncommitted file under one
   of the prefixes, and no other system changed: a workflow writes only what
   it declares), `[:diff/max-files n]`, and checkers registered by catalog
   workflows (`:wiki/v4`). A failed check escalates (or discards) without
   asking the reviewer.

   The reviewer is a function the host passes (`:reviewer`): `(fn [input] verdict)`
   with `input` `{:review/world :review/state-token :review/diff :review/checks
   :review/instructions}` and `verdict` `{:review/verdict :approve|:escalate
   :review/reasons [..] :review/concerns [..]}`. A scripted one in tests; a
   model-backed one runs as its own Run (it never edits the world).

   `review!` blocks (diffs, checks, a merge): call it at a boundary or from a
   worker, never inside a Spin."
  (:require [clojure.string :as str]
            [dvergr.discourse :as d]
            [dvergr.room.store :as store]
            [dvergr.rooms.forks :as forks]
            [dvergr.room.registry :as rreg]
            [hasch.core :as hasch]
            [org.replikativ.spindel.engine.core :as ec]))

(def default-spec
  {:review/checks [:fork/review-tier]
   :review/on-pass :merge
   :review/on-fail :escalate})

;; ============================================================================
;; Checks
;; ============================================================================

(defn- check-key [check] (if (vector? check) (first check) check))

(defmulti run-check
  "Run one check of a spec on a retained world: `{:pass? bool :evidence data}`.
   `ctx` is `{:fork room :review forks/review-map :files [paths] :other-systems
   [ids]}`."
  (fn [check _ctx] (check-key check)))

(defmethod run-check :default [check _]
  {:pass? false :evidence {:unknown-check (pr-str check)}})

(defmethod run-check :fork/review-tier [_ {:keys [review]}]
  {:pass? (not= :conflict (:tier review)) :evidence {:tier (:tier review)}})

(defn- under? [path prefixes]
  (let [p (str/replace (str path) #"^/+" "")]
    (some #(str/starts-with? p (str/replace (str %) #"^/+" "")) prefixes)))

(defmethod run-check :diff/only-paths [[_ prefixes] {:keys [files other-systems]}]
  (let [outside (vec (remove #(under? % prefixes) files))]
    {:pass? (and (empty? outside) (empty? other-systems))
     :evidence (cond-> {}
                 (seq outside) (assoc :outside (vec (take 20 outside)))
                 (seq other-systems) (assoc :other-systems (vec other-systems)))}))

(defmethod run-check :diff/max-files [[_ n] {:keys [files]}]
  {:pass? (<= (count files) n) :evidence {:files (count files) :max n}})

(defn- system-changed?
  "Whether a system other than files changed, conservatively: a database delta
   with any datom added or retracted (or any count at all), a book with
   intents, a failed diff. Anything unclear counts as a change: it goes to a
   person."
  [delta]
  (cond
    (nil? delta) false
    (:error delta) true
    (contains? delta :intents) (boolean (seq (:intents delta)))
    (contains? delta :summary) (boolean (some #(and (number? %) (pos? %)) (vals (:summary delta))))
    :else true))

(defn- changed-files
  "Every path the fork changed, committed (a workspace diff's files; a rename
   or copy names both) or not, and the systems other than files it changed."
  [fork {:keys [diff]}]
  (let [file-deltas (filter (fn [[_ delta]] (contains? delta :files)) diff)
        others (for [[sid delta] diff
                     :when (and (not (contains? delta :files)) (system-changed? delta))]
                 (str sid))]
    {:files (vec (distinct (concat (for [[_ delta] file-deltas
                                         {:keys [path]} (:files delta)
                                         p (str/split (str path) #"\t")]
                                     p)
                                   (map :path (forks/uncommitted fork)))))
     :other-systems (vec (sort others))}))

;; ============================================================================
;; The review
;; ============================================================================

(defn spot-checked?
  "Whether review `id` is sampled for a person under `{:rate :seed}`: a
   deterministic function of the id and the seed."
  [id {:keys [rate seed]}]
  (and rate (pos? rate)
       (< (/ (double (mod (hash (str id "/" seed)) 10000)) 10000.0) (double rate))))

(defn policy-id
  "The content id of a spec: which policy a decision was made under."
  [spec]
  (str (hasch/uuid spec)))

(defn- room-arg
  "A room id as ops takes it: a keyword id without its colon."
  [id]
  (if (keyword? id) (subs (str id) 1) (str id)))

(defn- decide-merge!
  "Merge `fork` if it is still in `state`: through `invoke` (`(invoke op args)`,
   e.g. `ops/invoke` as the reviewer's principal) when given, else directly."
  [fork state invoke]
  (if invoke
    (try (if (:merged (invoke :room/merge {:room (room-arg (:id fork)) :expect-state state}))
           {:ok? true}
           {:ok? false :error "the merge did not happen"})
         (catch Throwable t {:ok? false :error (.getMessage t)}))
    (forks/merge! fork :expect-state state)))

(defn- decide-discard! [fork invoke]
  (if invoke
    (try (if (:discarded (invoke :room/discard {:room (room-arg (:id fork))}))
           {:ok? true}
           {:ok? false :error "the discard did not happen"})
         (catch Throwable t {:ok? false :error (.getMessage t)}))
    (forks/discard! fork :review-rejected)))

(defn- record! [room review]
  (when-let [st (:store room)]
    (when (satisfies? store/PReviewStore st)
      (store/-store-review! st (store/conversation-id room) review)))
  review)

(defn- escalate! [room review]
  (try
    ;; a room message like any other (`d/message`): conversation listeners
    ;; (`on-each-message`, mirrors, a chat projection) see it
    (d/post! room (d/message :reviewer (d/room-target room)
                             (str "Review of " (:review/world review) " needs a person: "
                                  (str/join "; " (:review/reasons review))
                                  " (review_list, decision escalated)")
                             nil
                             (cond-> {:kind :review/escalated
                                      :object {:kind :review :id (:review/id review)}}
                               (:review/run-id review) (assoc :run-id (:review/run-id review)))))
    (catch Throwable _ nil))
  review)

(defn review!
  "Review the retained world `world` (a fork id) of `room` under `spec`
   (merged over `default-spec`). Options: `:run-id` (the work Run),
   `:reviewer` (a function, see the ns doc; required when the spec names a
   reviewer), `:invoke` (merge/discard through ops as a principal), `:now`.
   Returns the stored Review."
  [room world spec & {:keys [run-id reviewer invoke now]}]
  ;; the room's context: its registry, its forks (never a caller's)
  (binding [ec/*execution-context* (:ctx room)]
    (let [spec (merge default-spec spec)
          _ (when (and (:review/reviewer spec) (not reviewer))
              (throw (ex-info "The spec names a reviewer, but none was given" {:type ::no-reviewer})))
          fork (or (rreg/lookup world)
                   (throw (ex-info (str "No retained world " world) {:type ::no-world :world world})))
          rev (or (forks/review fork)
                  (throw (ex-info (str world " is not a reviewable fork") {:type ::not-reviewable :world world})))
          state (:state rev)
          ctx (merge {:fork fork :review rev} (changed-files fork rev))
          checks (vec (for [c (:review/checks spec)]
                        (merge {:check c} (try (run-check c ctx)
                                               (catch Throwable t {:pass? false :evidence {:error (.getMessage t)}})))))
          failed (remove :pass? checks)
          id (random-uuid)
          asked? (and (empty? failed) (:review/reviewer spec))
          verdict (when asked?
                    (try
                      (let [v (reviewer {:review/world (room-arg world) :review/state-token state
                                         :review/diff (select-keys ctx [:files :other-systems])
                                         :review/checks checks
                                         :review/instructions (get-in spec [:review/reviewer :instructions])})]
                        (if (and (map? v) (#{:approve :escalate} (:review/verdict v)))
                          v
                          {:review/verdict :escalate :review/reasons ["the reviewer gave no verdict"]}))
                      (catch Throwable t
                        {:review/verdict :escalate :review/reasons [(str "the reviewer failed: " (.getMessage t))]})))
          ;; with a reviewer, only its explicit approval approves
          approved? (and (empty? failed)
                         (if asked? (= :approve (:review/verdict verdict)) true))
          spot? (and approved? (spot-checked? id (:review/spot-check spec)))
          base {:review/id id :review/world (room-arg world) :review/state-token state
                :review/checks checks :review/policy (policy-id spec)
                :review/reviewed-at (or now (java.util.Date.))}
          base (cond-> base
                 run-id (assoc :review/run-id run-id)
                 verdict (assoc :review/verdict (-> (select-keys verdict [:review/verdict :review/reasons :review/concerns])
                                                    (update :review/reasons #(vec (map str %)))
                                                    (update :review/concerns #(vec (map str %)))))
                 spot? (assoc :review/spot-check? true))
          escalated (fn [reasons] (escalate! room (record! room (assoc base :review/decision :escalated
                                                                       :review/reasons (vec reasons)))))]
      (cond
        (seq failed)
        (let [reasons (mapv #(str "check failed: " (pr-str (:check %))) failed)]
          (if (= :discard (:review/on-fail spec))
            (let [{:keys [ok? error]} (decide-discard! fork invoke)]
              (if ok?
                (record! room (assoc base :review/decision :discarded :review/reasons reasons))
                (escalated (conj reasons (str "discard failed: " error)))))
            (escalated reasons)))

        (not approved?)
        (escalated (into ["the reviewer escalated"] (:review/reasons verdict)))

        spot?
        (escalated ["sampled for a person (spot check)"])

        (= :propose (:review/on-pass spec))
        (escalated (into ["approved; merging is left to a person"] (:review/reasons verdict)))

        :else
        (let [{:keys [ok? error]} (decide-merge! fork state invoke)]
          (if ok?
            (record! room (assoc base :review/decision :merged))
            (escalated [(str "merge failed: " error)])))))))

(defn reviews
  "The Reviews of `room`, newest first (`:decision`, `:run-id`, `:limit`)."
  ([room] (reviews room {}))
  ([room opts]
   (let [st (:store room)]
     (if (and st (satisfies? store/PReviewStore st))
       (store/-list-reviews st (store/conversation-id room) opts)
       []))))
