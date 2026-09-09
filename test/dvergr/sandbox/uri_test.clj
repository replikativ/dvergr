(ns dvergr.sandbox.uri-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.context :as ctx]))

(def references
  [["feed.xml" "https://example.org/blog/feed.xml"]
   ["../feed.xml" "https://example.org/feed.xml"]
   ["/feed.xml" "https://example.org/feed.xml"]
   ["//feeds.example.net/rss" "https://feeds.example.net/rss"]
   ["https://elsewhere.example/rss?q=1" "https://elsewhere.example/rss?q=1"]
   ["./rss?q=1" "https://example.org/blog/rss?q=1"]])

(defn check-uri [interpreter runtime]
  (doseq [[reference expected] references]
    (let [r (sandbox/eval-code
             interpreter
             (str "(str (.resolve (java.net.URI. \"https://example.org/blog/\") "
                  (pr-str reference) "))")
             :execution-context runtime)]
      (is (:success r))
      (is (= expected (:value r))))))

(deftest uri-in-session-and-managed-world
  (let [runtime (ctx/create-execution-context)]
    (try
      (testing "ordinary session uses the same class surface"
        (check-uri (sandbox/fork-for-session runtime) runtime))
      (let [ref (sandbox/create-spindel-sci-world! runtime)
            interpreter (sandbox/sci-context-in runtime ref)]
        (sandbox/setup-agent-namespaces! interpreter runtime)
        (check-uri interpreter runtime)
        (is (:success (sandbox/eval-code
                       interpreter
                       "(def location (atom (java.net.URI. \"https://example.org/blog/\")))"
                       :execution-context runtime)))
        (let [child (ctx/fork-context runtime)]
          (try
            (let [child-sci (sandbox/sci-context-in child ref)]
              (check-uri child-sci child)
              (is (= "https://example.org/blog/feed.xml"
                     (:value (sandbox/eval-code
                              child-sci
                              "(swap! location #(.resolve % \"feed.xml\")) (str @location)"
                              :execution-context child))))
              (is (= "https://example.org/blog/"
                     (:value (sandbox/eval-code interpreter "(str @location)"
                                                :execution-context runtime)))))
            (finally (ctx/close-context! child)))))
      (finally (ctx/close-context! runtime)))))

(deftest uri-does-not-enable-url-or-jvm-authority
  (let [runtime (ctx/create-execution-context)
        ref (sandbox/create-spindel-sci-world! runtime)
        interpreter (sandbox/sci-context-in runtime ref)]
    (try
      (sandbox/setup-agent-namespaces! interpreter runtime)
      ;; Harmless probes: no network requests or process operations performed.
      (doseq [form ["(java.net.URL. \"https://example.invalid\")"
                    "(.toURL (java.net.URI. \"https://example.invalid\"))"
                    "(.getProtocol (.toURL (java.net.URI. \"https://example.invalid\")))"
                    "(System/getProperty \"java.version\")"
                    "(Class/forName \"java.lang.System\")"]]
        (is (false? (:success (sandbox/eval-code interpreter form
                                                 :execution-context runtime))) form))
      (finally (ctx/close-context! runtime)))))
