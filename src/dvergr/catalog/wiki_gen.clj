(ns dvergr.catalog.wiki-gen
  "wiki/v3's benchmark set: worlds generated from a seed, instead of v2's one
   hand-written world.

   A world is an organisation with a history: founders and a board, three
   general managers in turn, a plant that is renamed, a supply project that is
   delayed, an incident that turns out to be a false alarm, a crisis, a
   partnership, values that change (members, capacity) and one unrelated
   organisation nearby. `documents` writes it the way v2's documents are
   written (a charter, reports of different years, a newsletter, a press
   release and its verbatim copy, board minutes, an incident report, an
   unofficial blog that gets the founding year wrong, an agenda, a news item
   about the other organisation). `gold` is derived from the world, not from
   the text, in v2's shape, so v2's checker scores it (`score-wiki-v2`).
   `reference-wiki` writes a correct wiki of the world: calibration pins that
   it scores top on every seed and that damaged variants do not.

   Everything is a pure function of the seed. Values a reader must treat with
   care (the blog's year, the old member count and capacity, the planned
   opening, the old plant name) are chosen so that no other document states
   them, or the currency check could not tell them apart."
  (:require [clojure.string :as str]))

(def version
  "The generator's version: part of every environment, so a changed generator
   is a different benchmark set."
  1)

;; ============================================================================
;; Sampling
;; ============================================================================

(defn- rng [seed] (java.util.Random. (long seed)))

(defn- between
  "An integer in [lo, hi]."
  [^java.util.Random r lo hi]
  (+ lo (.nextInt r (inc (- hi lo)))))

(defn- pick [^java.util.Random r xs] (nth xs (.nextInt r (count xs))))

(defn- pick-distinct
  "`n` distinct elements of `xs`."
  [^java.util.Random r n xs]
  (loop [acc [] left (vec xs)]
    (if (or (= n (count acc)) (empty? left))
      acc
      (let [i (.nextInt r (count left))]
        (recur (conj acc (nth left i))
               (into (subvec left 0 i) (subvec left (inc i))))))))

(def ^:private place-heads
  ["Alder" "Brackley" "Cinder" "Dunmore" "Elmsworth" "Fallow" "Greywater" "Harrow" "Ivel"
   "Juniper" "Kestrel" "Linden" "Marram" "Nettlefold" "Oxley" "Pennant" "Rushmere" "Sedge"
   "Thistle" "Umber" "Wrenford" "Yarrow" "Corran" "Halden" "Morrow" "Brenn"])

(def ^:private place-tails ["Valley" "Downs" "Fen" "Moor" "Glen" "Heath" "Vale" "Plain"])

(def ^:private rivers
  ["Tallow" "Esk" "Fennick" "Dace" "Corrie" "Lune" "Harl" "Wend" "Oriel" "Sallow" "Brin" "Ketter"])

(def ^:private heights ["Heron" "Kite" "Raven" "Curlew" "Plover" "Merlin" "Lapwing" "Osprey"])

(def ^:private first-names
  ["Ada" "Rowan" "Mira" "Jonas" "Colm" "Ines" "Tomas" "Leila" "Anders" "Nadia" "Kofi" "Petra"
   "Emeka" "Sanna" "Hugo" "Yara" "Dmitri" "Aoife" "Rafael" "Hana" "Oskar" "Priya" "Luca" "Ingrid"
   "Wanjiru" "Mateo" "Freya" "Tariq" "Elin" "Kenji"])

(def ^:private last-names
  ["Lindqvist" "Achebe" "Szabo" "Kessel" "Hartigan" "Moreau" "Okafor" "Brandt" "Castell" "Varga"
   "Nakamura" "Oduya" "Halvorsen" "Petrov" "Quinlan" "Ferreira" "Mbeki" "Sorensen" "Tanaka"
   "Wojcik" "Aydin" "Kowalski" "Dubois" "Eklund" "Ivanova" "Maddox" "Novak" "Renner"])

(def ^:private months
  ["January" "February" "March" "April" "May" "June" "July" "August" "September" "October"
   "November" "December"])

(def ^:private kinds
  "What an organisation of each kind runs, and how its documents speak of it."
  {:water   {:org "Water Cooperative" :plant "treatment plant" :plant-verb "treats"
             :project "pipeline" :project-from "reservoir" :unit "million litres a day"
             :incident "boil-water notice"
             :incident-names ["boil-water notice" "boil water notice" "Boil-water notice" "Boil-Water Notice"]
             :trigger "reported high turbidity" :clear "Laboratory tests found no contamination"
             :area "the whole valley" :crisis "drought" :restriction "restricted garden watering"
             :purpose "to build and run a shared water supply" :founders "farming households"
             :trust "River Trust" :trust-work "to restore the riverbank upstream of"}
   :energy  {:org "Energy Cooperative" :plant "hydro station" :plant-verb "generates"
             :project "transmission line" :project-from "wind farm" :unit "megawatts"
             :incident "outage warning"
             :incident-names ["outage warning" "Outage warning" "Outage Warning"]
             :trigger "reported a voltage fault" :clear "Line inspections found no fault"
             :area "every member household" :crisis "heatwave" :restriction "asked members to cut evening use"
             :purpose "to bring electricity to the district" :founders "smallholder families"
             :trust "Wetlands Trust" :trust-work "to protect the wetland below"}
   :heating {:org "Heating Cooperative" :plant "heating plant" :plant-verb "supplies"
             :project "heat main" :project-from "biomass depot" :unit "megawatts of heat"
             :incident "supply shutdown"
             :incident-names ["supply shutdown" "Supply shutdown" "Supply Shutdown"]
             :trigger "reported a pressure drop" :clear "Pressure tests found no leak"
             :area "the whole town" :crisis "cold snap" :restriction "lowered supply temperatures"
             :purpose "to heat the town's homes from one plant" :founders "tenant households"
             :trust "Woodland Trust" :trust-work "to replant the woodland around"}})

(def ^:private delays ["a landslide" "flooding" "a planning dispute" "a contractor's collapse"])

(def ^:private delay-terms
  {"a landslide" "landslide" "flooding" "flooding" "a planning dispute" "planning dispute"
   "a contractor's collapse" "contractor"})

;; ============================================================================
;; Numbers in words
;; ============================================================================

(def ^:private ones
  ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten" "eleven" "twelve"
   "thirteen" "fourteen" "fifteen" "sixteen" "seventeen" "eighteen" "nineteen"])

(def ^:private tens {2 "twenty" 3 "thirty" 4 "forty" 5 "fifty" 6 "sixty" 7 "seventy" 8 "eighty" 9 "ninety"})

(defn words
  "`n` (0–99) in words: 42 → \"forty-two\"."
  [n]
  (cond (< n 20) (nth ones n)
        (zero? (mod n 10)) (tens (quot n 10))
        :else (str (tens (quot n 10)) "-" (nth ones (mod n 10)))))

(defn- thousands
  "`n` with thousands separators, 4200 → \"4,200\", whatever the JVM's locale."
  [n]
  (String/format java.util.Locale/US "%,d" (object-array [(long n)])))

;; ============================================================================
;; The world
;; ============================================================================

(defn- base-world
  "The world of `seed` at scale 1: v2's shape."
  [seed]
  (let [r (rng seed)
        kind (pick r (sort (keys kinds)))
        k (kinds kind)
        [place-head other-head] (pick-distinct r 2 place-heads)
        place (str place-head " " (pick r place-tails))
        other-place (str other-head " " (pick r place-tails))
        [river trust-river] (pick-distinct r 2 rivers)
        height (pick r heights)
        [gm1 gm2 gm3 designer director] (map #(str %1 " " %2)
                                             (pick-distinct r 5 first-names)
                                             (pick-distinct r 5 last-names))
        founded (between r 1935 1960)
        ;; The years the documents state; the blog's and the planned opening
        ;; are stated nowhere else.
        gm2-from (between r 2000 2006)
        report-1 (+ gm2-from 2)
        crisis (inc report-1)
        newsletter (inc crisis)
        expected (inc newsletter)
        opened (inc expected)
        renamed (inc opened)
        incident (+ renamed (between r 2 3))
        gm3-from (inc incident)
        latest (+ gm3-from (between r 2 4))
        members-1 (* 50 (between r 40 110))
        members-2 (+ members-1 (* 50 (between r 8 30)))
        cap-1 (between r 5 12)
        cap-2 (+ cap-1 (between r 2 5))
        project-cap (between r 2 (dec cap-1))
        start-day (between r 2 12)
        days (between r 9 17)
        plant-old (str (pick r ["East" "West" "North" "South" "Old"]) (pick r ["bank" "field" "mill" "gate" "ford"]))
        plant-new (str (last (str/split designer #" ")) " Works")]
    {:seed seed :version version :kind kind
     :place place :org (str place " " (:org k))
     :river river :trust (str trust-river " " (:trust k)) :trust-river trust-river
     :founded founded :founded-wrong (- founded (between r 1 3))
     :founded-day (between r 2 27) :founded-month (pick r months)
     :founders (between r 21 79) :board (pick r [5 7 9])
     :gm1 gm1 :gm2 gm2 :gm3 gm3 :designer designer :director director
     :designed (+ founded (between r 3 8))
     :gm2-from gm2-from :gm3-from gm3-from :gm3-month (pick r months)
     :gm3-ops-from newsletter
     :report-1 report-1 :crisis crisis :crisis-weeks (between r 6 14)
     :newsletter newsletter :expected expected :opened opened :opened-month (pick r months)
     :renamed renamed :incident incident :incident-month (pick r months)
     :incident-start start-day :incident-end (+ start-day days) :incident-days days
     :latest latest :upgraded (dec latest)
     :members-1 members-1 :members-2 members-2
     :cap-1 cap-1 :cap-2 cap-2 :project-cap project-cap
     :project (str height " Ridge " (:project k)) :project-source (str height " Ridge " (:project-from k))
     :project-km (between r 12 48) :delay (pick r delays)
     :plant-old plant-old :plant-new plant-new
     :other-place other-place :other-farms (between r 120 480) :other-pct (between r 4 12)
     :other-site (str (pick r ["Lark" "Finch" "Rook" "Wren"]) " " (pick r ["Hollow" "Cross" "End" "Gate"]))}))

;; ----------------------------------------------------------------------------
;; Scale: more documents, more stale values, more distractors
;; ----------------------------------------------------------------------------
;;
;; A real document dump is an order of magnitude larger than twelve documents
;; (FreshWiki articles draw on ~70 sources). Scale s > 1 adds, from a random
;; stream of its own (so scale 1 is exactly the base world): annual reports for
;; years in between, each stating the member count of its year (a new stale
;; value to qualify); routine newsletters with names and numbers but no gold
;; fact; and more articles about other organisations (more distractors). Names,
;; places and years are drawn so that they collide with nothing the base world
;; states.

(defn- interpolate [a b t] (+ a (* (- b a) t)))

(defn- extra
  "What scale `scale` adds to `base`."
  [base scale]
  (let [m (dec scale)
        r (rng (bit-xor (long (:seed base)) (* 7919 (long scale))))
        used-people (set (mapcat #(str/split (get base %) #" ") [:gm1 :gm2 :gm3 :designer :director]))
        free-first (remove used-people first-names)
        free-last (remove used-people last-names)
        used-heads (set (map #(first (str/split (get base %) #" ")) [:place :other-place]))
        free-heads (remove used-heads place-heads)
        ;; Years with no report of their own, and stating no stale value: a
        ;; title like "Annual report 2011" must not repeat the planned opening.
        taken #{(:report-1 base) (:opened base) (:latest base) (:expected base) (:founded-wrong base)}
        years (vec (remove taken (range (inc (:report-1 base)) (:latest base))))
        report-years (sort (pick-distinct r (min (count years) (* 2 m)) years))
        span (- (:latest base) (:report-1 base))
        members (reduce (fn [acc y]
                          (let [v (* 50 (Math/round (/ (interpolate (:members-1 base) (:members-2 base)
                                                                    (/ (- y (:report-1 base)) (double span)))
                                                       50.0)))
                                v (loop [v v]
                                    (if (or (contains? (set (vals acc)) v)
                                            (#{(:members-1 base) (:members-2 base)} v))
                                      (recur (+ v 50)) v))]
                            (assoc acc y v)))
                        {} report-years)
        n-news (* 2 m)
        n-other (min m (count free-heads))
        people (map #(str %1 " " %2) (pick-distinct r (+ (* 2 n-news) n-other) free-first)
                    (pick-distinct r (+ (* 2 n-news) n-other) free-last))
        [news-people other-directors] [(take (* 2 n-news) people) (drop (* 2 n-news) people)]]
    {:reports (vec (for [y report-years]
                     {:year y :members (members y)
                      :gm (if (< y (:gm3-from base)) (:gm2 base) (:gm3 base))
                      :plant (if (< y (:renamed base)) (str "The " (:plant-old base) " plant") (:plant-new base))
                      :capacity (if (< y (:upgraded base)) (:cap-1 base) (:cap-2 base))}))
     :newsletters (vec (for [[i [a b]] (map-indexed vector (partition 2 news-people))]
                         {:year (pick r (if (seq years) years [(:latest base)]))
                          :season (pick r ["spring" "summer" "winter"]) :i i
                          :elected [a b] :volunteers (between r 12 90) :day (between r 2 27)
                          :month (pick r months) :event (pick r ["open day" "river clean-up" "school visit" "summer fair"])}))
     :others (vec (for [[head director] (map vector (pick-distinct r n-other free-heads) other-directors)]
                    {:place (str head " " (pick r place-tails)) :director director
                     :site (str (pick r ["Heath" "Mill" "Stone" "Ash"]) " " (pick r ["Farm" "Lane" "Point" "Row"]))
                     :count (between r 40 900) :unit (pick r ["hectares" "customers" "wells"])}))}))

(defn world
  "The world of `seed`: a map of everything its documents and its gold say.
   With `:scale` s > 1, also `:extra`: s-1 times more reports, newsletters and
   other organisations (see above); scale 1 is the base world."
  ([seed] (world seed {}))
  ([seed {:keys [scale] :or {scale 1}}]
   (let [base (base-world seed)]
     (if (<= scale 1) base (assoc base :scale scale :extra (extra base scale))))))

(defn- k [w key] (get-in kinds [(:kind w) key]))

;; ============================================================================
;; Documents
;; ============================================================================

(defn- doc [type date title & paras]
  (str "---\ntype: " type "\ndate: " date "\n---\n# " title "\n\n" (str/join "\n\n" paras) "\n"))

(defn document-names
  "The file names of `w`'s documents, in /docs."
  [w]
  {:charter (str "charter-" (:founded w) ".md")
   :blog "blog-history.md"
   :report-1 (str "annual-report-" (:report-1 w) ".md")
   :newsletter (str "newsletter-" (:newsletter w) ".md")
   :report-opened (str "annual-report-" (:opened w) ".md")
   :rename "press-release-rename.md"
   :rename-copy "press-release-rename-copy.md"
   :incident (str "incident-" (:incident w) ".md")
   :minutes (str "board-minutes-" (:gm3-from w) ".md")
   :report-latest (str "annual-report-" (:latest w) ".md")
   :agenda (str "agenda-" (inc (:latest w)) ".md")
   :other (str (-> (:other-place w) str/lower-case (str/replace " " "-")) ".md")})

(declare extra-documents)

(defn documents
  "`{\"/docs/x.md\" text}`: `w` as a folder of documents of different date,
   genre and authority."
  [w]
  (let [n (document-names w)
        plant (k w :plant)
        unit (k w :unit)
        rename (doc "press release" (str (:renamed w) "-03-20") (str (:plant-old w) " plant renamed " (:plant-new w))
                    (str "The cooperative's " plant " on the " (:river w) " River, known since its construction as the "
                         (:plant-old w) " plant, is renamed " (:plant-new w) " in honour of " (:designer w)
                         ", the engineer who designed it in " (:designed w) "."))]
    (merge
     (update-keys
      {(:charter n)
       (doc "charter" (format "%d-04-11" (:founded w)) (str "Charter of the " (:org w))
            (str "The " (:org w) " is founded on " (:founded-day w) " " (:founded-month w) " " (:founded w) " by "
                 (words (:founders w)) " " (k w :founders) " of " (:place w) ", " (k w :purpose) ".")
            (str "The cooperative is owned by its members. Each member household has one vote at the annual "
                 "meeting, which elects a board of " (words (:board w)) ". The board appoints a general manager."))

       (:blog n)
       (doc "blog post (unofficial, community history blog)" "2021-06-02" (str "A short history of " (:place w))
            (str (:place w) " got its cooperative in " (:founded-wrong w) ", when a group of neighbours pooled "
                 "their savings. For decades the cooperative ran everything from the old " (:plant-old w) " plant.")
            (str "Old-timers still call the plant \"" (:plant-old w) "\", whatever the signs say now."))

       (:report-1 n)
       (doc "annual report" (format "%d-12-15" (:report-1 w)) (str "Annual report " (:report-1 w))
            (str "The cooperative serves " (thousands (:members-1 w)) " member households. General manager "
                 (:gm2 w) ", appointed in " (:gm2-from w) " after the retirement of " (:gm1 w)
                 ", presented a plan for a second supply: the " (:project w) ", from the " (:project-source w) ".")
            (str "The " (:plant-old w) " plant on the " (:river w) " River " (k w :plant-verb) " "
                 (:cap-1 w) " " unit "."))

       (:newsletter n)
       (doc "member newsletter" (format "%d-09-01" (:newsletter w)) (str "Newsletter, autumn " (:newsletter w))
            (str "Construction of the " (:project w) " is under way. The line, " (:project-km w)
                 " kilometres long, is expected to open in " (:expected w) ".")
            (str "During the " (k w :crisis) " of " (:crisis w) " the cooperative " (k w :restriction) " for "
                 (words (:crisis-weeks w)) " weeks."))

       (:report-opened n)
       (doc "annual report" (format "%d-12-12" (:opened w)) (str "Annual report " (:opened w))
            (str "After a delay caused by " (:delay w) " on the construction route, the " (:project w)
                 " opened in " (:opened-month w) " " (:opened w) ", a year later than planned. It can deliver "
                 (:project-cap w) " " unit "."))

       (:rename n) rename
       (:rename-copy n) rename

       (:incident n)
       (doc "incident report" (format "%d-%02d-04" (:incident w) (min 12 (inc (.indexOf ^java.util.List months (:incident-month w)))))
            (str (str/capitalize (k w :incident)) ", " (:incident-month w) " " (:incident w))
            (str "On " (:incident-start w) " " (:incident-month w) " " (:incident w) " a sensor at " (:plant-new w) " "
                 (k w :trigger) " and the cooperative issued a " (k w :incident) " for " (k w :area) ". "
                 (k w :clear) "; the sensor was faulty. The " (k w :incident) " was lifted on "
                 (:incident-end w) " " (:incident-month w) " " (:incident w) ".")
            "The cooperative replaced the sensor array and added a second, independent sensor line.")

       (:minutes n)
       (doc "board minutes" (format "%d-02-08" (:gm3-from w)) (str "Board minutes, 8 February " (:gm3-from w))
            (str (:gm2 w) " announced a retirement as general manager after " (words (- (:gm3-from w) (:gm2-from w)))
                 " years. The board appointed " (:gm3 w) ", head of operations at " (:plant-new w) " since "
                 (:gm3-ops-from w) ", as general manager from 1 " (:gm3-month w) " " (:gm3-from w) "."))

       (:report-latest n)
       (doc "annual report" (format "%d-12-09" (:latest w)) (str "Annual report " (:latest w))
            (str "The cooperative now serves " (thousands (:members-2 w)) " member households. " (:plant-new w) " "
                 (k w :plant-verb) " " (:cap-2 w) " " unit " after its " (:upgraded w) " upgrade, and the "
                 (:project w) " supplies up to " (:project-cap w) " " unit " at peak.")
            (str "General manager " (:gm3 w) " signed a partnership with the " (:trust w) " " (k w :trust-work) " "
                 (:plant-new w) "."))

       (:agenda n)
       (doc "meeting agenda" (format "%d-05-02" (inc (:latest w))) "Annual meeting agenda"
            "1. Welcome\n2. Approval of last year's minutes\n3. Report of the general manager\n4. Election of board members\n5. Any other business")

       (:other n)
       (doc "news article (about a different organisation)" "2020-08-14" (str (:other-place w) " Irrigation District raises fees")
            (str "The " (:other-place w) " Irrigation District, a separate body across the ridge, raised its fees by "
                 (:other-pct w) " percent. Its director, " (:director w) ", said the district's " (:other-farms w)
                 " farms need a new pumping station at " (:other-site w) "."))}
      #(str "/docs/" %))
     (update-keys (extra-documents w) #(str "/docs/" %)))))

(defn- extra-documents
  "The documents scale adds (none at scale 1), by file name."
  [w]
  (let [{:keys [reports newsletters others]} (:extra w)
        unit (k w :unit)]
    (merge
     (into {} (for [{:keys [year members gm plant capacity]} reports]
                [(str "annual-report-" year ".md")
                 (doc "annual report" (format "%d-12-10" year) (str "Annual report " year)
                      (str "The cooperative serves " (thousands members) " member households. General manager " gm
                           " reported a year without major works.")
                      (str plant " " (k w :plant-verb) " " capacity " " unit "."))]))
     (into {} (for [{:keys [year season i elected volunteers day month event]} newsletters]
                [(str "newsletter-" year "-" season "-" i ".md")
                 (doc "member newsletter" (format "%d-%02d-01" year (inc (.indexOf ^java.util.List months month)))
                      (str "Newsletter, " season " " year)
                      (str "The members' meeting on " day " " month " elected " (first elected) " and " (second elected)
                           " to the events committee.")
                      (str volunteers " volunteers took part in the " event "."))]))
     (into {} (for [{:keys [place director site count unit]} others]
                [(str (-> place str/lower-case (str/replace " " "-")) ".md")
                 (doc "news article (about a different organisation)" "2021-03-18" (str place " Council plans new works")
                      (str "The " place " Council, which has no connection to the cooperative, is planning works at "
                           site ". Its chair, " director ", said the scheme covers " count " " unit "."))])))))

;; ============================================================================
;; Gold
;; ============================================================================

(defn gold
  "What a correct wiki of `w`'s documents says, in v2's gold shape
   (`resources/dvergr/catalog/wiki-v2/gold.edn` explains it)."
  [w]
  (let [n (document-names w)
        cap #(str % " " (first (str/split (k w :unit) #" ")))
        tenure (- (:gm3-from w) (:gm2-from w))]
    {:entities
     {:cooperative [(:org w)]
      :gm1 [(:gm1 w)] :gm2 [(:gm2 w)] :gm3 [(:gm3 w)]
      :plant [(:plant-new w)]
      :project [(:project w) (str/replace (:project w) (k w :project) (str/capitalize (k w :project)))]
      :designer [(:designer w)]
      :incident (k w :incident-names)
      :trust [(:trust w)]}

     :facts
     [{:id :founded :terms [(str (:founded w))] :sources [(:charter n)]}
      {:id :founders :terms [[(words (:founders w)) (str (:founders w))] ["households" "families"]] :sources [(:charter n)]}
      {:id :governance :terms [[(words (:board w)) (str (:board w))] "board"] :sources [(:charter n)]}
      {:id :gm1 :terms [(:gm1 w)] :sources [(:report-1 n)]}
      {:id :gm2 :terms [(:gm2 w) (str (:gm2-from w))] :sources [(:report-1 n) (:minutes n)]}
      {:id :gm3 :terms [(:gm3 w) (str (:gm3-from w))] :sources [(:minutes n) (:report-latest n)]}
      {:id :gm3-before :terms [(:gm3 w) ["head of operations" "operations"]] :sources [(:minutes n)]}
      {:id :members :terms [[(thousands (:members-2 w)) (str (:members-2 w))]] :sources [(:report-latest n)]}
      {:id :project-opened :terms [(:project w) (str (:opened w))] :sources [(:report-opened n)]}
      {:id :project-delay :terms [(delay-terms (:delay w))] :sources [(:report-opened n)]}
      {:id :project-length :terms [(let [km (:project-km w)]
                                     [(str km " kilometres") (str km " km") (str km "-kilometre") (str km " kilometers")])]
       :sources [(:newsletter n)]}
      {:id :project-capacity :terms [(cap (:project-cap w))] :sources [(:report-opened n) (:report-latest n)]}
      {:id :plant-renamed :terms [(:plant-new w) (:plant-old w) (str (:renamed w))] :sources [(:rename n) (:rename-copy n)]}
      {:id :plant-designer :terms [(:designer w) (str (:designed w))] :sources [(:rename n) (:rename-copy n)]}
      {:id :plant-capacity :terms [(cap (:cap-2 w))] :sources [(:report-latest n)]}
      {:id :incident-dates :terms [(str (:incident-start w) " " (:incident-month w)) (str (:incident-end w) " " (:incident-month w))]
       :sources [(:incident n)]}
      {:id :incident-cause :terms [["faulty" "false alarm" "no fault" "no leak" "no contamination"] "sensor"] :sources [(:incident n)]}
      {:id :incident-duration :terms [[(str (:incident-days w) " days") (str (words (:incident-days w)) " days")]]
       :sources [(:incident n)] :synthesis true}
      {:id :crisis :terms [(str (:crisis w)) [(str (words (:crisis-weeks w)) " weeks") (str (:crisis-weeks w) " weeks")]]
       :sources [(:newsletter n)]}
      {:id :partnership :terms [(:trust w)] :sources [(:report-latest n)]}
      {:id :gm2-tenure :terms [(:gm2 w) [(str (words tenure) " years") (str tenure " years")
                                         (str (:gm2-from w) " to " (:gm3-from w)) (str (:gm2-from w) "–" (:gm3-from w))
                                         (str (:gm2-from w) "-" (:gm3-from w))]]
       :sources [(:minutes n) (:report-1 n)]}]

     :stale
     (into
      [{:id :founded-wrong :value [(str (:founded-wrong w))]
        :ok-near ["blog" "unofficial" "claim" "incorrect" "mistaken" "some accounts" "sometimes" "erroneous" "contradict" "however"]}
       {:id :members-old :value [(thousands (:members-1 w)) (str (:members-1 w))]
        :ok-near [(str (:report-1 w)) "was" "had" "earlier" "previously" "grew" "from" "then"]}
       {:id :plant-capacity-old :value [(cap (:cap-1 w))]
        :ok-near [(str (:report-1 w)) "was" "before" "earlier" "previously" "upgrade" "from" "then"]}
       {:id :project-expected :value [(str (:expected w))]
        :ok-near ["expected" "planned" "scheduled" "originally" "delay" "late" "later than"]}
       {:id :plant-old-name :value [(:plant-old w)]
        :ok-near ["former" "formerly" "renamed" "originally" "known as" "previously" "until" "was" "old" "called"]}]
      (for [{:keys [year members]} (get-in w [:extra :reports])]
        {:id (keyword (str "members-" year)) :value [(thousands members) (str members)]
         :ok-near [(str year) "was" "had" "earlier" "previously" "grew" "from" "then"]}))

     :relations
     [[:gm3 :plant] [:designer :plant] [:gm2 :project] [:cooperative :plant]
      [:cooperative :trust] [:incident :plant]]

     :distractors (into [(:director w) (:other-site w) (str (:other-farms w) " farms")]
                        (mapcat (fn [{:keys [director site]}] [director site]) (get-in w [:extra :others])))}))

;; ============================================================================
;; A correct wiki (calibration)
;; ============================================================================

(defn- slug [s] (-> (str/lower-case s) (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-|-$)" "")))

(defn reference-wiki
  "`{\"/wiki/x.md\" text}`: a wiki of `w` that states every gold fact on an
   entity page citing its source, qualifies every stale value, grounds every
   number and links related pages. Calibration scores it top."
  [w]
  (let [n (document-names w)
        src (fn [label key] (str "([" label "](../docs/" (key n) "))"))
        link (fn [name] (str "[" name "](" (slug name) ".md)"))
        cap #(str % " " (k w :unit))
        incident-title (str (:incident w) " " (k w :incident))
        crisis-title (str (:crisis w) " " (k w :crisis))
        tenure (- (:gm3-from w) (:gm2-from w))
        pages
        {(:org w)
         (str "The " (:org w) " was founded on " (:founded-day w) " " (:founded-month w) " " (:founded w) " by "
              (words (:founders w)) " " (k w :founders) " " (k w :purpose) " " (src "charter" :charter) ". "
              "An unofficial community history blog gives " (:founded-wrong w) " instead, which the charter contradicts "
              (src "blog" :blog) ".\n\n"
              "It is owned by its members: each member household has one vote, and the annual meeting elects a board of "
              (words (:board w)) ", which appoints the general manager " (src "charter" :charter) ". It serves "
              (thousands (:members-2 w)) " member households " (src (str "annual report " (:latest w)) :report-latest)
              ", up from " (thousands (:members-1 w)) " in " (:report-1 w) " " (src (str "annual report " (:report-1 w)) :report-1) ".\n\n"
              "General managers: " (link (:gm1 w)) ", " (link (:gm2 w)) " (" (:gm2-from w) " to " (:gm3-from w) ") and "
              (link (:gm3 w)) " (since " (:gm3-from w) ") " (src (str "board minutes " (:gm3-from w)) :minutes) ". "
              "It runs " (link (:plant-new w)) " and the " (link (:project w)) ", and works with the " (link (:trust w)) ".")

         (:gm1 w)
         (str (:gm1 w) " was general manager of the " (link (:org w)) " until retiring; " (link (:gm2 w))
              " was appointed in " (:gm2-from w) " as successor " (src (str "annual report " (:report-1 w)) :report-1) ".")

         (:gm2 w)
         (str (:gm2 w) " was appointed general manager of the " (link (:org w)) " in " (:gm2-from w) ", succeeding "
              (link (:gm1 w)) ", and presented the plan for the " (link (:project w)) " "
              (src (str "annual report " (:report-1 w)) :report-1) ". "
              (:gm2 w) " retired in " (:gm3-from w) " after " (words tenure) " years and was succeeded by " (link (:gm3 w)) " "
              (src (str "board minutes " (:gm3-from w)) :minutes) ".")

         (:gm3 w)
         (str (:gm3 w) " has been general manager of the " (link (:org w)) " since 1 " (:gm3-month w) " " (:gm3-from w)
              ", and before that was head of operations at " (link (:plant-new w)) " from " (:gm3-ops-from w) " "
              (src (str "board minutes " (:gm3-from w)) :minutes) ". "
              (:gm3 w) " signed the partnership with the " (link (:trust w)) " "
              (src (str "annual report " (:latest w)) :report-latest) ".")

         (:plant-new w)
         (str (:plant-new w) " is the cooperative's " (k w :plant) " on the " (:river w) " River. It was known as the "
              (:plant-old w) " plant until it was renamed in " (:renamed w) " after " (link (:designer w))
              ", the engineer who designed it in " (:designed w) " " (src "press release" :rename) ".\n\n"
              "After its " (:upgraded w) " upgrade it " (k w :plant-verb) " " (cap (:cap-2 w)) " "
              (src (str "annual report " (:latest w)) :report-latest) "; in " (:report-1 w) " it "
              (k w :plant-verb) " " (cap (:cap-1 w)) " " (src (str "annual report " (:report-1 w)) :report-1) ". "
              (link (:gm3 w)) " ran its operations. It belongs to the " (link (:org w)) "; a faulty sensor there caused the "
              (link incident-title) " " (src "incident report" :incident) ".")

         (:project w)
         (str "The " (:project w) " runs " (:project-km w) " kilometres from the " (:project-source w) " "
              (src (str "newsletter " (:newsletter w)) :newsletter) ". Planned by " (link (:gm2 w)) ", it was expected to open in "
              (:expected w) ", but " (:delay w) " on the route delayed it and it opened in " (:opened-month w) " " (:opened w)
              "; it delivers " (cap (:project-cap w)) " " (src (str "annual report " (:opened w)) :report-opened) ".")

         (:designer w)
         (str (:designer w) " was the engineer who designed the cooperative's " (k w :plant) " in " (:designed w)
              ". It was renamed " (link (:plant-new w)) " in that engineer's honour in " (:renamed w) " "
              (src "press release" :rename) ".")

         incident-title
         (str "A " (k w :incident) " covered " (k w :area) " from " (:incident-start w) " " (:incident-month w) " to "
              (:incident-end w) " " (:incident-month w) " " (:incident w) ", " (:incident-days w) " days in all. "
              "The alarm came from one sensor at " (link (:plant-new w)) ", which turned out to be faulty: there was no "
              "real problem, and the cooperative now runs a second, independent sensor line "
              (src "incident report" :incident) ".")

         (:trust w)
         (str "The " (:trust w) " is a partner of the " (link (:org w)) "; " (link (:gm3 w)) " signed a partnership with it "
              (k w :trust-work) " " (link (:plant-new w)) " " (src (str "annual report " (:latest w)) :report-latest) ".")

         crisis-title
         (str "During the " (k w :crisis) " of " (:crisis w) " the " (link (:org w)) " " (k w :restriction) " for "
              (words (:crisis-weeks w)) " weeks " (src (str "newsletter " (:newsletter w)) :newsletter) ".")}]
    (into {"/wiki/index.md" (str "# " (:place w) " wiki\n\n"
                                 (str/join "\n" (for [t (keys pages)] (str "- " (link t)))) "\n")}
          (for [[title body] pages]
            [(str "/wiki/" (slug title) ".md") (str "# " title "\n\n" body "\n")]))))

;; ============================================================================
;; The benchmark set
;; ============================================================================

(defn seeds
  "The seeds of a split. `:dev` is public: 1…n. `:test` is held out: derived
   from `key` (a secret the host keeps), so its worlds are not known to anyone
   tuning a prompt on the dev set."
  ([split n] (seeds split n nil))
  ([split n key]
   (case split
     :dev (vec (range 1 (inc n)))
     :test (do (when (str/blank? (str key))
                 (throw (ex-info "The held-out split needs its key" {:type ::no-test-key})))
               (vec (for [i (range n)]
                      (bit-and Long/MAX_VALUE
                               (.getMostSignificantBits
                                (java.util.UUID/nameUUIDFromBytes (.getBytes (str key "|wiki-v3|" i)))))))))))
