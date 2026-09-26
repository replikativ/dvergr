(ns dvergr.agent.spend
  "What an Attempt cost, in one shape, whatever produced the usage.

   Usage reaches a receipt in two shapes. An LLM program's Run carries the chat
   budget (`chat.context/get-budget`: `:used` in microdollars, `:by-type` in
   natural units); a protocol Run (a benchmark's conversation or model step)
   carries what its provider counted, typically raw token counts, per role.
   Neither says on its own what the Attempt cost. `spend` folds either into

     {:microdollars N   ;; priced with the registry at the time of the Attempt
      :tokens {:input N :output N :cache-read N :cache-write N}
      :by-model {model-id {:microdollars N :tokens {...}}}
      :priced? bool}    ;; false when a model has no registry price

   so a Scorecard can put a bill next to a reward and a leaderboard can rank by
   cost per solved task. Prices are the registry's at the time of the Attempt;
   the receipt keeps the tokens so a later price can be applied again.

   A subscription model (Claude Code, Codex) costs nothing per call, which
   says nothing about what the work is worth. Its registry entry names the API
   model its tokens correspond to (`:list-price-of`), and its spend also
   carries `:notional-microdollars`: the same tokens at that model's list
   price. For any other model the notional cost is the cost. So cost per
   solved task compares across a subscription, a customer's own key and a
   paid run; the bill (`:microdollars`) stays what was paid."
  (:require [dvergr.chat.accounting :as acct]
            [dvergr.model.registry :as registry]))

(def ^:private token-keys
  ;; provider usage key -> canonical token key -> accounting resource type
  {:input-tokens          [:input :input-tokens]
   :output-tokens         [:output :output-tokens]
   :cache-read-tokens     [:cache-read :cache-read-tokens]
   :cache-creation-tokens [:cache-write :cache-write-tokens]
   :cache-write-tokens    [:cache-write :cache-write-tokens]
   :reasoning-output-tokens [:reasoning :reasoning-output-tokens]})

(defn- usage-map?
  "A provider usage record: token counts keyed by kind."
  [x]
  (and (map? x) (some #(contains? x %) (keys token-keys))))

(defn- tokens-of [usage]
  (reduce-kv (fn [acc k [canonical _]]
               (if-let [n (get usage k)]
                 (update acc canonical (fnil + 0) (long n))
                 acc))
             {}
             token-keys))

(defn- price
  "Microdollars for `usage` under `model`, or nil when the model has no
   registry price. Never invents a price: an unpriced Attempt says so."
  [model usage]
  (when (and model (acct/get-model-pricing model))
    (reduce-kv (fn [sum k [_ resource-type]]
                 (if-let [n (get usage k)]
                   (+ sum (acct/calculate-cost resource-type (long n) {:model model}))
                   sum))
               0
               token-keys)))

(defn- list-price-model
  "The model whose list price `model`'s tokens are worth: its `:list-price-of`
   (a subscription model), else itself."
  [model]
  (or (some-> model registry/get-model :list-price-of) model))

(defn- notional
  "Microdollars `usage` would cost at list price, or nil when `model` has no
   list price model (then the notional cost is the cost)."
  [model usage]
  (let [reference (list-price-model model)]
    (when (not= reference model)
      (price reference usage))))

(defn notional-microdollars
  "What `spend` is worth at list price: `:notional-microdollars` when it
   carries one (a subscription), else what it cost."
  [spend]
  (get spend :notional-microdollars (:microdollars spend 0)))

(defn- merge-spend [a b]
  (cond-> {:microdollars (+ (:microdollars a 0) (:microdollars b 0))
           :tokens (merge-with + (:tokens a {}) (:tokens b {}))
           :by-model (merge-with merge-spend (:by-model a {}) (:by-model b {}))
           :priced? (and (:priced? a true) (:priced? b true))}
    ;; Only when one of them carries it: spends recorded before notional
    ;; costs existed fold to exactly what they folded to then (stored
    ;; Scorecards recompute their summary from their entries).
    (or (contains? a :notional-microdollars) (contains? b :notional-microdollars))
    (assoc :notional-microdollars (+ (notional-microdollars a) (notional-microdollars b)))))

(def zero {:microdollars 0 :tokens {} :by-model {} :priced? true})

(defn of-usage
  "The spend of one provider usage record under `model`."
  [model usage]
  (let [tokens (tokens-of usage)
        md (price model usage)
        worth (notional model usage)
        one (cond-> {:microdollars (or md 0) :tokens tokens :priced? (some? md)}
              worth (assoc :notional-microdollars worth))]
    (assoc one :by-model (if model {model one} {}))))

(defn of-budget
  "The spend a chat budget already accounted (`:used` microdollars, `:by-type`
   natural units), attributed to `model`."
  [model {:keys [used by-type]}]
  (let [tokens (tokens-of by-type)
        worth (notional model by-type)
        one (cond-> {:microdollars (long (or used 0)) :tokens tokens :priced? true}
              worth (assoc :notional-microdollars worth))]
    (assoc one :by-model (if model {model one} {}))))

(defn of-metrics
  "The spend of a Run's `:run/metrics`, as an LLM program leaves them:
   `{:model m :usage {:used μ$ :by-type {...}}}`."
  [{:keys [model usage]}]
  (if (map? usage) (of-budget model usage) zero))

(defn of-roles
  "The spend of per-role usage as a protocol reports it,
   `{role usage-or-budget}` with the model per role in `models`
   (`{role model-id}`, a role absent = unpriced)."
  [models usages]
  (reduce-kv (fn [acc role usage]
               (let [model (get models role)]
                 (merge-spend acc
                              (cond
                                (and (map? usage) (contains? usage :used)) (of-budget model usage)
                                (usage-map? usage) (of-usage model usage)
                                :else zero))))
             zero
             (or usages {})))

(defn total
  "Fold spends."
  [spends]
  (reduce merge-spend zero (remove nil? spends)))

(defn dollars [spend]
  (/ (double (:microdollars spend 0)) 1e6))
