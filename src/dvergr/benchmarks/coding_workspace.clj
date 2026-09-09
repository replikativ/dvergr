(ns dvergr.benchmarks.coding-workspace
  "Bounded host-side snapshots of explicit virtual workspace files."
  (:require [dvergr.substrate.geschichte :as g]
            [muschel.fs :as fs]
            [org.replikativ.spindel.engine.core :as ec]))

(defn capture
  "Collect up to 16 explicitly named absolute paths, at most 64 KiB each.
   Reads only the registered Geschichte filesystem, never the host or parent.
   Missing/rejected files are evidence, not exceptions or empty source. The
   caller must run this after candidate quiescence (Evaluator :capture)."
  [room paths max-bytes]
  (when-not (and (vector? paths) (<= 1 (count paths) 16)
                 (= (count paths) (count (set paths)))
                 (every? #(and (string? %) (.startsWith ^String % "/")) paths)
                 (integer? max-bytes) (<= 1 max-bytes 65536))
    (throw (ex-info "Invalid coding artifact capture bounds" {})))
  (if-not (:ctx room)
    {:status :world-unavailable}
    (binding [ec/*execution-context* (:ctx room)]
      (if-let [filesystem (g/filesystem)]
        {:status :captured
         :files
         (into {}
               (map (fn [path]
                      [path
                       (let [{:keys [type size]} (fs/stat filesystem path)]
                         (cond
                           (nil? type) {:status :missing}
                           (not= :file type) {:status :not-file}
                           (not (and (integer? size) (<= 0 size max-bytes)))
                           {:status :size-rejected}
                           :else
                           (let [text (fs/read-file filesystem path)]
                             (cond
                               (not (string? text)) {:status :unreadable}
                               (> (alength (.getBytes ^String text "UTF-8")) max-bytes)
                               {:status :size-rejected}
                               :else {:status :ok :source text}))))]))
               paths)}
        {:status :no-workspace}))))
