(ns dvergr.workflows
  "Pre-canned multi-agent workflow patterns on top of `dvergr.discourse`.

   Every workflow is a 3-8 line spin assembled from discourse primitives
   (ask / fan-out / race / pipeline / iterative-refinement / debate /
   align-on). The thin layer here adds role-conventional prompt framing
   and structured return shapes.

   Participants must already be joined in the room under the expected role
   ids (`:researcher`, `:coder`, `:reviewer`, ...). Use any participant
   constructor — `agent` (LLM), `scripted` (test), `human`."
  (:require [clojure.string :as str]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.spin.combinators :as comb]
            [dvergr.discourse :as d]))

;; ============================================================================
;; Pattern 1 — Sequential research → implement → review
;; ============================================================================

(defn research-implement-test
  "Pipeline: researcher → coder → reviewer. Each stage receives the previous
   stage's content framed as task. Returns Spin[{:research :code :review}]."
  ([room topic]
   (research-implement-test room topic
                            {:researcher :researcher
                             :coder :coder
                             :reviewer :reviewer}))
  ([room topic {:keys [researcher coder reviewer]}]
   (sp/spin
     (let [research (sp/await (d/ask room researcher
                                     {:content (str "Research: " topic)}))
           code     (sp/await (d/ask room coder
                                     {:content (str "Implement based on:\n"
                                                    (:content research))}))
           review   (sp/await (d/ask room reviewer
                                     {:content (str "Review:\n"
                                                    (:content code))}))]
       {:research research :code code :review review}))))

;; ============================================================================
;; Pattern 2 — Parallel fan-out across topics or reviewers
;; ============================================================================

(defn parallel-research
  "Fan-out: dispatch each topic to one researcher in parallel.
   `topic+researcher` is a vector of `[topic researcher-id]` pairs.
   Returns Spin[Vector[Message]] preserving input order."
  [room topic+researcher]
  (sp/spin
    (sp/await
      (apply comb/parallel
             (mapv (fn [[topic researcher-id]]
                     (d/ask room researcher-id
                            {:content (str "Research: " topic)}))
                   topic+researcher)))))

;; ============================================================================
;; Pattern 3 — Iterative refinement (re-exported; the discourse primitive
;; already implements producer↔critic + accept?)
;; ============================================================================

(defn iterative-refinement
  "Producer drafts, critic reviews, loop until `accept?` fires or max-iter.
   Thin wrapper around `dvergr.discourse/iterative-refinement` with the
   producer/critic role convention."
  ([room task] (iterative-refinement room task {}))
  ([room task {:keys [producer critic accept? max-iter]
               :or {producer :coder critic :reviewer
                    accept? (fn [m] (re-find #"(?i)lgtm|approve" (:content m)))
                    max-iter 5}}]
   (d/iterative-refinement room producer critic
                           {:content task}
                           {:accept? accept? :max-iter max-iter})))

;; ============================================================================
;; Pattern 4 — Competitive race (first approach wins)
;; ============================================================================

(defn competitive-race
  "Send the same task to multiple agents; first reply wins.
   Returns Spin[Message]."
  [room targets task]
  (d/race room targets {:content task}))

;; ============================================================================
;; Composition combinators
;; ============================================================================

(defn then
  "Sequentially compose: `spin-a` then `(spin-b-fn result-a)`.
   Returns Spin yielding {:first result-a :second result-b}."
  [spin-a spin-b-fn]
  (sp/spin
    (let [a (sp/await spin-a)
          b (sp/await (spin-b-fn a))]
      {:first a :second b})))

(defn and-parallel
  "Run multiple workflows concurrently. Returns Spin[Vector] of all results."
  [& spins]
  (sp/spin (sp/await (apply comb/parallel spins))))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn review-accepts?
  "Predicate: does this review Message look like acceptance?
   Matches 'approve', 'lgtm', 'ship it', 'ready to merge' (case-insensitive)."
  [message]
  (when-let [content (:content message)]
    (let [lc (str/lower-case content)]
      (boolean
        (or (re-find #"approve" lc)
            (re-find #"lgtm" lc)
            (re-find #"ship it" lc)
            (re-find #"ready to merge" lc))))))
