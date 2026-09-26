# Licence and attribution

The documents in `docs/` are derived from revisions of the English Wikipedia article
"John Lewis Partnership" (https://en.wikipedia.org/wiki/John_Lewis_Partnership,
history: https://en.wikipedia.org/w/index.php?title=John_Lewis_Partnership&action=history),
written by its contributors, and are licensed under the Creative Commons
Attribution-ShareAlike 4.0 International licence (CC BY-SA 4.0,
https://creativecommons.org/licenses/by-sa/4.0/). So are the gold facts and the
reference wiki derived from them (`gold.edn`, `calibration/`).

| Document | Revision | Timestamp |
| --- | --- | --- |
| john-lewis-2010.md | https://en.wikipedia.org/w/index.php?oldid=366654519 | 2010-06-07 |
| john-lewis-2013.md | https://en.wikipedia.org/w/index.php?oldid=560434604 | 2013-06-18 |
| john-lewis-2016.md | https://en.wikipedia.org/w/index.php?oldid=725901536 | 2016-06-18 |
| john-lewis-2019.md | https://en.wikipedia.org/w/index.php?oldid=902045500 | 2019-06-16 |
| john-lewis-2022.md | https://en.wikipedia.org/w/index.php?oldid=1095473728 | 2022-06-28 |
| john-lewis-2025.md | https://en.wikipedia.org/w/index.php?oldid=1296179465 | 2025-06-18 |

Changes made: only the infobox and the lead section of each revision are kept, and of
the infobox only type, former name, foundation, location, founder, key people,
industry, employees, divisions or subsidiaries, revenue, net income and operating
income; wiki markup, templates (including increase/decrease arrows), footnotes,
references and HTML are removed; links are replaced by their text; line breaks in
lists become commas; repeated whitespace is collapsed; a front matter (type, date)
and a title are added. The text is otherwise unchanged, including its
inconsistencies (e.g. the 2010 revision gives 1920 as the founding year, the 2022
revision gives two employee counts), which is what the benchmark tests.
