(ns dvergr.tools.structural
  "Structural editing tools for Clojure code using rewrite-clj."
  (:require [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Form Finding
;; ---------------------------------------------------------------------------

(defn find-top-level-form
  "Find a top-level form by type and name.

   form-type: 'defn', 'def', 'ns', 'defmethod', etc.
   form-name: symbol name or string

   Returns zipper location of the form, or nil if not found."
  [zloc form-type form-name]
  (loop [loc zloc]
    (cond
      (z/end? loc) nil

      ;; Check if this is the form we're looking for
      (and (z/list? loc)
           (= form-type (-> loc z/down z/sexpr str))
           (or
            ;; Simple forms: (defn foo ...)
            (= form-name (-> loc z/down z/right z/sexpr str))
            ;; ns forms: (ns foo.bar ...)
            (and (= form-type "ns")
                 (= form-name (-> loc z/down z/right z/sexpr str)))
            ;; defmethod: (defmethod area :rectangle ...)
            (and (= form-type "defmethod")
                 (str/starts-with? form-name
                   (str (-> loc z/down z/right z/sexpr))))))
      loc

      :else (recur (z/next loc)))))

;; ---------------------------------------------------------------------------
;; Editing Operations
;; ---------------------------------------------------------------------------

(defn replace-form
  "Replace a form with new source code."
  [zloc new-source]
  (let [new-node (-> new-source z/of-string z/node)]
    (-> zloc
        (z/replace new-node)
        z/root-string)))

(defn insert-before-form
  "Insert source before a form."
  [zloc new-source]
  (let [new-node (-> new-source z/of-string z/node)]
    (-> zloc
        (z/insert-left new-node)
        (z/insert-left (n/newlines 2))
        z/root-string)))

(defn insert-after-form
  "Insert source after a form."
  [zloc new-source]
  (let [new-node (-> new-source z/of-string z/node)]
    (-> zloc
        (z/insert-right (n/newlines 2))
        (z/insert-right new-node)
        z/root-string)))

;; ---------------------------------------------------------------------------
;; Main Edit Function
;; ---------------------------------------------------------------------------

(defn edit-clojure-form
  "Edit a Clojure form in a file.

   Parameters:
   - file-path: path to file
   - form-type: type of form (defn, def, ns, etc.)
   - form-name: name of the form
   - operation: :replace, :insert-before, or :insert-after
   - new-source: new source code

   Returns:
   - {:success true :content new-file-content}
   - {:success false :error error-message}"
  [file-path form-type form-name operation new-source]
  (try
    (let [file (io/file file-path)]
      (if-not (.exists file)
        {:success false
         :error (str "File not found: " file-path)}

        (let [source (slurp file)
              zloc (z/of-string source)
              found (find-top-level-form zloc form-type form-name)]

          (if-not found
            {:success false
             :error (str "Form not found: " form-type " " form-name)}

            (let [new-content (case operation
                                :replace (replace-form found new-source)
                                :insert-before (insert-before-form found new-source)
                                :insert-after (insert-after-form found new-source))]
              {:success true
               :content new-content
               :old-form (z/string found)})))))

    (catch Exception e
      {:success false
       :error (str "Error editing form: " (.getMessage e))})))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate-clojure-syntax
  "Check if source code has valid Clojure syntax.
   Returns {:valid true} or {:valid false :error message}"
  [source]
  (try
    (z/of-string source)
    {:valid true}
    (catch Exception e
      {:valid false
       :error (.getMessage e)})))
