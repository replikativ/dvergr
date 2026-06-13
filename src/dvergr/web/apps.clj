(ns dvergr.web.apps
  "Serve a room's `app/` directory as a static web app — the served artifact
   that makes a room a buildable, previewable project (the lovable-style loop):

     agent writes app/index.html (+ js/css/data) in the room's workspace repo
       → live at /apps/<room-slug>/        (the room's CURRENT worktree)
       → fork the room, iterate, preview   (the fork's worktree — same code path,
                                            the room ctx IS the fork ctx)
       → merge = deploy, discard = drop    (existing room fork lifecycle)

   Static-first by design: apps are plain files (the Simmis_law-v2 architecture —
   CDN React/Mermaid + a data .js the agent regenerates), no server-side eval.
   GET-only, path-canonicalized to the app root (symlink escape included), no
   dotfiles. The daemon web binds loopback by default; publishing to third
   parties is a separate, explicit step (auth boundary TBD)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.substrate.git :as git]
            [dvergr.system.db :as sdb]
            [dvergr.system.rooms :as srooms]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private content-types
  {"html" "text/html; charset=utf-8"
   "htm"  "text/html; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "text/javascript; charset=utf-8"
   "mjs"  "text/javascript; charset=utf-8"
   "json" "application/json; charset=utf-8"
   "edn"  "application/edn; charset=utf-8"
   "svg"  "image/svg+xml"
   "png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "webp" "image/webp"
   "ico"  "image/x-icon"
   "txt"  "text/plain; charset=utf-8"
   "md"   "text/plain; charset=utf-8"
   "csv"  "text/csv; charset=utf-8"
   "wasm" "application/wasm"
   "woff" "font/woff"
   "woff2" "font/woff2"
   "map"  "application/json"})

(defn- content-type [path]
  (get content-types (some-> (re-find #"\.([A-Za-z0-9]+)$" path) second str/lower-case)
       "application/octet-stream"))

(defn app-root
  "The room's app directory — `<current worktree>/app` resolved fork-aware
   under the room's OWN execution context. nil when the room has no ctx/repo."
  [room-id]
  (when-let [room-ctx (srooms/room-ctx-for room-id)]
    (binding [ec/*execution-context* room-ctx]
      (when-let [wt (git/current-worktree-path)]
        (io/file wt "app")))))

(defn app-exists?
  "True when the room has an app/index.html to serve (used by the room page
   to decide whether to show the app link)."
  [room-id]
  (boolean (some-> (app-root room-id) (io/file "index.html") .isFile)))

(defn- safe-file
  "Resolve `rel` under `root`, canonicalized — nil unless the canonical result
   stays inside the canonical root (rejects .., absolute paths, symlink escape)
   and no path segment is a dotfile."
  [^java.io.File root rel]
  (when (and root (.isDirectory root))
    (let [rel (str/replace (or rel "") #"^/+" "")]
      (when-not (some #(str/starts-with? % ".") (str/split rel #"/"))
        (let [f (io/file root rel)
              canon (.getCanonicalPath f)
              root-canon (.getCanonicalPath root)]
          (when (or (= canon root-canon)
                    (str/starts-with? canon (str root-canon java.io.File/separator)))
            f))))))

(defn- not-found [slug]
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<!doctype html><meta charset=utf-8>"
              "<body style=\"font-family:system-ui;background:#111;color:#ccc;"
              "display:grid;place-items:center;height:100vh;margin:0\">"
              "<div style=\"max-width:34em;text-align:center\">"
              "<h2 style=\"color:#52b788\">No app here (yet)</h2>"
              "<p>Room <code>" slug "</code> has no <code>app/index.html</code> "
              "in its workspace. Ask an agent in the room to build one — files "
              "written under <code>app/</code> are served live at this URL.</p>"
              "</div></body>")})

(defn handle
  "Handle GET /apps/<room-slug>[/<path>] — serve the file from the room's
   current worktree `app/` dir. Returns a ring response, or nil when the
   uri isn't ours."
  [req _daemon]
  (let [uri (:uri req)]
    (when (str/starts-with? uri "/apps/")
      (if-not (#{:get :head} (:request-method req))
        {:status 405 :headers {"Allow" "GET, HEAD"} :body "GET only"}
        (let [rest-uri (subs uri (count "/apps/"))
              [slug path] (str/split rest-uri #"/" 2)
              slug (java.net.URLDecoder/decode (or slug "") "UTF-8")]
          (cond
            (str/blank? slug)
            {:status 404 :headers {"Content-Type" "text/plain"} :body "no room slug"}

            ;; /apps/<slug> (no trailing slash) → redirect so relative asset
            ;; URLs inside index.html resolve under /apps/<slug>/.
            (nil? path)
            {:status 301 :headers {"Location" (str "/apps/" slug "/")} :body ""}

            :else
            (let [room (sdb/room-by-slug slug)
                  root (some-> room :room/id app-root)
                  rel  (if (str/blank? path) "index.html" path)
                  rel  (if (str/ends-with? rel "/") (str rel "index.html") rel)
                  f    (safe-file root rel)]
              (if (and f (.isFile f))
                {:status 200
                 :headers {"Content-Type" (content-type (.getName f))
                           ;; live-iterate loop: agents rewrite files, humans reload
                           "Cache-Control" "no-cache"}
                 :body f}
                (not-found slug)))))))))
