(ns dvergr.model.cli-process-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.model.cli-process :as process])
  (:import [java.io BufferedReader InputStreamReader IOException]))

(deftest a-dead-parent-cannot-hide-a-pipe-holding-child
  (let [p (.start (ProcessBuilder. ^java.util.List
                   (process/command ["/bin/sh" "-c" "sleep 30 & echo $!; exit 0"])))
        child-pid (with-open [reader (BufferedReader. (InputStreamReader. (.getInputStream p)))]
                    (Long/parseLong (.readLine reader)))]
    (try
      (.waitFor p)
      (is (some #(= child-pid (.pid ^java.lang.ProcessHandle %)) (process/live-members (.pid p))))
      (process/stop-session! p)
      (is (false? (.isAlive p)))
      (is (empty? (process/live-members (.pid p))))
      (finally (process/stop-session! p)))))

(deftest uncertain-enumeration-does-not-certify-cleanup
  (let [p (.start (ProcessBuilder. ^java.util.List (process/command ["/bin/sleep" "30"])))
        unavailable? (atom true)
        scan process/live-members]
    (try
      (let [done (future
                   (with-redefs [process/live-members (fn [id] (if @unavailable? (throw (IOException. "fixture")) (scan id)))]
                     (process/stop-session! p)))]
        (is (= ::pending (deref done 100 ::pending)))
        (reset! unavailable? false)
        (is (not= ::pending (deref done 5000 ::pending)))
        (is (empty? (scan (.pid p)))))
      (finally (reset! unavailable? false) (process/stop-session! p)))))
