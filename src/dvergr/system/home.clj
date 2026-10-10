(ns dvergr.system.home
  "A home knows where it lives, so a moved home is told from a copied one.

   `home.edn` in the home holds its id and the path it was last opened at. A
   home opened at another path was moved, when its original place no longer
   holds it, and is adopted; or copied, when the original is still there with
   the same id, and is refused. A copy carries its stores' ids (they are kept
   beside the stores, `dvergr.substrate.store-ids`), so it is the same logical
   stores as the original, a replica, not an independent home. Dvergr itself
   never copies stores: to try something on a home's data without changing it,
   fork the room."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dvergr.substrate.paths :as paths]
            [taoensso.telemere :as tel]))

(defn- home-file ^java.io.File [dir] (io/file dir "home.edn"))

(defn- read-home [dir]
  (let [f (home-file dir)]
    (when (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception _ nil)))))

(defn- write-home! [dir m]
  (let [f (home-file dir)
        tmp (io/file (str f ".tmp-" (random-uuid)))]
    (spit tmp (pr-str m))
    (java.nio.file.Files/move (.toPath tmp) (.toPath f)
                              (into-array [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                           java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
    m))

(defn claim!
  "Check that the current home is this home's only copy and record where it
   lives. Returns `:created`, `:same` or `:moved`; throws when the home is a
   copy of one that still exists at its recorded path. Call before any of the
   home's stores is opened."
  []
  (let [here (.getCanonicalPath (io/file (paths/home)))
        {:home/keys [id path] :as m} (read-home here)]
    (cond
      (nil? m)
      (do (write-home! here {:home/id (random-uuid) :home/path here}) :created)

      (= path here) :same

      (= id (:home/id (read-home path)))
      (throw (ex-info (str "This home is a copy of the one at " path ", which still exists. "
                           "A copy holds the same stores under the same ids and must not run as a "
                           "home of its own. Move the home instead, or fork a room to work on its data.")
                      {:type ::copied-home :home here :original path}))

      :else
      (do (tel/log! {:level :info :id :home/moved :data {:from path :to here}} "Home was moved; adopting it")
          (write-home! here (assoc m :home/path here))
          :moved))))
