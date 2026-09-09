(ns org.replikativ.spindel.incremental.permutation
  "Permutations of finite positions, sparse-map representation.

   A permutation is a bijection on `[0, n)` for some `n`. We represent
   it as a Clojure map `{i → π(i)}` *omitting fixed points*. So the
   identity permutation is `{}`, a transposition `(0 1)` is `{0 1, 1 0}`,
   and a rotation of position 0 to position 2 is `{0 2, 1 0, 2 1}`.

   This representation has three useful properties:

   - **Canonical.** Two permutations are equal iff their sparse maps are
     equal. In particular the identity is uniquely `{}`.
   - **Compact.** Memory is `O(|non-fixed|)` — important for large
     sequences with localised reorderings.
   - **Group operations are simple.** `compose` is one map-iteration,
     `inverse` is `clojure.set/map-invert`.

   Conventions
   -----------
   A permutation `p` is interpreted *forward*: `p(i) = j` means \"the
   element at position i moves to position j.\" Composition order
   matches function composition: `(compose p q)(i) = p(q(i))`, i.e. apply
   `q` first, then `p`.

   `arrange` applies a permutation to a vector by reading: the new
   vector at position `j` contains the value that was at position
   `p⁻¹(j)`. Equivalently, `new[p(i)] = old[i]` for every i (with
   `p(i) = i` for fixed points).

   Algebraic structure
   -------------------
   Permutations form the symmetric group `S_n` for any fixed `n`:

   - **Closure.** `compose` of two permutations is a permutation.
   - **Associativity.** `(compose (compose p q) r) = (compose p (compose q r))`.
   - **Identity.** `(compose p {}) = (compose {} p) = p`.
   - **Inverse.** `(compose p (inverse p)) = (compose (inverse p) p) = {}`.

   Permutations form a *group*, not just a monoid — the only one of
   our five sequence-diff fields with full group structure (shrink is
   irreversible)."
  (:refer-clojure :exclude [cycle])
  (:require [clojure.set :as set]))

;; =============================================================================
;; Predicates and accessors
;; =============================================================================

(defn identity-perm?
  "True iff `p` is the identity permutation."
  [p]
  (or (nil? p) (zero? (count p))))

(defn canonicalize
  "Return `p` in canonical sparse-map form: any `(i, i)` fixed points
   are removed. The constructors in this namespace already produce
   canonical permutations; this helper is for callers who build
   permutations by hand and want to normalise before composition or
   comparison."
  [p]
  (persistent!
   (reduce-kv (fn [acc k v]
                (if (= k v) acc (assoc! acc k v)))
              (transient {})
              p)))

(defn apply-perm
  "Apply permutation `p` at position `i`. Returns `p(i)` if `i` is a
   non-fixed point, else `i` itself (fixed-point default)."
  [p i]
  (get p i i))

(defn fixed-points
  "Return the set of positions `< size` that are fixed by `p`."
  [p size]
  (into #{} (remove #(contains? p %)) (range size)))

;; =============================================================================
;; Group operations
;; =============================================================================

(defn compose
  "Compose two permutations: `(compose p q)(i) = p(q(i))`. Apply q
   first, then p. The result is in canonical form (fixed points
   omitted).

   Variadic: `(compose)` is the identity; `(compose p)` is `p`."
  ([] {})
  ([p] p)
  ([p q]
   ;; r(i) = p(q(i)). Domain of r is contained in dom(p) ∪ dom(q):
   ;; any i not in either is fixed by both, hence fixed by r.
   (let [touched (into #{} (concat (keys p) (keys q)))]
     (persistent!
      (reduce (fn [acc i]
                (let [qi  (apply-perm q i)
                      pqi (apply-perm p qi)]
                  (if (= pqi i)
                    acc
                    (assoc! acc i pqi))))
              (transient {})
              touched))))
  ([p q & more]
   (reduce compose (compose p q) more)))

(defn inverse
  "Return the inverse permutation: `(compose p (inverse p)) = {}`. For
   the sparse-map representation this is a key/value swap."
  [p]
  (set/map-invert p))

;; =============================================================================
;; Constructors for common permutations
;; =============================================================================

(defn cycle
  "Build a permutation from a cycle in one-line notation. `(cycle a b c)`
   produces a permutation that sends `a → b`, `b → c`, `c → a`. A
   one-element cycle is the identity. Positions must be distinct."
  [& positions]
  (let [n (count positions)]
    (cond
      (<= n 1) {}
      :else    (into {}
                     (map vector positions
                          (concat (rest positions) [(first positions)]))))))

(defn transposition
  "Build a transposition swapping positions `i` and `j`. Equivalent to
   `(cycle i j)` for `i ≠ j`; identity when `i = j`."
  [i j]
  (if (= i j) {} {i j j i}))

(defn rotation
  "Build a permutation that moves the element at position `from` to
   position `to`, shifting the elements between them by one to make
   room.

   When `from < to`: elements at `(from, to]` shift one slot toward the
   head (positions `from+1, …, to` become `from, …, to-1`).
   When `from > to`: elements at `[to, from)` shift one slot toward the
   tail.
   When `from = to`: identity."
  [from to]
  (cond
    (= from to) {}
    (< from to) (assoc (into {} (for [k (range (inc from) (inc to))] [k (dec k)]))
                       from to)
    :else       (assoc (into {} (for [k (range to from)] [k (inc k)]))
                       from to)))

(defn split-swap
  "The permutation that swaps two adjacent contiguous ranges
   `[a, a+m)` and `[a+m, a+m+n)`. After application the second range
   precedes the first. Used by sequence-diff composition to interleave
   slots grown by two consecutive diffs."
  [a m n]
  (if (or (zero? m) (zero? n))
    {}
    (into {}
          (concat
           ;; first range slides up by n
           (for [i (range a (+ a m))] [i (+ i n)])
           ;; second range slides down by m
           (for [i (range (+ a m) (+ a m n))] [i (- i m)])))))

;; =============================================================================
;; Action on vectors
;; =============================================================================

(defn arrange
  "Apply `p` to vector `coll`: the new vector has `coll[i]` at position
   `(apply-perm p i)`. Equivalently, `new[j] = coll[(inverse p)[j]]`.

   Permutation `p` must be a valid permutation on `[0, (count coll))` —
   if any `(p i) ≥ (count coll)` or `(p i) < 0`, behaviour is
   undefined (will produce nils or throw). Callers are expected to
   ensure the permutation matches the vector's size."
  [p coll]
  (let [v (vec coll)]
    (if (identity-perm? p)
      v
      (reduce-kv (fn [acc i j] (assoc acc j (nth v i))) v p))))

;; =============================================================================
;; Decomposition (helper for the diff layer; not part of the group ops)
;; =============================================================================

(defn decompose-cycles
  "Decompose `p` into its disjoint cycles, each represented as a vector
   of positions. The identity decomposes to `[]`. Useful for diff
   composition and for emitting minimal DOM moves."
  [p]
  (if (identity-perm? p)
    []
    (loop [remaining p
           cycles    []]
      (if-let [[start _] (first remaining)]
        (let [cyc (loop [i start
                         acc [start]]
                    (let [j (get remaining i)]
                      (if (or (nil? j) (= j start))
                        acc
                        (recur j (conj acc j)))))
              consumed (set cyc)]
          (recur (into {} (remove (fn [[k _]] (consumed k))) remaining)
                 (conj cycles cyc)))
        cycles))))
