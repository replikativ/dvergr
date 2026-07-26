(ns dvergr.web.apps-test
  "Path safety + routing shape of the room-app static server (dvergr.web.apps).
   The fork-aware worktree resolution is exercised live (room ctx needed); these
   cover the pure parts: canonicalized containment, dotfile/symlink rejection,
   method/uri gating."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.web.apps :as apps]
            [muschel.fs :as mfs]
            [muschel.fs.geschichte :as gfs]))

(def ^:private safe-path @#'apps/safe-path)
(def ^:private content-type @#'apps/content-type)

(defn- tmp-app-root []
  (let [{:keys [close!] :as repository} (gfs/memory-repository! {:name "app-test"})
        fs (gfs/make-root repository)]
    (mfs/mkdir fs "/app")
    (mfs/write-string! fs "/app/index.html" "<h1>hi</h1>" false)
    (mfs/mkdir fs "/app/js")
    (mfs/write-string! fs "/app/js/app.js" "1" false)
    {:root {:fs fs :root "/app"} :close! close!}))

(deftest safe-file-containment
  (let [{:keys [root close!]} (tmp-app-root)]
    (try
      (testing "normal paths resolve"
        (is (= "/app/index.html" (safe-path root "index.html")))
        (is (= "/app/js/app.js" (safe-path root "js/app.js"))))
      (testing "leading slashes are stripped, not treated as absolute"
        (is (= "/app/index.html" (safe-path root "/index.html"))))
      (testing "raw traversal is rejected by canonicalization"
        (is (nil? (safe-path root "../../../etc/passwd")))
        (is (nil? (safe-path root "js/../../outside.txt"))))
      (testing "dotfiles and dot-dirs are rejected in any segment"
        (is (nil? (safe-path root ".git/config")))
        (is (nil? (safe-path root "js/.hidden")))
        (is (nil? (safe-path root ".."))))
      (testing "symlink pointing outside the root is rejected"
        (mfs/symlink (:fs root) "/outside" "/app/leak.txt")
        (is (nil? (safe-path root "leak.txt"))))
      (testing "nil/missing root yields nil"
        (is (nil? (safe-path nil "index.html")))
        (is (nil? (safe-path (assoc root :root "/missing") "index.html"))))
      (finally (close!)))))

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
