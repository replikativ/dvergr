(ns dvergr.benchmarks.bird.dialect
  "SQLite SQL, as BIRD writes it, in the PostgreSQL dialect pg-datahike
   speaks. Only what SQLite has and PostgreSQL does not is rewritten, and each
   rewrite keeps SQLite's meaning:

     `ident`                 → \"ident\" (lower-cased: SQLite identifiers
                               are case-insensitive, `bird.load` stores them
                               lower-cased, and unquoted PostgreSQL names fold
                               to lower case)
     \"ident\"                → lower-cased likewise
     IIF(c, a, b)            → CASE WHEN c THEN a ELSE b END
     STRFTIME('%Y', x)       → SUBSTR(x, 1, 4)   (BIRD's dates are ISO text;
                               likewise %m %d %H %M and %Y-%m, %Y-%m-%d),
                               cast to INTEGER where it is an operand of
                               arithmetic (SQLite converts text there; 47 of
                               BIRD's uses subtract years, 97 compare text)
     INSTR(s, t)             → STRPOS(s, t)
     DATE('now')             → CURRENT_DATE
     LIMIT a, b              → LIMIT b OFFSET a
     CAST(x AS REAL)         → CAST(x AS DOUBLE PRECISION)
     ORDER BY k [ASC|DESC]   → ... NULLS FIRST | NULLS LAST (SQLite orders
                               NULL below every value, PostgreSQL above)

   Everything else passes through; what pg-datahike then cannot run is the
   compatibility report's finding, not hidden here."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Tokens: enough to rewrite without touching string literals

(defn tokenize
  "`sql` as tokens `{:t kind :s text}`: :str 'a''b', :qid `x` or \"x\", :word,
   :num, :ws, :punct (one character)."
  [^String sql]
  (let [n (count sql)]
    (loop [i 0 out []]
      (if (>= i n)
        out
        (let [c (.charAt sql i)]
          (cond
            (Character/isWhitespace c)
            (let [j (loop [j i] (if (and (< j n) (Character/isWhitespace (.charAt sql j))) (recur (inc j)) j))]
              (recur j (conj out {:t :ws :s (subs sql i j)})))

            (= c \')
            (let [j (loop [j (inc i)]
                      (cond (>= j n) j
                            (and (= \' (.charAt sql j)) (< (inc j) n) (= \' (.charAt sql (inc j)))) (recur (+ j 2))
                            (= \' (.charAt sql j)) (inc j)
                            :else (recur (inc j))))]
              (recur j (conj out {:t :str :s (subs sql i j)})))

            (or (= c \`) (= c \"))
            (let [j (let [k (.indexOf sql (str c) (int (inc i)))] (if (neg? k) n (inc k)))]
              (recur j (conj out {:t :qid :s (subs sql (inc i) (max (inc i) (dec j)))})))

            (or (Character/isLetter c) (= c \_))
            (let [j (loop [j i] (if (and (< j n) (let [d (.charAt sql j)] (or (Character/isLetterOrDigit d) (= d \_) (= d \$))))
                                  (recur (inc j)) j))]
              (recur j (conj out {:t :word :s (subs sql i j)})))

            (Character/isDigit c)
            (let [j (loop [j i] (if (and (< j n) (let [d (.charAt sql j)] (or (Character/isDigit d) (= d \.))))
                                  (recur (inc j)) j))]
              (recur j (conj out {:t :num :s (subs sql i j)})))

            :else (recur (inc i) (conj out {:t :punct :s (str c)}))))))))

(defn render [tokens]
  (apply str (map (fn [{:keys [t s]}]
                    (if (= t :qid) (str "\"" (str/replace s "\"" "\"\"") "\"") s))
                  tokens)))

(defn- word? [tok w] (and (= :word (:t tok)) (.equalsIgnoreCase ^String (:s tok) w)))
(defn- punct? [tok p] (and (= :punct (:t tok)) (= p (:s tok))))

(defn- call-args
  "From tokens starting just after `(`: `[args rest]`, args split at top-level
   commas (each a token vector), rest after the matching `)`; nil if unbalanced."
  [toks]
  (loop [ts toks depth 0 cur [] args []]
    (if-let [t (first ts)]
      (cond
        (and (zero? depth) (punct? t ")")) [(conj args cur) (rest ts)]
        (and (zero? depth) (punct? t ",")) (recur (rest ts) depth [] (conj args cur))
        (punct? t "(") (recur (rest ts) (inc depth) (conj cur t) args)
        (punct? t ")") (recur (rest ts) (dec depth) (conj cur t) args)
        :else (recur (rest ts) depth (conj cur t) args))
      nil)))

(declare trim-ws)
(defn- trim-ws* [ts] (vec (trim-ws ts)))

(defn- trim-ws [ts] (->> ts (drop-while #(= :ws (:t %))) reverse (drop-while #(= :ws (:t %))) reverse vec))

(def ^:private strftime-parts
  {"'%Y'" [1 4] "'%m'" [6 2] "'%d'" [9 2] "'%H'" [12 2] "'%M'" [15 2]
   "'%Y-%m'" [1 7] "'%Y-%m-%d'" [1 10]})

(defn- w [s] {:t :word :s s})
(defn- p [s] {:t :punct :s s})
(def ^:private sp {:t :ws :s " "})

(declare rewrite)

(defn- rewrite-call
  "A rewritten function call at `name-tok` with its arguments, or nil."
  [name-tok args]
  (let [args (mapv (comp rewrite trim-ws) args)]
    (cond
      (and (word? name-tok "INSTR") (= 2 (count args)))
      (concat [(w "STRPOS") (p "(")] (first args) [(p ",") sp] (second args) [(p ")")])

      (and (word? name-tok "DATE") (= 1 (count args)) (= 1 (count (first args)))
           (= "'now'" (str/lower-case (:s (ffirst args)))))
      [(w "CURRENT_DATE")]

      (and (word? name-tok "IIF") (= 3 (count args)))
      (let [[c a b] args]
        (concat [(w "CASE") sp (w "WHEN") sp] c [sp (w "THEN") sp] a [sp (w "ELSE") sp] b [sp (w "END")]))

      (and (word? name-tok "STRFTIME") (= 2 (count args))
           (= 1 (count (first args))) (strftime-parts (:s (ffirst args))))
      (let [[from len] (strftime-parts (:s (ffirst args)))]
        (concat [(w "SUBSTR") (p "(")] (second args)
                [(p ",") sp {:t :num :s (str from)} (p ",") sp {:t :num :s (str len)} (p ")")])))))

(def ^:private order-end
  "Words that end an ORDER BY list."
  #{"LIMIT" "OFFSET" "UNION" "EXCEPT" "INTERSECT" "FETCH"})

(defn- order-items
  "From tokens after ORDER BY: `[items rest]`, the sort keys split at top-level
   commas, up to a closing paren, a clause keyword or the end."
  [toks]
  (loop [ts toks depth 0 cur [] items []]
    (let [t (first ts)]
      (cond
        (or (nil? t)
            (and (zero? depth) (punct? t ")"))
            (and (zero? depth) (= :word (:t t)) (order-end (str/upper-case (:s t))))
            (and (zero? depth) (punct? t ";")))
        [(conj items cur) ts]
        (and (zero? depth) (punct? t ",")) (recur (rest ts) depth [] (conj items cur))
        (punct? t "(") (recur (rest ts) (inc depth) (conj cur t) items)
        (punct? t ")") (recur (rest ts) (dec depth) (conj cur t) items)
        :else (recur (rest ts) depth (conj cur t) items)))))

(defn- sqlite-nulls
  "A sort key with SQLite's NULL placement made explicit."
  [item]
  (let [trimmed (trim-ws item)
        words (set (map #(str/upper-case (:s %)) (filter #(= :word (:t %)) trimmed)))
        trailing (take-while #(= :ws (:t %)) (reverse item))]
    (if (contains? words "NULLS")
      item
      (concat trimmed [sp (w "NULLS") sp (w (if (= "DESC" (some-> (last trimmed) :s str/upper-case)) "LAST" "FIRST"))]
              (reverse trailing)))))

(defn rewrite
  "Rewrite a token vector (see the namespace doc)."
  [tokens]
  (loop [ts (vec tokens) out []]
    (if-let [t (first ts)]
      (let [nxt (->> (rest ts) (drop-while #(= :ws (:t %))))]
        (cond
          ;; SQLite identifiers are case-insensitive: canonical lower case
          (= :qid (:t t)) (recur (subvec ts 1) (conj out (update t :s str/lower-case)))

          (and (#{"IIF" "STRFTIME" "INSTR" "DATE"} (str/upper-case (str (:s t)))) (= :word (:t t))
               (some-> (first nxt) (punct? "(")))
          (if-let [[args after] (call-args (rest nxt))]
            (if-let [r (rewrite-call t args)]
              (let [arith? (fn [tok] (and tok (= :punct (:t tok)) (#{"-" "+" "*" "/"} (:s tok))))
                    before (last (remove #(= :ws (:t %)) out))
                    following (first (remove #(= :ws (:t %)) after))
                    r (if (and (word? t "STRFTIME") (or (arith? before) (arith? following)))
                        (concat [(w "CAST") (p "(")] r [sp (w "AS") sp (w "INTEGER") (p ")")])
                        r)]
                (recur (vec after) (into out r)))
              (recur (subvec ts 1) (conj out t)))
            (recur (subvec ts 1) (conj out t)))

          ;; CAST(x AS REAL): SQLite's REAL is PostgreSQL's single-precision
          ;; float, which would lose digits
          (and (word? t "AS") (some-> (first nxt) (word? "REAL")))
          (let [n-skip (inc (count (take-while #(= :ws (:t %)) (rest ts))))]
            (recur (subvec ts (inc n-skip)) (into out [t sp (w "DOUBLE") sp (w "PRECISION")])))

          ;; ORDER BY: SQLite's NULL placement
          (and (word? t "ORDER") (some-> (first nxt) (word? "BY")))
          (let [by-idx (inc (count (take-while #(= :ws (:t %)) (rest ts))))
                [items after] (order-items (subvec ts (inc by-idx)))
                items (map (comp sqlite-nulls rewrite) items)]
            (recur (vec after)
                   (into out (concat [t sp (w "BY") sp]
                                     (apply concat (interpose [(p ",") sp] (map trim-ws* items)))
                                     (when (and (seq after) (not (punct? (first after) ")"))) [sp])))))

          ;; LIMIT a, b → LIMIT b OFFSET a
          (and (word? t "LIMIT") (= :num (:t (first nxt)))
               (let [after-a (drop-while #(= :ws (:t %)) (rest nxt))]
                 (and (some-> (first after-a) (punct? ","))
                      (= :num (:t (first (drop-while #(= :ws (:t %)) (rest after-a))))))))
          (let [a (first nxt)
                after-a (drop-while #(= :ws (:t %)) (rest nxt))
                after-comma (drop-while #(= :ws (:t %)) (rest after-a))
                b (first after-comma)]
            (recur (vec (rest after-comma)) (into out [t sp b sp (w "OFFSET") sp a])))

          :else (recur (subvec ts 1) (conj out t))))
      out)))

(defn to-postgres
  "BIRD's SQLite `sql` in pg-datahike's dialect."
  [sql]
  (render (rewrite (tokenize sql))))
