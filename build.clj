(ns build
  "Build script for dvergr — both a library jar (for Clojars / git-dep
   consumers) and a runnable MCP-server uberjar.

   Usage:
     clj -T:build jar        # Library jar + pom  (org.replikativ/dvergr)
     clj -T:build uber       # Runnable MCP-server uberjar (main dvergr.mcp.server)
     clj -T:build harness    # Standalone harness uberjar — daemon + nREPL + TUI +
                             #   web + Telegram (main dvergr.cli.main, :cli deps)
     clj -T:build install    # Install the library jar to ~/.m2
     clj -T:build deploy     # Deploy to Clojars (needs CLOJARS_USERNAME/PASSWORD)
     clj -T:build clean      # Remove target/

   NOTE: dvergr depends on a fork of SCI via a git dep (whilo/sci, see
   deps.edn). tools.build's write-pom only emits Maven coordinates, so the
   SCI git dep does NOT appear in the published pom. Consequences:
     - git-dep consumers (`org.replikativ/dvergr {:git/url …}`) resolve SCI fine.
     - the uberjar bundles SCI fine.
     - a Clojars consumer must add the SCI git dep themselves until the fork
       lands on Maven (upstream PR) or we publish it under our own group."
  (:require [clojure.tools.build.api :as b]))

(def lib 'org.replikativ/dvergr)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))
;; The standalone harness includes the :cli alias (src-clients/ — the TUI + CLI
;; entry — plus the web + mail + nREPL deps).
(def harness-basis (delay (b/create-basis {:project "deps.edn" :aliases [:cli]})))
(def harness-file  (format "target/%s-%s-harness.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn- write-pom! []
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :scm       {:url "https://github.com/replikativ/dvergr"
                            :connection "scm:git:git://github.com/replikativ/dvergr.git"
                            :developerConnection "scm:git:ssh://git@github.com/replikativ/dvergr.git"
                            :tag (str "v" version)}
                :pom-data  [[:description "A Clojure agentic programming framework — continuous-time collaborative multi-agent rooms."]
                            [:url "https://github.com/replikativ/dvergr"]
                            [:licenses
                             [:license
                              [:name "Apache License 2.0"]
                              [:url "https://www.apache.org/licenses/LICENSE-2.0"]]]]}))

(defn jar
  "Build the library jar (src + resources) + pom."
  [_]
  (clean nil)
  (write-pom!)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println (str "JAR: " jar-file " (version " version ")")))

(defn uber
  "Build the runnable MCP-server uberjar (main = dvergr.mcp.server)."
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :src-dirs ["src"]
                  :class-dir class-dir
                  :ns-compile '[dvergr.mcp.server]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'dvergr.mcp.server})
  (println (str "Uberjar: " uber-file " (version " version ")")))

(defn harness
  "Build the standalone harness uberjar: one runnable jar that boots the daemon +
   nREPL + TUI + web dashboard + Telegram (main = dvergr.cli.main, the :cli entry).
   Bundles src-clients/ and the :cli extra-deps (spindel-tui, reitit web,
   clojure-mail, nREPL). The SCI git dep is bundled into the uber too."
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "src-clients" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis     @harness-basis
                  :src-dirs  ["src" "src-clients"]
                  :class-dir class-dir
                  :ns-compile '[dvergr.cli.main]})
  (b/uber {:class-dir class-dir
           :uber-file harness-file
           :basis     @harness-basis
           :main      'dvergr.cli.main})
  (println (str "Harness uberjar: " harness-file " (version " version ")"))
  (println "Run:  java -jar " harness-file "   # daemon + nREPL(:7888) + TUI; --no-tui --web for a server box"))

(defn install
  "Install the library jar to the local Maven repo (~/.m2)."
  [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir})
  (println (str "Installed " lib " " version " to ~/.m2")))

(defn deploy
  "Deploy the library jar to Clojars. Requires CLOJARS_USERNAME / CLOJARS_PASSWORD.
   See the SCI git-dep caveat in this ns's docstring before relying on this."
  [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact  (b/resolve-path jar-file)
    :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))
