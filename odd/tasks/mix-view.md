# mix-view — a DJ set view with BPM/Camelot affinity

Branch: `feat/mix-view` (from `main` @ `abf22e0`)
Status: implemented and verified; not pushed, no PR opened.

| Task | Route | Commit |
| --- | --- | --- |
| 1 | `mix/CamelotKey.kt` — key model, notation parsing, harmonic relation, tempo halving | inline, test-first | `99bd4a9` |
| 2 | `mix/MixModels.kt`, `mix/MixStore.kt` — set/track model and JSON persistence | inline, test-first | `99bd4a9` |
| 3 | `mix/MixAnalysis.kt` + `mix/GetSongBpm.kt` — cached lookup and the remote provider | inline, test-first | `99bd4a9` |
| 4 | `mix/MixSuggestions.kt` — affinity ranking engine | inline, test-first | `99bd4a9` |
| 5 | `desktop/.../ui/MixScreen.kt` + `MIX` nav entry in `App.kt` | inline | `d671c68` |
| 6 | README: the Mix view, the provider key, where the cache lives | inline | `d671c68` |
| 7 | Verify: `./gradlew :shared:test :desktop:test :desktop:compileKotlin` — 344 tests, 0 failures | — | — |

## Delivery

The work landed in two work-unit commits because the shared layer and the screen are reviewable on
their own: `99bd4a9` (Camelot, set store, lookup, affinity engine + 71 tests) and `d671c68`
(`MixScreen`, the nav entry, README). The first slice is over the ~400 authored-line guideline
because the tests for four new units came with it; the boundary to review is the two commits, not
the branch tip.

Open follow-ups, deliberately out of this slice: manual BPM/key entry for tracks no provider knows
(the SoundCloud rip case S3 called out), a second `BpmKeyProvider` for a local analyser, and
dragging rows instead of the arrow buttons.

## Specs

- **S1.** "también quiero que sea una herramienta para cuando mezclo música, por lo que en desktop
  estaría bien algo para encontrar canciones afines en sets vía BPM o camelot, usando una api de
  TuneBat o proveedores similares, incluso alguna herramienta local"
- **S2.** "es una vista completamente nueva, apartada de la biblioteca"
- **S3.** BPM/key source, chosen from the options offered: *"Híbrido: API remota + caché local, con
  fallback"* — *"Fetch remoto -> caché JSON (AppDirs.config/analysis.json) / Sin match: marca como
  'unknown', no bloquea la UI / Requiere API key en env var / Cubre catálogo comercial; falla en
  edits/rips de SoundCloud"*.
- **S4.** What the affinity runs over, chosen from the options offered: *"Set explícito (nuevo)"* —
  *"Un concepto nuevo de 'set' con tracks añadidos a mano, ordenados, y sugerencias de afines para el
  siguiente hueco"*.
- **S5.** Provider reality, established while resolving S1's uncertainty: TuneBat states on its own
  site that "Is there an API for the finder? Currently no" and points at Songstats (paid partner
  tier); the Tunebat Platform API is a paid JWT service. The free, documented option is GetSongBPM,
  so the remote implementation targets it behind an interface that another provider can slot into.
- **S6.** Desktop only, like the ask: "en desktop estaría bien algo". Android keeps its current
  navigation.

## Log

- **L1.** "quiero añadir una feature desde origin/main. Wren nació como un proyecto desde la
  disconformidad de los reproductores nativos de YT Music y Soundcloud, también quiero que sea una
  herramienta para cuando mezclo música, por lo que en desktop estaría bien algo para encontrar
  canciones afines en sets vía BPM o camelot, usando una api de TuneBat o proveedores similares,
  incluso alguna herramienta local"
- **L2.** "es una vista completamente nueva, apartada de la biblioteca"
- **L3.** Decisions recorded from the questionnaire: hybrid source (S3) and an explicit-set concept (S4).
- **L4.** Provider research while resolving S1: TuneBat has no public API; GetSongBPM is the free,
  documented alternative. Recorded as S5 rather than silently substituting the provider.
