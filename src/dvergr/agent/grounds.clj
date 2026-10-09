(ns dvergr.agent.grounds
  "What an Attempt's result rests on, read from its effect receipts, and how
   much two Attempts share.

   An Attempt's grounds are the sources it read: pages and APIs it fetched
   (`:external`) and files and rooms of its world (`:local`). Each source maps
   to the digests of what it returned. Local reads see the room the Attempt
   was forked from, which every sibling shares by construction; external reads
   are evidence the Attempt added. Two Attempts that agree because they read
   the same pages are one source counted twice, not independent confirmation:
   `independence` measures that overlap on the external grounds.

   Nothing is inferred from model output. A source is what a receipt names,
   so a fact an Attempt took from its prompt or a model's memory has no
   ground here."
  (:require [clojure.set :as set]
            [dvergr.artifact :as artifact]))

(defn effect-log
  "The receipts of an Attempt's effect log `ref` in `room`'s artifact store,
   or nil when there is none."
  [room ref]
  (when ref
    (let [store (or (some-> room :store :artifacts)
                    (some-> room :store :conn artifact/datahike-store))]
      (some-> store
              (artifact/get-value (or (parse-uuid (str ref)) ref))
              :dvergr/effect-log))))

(defn attempt-receipts
  "The effect receipts of certified `attempt` of `room`."
  [room attempt]
  (effect-log room (get-in attempt [:attempt/receipt :attempt/metrics :effects :log])))

(defn source
  "The source receipt `r` read, as `[:external id]` or `[:local id]`, or nil
   when `r` is not a completed read: a write, a denial, a failure, a model
   call (spend, not evidence) or an event."
  [{:keys [effect resource decision error]}]
  (when (and (= :allowed decision) (nil? error))
    (case effect
      :http/request (when-let [url (:url resource)] [:external url])
      :fs/read [:local (str "file:" (if (map? resource) (:path resource) resource))]
      :room/read [:local (str "room:" (or (:room resource) (pr-str resource)))]
      nil)))

(defn grounds
  "The grounds of `receipts`: `{:external {source #{digest}} :local {...}}`."
  [receipts]
  (reduce (fn [g r]
            (if-let [[kind id] (source r)]
              (update-in g [kind id] (fnil conj #{}) (:digest r))
              g))
          {:external {} :local {}}
          receipts))

(defn summary
  "Counts of `g`'s sources, for a table."
  [g]
  {:external (count (:external g)) :local (count (:local g))})

(defn independence
  "How much two Attempts' external grounds `ga` and `gb` overlap:
   `{:shared #{source} :only-a n :only-b n :overlap jaccard :changed #{source}}`
   (`:changed`: shared sources that returned different content, e.g. a live
   page read at two times). `:overlap` is nil when neither read anything
   external: no evidence either way, not independence."
  [ga gb]
  (let [a (:external ga) b (:external gb)
        ka (set (keys a)) kb (set (keys b))
        shared (set/intersection ka kb)
        union (set/union ka kb)]
    {:shared shared
     :only-a (count (set/difference ka kb))
     :only-b (count (set/difference kb ka))
     :overlap (when (seq union) (/ (double (count shared)) (count union)))
     :changed (into #{} (remove #(= (a %) (b %))) shared)}))

(defn shared-sources
  "Pairs of `gs` (grounds by index) whose external grounds overlap, highest
   overlap first: `[{:attempts [i j] :overlap x :shared [source]}]`."
  [gs]
  (let [gs (vec gs)]
    (->> (for [i (range (count gs)) j (range (inc i) (count gs))
               :let [{:keys [overlap shared]} (independence (gs i) (gs j))]
               :when (and overlap (pos? overlap))]
           {:attempts [i j]
            :overlap (/ (Math/round (* 100 overlap)) 100.0)
            :shared (vec (sort shared))})
         (sort-by (comp - :overlap))
         vec)))
