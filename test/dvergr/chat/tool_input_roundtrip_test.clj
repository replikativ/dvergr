(ns dvergr.chat.tool-input-roundtrip-test
  "H1 — tool-input persistence is TOTAL: an input that cannot be typed against a
   tool's schema (or a hallucinated/unregistered tool) is preserved as a raw-EDN
   fallback entity instead of dropped, and round-trips back to the original
   argument map on replay. Guards against both the char-explosion storm class
   and silent argument loss."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [dvergr.chat.schema :as sch]
            [dvergr.chat.tool-schema :as ts]
            [dvergr.model.api.anthropic :as anthropic]
            [dvergr.model.api.openai :as openai]
            [dvergr.model.provider :as p]
            [jsonista.core :as json]))

(defn- fresh-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? false}]
    (d/create-database cfg)
    (doto (d/connect cfg) (sch/ensure-full-schema!))))

(deftest reconstruct-tool-args
  (testing "raw-EDN fallback entity round-trips to the original argument map"
    (let [orig {:tags ["a" "b"] :relevance 5}
          raw  (ts/raw-input->entity "mystery_tool" orig)]
      (is (= orig (ts/input-entity->args raw)))))
  (testing "a structured entity has its datahike namespace prefixes stripped"
    (is (= {:tags ["a"] :title "x"}
           (ts/input-entity->args
            {:tool-input.knowledge-add/tags ["a"]
             :tool-input.knowledge-add/title "x"}))))
  (testing "nil / non-map input never replays as null (a hard 400)"
    (is (= {} (ts/input-entity->args nil)))
    (is (= {} (ts/input-entity->args "not-a-map")))))

(deftest raw-fallback-schema-is-installed
  (testing "ensure-full-schema! installs the :tool-input.raw/* fallback attrs"
    (let [conn (fresh-conn)]
      (is (boolean (d/q '[:find ?e . :where [?e :db/ident :tool-input.raw/content]]
                        (d/db conn))))
      (is (boolean (d/q '[:find ?e . :where [?e :db/ident :tool-input.raw/tool-name]]
                        (d/db conn)))))))

(deftest persist-then-replay-round-trip
  (testing "an un-typeable tool input persists as raw EDN, pulls back, and
            reconstructs to the original args for replay (was silently dropped)"
    (let [conn    (fresh-conn)
          orig    {:tags ["founders" "tools"] :relevance 5}
          raw-ent (ts/raw-input->entity "mystery_tool" orig)
          chat-id (random-uuid)
          msg-id  (random-uuid)]
      (d/transact conn [{:chat/id chat-id}])
      (d/transact conn [{:message/id msg-id
                         :message/chat [:chat/id chat-id]
                         :message/role :assistant
                         :message/content "x"
                         :message/created-at (java.util.Date.)
                         :message/tool-uses [{:tool-use/id "tu1"
                                              :tool-use/name "mystery_tool"
                                              :tool-use/input raw-ent}]}])
      (let [eid    (d/q '[:find ?m . :in $ ?mid :where [?m :message/id ?mid]]
                        (d/db conn) msg-id)
            pulled (d/pull (d/db conn) '[*] eid)
            tu     (first (:message/tool-uses pulled))]
        (is (= orig (ts/input-entity->args (:tool-use/input tu))))))))

(deftest provider-formatters-replay-guards
  (testing "the LIVE provider formatters share the one args reconstruction:
            a raw-EDN fallback entity round-trips, a poisoned name is cleaned,
            and input is never null — through the ACTUAL MessageFormatter path
            (the guards used to live only in the deleted legacy formatter)"
    (let [orig     {:tags ["a" "b"] :relevance 5}
          raw-ent  (ts/raw-input->entity "knowledge_add" orig)
          messages [{:message/role :assistant
                     :message/content "calling tools"
                     :message/tool-uses
                     [{:tool-use/id "tu1"
                       :tool-use/name "knowledge_add<arg_key>leak</arg_key>"
                       :tool-use/input raw-ent}
                      {:tool-use/id "tu2"
                       :tool-use/name "shell"
                       :tool-use/input nil}]}]
          anth     (anthropic/->AnthropicProvider {:api-key "test"})
          oai      (openai/->OpenAIProvider {:api-key "test"})]
      (let [[msg]  (p/format-messages anth messages "claude-x")
            blocks (filterv #(= "tool_use" (:type %)) (:content msg))]
        (is (= "knowledge_add" (:name (first blocks))))
        (is (= orig (:input (first blocks))))
        (is (= {} (:input (second blocks)))))
      (let [[msg] (p/format-messages oai messages "gpt-x")
            calls (:tool_calls msg)]
        (is (= "knowledge_add" (get-in (first calls) [:function :name])))
        (is (= orig (json/read-value (get-in (first calls) [:function :arguments])
                                     (json/object-mapper {:decode-key-fn true}))))
        (is (= "{}" (get-in (second calls) [:function :arguments])))))))
