(ns dvergr.web.ops
  "Web binding MODULE for `dvergr.ops` — derives the `/api` action routes from the
   central operations spec, the datahike codegen-module pattern (runtime data, not
   macros). The central spec stays pure semantics; THIS module holds only what is
   web-specific: the HTTP verb + path convention, and how an op result becomes a
   ring response. Add an op to `dvergr.ops` and (if it needs a route) one line of
   overlay here — the `slug→room` resolution and the call all come from
   `ops/invoke`.

   Routes covered here are the room/agent LIFECYCLE actions. Reads that render
   hiccup fragments (stats/messages/tree) and the message-post command path stay
   in the hand-written handler (rendering is the per-binding overlay, not derived)."
  (:require [clojure.string :as str]
            [dvergr.ops :as ops]
            [dvergr.agent.fields :as fields]))

(defn- redirect [loc] {:status 303 :headers {"Location" loc} :body ""})

(defn- parse-form [req]
  (when-let [b (:body req)]
    (into {} (for [pair (str/split (slurp b) #"&")
                   :let [[k v] (str/split pair #"=" 2)]
                   :when (and k v)]
               [k (java.net.URLDecoder/decode (str/replace v #"\+" " ") "UTF-8")]))))

;; ---------------------------------------------------------------------------
;; The web overlay: a route table. Each entry derives its call from `:op`; the
;; per-op web specifics are `:method`, `:path` (a regex with named groups bound
;; positionally to `:slots`), `:args` (slots+form → op args) and `:respond`
;; (op-result+slots → ring response). Most are one trivial line.
;; ---------------------------------------------------------------------------

(def ^:private routes
  [;; Room lifecycle — GET /api/rooms/:slug/<action>, <action> == the op name.
   {:op :room/delete  :method :get  :path #"/api/rooms/(.+)/delete"  :slots [:slug]
    :args (fn [[slug] _] {:room slug}) :respond (fn [_ _] (redirect "/dashboard"))}
   {:op :room/fork    :method :get  :path #"/api/rooms/(.+)/fork"    :slots [:slug]
    :args (fn [[slug] _] {:room slug}) :respond (fn [_ _] (redirect "/dashboard"))}
   {:op :room/merge   :method :get  :path #"/api/rooms/(.+)/merge"   :slots [:slug]
    :args (fn [[slug] _] {:room slug}) :respond (fn [_ _] (redirect "/dashboard"))}
   {:op :room/discard :method :get  :path #"/api/rooms/(.+)/discard" :slots [:slug]
    :args (fn [[slug] _] {:room slug}) :respond (fn [_ _] (redirect "/dashboard"))}

   ;; Room create — POST /api/rooms (form: slug, title)
   {:op :room/create  :method :post :path #"/api/rooms"                 :slots []
    :args (fn [_ form] {:slug (some-> (get form "slug") str/trim)
                        :title (let [t (some-> (get form "title") str/trim)]
                                 (if (seq t) t (some-> (get form "slug") str/trim)))})
    :respond (fn [_ _] (redirect "/dashboard"))}

   ;; Agent create / update — the form is parsed by the shared field-spec, so the
   ;; web and MCP create/update go through the identical op. Config GET (render the
   ;; page) stays hand-written (it renders hiccup; a read is not a derivable action).
   {:op :agent/create :method :post :path #"/agents/new" :slots []
    :args (fn [_ form] (assoc (fields/parse-params form)
                              :id (some-> (get form "id") str/trim)))
    :respond (fn [result _]
               (redirect (if-let [id (:created result)]
                           (str "/agents/" id "/config") "/dashboard#agents")))}
   {:op :agent/update :method :post :path #"/agents/([^/]+)/config" :slots [:id]
    :args (fn [[id] form] {:id id :patch (fields/parse-params form)})
    :respond (fn [_ [id]] (redirect (str "/agents/" id "/config")))}

   ;; Agent lifecycle — `agent/delete` stops-then-deletes inside the op now.
   {:op :agent/delete :method :get :path #"/agents/([^/]+)/delete" :slots [:id]
    :args (fn [[id] _] {:id id}) :respond (fn [_ _] (redirect "/agents"))}
   {:op :agent/open   :method :get :path #"/agents/([^/]+)/open"   :slots [:id]
    :args (fn [[id] _] {:id id})
    :respond (fn [result _]
               (if result
                 (redirect (str "/rooms/" (:id result)))
                 {:status 503 :headers {"Content-Type" "text/html"}
                  :body "<p>Could not open chat — no chat DB connection.</p>"}))}])

(defn dispatch
  "If `req` matches a spec-derived web route, run the op via `ops/invoke` and return
   a ring response; else nil (the caller falls through to the hand-written routes)."
  [req daemon]
  (let [uri (:uri req) method (:request-method req)]
    (some (fn [entry]
            (when (= method (:method entry))
              (when-let [m (re-matches (:path entry) uri)]
                (let [slots (if (vector? m) (vec (rest m)) [])
                      form  (when (= :post method) (parse-form req))
                      args  ((:args entry) slots form)
                      res   (ops/invoke daemon (:op entry) args)]
                  ((:respond entry) res slots)))))
          routes)))
