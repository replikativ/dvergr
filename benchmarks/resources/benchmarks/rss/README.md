# RSS reference-resolution repair

`rss.clj` is an unmodified snapshot from replikativ/dvergr-sandbox, tree commit
`25436365b401b928fc8bebf295afe017cd9c9a45`, path `dvergr/intake/rss.clj`.
The file's last change in that tree is `0f32ee061e63b7ac3dafce6cd6c1ca11e1d4dde5`.
The included Apache 2.0 LICENSE is taken from the pinned tree.

Unlike the permutation task, this is a historical bug in the snapshot, not a
seeded mutation. The task repairs document-relative discovery URLs while
preserving unrelated behavior. No production intake source is changed by this
benchmark package.

`url-cases.edn` lists 18 public `[page-url href expected-url]` cases. Additional
checks cover fetch errors, fallback probing, RSS parsing/count limits, and Atom
title/link/category extraction using the ordinary sandbox XML binding.

Setup supplies a small, explicitly labelled `dvergr.intake.core/fetch-text`
dependency stub. Tests replace it with HTML/XML bodies via `with-redefs`.
Verification reconstructs that dependency, ignoring candidate edits to it.
This measures source repair, not production HTTP acquisition.

Known unrelated snapshot limitation: missing XML text can become an empty
string, which prevents Atom summary/date fallback from finding later fields.
The Atom check deliberately does not score those fields. They warrant a
separate intake regression/fix, not a hidden requirement of this URL task.
