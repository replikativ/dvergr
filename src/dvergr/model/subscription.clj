(ns dvergr.model.subscription
  "How much of a subscription's usage windows is used: one meter for every
   subscription provider (Codex, Claude Code), fed by what the provider reports
   with each call, and a governor that experiments ask before they start a
   cell.

   A subscription has no bill, but it is the user's own quota: a benchmark
   that drains the week's window costs them their working tool. A reading is

     {:plan \"pro\" :at-ms 1790…
      :windows {:primary {:used 0.40 :window-minutes 10080 :resets-at-ms 1791…}}}

   `:used` is a fraction of the window. Codex reports whole percent of a
   weekly window (response headers `x-codex-primary-used-percent`, …); Claude
   Code reports utilization per window with every CLI result.

   An allowance bounds one experiment: `{:share 0.10 :pause-at 0.80}` admits
   no new cell once the experiment has used ten points of any window since it
   started, or once a window is 80% used, whoever used it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

(defonce ^:private readings (atom {}))

(def ^:private history-size 2000)

(defonce ^:private history
  ;; [{:provider :at-ms :windows}], newest last: measurements for learning
  ;; what a unit of work costs in window points
  (atom []))

(defn observe!
  "Record `reading` of `provider`'s subscription."
  [provider reading]
  (let [reading (assoc reading :at-ms (or (:at-ms reading) (System/currentTimeMillis)))]
    (swap! readings assoc provider reading)
    (swap! history (fn [h] (let [h (conj h (assoc reading :provider provider))]
                             (if (> (count h) history-size) (subvec h (- (count h) history-size)) h))))
    reading))

(defn forget!
  "Drop what is known of `provider`'s subscription (tests that fake a report)."
  [provider]
  (swap! readings dissoc provider)
  (swap! history (fn [h] (filterv #(not= provider (:provider %)) h))))

(defn reading
  "The latest reading of `provider`, or nil."
  [provider]
  (get @readings provider))

(defn all-readings [] @readings)

(defn samples
  "Recorded readings, oldest first (`provider` only, when given)."
  ([] @history)
  ([provider] (filterv #(= provider (:provider %)) @history)))

;; ---------------------------------------------------------------------------
;; Measurement

(def resolution
  "The smallest change a reading shows: Codex reports whole percent."
  0.01)

(defn moved
  "How far `provider`'s most moved window went in the readings since
   `since-ms`, as a fraction; nil without two readings. A reading's resolution
   is one point, so 0 means less than `resolution`."
  [provider since-ms]
  (let [xs (filterv #(>= (:at-ms %) since-ms) (samples provider))]
    (when (>= (count xs) 2)
      (let [first-r (first xs) last-r (peek xs)]
        (reduce max 0.0
                (keep (fn [[k {u :used}]]
                        (when-let [u0 (get-in first-r [:windows k :used])]
                          (when u (- u u0))))
                      (:windows last-r)))))))

;; ---------------------------------------------------------------------------
;; Calibration: what a token costs in window points, across runs

(def ^:dynamic *calibration-file*
  "Where runs record what they spent and moved. A subscription's quota is the
   user's, not a project's, so this is per user (`DVERGR_CALIBRATION` moves
   it)."
  (or (System/getenv "DVERGR_CALIBRATION")
      (str (System/getProperty "user.home") "/.config/dvergr/subscription-calibration.edn")))

(defn calibration-records
  "Every recorded run: `[{:provider :tokens :points :at-ms :cells}]`."
  []
  (let [f (io/file *calibration-file*)]
    (if (.exists f)
      (try (vec (edn/read-string (slurp f))) (catch Exception _ []))
      [])))

(defn record-run!
  "Record that a run spent `tokens` on `provider` and moved its most used
   window `points` (a fraction; whole points only, so often 0)."
  [provider {:keys [tokens points cells]}]
  (when (and (pos? (or tokens 0)) (some? points))
    (let [f (io/file *calibration-file*)]
      (io/make-parents f)
      (locking #'record-run!
        (spit f (pr-str (conj (calibration-records)
                              {:provider provider :tokens (long tokens) :points (double points)
                               :cells cells :at-ms (System/currentTimeMillis)})))))))

(defn points-per-token
  "`provider`'s window points per token over every recorded run: total points
   over total tokens, with the unseen part of a point counted once more (each
   reading hides up to one point), so it errs high; nil without records. The
   records are only as clean as the window: the user's own use during a run
   counts too, which errs high again."
  ([provider] (points-per-token provider (calibration-records)))
  ([provider records]
   (let [rs (filter #(= provider (:provider %)) records)
         tokens (reduce + 0 (map :tokens rs))
         points (reduce + 0.0 (map :points rs))]
     (when (pos? tokens)
       {:points-per-token (/ (+ points resolution) tokens)
        :tokens tokens :points points :runs (count rs)}))))

;; ---------------------------------------------------------------------------
;; What providers report

(defmulti observe-headers!
  "Record the usage a provider reports in the response headers of a call
   (`headers`: lower-case names to strings). A provider without a meter
   ignores them."
  (fn [provider _headers] provider))

(defmethod observe-headers! :default [_ _] nil)

(defn- number [s]
  (when-not (str/blank? (str s))
    (try (Double/parseDouble (str s)) (catch NumberFormatException _ nil))))

(defn codex-reading
  "A reading from Codex's `x-codex-*` response headers, or nil."
  [headers]
  (let [h (fn [k] (get headers (str "x-codex-" k)))
        window (fn [prefix]
                 (let [used (number (h (str prefix "-used-percent")))
                       minutes (number (h (str prefix "-window-minutes")))
                       reset (number (h (str prefix "-reset-at")))]
                   (when (and used minutes (pos? minutes))
                     {:used (/ used 100.0)
                      :window-minutes (long minutes)
                      :resets-at-ms (when reset (long (* 1000 reset)))})))
        windows (cond-> {}
                  (window "primary") (assoc :primary (window "primary"))
                  (window "secondary") (assoc :secondary (window "secondary")))]
    (when (seq windows)
      {:plan (h "plan-type") :windows windows})))

(defmethod observe-headers! :codex-subscription [provider headers]
  (when-let [r (codex-reading headers)]
    (observe! provider r)))

;; ---------------------------------------------------------------------------
;; The governor

(defn used
  "The most used window of `r` (a reading): `[window-key fraction]`."
  [r]
  (when-let [ws (seq (:windows r))]
    (let [[k w] (apply max-key (comp #(or % 0.0) :used val) ws)]
      [k (:used w)])))

(defn baseline
  "The readings an allowance counts from: now, for every provider read."
  []
  (all-readings))

(defn refusal
  "Why a new cell must not start under `allowance` (`{:share :pause-at}`,
   counted from `base`, a `baseline`), for the subscription `providers` in
   use; nil when it may."
  [{:keys [share pause-at]} base providers]
  (some (fn [provider]
          (when-let [now (reading provider)]
            (some (fn [[k {u :used :keys [resets-at-ms]}]]
                    (let [before (get-in base [provider :windows k :used])]
                      (cond
                        ;; readings are whole percent: compare with a
                        ;; tolerance, or 0.50 - 0.40 falls short of 0.10
                        (and pause-at u (>= (+ u 1e-9) pause-at))
                        {:reason :window-full :provider provider :window k :used u
                         :pause-at pause-at :resets-at-ms resets-at-ms}

                        (and share u before (>= (+ (- u before) 1e-9) share))
                        {:reason :allowance-used :provider provider :window k
                         :used u :since (- u before) :share share :resets-at-ms resets-at-ms})))
                  (:windows now))))
        providers))

(defn governor
  "An admission function for an experiment's cells: `(fn [cell])` → nil to admit,
   or the refusal, logged once. `providers` are the subscription providers the
   experiment may call. The allowance counts from each provider's reading when
   the governor is made, or from its first reading after (a fresh process has
   none until its first call)."
  [allowance providers]
  (let [base (atom (select-keys (baseline) providers))
        logged (atom false)]
    (fn [& _]
      (doseq [p providers]
        (when-let [r (and (not (contains? @base p)) (reading p))]
          (swap! base #(if (contains? % p) % (assoc % p r)))))
      (when-let [r (refusal allowance @base providers)]
        (when (compare-and-set! logged false true)
          (tel/log! {:level :warn :id :subscription/allowance :data r}
                    "No new cells: the subscription allowance is reached"))
        r))))
