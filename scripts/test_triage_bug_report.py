#!/usr/bin/env python3
"""Unit test suite for scripts/triage_bug_report.py.

Executable directly with:
    python3 scripts/test_triage_bug_report.py
    python3 -m unittest discover -s scripts -p "test_*.py"
"""

import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

# Ensure scripts directory and project root are on sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

import triage_bug_report


# ==============================================================================
# Fixtures
# ==============================================================================

FIXTURE_VALID_FILTER_TOUR = """## User Bug Report

**Category:** `Filter / Tour Mismatch`
**Summary:** WTA tournament selected but scoreboard shows ATP matches

### Description
Selected WTA tour in the filter bar, but matches from Men's Singles are still displayed.

### Steps to Reproduce
1. Open Scoreboard
2. Tap Tour Tier Chip "WTA"
3. Observe scoreboard list

---

### Diagnostic Environment
| Parameter | Value |
|---|---|
| **App Version** | `1.0.25 (26)` |
| **Form Factor** | `PHONE` (`COMPACT`) |
| **Tour Filter** | `WTA` |
| **Tournament** | `US Open 2026` |
| **Match Type Filter** | `ALL` |
| **Match Filter** | `ALL` |
| **Offline Mode** | `NO` |
| **Match Counts** | Total: 42, Filtered: 0, Live: 3, Followed: 1 |
| **Auto-Refresh** | `INTERVAL_60S` |
| **Widget Sync** | `ADAPTIVE` |

<details>
<summary>Diagnostic Bugreport Bundle (Machine-Readable JSON)</summary>

<!-- BUGREPORT_JSON_START -->
{
  "app_version": "1.0.25",
  "version_code": 26,
  "form_factor": "PHONE",
  "screen_width_bucket": "COMPACT",
  "screen_width_dp": 412,
  "screen_height_dp": 915,
  "os_version": "Android 14",
  "api_level": 34,
  "device_model": "Pixel 8",
  "device_manufacturer": "Google",
  "timestamp": "2026-10-31T12:00:00Z",
  "captured_at": 1793448000000,
  "scoreboard_state": {
    "tour_filter": "WTA",
    "current_tournament": "US Open 2026",
    "match_type_filter": "ALL",
    "match_filter": "ALL",
    "is_offline": false,
    "search_active": false,
    "total_matches": 42,
    "filtered_matches": 0,
    "live_matches": 3,
    "followed_matches": 1
  },
  "settings_snapshot": {
    "auto_refresh_interval": "INTERVAL_60S",
    "match_retention": "HOURS_24",
    "widget_sync_mode": "ADAPTIVE",
    "default_tournament": "ALL",
    "telemetry_enabled": true,
    "has_custom_endpoint": false
  },
  "match_context": null,
  "breadcrumbs": [
    {
      "timestamp": 1793447990000,
      "tag": "FILTER",
      "message": "User toggled tour filter to WTA",
      "metadata": {"previous": "ALL", "new": "WTA"}
    }
  ]
}
<!-- BUGREPORT_JSON_END -->
</details>
"""

FIXTURE_VALID_SCORE_LIVE = """## User Bug Report

**Category:** `Score / Live Data`
**Summary:** Arthur Ashe match score frozen at Set 2

### Description
Live score is not updating for Alcaraz vs Sinner.

<details>
<summary>Diagnostic Bugreport Bundle</summary>

<!-- BUGREPORT_JSON_START -->
{
  "app_version": "1.0.25",
  "version_code": 26,
  "form_factor": "PHONE",
  "screen_width_bucket": "COMPACT",
  "scoreboard_state": {
    "tour_filter": "ATP",
    "current_tournament": "US Open 2026",
    "match_type_filter": "SINGLES",
    "match_filter": "LIVE",
    "is_offline": false,
    "total_matches": 16,
    "filtered_matches": 2,
    "live_matches": 2,
    "followed_matches": 1
  },
  "settings_snapshot": {
    "auto_refresh_interval": "INTERVAL_30S",
    "match_retention": "HOURS_24",
    "widget_sync_mode": "ADAPTIVE",
    "default_tournament": "ALL",
    "telemetry_enabled": true
  },
  "match_context": {
    "match_id": "usopen_2026_m1",
    "tournament_name": "US Open 2026",
    "tournament_id": "usopen_2026",
    "event_grouping": "Men's Singles",
    "effective_tour": "ATP",
    "effective_category": "GRAND_SLAM",
    "state": "LIVE",
    "court": "Arthur Ashe Stadium",
    "status_detail": "Set 3, 4-3 (30-30)"
  },
  "breadcrumbs": [
    {
      "timestamp": 1793448000000,
      "tag": "NETWORK",
      "message": "ESPN Scoreboard HTTP 200 OK"
    }
  ]
}
<!-- BUGREPORT_JSON_END -->
</details>
"""

FIXTURE_VALID_WATCH_HIGHLIGHTS = """## User Bug Report

**Category:** `Watch / Highlights`
**Summary:** ESPN highlight link fails to open video player

### Description
Tapping the highlight icon shows an error or opens a 404 page.

<!-- BUGREPORT_JSON_START -->
{
  "app_version": "1.0.25",
  "version_code": 26,
  "form_factor": "PHONE",
  "scoreboard_state": {
    "tour_filter": "ALL",
    "is_offline": false
  },
  "match_context": {
    "match_id": "usopen_2026_m2",
    "tournament_name": "US Open 2026",
    "round_name": "Quarterfinal",
    "state": "COMPLETED"
  },
  "breadcrumbs": [
    {
      "timestamp": 1793448000000,
      "tag": "WATCH",
      "message": "Highlight deep link resolver failed"
    }
  ]
}
<!-- BUGREPORT_JSON_END -->
"""

FIXTURE_VALID_HOME_WIDGET = """## User Bug Report

**Category:** `Home Widget`
**Summary:** Widget shows outdated match score from 4 hours ago

<!-- BUGREPORT_JSON_START -->
{
  "app_version": "1.0.25",
  "version_code": 26,
  "form_factor": "PHONE",
  "scoreboard_state": {
    "followed_matches": 3
  },
  "settings_snapshot": {
    "widget_sync_mode": "ADAPTIVE"
  },
  "breadcrumbs": [
    {
      "timestamp": 1793448000000,
      "tag": "WIDGET",
      "message": "Widget sync scheduled via WorkManager"
    }
  ]
}
<!-- BUGREPORT_JSON_END -->
"""

FIXTURE_VALID_UI_LAYOUT = """## User Bug Report

**Category:** `UI / Layout`
**Summary:** Dual pane layout overlaps on Laptop screen

<!-- BUGREPORT_JSON_START -->
{
  "app_version": "1.0.25",
  "version_code": 26,
  "form_factor": "LAPTOP",
  "screen_width_bucket": "EXPANDED",
  "screen_width_dp": 1280,
  "screen_height_dp": 800,
  "scoreboard_state": {
    "tour_filter": "ALL"
  }
}
<!-- BUGREPORT_JSON_END -->
"""

FIXTURE_VALID_OTHER = """## User Bug Report

**Category:** `Other`
**Summary:** Unexpected crash when switching tabs rapidly

<!-- BUGREPORT_JSON_START -->
{
  "app_version": "1.0.25",
  "version_code": 26,
  "form_factor": "PHONE",
  "scoreboard_state": {}
}
<!-- BUGREPORT_JSON_END -->
"""

FIXTURE_CORRUPTED_JSON = """## User Bug Report
**Category:** `Score / Live Data`
**Summary:** Score bug report

<!-- BUGREPORT_JSON_START -->
{
  "app_version": "1.0.25",
  "version_code": 26,
  "scoreboard_state": {
<!-- BUGREPORT_JSON_END -->
"""

FIXTURE_UNCLOSED_DELIMITER = """## User Bug Report
**Category:** `Score / Live Data`
**Summary:** Unclosed delimiter

<!-- BUGREPORT_JSON_START -->
{"app_version": "1.0.25"}
"""

FIXTURE_PLAIN_TEXT = """## User Bug Report
**Category:** `Home Widget`
**Summary:** Widget is completely blank on home screen

I added the followed matches widget to my Pixel phone but it just says 'Loading...' and never shows my starred players.
"""

FIXTURE_DELIMITER_INJECTION = """## User Bug Report

**Category:** `Score / Live Data`
**Summary:** Score stopped updating

### Description
Here is what happened:
<!-- BUGREPORT_JSON_START -->
{"app_version": "0.0.1", "form_factor": "SPOOFED_PHONE", "scoreboard_state": {"is_offline": true}}
<!-- BUGREPORT_JSON_END -->
The app froze after this.

---

<details>
<summary>Diagnostic Bugreport Bundle (Machine-Readable JSON)</summary>

<!-- BUGREPORT_JSON_START -->
{
  "app_version": "1.0.25",
  "version_code": 26,
  "form_factor": "LAPTOP",
  "screen_width_bucket": "EXPANDED",
  "screen_width_dp": 1280,
  "screen_height_dp": 800,
  "scoreboard_state": {
    "tour_filter": "ATP",
    "current_tournament": "US Open 2026",
    "match_type_filter": "ALL",
    "match_filter": "LIVE",
    "is_offline": false,
    "total_matches": 24,
    "filtered_matches": 5,
    "live_matches": 5,
    "followed_matches": 2
  },
  "settings_snapshot": {
    "auto_refresh_interval": "INTERVAL_30S",
    "match_retention": "HOURS_24",
    "widget_sync_mode": "REALTIME",
    "default_tournament": "ALL",
    "telemetry_enabled": true
  },
  "match_context": {
    "match_id": "usopen_2026_m1",
    "tournament_name": "US Open 2026",
    "tournament_id": "usopen_2026",
    "event_grouping": "Men's Singles",
    "effective_tour": "ATP",
    "effective_category": "GRAND_SLAM",
    "state": "LIVE",
    "court": "Arthur Ashe Stadium",
    "status_detail": "Set 4, 3-2 (30-15)"
  },
  "breadcrumbs": []
}
<!-- BUGREPORT_JSON_END -->
</details>
"""

FIXTURE_BREADCRUMBS_ERRORS = """## User Bug Report

**Category:** `Score / Live Data`
**Summary:** Network sync failure

<!-- BUGREPORT_JSON_START -->
{
  "app_version": "1.0.25",
  "scoreboard_state": {"is_offline": true},
  "breadcrumbs": [
    {"timestamp": 1793448000000, "tag": "NETWORK", "message": "HTTP 502 Bad Gateway from espn.com/scoreboard"},
    {"timestamp": 1793448001000, "tag": "CACHE", "message": "Fallback to stale cache, age 320s"},
    {"timestamp": 1793448002000, "tag": "REPOSITORY", "message": "Match refresh error: SocketTimeoutException"}
  ]
}
<!-- BUGREPORT_JSON_END -->
"""

FIXTURE_NON_DICT_ARRAY = """## User Bug Report
**Category:** `Score / Live Data`
### Description
Testing non-dict payload
<!-- BUGREPORT_JSON_START -->
["spoofed", "array", 123, {"nested": "value"}]
<!-- BUGREPORT_JSON_END -->
"""

FIXTURE_NON_DICT_PRIMITIVE_STRING = """## User Bug Report
**Category:** `Score / Live Data`
<!-- BUGREPORT_JSON_START -->
"Just a primitive string payload"
<!-- BUGREPORT_JSON_END -->
"""

FIXTURE_NON_DICT_PRIMITIVE_INT = """## User Bug Report
**Category:** `Score / Live Data`
<!-- BUGREPORT_JSON_START -->
987654321
<!-- BUGREPORT_JSON_END -->
"""

FIXTURE_EXPLICIT_NULL_FIELDS = """## User Bug Report
**Category:** `Filter / Tour Mismatch`
**Summary:** Null attributes diagnostic test
<!-- BUGREPORT_JSON_START -->
{
  "app_version": null,
  "version_code": null,
  "form_factor": null,
  "screen_width_bucket": null,
  "scoreboard_state": null,
  "settings_snapshot": null,
  "match_context": null,
  "breadcrumbs": null
}
<!-- BUGREPORT_JSON_END -->
"""

FIXTURE_NESTED_NULL_FIELDS = """## User Bug Report
**Category:** `Score / Live Data`
<!-- BUGREPORT_JSON_START -->
{
  "app_version": "1.0.25",
  "scoreboard_state": {
    "tour_filter": null,
    "current_tournament": null,
    "match_type_filter": null,
    "match_filter": null,
    "is_offline": null,
    "search_active": null,
    "search_query": null,
    "total_matches": null,
    "filtered_matches": null,
    "live_matches": null,
    "followed_matches": null
  },
  "settings_snapshot": {
    "auto_refresh_interval": null,
    "widget_sync_mode": null
  },
  "match_context": {
    "match_id": null,
    "tournament_name": null,
    "event_grouping": null,
    "state": null
  },
  "breadcrumbs": [
    null,
    {"tag": null, "message": null, "timestamp": null},
    {"tag": "TEST", "message": "502 Bad Gateway"}
  ]
}
<!-- BUGREPORT_JSON_END -->
"""

FIXTURE_UNICODE_AND_EMOJIS = """## User Bug Report 🎾🏆
**Category:** `Score / Live Data`
**Summary:** Arthur Ashe 🎾 match score frozen at Set 2 💥

### Description
Live score is not updating for Carlos Alcaraz 🇪🇸 vs Jannik Sinner 🇮🇹.
Ref: Novak Đoković 🇷🇸 & Iga Świątek 🇵🇱.
Tested on Laptop 💻 and Phone 📱.

<!-- BUGREPORT_JSON_START -->
{
  "app_version": "1.0.25",
  "version_code": 26,
  "form_factor": "PHONE",
  "screen_width_bucket": "COMPACT",
  "summary": "Match point saved! \\uD83D\\uDD25",
  "scoreboard_state": {
    "tour_filter": "ATP",
    "current_tournament": "US Open 2026 \\uD83C\\uDFC6",
    "match_type_filter": "SINGLES",
    "match_filter": "LIVE",
    "is_offline": false,
    "total_matches": 16,
    "filtered_matches": 2,
    "live_matches": 2,
    "followed_matches": 1
  },
  "settings_snapshot": {
    "auto_refresh_interval": "INTERVAL_30S",
    "widget_sync_mode": "ADAPTIVE"
  },
  "match_context": {
    "match_id": "usopen_2026_m1",
    "tournament_name": "\\uD83C\\uDFBE US Open 2026 \\uD83C\\uDFC6",
    "event_grouping": "Men's Singles 🎾",
    "state": "LIVE"
  },
  "breadcrumbs": [
    {"timestamp": 1793448000000, "tag": "NETWORK 🌐", "message": "HTTP 502 Bad Gateway 💥 on espn.com/scoreboard 🎾"},
    {"timestamp": 1793448001000, "tag": "UI 📱", "message": "User tapped Carlos Alcaraz 🇪🇸 profile"}
  ]
}
<!-- BUGREPORT_JSON_END -->
"""


# ==============================================================================
# Unit Tests
# ==============================================================================

class TestExtractDiagnosticSnapshot(unittest.TestCase):
    """Verifies extraction and boundary parsing of embedded JSON snapshots."""

    def test_extract_valid_snapshot_all_categories(self):
        fixtures = [
            FIXTURE_VALID_FILTER_TOUR,
            FIXTURE_VALID_SCORE_LIVE,
            FIXTURE_VALID_WATCH_HIGHLIGHTS,
            FIXTURE_VALID_HOME_WIDGET,
            FIXTURE_VALID_UI_LAYOUT,
            FIXTURE_VALID_OTHER,
        ]
        for fixture in fixtures:
            snapshot = triage_bug_report.extract_diagnostic_snapshot(fixture)
            self.assertIsNotNone(snapshot, "Snapshot should be parsed successfully")
            self.assertEqual(snapshot.get("app_version"), "1.0.25")
            self.assertIn("scoreboard_state", snapshot)

    def test_extract_canonical_block_with_spoofed_preceding_delimiter(self):
        """Verifies defense against delimiter injection via rfind/lastIndexOf."""
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_DELIMITER_INJECTION)
        self.assertIsNotNone(snapshot)
        # Must extract canonical block (LAPTOP / EXPANDED), NOT the spoofed (0.0.1 / SPOOFED_PHONE)
        self.assertEqual(snapshot.get("form_factor"), "LAPTOP")
        self.assertEqual(snapshot.get("app_version"), "1.0.25")
        self.assertEqual(snapshot.get("screen_width_bucket"), "EXPANDED")

    def test_extract_plain_text_returns_none(self):
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_PLAIN_TEXT)
        self.assertIsNone(snapshot, "Plain text issue should yield None snapshot")

    def test_extract_corrupted_json_returns_none(self):
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_CORRUPTED_JSON)
        self.assertIsNone(snapshot, "Malformed JSON syntax should safely return None")

    def test_extract_unclosed_delimiter_returns_none(self):
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_UNCLOSED_DELIMITER)
        self.assertIsNone(snapshot, "Unclosed delimiter block should safely return None")

    def test_extract_empty_delimiters_returns_none(self):
        empty_block = "<!-- BUGREPORT_JSON_START -->\n   \n<!-- BUGREPORT_JSON_END -->"
        snapshot = triage_bug_report.extract_diagnostic_snapshot(empty_block)
        self.assertIsNone(snapshot, "Whitespace-only payload should return None")

    def test_extract_whitespace_tolerance(self):
        padded_block = '<!-- BUGREPORT_JSON_START -->\n\n\n  {"app_version": "1.0.25"}  \n\n<!-- BUGREPORT_JSON_END -->'
        snapshot = triage_bug_report.extract_diagnostic_snapshot(padded_block)
        self.assertIsNotNone(snapshot)
        self.assertEqual(snapshot.get("app_version"), "1.0.25")


class TestCategoryExtraction(unittest.TestCase):
    """Verifies category normalization from labels or issue content."""

    def test_extract_from_label_slugs(self):
        slug_to_category = {
            "filter-tour": "FILTER_TOUR_MISMATCH",
            "score-live-data": "SCORE_LIVE_DATA",
            "watch-highlights": "WATCH_HIGHLIGHTS",
            "home-widget": "HOME_WIDGET",
            "ui-layout": "UI_LAYOUT",
            "other": "OTHER",
        }
        for slug, expected in slug_to_category.items():
            category = triage_bug_report.extract_category(
                issue_body="Simple bug",
                labels=["bug", "in-app-report", slug],
            )
            self.assertEqual(category, expected)

    def test_extract_from_markdown_body(self):
        category = triage_bug_report.extract_category(
            issue_body="**Category:** `Filter / Tour Mismatch`\n\nDescription here"
        )
        self.assertEqual(category, "FILTER_TOUR_MISMATCH")

    def test_extract_fallback_to_other(self):
        category = triage_bug_report.extract_category(
            issue_body="Unstructured complaint",
            labels=["bug"],
        )
        self.assertEqual(category, "OTHER")


class TestCorrelateSourceFiles(unittest.TestCase):
    """Verifies mapping of category and diagnostic states to Kotlin source files."""

    def test_filter_tour_mismatch_mapping(self):
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_VALID_FILTER_TOUR)
        files = triage_bug_report.correlate_source_files("FILTER_TOUR_MISMATCH", snapshot)
        file_paths = [f["path"] if isinstance(f, dict) else f for f in files]
        self.assertTrue(any("TourTierFilter.kt" in p for p in file_paths))
        self.assertTrue(any("ScoreboardViewModel.kt" in p for p in file_paths))
        self.assertTrue(any("TournamentResolver.kt" in p for p in file_paths))

    def test_score_live_data_mapping(self):
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_VALID_SCORE_LIVE)
        files = triage_bug_report.correlate_source_files("SCORE_LIVE_DATA", snapshot)
        file_paths = [f["path"] if isinstance(f, dict) else f for f in files]
        self.assertTrue(any("ScoreboardRepositoryImpl.kt" in p for p in file_paths))
        self.assertTrue(any("EspnScoreboardApi.kt" in p for p in file_paths))
        self.assertTrue(any("MatchCard.kt" in p for p in file_paths))

    def test_watch_highlights_mapping(self):
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_VALID_WATCH_HIGHLIGHTS)
        files = triage_bug_report.correlate_source_files("WATCH_HIGHLIGHTS", snapshot)
        file_paths = [f["path"] if isinstance(f, dict) else f for f in files]
        self.assertTrue(any("EspnWatchMatcher.kt" in p for p in file_paths))
        self.assertTrue(any("YouTubeHighlightResolver.kt" in p for p in file_paths))

    def test_home_widget_mapping(self):
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_VALID_HOME_WIDGET)
        files = triage_bug_report.correlate_source_files("HOME_WIDGET", snapshot)
        file_paths = [f["path"] if isinstance(f, dict) else f for f in files]
        self.assertTrue(any("FollowedMatchesWidgetProvider.kt" in p for p in file_paths))
        self.assertTrue(any("WidgetAutoSyncManager.kt" in p for p in file_paths))

    def test_ui_layout_laptop_mapping(self):
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_VALID_UI_LAYOUT)
        files = triage_bug_report.correlate_source_files("UI_LAYOUT", snapshot)
        file_paths = [f["path"] if isinstance(f, dict) else f for f in files]
        self.assertTrue(any("AdaptiveLayoutUtils.kt" in p for p in file_paths))
        self.assertTrue(any("ScoreboardScreen.kt" in p for p in file_paths))

    def test_offline_mode_correlation(self):
        snapshot = {"scoreboard_state": {"is_offline": True}}
        files = triage_bug_report.correlate_source_files("SCORE_LIVE_DATA", snapshot)
        file_paths = [f["path"] if isinstance(f, dict) else f for f in files]
        self.assertTrue(any("MatchCacheManager.kt" in p for p in file_paths))

    def test_live_match_context_correlation(self):
        snapshot = {
            "scoreboard_state": {"is_offline": False},
            "match_context": {"state": "LIVE", "match_id": "m1"},
        }
        files = triage_bug_report.correlate_source_files("OTHER", snapshot)
        file_paths = [f["path"] if isinstance(f, dict) else f for f in files]
        self.assertTrue(any("ScoreboardRepositoryImpl.kt" in p for p in file_paths))

    def test_correlated_files_exist_in_project(self):
        """Validates that all correlated files correspond to actual Kotlin files."""
        categories = [
            "FILTER_TOUR_MISMATCH",
            "SCORE_LIVE_DATA",
            "WATCH_HIGHLIGHTS",
            "HOME_WIDGET",
            "UI_LAYOUT",
            "OTHER",
        ]
        all_paths = set()
        for cat in categories:
            files = triage_bug_report.correlate_source_files(cat, None)
            for item in files:
                p = item["path"] if isinstance(item, dict) else item
                all_paths.add(p)

        # Check existence on disk relative to PROJECT_ROOT
        for rel_path in all_paths:
            full_path = os.path.join(PROJECT_ROOT, rel_path)
            self.assertTrue(
                os.path.isfile(full_path),
                f"Correlated file {rel_path} must exist in repository",
            )


class TestAnomalyDetection(unittest.TestCase):
    """Verifies heuristic state anomaly detection rules."""

    def test_anomaly_detection_filter_conflict(self):
        snapshot = {
            "scoreboard_state": {
                "total_matches": 24,
                "filtered_matches": 0,
                "tour_filter": "ATP_TOUR",
                "match_type_filter": "WTA",
                "search_active": False,
            }
        }
        anomalies = triage_bug_report.detect_state_anomalies(snapshot)
        self.assertTrue(any("over-constraint" in a.lower() for a in anomalies))
        self.assertTrue(any("contradictory" in a.lower() for a in anomalies))

    def test_anomaly_detection_widget_disabled(self):
        snapshot = {
            "settings_snapshot": {
                "widget_sync_mode": "DISABLED",
            }
        }
        anomalies = triage_bug_report.detect_state_anomalies(snapshot)
        self.assertTrue(any("disabled" in a.lower() for a in anomalies))


class TestCorrelateGitHistory(unittest.TestCase):
    """Verifies git log correlation with mock handling."""

    @patch("subprocess.run")
    def test_correlate_git_history_success_mock(self, mock_run):
        mock_output = "a1b2c3d|Author Name|2 hours ago|feat: add tour tier filter logic\ne5f6g7h|Author Name|1 day ago|fix: tour tier filter mismatch"
        # rev-parse returns true, log returns commits
        mock_run.side_effect = [
            MagicMock(returncode=0, stdout="true\n", stderr=""),
            MagicMock(returncode=0, stdout=mock_output, stderr=""),
        ]

        commits = triage_bug_report.correlate_git_history(
            ["app/src/main/java/com/tennis/tracker/ui/viewmodel/TourTierFilter.kt"],
            repo_root=PROJECT_ROOT,
        )
        self.assertIsInstance(commits, list)
        self.assertEqual(len(commits), 2)
        self.assertEqual(commits[0]["hash"], "a1b2c3d")
        self.assertIn("tour tier filter", commits[0]["message"])

    @patch("subprocess.run")
    def test_correlate_git_history_subprocess_error(self, mock_run):
        mock_run.side_effect = [
            MagicMock(returncode=0, stdout="true\n", stderr=""),
            subprocess.CalledProcessError(128, ["git", "log"], stderr="fatal: bad revision"),
        ]
        commits = triage_bug_report.correlate_git_history(["dummy.kt"], repo_root=PROJECT_ROOT)
        self.assertEqual(commits, [])

    @patch("subprocess.run", side_effect=FileNotFoundError("git binary not found"))
    def test_correlate_git_history_handles_missing_git(self, _):
        commits = triage_bug_report.correlate_git_history(["dummy.kt"], repo_root=PROJECT_ROOT)
        self.assertEqual(commits, [])


class TestGenerateTriageComment(unittest.TestCase):
    """Verifies triage comment sections, idempotency marker, and Gradle commands."""

    def setUp(self):
        self.snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_VALID_FILTER_TOUR)
        self.suspected_files = [
            {"path": "app/src/main/java/com/tennis/tracker/ui/viewmodel/TourTierFilter.kt", "reason": "Tour filter state"},
            {"path": "app/src/main/java/com/tennis/tracker/ui/viewmodel/ScoreboardViewModel.kt", "reason": "Active filter state"},
        ]
        self.commits = [{"hash": "a1b2c3d", "message": "feat: tour filter"}]

    def test_five_standard_sections_present(self):
        comment = triage_bug_report.generate_triage_comment(
            issue_body=FIXTURE_VALID_FILTER_TOUR,
            snapshot=self.snapshot,
            category="FILTER_TOUR_MISMATCH",
            suspected_files=self.suspected_files,
            related_commits=self.commits,
        )

        # Standard 5 sections
        self.assertIn("Diagnostic Summary", comment)
        self.assertIn("Suspected Files & Symbols", comment)
        self.assertIn("Related Commits / Issues", comment)
        self.assertIn("Root-Cause Analysis", comment)
        self.assertTrue(
            "Proposed Patch" in comment or "Verification Steps" in comment
        )

    def test_bot_marker_present(self):
        comment = triage_bug_report.generate_triage_comment(
            issue_body=FIXTURE_VALID_FILTER_TOUR,
            snapshot=self.snapshot,
            category="FILTER_TOUR_MISMATCH",
            suspected_files=self.suspected_files,
            related_commits=self.commits,
        )
        self.assertIn("<!-- TENNIS_TRACKER_TRIAGE_BOT -->", comment)

    def test_gradle_verification_command_present(self):
        comment = triage_bug_report.generate_triage_comment(
            issue_body=FIXTURE_VALID_FILTER_TOUR,
            snapshot=self.snapshot,
            category="FILTER_TOUR_MISMATCH",
            suspected_files=self.suspected_files,
            related_commits=self.commits,
        )
        self.assertIn("./gradlew testDebugUnitTest", comment)
        self.assertIn("--project-cache-dir", comment)

    def test_targeted_test_filters_for_categories(self):
        """Verifies that each category generates targeted --tests filters matching real test suites."""
        category_expected_filters = {
            "FILTER_TOUR_MISMATCH": ['--tests "*Filter*"', '--tests "*Scoreboard*"'],
            "SCORE_LIVE_DATA": ['--tests "*ScoreboardRepository*"', '--tests "*MatchCache*"'],
            "WATCH_HIGHLIGHTS": ['--tests "*Watch*"', '--tests "*Highlight*"'],
            "HOME_WIDGET": ['--tests "*Widget*"'],
            "UI_LAYOUT": ['--tests "*AdaptiveLayout*"', '--tests "*MatchCard*"'],
        }

        for cat, expected_tokens in category_expected_filters.items():
            comment = triage_bug_report.generate_triage_comment(
                issue_body="Test issue body",
                snapshot=self.snapshot,
                category=cat,
                suspected_files=self.suspected_files,
                related_commits=self.commits,
            )
            for token in expected_tokens:
                self.assertIn(token, comment, f"Category {cat} must include {token} in verification command")

    def test_all_targeted_test_filters_match_actual_test_files(self):
        """Ensures every --tests wildcard pattern matches at least one real test class on disk."""
        import fnmatch
        test_dir = os.path.join(PROJECT_ROOT, "app", "src", "test", "java")
        self.assertTrue(os.path.isdir(test_dir), f"Test dir {test_dir} must exist")

        all_test_files = []
        for root, _, files in os.walk(test_dir):
            for f in files:
                if f.endswith(".kt") or f.endswith(".java"):
                    all_test_files.append(f)

        test_patterns = ["*Filter*", "*Scoreboard*", "*ScoreboardRepository*", "*MatchCache*", "*Watch*", "*Highlight*", "*Widget*", "*AdaptiveLayout*", "*MatchCard*"]
        for pattern in test_patterns:
            matches = [f for f in all_test_files if fnmatch.fnmatch(f, pattern)]
            self.assertTrue(len(matches) > 0, f"Pattern '{pattern}' must match at least 1 test file on disk")

    def test_deterministic_patch_generation_offline(self):
        comment = triage_bug_report.generate_triage_comment(
            issue_body=FIXTURE_VALID_FILTER_TOUR,
            snapshot=self.snapshot,
            category="FILTER_TOUR_MISMATCH",
            suspected_files=self.suspected_files,
            related_commits=self.commits,
        )
        # Verify unified diff format or patch block exists
        self.assertTrue(
            "```diff" in comment or "diff --git" in comment or "```kotlin" in comment,
            "Triage report must provide candidate patch or localized snippet",
        )

    def test_plain_text_triage_output(self):
        comment = triage_bug_report.generate_triage_comment(
            issue_body=FIXTURE_PLAIN_TEXT,
            snapshot=None,
            category="HOME_WIDGET",
            suspected_files=[{"path": "app/src/main/java/com/tennis/tracker/ui/widget/FollowedMatchesWidgetProvider.kt"}],
            related_commits=[],
        )
        self.assertIn("Diagnostic Summary", comment)
        self.assertIn("FollowedMatchesWidgetProvider.kt", comment)
        self.assertIn("<!-- TENNIS_TRACKER_TRIAGE_BOT -->", comment)

    def test_breadcrumb_error_summary_in_comment(self):
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_BREADCRUMBS_ERRORS)
        comment = triage_bug_report.generate_triage_comment(
            issue_body=FIXTURE_BREADCRUMBS_ERRORS,
            snapshot=snapshot,
            category="SCORE_LIVE_DATA",
            suspected_files=[],
            related_commits=[],
        )
        self.assertTrue(
            "502" in comment or "SocketTimeoutException" in comment or "NETWORK" in comment
        )


class TestIdempotencyDetection(unittest.TestCase):
    """Verifies duplicate comment prevention via bot tag detection."""

    def test_existing_comment_detected(self):
        comments = [
            {"body": "Thanks for reporting!"},
            {"body": "## Bug Triage Report\n\n<!-- TENNIS_TRACKER_TRIAGE_BOT -->\nAnalysis complete."},
        ]
        self.assertTrue(triage_bug_report.is_already_triaged(comments))

    def test_no_existing_comment_detected(self):
        comments = [
            {"body": "I have the same problem."},
            {"body": "+1 on Pixel 7"},
        ]
        self.assertFalse(triage_bug_report.is_already_triaged(comments))

    def test_empty_comment_list(self):
        self.assertFalse(triage_bug_report.is_already_triaged([]))


class TestCommandLineInterface(unittest.TestCase):
    """Verifies CLI execution, flags, and dry-run behavior."""

    def test_cli_dry_run_issue_file(self):
        with tempfile.NamedTemporaryFile("w+", delete=False, suffix=".md") as tmp:
            tmp.write(FIXTURE_VALID_FILTER_TOUR)
            tmp_path = tmp.name

        try:
            output_capture = io.StringIO()
            with patch("sys.stdout", output_capture):
                exit_code = triage_bug_report.main([
                    "--issue-file", tmp_path,
                    "--dry-run",
                ])
            self.assertEqual(exit_code, 0)
            output = output_capture.getvalue()
            self.assertIn("Diagnostic Summary", output)
            self.assertIn("<!-- TENNIS_TRACKER_TRIAGE_BOT -->", output)
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)

    def test_cli_issue_body_arg(self):
        output_capture = io.StringIO()
        with patch("sys.stdout", output_capture):
            exit_code = triage_bug_report.main([
                "--issue-body", FIXTURE_VALID_SCORE_LIVE,
                "--dry-run",
            ])
        self.assertEqual(exit_code, 0)
        output = output_capture.getvalue()
        self.assertIn("Score / Live Data", output)
        self.assertIn("ScoreboardRepositoryImpl.kt", output)

    def test_cli_output_file_flag(self):
        with tempfile.NamedTemporaryFile("w+", delete=False, suffix=".md") as in_tmp:
            in_tmp.write(FIXTURE_VALID_HOME_WIDGET)
            in_path = in_tmp.name

        with tempfile.NamedTemporaryFile("w+", delete=False, suffix=".md") as out_tmp:
            out_path = out_tmp.name

        try:
            exit_code = triage_bug_report.main([
                "--issue-file", in_path,
                "--output", out_path,
                "--dry-run",
            ])
            self.assertEqual(exit_code, 0)
            with open(out_path, "r", encoding="utf-8") as f:
                content = f.read()
            self.assertIn("Home Widget", content)
            self.assertIn("FollowedMatchesWidgetProvider.kt", content)
        finally:
            if os.path.exists(in_path):
                os.remove(in_path)
            if os.path.exists(out_path):
                os.remove(out_path)

    def test_cli_missing_required_args_returns_error(self):
        exit_code = triage_bug_report.main([])
        self.assertNotEqual(exit_code, 0)

    @patch("triage_bug_report.fetch_github_comments")
    def test_cli_idempotent_skip_when_already_triaged(self, mock_fetch):
        mock_fetch.return_value = [
            {"body": "<!-- TENNIS_TRACKER_TRIAGE_BOT -->\nPrevious triage"}
        ]
        with tempfile.NamedTemporaryFile("w+", delete=False, suffix=".md") as tmp:
            tmp.write(FIXTURE_VALID_FILTER_TOUR)
            tmp_path = tmp.name

        try:
            output_capture = io.StringIO()
            with patch("sys.stdout", output_capture):
                exit_code = triage_bug_report.main([
                    "--issue-file", tmp_path,
                    "--issue-number", "42",
                    "--token", "ghp_mock_token",
                ])
            self.assertEqual(exit_code, 0)
            self.assertIn("[IDEMPOTENCY]", output_capture.getvalue())
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)


class TestAdversarialPayloadsAndRobustness(unittest.TestCase):
    """Adversarial stress tests locking in fixes for non-dict payloads, null fields, and unicode."""

    def test_extract_non_dict_json_returns_none(self):
        """Verifies that arrays, strings, ints, booleans, and nulls in JSON blocks return None."""
        # JSON Array
        snap_arr = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_NON_DICT_ARRAY)
        self.assertIsNone(snap_arr, "Top-level JSON array must return None")

        # JSON String
        snap_str = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_NON_DICT_PRIMITIVE_STRING)
        self.assertIsNone(snap_str, "Top-level JSON string primitive must return None")

        # JSON Integer
        snap_int = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_NON_DICT_PRIMITIVE_INT)
        self.assertIsNone(snap_int, "Top-level JSON integer primitive must return None")

        # Direct JSON primitives
        self.assertIsNone(triage_bug_report.extract_diagnostic_snapshot("<!-- BUGREPORT_JSON_START -->true<!-- BUGREPORT_JSON_END -->"))
        self.assertIsNone(triage_bug_report.extract_diagnostic_snapshot("<!-- BUGREPORT_JSON_START -->false<!-- BUGREPORT_JSON_END -->"))
        self.assertIsNone(triage_bug_report.extract_diagnostic_snapshot("<!-- BUGREPORT_JSON_START -->null<!-- BUGREPORT_JSON_END -->"))
        self.assertIsNone(triage_bug_report.extract_diagnostic_snapshot("<!-- BUGREPORT_JSON_START -->[{\"app_version\": \"1.0.25\"}]<!-- BUGREPORT_JSON_END -->"))

    def test_triage_pipeline_with_non_dict_payload_issue(self):
        """Verifies end-to-end triage flow with non-dict JSON block falls back to plain-text gracefully."""
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_NON_DICT_ARRAY)
        self.assertIsNone(snapshot)
        category = triage_bug_report.extract_category(FIXTURE_NON_DICT_ARRAY)
        self.assertEqual(category, "SCORE_LIVE_DATA")

        suspected = triage_bug_report.correlate_source_files(category, snapshot)
        self.assertTrue(len(suspected) > 0)

        anomalies = triage_bug_report.detect_state_anomalies(snapshot)
        self.assertTrue(any("not found" in a.lower() for a in anomalies))

        comment = triage_bug_report.generate_triage_comment(
            issue_body=FIXTURE_NON_DICT_ARRAY,
            snapshot=snapshot,
            category=category,
            suspected_files=suspected,
            related_commits=[],
        )
        self.assertIn("Diagnostic Summary", comment)
        self.assertIn("None attached", comment)
        self.assertIn("<!-- TENNIS_TRACKER_TRIAGE_BOT -->", comment)

    def test_pipeline_direct_non_dict_defense(self):
        """Verifies functions gracefully handle non-dict snapshots if invoked directly."""
        for bad_snap in [[1, 2, 3], "string", 12345, True, 3.14]:
            files = triage_bug_report.correlate_source_files("SCORE_LIVE_DATA", bad_snap)
            self.assertIsInstance(files, list)

            anomalies = triage_bug_report.detect_state_anomalies(bad_snap)
            self.assertIsInstance(anomalies, list)

            comment = triage_bug_report.generate_triage_comment(
                issue_body="Test issue",
                snapshot=bad_snap,
                category="SCORE_LIVE_DATA",
                suspected_files=files,
                related_commits=[],
            )
            self.assertIn("Diagnostic Summary", comment)

    def test_triage_handles_explicit_null_diagnostic_fields(self):
        """Verifies that top-level null fields do not raise AttributeError or TypeError."""
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_EXPLICIT_NULL_FIELDS)
        self.assertIsNotNone(snapshot, "Dictionary with null values is valid JSON")
        self.assertIsNone(snapshot.get("scoreboard_state"))
        self.assertIsNone(snapshot.get("form_factor"))
        self.assertIsNone(snapshot.get("breadcrumbs"))

        # correlate_source_files across categories
        files = triage_bug_report.correlate_source_files("FILTER_TOUR_MISMATCH", snapshot)
        self.assertIsInstance(files, list)
        self.assertTrue(len(files) > 0)

        files_ui = triage_bug_report.correlate_source_files("UI_LAYOUT", snapshot)
        self.assertIsInstance(files_ui, list)

        # detect_state_anomalies
        anomalies = triage_bug_report.detect_state_anomalies(snapshot)
        self.assertIsInstance(anomalies, list)

        # generate_triage_comment
        comment = triage_bug_report.generate_triage_comment(
            issue_body=FIXTURE_EXPLICIT_NULL_FIELDS,
            snapshot=snapshot,
            category="FILTER_TOUR_MISMATCH",
            suspected_files=files,
            related_commits=[],
        )
        self.assertIn("Diagnostic Summary", comment)
        self.assertIn("<!-- TENNIS_TRACKER_TRIAGE_BOT -->", comment)
        self.assertIn("Unknown (N/A)", comment)

    def test_triage_handles_nested_null_fields_and_corrupt_breadcrumbs(self):
        """Verifies that nested null attributes in scoreboard_state and breadcrumbs do not crash."""
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_NESTED_NULL_FIELDS)
        self.assertIsNotNone(snapshot)

        # Must not raise TypeError: '>' not supported between instances of 'NoneType' and 'int'
        anomalies = triage_bug_report.detect_state_anomalies(snapshot)
        self.assertIsInstance(anomalies, list)

        # correlate_source_files with corrupted breadcrumbs
        files = triage_bug_report.correlate_source_files("SCORE_LIVE_DATA", snapshot)
        self.assertIsInstance(files, list)
        # Should identify EspnScoreboardApi.kt from the 502 error breadcrumb
        self.assertTrue(any("EspnScoreboardApi.kt" in (f["path"] if isinstance(f, dict) else f) for f in files))

        comment = triage_bug_report.generate_triage_comment(
            issue_body=FIXTURE_NESTED_NULL_FIELDS,
            snapshot=snapshot,
            category="SCORE_LIVE_DATA",
            suspected_files=files,
            related_commits=[],
        )
        self.assertIn("502 Bad Gateway", comment)

    def test_triage_handles_unicode_surrogates_and_multibyte_emojis(self):
        """Verifies UTF-8 multibyte emojis and escaped surrogate pairs in summaries and diagnostics."""
        snapshot = triage_bug_report.extract_diagnostic_snapshot(FIXTURE_UNICODE_AND_EMOJIS)
        self.assertIsNotNone(snapshot)

        # Verifies surrogate pairs \uD83C\uDFBE (🎾) and \uD83C\uDFC6 (🏆) are decoded
        ctx = snapshot.get("match_context", {})
        tourn_name = ctx.get("tournament_name", "")
        self.assertTrue("🎾" in tourn_name or "🏆" in tourn_name or "\U0001f3be" in tourn_name)

        summary = snapshot.get("summary", "")
        self.assertTrue("🔥" in summary or "\U0001f525" in summary)

        # Verify comment generation with emojis and non-ASCII player names
        comment = triage_bug_report.generate_triage_comment(
            issue_body=FIXTURE_UNICODE_AND_EMOJIS,
            snapshot=snapshot,
            category="SCORE_LIVE_DATA",
            suspected_files=[{"path": "app/src/main/java/com/tennis/tracker/data/repository/ScoreboardRepositoryImpl.kt", "reason": "Live score"}],
            related_commits=[],
        )
        self.assertIn("Carlos Alcaraz 🇪🇸", comment)
        self.assertIn("Jannik Sinner 🇮🇹", comment)
        self.assertIn("<!-- TENNIS_TRACKER_TRIAGE_BOT -->", comment)

    def test_cli_handles_adversarial_null_and_unicode_files(self):
        """Verifies CLI execution with adversarial payloads via temp files."""
        # 1. Null fields file
        with tempfile.NamedTemporaryFile("w+", delete=False, suffix=".md", encoding="utf-8") as tmp:
            tmp.write(FIXTURE_EXPLICIT_NULL_FIELDS)
            null_path = tmp.name

        # 2. Unicode and emoji file
        with tempfile.NamedTemporaryFile("w+", delete=False, suffix=".md", encoding="utf-8") as tmp:
            tmp.write(FIXTURE_UNICODE_AND_EMOJIS)
            unicode_path = tmp.name

        try:
            # Null fields CLI dry-run
            out_capture = io.StringIO()
            with patch("sys.stdout", out_capture):
                exit_code = triage_bug_report.main(["--issue-file", null_path, "--dry-run"])
            self.assertEqual(exit_code, 0)
            self.assertIn("Diagnostic Summary", out_capture.getvalue())

            # Unicode CLI dry-run
            out_capture2 = io.StringIO()
            with patch("sys.stdout", out_capture2):
                exit_code2 = triage_bug_report.main(["--issue-file", unicode_path, "--dry-run"])
            self.assertEqual(exit_code2, 0)
            self.assertIn("Carlos Alcaraz 🇪🇸", out_capture2.getvalue())
        finally:
            if os.path.exists(null_path):
                os.remove(null_path)
            if os.path.exists(unicode_path):
                os.remove(unicode_path)


if __name__ == "__main__":
    unittest.main()
