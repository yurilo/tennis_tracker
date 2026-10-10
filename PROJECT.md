# Project: Tennis Tracker

## Architecture
The application follows modern Android Clean Architecture with Jetpack Compose (Material3), Coroutines, StateFlow, and a Repository pattern with dual-source resilience (Live ESPN REST API with local mock/fixture fallback).

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Presentation Layer (UI)                         │
│  - MainActivity & UsOpenApp Root Composable                           │
│  - ScoreboardScreen (Live, Upcoming, Finished Tabs/FilterChips)        │
│  - MatchCard & MatchDetail components (Players, Seeds, Set/Game Score) │
│  - WatchOnEspnButton (App intent launch with web URL fallback)         │
│  - PullToRefreshContainer & Offline/Error Banner Indicator             │
│  - ScoreboardViewModel & ScoreboardUiState (StateFlow)                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ observes StateFlow / triggers intents
┌───────────────────────────────────▼────────────────────────────────────┐
│                             Domain Layer                               │
│  - Domain Models: Match, Competitor, ScoreState, SetScore, GameScore,  │
│    MatchStatus, MatchState (LIVE, UPCOMING, FINISHED), RoundInfo,      │
│    DeepLinkConfig                                                      │
│  - Use Cases / Services:                                               │
│    * GetScoreboardUseCase (fetches, parses & filters matches)          │
│    * DeepLinkGenerator (generates ESPN app URIs & fallback web URLs)   │
│    * IntentLauncher (resolves ESPN app package/scheme, falls back)     │
│    * ScoreboardRepository (interface)                                  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ implements interface
┌───────────────────────────────────▼────────────────────────────────────┐
│                              Data Layer                                │
│  - EspnScoreboardApi / RemoteDataSource (OkHttp / HttpURLConnection /  │
│    JSON Parser for ESPN Scoreboard endpoints)                          │
│  - OfflineFixtureSource / LocalDataSource (bundled realistic US Open   │
│    tournament mock fixtures for offline resilience)                    │
│  - ScoreboardRepositoryImpl (online fetch with fallback to fixtures)   │
│  - Json Parsers / DTO Mappers (ESPN raw JSON -> Domain Models)         │
└────────────────────────────────────────────────────────────────────────┘
```

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Gradle & Project Setup | Android Gradle build setup with Kotlin, Jetpack Compose, Material3, Coroutines, dependencies | M1 | ORIGINAL_REQUEST §R4 |
| 2 | Domain Models & Score Structures | Match, Competitor, Seed, SetScores, GameScores, Status, Round domain models | M2 | ORIGINAL_REQUEST §R1 |
| 3 | ESPN API Client & JSON Parser | REST client & robust parser for ESPN tennis scoreboard API responses | M2 | ORIGINAL_REQUEST §R3 |
| 4 | Offline Resilience & Fixtures | Local mock data source & graceful error handling when network fails/offline | M2 | ORIGINAL_REQUEST §R3 |
| 5 | Deep Link URI Generator | Constructs valid ESPN app URI schemes (`sportscenter://`, `espn://`) and web fallback URLs | M3 | ORIGINAL_REQUEST §R2 |
| 6 | Android Intent Launcher & Fallback | Dispatches `ACTION_VIEW` intent to ESPN app with fallback to browser web URL | M3 | ORIGINAL_REQUEST §R2 |
| 7 | Compose Scoreboard UI & Filtering | Jetpack Compose screen rendering matches categorized/filterable by Live, Upcoming, Finished | M4 | ORIGINAL_REQUEST §R1 |
| 8 | Match Card & Watch Button UI | Compose match card rendering players, scores, seeds, round, and "Watch on ESPN" button | M4 | ORIGINAL_REQUEST §R1, R2 |
| 9 | Pull-to-Refresh & Error States | Swipe/button refresh and offline/error banner indicators in UI | M4 | ORIGINAL_REQUEST §R3 |
| 10 | Unit Test Suite (Parsing, Linking, Repos) | Unit tests verifying parsing, domain models, deep link URIs, and offline fallback | M5 | ORIGINAL_REQUEST §R4 |
| 11 | E2E Testing Suite (Tiers 1-4) | Comprehensive opaque-box test suite verifying all acceptance criteria | M5 | ORIGINAL_REQUEST §Acceptance Criteria |
| 12 | Build & Verification Hardening | `./gradlew assembleDebug` (generates APK) and `./gradlew testDebugUnitTest` passing 100% | M6 | ORIGINAL_REQUEST §R4 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Project Scaffolding & Gradle Build | Initialize project structure, `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `AndroidManifest.xml`, Gradle wrapper | none | IN_PROGRESS |
| M2 | Domain Models, ESPN Parser & Offline Repository | Data DTOs, domain models, JSON parsing engine, offline fixture provider, and ScoreboardRepository | M1 | PLANNED |
| M3 | ESPN Deep Linking & Intent Dispatcher | DeepLinkGenerator, IntentLauncher with package check and browser fallback | M2 | PLANNED |
| M4 | Jetpack Compose Scoreboard UI | ScoreboardViewModel, ScoreboardScreen, MatchCard, FilterChips, WatchButton, Pull-to-refresh | M2, M3 | PLANNED |
| M5 | Unit Test Suite & E2E Testing Track | Full unit tests covering models, parsing, deep-linking, repo fallback, and Compose StateFlow | M4 | PLANNED |
| M6 | Adversarial Hardening, Forensic Audit & Build Verification | Full `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` verification + Auditor Check | M5 | PLANNED |

## Interface Contracts

### `ScoreboardRepository`
```kotlin
interface ScoreboardRepository {
    suspend fun getScoreboard(forceRefresh: Boolean = false): Result<List<Match>>
    fun getOfflineScoreboard(): List<Match>
}
```

### `DeepLinkGenerator`
```kotlin
interface DeepLinkGenerator {
    fun generateAppUri(match: Match): String // e.g. sportscenter://x-callback-url/showEvent?eventId=... or espn://watch?id=...
    fun generateWebUrl(match: Match): String // e.g. https://www.espn.com/watch/player/_/id/... or https://www.espn.com/tennis/match/_/gameId/...
}
```

### `IntentLauncher`
```kotlin
interface IntentLauncher {
    fun launchWatchIntent(context: Context, appUri: String, webUrl: String): IntentLaunchResult
}

sealed class IntentLaunchResult {
    data object LaunchedApp : IntentLaunchResult()
    data object LaunchedBrowserFallback : IntentLaunchResult()
    data class Failed(val error: Throwable) : IntentLaunchResult()
}
```

### `ScoreboardUiState`
```kotlin
data class ScoreboardUiState(
    val isLoading: Boolean = false,
    val matches: List<Match> = emptyList(),
    val filterState: MatchFilter = MatchFilter.ALL, // ALL, LIVE, UPCOMING, FINISHED
    val isOffline: Boolean = false,
    val errorMessage: String? = null
)
```

## Code Layout
```
us_open_tracker/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/
        │   │   ├── values/strings.xml, colors.xml, themes.xml
        │   │   └── drawable/
        │   └── java/com/usopen/tracker/
        │       ├── MainActivity.kt
        │       ├── UsOpenApp.kt
        │       ├── domain/
        │       │   ├── model/
        │       │   │   ├── Match.kt
        │       │   │   ├── Competitor.kt
        │       │   │   ├── Score.kt
        │       │   │   ├── MatchState.kt
        │       │   │   └── TournamentRound.kt
        │       │   └── repository/
        │       │       └── ScoreboardRepository.kt
        │       ├── data/
        │       │   ├── api/
        │       │   │   ├── EspnScoreboardApi.kt
        │       │   │   └── EspnJsonParser.kt
        │       │   ├── local/
        │       │   │   └── OfflineFixtures.kt
        │       │   └── repository/
        │       │       └── ScoreboardRepositoryImpl.kt
        │       ├── deeplink/
        │       │   ├── DeepLinkGenerator.kt
        │       │   └── IntentLauncher.kt
        │       └── presentation/
        │           ├── ScoreboardViewModel.kt
        │           ├── ScoreboardUiState.kt
        │           └── ui/
        │               ├── ScoreboardScreen.kt
        │               ├── MatchCard.kt
        │               ├── FilterChips.kt
        │               ├── LiveScoreBadge.kt
        │               └── theme/
        │                   ├── Color.kt
        │                   ├── Theme.kt
        │                   └── Type.kt
        └── test/java/com/usopen/tracker/
            ├── data/
            │   ├── EspnJsonParserTest.kt
            │   └── ScoreboardRepositoryTest.kt
            ├── deeplink/
            │   ├── DeepLinkGeneratorTest.kt
            │   └── IntentLauncherTest.kt
            └── presentation/
                └── ScoreboardViewModelTest.kt
```
