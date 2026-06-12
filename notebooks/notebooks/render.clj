(ns notebooks.render
  "Headless Clay build: render the literate notebooks as a Quarto **book** (TOC,
   theme, index page) under `docs/` — the directory GitHub Pages serves.

   Run:
     clj -M:clay -m notebooks.render

   Needs the `quarto` CLI on PATH (see clay.edn for the HTML/theme config).

   Clay renders the book into `docs/_clay/` and then tries to relocate it to
   `docs/` — a step that throws on some JDKs (babashka.fs `u+wx` chmod). We catch
   that and do the move ourselves with plain Java IO, so the published site
   always lands at `docs/` root regardless of JDK."
  (:require [scicloj.clay.v2.api :as clay]
            [clojure.java.io :as io]))

;; Order = reading order in the rendered book's sidebar. Paths are relative to
;; :base-source-path. The notebook namespaces live under `notebooks/notebooks/`,
;; so the `:clay` alias's `"notebooks"` extra-path is the classpath root.
(def notebooks
  ["getting_started.clj"
   "programming_model.clj"
   "humans_and_agents.clj"
   "agents_and_tools.clj"])

(defn- delete-recursively! [f]
  (let [f (io/file f)]
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (delete-recursively! c)))
    (.delete f)))

(defn- promote-book!
  "Move the rendered book from docs/_clay/ up to docs/ and drop the quarto
   source intermediates (.qmd, _quarto.yml). No-op if Clay already relocated."
  []
  (let [clay-dir (io/file "docs/_clay")]
    (when (.isDirectory clay-dir)
      (doseq [c (.listFiles clay-dir)]
        (let [dest (io/file "docs" (.getName c))]
          (delete-recursively! dest)
          (.renameTo c dest)))
      (delete-recursively! clay-dir))
    ;; clean quarto source intermediates left in docs/
    (doseq [n (.listFiles (io/file "docs"))]
      (when (re-matches #".*\.qmd|_quarto\.yml" (.getName n))
        (.delete n)))))

(defn -main
  [& _args]
  (println "Rendering" (count notebooks) "notebooks as a Quarto book → docs/ ...")
  (try
    (clay/make!
     {:format           [:quarto :html]
      :base-source-path "notebooks/notebooks"
      :source-path      notebooks
      :base-target-path "docs"
      :book             {:title "dvergr — examples"}
      :show             false
      :browse           false})
    (catch Throwable e
      (println "[render] Clay relocation step threw (known JDK/babashka.fs"
               "u+wx issue); promoting the rendered output manually:" (.getMessage e))))
  (promote-book!)
  (if (.exists (io/file "docs/index.html"))
    (println "Done. Book at docs/index.html")
    (do (println "FAILED: no docs/index.html was produced.")
        (shutdown-agents)
        (System/exit 1)))
  (shutdown-agents)
  (System/exit 0))
