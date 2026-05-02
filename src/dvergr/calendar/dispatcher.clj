(ns dvergr.calendar.dispatcher
  "Calendar event dispatcher.

   A spindel spin loop that polls for upcoming events every 60 seconds and
   fires a dispatch function when events are due to start. Uses
   :cal/dispatched? to prevent double-firing."
  (:require [dvergr.calendar.core :as cal]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.core :refer [spin await]]
            [org.replikativ.spindel.spin.combinators :as comb]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private running? (atom false))

;; ============================================================================
;; Dispatcher Loop
;; ============================================================================

(defn start-dispatcher!
  "Start a spin loop that checks for upcoming events every 60 seconds.

   For each event whose start time falls within the next 60s window:
   - Calls (dispatch-fn event) with the full event map
   - Marks the event as dispatched to prevent re-firing

   Args:
     conn        - Datahike connection
     exec-ctx    - Spindel execution context
     dispatch-fn - (fn [event-map]) called when event fires"
  [conn exec-ctx dispatch-fn]
  (reset! running? true)
  (binding [rtc/*execution-context* exec-ctx]
    (let [dispatcher-spin
          (spin
            (loop []
              (await (comb/sleep 60000)) ;; Check every 60 seconds
              (when @running?
                (try
                  (let [events (cal/undispatched-upcoming conn 2)] ;; 2 min window for safety
                    (doseq [evt events]
                      (try
                        (tel/log! {:level :info :id :calendar/dispatching
                                   :data {:title (:cal/title evt)
                                          :id (:cal/id evt)}}
                                  "Dispatching calendar event")
                        (dispatch-fn evt)
                        (cal/mark-dispatched! conn (:cal/id evt))
                        (catch Exception e
                          (tel/log! {:level :error :id :calendar/dispatch-error
                                     :data {:id (:cal/id evt) :error (.getMessage e)}}
                                    "Calendar dispatch error")))))
                  (catch Exception e
                    (tel/log! {:level :warn :id :calendar/poll-error
                               :data {:error (.getMessage e)}}
                              "Calendar poll error")))
                (recur))))]
      ;; Fire and forget
      (dispatcher-spin
        (fn [_] (tel/log! {:id :calendar/dispatcher-exited} "Calendar dispatcher exited"))
        (fn [e] (tel/log! {:level :error :id :calendar/dispatcher-error
                           :data {:error (str e)}} "Calendar dispatcher error")))
      (tel/log! {:id :calendar/dispatcher-started} "Calendar dispatcher started"))))

(defn stop-dispatcher!
  "Stop the calendar event dispatcher."
  []
  (reset! running? false)
  (tel/log! {:id :calendar/dispatcher-stopped} "Calendar dispatcher stopped"))
