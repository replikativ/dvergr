(ns dvergr.web.api
  "Spec-derived JSON HTTP API — the datahike analogue for dvergr. EVERY route is
   generated from `dvergr.ops/specification`: a read → GET (args from query
   params), a write → POST (args from the JSON body). malli coerces the request
   into the op's args map (the op's `:schema` IS the coercion schema), `ops/invoke`
   runs it, and the data result is returned as JSON. Swagger UI at /api/v1/docs,
   OpenAPI JSON at /api/v1/swagger.json — both derived from the same malli schemas.

   This is a MACHINE surface (curl/SDK/other services), parallel to MCP; the HTMX
   web UI keeps its own HTML endpoints (it consumes HTML, not JSON). The reitit
   ring-handler returns nil for non-/api URIs, so the hand-rolled http-kit server
   falls through to its own routes.

   Every call runs for the request's `:dvergr/principal`, which the server
   authenticated (`dvergr.web.server`): a request without one is refused (401),
   so mounting this handler elsewhere needs an authentication of its own. The
   routes follow `dvergr.ops/operations`, so an op a host registers is served
   too."
  (:require [dvergr.ops :as ops]
            [muuntaja.core :as muu]
            [reitit.ring :as ring]
            [reitit.coercion.malli]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]))
;; NOTE: reitit.swagger-ui (the interactive /docs page + its 2.6 MB asset jar) is
;; OPTIONAL — loaded lazily in `handler` below. swagger.json + all routes work
;; without it; add `metosin/reitit-swagger-ui` to get the browseable docs page.

(defn- current-daemon []
  (some-> (requiring-resolve 'dvergr.orchestration.daemon/current-daemon) deref deref))

;; JSON encoder that tolerates the values ops return — keyword map keys → names,
;; and anything Jackson doesn't know (java.time instants, UUIDs, keyword *values*)
;; → its string form, so a read never 500s on serialization.
(def ^:private m
  (muu/create
   (-> muu/default-options
       (assoc-in [:formats "application/json" :encoder-opts]
                 {:encode-key-fn name :date-format "yyyy-MM-dd'T'HH:mm:ss'Z'"})
       (assoc :default-format "application/json"))))

(defn- op-handler [op]
  (fn [{:keys [parameters] :as req}]
    ;; Prefer the system value threaded in by the server (assoc'd as
    ;; :dvergr/daemon in root-handler) so the API acts on the SAME system the
    ;; server was started with — embedded or not. The global current-daemon is
    ;; only a REPL/CLI convenience fallback, never the source of truth.
    (if-let [d (or (:dvergr/daemon req) (current-daemon))]
      (try
        (if-let [p (:dvergr/principal req)]
          {:status 200 :body (ops/invoke d p op (or (:body parameters) (:query parameters) {}))}
          {:status 401 :body {:error "Not authenticated" :op (ops/op->name op)}})
        (catch Throwable e
          ;; an op's failure carries its status: 4xx when the request is wrong
          {:status (ops/error-status e) :body {:error (.getMessage e) :op (ops/op->name op)}}))
      {:status 503 :body {:error "No dvergr daemon running."}})))

(defn- op-route [op {:keys [doc kind]}]
  (let [read?  (= :read kind)
        schema (ops/malli-schema op)]
    [(str "/" (ops/op->name op))
     {(if read? :get :post)
      {:summary     doc
       :operationId (ops/op->name op)
       :tags        [(namespace op)]
       :parameters  (if read? {:query schema} {:body schema})
       :handler     (op-handler op)}}]))

(defn- make-router [operations]
  (ring/router
   [(into ["/api/v1"
           ["/swagger.json"
            {:get {:no-doc  true
                   :swagger {:info {:title       "dvergr ops API"
                                    :description "Spec-derived HTTP API over dvergr.ops — one route per operation. Reads are GET (query args), writes POST (JSON body)."}}
                   :handler (swagger/create-swagger-handler)}}]]
          (for [[op spec] operations] (op-route op spec)))]
   {:data {:coercion   reitit.coercion.malli/coercion
           :muuntaja   m
           :middleware [swagger/swagger-feature
                        parameters/parameters-middleware
                        muuntaja/format-middleware
                        exception/exception-middleware     ; coercion errors → 400
                        coercion/coerce-request-middleware
                        coercion/coerce-response-middleware]}}))

(defn- swagger-ui-handler
  "The Swagger UI page handler — only if reitit-swagger-ui (optional) is on the
   classpath; else nil (swagger.json + all routes still work, just no /docs page)."
  []
  (try
    (when-let [mk (requiring-resolve 'reitit.swagger-ui/create-swagger-ui-handler)]
      (mk {:path "/api/v1/docs" :url "/api/v1/swagger.json"}))
    (catch Throwable _ nil)))

(def ^:private ring-handler-for
  ;; one handler per value of `ops/operations`: rebuilt when a host registers an op
  (memoize (fn [operations]
             (ring/ring-handler
              (make-router operations)
              (or (some-> (swagger-ui-handler) ring/routes)
                  (constantly nil))))))

(defn handler
  "Ring handler for /api/v1/* (+ Swagger UI at /api/v1/docs when reitit-swagger-ui
   is present). Returns nil on a non-matching URI so the http-kit server falls
   through to its HTML routes."
  [req]
  ((ring-handler-for (ops/operations)) req))
