# Wiki (a catalog workflow, not a transcription)

Part of the [benchmark catalog](../../doc/benchmarks.md); how to run an experiment and read its
report: [doc/running-benchmarks.md](../../doc/running-benchmarks.md).

`dvergr.catalog.wiki`: turn a folder of documents (`/docs`) into a linked wiki
(`/wiki`). Unlike tau2 and BFCL it is our own task, so there is no upstream to be
equivalent to; the checker is argued valid by **calibration** instead.

| | tau2 / BFCL | wiki |
| --- | --- | --- |
| Tasks | transcribed, upstream pinned by sha256 | written here, `resources/dvergr/catalog/wiki-v*/` |
| Validity | equivalence to upstream's checker | calibration: a reference wiki and damaged variants |
| World | a DB (tau2), none (BFCL) | a workspace per attempt (in-memory Geschichte), fixtures seeded |
| Candidate | tools or REPL, one step or a conversation | the LLM agent with file tools, many steps |
| Grade | final state / emitted calls | the files left in the world |

Two paths share task and checker: `experiment!` (discarded worlds, a Scorecard;
`{:version 1|2|3}`) and `catalog_start` over MCP (worlds kept for review and
adoption; without a room on the benchmark set, with one on the room's own `/docs`).

**v1**: six documents, ten gold facts found by substring. Too easy (Luna 1.0 on
both attempts) and gameable: a page pasting every source scores 1.0.

**v2**: twelve dated documents of different authority (a charter, annual
reports, a newsletter, board minutes, press releases incl. a duplicate, an
unofficial blog that is wrong, an agenda with no facts, and an article about an
unrelated organisation). The gold (`wiki-v2/gold.edn`) is derived from the world
the documents were written from: 9 entities, 21 facts (one synthesis: the
duration of the 2018 notice), 5 stale or wrong values, 6 relations, distractor
terms. `score-wiki-v2` measures:

- coverage: a fact counts on a page that states it, cites a document it comes
  from, and is not a copy (8-word shingles, 75% threshold);
- currency: a stale value only near a qualifier ("formerly", "in 2010", "the
  blog claims");
- grounding: every number of two or more digits appears in a source the page
  cites (digits or words), or is a gold synthesis value; each 1% unsupported
  costs 5% of the grounding weight;
- entity pages, relations as links, citations and links valid, an index, no
  distractor facts.

Calibration (`test/dvergr/catalog/wiki_calibration_test.clj`): the hand-written
reference wiki scores 1.0 with every check true; a dump of the sources 0.32, the
wiki without citations 0.50, stale values 0.92, invented numbers 0.90, missing
pages 0.77, the unrelated organisation's facts 0.95, each losing exactly the
checks it damaged. A test pins that every gold fact is stated in the documents it
names.

First live run (Luna, two attempts): 0.937 and 0.894 (before spelled-out numbers were
grounded; both lost a little for writing "42"); both missed the Tallow River
Trust's own page and the derived duration; the weaker also the pipeline length,
the drought and Achebe's tenure. Scores now separate attempts.

**v3: generated worlds** (`dvergr.catalog.wiki-gen`). v2 measures one world, so
n attempts of a model are n draws on the same twelve documents, and its corpus is
public. v3 generates the world from a seed, in v2's shape: an organisation of one
of three kinds (a water, energy or district-heating cooperative) with founders
and a board, three general managers in turn, a plant that is renamed, a supply
project that is delayed, a false-alarm incident, a crisis, a partnership, values
that change (members, capacity) and an unrelated organisation nearby. The same
twelve genres are written from it, the gold is derived from the world, and v2's
checker scores it unchanged. The values a reader must qualify (the blog's
founding year, the old member count and capacity, the planned opening, the old
plant name) are chosen so that no other document states them.

- Environments: one per seed (`:environment/metadata {:seed … :split … :generator …}`,
  part of the content id); the world setup writes the seed's documents into the
  attempt's world, the evaluator derives the seed's gold.
- Splits: `:dev` is public (seeds 1…n); `:test` is held out, its seeds derived from
  a key the host keeps (`DVERGR_WIKI_TEST_KEY`), so a prompt tuned on dev never saw
  them.
- Calibration (`test/dvergr/catalog/wiki_gen_test.clj`), on 30 seeds of all three
  kinds: every gold fact is stated in the documents it names; the generated
  reference wiki scores 1.0 with every check true; a dump, stale values, invented
  numbers, a wiki without citations, the distractor's facts and missing pages each
  lose exactly what they damaged. Worlds are pure functions of the seed and do not
  depend on the JVM's locale.
- Run: `catalog_benchmark {workflow: "wiki/v3", models, environments: 6, split: "dev"}`
  (an MCP connection with `--profile bench`, doc/mcp.md; or `(wiki/experiment! {:version 3 :n 6 …})`); `catalog_start` on the benchmark set
  uses world 1. End to end (`catalog_daemon_test`): a perfect wiki of each world
  scores 1.0 against that world's gold.

**Scale** (`:scale` s, default 1; `catalog_benchmark {… scale: 3}`). A generated
world at scale 1 has v2's twelve documents; above it, s − 1 times more are added from
a random stream of their own, so scale 1 stays exactly the base world (and its
environments keep their content ids): annual reports for years in between, each
stating the member count of its year (one more stale value to qualify); routine
newsletters with names and numbers but no gold fact; articles about other
organisations (more distractors). New names, places and years collide with nothing
the base world states, and no in-between report's year repeats a stale value (a
title "Annual report 2011" must not repeat the planned opening). Scale 3: 22
documents, about 1,000 words, 9 stale values; scale 5: 31–32 documents, about 1,400
words, 12–13 stale values. Calibration runs on scales 1, 3 and 5.

**Prose** (`:prose` p, default 0; `catalog_benchmark {… prose: 3}`). Real documents
are mostly text a wiki does not need; p paragraphs of routine text (maintenance,
opening hours, thanks, boilerplate) are appended to every document, and a table of
routine figures to annual reports, from a random stream of each document's own. The
bank states no name, no year, no "million" and no number above sixty, so it adds no
stale value, no distractor and no fact; prose 0 is exactly the world without it.
Scale 1 with prose 3: 12 documents, about 2,300 words; scale 3 with prose 4: 22
documents, about 5,000 words, the size of a real dump. One consequence, measured by
the calibration: grounding accepts a number found anywhere in a cited source, so
routine figures make it more lenient, never stricter (an invented number that
happens to appear in a cited table counts as supported). Strict grounding, reported
next to it (`grounding-strict`, `loosely-grounded`) but not scored, catches that
case: a number counts strictly when a cited source states it in a sentence (or its
header: front matter and title) that shares a content word (stemmed to five
letters) or another number with the page's paragraph, citations removed. Reference
wikis read 0.97–1.0 on generated worlds and 0.98 on the real corpora; a number
borrowed from a routine table is flagged. Whether it should count is to be decided
on real runs.

**Noise** (`:noise` n, 0–4, default 0; `catalog_benchmark {… noise: 3}`). Documents
as they arrive, not as they were written; each level adds one kind, and the
checker measures each. 1, scan artefacts: the paper genres (charter, annual
reports, board minutes, incident reports) read like text extracted from a PDF,
hard-wrapped at 64 characters, long words hyphenated across line ends ("volunt-" at the end of one line,
"eers" on the next), a footer per page ("Annual report 2004 — page 1 of 2"); about one gold term
per world (a name, "head of operations", "eleven years") is broken, so a wiki that
copies fragments misses the fact or the entity. 2, an erratum: the latest annual
report misprints the member count (two digits swapped) and a later erratum notice
gives the correct one; the misprint is one more stale value (qualified by
"misprint", "erratum", "error", …) and the current count is supported only by the
erratum. 3, short names: the board minutes name the general managers by initial and
surname ("W. Novak"), so "head of operations" belongs to a person whose full name is
stated in another document, and the gold names both. 4, retyped documents: the
verbatim copy of the rename press release misspells the designer's surname (two
neighbouring letters swapped, consistently, the plant's name included), a spelling
a wiki may give only as a misspelling (one more stale value); a wiki that takes the
copy's spelling loses the designer's entity and fact. The latest annual report
gives its figures (members, capacities) in a table rather than in sentences. `unscan` undoes the scan, and
the calibration checks the gold against the unscanned text; the copy check also
compares against the sources with hyphenated words rejoined, so a dump of the
cleaned-up documents is still a copy. Calibration covers every level on ten seeds,
and noise 4 with scale 3 and prose 2.

**Real corpora next to the generated worlds** (split `:real`). `mondragon`: six
dated snapshots (infobox and lead, 2010–2025) of the Wikipedia article on the
Mondragon Corporation, in `resources/dvergr/catalog/wiki-real/mondragon/`
(CC BY-SA 4.0; `LICENSE.md` links every revision and lists what was changed). What
is hard in it is real, not planted: the head of the federation changes four times;
the employee count moves (92,773 in 2008; 80,321 or 83,321 for 2012, both in the
same revision; 74,335 in 2015; 81,507 in 2019; over 70,000 in 2024); revenue and
assets stop at 2015 and 2014 while the text goes on to 2025; revisions contradict
each other (seventh- vs tenth-largest company, 74,117 employees at the end of 2014
or of 2016). The gold (13 facts, 8 stale values) is curated from the documents,
and a hand-written reference wiki calibrates it like v2's
(`test/dvergr/catalog/wiki_real_test.clj`). Run it with
`catalog_benchmark {workflow: "wiki/v3", split: "real", …}`: scores for real corpora
are a separate Scorecard, not averaged into the generated worlds.

`john-lewis`: six snapshots (2010–2025) of the article on the John Lewis Partnership,
in the same shape (`resources/dvergr/catalog/wiki-real/john-lewis/`). The chairman
changes twice (Mayfield → White → Tarry); the headcount moves (68,430 in 2008,
84,700, 88,700, 83,000, then 80,000 for 2022 or around 80,800 for 2020 in the same
revision, 74,000 in 2024); revenue is given for five different years; the oldest
revision gives 1920 as the founding year and every later one 1929; the Ocado supply
"will expire in September 2020" in 2019 and "expired" in 2022, a planned date later
confirmed; the legal form goes from private to partnership to public limited
company. Gold: 14 facts, 12 stale values. The operating income (£0.147 billion) is
in the gold, and the reference writes it as "£147 million": grounding converts
millions and billions ("0.147 billion" is 147 million, "750 million" is 0.75
billion), which digit-level normalisation alone could not.

A finding from `mondragon`, now fixed: currency's weight (15%) was shared by a
world's stale values, so one stale value cost less as a world had more of them
(two former heads stated as current cost 0.04 with 8 stale values, 0.025 with
john-lewis's 12, 0.06 in v2's 5). Each stale value stated as current now costs a
fifth of the weight, 0.03 of the reward, however many there are (up to the whole
weight). v2 and scale-1 worlds have five stale values, so their scores are
unchanged; larger worlds and the real corpora are stricter. The calibration pins
that a damage costs the same at scale 1 and scale 5.

**The anchor for article shape: FreshWiki** (the STORM paper's set, NAACL 2024;
100 recent Wikipedia articles rated B-class or better, CC BY-SA 4.0; measured on its
per-sentence citation data). Median article: 8 sections, 28 sentences, 634 words;
43% of sentences carry a citation (p10–p90 0–71%); 71.5 distinct references
(22–131); 41% of sentences contain a number, and 41% of those are cited; references
are 25% news, 3% government, 71% other (organisations, archives, video, books) over
2,370 domains. Two consequences: a real source set is an order of magnitude larger
than a generated world's twelve documents (hence the scale knobs below), and good
encyclopedic writing cites fewer than half its sentences, so our per-page citation
and grounding checks are stricter than Wikipedia practice (defensible for an
internal wiki, whose reader must be able to check every number).

**Validation, 2026-09-27** (wiki/v3 through `catalog_benchmark` over MCP; both candidates on
subscriptions; the Claude Code CLI in an empty directory of its own, see below):

| Set | `codex-subscription-luna` | `claude-code-haiku` | Difference, 95% range | List price per attempt |
| --- | --- | --- | --- | --- |
| dev, 3 generated worlds | 0.919 (0.77–1.0) | 0.909 (0.81–1.0) | −0.009 paired (−0.07 to +0.05) | Luna $0.026, Haiku $0.244 |
| real, 2 corpora × 3 runs | 0.845 (0.75–0.94) | 0.882 (0.85–0.91) | −0.036 unpaired (−0.13 to +0.06) | Luna $0.022, Haiku $0.147 |

Not separated on either set, and Luna costs a seventh to a ninth at list price: the answer to
"is the cheaper model good enough here?", with its uncertainty stated. No wiki passes every
check. Luna misses the trust's page, the crisis, a manager's tenure and a computed duration,
and on the real corpora states former heads as current; Haiku misses the incident page and a
former chairman. Runs of one model on one corpus spread widely (a single run per world
misleads), and with two corpora a paired interval has one degree of freedom, so the
comparison also reports the unpaired difference over every Attempt
(`reward-difference-unpaired`). With no passes, cost per pass is undefined; the comparison
reports list-price cost per attempt (`notional-cost-ratio`).

An earlier round (same day) ran the Claude Code CLI inside a checkout of dvergr, where it
loaded that project's `CLAUDE.md` and settings into the candidate's context: Haiku scored
0.739 on the same three dev worlds (0.909 clean). The CLI now runs in an empty directory
outside any repository (dvergr #176); a benchmark's candidates must see only the task.

Next for the wiki benchmark:

- **More real corpora.** More Wikipedia revision histories (the same shape as
  `mondragon` and `john-lewis`), and SEC EDGAR filings (sec.gov: "may be copied or
  further distributed … without the SEC's permission") for documents of different
  genre and authority about one company.
- **More noise.** Typos in running text, and (from the real corpora) documents in
  more than one language.
- **Incremental update.** New documents arrive; score the updated wiki and its cost.
- **Claim-level receipts.** A citation counts only if the Run actually read the
  cited document (the file-read record), as `discovery-citations` does for the web.
- **Reporting.** Done: every leaderboard row (`scorecard_list`, `scorecard_detail`)
  carries a 95% interval for its pass rate (Jeffreys: the central 95% of
  Beta(passes + ½, fails + ½), sound at 0 and n passes; 3 of 4 passes is
  [0.28, 0.97], not 0.75) and for its mean reward (a t interval clipped to [0, 1],
  from two attempts on), and the cost per pass next to the cost per attempt;
  `scorecard_detail` adds each candidate's pass rate per check (which checks a model
  fails: currency, grounding, one fact). Computed when read
  (`dvergr.agent.experiment.stats`), so stored Scorecards and their content ids are
  unchanged. `scorecard_detail {…, baseline}` also compares every candidate with a
  baseline (default: the top of the leaderboard): the probability that its pass
  rate is higher, and that it is no worse by more than 5 points (under the two
  Jeffreys posteriors); its reward minus the baseline's, paired by world, with a 95%
  interval; and the cost per pass saved and the cost ratio. The question a pilot
  asks: "is the model at a tenth of the price good enough here?" With 3 of 4 passes
  against 4 of 4, the answer is "probably worse (P higher = 0.16, P no worse by 5
  points = 0.24): run more worlds", not a ranking. `scorecard_detail` and
  `experiment_report` also return the paired comparisons (`paired`: exact McNemar
  on the cases where two candidates differ, Holm-adjusted; see
  running-benchmarks.md). For `paired`, `baseline` names the candidate every
  other one is paired with; without it every pair is compared (beyond five
  candidates, each against the best). The leaderboard-top default above is the
  `comparison`'s only.
- **Cost at list price.** A subscription model (Claude Code, Codex) costs nothing
  per call, which says nothing about what the work is worth. Its registry entry
  names the API model its tokens correspond to (`:list-price-of`: claude-code-haiku
  → claude-haiku-4-5, codex-subscription-luna → gpt-5.6-luna, …), and its spend
  carries `notional-microdollars`, the same tokens at that list price; for any
  other model the notional cost is the cost. Spends show `notional-dollars` next to
  `dollars` (what was paid), and leaderboard rows `notional-microdollars-per-pass`,
  so cost per solved task compares across a subscription, a customer's own key and
  a paid run. Spends recorded before fold exactly as they did (stored Scorecards
  still validate).

**v4: atoms, overviews and quoted claims** (`dvergr.catalog.wiki-v4`). A wiki in the
two-tier shape of Sam's vault-wiki contracts, scored on v3's generated worlds:
`/wiki/atoms/<slug>.md` (front matter `type`, `source`, `entry-point`; one claim per
`##` heading; a `**Sources:**` line), `/wiki/overviews/<slug>.md` (`type: overview`,
linking the atoms it synthesises) and `/wiki/index.md` linking the overviews. A claim
carries its evidence as a verbatim quote, `> "…" [[doc]]` or `[[doc#Section]]` (a source
without sections is cited whole; the quote pins the place). `score-wiki-v4` keeps v2's
currency, grounding, entities, relations, distractors and copies (it runs
`score-wiki-v2` on the wiki with its wikilinks as v2's links), counts a fact only in a
claim that cites one of its sources, and adds:

- quoted claims: every claim that cites a document carries a faithful quote; a wiki
  without quotes earns no quote credit;
- quote fidelity: every quote is in the section it cites after vault-wiki's normaliser
  (currency signs, thousands separators, `5 %`, number words, dates, whitespace, case),
  with scan artefacts of the source undone; each 1% unfaithful costs 5% of the weight;
- claim headers: every quote sits under a heading (one claim, one citable slot);
- citations: every citation names a document and its section exists;
- front matter: an atom has type, a source list naming documents and an entry-point, and
  its Sources line lists the same documents; an overview is `type: overview`;
- tiers: every atom is linked from an overview, every overview from the index, links
  resolve.

Calibration (`test/dvergr/catalog/wiki_v4_test.clj`, 45 worlds over seeds, scales,
prose and noise): the generated reference wiki (v3's reference pages as atoms, one claim
per paragraph, each quoting the sentence of every cited source that supports it best,
avoiding stale values and distractors) scores 1.0 with every check true; a misquoted
number, a quote above the first heading, a citation of a missing section, an atom no
overview links, an atom without front matter, a Sources line out of step, a stale value,
invented numbers, a wiki without citations, a wiki without quotes, the distractor's
facts and missing pages each fail their own check and score lower; a dump scores below
0.5. Not yet a catalog workflow (`catalog_start`/`experiment!` take versions 1–3).

Open: an LLM judge tier for prose faithfulness (vault-wiki's paraphrase rubric:
matches / softens / inverts / inserts), and wiki/v4 as a catalog workflow and
experiment.

