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

   Coverage, currency and grounding read a claim's own words: quote lines,
   link targets and the Sources line are not statements (a quote is
   attributed evidence, checked for fidelity on its own). Distractors count in
   quotes too. Pages outside atoms/ and overviews/ earn nothing.

   Where this differs from vault-wiki: number words are replaced as whole
   words (its table replaces substrings, so `someone` became `some1` on both
   sides); a quote must have three words and share a number or a word with
   its claim, and every document a claim cites must be quoted in it; a
   sectioned source must be cited by section (a bare [[doc]] only on the
   Sources line, or for a source without sections); an overview is checked as
   navigation (type and links to atoms), not as a synthesis.

   The paraphrase-drift rubric (matches / softens / inverts / inserts) needs a
   model; it is not part of this deterministic score."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.workflow :as workflow]
            [dvergr.catalog.wiki :as wiki]
            [dvergr.catalog.wiki-gen :as gen]
            [dvergr.catalog.workspace :as ws]))

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
   `key: [a, b]`, and `key:` followed by `- item` lines), or `[nil text]`.
   Never throws: a list item after a scalar makes the field a list."
  [text]
  (if-let [[_ yaml body] (re-find #"(?s)^---\n(.*?)\n---\n?(.*)$" (str text))]
    (let [lines (str/split-lines yaml)
          unquote #(str/replace (str/trim %) #"^[\"']|[\"']$" "")
          add (fn [v item] (conj (cond (vector? v) v (nil? v) [] :else [v]) item))]
      [(loop [ls lines k nil acc {}]
         (if-let [l (first ls)]
           (if-let [[_ item] (re-find #"^\s*-\s+(.+)$" l)]
             (recur (rest ls) k (if k (update acc k add (unquote item)) acc))
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
  (concat (for [[_ t h] (re-seq wikilink-re (str text))] {:target (str/trim t) :heading (some-> h str/trim not-empty) :kind :wiki})
          (for [[_ t h] (re-seq mdlink-re (str text))] {:target (str/trim t) :heading (some-> h str/trim not-empty) :kind :md})))

(def ^:private quote-line-re
  "A line opening with a quote: `> \"…\"` and the rest of the line."
  #"(?m)^>[ \t]*[\"“]([^\"”\n]+)[\"”]([^\n]*)$")

(defn- citations-only?
  "Whether the rest of a quote line is its citations and nothing else (links
   parsed as `links` parses them, titles included; separators aside)."
  [tail]
  (str/blank? (-> (str tail)
                  (str/replace #"\[\[([^\]|#]+)(?:#([^\]|]*))?(?:\|[^\]]*)?\]\]" "")
                  (str/replace #"\[[^\]]*\]\(\s*<?([^)\s>#]+)(?:#([^)\s>]*))?>?(?:\s+\"[^\"]*\")?\s*\)" "")
                  (str/replace #"[ \t,;()]" ""))))

(defn- strip-quotes
  "`text` without its quote lines: a line is one when the quote is followed by
   its citations and nothing else; a line with more text after the quote is
   prose, so no statement hides behind a quote."
  [text]
  (str/replace (str text) quote-line-re (fn [[whole _ tail]] (if (citations-only? tail) "" whole))))

(def ^:private sources-line-re #"(?m)^\*\*Sources:\*\*.*$")

(defn claims
  "The claims of a page body, in order: `[{:index i :heading h :text t}]` for
   the text under each `##`/`###` heading, `{:heading nil}` for text before
   the first one. The page's **Sources:** line belongs to no claim."
  [body]
  (let [parts (str/split (str/replace (str body) sources-line-re "") #"(?m)^(?=#{2,3}\s)")]
    (vec (map-indexed (fn [i c] (assoc c :index i))
                      (for [p parts :when (not (str/blank? p))]
                        (if-let [[_ h rest] (re-find #"(?s)^#{2,3}\s+([^\n]*)\n?(.*)$" p)]
                          {:heading (str/trim h) :text rest}
                          {:heading nil :text p}))))))

(defn quotes
  "The verbatim quotes of `text`: `[{:quote q :citations [link …]}]`."
  [text]
  (for [[_ q tail] (re-seq quote-line-re (str text))
        :when (citations-only? tail)]
    {:quote q :citations (vec (links tail))}))

(defn prose
  "A claim's own words: its text without quote lines, links reduced to their
   labels (a link's target, such as `charter-1953.md`, is not a statement)."
  [text]
  (-> (str text)
      strip-quotes
      (str/replace #"\[\[[^\]|]*\|([^\]]*)\]\]" "$1") ; an alias is visible text
      (str/replace #"\[\[[^\]]*\]\]" "")
      (str/replace #"\[([^\]]*)\]\([^)]*\)" "$1")))

(defn sections
  "`{heading-slug text}` of a source document's `##`/`###` sections, read from
   the document as it is (scan artefacts are undone only in a section's body)."
  [text]
  (into {} (for [[_ h body] (re-seq #"(?ms)^#{2,3}\s+([^\n]+)\n(.*?)(?=^#{1,3}\s|\z)" (str text))]
             [(slug h) body])))

(defn- substantive?
  "Whether a quote carries evidence: at least three words or numbers."
  [q]
  (<= 3 (count (re-seq #"[a-z0-9]+" (normalize q)))))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn- join-path
  "`rel` resolved against directory `dir` (`/wiki/atoms`), `..` and `.` applied."
  [dir rel]
  (let [segs (reduce (fn [acc s] (case s "" acc "." acc ".." (vec (butlast acc)) (conj acc s)))
                     (if (str/starts-with? rel "/") [] (vec (remove str/blank? (str/split dir #"/"))))
                     (str/split rel #"/"))]
    (str "/" (str/join "/" segs))))

(defn- dir-of [path] (str/join "/" (butlast (str/split path #"/"))))

(defn- md-path [path] (if (str/ends-with? path ".md") path (str path ".md")))

(defn- resolve-doc
  "The document path a link from `page` names, or nil. A wikilink names a
   document (`charter`, `charter.md`, `docs/charter`); a markdown link is a
   path, relative to `page` or absolute."
  [docs source page {:keys [target kind]}]
  (let [path (if (= :md kind)
               (md-path (join-path (dir-of page) target))
               (str source "/" (md-path (-> target (str/replace #"^(\.\./)+|^\./|^/" "")
                                            (str/replace (re-pattern (str "^" (subs source 1) "/")) "")))))]
    (when (contains? docs path) path)))

(defn- resolve-page
  "The wiki page a link from `page` names, or nil. A wikilink names a page
   (`atoms/x`, `x` for an atom or overview); a markdown link is a path,
   relative to `page` or absolute."
  [pages target-dir page {:keys [target kind]}]
  (if (= :md kind)
    (let [p (md-path (join-path (dir-of page) target))] (when (contains? pages p) p))
    (let [t (-> target (str/replace #"^(\.\./)+|^\./|^/" "") (str/replace #"\.md$" "")
                (str/replace (re-pattern (str "^" (subs target-dir 1) "/")) ""))
          candidates (cond-> [(str target-dir "/" t ".md")]
                       (not (str/includes? t "/")) (into [(str target-dir "/atoms/" t ".md")
                                                          (str target-dir "/overviews/" t ".md")]))]
      (some #(when (contains? pages %) %) candidates))))

(defn- cited-text
  "The text a citation points at, or nil when it does not resolve: the named
   section; the whole document when the citation names none and the document
   has no sections (a sectioned document must be cited by section)."
  [docs source page link]
  (when-let [path (resolve-doc docs source page link)]
    (let [text (get docs path) secs (sections text)]
      (if-let [h (:heading link)]
        (get secs (slug h))
        (when (empty? secs) text)))))

;; ============================================================================
;; v2's view of a v4 wiki
;; ============================================================================

(defn- as-v2-text
  "A v4 page as v2's checker reads it: without its front matter (a document
   name such as `annual-report-2019.md` is not a claim) and its quote lines
   (attributed evidence, not the wiki's statements), every link written as
   v2's markdown link with an absolute target and a neutral label:
   `[[charter]]` → `[source](/docs/charter.md)`, `[[atoms/x]]` → `[page](/wiki/atoms/x.md)`."
  [docs pages source target-dir page text]
  (let [canonical (fn [l]
                    (if-let [d (resolve-doc docs source page l)]
                      (str "[source](" d ")")
                      (when-let [p (resolve-page pages target-dir page l)]
                        (str "[page](" p ")"))))
        ;; a link's label (a markdown label, a wikilink's alias) is visible
        ;; text and stays for v2 to read; the target becomes canonical, or
        ;; goes when it names nothing (a broken link, reported by v4)
        md (fn [[_ label t h]]
             (let [c (canonical {:target (str/trim t) :heading h :kind :md})]
               (str label (when c (str " " c)))))
        wiki (fn [[_ t h alias]]
               (let [c (canonical {:target (str/trim t) :heading h :kind :wiki})]
                 (str (some-> alias (str " ")) c)))]
    (-> (second (front-matter text))
        ;; quotes are attributed evidence, checked for fidelity on their own:
        ;; currency, grounding and copies judge the wiki's own words
        strip-quotes
        (str/replace #"\[([^\]\n]*)\]\(\s*<?([^)\s>#]+)(?:#([^)\s>]*))?>?(?:\s+\"[^\"]*\")?\s*\)" md)
        (str/replace #"\[\[([^\]|#\n]+)(?:#([^\]|\n]*))?(?:\|([^\]\n]*))?\]\]" wiki))))

;; ============================================================================
;; The score
;; ============================================================================

(defn- ratio*
  "`n`/`d`, and `none` (default 1.0) when there is nothing to count."
  ([n d] (ratio* n d 1.0))
  ([n d none] (if (pos? d) (double (/ n d)) none)))

(defn- credit
  "The share of a weight that `ratio` earns when each 1% short costs
   `per-point` %: an error a reader cannot see costs more than its share."
  [ratio per-point]
  (max 0.0 (- 1.0 (* per-point (- 1.0 ratio)))))

(def atom-types
  "The atom types vault-wiki and this checker know."
  #{"entity" "concept" "event" "person" "place" "organisation" "organization" "case" "instrument"})

(defn- as-list [v] (cond (nil? v) [] (sequential? v) (vec v) :else [v]))
(defn- text-field? [v] (and (string? v) (not (str/blank? v))))

(defn- shares-evidence?
  "Whether quote `q` bears on claim prose `p`: a shared number, or a shared
   word of five letters or more."
  [q p]
  (let [nq (normalize q) np (normalize p)
        nums #(set (re-seq #"\d+" %)) ws #(set (re-seq #"[a-z]{5,}" %))]
    (boolean (or (seq (set/intersection (nums nq) (nums np)))
                 (seq (set/intersection (ws nq) (ws np)))))))

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
        untiered (vec (sort (keep (fn [[p {:keys [tier]}]] (when (= :other tier) p)) parsed)))
        tiered-pages (apply dissoc pages untiered)
        doc-of (fn [page l] (resolve-doc sources source page l))
        page-of (fn [page l] (resolve-page pages target page l))
        ;; ---- v2's measures, on v2's view of the tiered wiki ----
        v2 (wiki/score-wiki-v2
            {:pages (into {} (for [[p t] tiered-pages] [p (as-v2-text sources pages source target p t)]))
             :sources sources :gold gold :target target :source source})
        copied (set (get-in v2 [:scores :copied-pages]))
        ;; ---- claims and their evidence ----
        ;; claims of atoms, overviews and the index: a quote anywhere is
        ;; checked (the index is not a place to hide one)
        claim-list (vec (for [[p {:keys [body]}] (merge content (select-keys parsed [index-path]))
                              c (claims body)]
                          (let [pr (prose (:text c))
                                qs (vec (for [q (quotes (:text c))]
                                          (let [docs (keep #(when-let [d (doc-of p %)] [% d]) (:citations q))
                                                ;; evidence for exactly the documents whose cited
                                                ;; text holds the quote
                                                holding (when (substantive? (:quote q))
                                                          (set (keep (fn [[l d]]
                                                                       (when-let [t (cited-text sources source p l)]
                                                                         (when (str/includes? (source-text t) (normalize (:quote q))) d)))
                                                                     docs)))
                                                ok? (and (seq holding) (= (count holding) (count (set (map second docs)))))]
                                            (assoc q :docs (set holding) :faithful? (boolean ok?)
                                                   :relevant? (shares-evidence? (:quote q) pr)))))]
                            (assoc c :page p :id [p (:index c)] :prose pr
                                   ;; the claim's own citations: its text, quote lines aside
                                   :cites (into #{} (keep #(doc-of p %)) (links (strip-quotes (:text c))))
                                   :quotes qs))))
        quote-list (vec (for [c claim-list q (:quotes c)] (assoc q :page (:page c) :heading (:heading c) :claim (:id c))))
        unfaithful (vec (remove :faithful? quote-list))
        headless (vec (filter #(nil? (:heading %)) quote-list))
        ;; every document a claim cites in its own words is quoted in that
        ;; claim, faithfully and to the point (vault-wiki: load-bearing facts
        ;; are quoted)
        citing-claims (filter #(seq (:cites %)) claim-list)
        ;; an atom's claim that says something (three words) and cites nothing
        uncited (vec (for [c claim-list
                           :when (and (= :atom (tier-of (:page c))) (:heading c) (empty? (:cites c))
                                      (<= 3 (count (re-seq #"\p{L}+" (str (:prose c))))))]
                       {:page (:page c) :heading (:heading c)}))
        unquoted (vec (keep (fn [c]
                              (let [evidenced (into #{} (comp (filter #(and (:faithful? %) (:relevant? %))) (mapcat :docs)) (:quotes c))
                                    missing (remove evidenced (:cites c))]
                                (when (seq missing) {:page (:page c) :heading (:heading c) :missing (vec (sort missing))})))
                            citing-claims))
        ;; every citation of a document: resolves, and names a section when the
        ;; document has sections; every link to a page resolves, its section too
        ;; (the Sources line is the page's bibliography: a bare [[doc]] there
        ;; names the document, it need not name a section)
        all-links (for [[p {:keys [links]}] (select-keys parsed (keys tiered-pages)) l links] [p l])
        bibliography (for [[p {:keys [body]}] (select-keys parsed (keys tiered-pages))
                           line (re-seq sources-line-re (str body))
                           l (links line)]
                       [p l])
        cited-links (for [[p {:keys [body]}] (select-keys parsed (keys tiered-pages))
                          l (links (str/replace (str body) sources-line-re ""))]
                      [p l])
        ;; a link that names no document and no page is a broken link (below),
        ;; not also a bad citation
        doc-links (concat (for [[p l] cited-links :when (doc-of p l)] [p l false])
                          (for [[p l] bibliography :when (doc-of p l)] [p l true]))
        bad-citations (vec (for [[p l bib?] doc-links
                                 ;; a bibliography entry may name a document without a section;
                                 ;; a section it names must exist
                                 :when (not (and (doc-of p l) (or (and bib? (nil? (:heading l))) (cited-text sources source p l))))]
                             {:page p :target (:target l) :heading (:heading l)}))
        page-headings (fn [p] (into #{(slug (str (second (re-find #"(?m)^#\s+(.*)$" (get-in parsed [p :body] "")))))}
                                    (keep #(some-> (:heading %) slug)) (claims (get-in parsed [p :body]))))
        broken-links (vec (for [[p l] all-links
                                :when (not (doc-of p l))
                                :let [t (page-of p l)]
                                :when (or (nil? t) (and (:heading l) (not (contains? (page-headings t) (slug (:heading l))))))]
                            {:page p :target (:target l) :heading (:heading l)}))
        ;; ---- coverage, claim by claim, in the claim's own words ----
        found (into #{}
                    (keep (fn [{:keys [id terms] srcs :sources}]
                            (let [wanted (set (map #(str source "/" %) srcs))]
                              (when (some (fn [c]
                                            (and (not (contains? copied (:page c)))
                                                 ;; the index navigates; v2 does not ground or copy-check it
                                                 (not= index-path (:page c))
                                                 (some wanted (:cites c))
                                                 (every? #(#'wiki/term-in? (:prose c) %) terms)))
                                          claim-list)
                                id))))
                    (:facts gold))
        ;; ---- front matter ----
        ;; all of a page's Sources lines, as one
        sources-line (fn [body] (some->> (re-seq sources-line-re (str body)) seq (str/join "\n")))
        claim-docs (fn [p] (into #{} (comp (filter #(= p (:page %))) (mapcat (fn [c] (into (:cites c) (mapcat :docs (:quotes c))))))
                                 claim-list))
        atom-problems (into {}
                            (keep (fn [[p {:keys [fm body]}]]
                                    (let [declared-list (as-list (:source fm))
                                          declared (set (keep #(when (string? %) (doc-of p {:target % :kind :wiki})) declared-list))
                                          listed (set (keep #(doc-of p %) (links (or (sources-line body) ""))))
                                          problems (cond-> []
                                                     (nil? fm) (conj :no-front-matter)
                                                     (and fm (not (contains? atom-types (:type fm)))) (conj :bad-type)
                                                     (and fm (empty? declared-list)) (conj :no-source)
                                                     (and fm (not= (count declared) (count declared-list))) (conj :unknown-source)
                                                     (and fm (not (text-field? (:entry-point fm)))) (conj :no-entry-point)
                                                     (nil? (sources-line body)) (conj :no-sources-line)
                                                     (and (sources-line body) (not= declared listed)) (conj :sources-out-of-sync)
                                                     (and fm (not= declared (claim-docs p))) (conj :source-not-what-claims-cite))]
                                      (when (seq problems) [p problems]))))
                            atoms)
        overview-problems (into {}
                                (keep (fn [[p {:keys [fm links]}]]
                                        (let [problems (cond-> []
                                                         (not= "overview" (:type fm)) (conj :not-type-overview)
                                                         (not-any? #(= :atom (tier-of %)) (keep #(page-of p %) links)) (conj :links-no-atom))]
                                          (when (seq problems) [p problems]))))
                                overviews)
        front-problems (merge atom-problems overview-problems)
        ;; ---- tiers ----
        page-links (fn [p] (into #{} (keep #(page-of p %)) (get-in parsed [p :links])))
        linked-from-overviews (reduce into #{} (map page-links (keys overviews)))
        orphans (vec (sort (remove linked-from-overviews (keys atoms))))
        unindexed (vec (sort (remove (page-links index-path) (keys overviews))))
        ;; ---- scores ----
        v2s (:scores v2)
        stale-hits (:stale-values v2s)
        ;; distractors in the whole wiki, quotes included: quoting the unrelated
        ;; organisation's document is still writing about it
        full-text (str/lower-case (str/join "\n" (vals tiered-pages)))
        contamination (vec (distinct (concat (:distractor-terms v2s)
                                             (filter #(str/includes? full-text (str/lower-case %)) (:distractors gold)))))
        scores {:pages (count pages) :atoms (count atoms) :overviews (count overviews)
                :coverage (ratio* (count found) (count (:facts gold)))
                :currency (:currency v2s)
                :grounding (:grounding v2s)
                ;; a measure with nothing to count earns nothing (0.0): a
                ;; wiki without quotes is not faithful, it is unquoted
                :quote-fidelity (ratio* (- (count quote-list) (count unfaithful)) (count quote-list) 0.0)
                :quotes (count quote-list)
                :quoted-claims (ratio* (- (count citing-claims) (count unquoted)) (count citing-claims) 0.0)
                :claim-headers (ratio* (- (count quote-list) (count headless)) (count quote-list) 0.0)
                :citations-valid (ratio* (- (count doc-links) (count bad-citations)) (count doc-links) 0.0)
                :front-matter (ratio* (- (count content) (count front-problems)) (count content) 0.0)
                :tier-coverage (ratio* (- (count atoms) (count orphans)) (+ (count atoms) (count untiered)) 0.0)
                :entities (:entities v2s)
                :relations (:relations v2s)
                ;; diagnostics
                :unfaithful-quotes (vec (take 10 (map #(select-keys % [:page :heading :quote]) unfaithful)))
                :headless-quotes (vec (take 10 (map #(select-keys % [:page :quote]) headless)))
                :unquoted-claims (vec (take 10 unquoted))
                :uncited-claims (vec (take 10 uncited))
                :uncited-claim-count (count uncited)
                :bad-citations (vec (take 10 bad-citations))
                :front-matter-problems front-problems
                :orphan-atoms orphans :unindexed-overviews unindexed :broken-links broken-links
                :untiered-pages untiered
                :copied-pages (vec (sort copied)) :stale-values stale-hits
                :unsupported-numbers (:unsupported-numbers v2s) :distractor-terms contamination}
        index? (and (contains? parsed index-path) (seq overviews) (empty? unindexed))
        checks (-> {:has-atoms? (boolean (seq atoms))
                    :has-overviews? (boolean (seq overviews))
                    :index? (boolean index?)
                    :quotes-faithful? (empty? unfaithful)
                    :every-claim-quoted? (and (seq citing-claims) (empty? unquoted))
                    :every-quote-under-a-heading? (empty? headless)
                    :citations-valid? (empty? bad-citations)
                    :front-matter? (empty? front-problems)
                    :no-orphan-atoms? (empty? orphans)
                    :no-untiered-pages? (empty? untiered)
                    :links-valid? (empty? broken-links)
                    :grounded? (<= 0.95 (:grounding v2s))
                    :no-copied-pages? (empty? copied)
                    :no-distractor-facts? (empty? contamination)}
                   (update :every-claim-quoted? boolean)
                   (into (for [{:keys [id]} (:facts gold)] [(keyword "fact" (name id)) (contains? found id)]))
                   (into (filter (fn [[k _]] (#{"current" "entity"} (namespace k)))) (:checks v2)))
        reward (if (empty? atoms)
                 0.0
                 (+ (* 0.20 (:coverage scores))
                    (* 0.10 (max 0.0 (- 1.0 (* 0.2 (count stale-hits)))))
                    (* 0.10 (credit (:grounding scores) 5.0))
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
  ;; front matter and title off first: unscanning joins their lines
  (->> (str/split (gen/unscan (str/replace (str text) #"(?s)^---\n.*?\n---\n#[^\n]*\n" "")) #"(?<=[.!?])\s+|\n")
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
        ;; a sentence that bears on the claim, without avoided terms if one will do
        bearing (filter #(pos? (score %)) cands)
        pool (or (seq (remove bad? bearing)) (seq bearing) (seq (remove bad? cands)) cands)]
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
        ;; quotes may state stale values (they are evidence, not the wiki's
        ;; statements); distractors they must not
        avoid (:distractors gold)
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

;; ============================================================================
;; As a workflow and an experiment
;; ============================================================================

(defn task
  "The v4 instruction: v2's content rules in the two-tier layout this checker
   scores."
  [{:keys [source target]}]
  (str "The folder " source " contains source documents. Write a wiki in " target " from them, in two tiers.\n\n"
       "Atoms, " target "/atoms/<slug>.md: one page per important entity (the organisation, people, places, "
       "projects, events). Each starts with front matter\n"
       "---\ntype: entity\nsource: [charter.md, annual-report-2019.md]\nentry-point: One line on what the page is for.\n---\n"
       "(type is entity, concept or event; source lists exactly the documents the page's claims cite), then "
       "`# Title`, then one claim per `## ` heading. A claim states its facts in your own words and cites the "
       "document they come from as [[charter]], or as [[charter#Section]] when the document has `##` sections. "
       "Under it, for every document it cites, a verbatim sentence from that document on a line of its own:\n"
       "> \"The exact sentence from the document.\" [[charter#Section]]\n"
       "End each atom with a line **Sources:** [[charter]], [[annual-report-2019]].\n\n"
       "Link related atoms to each other as [[atoms/<slug>]] (a person to the organisation they worked "
       "for, a project to its designer).\n\n"
       "Overviews, " target "/overviews/<slug>.md: front matter `type: overview`, linking the atoms they cover "
       "as [[atoms/<slug>]]; every atom is linked from an overview.\n\n"
       "Index, " target "/index.md: links every overview as [[overviews/<slug>]].\n\n"
       "The documents differ in authority and date; some are outdated or wrong. State the current value and mark "
       "older ones as former. Do not copy documents beyond the quotes, and do not include facts about organisations "
       "other than the one the documents are about. Use the file tools to read and write files."))

(def ^:private no-gold
  "A gold with nothing to find: what a room without known answers is scored on."
  {:entities {} :facts [] :stale [] :current [] :distractors [] :relations []})

(def structure-checks
  "The checks that need no gold: structure, evidence, links, grounding, copies,
   and every claim of an atom citing a source (with gold, coverage and
   currency judge what is said; without it, nothing would)."
  [:has-atoms? :has-overviews? :index? :every-claim-cites? :quotes-faithful? :every-claim-quoted?
   :every-quote-under-a-heading? :citations-valid? :front-matter? :no-orphan-atoms?
   :no-untiered-pages? :links-valid? :grounded? :no-copied-pages?])

(defn score-structure
  "Score a v4 wiki without gold (a user's room): `structure-checks`, with the
   share passed as the reward. `{:scores :checks :reward}`."
  [opts]
  (let [{:keys [scores checks]} (score-wiki-v4 (assoc opts :gold no-gold))
        checks (select-keys (assoc checks :every-claim-cites? (zero? (:uncited-claim-count scores))) structure-checks)]
    {:scores (dissoc scores :coverage :currency :entities :relations :stale-values :distractor-terms)
     :checks checks
     :reward (if (:has-atoms? checks)
               (/ (Math/round (* 1000.0 (/ (count (filter true? (vals checks))) (count checks)))) 1000.0)
               0.0)}))

(defn evaluator
  "The v4 checker as an Evaluator: against `gold` (one fixed world), else the
   gold of each attempt's environment (a v3 world, `wiki/world-of`); with
   `:structure? true`, `score-structure` (no gold)."
  [{:keys [source target gold structure?]}]
  ;; no basis: catalog_start's environments carry the verifier's id and
  ;; version only
  (evaluation/make-evaluator
   {:id (if structure? :catalog/wiki-structure :catalog/wiki)
    :version (if structure? 1 4)
    :capture (fn [{world :world/room}]
               {:pages (ws/read-tree world target)
                :sources (ws/read-tree world source)})
    :observe (fn [{:keys [default result environment] captured :execution/evidence}]
               (let [opts {:pages (:pages captured) :sources (:sources captured)
                           :target target :source source}
                     scored (if structure?
                              (score-structure opts)
                              (score-wiki-v4 (assoc opts :gold (or gold (:gold (wiki/world-of environment))))))]
                 (assoc default
                        :run-status (:run/status result)
                        :pages (:pages captured)
                        :scores (:scores scored)
                        :checks (:checks scored)
                        :reward (:reward scored))))
    :verify (fn [_ {:keys [run-status checks reward]}]
              {:checks (assoc checks :completed? (= :completed run-status))
               :reward (if (= :completed run-status) reward 0.0)})}))

(defn experiment-plan
  "v3's plan (`wiki/experiment-plan` with `:version 3`: its worlds, splits and
   knobs) with v4's task and checker."
  [{:keys [models budget-dollars prompt] :as opts}]
  (let [setup (wiki/world-setup-v3)
        ev (evaluator params)
        ref (evaluation/evaluator-ref ev)
        v3-envs (wiki/environments-v3 setup ev (update opts :test-key #(or % (System/getenv "DVERGR_WIKI_TEST_KEY"))))
        envs (mapv (fn [e]
                     (environment/make-environment
                      {:id :catalog/wiki-v4
                       :task (task params)
                       :verifier {:id (:verifier/id ref) :version (:verifier/version ref)}
                       :limits (:environment/limits e)
                       :world (:environment/world e)
                       :metadata (:environment/metadata e)}))
                   v3-envs)
        {:keys [team ids]} (workflow/candidates {:models models :profile "developer"
                                                 :budget-dollars budget-dollars :prompt prompt})]
    {:benchmark :catalog-wiki
     :capabilities {:world-setup setup :evaluator ev}
     :environments envs
     :team team
     :models (mapv #(:agent/model-policy (roster/agent team %)) ids)
     :dataset {:id :catalog/wiki-v4
               :metadata {:generator gen/version :split (or (:split opts) :dev)
                          :worlds (mapv #(let [m (:environment/metadata %)] (or (:seed m) (:corpus m))) envs)}}}))
