# Project: Tennis Tracker Multi-Tournament Support

## Architecture
- **Layer 1: Domain Models (`com.usopen.tracker.domain.model`)**:
  - Core abstractions: `Tournament`, `TourType` (ATP, WTA, COMBINED), `TournamentCategory` (GRAND_SLAM, MASTERS_1000/WTA_1000, ATP_500/WTA_500, ATP_250/WTA_250, FINALS), `CourtSurface` (HARD, CLAY, GRASS, CARPET), `Match`, `H2H`.
  - Dynamic discovery & resolution: `TournamentResolver`.
- **Layer 2: Remote & Local Data (`com.usopen.tracker.data`)**:
  - `EspnScoreboardApi`: Live ESPN feed parsing, metadata assignment via `TournamentResolver`.
  - `EspnWatchMatcher`: Video stream matching with pre-indexed streams and compiled regex patterns.
  - `YouTubeHighlightResolver`: Video highlight matching with pre-lowercased official channel set.
  - `TennisStatsApi`: Live stats parsing with indexed player token lookup.
  - `ScoreboardRepositoryImpl`: Match fetching, caching, deduplication with `distinctBy`, thread-safe state.
  - `H2HDataProvider`, `MockH2HData`, `MockTournamentData`: Dynamic year/date resolution via `Year.now()`, `LocalDate.now()`, `Instant.now()`.
- **Layer 3: UI & ViewModel (`com.usopen.tracker.ui`)**:
  - `TourTierFilter`: Enum for tour tier chips (`All`, `Grand Slams`, `ATP Tour`, `WTA Tour`, `1000s`, `500s/250s`).
  - `ScoreboardUiState`: Holds `selectedTierFilter`, `currentTournament`, `contextualTournaments`, and matches.
  - `ScoreboardViewModel`: Handles tier filter selection, contextual tournament generation, and multi-stage match filtering.
  - `ScoreboardScreen`: Tiered chip rows (`TourTierChipsRow`, `ContextualTournamentChipsRow`, `FilterChipsRow`).
- **Layer 4: Automated Testing (`com.usopen.tracker.test`)**:
  - Comprehensive unit tests covering dynamic dates, tournament classification, collection pipelines, and regression suite.
  - Opaque-box E2E test suite (Tiers 1-4) and adversarial hardening (Tier 5).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---|---|---|---|
| 1 | Dynamic Year/Date Resolution | Eliminate hardcoded years ("2026", "Tennis Tournament 2026") using `Year.now()`, `LocalDate.now()`. | M1 | R1, Survey 1 |
| 2 | Dynamic Mock Relative Timestamps | Generate relative timestamps (`Instant.now()`, `Duration`) in mock datasets. | M1 | R1, Survey 1 |
| 3 | Date Parser Harmonization | Migrate fragile `Instant.parse` in `EspnWatchApi` to `TimezoneFormatter.parseToInstant`. | M1 | R1, Survey 1 |
| 4 | Tour Types Domain Abstraction | Enum `TourType` (`ATP`, `WTA`, `COMBINED`) with helper properties. | M2 | R2, Survey 2 |
| 5 | Tournament Categories Domain Abstraction | Enum `TournamentCategory` (`GRAND_SLAM`, `MASTERS_1000`/`WTA_1000`, `ATP_500`/`WTA_500`, `ATP_250`/`WTA_250`, `FINALS`). | M2 | R2, Survey 2 |
| 6 | Court Surface Domain Model | Promote `CourtSurface` (`HARD`, `CLAY`, `GRASS`, `CARPET`) to primary domain model. | M2 | R2, Survey 2 |
| 7 | Expanded `Tournament` Model & Registry | Add tour, category, surface, city, country, and official ATP/WTA registry. | M2 | R2, Survey 2 |
| 8 | Enriched `Match` Domain Model | Add tournament, tour, category, surface references and effective getters. | M2 | R2, Survey 2 |
| 9 | Dynamic Tournament Resolver & Discovery | `TournamentResolver` for canonical match + discovery from live ESPN feeds. | M2 | R2, Survey 2 |
| 10 | Live ESPN Feed Metadata Assignment | Update `EspnScoreboardApi` to classify and assign tournament metadata to matches. | M2 | R2, Survey 2 |
| 11 | Tour Tier Filter Chips | UI chips for `All`, `Grand Slams`, `ATP Tour`, `WTA Tour`, `1000s`, `500s/250s`. | M3 | R2, Survey 2 |
| 12 | Contextual Tournament Chips | Contextual chips showing active tournaments matching selected tier. | M3 | R2, Survey 2 |
| 13 | ViewModel Multi-Stage Filtering | Update `ScoreboardViewModel` with tier selection and contextual tournament computation. | M3 | R2, Survey 2 |
| 14 | Scoreboard Screen Compose UI | Restructure `ScoreboardScreen` to render tiered chips and contextual chips rows. | M3 | R2, Survey 2 |
| 15 | Repository Loop & Deduplication Optimization | Replace $O(N \times M)$ deduplication with `distinctBy`, ensure thread safety. | M4 | R3, Survey 3 |
| 16 | Stream Matcher Pre-Indexing & Regex Optimization | Extract top-level regex constants and pre-normalize stream titles in `EspnWatchMatcher`. | M4 | R3, Survey 3 |
| 17 | Video Highlight Resolver Single-Pass Optimization | Convert `OFFICIAL_CHANNELS` to `Set` and single-pass matching in `YouTubeHighlightResolver`. | M4 | R3, Survey 3 |
| 18 | API Parser Loop Refactoring & Null Safety | Refactor `EspnScoreboardApi` loops with Kotlin collection helpers, remove unsafe assertions. | M4 | R3, Survey 3 |
| 19 | Live Stats Search Indexing | Replace $O(N \times M)$ search loop in `TennisStatsApi` with indexed map lookup. | M4 | R3, Survey 3 |
| 20 | Dynamic Year Boundary Unit Tests | Verify dynamic year resolution and rollover without hardcoded years. | M5 | R4, Survey 3 |
| 21 | Tournament Classification Unit Tests | Verify matching and category assignment for ATP, WTA, 1000s, 500s, 250s. | M5 | R4, Survey 3 |
| 22 | Collection Pipeline Efficiency Tests | Verify $O(N+M)$ deduplication and pre-indexing correctness/performance. | M5 | R4, Survey 3 |
| 23 | Regression Verification | Verify all 152 baseline unit tests pass with zero regressions. | M5 | R4, Survey 3 |
| 24 | E2E Test Suite (Tiers 1-4) & Adversarial Hardening (Tier 5) | Pass 100% of E2E test suite and adversarial test coverage verification. | M5 | R4, Dual Track |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|---|---|---|---|
| M1 | Dynamic Dates & Year Resolution | Features 1–3: `H2HDataProvider.kt`, `MockH2HData.kt`, `MockTournamentData.kt`, `TimezoneFormatter.kt`, `EspnWatchApi.kt`, `H2HTest.kt` | none | DONE |
| M2 | ATP & WTA Tournament Abstractions & Live Classification | Features 4–10: `TourType.kt`, `TournamentCategory.kt`, `CourtSurface.kt`, `Tournament.kt`, `Match.kt`, `TournamentResolver.kt`, `EspnScoreboardApi.kt` | none | DONE |
| M3 | Tiered UI Chips & Filtering | Features 11–14: `TourTierFilter.kt`, `ScoreboardUiState.kt`, `ScoreboardViewModel.kt`, `ScoreboardScreen.kt` | M2 | DONE |
| M4 | Loop Refactoring & Performance Optimization | Features 15–19: `ScoreboardRepositoryImpl.kt`, `EspnWatchMatcher.kt`, `YouTubeHighlightResolver.kt`, `EspnScoreboardApi.kt`, `TennisStatsApi.kt` | M1, M2 | DONE |
| M5 | Automated Testing & Verification | Features 20–24: New unit tests, regression verification (152 tests), E2E test suite pass (Tiers 1-4), Tier 5 adversarial hardening | M1, M2, M3, M4 | DONE |

## Interface Contracts

### M1 ↔ Codebase
- `Year.now().value`: Primary integer year provider.
- `LocalDate.now()`: Primary date provider.
- `TimezoneFormatter.parseToInstant(rawTime: String?): Instant?`: Universal date-time parser.

### M2 ↔ M3, M4, M5
- `TourType`: Enum { ATP, WTA, COMBINED } with `isAtp`, `isWta`.
- `TournamentCategory`: Enum { GRAND_SLAM, MASTERS_1000, WTA_1000, ATP_500, WTA_500, ATP_250, WTA_250, FINALS } with `isGrandSlam`, `is1000`, `is500`, `is250`, `is500Or250`, `isFinals`.
- `CourtSurface`: Enum { HARD, CLAY, GRASS, CARPET } with `displayName`, `icon`, `colorHex`.
- `Tournament`:
  - `val tour: TourType = TourType.COMBINED`
  - `val category: TournamentCategory = ...`
  - `val surface: CourtSurface = CourtSurface.HARD`
  - `val city: String? = null`
  - `val country: String? = null`
  - `fun matches(rawTournamentName: String?): Boolean`
- `Match`:
  - `val tournament: Tournament? = null`
  - `val tour: TourType? = null`
  - `val category: TournamentCategory? = null`
  - `val surface: CourtSurface? = null`
  - `val effectiveTour: TourType`
  - `val effectiveCategory: TournamentCategory`
  - `val effectiveSurface: CourtSurface`
- `TournamentResolver`:
  - `fun resolve(rawName: String, groupingName: String = "", eventJson: JSONObject? = null): Tournament`

### M3 ↔ M2 & UI
- `TourTierFilter`: Enum { ALL, GRAND_SLAMS, ATP_TOUR, WTA_TOUR, MASTERS_1000, TIER_500_250 }
  - `fun matches(tournament: Tournament?, match: Match? = null): Boolean`
- `ScoreboardUiState`:
  - `val selectedTierFilter: TourTierFilter`
  - `val contextualTournaments: List<Tournament>`
- `ScoreboardViewModel`:
  - `fun selectTierFilter(tier: TourTierFilter)`
  - `fun selectTournament(tournament: Tournament)`

## Code Layout & Write Ownership
- **Milestone 1 Exclusives**:
  - `app/src/main/java/com/usopen/tracker/data/local/H2HDataProvider.kt`
  - `app/src/main/java/com/usopen/tracker/data/local/MockH2HData.kt`
  - `app/src/main/java/com/usopen/tracker/data/local/MockTournamentData.kt`
  - `app/src/test/java/com/usopen/tracker/data/local/MockTournamentData.kt`
  - `app/src/main/java/com/usopen/tracker/data/remote/EspnWatchApi.kt`
  - `app/src/main/java/com/usopen/tracker/util/TimezoneFormatter.kt`
  - `app/src/test/java/com/usopen/tracker/domain/H2HTest.kt`
- **Milestone 2 Exclusives**:
  - `app/src/main/java/com/usopen/tracker/domain/model/TourType.kt`
  - `app/src/main/java/com/usopen/tracker/domain/model/TournamentCategory.kt`
  - `app/src/main/java/com/usopen/tracker/domain/model/CourtSurface.kt`
  - `app/src/main/java/com/usopen/tracker/domain/model/Tournament.kt`
  - `app/src/main/java/com/usopen/tracker/domain/model/TournamentResolver.kt`
  - `app/src/main/java/com/usopen/tracker/domain/model/Match.kt`
  - `app/src/main/java/com/usopen/tracker/domain/model/H2H.kt` (import CourtSurface)
- **Milestone 3 Exclusives**:
  - `app/src/main/java/com/usopen/tracker/ui/viewmodel/TourTierFilter.kt`
  - `app/src/main/java/com/usopen/tracker/ui/viewmodel/ScoreboardViewModel.kt`
  - `app/src/main/java/com/usopen/tracker/ui/screen/ScoreboardScreen.kt`
- **Milestone 4 Exclusives**:
  - `app/src/main/java/com/usopen/tracker/data/repository/ScoreboardRepositoryImpl.kt`
  - `app/src/main/java/com/usopen/tracker/data/remote/EspnScoreboardApi.kt`
  - `app/src/main/java/com/usopen/tracker/data/remote/EspnWatchMatcher.kt`
  - `app/src/main/java/com/usopen/tracker/data/remote/YouTubeHighlightResolver.kt`
  - `app/src/main/java/com/usopen/tracker/data/remote/TennisStatsApi.kt`
- **Milestone 5 Exclusives**:
  - `app/src/test/java/com/usopen/tracker/domain/DynamicYearResolutionTest.kt`
  - `app/src/test/java/com/usopen/tracker/domain/TournamentClassificationTest.kt`
  - `app/src/test/java/com/usopen/tracker/ui/TieredFilterViewModelTest.kt`
  - `app/src/test/java/com/usopen/tracker/data/CollectionPipelineOptimizationTest.kt`
  - E2E testing test runner & verification scripts
