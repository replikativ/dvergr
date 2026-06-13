(ns dvergr.web.apps-test
  "Path safety + routing shape of the room-app static server (dvergr.web.apps).
   The fork-aware worktree resolution is exercised live (room ctx needed); these
   cover the pure parts: canonicalized containment, dotfile/symlink rejection,
   method/uri gating."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [dvergr.web.apps :as apps])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private safe-file @#'apps/safe-file)
(def ^:private content-type @#'apps/content-type)

(defn- tmp-app-root []
  (let [dir (.toFile (Files/createTempDirectory "dvergr-app-test" (make-array FileAttribute 0)))]
    (spit (io/file dir "index.html") "<h1>hi</h1>")
    (.mkdirs (io/file dir "js"))
    (spit (io/file dir "js" "app.js") "1")
    dir))

(deftest safe-file-containment
  (let [root (tmp-app-root)]
    (testing "normal paths resolve"
      (is (.isFile (safe-file root "index.html")))
      (is (.isFile (safe-file root "js/app.js"))))
    (testing "leading slashes are stripped, not treated as absolute"
      (is (.isFile (safe-file root "/index.html"))))
    (testing "raw traversal is rejected by canonicalization"
      (is (nil? (safe-file root "../../../etc/passwd")))
      (is (nil? (safe-file root "js/../../outside.txt"))))
    (testing "dotfiles and dot-dirs are rejected in any segment"
      (is (nil? (safe-file root ".git/config")))
      (is (nil? (safe-file root "js/.hidden")))
      (is (nil? (safe-file root "..")))) ; pure dot segment
    (testing "symlink pointing outside the root is rejected"
      (let [link (io/file root "leak.txt")]
        (Files/createSymbolicLink (.toPath link) (.toPath (io/file "/etc/hostname"))
                                  (make-array FileAttribute 0))
        (is (nil? (safe-file root "leak.txt")))))
    (testing "nil/missing root yields nil"
      (is (nil? (safe-file nil "index.html")))
      (is (nil? (safe-file (io/file root "nonexistent-dir") "index.html"))))))

(deftest content-types
  (is (= "text/html; charset=utf-8" (content-type "index.html")))
  (is (= "text/javascript; charset=utf-8" (content-type "data.js")))
  (is (= "image/svg+xml" (content-type "diagram.svg")))
  (is (= "application/octet-stream" (content-type "blob.unknownext"))))

(deftest handle-gating
  (testing "non-/apps uris are not ours"
    (is (nil? (apps/handle {:uri "/rooms/x" :request-method :get} nil))))
  (testing "writes rejected"
    (is (= 405 (:status (apps/handle {:uri "/apps/x/" :request-method :post} nil)))))
  (testing "missing slug 404s"
    (is (= 404 (:status (apps/handle {:uri "/apps/" :request-method :get} nil)))))
  (testing "no trailing slash redirects so relative assets resolve"
    (let [resp (apps/handle {:uri "/apps/myroom" :request-method :get} nil)]
      (is (= 301 (:status resp)))
      (is (= "/apps/myroom/" (get-in resp [:headers "Location"]))))))
