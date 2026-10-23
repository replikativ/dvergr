(ns dvergr.runtime.bounded-output
  "Private native output buffers, bounded while writing rather than on return."
  (:import [java.io Writer]))

(def ^:private chars-class (class (char-array 0)))

(defn create
  ([] (create 65536))
  ([limit]
   (when-not (and (integer? limit) (<= 0 limit 1048576))
     (throw (ex-info "Invalid evaluation output limit" {:type ::invalid-limit})))
   (let [buffer (StringBuilder.) monitor (Object.) truncated? (volatile! false)
         write! (fn [value off len]
                  (let [length (cond (string? value) (count value)
                                     (instance? chars-class value) (alength ^chars value)
                                     :else (throw (IllegalArgumentException. "Writer requires string or characters")))]
                    (when-not (<= 0 off (+ off len) length) (throw (IndexOutOfBoundsException.)))
                    (locking monitor
                      (let [take-n (min len (- limit (.length buffer)))]
                        (when (< take-n len) (vreset! truncated? true))
                        (when (pos? take-n)
                          (if (string? value)
                            (.append buffer ^CharSequence value (int off) (int (+ off take-n)))
                            (.append buffer ^chars value (int off) (int take-n))))))))
         snapshot (fn []
                    (locking monitor
                      (let [text (.toString buffer) n (count text)
                            dangling? (and (pos? n) (Character/isHighSurrogate (.charAt text (dec n))))]
                        {:text (if dangling? (subs text 0 (dec n)) text)
                         :truncated? (or @truncated? dangling?)})))
         writer (proxy [Writer] []
                  (write
                    ([value]
                     (if (number? value)
                       (write! (str (char (bit-and (int value) 65535))) 0 1)
                       (write! value 0 (if (string? value) (count value) (alength ^chars value)))))
                    ([value off len] (write! value off len)))
                  (flush [])
                  (close [])
                  (toString [] (:text (snapshot))))]
     {:writer writer :snapshot snapshot})))
