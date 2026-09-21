(ns dvergr.benchmarks.discovery
  "Frozen discovery through the ordinary isolated Run and evaluator lifecycle.
   Synthetic companies test acquisition and evidence handling, not market demand."
  (:require [datahike.api :as d]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.artifact :as artifact]
            [dvergr.benchmarks.discovery-citations :as citations]
            [dvergr.benchmarks.frozen-web :as web]
            [dvergr.benchmarks.market-evidence :as evidence]
            [dvergr.sandbox.ns.io :as io]
            [hasch.core :as hasch]
            [jsonista.core :as j]))

(def pages
  {"https://example.org/aster" {:title "Aster" :body "Aster supports persistent agent teams with shared organizational memory."}
   "https://example.org/beryl" {:title "Beryl" :body "Beryl supports agent teams with human approval of proposed business changes."}
   "https://example.org/cinder" {:title "Cinder" :body "Cinder is a personal autocomplete editor, not an agent team workspace."}})

(def references (into {} (map (fn [url] [url (:body (get pages url))])
                              ["https://example.org/aster" "https://example.org/beryl"])))
(def fixture-id (:dvergr/fixture-id ((web/transport pages) {:url "https://example.org/aster"})))
(def basis (hasch/uuid [:discovery/v1 fixture-id references :search-and-captured-citations]))
(def setup-ref {:setup/id :business/frozen-discovery :setup/version 1 :setup/basis basis})

(def task
  (str "Discover synthetic products supporting agent teams, excluding personal autocomplete editors. "
       "Use clojure_eval and babashka.http-client. The web is a frozen lexical corpus, not the live internet. "
       "Search GET " web/search-url " with {:query-params {:q your-query :count 10}}; "
       "parse :body with cheshire.core/parse-string and keyword keys. Results are under [:web :results]. "
       "Fetch relevant result URLs. HTTP responses carry a receipt UUID at [:dvergr/acquisition :id]. "
       "Return exactly one EDN map {:search search-receipt-uuid :alternatives "
       "[{:url url :receipt fetch-receipt-uuid :quote complete-page-text} ...]}. "
       "All submitted URLs must occur in the cited search response. You may search again if needed. "
       "Do not fabricate receipts. No fences or commentary in the final answer."))

(defn definition []
  (environment/make-environment
   {:id :business/frozen-discovery :task task
    :verifier {:id :business/discovery-checks :version 1 :basis basis}
    :world {:isolation :ctx :settlement :discard :setup setup-ref}
    :limits {:timeout-ms 180000 :cancel-timeout-ms 10000}
    :metadata {:fixture-id fixture-id :kind :synthetic-discovery}}))

(defn world-setup []
  (evaluation/make-world-setup
   {:id (:setup/id setup-ref) :version 1 :basis basis
    :prepare (fn [_]
               (io/install-http-fixture!
                {:id fixture-id :transport (web/transport pages)
                 :env {"BRAVE_API_KEY" "offline-fixture"}})
               {:fixture-id fixture-id})}))

(def capture-policy
  {:allowed-origins #{"https://example.org" "https://api.search.brave.com"}
   :max-bytes 16384})

(defn score
  "Host-only verification; requires the control Room's capture policy enabled.
   Scope every lookup before reading captured data. No candidate-supplied body
   or computed score is authoritative."
  [room run-id answer]
  (let [conn (some-> room :store :conn)
        artifacts (some-> room :store :artifacts)
        shape? (and (map? answer) (= #{:search :alternatives} (set (keys answer)))
                    (uuid? (:search answer)))
        scoped (fn [id]
                 (when (uuid? id)
                   (d/q '[:find (pull ?e [*]) . :in $ ?id ?room ?run
                          :where [?e :acquisition/id ?id]
                          [?e :acquisition/room-id ?room] [?e :acquisition/run-id ?run]]
                        @conn id (:id room) run-id)))
        page-score (citations/verify room run-id references
                                     (when shape? (select-keys answer [:alternatives])))
        row (when shape? (scoped (:search answer)))
        search? (and (= fixture-id (:acquisition/fixture-id row))
                     (= "https://api.search.brave.com" (:acquisition/origin row))
                     (= :get (:acquisition/method row))
                     (= :completed (:acquisition/status row))
                     (= 200 (:acquisition/http-status row))
                     (= :captured (:acquisition/capture row))
                     (uuid? (:acquisition/body-store-ref row)))
        body (when search? (:body (artifact/get-value artifacts (:acquisition/body-store-ref row))))
        hits (when (string? body)
               (try (get-in (j/read-value body j/keyword-keys-object-mapper) [:web :results])
                    (catch Exception _ nil)))
        urls (set (map :url hits))
        grounded? (and shape? (get-in page-score [:checks :citations?]) search?
                       (every? #(and (contains? urls (:url %))
                                     (= fixture-id (:acquisition/fixture-id (scoped (:receipt %)))))
                               (:alternatives answer)))]
    (-> page-score
        (assoc-in [:checks :search-grounded?] (boolean grounded?))
        (update :reward #(if grounded? % 0.0)))))

(defn evaluator []
  (evaluation/make-evaluator
   {:id :business/discovery-checks :version 1 :basis basis
    :observe (fn [{:keys [room run-id result default setup/evidence]}]
               (assoc default :execution-status (:run/status result)
                      :fixture-id (:fixture-id evidence)
                      :verification (score room run-id (evidence/parse-answer (:result default)))))
    :verify (fn [_ observed]
              (let [complete? (and (= :completed (:execution-status observed))
                                   (= fixture-id (:fixture-id observed)))]
                (-> (:verification observed)
                    (assoc-in [:checks :completed?] (boolean complete?))
                    (update :reward #(if complete? % 0.0)))))}))
