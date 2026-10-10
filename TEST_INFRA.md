# E2E Test Infra: US Open Tennis Tracker

## Test Philosophy
- Requirement-driven, opaque-box testing derived from ORIGINAL_REQUEST.md.
- Verification covers all acceptance criteria: clean build compilation, unit test passes, accurate data parsing, deep linking intent generation and fallback, and Jetpack Compose UI state integrity.

## Feature Inventory & Test Mapping
| # | Feature | Source (Requirement) | Tier 1 (Coverage) | Tier 2 (Boundary/Error) | Tier 3 (Cross-Feature) | Tier 4 (Workload Scenario) |
|---|---------|---------------------|:-----------------:|:-----------------------:|:----------------------:|:--------------------------:|
| F1 | Gradle Build & APK Output | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ | ✓ |
| F2 | Domain Models & Score State | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| F3 | ESPN Scoreboard JSON Parsing | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ | ✓ |
| F4 | Offline Fallback & Error Handling | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ | ✓ |
| F5 | ESPN Deep Link URI Construction | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| F6 | Intent Launching & Browser Fallback | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| F7 | Compose Scoreboard UI & Filters | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |

## Test Architecture
- Test Runner: `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`
- Unit Test Framework: JUnit 4 / Kotlin Test / Coroutines Test (`kotlinx-coroutines-test`) / Robolectric or standard JVM unit tests for parsers, repositories, deep-linking, viewmodels.
- Pass/Fail Semantics: 0 build errors, 0 test failures, exit code 0.

## Acceptance Test Scenarios (Tier 4)
1. **Live Match Scenario**: Live match with ongoing tiebreak (e.g. Alcaraz vs Sinner set 5, 6-6 (7-5)), verifying live status badge, set score display, game score display, and "Watch on ESPN" URI generation.
2. **Upcoming Match Scenario**: Scheduled match with start time, tournament round (e.g. "Men's Singles - Semifinals"), player seedings (#1 vs #4), verifying disabled score fields and future broadcast deep-link.
3. **Completed Match Scenario**: Final match result with winner highlighted, full match set breakdown (e.g. 6-4, 3-6, 7-6, 6-2), verifying replay stream deep-link generation.
4. **Offline Resilience Scenario**: Network disconnected or endpoint returning HTTP 500 / malformed JSON, verifying UI displays fallback tournament fixture without crashing and displays offline indicator.
5. **Deep Link Fallback Scenario**: Device without ESPN installed, verifying intent dispatcher detects inability to resolve custom app scheme and routes user to universal ESPN watch web URL (`https://www.espn.com/watch/...`).
