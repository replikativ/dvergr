# Spindel composition repair fixture

`permutation.cljc` is an unmodified snapshot from replikativ/spindel, commit
`6b87946a58a7c1bbcbf023f2ff4c657e7a6970a0`, path
`src/org/replikativ/spindel/incremental/permutation.cljc`.

Copyright 2026 Christian Weilbach, org.replikativ. Distributed under the
included Apache 2.0 LICENSE. The benchmark seeds a reversed binary composition
direction at setup time and marks that generated copy as modified.

The snapshot is the correct reference, not a claimed upstream bug. Public
checks enumerate all 576 ordered pairs of permutations on four positions and
also cover nullary, unary, ternary composition and vector arrangement.
These are development checks, not hidden evaluation data.
