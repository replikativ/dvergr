;; Copyright (c) Bruce Hauman and clojure-mcp contributors
;; Licensed under Eclipse Public License v2.0
;;
;; Adapted from clojure-mcp:
;; https://github.com/bhauman/clojure-mcp/blob/main/src/clojure_mcp/sexp/match.clj
;;
;; This file is licensed under EPL-2.0 only.
;; See THIRD-PARTY-LICENSES.md for full license text.

(ns dvergr.sexp.match
  "Pattern matching for s-expressions with wildcards.

   Wildcards:
   - `_?` consumes exactly one form
   - `_*` consumes zero or more forms

   Examples:
   (match-sexpr '(defn _? [_*] _*)
                '(defn foo [x y] (+ x y)))  ;; => true

   (match-sexpr '(defmethod area :rectangle [_?] _*)
                '(defmethod area :rectangle [shape] (* (:w shape) (:h shape))))  ;; => true"
  (:require [rewrite-clj.zip :as z]))

(defn match-sexpr
  "Return true if `pattern` matches `data`.
   Wildcards in `pattern`:
     - `_?` consumes exactly one form
     - `_*` consumes zero or more forms, but if there are more pattern elements
       after it, it will try to align them with the tail of `data`."
  [pattern data]
  (cond
    ;; both are sequences ⇒ walk with possible '_*' backtracking
    (and (sequential? pattern) (sequential? data))
    (letfn [(match-seq [ps ds]
              (cond
                ;; pattern exhausted ⇒ only match if data also exhausted
                (empty? ps)
                (empty? ds)

                ;; '_?' ⇒ must have at least one ds, then consume exactly one
                (= (first ps) '_?)
                (and (seq ds)
                     (recur (rest ps) (rest ds)))

                ;; '_*' ⇒ two cases:
                ;; 1) no more pattern ⇒ matches anything
                ;; 2) with remaining pattern, try every split point
                (= (first ps) '_*)
                (let [ps-rest (rest ps)]
                  (if (empty? ps-rest)
                    true ;; Case 1: No more pattern elements after _*, so it matches anything
                    ;; Case 2: Try matching remaining pattern at each possible position
                    (loop [k 0]
                      (cond
                        ;; We've gone beyond the end of ds, no match
                        (> k (count ds))
                        false

                        ;; Try matching rest of pattern against rest of data starting at position k
                        (match-seq ps-rest (drop k ds))
                        true

                        ;; Try next position
                        :else
                        (recur (inc k))))))

                ;; nested list/vector ⇒ recurse
                (and (sequential? (first ps))
                     (sequential? (first ds)))
                (and (match-sexpr (first ps) (first ds))
                     (recur (rest ps) (rest ds)))

                ;; literal equality
                :else
                (and (= (first ps) (first ds))
                     (recur (rest ps) (rest ds)))))]
      (match-seq pattern data))
    (= pattern '_?) true
    (= pattern '_*) true
    ;; atoms ⇒ direct equality
    :else
    (= pattern data)))

(defn find-match*
  "Find first form in zipper that matches the pattern s-expression.

   Returns the zipper location of the match, or nil if no match found."
  [pattern-sexpr zloc]
  (loop [loc zloc]
    (when-not (z/end? loc)
      (let [form (try (z/sexpr loc)
                      (catch Exception _e
                        ::continue))]
        (if (= ::continue form)
          (recur (z/next loc))
          (if (match-sexpr pattern-sexpr form)
            loc
            (recur (z/next loc))))))))

(defn find-match
  "Find first form in code string that matches the pattern string.

   Arguments:
   - pattern-str: Pattern string with wildcards (e.g., \"(defn _? [_*] _*)\")
   - code-str: Code string to search

   Returns zipper location of match, or nil if no match."
  [pattern-str code-str]
  (find-match* (z/sexpr (z/of-string pattern-str))
               (z/of-string code-str)))
