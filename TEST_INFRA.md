# TEST_INFRA: In-App Bug Reporting & Automated Triage System

## 1. Test Philosophy & Architecture
- **Methodology**: Strict opaque-box, requirement-driven testing derived from `ORIGINAL_REQUEST.md` (R1–R5) and `PROJECT.md` Feature Inventory.
- **Verification Boundaries**: Verifies outward API contracts, JSON payload formatting, zero-PII sanitization, URL fallback generation, Cloudflare Worker relay schema, and automated triage correlation without coupling to internal private methods.
- **Progressive Testability**: Self-contained contract harness (`BugReportContractHarness.kt`) enables immediate compilation and execution under JUnit 4 and Gradle without waiting for milestone implementation merges.
- **Dual-Track Assurance**: Validates Android client telemetry/reporting pipelines and GitHub Actions triage workflows end-to-end.

---

## 2. Feature Inventory Test Matrix

| # | Feature Area | Contract / Specification | Tier 1 (Coverage) | Tier 2 (Boundary) | Tier 3 (Cross-Feature) | Tier 4 (Real-World) |
|:---:|---|---|:---:|:---:|:---:|:---:|
| **F1** | Bug Reporting Payload & Models | `BugReport`, summary, description, repro steps | 5 | 4 | ✓ | ✓ |
| **F2** | Category Taxonomy & GitHub Slugs | `BugReportCategory` (6 categories + slugs) | 5 | 3 | ✓ | ✓ |
| **F3** | Diagnostic Snapshot Collector | Device info, scoreboard state, settings, match | 5 | 3 | ✓ | ✓ |
| **F4** | Zero-PII Redaction Engine | `PiiScrubber` (tokens, keys, IPs, emails, paths) | 6 | 4 | ✓ | ✓ |
| **F5** | JSON Block & Markdown Formatter | `<!-- BUGREPORT_JSON_START -->...<!-- END -->` | 5 | 4 | ✓ | ✓ |
| **F6** | GitHub Issue URL & Fallback Builder | `GitHubIssueUrlBuilder`, query escaping, limits | 5 | 4 | ✓ | ✓ |
| **F7** | Cloudflare Worker `/bug-report` Relay | POST schema, CORS, rate limits, GitHub API | 5 | 4 | ✓ | ✓ |
| **F8** | Automated Triage Script Parsing | JSON extraction, heuristic localization, patch | 5 | 4 | ✓ | ✓ |
| **TOTAL** | **Comprehensive Opaque-Box Suite** | **8 Core Feature Areas** | **41** | **30** | **15** | **6** |

*Note: In addition to the 92 tier tests, integrated suites `BugReportingE2ETest` (8 tests) and `TriageWorkflowE2ETest` (6 tests) bring the comprehensive suite total to **106 tests**.*

---

## 3. Four-Tier Verification Architecture

```
app/src/test/java/com/tennis/tracker/e2e/
├── harness/
│   └── BugReportContractHarness.kt           (Universal Opaque-Box Contracts, Models & Mock Relays)
├── BugReportTier1FeatureCoverageTest.kt      (41 tests: Isolated happy path coverage across F1-F8)
├── BugReportTier2BoundaryCornerCaseTest.kt   (30 tests: Edge cases, whitespace, overflow, rate limits)
├── BugReportTier3CrossFeatureTest.kt         (15 tests: Multi-feature interactions & failover flows)
├── BugReportTier4RealWorldScenariosTest.kt   (6 tests: Full end-to-end lifecycle simulations)
├── BugReportingE2ETest.kt                    (8 tests: In-App reporting & relay/fallback integration)
├── TriageWorkflowE2ETest.kt                  (6 tests: GitHub Actions triage & heuristic patch engine)
└── BugReportingE2ETestSuite.kt               (Master Test Suite & Standalone JVM Runner)
```

### Tier 1: Feature Coverage (41 Tests)
- **F1 (Payload & Model)**: Category attachment, mandatory summary/description validation, optional reproduction steps, timestamp immutability, data class copy semantics.
- **F2 (Category Taxonomy)**: All 6 required categories (`FILTER_TOUR_MISMATCH`, `SCORE_LIVE_DATA`, `WATCH_HIGHLIGHTS`, `HOME_WIDGET`, `UI_LAYOUT`, `OTHER`), display labels, GitHub label slugs, slug reverse resolution.
- **F3 (Diagnostic Collector)**: App version/code, form factor (`PHONE`/`LAPTOP`), screen width bucket (`COMPACT`/`MEDIUM`/`EXPANDED`), scoreboard filter state, settings snapshot, optional target match metadata.
- **F4 (Zero-PII Scrubber)**: Redaction of GitHub tokens (`ghp_...`, `github_pat_...`), API keys/bearer tokens, passwords, IPv4/IPv6 addresses, email addresses, filesystem paths, while preserving tennis scores and player names.
- **F5 (Payload Formatter)**: Markdown summary generation, machine-readable `<details>` JSON block, embedded start/end markers (`<!-- BUGREPORT_JSON_START -->` / `<!-- BUGREPORT_JSON_END -->`), valid JSON serialization.
- **F6 (GitHub URL Fallback)**: Query parameter construction, URI encoding for special characters, label list assembly, browser URL length constraint enforcement, clipboard guidance generation.
- **F7 (Worker Relay Schema)**: `POST /bug-report` payload validation, JSON schema conformance, CORS preflight handling, rate limiting response codes, GitHub issue API mapping.
- **F8 (Triage Script Parsing)**: Marker extraction regex, diagnostic JSON deserialization, category-to-module mapping, git correlation heuristics, 5-section Markdown report synthesis.

### Tier 2: Boundary & Corner Cases (30 Tests)
- **Input Boundaries**: Empty, whitespace-only, and single-character summaries/descriptions.
- **Extreme Lengths**: 10,000-character description payloads, 2,000-character single-line inputs, oversized URL fallback truncation.
- **Screen & Hardware Variations**: Zero/minimal window widths, ultra-wide 4K laptop layouts, bucket transitions.
- **Network & State Boundaries**: Offline device state, relay unreachable/500 internal server error, worker timeout, HTTP 429 rate limit backoff.
- **Parsing Robustness**: Corrupted JSON inside markers, missing end marker, extraneous markdown surrounding markers, unescaped quotes.
- **Buffer Limits**: 25-event circular breadcrumb overflow, concurrent multi-threaded logging, oldest-event eviction under burst load.
- **Adversarial Encoding**: Multiline unicode, emoji strings, Markdown injection attempts in user fields.

### Tier 3: Cross-Feature Interactions (15 Tests)
- **Contextual Match + Active Filters + Scrubbing + Breadcrumbs**: Reporting from a specific Grand Slam match while active filters (TourTier, MatchType, search) are active, ensuring PII scrubbing scrubs both user fields and breadcrumb logs before JSON block insertion.
- **Worker Relay Failover to Web Fallback**: Seamless transition from failed Worker relay (e.g. unconfigured token or network drop) to pre-filled GitHub new issue URL with clipboard payload.
- **Settings Custom Token vs Worker Relay**: Routing priority when user provides custom GitHub token in AppSettings vs relying on Cloudflare Worker relay.
- **Full Serialization Round-Trip**: Serializing client-side snapshot to issue body and deserializing via triage engine with zero data loss.
- **Multi-Credential Redaction**: Simultaneous scrubbing of mixed credentials (GitHub token + bearer token + internal path + email) across summary, description, and breadcrumbs.

### Tier 4: Real-World Scenarios (6 Scenarios)
1. **Global Scoreboard Bug Report**: User encounters live score lag on ATP Tour, opens BugReport sheet via TopAppBar, submits via Worker relay, issue created on GitHub, triage workflow runs and identifies `ScoreboardRepositoryImpl.kt`.
2. **Contextual Match Error Reporting**: User watching Wimbledon Women's Final notices set score mismatch, taps "Report Issue for this Match", captures match context snapshot, scrubs sensitive data, packages report.
3. **Offline Field Reporting with Fallback**: User on airplane mode logs UI glitch; Worker submission fails cleanly, app opens fallback URL with pre-filled title/labels and copies full Markdown bundle to clipboard.
4. **Home Widget Synchronization Failure**: User reports widget stale data under 15m sync interval; captured snapshot includes widget sync mode and breadcrumbs, triage maps issue to `TennisWidgetProvider.kt` and `WidgetSyncWorker.kt`.
5. **Accidental Credential Leak Protection**: User pastes crash stack trace containing internal file paths, developer email, and GitHub auth tokens; verified that zero PII escapes into the generated issue body or URL.
6. **End-to-End Triage Automation**: Complete simulation of GitHub issue opened with diagnostic block, processed by Python triage script, producing a 5-section markdown triage comment with candidate patch suggestions.

---

## 4. Execution Commands

### Android Unit & E2E Tests (Gradle)
```bash
PROJ_ID="$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)" && ./gradlew testDebugUnitTest --project-cache-dir="$HOME/.cache/projects/$PROJ_ID/gradle"
```

### Standalone E2E Test Suite Runner (JVM)
```bash
PROJ_ID="$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)" && ./gradlew testDebugUnitTest --tests "com.tennis.tracker.e2e.BugReportingE2ETestSuite" --project-cache-dir="$HOME/.cache/projects/$PROJ_ID/gradle"
```
