# KB wiki projection and review

`dvergr.catalog.wiki-v4/kb->pages` takes a Datahike DB value and options:

```clojure
(kb->pages db
  {:resolve-source (fn [doc-ref] {:path "/docs/charter.md" :text "…"})
   :attrs {}})
;; => {:pages {"/wiki/atoms/river.md" "…markdown…" …}
;;     :sources {"/docs/charter.md" "…source text…"}}
```

Pass that result directly to `score-structure`, or add `:gold` for
`score-wiki-v4`. This is a projection; it does not transact or write files.
The resolver receives citation targets (`doc`, `doc#Section`, `dh://…`),
including their document part without a section. Return nil for an unresolved
source: its citation remains broken, so scoring detects it. Return the whole
source document, including section headings, and a path under `/docs`.

Pages have `:S.Page/kind` in `#{:wiki/atom :wiki/overview :wiki/index}`,
`:S.Page/title`, a string `:S.Page/entry-point`, and cardinality-many string
`:S.Page/source`. Atoms project with `type: entity`, overviews with
`type: overview`. Paths are `/wiki/atoms/<slug>.md`,
`/wiki/overviews/<slug>.md`, and `/wiki/index.md`. Colliding paths get a UUID
suffix from `:entity/uuid`, or a deterministic UUID derived from the entity id.

Blocks have `:block/parent`, `:block/order` (a fractional-index string, sorted
lexicographically), and tiptap HTML in `:block/content`. A top-level block
starting with `<h2>` starts a claim; its descendants are traversed depth first.
Earlier content is lead text. A block containing only
`<blockquote>"…"</blockquote><p>[[doc#Section]]</p>` becomes one quoted line.
`<strong>Sources:</strong>` becomes `**Sources:**`. The Jsoup walk preserves
headings, quotes, links, lists, code, strong and emphasis; other elements yield
their text. `[[Title]]` becomes the wiki page path; `((uuid))` resolves through
`:entity/uuid` to the page and the containing claim heading. Source references,
including `[[dh://…][Display]]`, become Markdown citations to the resolved path.

`dvergr.kb.wiki/defaults` lists the attribute keys. Override with semantic keys,
e.g. `:attrs {:title :entity/title :page-uuid :entity/id}`. When simmis's
`is.simm.model.morphism/attr-of` is available, page attributes are resolved
through the KB's pulled morphism entities before explicit overrides are applied,
so `:morphism/storage-attr` is respected (including reflected timestamps).
Block morphisms, when present, use the same resolution.

For review, name the KB's **registry `:system/id` UUID**, not its Yggdrasil id:

```clojure
{:review/checks
 [[:diff/only-paths ["wiki/"]]
  [:diff/only-kb-pages
   {:system kb-system-id
    :kinds #{:wiki/atom :wiki/overview :wiki/index}}]]}
```

`review!` makes `only-paths` ignore exactly the KB systems named by the companion
checks. Files still must pass `only-paths`; other systems changing fail.
`only-kb-pages` reads the parent's merge-base DB and the fork-head DB, compares
EAV datoms itself, and checks additions and retractions. Concurrent parent
changes are excluded. Missing systems or merge bases fail review.

Each changed entity must be an allowed wiki page, or a block whose parent chain
ends at one, in both snapshots where the entity exists. This permits creation
and deletion but rejects changing a page to or from a non-wiki kind. Cycles,
dangling parents and chains longer than 1000 edges fail closed. Allowed page
attributes are title, name, kind, source, entry-point, archived, created-at and
updated-at; allowed block attributes are parent, order, content, collapsed,
references and mentions. All other attributes fail, including schema, grants,
widget-code, viz-spec and UUID identity changes. Only Datahike's generated
transaction-entity `:db/txInstant` datoms are excluded from content scope.
Evidence is `{:offenders [{:entity eid :attribute attr} …]}` (at most 20), with
`:other-systems` when another non-file system changes. The review check uses the
shared default/attr-of mapping; its scope cannot be widened through `:attrs`.
