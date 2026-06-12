(ns scenario-streaming-partial
  "Streaming `:partial/token` scenario.

   One producer fires 50 tokens onto the bus. Two consumers tap the
   same `[:type :partial/token]` topic with DIFFERENT buffer policies:

     audit consumer — DEFAULT (fixed-buffer 256, the :partial namespace policy)
     UI consumer    — OVERRIDDEN to sliding-buffer 1 (only latest matters)

   Tokens are discrete data — losing one loses information — so the
   default for `:partial` is fixed-buffer. UI consumers that only care
   about \"current accumulated state\" opt INTO lossy semantics with a
   per-subscription override. This way the producer can assume its
   tokens will be observed somewhere; consumers pick their policy.

   Run:
     clojure -M:examples -m scenario-streaming-partial"
  (:require [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.pubsub.buffer :as buf]
            [dvergr.runtime.bus :as bus]
            [dvergr.discourse :as d]))

(defn spawn-token-producer!
  "Fire `n` `:partial/token` messages onto the bus as fast as the engine
   accepts them. No delay between posts — bursts are what stress the SLA."
  [room n]
  (binding [ec/*execution-context* (:ctx room)]
    (sp/spawn!
     (spin
      (dotimes [i n]
        (d/post! room {:type :partial/token
                       :from :producer
                       :payload {:i i :tok (str "tok-" i)}}))
      nil))))

(defn spawn-ui-consumer!
  "UI consumer: simulate a slow re-render via Thread/sleep.
   Explicitly OVERRIDES the :partial default (fixed-buffer 256) with
   sliding-buffer 1 — UIs rendering current state don't need backlog;
   under burst we expect to observe a SAMPLE of tokens, not all of them."
  [room got]
  (let [sub (binding [ec/*execution-context* (:ctx room)]
              (bus/subscribe! (:bus room) [:type :partial/token]
                              (buf/sliding-buffer 1)))]
    (binding [ec/*execution-context* (:ctx room)]
      (sp/spawn!
       (spin
        (loop [s (:aseq sub)]
          (when-let [r (await (aseq/anext s))]
            (let [[msg rest-s] r]
              (Thread/sleep 5)         ; slow render
              (swap! got conj (get-in msg [:payload :i]))
              (recur rest-s)))))))
    sub))

(defn spawn-audit-consumer!
  "Audit consumer: uses the :partial default (fixed-buffer 256), which
   is the right policy for discrete tokens — every chunk is data and
   must be observed losslessly."
  [room got]
  (let [sub (binding [ec/*execution-context* (:ctx room)]
              (bus/subscribe! (:bus room)
                              [:type :partial/token]))]
    (binding [ec/*execution-context* (:ctx room)]
      (sp/spawn!
       (spin
        (loop [s (:aseq sub)]
          (when-let [r (await (aseq/anext s))]
            (let [[msg rest-s] r]
              (swap! got conj (get-in msg [:payload :i]))
              (recur rest-s)))))))
    sub))

(defn -main
  [& _args]
  (let [room       (d/room :stream-demo)
        ui-got     (atom [])
        audit-got  (atom [])
        n          50]
    ;; Subscribers FIRST — pubs start their pumps lazily on first sub.
    (spawn-ui-consumer!    room ui-got)
    (spawn-audit-consumer! room audit-got)

    (println "=== Streaming-partial scenario ===")
    (println "Producer firing" n ":partial/token messages...")
    (spawn-token-producer! room n)

    ;; Wait long enough for UI (slow) to settle.
    (Thread/sleep (+ 200 (* 5 n)))

    (println)
    (println "Audit consumer saw" (count @audit-got) "tokens (expected" n ")")
    (println "  first 10:" (vec (take 10 @audit-got)))
    (println "  last 5:  " (vec (take-last 5 @audit-got)))

    (println)
    (println "UI consumer saw"     (count @ui-got)    "tokens (sliding-1 override,")
    (println "  i.e. lossy on purpose — UI only wants the latest)")
    (println "  what UI got:" @ui-got)

    (println)
    (println "Log size:" (count (d/log room)) "(all" n "tokens are in the log)")
    (println)
    (println "Same producer. Different SLA per consumer. No producer awareness.")
    (System/exit 0)))
