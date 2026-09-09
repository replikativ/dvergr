(ns dvergr.tools.clojure-edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.tools :as tools]
            [muschel.fs :as fs]
            [muschel.fs.geschichte :as gfs]))

(deftest advertised-edit-operations-work-on-virtual-files
  (let [{:keys [close!] :as repository} (gfs/memory-repository! {:name "edit-operations"})
        filesystem (gfs/make-root repository)
        context (tools/make-context {:cwd "/" :filesystem filesystem})
        original "(ns demo)\n(defn answer [] 41)\n"
        form '(defn answer [] 41)
        helper '(defn helper [] 42)]
    (try
      (doseq [[operation new-source expected]
              [["replace" "(defn answer [] 42)" '[(ns demo) (defn answer [] 42)]]
               ["insert_before" (pr-str helper) [(list 'ns 'demo) helper form]]
               ["insert_after" (pr-str helper) [(list 'ns 'demo) form helper]]]]
        (testing operation
          (is (= :success (:type (tools/execute "write_file"
                                                {:path "demo.clj" :content original} context))))
          (is (= :success (:type (tools/execute "clojure_edit"
                                                {:file_path "demo.clj" :form_type "defn"
                                                 :form_name "answer" :operation operation
                                                 :new_source new-source} context))))
          (is (= expected (read-string (str "[" (fs/read-file filesystem "/demo.clj") "]"))))))
      (is (nil? (fs/physical-path filesystem "/demo.clj")))
      (finally (close!)))))
