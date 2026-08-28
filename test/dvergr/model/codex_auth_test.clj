(ns dvergr.model.codex-auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.model.api.codex-auth :as auth]
            [dvergr.model.gateway :as gateway]
            [jsonista.core :as json])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant]
           [java.util Base64]))

(defn- jwt [claims]
  (let [encoder (.withoutPadding (Base64/getUrlEncoder))
        encoded (fn [value]
                  (.encodeToString encoder
                                   (.getBytes (json/write-value-as-string value)
                                              StandardCharsets/UTF_8)))]
    (str (encoded {:alg "none"}) "." (encoded claims) ".signature")))

(defn- auth-state [access expiry]
  {:auth_mode "chatgpt"
   :tokens {:id_token (jwt {"https://api.openai.com/auth"
                            {:chatgpt_account_id "account-1"}})
            :access_token (jwt {:exp (.getEpochSecond expiry)
                                :token access})
            :refresh_token "refresh-1"
            :account_id "account-1"}
   :last_refresh (str (Instant/now))})

(deftest codex-file-credentials-resolve-without-exporting-storage
  (let [state (atom (auth-state "current" (.plusSeconds (Instant/now) 3600)))
        credentials (auth/create {:load-auth #(deref state)
                                  :save-auth #(reset! state %)
                                  :refresh-request (fn [_]
                                                     (throw (ex-info "unexpected refresh" {})))})
        resolved (gateway/resolve-auth! credentials
                                        {:url "https://chatgpt.com/backend-api/codex/responses"})]
    (is (re-find #"^Bearer " (get-in resolved [:headers "Authorization"])))
    (is (= "account-1" (get-in resolved [:headers "ChatGPT-Account-ID"])))
    (is (= :codex-subscription (gateway/credential-kind credentials)))
    (is (= "#<CodexFileCredentials>" (str credentials)))
    (is (not (re-find #"refresh-1" (str credentials))))))

(deftest expiring-codex-credentials-refresh-and-persist-rotation
  (let [state (atom (auth-state "old" (.minusSeconds (Instant/now) 10)))
        request-seen (atom nil)
        new-access (jwt {:exp (.getEpochSecond (.plusSeconds (Instant/now) 7200))
                         :token "new"})
        credentials (auth/create
                     {:load-auth #(deref state)
                      :save-auth #(reset! state %)
                      :refresh-request
                      (fn [request]
                        (reset! request-seen request)
                        {:status 200
                         :body (json/write-value-as-string
                                {:access_token new-access
                                 :refresh_token "refresh-2"})})})
        resolved (gateway/resolve-auth! credentials
                                        {:url "https://chatgpt.com/backend-api/codex/responses"})]
    (is (= "refresh_token" (:grant_type @request-seen)))
    (is (= "refresh-1" (:refresh_token @request-seen)))
    (is (= "refresh-2" (get-in @state [:tokens :refresh_token])))
    (is (= new-access (get-in @state [:tokens :access_token])))
    (is (= (str "Bearer " new-access)
           (get-in resolved [:headers "Authorization"])))))

(deftest native-availability-is-file-store-specific
  (testing "valid injected file state is available"
    (is (auth/available?
         {:load-auth #(auth-state "current" (.plusSeconds (Instant/now) 3600))})))
  (testing "missing state is not available"
    (is (false? (auth/available? {:load-auth (constantly nil)})))))
