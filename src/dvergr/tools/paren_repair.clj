;; Copyright (c) Bruce Hauman and clojure-mcp contributors
;; Copyright (c) 2026 Christian Weilbach
;; Licensed under Eclipse Public License v2.0
;;
;; Adapted from clojure-mcp:
;; https://github.com/bhauman/clojure-mcp/blob/main/src/clojure_mcp/tools/paren_repair/core.clj
;;
;; This file is licensed under EPL-2.0 only.
;; See THIRD-PARTY-LICENSES.md for full license text.
;;
;; Modifications: Simplified for dvergr architecture (removed nREPL dependencies,
;; removed cljfmt integration, adapted for direct file operations)

(ns dvergr.tools.paren-repair
  "Parenthesis/delimiter repair tool using parinfer.

   Use this tool when:
   - A file has unbalanced delimiters causing parse errors
   - LLM-generated code has paren mismatches
   - You need to repair a file after an errant edit

   This tool can save many wasted agent turns when LLMs generate
   functionally correct code but with broken delimiters."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.sexp.paren-utils :as paren-utils]))

(def clojure-extensions
  "Set of file extensions considered Clojure files."
  #{".clj" ".cljs" ".cljc" ".edn"})

(defn clojure-file?
  "Returns true if the file path has a Clojure file extension."
  [file-path]
  (let [path-str (str file-path)]
    (some #(str/ends-with? path-str %) clojure-extensions)))

(defn repair-file!
  "Repairs delimiter errors in a Clojure file.

   Arguments:
   - file-path: Absolute path to the file to repair

   Returns a map with:
   - :success - boolean indicating overall success
   - :file-path - the processed file path
   - :message - human-readable status message
   - :original - original file content (if repaired)
   - :repaired - repaired file content (if repaired)"
  [file-path]
  (let [file (io/file file-path)]
    (cond
      ;; File doesn't exist
      (not (.exists file))
      {:success false
       :file-path file-path
       :message "File does not exist"}

      ;; Not a Clojure file
      (not (clojure-file? file-path))
      {:success false
       :file-path file-path
       :message "Not a Clojure file (expected .clj, .cljs, .cljc, or .edn)"}

      :else
      (try
        (let [original-content (slurp file-path :encoding "UTF-8")
              has-delimiter-error? (paren-utils/delimiter-error? original-content)]

          (if-not has-delimiter-error?
            ;; No delimiter errors
            {:success true
             :file-path file-path
             :message "No delimiter errors found"}

            ;; Try to repair
            (if-let [repaired-content (paren-utils/parinfer-repair original-content)]
              (do
                ;; Write repaired content
                (spit file-path repaired-content :encoding "UTF-8")
                {:success true
                 :file-path file-path
                 :message "Delimiter errors fixed with parinfer"
                 :original original-content
                 :repaired repaired-content})

              ;; Repair failed
              {:success false
               :file-path file-path
               :message "Could not repair delimiter errors (parinfer failed)"
               :original original-content})))

        (catch Exception e
          {:success false
           :file-path file-path
           :message (str "Error: " (.getMessage e))})))))

(defn repair-code-string
  "Repairs delimiter errors in a code string without writing to disk.

   Arguments:
   - code-str: Clojure code string to repair

   Returns a map with:
   - :success - boolean indicating success
   - :has-error - boolean indicating if original had delimiter error
   - :repaired - repaired code string (if successful)
   - :message - human-readable status message"
  [code-str]
  (try
    (let [has-error? (paren-utils/delimiter-error? code-str)]
      (if-not has-error?
        {:success true
         :has-error false
         :repaired code-str
         :message "No delimiter errors"}

        ;; Try to repair
        (if-let [repaired (paren-utils/parinfer-repair code-str)]
          {:success true
           :has-error true
           :repaired repaired
           :message "Delimiter errors fixed"}

          {:success false
           :has-error true
           :message "Could not repair delimiter errors"})))

    (catch Exception e
      {:success false
       :message (str "Error: " (.getMessage e))})))

;; Tool definition for agent use
(def tool-spec
  {:name "paren_repair"
   :description "Fix unbalanced parentheses, brackets, and braces in Clojure code using parinfer.

Use this tool when:
- A file has unbalanced delimiters causing parse errors
- LLM-generated code has paren mismatches
- You need to repair a file after an errant edit
- The file won't compile due to delimiter errors

This tool uses parinfer's indent mode to infer correct delimiter placement from indentation.
It can save many wasted turns when code is semantically correct but has broken delimiters."

   :parameters {:type "object"
                :properties {:file_path {:type "string"
                                         :description "Absolute path to the Clojure file to repair (.clj, .cljs, .cljc, .edn)"}}
                :required ["file_path"]}

   :handler (fn [params]
              (repair-file! (:file_path params)))})

(comment
  ;; Test delimiter detection
  (paren-utils/delimiter-error? "(defn foo [x] (+ x 1)")  ;; => true
  (paren-utils/delimiter-error? "(defn foo [x] (+ x 1))") ;; => false

  ;; Test repair
  (repair-code-string "(defn foo [x]\n  (+ x 1")
  ;; => {:success true, :has-error true, :repaired "(defn foo [x]\n  (+ x 1))", :message "Delimiter errors fixed"}

  ;; Test file repair
  (spit "/tmp/test.clj" "(defn foo [x]\n  (+ x 1" :encoding "UTF-8")
  (repair-file! "/tmp/test.clj")
  ;; => {:success true, :file-path "/tmp/test.clj", :message "Delimiter errors fixed", ...}
  )
