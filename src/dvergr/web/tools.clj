(ns dvergr.web.tools
  "Agent-facing tools for web serving and self-testing.

   web_serve — Agent mounts a Ring handler (written in SCI with hiccup).
   web_fetch enhancement — Relative URLs resolve to agent's own mounted handler."
  (:require [dvergr.tools :as tools]
            [dvergr.sandbox :as sandbox]
            [clojure.string :as str]))

;; ============================================================================
;; Lazy requires
;; ============================================================================

(defn- web-server-ns []
  (require 'dvergr.web.server)
  (find-ns 'dvergr.web.server))

;; ============================================================================
;; web_serve tool
;; ============================================================================

(tools/register!
  {:name "web_serve"
   :description "Register a web UI handler for your agent.

   Write a Clojure Ring handler function that takes a request map and returns
   a response map. Your handler sees '/' as root — path scoping is automatic.
   The system mounts your handler at /agents/{your-agent-id}/.

   Use hiccup for HTML generation:
     (require '[hiccup2.core :as h])

   Your handler_code must evaluate to a function (fn [req] -> response-map).

   IMPORTANT for HTMX: Use relative paths WITHOUT leading slash in hx-get/hx-post
   attributes (e.g. hx-get=\"data\" not hx-get=\"/data\"), because the browser resolves
   them relative to your mount point. Your handler still matches routes with leading
   slash (e.g. case \"/data\") since the server strips the prefix.

   Example handler_code:
     (require '[hiccup2.core :as h])
     (fn [req]
       (case (:uri req)
         \"/\" {:status 200
               :headers {\"Content-Type\" \"text/html\"}
               :body (str (h/html [:div [:h1 \"My Dashboard\"]
                                   [:div {:hx-get \"data\" :hx-trigger \"every 5s\"} \"Loading...\"]]))}
         \"/data\" {:status 200
                  :headers {\"Content-Type\" \"text/html\"}
                  :body (str (h/html [:p \"Time: \" (str (java.time.Instant/now))]))}
         {:status 404 :body \"Not found\"}))

   Test your pages with web_fetch using relative paths like '/my-page'.

   Returns the URL where your handler is mounted."
   :parameters {:type "object"
                :properties {:handler_code {:type "string"
                                            :description "Clojure code that evaluates to a Ring handler function (fn [req] -> response)"}}
                :required ["handler_code"]}
   :execute (fn [{:keys [handler_code]} {:keys [sci-ctx chat-ctx isolation eval-ns]}]
              (try
                (let [ws-ns (web-server-ns)
                      mount! (ns-resolve ws-ns 'mount-agent-handler!)
                      running? (ns-resolve ws-ns 'running?)

                      ;; Get agent-id from chat context or generate one
                      agent-id (or (when (map? chat-ctx)
                                     (:agent-id chat-ctx))
                                   (keyword (str "agent-" (rand-int 10000))))

                      ;; Eval handler code
                      handler (if (= :native isolation)
                                ;; Native eval — wrap in (do ...) to handle multi-form code
                                (let [wrapped (str "(do " handler_code "\n)")
                                      form (binding [*ns* @eval-ns]
                                             (read-string wrapped))]
                                  (binding [*ns* @eval-ns]
                                    (eval form)))
                                ;; SCI eval
                                (if sci-ctx
                                  (let [_ (sandbox/add-hiccup-ns! sci-ctx)
                                        result (sandbox/eval-code sci-ctx handler_code)]
                                    (if (:success result)
                                      (:value result)
                                      (throw (ex-info (str "Handler eval error: " (:message (:error result)))
                                                      {:error (:error result)}))))
                                  (throw (ex-info "No SCI context available" {}))))]

                  (when-not (fn? handler)
                    (throw (ex-info "handler_code must evaluate to a function" {:got (type handler)})))

                  (when-not (running?)
                    (throw (ex-info "HTTP server not running. Start the daemon with :http config." {})))

                  (let [url (mount! agent-id handler)]
                    {:type :success
                     :content (str "Handler mounted successfully!\n"
                                   "URL: " url "\n"
                                   "Agent ID: " (name agent-id) "\n\n"
                                   "Test it with: web_fetch {\"url\": \"/\"}")
                     :metadata {:url url :agent-id (name agent-id)}}))
                (catch Exception e
                  {:type :error
                   :error (str "web_serve failed: " (.getMessage e))})))})

;; ============================================================================
;; web_fetch relative URL enhancement
;; ============================================================================

(defn resolve-agent-url
  "If URL starts with '/', resolve to agent's mounted path on localhost.
   Otherwise return URL unchanged."
  [url agent-id]
  (if (and (str/starts-with? url "/")
           (not (str/starts-with? url "//")))
    (let [ws-ns (web-server-ns)
          port-fn (ns-resolve ws-ns 'server-port)
          port (or (port-fn) 8080)]
      (str "http://localhost:" port "/agents/" (name agent-id) url))
    url))
