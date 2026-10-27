(ns dvergr.system.profile-runtime-retirement-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.gc-roots :as roots]
            [dvergr.discourse :as discourse]
            [dvergr.rooms :as live]
            [dvergr.room.registry :as registry]
            [dvergr.system.db :as db]
            [dvergr.substrate.geschichte :as workspace]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.rooms :as rooms]
            [dvergr.system.profiled-room-creation-test :as native]
            [geschichte.bytes :as bytes]
            [geschichte.repo :as repo]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(defn- with-profile [f]
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "dvergr-retirement-" (make-array java.nio.file.attribute.FileAttribute 0)))
        joined? (atom false)]
    (try
      (@#'native/with-fixture
       (fn [{:keys [root configs] :as fixture}]
         (with-redefs [paths/systems-dir (constantly (str directory))
                       workspace/repository-config
                       (fn [scope]
                         (get (swap! configs
                                     #(if (contains? % scope) %
                                          (assoc % scope {:store {:backend :file
                                                                  :path (str (io/file scope "datahike"))
                                                                  :id (random-uuid)}
                                                          :schema-flexibility :write :keep-history? false
                                                          :attribute-refs? false :crypto-hash? true :commit-graph? true}))) scope))]
           (let [prepared (rooms/provision-profiled-room!
                           {:slug (str "retirement-" (random-uuid)) :repository-profile :fixture/knowledge}
                           (@#'native/policies (atom [])))
                 id (:room-id prepared)
                 room (live/register-prepared-room! id)]
             (try (f (assoc fixture :id id :room room :prepared prepared))
                  (finally
                    (when (identical? room (registry/lookup (:id room)))
                      (discourse/close-room! room))))))))
      (reset! joined? true)
      (finally
        ;; Preserve failed native ownership and files for investigation.
        (when @joined?
          (doseq [file (reverse (file-seq directory))] (io/delete-file file true)))))))

(deftest retirement-releases-only-owned-leases-and-preserves-pinned-history
  (with-profile
    (fn [{:keys [root id room]}]
      (let [conn (:conn (binding [ec/*execution-context* (:ctx room)] (rooms/default-workspace id)))
            _ (repo/write! conn "evidence.md" (bytes/utf8 "retained evidence"))
            snapshot @conn commit (d/commit-id snapshot) pin (random-uuid)
            config (:config snapshot)
            outsider (d/connect config)
            outsider-live? (atom true)
            release d/release released (atom [])]
        (try
          (roots/pin! snapshot {:id pin :ttl-ms nil :owner {:test :runtime-retirement}} {:sync? true})
          (repo/write! conn "evidence.md" (bytes/utf8 "newer evidence"))
          (discourse/close-room! room)
          (db/archive-room! id)
          (with-redefs [d/release (fn [actual]
                                    (release actual)
                                    (swap! released conj actual))]
            (is (true? (rooms/retire-room-runtime! id room)))
            (is (true? (rooms/retire-room-runtime! id room))))
          (is (= 3 (count @released)) "msgs, repo and KB each release their one acquired lease")
          (is (nil? (rooms/room-ctx-for id)))
          (is (.exists (io/file (get-in config [:store :path]))) "retirement preserves repository files")
          (is (nil? (registry/lookup (:id room))))
          (is (empty? (binding [ec/*execution-context* (:ctx room)] (ygg/registered-systems))))
          (is (= "newer evidence" (bytes/decode-utf8 (repo/read outsider "evidence.md"))))
          (is (contains? (roots/roots (:store @outsider) {:sync? true}) pin))
          (release outsider)
          (reset! outsider-live? false)
          (let [reopened (d/connect config)]
            (try
              (let [retained (d/commit-as-db (:store @reopened) commit)]
                (is (some? retained))
                (is (= "retained evidence" (bytes/decode-utf8 (repo/read (atom retained) "evidence.md")))))
              (is (contains? (roots/roots (:store @reopened) {:sync? true}) pin))
              (roots/release! @reopened pin {:sync? true})
              (finally (release reopened))))
          (finally (when @outsider-live? (release outsider))))))))

(deftest retirement-refuses-live-unarchived-and-foreign-native-identities
  (with-profile
    (fn [{:keys [root id room]}]
      (let [calls (atom 0) other-root (context/create-execution-context)]
        (try
          (with-redefs [d/release (fn [& _] (swap! calls inc) (throw (AssertionError. "No release before exact retirement")))]
            (is (thrown? clojure.lang.ExceptionInfo (rooms/retire-room-runtime! id room)))
            (discourse/close-room! room)
            (is (thrown? clojure.lang.ExceptionInfo (rooms/retire-room-runtime! id room)))
            (db/archive-room! id)
            (doseq [[foreign-id foreign-room] [[(random-uuid) room]
                                               [id (assoc room :incarnation (random-uuid))]
                                               [id (assoc room :ctx (context/fork-context root))]]]
              (is (thrown? clojure.lang.ExceptionInfo (rooms/retire-room-runtime! foreign-id foreign-room))))
            (binding [ec/*execution-context* other-root]
              (is (thrown? clojure.lang.ExceptionInfo (rooms/retire-room-runtime! id room))))
            (is (zero? @calls)))
          (is (identical? (:ctx room) (rooms/room-ctx-for id)))
          (is (true? (rooms/retire-room-runtime! id room)))
          (finally (context/close-context! other-root)))))))

(deftest replacement-room-is-never-released-or-evicted-by-an-old-receipt
  (with-profile
    (fn [{:keys [id room root]}]
      (discourse/close-room! room)
      (db/archive-room! id)
      (let [replacement (discourse/make-room {:id (:id room) :ctx (context/fork-context root)})]
        (try
          (is (thrown? clojure.lang.ExceptionInfo (rooms/retire-room-runtime! id room)))
          (is (identical? replacement (registry/lookup (:id room))))
          (is (identical? (:ctx room) (rooms/room-ctx-for id)))
          (finally (discourse/close-room! replacement))))
      (is (true? (rooms/retire-room-runtime! id room))))))

(deftest reset-cannot-forget-live-published-acquisitions
  (with-profile
    (fn [{:keys [id room]}]
      (is (thrown? clojure.lang.ExceptionInfo (rooms/clear-room-ctxs!)))
      (is (identical? (:ctx room) (rooms/room-ctx-for id)))
      (is (thrown? clojure.lang.ExceptionInfo (rooms/release-room-stores!)))
      (is (identical? room (registry/lookup (:id room))))
      (is (identical? (:ctx room) (rooms/room-ctx-for id)))
      (discourse/close-room! room)
      (rooms/release-room-stores!)
      (is (nil? (rooms/room-ctx-for id))))))

(deftest shutdown-consumes-the-journal-without-archiving-or-forcing-an-outsider
  (with-profile
    (fn [{:keys [id room root]}]
      (let [conn (:conn (binding [ec/*execution-context* (:ctx room)] (rooms/default-workspace id)))
            config (:config @conn) outsider (d/connect config)
            release d/release releases (atom 0)]
        (try
          (discourse/close-room! room)
          (with-redefs [d/release (fn [owned] (release owned) (swap! releases inc))]
            (rooms/release-room-stores!)
            (rooms/release-room-stores!))
          (is (= 3 @releases))
          (is (nil? (rooms/room-ctx-for id)))
          (is (nil? (:room/archived-at (d/entity @(db/get-conn) [:room/id id]))))
          (is (some? (repo/log outsider)))
          ;; Active durable Rooms can hydrate again with a fresh acquisition
          ;; owner; shutdown does not turn them into archived benchmark Rooms.
          (rooms/hydrate-rooms! {:repository-profiles (@#'native/policies (atom []))})
          (let [reopened (live/register-prepared-room! id)]
            (try
              (is (not (identical? (:ctx room) (:ctx reopened))))
              (is (identical? reopened (registry/lookup (:id reopened))))
              (finally
                (discourse/close-room! reopened)
                (rooms/release-room-stores!))))
          (finally (release outsider)))))))

(deftest shutdown-retry-preserves-partial-release-acknowledgements
  (with-profile
    (fn [{:keys [id room]}]
      (discourse/close-room! room)
      (let [release d/release list-rooms registry/list-rooms
            releases (atom 0) hold? (atom true)]
        (with-redefs [d/release (fn [owned] (release owned) (swap! releases inc))
                      registry/list-rooms
                      (fn [& args]
                        (if (and @hold? (pos? @releases))
                          [(assoc room :id :temporary-context-owner)]
                          (apply list-rooms args)))]
          (is (thrown? clojure.lang.ExceptionInfo (rooms/release-room-stores!)))
          (is (= 1 @releases))
          (is (thrown? clojure.lang.ExceptionInfo (rooms/room-ctx-for id)))
          (is (thrown? clojure.lang.ExceptionInfo (rooms/clear-room-ctxs!)))
          (reset! hold? false)
          (rooms/release-room-stores!)
          (is (= 3 @releases))
          (is (nil? (rooms/room-ctx-for id))))))))

(deftest failed-owner-shutdown-never-clears-the-cache-as-compensation
  (with-profile
    (fn [{:keys [id room]}]
      (discourse/close-room! room)
      (with-redefs [dvergr.system.room-preparation/retire-published!
                    (fn [& _] (throw (ex-info "Test fence before native release" {})))]
        (is (thrown? clojure.lang.ExceptionInfo (rooms/release-room-stores!)))
        (is (identical? (:ctx room) (rooms/room-ctx-for id)))
        (is (thrown? clojure.lang.ExceptionInfo (rooms/clear-room-ctxs!))))
      (rooms/release-room-stores!)
      (is (nil? (rooms/room-ctx-for id))))))

(deftest publication-racing-with-reset-cannot-lose-its-acquisition-owner
  (with-profile
    (fn [{:keys [id room]}]
      (let [cache (var-get #'rooms/room-ctxs)
            original @cache entry (get original id)
            ;; A unique unowned snapshot makes the interleaving deterministic.
            initial {::unowned {:ctx (:ctx room)}}
            values vals injected? (atom false)]
        (try
          (reset! cache initial)
          (with-redefs [clojure.core/vals
                        (fn [entries]
                          (let [result (values entries)]
                            ;; The first read sees no owner, but publication
                            ;; commits before clear can replace that snapshot.
                            (when (and (identical? entries initial)
                                       (compare-and-set! injected? false true))
                              (reset! cache (assoc initial id entry)))
                            result))]
            (is (thrown? clojure.lang.ExceptionInfo (rooms/clear-room-ctxs!))))
          (is @injected?)
          (is (identical? entry (get @cache id)))
          (finally (reset! cache original)))))))
