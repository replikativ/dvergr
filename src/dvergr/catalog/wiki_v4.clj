(ns dvergr.catalog.wiki-v4
  "wiki/v4: a two-tier wiki of atoms and overviews whose claims carry their
   evidence, after Sam's vault-wiki contracts (atoms + overviews, one claim per
   heading, verbatim quotes deep-linked to their sources, numeric fidelity,
   mechanical frontmatter compliance), scored on v3's generated worlds.

   A wiki is laid out as

     /wiki/index.md                  links every overview
     /wiki/overviews/<slug>.md       type: overview; links the atoms it synthesises
     /wiki/atoms/<slug>.md           type: entity|concept|event; one claim per `##` heading

   An atom:

     ---
     type: entity
     source: [charter.md, annual-report-2019.md]
     entry-point: What the cooperative is and how it is governed.
     ---
     # Tallow River Water Cooperative

     ## Founded in 1952 by forty households
     The cooperative was founded in 1952 ... [[charter]]
     > \"founded on 3 May 1952 by forty households\" [[charter]]

     **Sources:** [[charter]], [[annual-report-2019]]

   A citation is a wikilink to a document, `[[charter]]` or
   `[[charter#Section]]` for a section of it (a markdown link to
   `../docs/charter.md` counts too). A source without sections is cited whole;
   the verbatim quote then pins the place (vault-wiki's declared degradation).
   A link to another page is `[[atoms/slug]]`, `[[slug]]` or a markdown link.

   `score-wiki-v4` keeps v2's measures (`dvergr.catalog.wiki/score-wiki-v2`:
   currency, grounding, entities, relations, distractors, copies), counts a
   fact only in a claim (the text under one heading) that cites one of the
   fact's sources, and adds vault-wiki's contracts:

     quoted claims    every claim that cites a document carries at least one
                      faithful quote (load-bearing facts are quoted)
     quote fidelity   every quote is in the section it cites, after
                      vault-wiki's normaliser (currency signs, thousands
                      separators, `5 %`, number words, dates; whitespace and
                      case) and with scan artefacts of the source undone
     claim headers    every quote sits under a heading (WM-slot discipline)
     citations        every citation names a document, and its section exists
     frontmatter      an atom has type, a source list naming documents and an
                      entry-point, and its **Sources:** line lists the same
                      documents; an overview has type: overview
     tiers            every atom is linked from an overview, every overview
                      from the index, and their links resolve

   The paraphrase-drift rubric (matches / softens / inverts / inserts) needs a
   model; it is not part of this deterministic score."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dvergr.catalog.wiki :as wiki]
            [dvergr.catalog.wiki-gen :as gen]))

(def params {:source "/docs" :target "/wiki"})

;; ============================================================================
;; Text
;; ============================================================================

(defn- lower [s] (str/lower-case (str s)))

(defn slug [s] (-> (lower s) (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-|-$)" "")))

(defn- stem
  "`/docs/annual-report-2019.md` → `annual-report-2019`."
  [path]
  (-> path (str/split #"/") last (str/replace #"\.md$" "")))

(def ^:private number-words
  {"zero" "0" "one" "1" "two" "2" "three" "3" "four" "4" "five" "5" "six" "6"
   "seven" "7" "eight" "8" "nine" "9"})

(def ^:private month-day-year
  #"(?i)\b(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{1,2}),?\s+(\d{4})\b")

(defn normalize
  "`text` as vault-wiki compares a quote with its source (its common pre-pass
   and `normalizers/en.json`): currency signs and thousands separators gone,
   `5 %` → `5%`, whitespace collapsed, lower case, number words as digits
   (whole words only), \"May 3, 2019\" → \"may 3 2019\"."
  [text]
  (-> (str text)
      (str/replace #"[\$¥€£]" "")
      (str/replace #"(\d),(?=\d{3}(\D|$))" "$1")
      (str/replace #"(\d)\s+%" "$1%")
      (str/replace #"\s+" " ")
      str/trim
      lower
      (str/replace #"\b(zero|one|two|three|four|five|six|seven|eight|nine)\b" #(number-words (second %)))
      (str/replace month-day-year "$1 $2 $3")))

(defn source-text
  "A source as a quote is compared with it: scan artefacts undone (footers,
   hyphenation, hard-wrapped lines; `gen/unscan`), then `normalize`d."
  [text]
  (normalize (gen/unscan (str text))))

;; ============================================================================
;; Pages: front matter, headings, claims, links
;; ============================================================================

(defn front-matter
  "`[fields body]` of a page: the YAML subset vault-wiki writes (`key: value`,
   `key: [a, b]`, and `key:` followed by `- item` lines), or `[nil text]`."
  [text]
  (if-let [[_ yaml body] (re-find #"(?s)^---\n(.*?)\n---\n?(.*)$" (str text))]
    (let [lines (str/split-lines yaml)
          unquote #(str/replace (str/trim %) #"^[\"']|[\"']$" "")]
      [(loop [ls lines k nil acc {}]
         (if-let [l (first ls)]
           (if-let [[_ item] (re-find #"^\s*-\s+(.+)$" l)]
             (recur (rest ls) k (if k (update acc k (fnil conj []) (unquote item)) acc))
             (if-let [[_ key v] (re-find #"^([A-Za-z][\w-]*):\s*(.*)$" l)]
               (let [kw (keyword key) v (str/trim v)]
                 (recur (rest ls) kw
                        (assoc acc kw (cond
                                        (str/blank? v) []
                                        (re-find #"^\[.*\]$" v) (->> (str/split (subs v 1 (dec (count v))) #",")
                                                                     (map unquote) (remove str/blank?) vec)
                                        :else (unquote v)))))
               (recur (rest ls) k acc)))
           acc))
       body])
    [nil (str text)]))

(def ^:private wikilink-re #"\[\[([^\]|#]+)(?:#([^\]|]*))?(?:\|[^\]]*)?\]\]")
(def ^:private mdlink-re #"\[[^\]]*\]\(\s*<?([^)\s>#]+)(?:#([^)\s>]*))?>?(?:\s+\"[^\"]*\")?\s*\)")

(defn links
  "The links of `text`: `[{:target \"charter\" :heading \"Section\" :kind :wiki|:md}]`."
  [text]
  (concat (for [[_ t h] (re-seq wikilink-re text)] {:target (str/trim t) :heading (some-> h str/trim not-empty) :kind :wiki})
          (for [[_ t h] (re-seq mdlink-re text)] {:target (str/trim t) :heading (some-> h str/trim not-empty) :kind :md})))

(def ^:private quote-re
  "A verbatim quote line: `> \"…\"` and its citation."
  #"(?m)^>\s*[\"“]([^\"”]+)[\"”]\s*(.*)$")

(defn claims
  "The claims of a page body: `[{:heading h :text t}]` for the text under each
   `##`/`###` heading, and `{:heading nil}` for text before the first one."
  [body]
  (let [parts (str/split (str body) #"(?m)^(?=#{2,3}\s)")]
    (vec (for [p parts :when (not (str/blank? p))]
           (if-let [[_ h rest] (re-find #"(?s)^#{2,3}\s+([^\n]*)\n?(.*)$" p)]
             {:heading (str/trim h) :text rest}
             {:heading nil :text p})))))

(defn quotes
  "The verbatim quotes of `text`: `[{:quote q :citations [link …]}]`."
  [text]
  (for [[_ q tail] (re-seq quote-re (str text))]
    {:quote q :citations (vec (links tail))}))

(defn sections
  "`{heading-slug text}` of a source document's `##`/`###` sections."
  [text]
  (into {} (for [[_ h body] (re-seq #"(?ms)^#{2,3}\s+([^\n]+)\n(.*?)(?=^#{1,3}\s|\z)" (str text))]
             [(slug h) body])))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn- resolve-doc
  "The document path a citation names, or nil: `charter`, `charter.md`,
   `../docs/charter.md` or `/docs/charter.md` among `docs` (`{path text}`)."
  [docs source {:keys [target]}]
  (let [t (str/replace target #"^(\.\./)+|^/" "")
        t (str/replace t (re-pattern (str "^" (subs source 1) "/")) "")
        path (str source "/" (if (str/ends-with? t ".md") t (str t ".md")))]
    (when (contains? docs path) path)))

(defn- resolve-page
  "The wiki page a link names, or nil: `atoms/x`, `x`, `x.md`, `../atoms/x.md`."
  [pages target-dir {:keys [target]}]
  (let [t (-> target (str/replace #"^(\.\./)+|^\./|^/" "") (str/replace #"\.md$" ""))
        t (str/replace t (re-pattern (str "^" (subs target-dir 1) "/")) "")
        candidates (cond-> [(str target-dir "/" t ".md")]
                     (not (str/includes? t "/")) (into [(str target-dir "/atoms/" t ".md")
                                                        (str target-dir "/overviews/" t ".md")]))]
    (some #(when (contains? pages %) %) candidates)))

(defn- cited-text
  "The text a citation points at: the section when it names one, else the
   whole document; nil when it does not resolve."
  [docs source link]
  (when-let [path (resolve-doc docs source link)]
    (let [text (get docs path)]
      (if-let [h (:heading link)]
        (get (sections (gen/unscan text)) (slug h))
        text))))

;; ============================================================================
;; v2's view of a v4 wiki
;; ============================================================================

(defn- as-v2-text
  "A v4 page as v2's checker reads it: without its front matter (a document
   name such as `annual-report-2019.md` is not a claim), its wikilinks written
   as v2's markdown links with absolute targets and a neutral label:
   `[[charter]]` → `[source](/docs/charter.md)`, `[[atoms/x]]` → `[page](/wiki/atoms/x.md)`."
  [docs pages source target-dir text]
  (-> (second (front-matter text))
      (str/replace wikilink-re
                   (fn [[whole t h]]
                     (let [l {:target (str/trim t) :heading h}]
                       (if-let [d (resolve-doc docs source l)]
                         (str "[source](" d ")")
                         (if-let [p (resolve-page pages target-dir l)]
                           (str "[page](" p ")")
                           whole)))))))

;; ============================================================================
;; The score
;; ============================================================================

(defn- ratio* [n d] (if (pos? d) (double (/ n d)) 1.0))

(defn- credit
  "The share of a weight that `ratio` earns when each 1% short costs
   `per-point` %: an error a reader cannot see costs more than its share."
  [ratio per-point]
  (max 0.0 (- 1.0 (* per-point (- 1.0 ratio)))))

(defn score-wiki-v4
  "Score a v4 wiki. `pages` and `sources` are `{path text}`; `gold` is v3's
   gold shape (`dvergr.catalog.wiki-gen/gold`). Returns `{:scores :checks
   :reward}` (see the ns doc)."
  [{:keys [pages sources gold target source] :or {target "/wiki" source "/docs"}}]
  (let [pages (into {} (filter (fn [[p _]] (str/ends-with? p ".md"))) pages)
        index-path (str target "/index.md")
        tier-of (fn [p] (cond (str/starts-with? p (str target "/atoms/")) :atom
                              (str/starts-with? p (str target "/overviews/")) :overview
                              (= p index-path) :index
                              :else :other))
        parsed (into {} (for [[p t] pages
                              :let [[fm body] (front-matter t)]]
                          [p {:tier (tier-of p) :fm fm :body body :links (links body)}]))
        atoms (into {} (filter #(= :atom (:tier (val %)))) parsed)
        overviews (into {} (filter #(= :overview (:tier (val %)))) parsed)
        content (into {} (filter #(#{:atom :overview} (:tier (val %)))) parsed)
        ;; ---- v2's measures, on v2's view of the wiki ----
        v2 (wiki/score-wiki-v2
            {:pages (into {} (for [[p t] pages] [p (as-v2-text sources pages source target t)]))
             :sources sources :gold gold :target target :source source})
        copied (set (get-in v2 [:scores :copied-pages]))
        ;; ---- claims and quotes ----
        claim-list (for [[p {:keys [body]}] content
                         c (claims body)]
                     (assoc c :page p))
        quote-list (for [{:keys [page heading text]} claim-list
                         q (quotes text)]
                     (assoc q :page page :heading heading))
        doc-citations (fn [ls] (keep #(when-let [d (resolve-doc sources source %)] [% d]) ls))
        quote-results (for [{:keys [quote citations] :as q} quote-list
                            :let [docs (doc-citations citations)
                                  ok? (and (seq docs)
                                           (some (fn [[l _]]
                                                   (when-let [t (cited-text sources source l)]
                                                     (str/includes? (source-text t) (normalize quote))))
                                                 docs))]]
                        (assoc q :faithful? (boolean ok?) :numeric? (boolean (re-find #"\d" (normalize quote)))))
        unfaithful (vec (remove :faithful? quote-results))
        ;; a claim that cites a document carries the evidence for it: at
        ;; least one faithful quote (vault-wiki: load-bearing facts are quoted)
        citing-claims (filter (fn [{:keys [text]}] (some #(resolve-doc sources source %) (links text))) claim-list)
        faithful-at (into #{} (comp (filter :faithful?) (map (juxt :page :heading))) quote-results)
        unquoted (vec (remove #(contains? faithful-at [(:page %) (:heading %)]) citing-claims))
        headless (vec (filter #(nil? (:heading %)) quote-list))
        ;; every citation of a document (in claims and Sources lines): resolves, and its section exists
        doc-links (for [[p {:keys [links]}] content
                        l links
                        :when (or (resolve-doc sources source l)
                                  (not (resolve-page pages target l)))]
                    (assoc l :page p))
        citation-ok? (fn [l] (and (resolve-doc sources source l)
                                  (or (nil? (:heading l)) (some? (cited-text sources source l)))))
        bad-citations (vec (remove citation-ok? doc-links))
        ;; ---- coverage per claim ----
        claim-cites (fn [{:keys [text]}]
                      (into #{} (keep #(resolve-doc sources source %)) (links text)))
        own-claims (remove #(contains? copied (:page %)) claim-list)
        found (into #{}
                    (keep (fn [{:keys [id terms] srcs :sources}]
                            (let [wanted (set (map #(str source "/" %) srcs))]
                              (when (some (fn [c]
                                            (and (some wanted (claim-cites c))
                                                 (every? #(#'wiki/term-in? (str/replace (:text c) wikilink-re "") %) terms)))
                                          own-claims)
                                id))))
                    (:facts gold))
        ;; ---- front matter ----
        sources-line (fn [body] (some->> (re-find #"(?m)^\*\*Sources:\*\*(.*)$" body) second))
        atom-problems (into {}
                            (keep (fn [[p {:keys [fm body]}]]
                                    (let [declared (set (keep #(resolve-doc sources source {:target %}) (:source fm)))
                                          listed (set (keep #(resolve-doc sources source %)
                                                            (links (or (sources-line body) ""))))
                                          problems (cond-> []
                                                     (nil? fm) (conj :no-front-matter)
                                                     (and fm (str/blank? (str (:type fm)))) (conj :no-type)
                                                     (and fm (= "overview" (:type fm))) (conj :overview-in-atoms)
                                                     (and fm (empty? (:source fm))) (conj :no-source)
                                                     (and fm (not= (count declared) (count (:source fm)))) (conj :unknown-source)
                                                     (and fm (str/blank? (str (:entry-point fm)))) (conj :no-entry-point)
                                                     (nil? (sources-line body)) (conj :no-sources-line)
                                                     (and (sources-line body) (not= declared listed)) (conj :sources-out-of-sync))]
                                      (when (seq problems) [p problems]))))
                            atoms)
        overview-problems (into {}
                                (keep (fn [[p {:keys [fm]}]]
                                        (when-not (= "overview" (:type fm)) [p [:not-type-overview]])))
                                overviews)
        ;; ---- tiers ----
        page-links (fn [p] (into #{} (keep #(resolve-page pages target %)) (get-in parsed [p :links])))
        linked-from-overviews (reduce into #{} (map page-links (keys overviews)))
        orphans (vec (sort (remove linked-from-overviews (keys atoms))))
        index-links (page-links index-path)
        unindexed (vec (sort (remove index-links (keys overviews))))
        internal (for [[p {:keys [links]}] (select-keys parsed (cons index-path (keys overviews)))
                       l links
                       :when (not (resolve-doc sources source l))]
                   [p l])
        broken-links (vec (for [[p l] internal :when (nil? (resolve-page pages target l))] {:page p :target (:target l)}))
        ;; ---- scores ----
        v2s (:scores v2)
        stale-hits (:stale-values v2s)
        contamination (:distractor-terms v2s)
        scores {:pages (count pages) :atoms (count atoms) :overviews (count overviews)
                :coverage (ratio* (count found) (count (:facts gold)))
                :currency (:currency v2s)
                :grounding (:grounding v2s)
                :quote-fidelity (ratio* (- (count quote-results) (count unfaithful)) (count quote-results))
                :quotes (count quote-results)
                :quoted-claims (ratio* (- (count citing-claims) (count unquoted)) (count citing-claims))
                :claim-headers (ratio* (- (count quote-list) (count headless)) (count quote-list))
                :citations-valid (ratio* (- (count doc-links) (count bad-citations)) (count doc-links))
                :front-matter (ratio* (- (count atoms) (count atom-problems)) (count atoms))
                :tier-coverage (ratio* (- (count atoms) (count orphans)) (count atoms))
                :entities (:entities v2s)
                :relations (:relations v2s)
                ;; diagnostics
                :unfaithful-quotes (vec (take 10 (map #(select-keys % [:page :heading :quote]) unfaithful)))
                :headless-quotes (vec (take 10 (map #(select-keys % [:page :quote]) headless)))
                :unquoted-claims (vec (take 10 (map #(select-keys % [:page :heading]) unquoted)))
                :bad-citations (vec (take 10 (map #(select-keys % [:page :target :heading]) bad-citations)))
                :front-matter-problems (merge atom-problems overview-problems)
                :orphan-atoms orphans :unindexed-overviews unindexed :broken-links broken-links
                :copied-pages (vec (sort copied)) :stale-values stale-hits
                :unsupported-numbers (:unsupported-numbers v2s) :distractor-terms contamination}
        index? (and (contains? parsed index-path) (seq overviews) (empty? unindexed))
        checks (-> {:has-atoms? (boolean (seq atoms))
                    :has-overviews? (boolean (seq overviews))
                    :index? (boolean index?)
                    :quotes-faithful? (empty? unfaithful)
                    :every-claim-quoted? (empty? unquoted)
                    :every-quote-under-a-heading? (empty? headless)
                    :citations-valid? (empty? bad-citations)
                    :front-matter? (empty? (merge atom-problems overview-problems))
                    :no-orphan-atoms? (empty? orphans)
                    :links-valid? (empty? broken-links)
                    :grounded? (<= 0.95 (:grounding v2s))
                    :no-copied-pages? (empty? copied)
                    :no-distractor-facts? (empty? contamination)}
                   (into (for [{:keys [id]} (:facts gold)] [(keyword "fact" (name id)) (contains? found id)]))
                   (into (filter (fn [[k _]] (#{"current" "entity"} (namespace k)))) (:checks v2)))
        reward (if (empty? atoms)
                 0.0
                 (+ (* 0.20 (:coverage scores))
                    (* 0.10 (max 0.0 (- 1.0 (* 0.2 (count stale-hits)))))
                    (* 0.10 (credit (:grounding scores) 5.0))
                    ;; a wiki without quotes earns nothing here, not full marks
                    (* 0.15 (credit (:quote-fidelity scores) 5.0) (:quoted-claims scores))
                    (* 0.05 (:claim-headers scores))
                    (* 0.05 (:citations-valid scores))
                    (* 0.10 (:front-matter scores))
                    (* 0.05 (:tier-coverage scores))
                    (* 0.025 (if index? 1.0 0.0))
                    (* 0.025 (if (empty? broken-links) 1.0 0.0))
                    (* 0.05 (:entities scores))
                    (* 0.05 (:relations scores))
                    (* 0.05 (if (empty? contamination) 1.0 0.0))))]
    {:scores scores :checks checks :reward (/ (Math/round (* 1000 reward)) 1000.0)}))

;; ============================================================================
;; A correct v4 wiki (calibration)
;; ============================================================================

(defn- body-sentences
  "The sentences of a document as a quote may take them: scan artefacts
   undone, front matter and title dropped, table rows left out."
  [text]
  (->> (str/split (str/replace (gen/unscan text) #"(?s)^---\n.*?\n---\n#[^\n]*\n" "") #"(?<=[.!?])\s+|\n")
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "|") (str/starts-with? % "#") (re-find #"[\"“”]" %)))))

(defn- digits [text] (set (re-seq #"\d[\d,.]*\d|\d" (str text))))
(defn- content-words [text] (set (re-seq #"[a-z]{5,}" (lower text))))

(defn- best-sentence
  "The sentence of `doc` that supports `claim` best (shared numbers count
   three times a shared word), avoiding `avoid` terms (stale values,
   distractors) where another sentence will do."
  [doc claim avoid]
  (let [cands (body-sentences doc)
        bad? (fn [s] (some #(str/includes? (lower s) (lower %)) avoid))
        score (fn [s] (+ (* 3 (count (set/intersection (digits s) (digits claim))))
                         (count (set/intersection (content-words s) (content-words claim)))))
        pool (or (seq (remove bad? cands)) cands)]
    (when (seq pool) (apply max-key score (reverse pool)))))

(defn- v3->v4-links
  "v3 reference prose with its links as v4 wikilinks: `([label](../docs/x.md))` → `[[x]]`,
   `[Name](slug.md)` → `[[slug]]`."
  [text]
  (-> text
      (str/replace #"\(\[[^\]]*\]\(\.\./docs/([^)]+)\.md\)\)" "[[$1]]")
      (str/replace #"\[([^\]]+)\]\(([a-z0-9-]+)\.md\)" "$1 ([[$2]])")))

(defn- heading-of
  "A claim heading: the first sentence of `para`, its links dropped, at most
   ten words."
  [para]
  (let [plain (-> para (str/replace #"\(\[\[[^\]]+\]\]\)|\[\[[^\]]+\]\]" "")
                  (str/replace #"\s+" " ") str/trim)
        first-sentence (first (str/split plain #"(?<=[.;])\s"))]
    (->> (str/split (str/replace first-sentence #"[.;:,]+$" "") #"\s+") (take 10) (str/join " "))))

(defn reference-wiki
  "`{\"/wiki/x.md\" text}`: a v4 wiki of `w`: v3's calibrated reference pages
   (`gen/reference-wiki`) as atoms, one claim per paragraph, each claim with a
   verbatim quote from every source it cites, front matter and a Sources line;
   one overview linking every atom; an index linking the overview."
  [w]
  (let [docs (gen/documents w)
        gold (gen/gold w)
        avoid (concat (mapcat :value (:stale gold)) (:distractors gold))
        v3 (dissoc (gen/reference-wiki w) "/wiki/index.md")
        atoms (into (sorted-map)
                    (for [[path text] v3
                          :let [s (stem path)
                                [_ title body] (re-find #"(?s)^#\s+([^\n]*)\n\n(.*)$" (str/trim text))
                                paras (remove str/blank? (str/split body #"\n\n"))
                                cited (fn [para] (distinct (map second (re-seq #"\]\(\.\./docs/([^)]+)\.md\)" para))))
                                all (sort (distinct (mapcat cited paras)))
                                claim (fn [para]
                                        (str "## " (heading-of (v3->v4-links para)) "\n\n"
                                             (v3->v4-links para) "\n"
                                             (str/join (for [d (cited para)
                                                             :let [q (best-sentence (get docs (str "/docs/" d ".md")) para avoid)]
                                                             :when q]
                                                         (str "> \"" q "\" [[" d "]]\n")))))]]
                      [(str "/wiki/atoms/" s ".md")
                       (str "---\ntype: entity\nsource: [" (str/join ", " (map #(str % ".md") all)) "]\n"
                            "entry-point: " (heading-of (v3->v4-links (first paras))) "\n---\n"
                            "# " title "\n\n"
                            (str/join "\n" (map claim paras))
                            "\n**Sources:** " (str/join ", " (map #(str "[[" % "]]") all)) "\n")]))
        overview (str "/wiki/overviews/" (slug (:org w)) ".md")]
    (assoc atoms
           overview (str "---\ntype: overview\n---\n# " (:org w) "\n\n"
                         "The atoms of this wiki, about the " (:org w) " and the people, places and events of its history.\n\n"
                         ;; links only: an atom's title (\"2008 heatwave\") on a
                         ;; page that cites nothing would be an ungrounded claim
                         (str/join (for [[p _] atoms] (str "- [[atoms/" (stem p) "]]\n"))))
           "/wiki/index.md" (str "# " (:place w) " wiki\n\n- [[overviews/" (slug (:org w)) "]]\n"))))
