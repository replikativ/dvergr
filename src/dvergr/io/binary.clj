(ns dvergr.io.binary
  "Bounded binary values. Streams stay host-side and are never exposed to SCI."
  (:import [java.io InputStream ByteArrayOutputStream]))

(def max-bytes (* 128 1024 1024))

(defn checked-limit [n ceiling]
  (when-not (and (integer? n) (pos? n) (<= n ceiling))
    (throw (ex-info "Invalid binary size limit" {:limit n :maximum ceiling})))
  (long n))

(defn read-bounded
  "Read at most limit bytes. The caller owns and closes the stream."
  ^bytes [^InputStream input limit]
  (let [limit (checked-limit limit max-bytes)
        buffer (byte-array 8192)
        output (ByteArrayOutputStream.)]
    (loop [total 0]
      (let [n (.read input buffer 0 (int (min (alength buffer) (inc (- limit total)))))]
        (if (neg? n)
          (.toByteArray output)
          (let [next-total (+ total n)]
            (when (> next-total limit)
              (throw (ex-info "Binary input exceeds size limit" {:type ::too-large :max-bytes limit})))
            (.write output buffer 0 n)
            (recur next-total)))))))

(defn contains-bytes?
  "Exact byte-subsequence match, without decoding or copying the binary body."
  [^bytes body ^bytes needle]
  (let [n (alength needle) end (- (alength body) n)]
    (and (pos? n)
         (loop [i 0]
           (when (<= i end)
             (if (loop [j 0]
                   (or (= j n)
                       (and (= (aget body (+ i j)) (aget needle j))
                            (recur (inc j)))))
               true
               (recur (inc i))))))))
