(ns dvergr.runtime.host-lifetime
  "Optional trusted embedder lifetime around synchronous host operations.
   This seam owns no state, executor or authorization policy itself.")

(defn call
  "Run f within configured [:config :host-lifecycle :with-operation], or
   directly when no lifecycle is configured. A configured hook must invoke f
   exactly once, synchronously on this thread, while retaining its exact host.
   Results must not defer native resource access beyond this call's return.
   Capture the ORIGINAL lifetime owner, never resolve a replacement installation.
   A closed forwarding gate refuses saved/deferred or repeated callbacks.
   This is trusted HOST configuration, never transport or sandbox input."
  [host f]
  (if-not (contains? (:config host) :host-lifecycle)
    (f)
    (let [lifecycle (get-in host [:config :host-lifecycle])
          wrapper (:with-operation lifecycle)]
      (when-not (and (map? lifecycle) (= #{:with-operation} (set (keys lifecycle))) (fn? wrapper))
        (throw (ex-info "Malformed host lifecycle configuration" {:type ::invalid-configuration})))
      (let [thread (Thread/currentThread)
            state (volatile! :open)]
        (try
          (let [result (wrapper (fn []
                                  (when-not (and (identical? thread (Thread/currentThread)) (= :open @state))
                                    (throw (ex-info "Host lifecycle callback is not current"
                                                    {:type ::invalid-forwarding})))
                                  (vreset! state :entered)
                                  (try (f) (finally (vreset! state :finished)))))]
            (when-not (= :finished @state)
              (throw (ex-info "Host lifecycle did not execute its operation"
                              {:type ::not-invoked})))
            result)
          (finally (vreset! state :closed)))))))
