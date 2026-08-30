(ns dvergr.web.dashboard-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dvergr.web.dashboard :as dashboard]
            [hiccup2.core :as h]))

(def ^:private message-hiccup #'dashboard/message-hiccup)

(deftest message-renders-semantic-activity-with-run-correlation
  (let [html (str (h/html
                   (message-hiccup
                    {:id (random-uuid)
                     :from :var
                     :role :tool
                     :content "used a tool"
                     :run-id (java.util.UUID/fromString "12345678-1234-1234-1234-123456789abc")
                     :activities [{:activity/kind :tool
                                   :activity/verb :invoke
                                   :activity/tool-name "clojure_eval"}]})))]
    (is (str/includes? html "invoke · clojure_eval"))
    (is (str/includes? html "run 12345678"))))
