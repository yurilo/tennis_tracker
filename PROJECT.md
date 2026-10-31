# Project: Tennis Tracker In-App Bug Reporting & Automated Triage System

## Architecture
- **Layer 1: Diagnostics & Telemetry Models (`com.tennis.tracker.domain.model.bugreport`)**:
  - `BugReportCategory`: Enum for issue categories (`FILTER_TOUR_MISMATCH`, `SCORE_LIVE_DATA`, `WATCH_HIGHLIGHTS`, `HOME_WIDGET`, `UI_LAYOUT`, `OTHER`) with display labels and GitHub label slugs.
  - `BugReport`: User-entered information (category, summary, description, reproduction steps, timestamp).
  - `DiagnosticSnapshot`: Device info (app version, versionCode, form factor `PHONE`/`LAPTOP`, window width bucket), scoreboard state (`TourTierFilter`, `currentTournament`, `MatchTypeFilter`, `MatchFilter`, search query, offline flag, match counts), `AppSettings` snapshot, optional `MatchContextSnapshot`, and recent `DiagnosticBreadcrumb` list.
  - `PiiScrubber`: Zero-PII sanitization engine redacting GitHub tokens, API keys, passwords, bearer tokens, IP addresses, emails, and filesystem paths.
  - `DiagnosticPayloadFormatter`: Markdown summary builder + embedded `<details>` JSON block (`<!-- BUGREPORT_JSON_START -->...<!-- BUGREPORT_JSON_END -->`).
- **Layer 2: Diagnostic Collection & Ring Buffer (`com.tennis.tracker.data.diagnostic`)**:
  - `DiagnosticBreadcrumbBuffer`: Thread-safe, memory-bounded 25-event circular buffer for repository and network events.
  - `DiagnosticCollector`: Gathers environment, scoreboard state, settings, and match metadata.
- **Layer 3: Network Submission & Web Fallback (`com.tennis.tracker.data.bugreport`)**:
  - `BugReportSubmissionService`: Submits bug reports via Cloudflare Worker relay `POST /bug-report` or direct GitHub REST API if custom token provided.
  - `GitHubIssueUrlBuilder`: Generates pre-filled GitHub new issue URLs (`https://github.com/yurilo/tennis_tracker/issues/new?...`) with title, labels, and clipboard copy guidance.
  - `telemetry_dashboard/worker.js`: Cloudflare Worker handling `POST /bug-report` with KV rate limiting, payload validation, and GitHub Issues API forwarding via `env.GITHUB_TOKEN`.
- **Layer 4: In-App UI & Contextual Touchpoints (`com.tennis.tracker.ui.bugreport`)**:
  - Global entry point: `IconButton(Icons.Default.BugReport)` in `ScoreboardScreen` TopAppBar + About dialog link.
  - Contextual entry point: "Report Issue for this Match" button in `MatchCopyBottomSheet`.
  - `BugReportBottomSheet`: Responsive Material 3 bottom sheet (`widthIn(max = 640.dp)`) for Phone and Laptop with category chips, description fields, diagnostic bundle preview toggle, submit action, and submission result feedback (issue number + URL).
- **Layer 5: GitHub Actions Automated Triage Engine (`.github/workflows` & `scripts`)**:
  - `.github/workflows/bug-triage.yml`: GitHub Actions workflow triggered on issues `opened` or `labeled` with `in-app-report` / `bug`.
  - `scripts/triage_bug_report.py`: Standalone Python 3 correlation script parsing embedded JSON, mapping categories and state to Kotlin source files, correlating git commits/issues, and posting 5-section triage comment with candidate patch suggestions.
  - `scripts/test_triage_bug_report.py`: Python unit test suite for triage engine.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Bug Report Domain Models | Define `BugReportCategory`, `BugReport`, `DiagnosticSnapshot`, `Breadcrumb`. | M1 | R1, R2, Survey 2 |
| 2 | PII Redaction Engine (`PiiScrubber`) | Regex-based zero-PII scrubbing for tokens, URLs, emails, IPs, paths. | M1 | R2, Survey 2 |
| 3 | Diagnostic Breadcrumb Buffer | 25-event thread-safe FIFO ring buffer for network & repository events. | M1 | R2, Survey 2 |
| 4 | Diagnostic Snapshot Collector | Collects app version, form factor, scoreboard state, settings, match data. | M1 | R2, Survey 1, Survey 2 |
| 5 | Payload Formatter (Markdown + JSON) | Generates Markdown summary and `<!-- BUGREPORT_JSON_START -->` block. | M1 | R2, Survey 2 |
| 6 | Cloudflare Worker `/bug-report` Route | Endpoint in `worker.js` with CORS, rate-limiting, and GitHub API forwarding. | M2 | R3, Survey 2 |
| 7 | Android Bug Report Submission Client | `BugReportSubmissionService` with OkHttpClient posting to Worker relay. | M2 | R3, Survey 2 |
| 8 | GitHub Issue URL & Web Fallback Builder | `GitHubIssueUrlBuilder` + clipboard copy fallback for offline/unconfigured states. | M2 | R3, Survey 2 |
| 9 | AppSettings Bug Report Configuration | Add optional bug report endpoint & GitHub token overrides to Settings. | M2 | R3, Survey 1, Survey 2 |
| 10 | Scoreboard TopAppBar Bug Report Button | Global entry point icon in `ScoreboardScreen.kt` TopAppBar actions. | M3 | R1, Survey 1 |
| 11 | Contextual Match Action Sheet Entry | "Report Issue for this Match" button in `MatchCopyBottomSheet.kt`. | M3 | R1, Survey 1 |
| 12 | Bug Report Modal Sheet (Compose UI) | `BugReportBottomSheet` with category chips, text fields, and preview toggle. | M3 | R1, Survey 1 |
| 13 | Submission Feedback & Issue Link UI | Displays created issue number, clickable URL, or fallback instructions. | M3 | R1, Survey 1 |
| 14 | GitHub Actions Workflow (`bug-triage.yml`)| Action triggered on `issues: [opened, labeled]` executing triage script. | M4 | R4, Survey 3 |
| 15 | Python Triage & Correlation Script | `triage_bug_report.py` mapping diagnostics to code, commits, and patches. | M4 | R4, Survey 3 |
| 16 | Heuristic Localization & LLM Dual-Mode | Deterministic static analysis + optional LLM patch generation. | M4 | R4, Survey 3 |
| 17 | Triage Script Python Unit Tests | `scripts/test_triage_bug_report.py` verifying parsing, mapping, formatting. | M4 | R4, Survey 3 |
| 18 | Diagnostic & Submission Unit Tests | JUnit tests for collector, scrubbing, URL builder, repository, ViewModel. | M5 | R5, Dual Track |
| 19 | Opaque-Box E2E Test Suite (Tiers 1-4) | Comprehensive test suite verifying all bug report features opaque-box. | M5 | R5, Dual Track |
| 20 | Adversarial Coverage Hardening (Tier 5) | White-box adversarial testing and forensic audit clean verification. | M5 | R5, Dual Track |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Diagnostic Collector, PII Scrubber & Breadcrumb Buffer | Features 1–5: `com.tennis.tracker.domain.model.bugreport.*`, `DiagnosticBreadcrumbBuffer.kt`, `PiiScrubber.kt`, `DiagnosticCollector.kt`, `BugReportPayloadFormatter.kt` | none | DONE |
| M2 | Cloudflare Worker Bug-Report Relay & Submission Client | Features 6–9: `telemetry_dashboard/worker.js`, `BugReportSubmissionService.kt`, `GitHubIssueUrlBuilder.kt`, `AppSettings.kt` | M1 | DONE |
| M3 | Bug Reporting UI & Contextual Touchpoints | Features 10–13: `ScoreboardScreen.kt`, `MatchCopyBottomSheet.kt`, `BugReportBottomSheet.kt`, `BugReportViewModel.kt` | M1, M2 | DONE |
| M4 | GitHub Actions Bug Triage Workflow & Correlation Script | Features 14–17: `.github/workflows/bug-triage.yml`, `scripts/triage_bug_report.py`, `scripts/test_triage_bug_report.py` | M1 | DONE |
| M5 | Automated Testing, E2E Suite & Adversarial Hardening | Features 18–20: Full JUnit tests, Python tests, E2E Test Suite (Tiers 1-4), Tier 5 adversarial tests, clean build | M1, M2, M3, M4 | DONE |

## Interface Contracts

### M1 ↔ M2, M3
- `BugReportCategory`:
  - `FILTER_TOUR_MISMATCH("Filter / Tour Mismatch", "filter-tour")`
  - `SCORE_LIVE_DATA("Score / Live Data", "score-live-data")`
  - `WATCH_HIGHLIGHTS("Watch / Highlights", "watch-highlights")`
  - `HOME_WIDGET("Home Widget", "home-widget")`
  - `UI_LAYOUT("UI / Layout", "ui-layout")`
  - `OTHER("Other", "other")`
- `DiagnosticSnapshot`:
  - `fun toJson(): JSONObject`
  - `fun toFormattedMarkdown(): String`
  - `fun toFullIssueBody(userReport: BugReport): String` (contains `<!-- BUGREPORT_JSON_START -->...<!-- BUGREPORT_JSON_END -->`)
- `PiiScrubber`:
  - `fun scrub(input: String): String`
- `DiagnosticBreadcrumbBuffer`:
  - `fun log(tag: String, message: String, metadata: Map<String, String> = emptyMap())`
  - `fun getRecentBreadcrumbs(): List<DiagnosticBreadcrumb>`

### M2 ↔ M3
- `BugReportSubmissionService`:
  - `suspend fun submitReport(report: BugReport, snapshot: DiagnosticSnapshot, endpoint: String?, customToken: String?): SubmissionResult`
  - `sealed class SubmissionResult { data class Success(val issueNumber: Int, val issueUrl: String) : SubmissionResult(); data class FallbackRequired(val fallbackUrl: String, val fullMarkdownBody: String, val reason: String) : SubmissionResult() }`
- `GitHubIssueUrlBuilder`:
  - `fun buildNewIssueUrl(title: String, body: String, labels: List<String>): String`

### M1, M2 ↔ M4
- JSON Block Markers:
  - Start: `<!-- BUGREPORT_JSON_START -->`
  - End: `<!-- BUGREPORT_JSON_END -->`
- Schema in JSON:
  - `app_version`: `string`
  - `version_code`: `int`
  - `form_factor`: `string` ("PHONE" | "LAPTOP")
  - `screen_width_bucket`: `string` ("COMPACT" | "MEDIUM" | "EXPANDED")
  - `scoreboard_state`: `{ tour_filter, current_tournament, match_type_filter, match_filter, is_offline, total_matches, filtered_matches, live_matches, followed_matches }`
  - `settings_snapshot`: `{ auto_refresh_interval, match_retention, widget_sync_mode, default_tournament, telemetry_enabled }`
  - `match_context`: optional `{ match_id, tournament_name, tournament_id, event_grouping, effective_tour, effective_category, state, court }`
  - `breadcrumbs`: `list of { timestamp, tag, message }`

## Code Layout & Write Ownership
- **Milestone 1 Exclusives**:
  - `app/src/main/java/com/tennis/tracker/domain/model/bugreport/`
  - `app/src/main/java/com/tennis/tracker/data/diagnostic/`
  - `app/src/test/java/com/tennis/tracker/data/diagnostic/`
- **Milestone 2 Exclusives**:
  - `telemetry_dashboard/worker.js`
  - `app/src/main/java/com/tennis/tracker/data/bugreport/`
  - `app/src/test/java/com/tennis/tracker/data/bugreport/`
- **Milestone 3 Exclusives**:
  - `app/src/main/java/com/tennis/tracker/ui/bugreport/`
  - `app/src/main/java/com/tennis/tracker/ui/screen/ScoreboardScreen.kt` (TopAppBar & About dialog touchpoints)
  - `app/src/main/java/com/tennis/tracker/ui/screen/MatchCopyBottomSheet.kt` (Contextual entry point)
- **Milestone 4 Exclusives**:
  - `.github/workflows/bug-triage.yml`
  - `scripts/triage_bug_report.py`
  - `scripts/test_triage_bug_report.py`
- **Milestone 5 Exclusives**:
  - `app/src/test/java/com/tennis/tracker/e2e/`
  - Full regression execution across all modules
