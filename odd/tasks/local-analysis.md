# local-analysis — BPM and Camelot key estimated from the audio itself

Branch: `feat/local-analysis` (from `feat/mix-view` @ `34c64fe`)
Status: implemented; the S7 cache slice is in the working tree, uncommitted.

| Task | Route | Commit |
| --- | --- | --- |
| 1 | `shared/.../mix/analysis/` — chroma → Krumhansl key, onset envelope → tempo | inline, test-first (worker failed, see Delivery) | `c6aeec0` |
| 2 | `desktop/.../analysis/LocalAnalysisProvider.kt` — `BpmKeyProvider` with `isConfigured = true` | inline | `009f09c` |
| 3 | `desktop/.../analysis/AudioSamples.kt` — decode N seconds to mono PCM with the bundled FFmpeg | inline, test-first | `009f09c` |
| 4 | `ui/MixScreen.kt` — prefer the local provider, keep the remote one when a key exists | inline | `009f09c` |
| 5 | README: what is analysed, what it cannot do | inline | `84c3b46` |
| 6 | Verify: `./gradlew :shared:test :desktop:test :desktop:compileKotlin` — 367 tests, 0 failures | — | — |
| 7 | `mix/MixAnalysis.kt` — provider-scoped cache, and a forced re-ask is stored (S7) | inline, test-first | `f7c7bd9` |
| 8 | `ui/MixScreen.kt`, `mix/MixStore.kt` — add resolves through the cache; no write when nothing changed | inline | `f7c7bd9` |
| 9 | Verify: `./gradlew :shared:test :desktop:test :desktop:compileKotlin` — 392 tests, 0 failures | — | — |
| 10 | `mix/analysis/LocalAnalysis.kt` — spectral tilt and a centred profile match (S8) | inline, test-first | `c933cb0` |
| 11 | `mix/analysis/LocalAnalysisTest.kt` — rich-partial loops as the floor the key must clear | inline, test-first | `c933cb0` |
| 12 | Verify: `./gradlew :shared:test :desktop:test :desktop:compileKotlin` — 394 tests, 0 failures | — | — |

## Delivery

Slices are the analysis layer (pure math + tests), the desktop bridge (decode + provider wiring), and
the docs. The DSP slice is the review-heavy one: it decides where tempo and key come from.

The DSP unit was first delegated to `gentle-ai-worker` with the acceptance tests frozen; the worker
failed without writing anything, so the parent implemented it inline against the same tests. Two
findings came out of making them pass, both recorded as comments in the code: the lag grid has to be
fractional (128 BPM sits at 40.375 frames, and the 81-frame point lands closer to the grid), and the
octave preference band had to be measured at 0.90 because an asymmetric onset envelope makes the
double-period point score up to ~5% higher on a true 128 BPM pulse train.

## Specs

- **S1.** "pero no tengo forma de conseguir la api key, es mejor recurrir a alguna librería local?"
- **S2.** Source of the data, chosen from the options offered: *"Todo local: analizador propio
  completo"* — *"BPM y tonalidad desde el audio: onsets, autocorrelación y croma. Cero red, cero
  licencias ajenas, más trabajo."*
- **S3.** *"Coste: ~400 líneas + tests de DSP"* — *"Dependencia: JTransforms (Apache-2.0)"*.
- **S4.** *"Funciona offline y en las tres plataformas, sin cuotas ni servicios que mueran."*
- **S5.** *"Riesgo: precisión de BPM mediocre en material no bailable (más falsos dobles/mitades)."* —
  accepted: the estimate is reported as what it is, and the existing `fold octave` toggle covers
  half/double time. A wrong tempo must never be silently turned into a confident suggestion.
- **S6.** Constraint from the repository, not from the ask: Wren is MIT (`LICENSE`), so the analysis
  code and every new dependency must stay MIT-compatible. Essentia (AGPL-3.0), aubio and
  libKeyFinder (GPL-3.0) are therefore excluded; JTransforms is Apache-2.0 and lives in `shared`,
  which both `desktop` and `android` compile against.

- **S7.** Defect found while reviewing the branch: the local analyser's answer was never written to
  the cache. `refill` — the only path the screen used for an unanalysed track — discarded it, and
  `resolve` was only ever reached for tracks that already carried an answer. The README promised a
  cache that stayed empty, and adding the same track to a second set re-resolved its stream URL and
  re-decoded up to six minutes of audio. Second half of the same defect: entries were keyed by track
  alone, so a miss from the metadata provider hid the track from the analyser for the whole 7-day
  TTL, and a metadata hit shadowed the audio answer forever. The cache is now keyed by source **and**
  track, every answer is stored by whoever obtained it, and a provider that fails outright still
  stores nothing.

- **S8.** The key reading was weak enough to break the feature. Measured on eight dance classics
  against published TuneBat/SongData/SongBPM data: no key exact, two harmonically usable, six clashes
  — and the ranking drops every candidate whose key clashes with the anchor, so most suggestions
  disappeared. Two causes: the chroma summed *power* with no spectral tilt, so the bass of a master
  decided the answer, and the profile match was an uncentred dot product, so the profile with the
  larger total won on any rich chroma. The chroma now uses magnitude with half of the music's
  6 dB/octave tilt restored, and the match is a Pearson correlation. The floor the tests pin: a
  harmonically rich major loop reads exactly right in all twelve roots, and no loop — major or minor,
  any root — may read a slot its own mixing rules call a clash; a relative, a neighbour or `null` are
  all acceptable. Measured after the change: two keys exact, one the relative, one abstains, four
  still clash. The remaining weakness is structural — the fifth harmonic of a sawtooth tonic is a
  major third nobody played — and closing it needs harmonic suppression or a trained model, not
  another constant.

## Log

- **L1.** "pero no tengo forma de conseguir la api key, es mejor recurrir a alguna librería local?"
- **L2.** Answer to the questionnaire: *"Todo local: analizador propio completo"*. Context that led to
  it, established while resolving L1: GetSongBPM needs a key this user cannot obtain; Deezer returns
  a real `bpm` for `api.deezer.com/track/<id>` without one but no musical key, and its `/search`
  omits `bpm`; AcousticBrainz answers 404; MusicBrainz works without a key but carries no tempo or
  key data.
- **L3.** "si, empieza por el slice de caché" — authorisation to implement the cache defect recorded
  as S7.
- **L4.** Decision taken while implementing S7: where a forced re-ask and the track disagree, the
  provider's answer wins and the track only fills the half the provider left out — the rule
  `detect` already applied before the merge moved into the mix layer. Reading the cache without
  naming a provider is no longer allowed: an entry belongs to the source that produced it.
- **L5.** "ve a la clave" — authorisation to attack the key path, recorded as S8.
- **L6.** Evidence gathered before implementing S8, kept here because it is the benchmark of record:
  eight classics measured through `AudioSamples.decode` + `LocalAnalysis.analyse` (scratch harness,
  not committed). Tempo within 1 %: One More Time 122.5 (123), MSBWY 124, I Feel Love 128, Born Slippy
  140, Show Me Love 120. Half time: Good Life 66 against 132. Misses: Lady 86.75 against 126, Strings
  of Life 120 against 128. Keys before the change: 7A, 3A, 8A, 3A, 3A, 6A, 8A, 3A — `3A` on four
  unrelated tonics. Keys after: 6B, null, 8B, 10B, 7B, 5B, 8A, 2B. A whitened chroma was tried and
  rejected: it broke three existing tests and made the rich-loop round trip worse. The same
  measurement also showed a source problem — YouTube refuses media fetches (403, PO token) and
  official SoundCloud uploads are DRM protected, so the corpus came from unofficial uploads and part
  of the error may belong to the files rather than to the algorithm.
