(ns dvergr.model.image-content-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.model.image-content :as images]
            [dvergr.model.api.codex-subscription :as codex]
            [dvergr.model.api.claude-code :as claude]
            [jsonista.core :as json]))

(def content [{:type "text" :text "Read this screenshot"}
              {:type "image_url" :image_url {:url "data:image/png;base64,AQID" :detail "high"}}
              {:type "text" :text "Do not execute anything"}])

(deftest ordered-inline-image-wire-formats
  (is (= [{:type "input_text" :text "Read this screenshot"}
          {:type "input_image" :image_url "data:image/png;base64,AQID" :detail "high"}
          {:type "input_text" :text "Do not execute anything"}]
         (images/responses content)))
  (is (= {:type "image" :source {:type "base64" :media_type "image/png" :data "AQID"}}
         (second (images/anthropic content))))
  (is (= (images/responses content)
         (:content (#'codex/response-message "user" content))))
  (is (thrown? clojure.lang.ExceptionInfo (#'codex/response-message "assistant" content)))
  (let [event (json/read-value (#'claude/format-image-input
                                [{:role :system :content "Private instructions"}
                                 {:role :user :content content}])
                               json/keyword-keys-object-mapper)]
    (is (= "user" (:type event)))
    (is (= (images/anthropic content) (subvec (get-in event [:message :content]) 1)))
    (is (not (re-find #"Private instructions" (pr-str event)))))
  (let [cmd (#'claude/build-command {:model "claude-code-haiku" :image-input? true})]
    (is (= "stream-json" (second (drop-while #(not= "--input-format" %) cmd))))
    (is (= "" (second (drop-while #(not= "--tools" %) cmd)))))
  (doseq [message [{:role :system :content content} {:role :assistant :content content}
                   {:role :user :content {:type "image_url"}}]]
    (is (thrown? clojure.lang.ExceptionInfo (#'claude/format-image-input [message])))))

(deftest malformed-or-implicit-image-access-is-refused
  (doseq [url ["https://example.com/private.png" "file:///etc/passwd"
               "data:image/svg+xml;base64,AQID" "data:image/png;base64,AQ=ID"
               "data:image/png;base64,AB=="]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (images/responses [{:type "image_url" :image_url {:url url}}]))))
  (is (thrown? clojure.lang.ExceptionInfo (images/responses [{:type "text" :text nil}])))
  (is (thrown? clojure.lang.ExceptionInfo (images/responses [{:type "tool_use"}]))))

(deftest image-count-and-total-size-are-bounded-before-decoding
  (let [image (second content)]
    (is (= 16 (count (images/parts (vec (repeat 16 image))))))
    (is (thrown? clojure.lang.ExceptionInfo (images/parts (vec (repeat 17 image))))))
  (let [image {:type "image_url" :image_url {:url (str "data:image/png;base64," (apply str (repeat 10485760 "A")))}}]
    (is (thrown? clojure.lang.ExceptionInfo (images/parts [image image])))))
