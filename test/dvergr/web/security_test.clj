(ns dvergr.web.security-test
  "The web server's browser boundary: Host (DNS rebinding), Origin, the CSRF
   token, side-effect-free GETs, no wildcard CORS, and escaped HTML. Requests
   go straight to `server/handler`, so no port is bound."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.discourse :as discourse]
            [dvergr.discourse.commands :as commands]
            [dvergr.ops :as ops]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as rstore]
            [dvergr.scheduler.core :as scheduler]
            [dvergr.system.db :as sdb]
            [dvergr.web.agents :as web-agents]
            [dvergr.web.dashboard :as dash]
            [dvergr.web.guard :as guard]
            [dvergr.web.server :as server]
            [hiccup2.core :as h]
            [org.replikativ.spindel.engine.context :as ctx])
  (:import (java.io ByteArrayInputStream)))

(defn- mock-daemon []
  {:config {} :execution-ctx (ctx/create-execution-context) :status (atom :running)})

(defn- app
  ([] (app {}))
  ([opts] (server/handler (mock-daemon) (merge {:ip "127.0.0.1"} opts))))

(def ^:private host "127.0.0.1:17880")

(defn- req [method uri & {:keys [headers body]}]
  (cond-> {:request-method method :uri uri
           :headers (merge {"host" host} headers)}
    body (assoc :body (ByteArrayInputStream. (.getBytes ^String body "UTF-8")))))

(defn- session
  "A browser session: the cookie and the token the dashboard hands out."
  [app]
  (let [resp (app (req :get "/dashboard"))
        cookie (some-> (get-in resp [:headers "Set-Cookie"]) (str/split #";") first)
        token (second (re-find #"content=\"([0-9a-f]{64})\"" (:body resp)))]
    {:cookie cookie :token token}))

(defn- form-post [app uri form {:keys [cookie]} & {:keys [headers]}]
  (app (req :post uri
            :headers (merge {"content-type" "application/x-www-form-urlencoded"
                             "origin" (str "http://" host)}
                            (when cookie {"cookie" cookie})
                            headers)
            :body form)))

(defmacro ^:private with-room
  "Run `body` with a fake room `r` and record `/post` executions in `calls`."
  [calls & body]
  `(with-redefs [rstore/slug->room-id identity
                 rreg/lookup (fn [_#] {:slug "r"})
                 discourse/room-target (constantly :var)
                 discourse/post! (fn [& a#] (swap! ~calls conj [:post a#]))
                 commands/execute! (fn [& a#] (swap! ~calls conj [:execute a#]))]
     ~@body))

(def ^:private eval-cmd "content=%2Fclojure_eval+var+%28%2B+1+2%29")

(deftest a-cross-site-post-is-refused
  (let [app (app) s (session app) calls (atom [])]
    (with-room calls
      (testing "the attack: another site's form posts a tool command"
        (is (= 403 (:status (form-post app "/rooms/r/post" (str eval-cmd "&csrf=" (:token s)) s
                                       :headers {"origin" "https://evil.example"}))))
        (is (= 403 (:status (form-post app "/rooms/r/post" eval-cmd {}
                                       :headers {"origin" "https://evil.example"})))))
      (testing "a Referer stands in for a missing Origin"
        (is (= 403 (:status (form-post app "/rooms/r/post" (str eval-cmd "&csrf=" (:token s)) s
                                       :headers {"origin" nil
                                                 "referer" "https://evil.example/page"})))))
      (testing "an opaque (null) origin is refused"
        (is (= 403 (:status (form-post app "/rooms/r/post" (str eval-cmd "&csrf=" (:token s)) s
                                       :headers {"origin" "null"})))))
      (testing "a localhost page on another port is another origin"
        (is (= 403 (:status (form-post app "/rooms/r/post" (str eval-cmd "&csrf=" (:token s)) s
                                       :headers {"origin" "http://127.0.0.1:3000"})))))
      (is (empty? @calls) "nothing ran"))))

(deftest a-missing-or-wrong-csrf-token-is-refused
  (let [app (app) s (session app) other (session app) calls (atom [])]
    (is (re-matches #"[0-9a-f]{64}" (str (:token s))) "the dashboard carries a token")
    (is (str/includes? (str (:cookie s)) guard/session-cookie) "and sets a session cookie")
    (with-room calls
      (testing "no token"
        (is (= 403 (:status (form-post app "/rooms/r/post" eval-cmd s)))))
      (testing "no session cookie"
        (is (= 403 (:status (form-post app "/rooms/r/post" (str eval-cmd "&csrf=" (:token s)) {})))))
      (testing "a wrong token"
        (is (= 403 (:status (form-post app "/rooms/r/post" (str eval-cmd "&csrf=" (apply str (repeat 64 "0"))) s)))))
      (testing "another session's token"
        (is (= 403 (:status (form-post app "/rooms/r/post" (str eval-cmd "&csrf=" (:token other)) s)))))
      (testing "a token from another server (restarted: new secret)"
        (let [s2 (session (dvergr.web.security-test/app))]
          (is (= 403 (:status (form-post app "/rooms/r/post" (str eval-cmd "&csrf=" (:token s2)) s))))))
      (is (empty? @calls))
      (testing "the same-origin form with its token goes through"
        (let [resp (form-post app "/rooms/r/post" (str eval-cmd "&csrf=" (:token s)) s)]
          (is (= 303 (:status resp)))
          (is (= [:execute] (map first @calls)))))
      (testing "the token may come as the X-CSRF-Token header (htmx, fetch)"
        (reset! calls [])
        (is (= 303 (:status (form-post app "/rooms/r/post" "content=hello" s
                                       :headers {"x-csrf-token" (:token s)}))))
        (is (= [:post] (map first @calls)))))))

(deftest voice-uploads-need-the-token
  (let [app (app) s (session app)]
    (with-redefs [rstore/slug->room-id identity rreg/lookup (constantly nil)]
      (is (= 403 (:status (app (req :post "/rooms/r/voice"
                                    :headers {"content-type" "audio/webm" "cookie" (:cookie s)
                                              "origin" (str "http://" host)}
                                    :body "audio"))))))))

(def ^:private room-actions
  [["/api/rooms/r/delete" :room/delete] ["/api/rooms/r/fork" :room/fork]
   ["/api/rooms/r/merge" :room/merge] ["/api/rooms/r/discard" :room/discard]
   ["/agents/a/delete" :agent/delete] ["/agents/a/open" :agent/open]])

(deftest gets-have-no-side-effects
  (let [app (app) s (session app) calls (atom [])]
    (with-redefs [ops/invoke (fn [_ op args] (swap! calls conj [op args]) {:id "a"})]
      (testing "the actions are no longer reachable by GET (link, img, prefetch)"
        (doseq [[uri _] room-actions]
          (let [resp (app (req :get uri))]
            (is (not (#{200 301 302 303} (:status resp))) uri)))
        (is (empty? @calls)))
      (testing "they are POSTs that carry the token"
        (doseq [[uri op] room-actions]
          (reset! calls [])
          (is (= 303 (:status (form-post app uri (str "csrf=" (:token s)) s))) uri)
          (is (= [op] (map first @calls)) uri))))))

(deftest the-host-header-must-name-this-server
  (testing "a rebound DNS name is refused, reads included"
    (let [app (app)]
      (is (= 403 (:status (app (req :get "/dashboard" :headers {"host" "evil.example:17880"})))))
      (is (= 403 (:status (app (req :get "/api/health" :headers {"host" "evil.example"})))))
      (is (= 403 (:status (app (req :get "/api/health" :headers {"host" nil})))))
      (doseq [h ["127.0.0.1:17880" "localhost:17880" "LOCALHOST" "[::1]:17880"]]
        (is (= 200 (:status (app (req :get "/api/health" :headers {"host" h})))) h))))
  (testing "configured names (a reverse proxy) are accepted"
    (let [app (app {:allowed-hosts ["dvergr.lan"]})]
      (is (= 200 (:status (app (req :get "/api/health" :headers {"host" "dvergr.lan:8443"})))))
      (is (= 403 (:status (app (req :get "/api/health" :headers {"host" "evil.example"})))))))
  (testing "a non-loopback bind accepts its own address"
    (let [app (app {:ip "10.1.2.3"})]
      (is (= 200 (:status (app (req :get "/api/health" :headers {"host" "10.1.2.3:17880"})))))
      (is (= 403 (:status (app (req :get "/api/health" :headers {"host" "10.1.2.4:17880"})))))))
  (testing "a wildcard bind accepts loopback and refuses names"
    (let [app (app {:ip "0.0.0.0"})]
      (is (= 200 (:status (app (req :get "/api/health" :headers {"host" "127.0.0.1:17880"})))))
      (is (= 403 (:status (app (req :get "/api/health" :headers {"host" "evil.example"}))))))))

(deftest allowed-origins-admit-a-proxy-origin
  (let [app (app {:allowed-hosts ["127.0.0.1"] :allowed-origins ["https://dvergr.example.com"]})
        s (session app) calls (atom [])]
    (with-room calls
      (is (= 303 (:status (form-post app "/rooms/r/post" (str "content=hi&csrf=" (:token s)) s
                                     :headers {"origin" "https://dvergr.example.com"}))))
      (is (= 403 (:status (form-post app "/rooms/r/post" (str "content=hi&csrf=" (:token s)) s
                                     :headers {"origin" "https://other.example.com"})))))))

(deftest no-wildcard-cors
  (let [app (app)]
    (doseq [uri ["/api/health" "/api/agents" "/nonexistent"]]
      (is (nil? (get-in (app (req :get uri)) [:headers "Access-Control-Allow-Origin"])) uri))))

(deftest the-json-api-refuses-cross-site-writes
  (let [app (app) calls (atom [])]
    (with-redefs [ops/invoke (fn [_ op args] (swap! calls conj [op args]) {:ok true})]
      (testing "another origin"
        (is (= 403 (:status (app (req :post "/api/v1/room_create"
                                      :headers {"content-type" "application/json"
                                                "origin" "https://evil.example"}
                                      :body "{\"title\":\"x\"}"))))))
      (testing "a body a cross-site form can send (text/plain, no Origin)"
        (is (= 415 (:status (app (req :post "/api/v1/room_create"
                                      :headers {"content-type" "text/plain"}
                                      :body "{\"title\":\"x\"}"))))))
      (is (empty? @calls))
      (testing "a machine client (JSON, no Origin) needs no session"
        (is (= 200 (:status (app (req :post "/api/v1/room_create"
                                      :headers {"content-type" "application/json"}
                                      :body "{\"title\":\"x\"}")))))
        (is (= [:room/create] (map first @calls)))))))

(deftest schedule-html-is-escaped
  (let [app (app)]
    (with-redefs [scheduler/list-all-schedules
                  (constantly [{:description "<script>alert(1)</script>" :agent-id :a
                                :room "<b>r</b>" :next-fire "<i>soon</i>"}])]
      (let [{:keys [status body]} (app (req :get "/api/schedules" :headers {"accept" "text/html"}))]
        (is (= 200 status))
        (is (not (str/includes? body "<script>")))
        (is (not (str/includes? body "<b>")))
        (is (not (str/includes? body "<i>")))
        (is (str/includes? body "&lt;script&gt;alert(1)&lt;/script&gt;"))
        (is (str/includes? body "schedule-card"))))))

(deftest app-pages-escape-and-isolate
  (let [app (app)]
    (with-redefs [sdb/room-by-slug (constantly nil)]
      (testing "the not-found page escapes the slug"
        (let [{:keys [body]} (app (req :get "/apps/%3Cscript%3Ealert(1)%3C%2Fscript%3E/"))]
          (is (not (str/includes? body "<script>")))
          (is (str/includes? body "&lt;script&gt;"))))
      (testing "the trailing-slash redirect does not put a decoded slug in a header"
        (let [resp (app (req :get "/apps/a%0d%0aSet-Cookie:x=1"))]
          (is (= 301 (:status resp)))
          (is (not (re-find #"[\r\n]" (get-in resp [:headers "Location"])))))))))

(deftest templates-post-their-actions-with-the-token
  (binding [guard/*csrf-token* "tok"]
    (testing "the room tree's fork and delete are POST forms"
      (let [html (str (h/html (#'dash/room-node-hiccup {:slug "s" :title "t"} {})))]
        (is (not (str/includes? html "href=\"/api/rooms/s/")))
        (is (str/includes? html "action=\"/api/rooms/s/fork\""))
        (is (str/includes? html "action=\"/api/rooms/s/delete\""))
        (is (str/includes? html "method=\"post\""))
        (is (str/includes? html "value=\"tok\""))))
    (testing "a fork's merge and discard are POST forms"
      (let [html (str (h/html (#'dash/fork-node-hiccup {:slug "s/fork-1" :title "f"} {})))]
        (is (not (str/includes? html "href=\"/api/rooms/s/fork-1/")))
        (is (str/includes? html "action=\"/api/rooms/s/fork-1/merge\""))
        (is (str/includes? html "action=\"/api/rooms/s/fork-1/discard\""))))
    (testing "the agent card's Chat is a POST and its text is escaped once"
      (let [html (str (h/html (#'web-agents/agent-card {:id :a :description "x & y"
                                                        :persona-source :builtin})))]
        (is (str/includes? html "action=\"/agents/a/open\""))
        (is (str/includes? html "x &amp; y"))
        (is (not (str/includes? html "&amp;amp;")))))
    (testing "the page shell hands htmx and scripts the token"
      (let [html (dash/shell {} [:p "x"])]
        (is (str/includes? html "content=\"tok\""))
        (is (str/includes? html "X-CSRF-Token"))))))
