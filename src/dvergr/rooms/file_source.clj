(ns dvergr.rooms.file-source
  "HOST-only synchronous ownership of one finalized binary file. Never put
   sources in effects, room state, transaction data, RPC or SCI."
  (:require [clojure.java.io :as io]
            [datahike.blob :as blob])
  (:import [java.nio.channels Channels FileChannel]
           [java.nio.file Files LinkOption OpenOption StandardOpenOption]))

(deftype ^:private FileSource [channel owner active used content size])

(defn- refuse! []
  (throw (ex-info "Finalized file source is unavailable" {:type ::refused})))

(defn- current! [source]
  (when-not (instance? FileSource source) (refuse!))
  (let [^FileSource source source]
    (when-not (and (identical? (.-owner source) (Thread/currentThread))
                   @(.-active source) (.isOpen ^FileChannel (.-channel source)))
      (refuse!))
    source))

(defn identity-of
  "Inspected identity, not permission. Valid only within the original extent."
  [source]
  (let [^FileSource source (current! source)]
    {:content (.-content source) :size (.-size source)}))

(defn with-input!
  "Borrow the source exactly once on its original owning worker. The checked
   ingestion caller must verify identity and exact size before publication."
  [source f]
  (let [^FileSource source (current! source)]
    (when @(.-used source) (refuse!))
    (vreset! (.-used source) true)
    (f (Channels/newInputStream (.-channel source)))))

(defn with-finalized-file!
  "Own one regular non-symlink finalized file through synchronous `f`.
   Caller guarantees exclusive immutable ownership during this extent; the
   inspected hash/size are still rechecked during actual publication. No file
   is deleted. Escaped sources cannot be used after this call or across threads."
  [file f]
  (let [path (.toPath (io/file file))]
    (when-not (and (fn? f) (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
      (refuse!))
    (with-open [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS]))]
      (let [size (.size channel) content (blob/blob-id (Channels/newInputStream channel))]
        (when-not (= size (.size channel)) (refuse!))
        (.position channel 0)
        (let [active (volatile! true)
              source (FileSource. channel (Thread/currentThread) active (volatile! false) content size)]
          (try (f source) (finally (vreset! active false))))))))
