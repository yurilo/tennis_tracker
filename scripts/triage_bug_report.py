#!/usr/bin/env python3
"""Bug Report Triage & Correlation Engine for Tennis Tracker.

Extracts in-app diagnostic bundles (zero-PII JSON), maps report categories and
device/app state to relevant Kotlin source files, correlates git commits and PRs,
performs root-cause analysis, and generates a structured 5-section Markdown triage
report with candidate patches and verification commands.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

JSON_START_DELIMITER = "<!-- BUGREPORT_JSON_START -->"
JSON_END_DELIMITER = "<!-- BUGREPORT_JSON_END -->"
BOT_MARKER_1 = "<!-- TENNIS_TRACKER_TRIAGE_BOT -->"
BOT_MARKER_2 = "<!-- TENNIS_TRACKER_AUTO_TRIAGE -->"

# Canonical category taxonomy mapping slugs and markdown labels to enum keys
SLUG_TO_CATEGORY = {
    "filter-tour": "FILTER_TOUR_MISMATCH",
    "score-live-data": "SCORE_LIVE_DATA",
    "watch-highlights": "WATCH_HIGHLIGHTS",
    "home-widget": "HOME_WIDGET",
    "ui-layout": "UI_LAYOUT",
    "other": "OTHER",
}

CATEGORY_DISPLAY_NAMES = {
    "FILTER_TOUR_MISMATCH": "Filter / Tour Mismatch",
    "SCORE_LIVE_DATA": "Score / Live Data",
    "WATCH_HIGHLIGHTS": "Watch / Highlights",
    "HOME_WIDGET": "Home Widget",
    "UI_LAYOUT": "UI / Layout",
    "OTHER": "Other",
}

# Static code localization catalog
# All paths must exist in repository under app/src/main/java/
CATEGORY_MODULE_MAPPING = {
    "FILTER_TOUR_MISMATCH": {
        "files": [
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/viewmodel/TourTierFilter.kt",
                "symbols": ["TourTierFilter.matches()", "TourTierFilter.fromTourCode()"],
                "confidence": "HIGH",
                "reason": "Evaluates circuit tier classification against tournament and match event groupings",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/viewmodel/ScoreboardViewModel.kt",
                "symbols": ["ScoreboardViewModel.applyFilterAndSearch()", "ScoreboardViewModel.selectTierFilter()"],
                "confidence": "HIGH",
                "reason": "Manages tour tier and match type filter state coordination",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/domain/model/TournamentResolver.kt",
                "symbols": ["TournamentResolver.resolveTournament()", "TournamentResolver.resolveCategory()"],
                "confidence": "MEDIUM",
                "reason": "Normalizes ESPN tournament identifiers and circuit tiers",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/domain/model/TournamentCategory.kt",
                "symbols": ["TournamentCategory.fromTourCode()"],
                "confidence": "MEDIUM",
                "reason": "Categorizes tournaments into Grand Slams, Masters 1000, etc.",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/viewmodel/MatchTypeFilter.kt",
                "symbols": ["MatchTypeFilter.matches()"],
                "confidence": "MEDIUM",
                "reason": "Filters draws by gender and singles/doubles format",
            },
        ],
        "rationale": "Tournament, circuit tier, or draw category filtering discrepancy.",
    },
    "SCORE_LIVE_DATA": {
        "files": [
            {
                "path": "app/src/main/java/com/tennis/tracker/data/repository/ScoreboardRepositoryImpl.kt",
                "symbols": ["ScoreboardRepositoryImpl.getScoreboardInternal()", "ScoreboardRepositoryImpl.getMatchStatistics()"],
                "confidence": "HIGH",
                "reason": "Handles live score network fetching, cache coordination, and deduplication",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/data/remote/EspnScoreboardApi.kt",
                "symbols": ["EspnScoreboardApi.fetchScoreboard()", "EspnScoreboardApi.parseMatch()"],
                "confidence": "HIGH",
                "reason": "Executes HTTP calls to ESPN scoreboard REST endpoints and parses JSON responses",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/screen/MatchCard.kt",
                "symbols": ["MatchCard.MatchCardContent()", "MatchCard.LiveIndicator()"],
                "confidence": "MEDIUM",
                "reason": "Renders real-time scorelines, game scores, and set indicators",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/domain/model/Match.kt",
                "symbols": ["Match.isLive", "Match.formattedScore()"],
                "confidence": "MEDIUM",
                "reason": "Domain entity defining score state and competition attributes",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/data/remote/TennisStatsApi.kt",
                "symbols": ["TennisStatsApi.fetchLiveGameScores()", "TennisStatsApi.fetchStatistics()"],
                "confidence": "MEDIUM",
                "reason": "Fetches game-by-game scores and live match statistical metrics",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/data/local/MatchCacheManager.kt",
                "symbols": ["MatchCacheManager.saveMatches()", "MatchCacheManager.loadMatches()"],
                "confidence": "MEDIUM",
                "reason": "Local disk cache persistence and offline fallback state",
            },
        ],
        "rationale": "Score staleness, game score mismatch, or live polling network failure.",
    },
    "WATCH_HIGHLIGHTS": {
        "files": [
            {
                "path": "app/src/main/java/com/tennis/tracker/data/remote/EspnWatchMatcher.kt",
                "symbols": ["EspnWatchMatcher.findBestWatchStream()"],
                "confidence": "HIGH",
                "reason": "Matches competition metadata to active ESPN Watch live broadcast streams",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/data/remote/EspnWatchApi.kt",
                "symbols": ["EspnWatchApi.fetchWatchStreams()"],
                "confidence": "HIGH",
                "reason": "Queries ESPN broadcast schedules and video playback links",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/data/remote/YouTubeHighlightResolver.kt",
                "symbols": ["YouTubeHighlightResolver.generateDeepLink()"],
                "confidence": "HIGH",
                "reason": "Constructs high-fidelity YouTube match highlight search deep links",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/domain/model/DeepLink.kt",
                "symbols": ["DeepLink.Watch", "DeepLink.Highlights"],
                "confidence": "MEDIUM",
                "reason": "Domain model representing streaming and video playback intent URIs",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/domain/usecase/DeepLinkGenerator.kt",
                "symbols": ["DeepLinkGenerator.generate()"],
                "confidence": "MEDIUM",
                "reason": "Dispatches external streaming and video intent generation",
            },
        ],
        "rationale": "ESPN Watch live broadcast or YouTube highlight video deep link resolution failure.",
    },
    "HOME_WIDGET": {
        "files": [
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/widget/FollowedMatchesWidgetProvider.kt",
                "symbols": ["FollowedMatchesWidgetProvider.onUpdate()", "FollowedMatchesWidgetProvider.onReceive()"],
                "confidence": "HIGH",
                "reason": "BroadcastReceiver handling widget alarms, refresh intents, and layout inflation",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/widget/FollowedMatchesWidgetService.kt",
                "symbols": ["FollowedMatchesWidgetService.RemoteViewsFactory.getViewAt()"],
                "confidence": "HIGH",
                "reason": "RemoteViews collection factory populating desktop widget match rows",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/widget/WidgetAutoSyncManager.kt",
                "symbols": ["WidgetAutoSyncManager.calculateInterval()", "WidgetAutoSyncManager.scheduleNextSync()"],
                "confidence": "HIGH",
                "reason": "Manages adaptive background polling intervals and AlarmManager wakeups",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/data/local/FollowManager.kt",
                "symbols": ["FollowManager.getFollowedMatchIds()", "FollowManager.toggleFollow()"],
                "confidence": "MEDIUM",
                "reason": "Manages starred match persistence that drives the widget display list",
            },
        ],
        "rationale": "Home screen widget update delay, background AlarmManager scheduling, or tap intent failure.",
    },
    "UI_LAYOUT": {
        "files": [
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/util/AdaptiveLayoutUtils.kt",
                "symbols": ["AdaptiveLayoutUtils.computeConfig()", "AdaptiveLayoutUtils.resolveFormFactor()"],
                "confidence": "HIGH",
                "reason": "Computes responsive breakpoints, window size buckets, and column grid counts",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/screen/ScoreboardScreen.kt",
                "symbols": ["ScoreboardScreen.ScoreboardContent()", "ScoreboardScreen.TopAppBar()"],
                "confidence": "HIGH",
                "reason": "Top-level responsive container hosting scoreboard match list grid",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/screen/MatchCard.kt",
                "symbols": ["MatchCard.MatchCardContent()"],
                "confidence": "MEDIUM",
                "reason": "Component rendering match scores and competitor rows across window widths",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/screen/BugReportBottomSheet.kt",
                "symbols": ["BugReportBottomSheet.BugReportModal()"],
                "confidence": "MEDIUM",
                "reason": "Responsive modal bottom sheet dialog bounded by adaptive width constraints",
            },
        ],
        "rationale": "Window width breakpoint, column count calculation, or responsive layout clipping.",
    },
    "OTHER": {
        "files": [
            {
                "path": "app/src/main/java/com/tennis/tracker/MainActivity.kt",
                "symbols": ["MainActivity.onCreate()", "MainActivity.setContent()"],
                "confidence": "MEDIUM",
                "reason": "Application root entry point and top-level Compose view tree setup",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/TennisApp.kt",
                "symbols": ["TennisApp.onCreate()"],
                "confidence": "MEDIUM",
                "reason": "Application class initializing repositories, background sync, and diagnostics",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/viewmodel/ScoreboardViewModel.kt",
                "symbols": ["ScoreboardViewModel.loadMatches()"],
                "confidence": "MEDIUM",
                "reason": "Primary presentation state holder and background refresh coordinator",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/data/repository/SettingsRepositoryImpl.kt",
                "symbols": ["SettingsRepositoryImpl.updateSettings()"],
                "confidence": "MEDIUM",
                "reason": "Preferences storage managing user configuration snapshot",
            },
        ],
        "rationale": "General application error, uncaught lifecycle issue, or unclassified discrepancy.",
    },
}


def extract_diagnostic_snapshot(body: Optional[str]) -> Optional[Dict[str, Any]]:
    """Extract embedded diagnostic JSON block using canonical rfind/lastIndexOf."""
    if not body:
        return None

    start_index = body.rfind(JSON_START_DELIMITER)
    if start_index == -1:
        return None

    content_start = start_index + len(JSON_START_DELIMITER)
    end_index = body.find(JSON_END_DELIMITER, content_start)
    if end_index == -1:
        return None

    raw_json = body[content_start:end_index].strip()
    if not raw_json:
        return None

    # Strip optional markdown code fences if wrapped
    if raw_json.startswith("```"):
        lines = raw_json.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].startswith("```"):
            lines = lines[:-1]
        raw_json = "\n".join(lines).strip()

    try:
        parsed = json.loads(raw_json)
        return parsed if isinstance(parsed, dict) else None
    except Exception:
        return None


# Backward-compatible alias
extract_json_from_body = extract_diagnostic_snapshot

def extract_issue_description(issue_body: Optional[str] = "") -> str:
    """Extract user-provided problem description or summary from issue markdown."""
    if not issue_body or not isinstance(issue_body, str):
        return ""

    # Strip machine-readable diagnostic block from search area
    clean_body = issue_body
    start_idx = clean_body.rfind(JSON_START_DELIMITER)
    if start_idx != -1:
        clean_body = clean_body[:start_idx]

    # 1. Match explicit ### Description section
    desc_match = re.search(
        r"###\s+Description\s*[\r\n]+(.*?)(?=\n###|\n---|<!--|<details>|\Z)",
        clean_body,
        re.DOTALL | re.IGNORECASE,
    )
    if desc_match:
        desc = desc_match.group(1).strip()
        if desc:
            return desc

    # 2. Look for **Summary:** if no description block
    summary_match = re.search(
        r"\*\*Summary:\*\*\s*(.+)",
        clean_body,
        re.IGNORECASE,
    )

    # 3. Handle plain text fallback (strip known markdown headers / tables)
    lines = []
    for line in clean_body.splitlines():
        trimmed = line.strip()
        if not trimmed:
            continue
        if trimmed.startswith("## ") or trimmed.startswith("### "):
            continue
        if trimmed.startswith("**Category:**") or trimmed.startswith("**Summary:**"):
            continue
        if trimmed.startswith("---") or trimmed.startswith("<details>") or trimmed.startswith("</details>"):
            continue
        if trimmed.startswith("|") and trimmed.endswith("|"):
            continue
        lines.append(trimmed)

    if lines:
        return "\n".join(lines).strip()

    if summary_match:
        return summary_match.group(1).strip()

    return ""


# Backward-compatible alias
extract_description_from_body = extract_issue_description


def extract_category(issue_body: Optional[str] = "", labels: Optional[List[Any]] = None) -> str:
    """Normalize issue category from GitHub labels or issue markdown content."""
    # 1. Check labels for known slugs
    if labels and isinstance(labels, list):
        for lbl in labels:
            if not lbl:
                continue
            slug = str(lbl).lower().strip()
            if slug in SLUG_TO_CATEGORY:
                return SLUG_TO_CATEGORY[slug]
            for cat_key, display_name in CATEGORY_DISPLAY_NAMES.items():
                if slug == display_name.lower():
                    return cat_key

    # 2. Check issue body markdown
    if issue_body and isinstance(issue_body, str):
        match = re.search(r"\*\*Category:\*\*\s*[`\"]?([^`\"\n\r]+)[`\"]?", issue_body)
        if match:
            cat_text = match.group(1).strip()
            for cat_key, display in CATEGORY_DISPLAY_NAMES.items():
                if cat_text.lower() == display.lower():
                    return cat_key
            for slug, cat_key in SLUG_TO_CATEGORY.items():
                if cat_text.lower() == slug.lower():
                    return cat_key

        # Check for category names anywhere in bold or title
        for cat_key, display in CATEGORY_DISPLAY_NAMES.items():
            if f"`{display}`" in issue_body or f"**{display}**" in issue_body:
                return cat_key

    return "OTHER"


def correlate_source_files(
    category: str,
    snapshot: Optional[Dict[str, Any]] = None,
    user_text: str = "",
) -> List[Dict[str, Any]]:
    """Map category and diagnostic state to suspected Kotlin source files."""
    norm_cat = str(category or "").upper()
    if norm_cat not in CATEGORY_MODULE_MAPPING:
        norm_cat = "OTHER"

    # Base list for category
    base_info = CATEGORY_MODULE_MAPPING[norm_cat]
    results: List[Dict[str, Any]] = [dict(item) for item in base_info["files"]]
    seen_paths = {item["path"] for item in results}

    # Dynamic heuristics based on snapshot
    if snapshot and isinstance(snapshot, dict):
        raw_sb = snapshot.get("scoreboard_state")
        scoreboard = raw_sb if isinstance(raw_sb, dict) else {}
        match_ctx = snapshot.get("match_context")
        raw_ff = snapshot.get("form_factor")
        form_factor = str(raw_ff).upper() if raw_ff is not None else ""
        raw_bc = snapshot.get("breadcrumbs")
        breadcrumbs = raw_bc if isinstance(raw_bc, list) else []

        # Offline fallback check
        if scoreboard.get("is_offline") is True:
            cache_file = "app/src/main/java/com/tennis/tracker/data/local/MatchCacheManager.kt"
            if cache_file not in seen_paths:
                results.append({
                    "path": cache_file,
                    "symbols": ["MatchCacheManager.loadMatches()", "MatchCacheManager.saveMatches()"],
                    "confidence": "HIGH",
                    "reason": "Offline mode active: local cache retrieval fallback in MatchCacheManager",
                })
                seen_paths.add(cache_file)

        # Contextual match check
        if match_ctx and isinstance(match_ctx, dict):
            match_id = match_ctx.get("match_id") or "unknown"
            repo_file = "app/src/main/java/com/tennis/tracker/data/repository/ScoreboardRepositoryImpl.kt"
            if repo_file not in seen_paths:
                results.insert(0, {
                    "path": repo_file,
                    "symbols": ["ScoreboardRepositoryImpl.getMatchStatistics()", "ScoreboardRepositoryImpl.getScoreboardInternal()"],
                    "confidence": "HIGH",
                    "reason": f"Active contextual match reported (Match ID: {match_id})",
                })
                seen_paths.add(repo_file)

        # Form factor adaptive check for UI_LAYOUT
        if norm_cat == "UI_LAYOUT":
            adaptive_file = "app/src/main/java/com/tennis/tracker/ui/util/AdaptiveLayoutUtils.kt"
            if form_factor == "LAPTOP" and adaptive_file not in seen_paths:
                results.insert(0, {
                    "path": adaptive_file,
                    "symbols": ["AdaptiveLayoutUtils.computeConfig()", "AdaptiveLayoutUtils.resolveFormFactor()"],
                    "confidence": "HIGH",
                    "reason": "Laptop / Expanded screen form factor layout computation",
                })
                seen_paths.add(adaptive_file)

        # Breadcrumbs error inspection
        for bc in breadcrumbs:
            if isinstance(bc, dict):
                msg = str(bc.get("message") or "")
                tag = str(bc.get("tag") or "")
                if any(err in msg for err in ["502", "500", "429", "SocketTimeoutException", "ConnectException", "Exception"]):
                    api_file = "app/src/main/java/com/tennis/tracker/data/remote/EspnScoreboardApi.kt"
                    if api_file not in seen_paths:
                        results.append({
                            "path": api_file,
                            "symbols": ["EspnScoreboardApi.fetchScoreboard()"],
                            "confidence": "HIGH",
                            "reason": f"Network exception logged in breadcrumb tag [{tag}]: {msg[:60]}",
                        })
                        seen_paths.add(api_file)

    # Keyword-based correlation from user problem description (e.g. when category is OTHER)
    lower_text = str(user_text or "").lower()
    if any(kw in lower_text for kw in ["tiebreak", "tie break", "tie-break", "30:30", "live score", "game score", "6:6"]):
        tiebreak_files = [
            {
                "path": "app/src/main/java/com/tennis/tracker/data/remote/EspnScoreboardApi.kt",
                "symbols": ["EspnScoreboardApi.parseCompetitors()", "EspnScoreboardApi.parseScoreboardJson()"],
                "confidence": "HIGH",
                "reason": "Parses live set scores, tiebreak scores, and fallback game point strings (e.g. 40/30 vs TB)",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/screen/MatchCard.kt",
                "symbols": ["MatchCard.CompetitorRow()", "MatchCard.isValidTennisScore()"],
                "confidence": "HIGH",
                "reason": "Renders live game score pill and tiebreak superscripts during 6-6 sets",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/data/repository/ScoreboardRepositoryImpl.kt",
                "symbols": ["ScoreboardRepositoryImpl.getScoreboardInternal()"],
                "confidence": "HIGH",
                "reason": "Enriches live matches with point-by-point scores from TennisStatsApi",
            },
            {
                "path": "app/src/main/java/com/tennis/tracker/ui/widget/FollowedMatchesWidgetService.kt",
                "symbols": ["FollowedMatchesWidgetFactory.getGameScore()", "FollowedMatchesWidgetFactory.buildScoreSpannable()"],
                "confidence": "MEDIUM",
                "reason": "Formats live game and tiebreak scores for the home screen widget",
            },
        ]
        for tf in reversed(tiebreak_files):
            if tf["path"] not in seen_paths:
                results.insert(0, tf)
                seen_paths.add(tf["path"])

    return results


def detect_state_anomalies(snapshot: Optional[Dict[str, Any]]) -> List[str]:
    """Detect anomalies and inconsistencies in diagnostic state."""
    if not snapshot or not isinstance(snapshot, dict):
        return ["Diagnostic snapshot was not found in issue body (web/manual submission fallback)."]

    anomalies: List[str] = []
    raw_sb = snapshot.get("scoreboard_state")
    scoreboard = raw_sb if isinstance(raw_sb, dict) else {}
    raw_settings = snapshot.get("settings_snapshot")
    settings = raw_settings if isinstance(raw_settings, dict) else {}
    raw_bc = snapshot.get("breadcrumbs")
    breadcrumbs = raw_bc if isinstance(raw_bc, list) else []

    raw_total = scoreboard.get("total_matches")
    total = raw_total if isinstance(raw_total, (int, float)) else 0
    raw_filtered = scoreboard.get("filtered_matches")
    filtered = raw_filtered if isinstance(raw_filtered, (int, float)) else 0

    tour_filter = str(scoreboard.get("tour_filter") or "ALL")
    match_type = str(scoreboard.get("match_type_filter") or "ALL")
    is_offline = scoreboard.get("is_offline") is True
    search_active = scoreboard.get("search_active") is True
    search_query = scoreboard.get("search_query")

    # Filter over-constraint
    if total > 0 and filtered == 0:
        anomalies.append(
            f"Filter over-constraint detected: `total_matches` is {total} but `filtered_matches` is 0. "
            f"Active filters: Tour=`{tour_filter}`, MatchType=`{match_type}`, SearchActive={search_active}."
        )

    # Contradictory filters
    if (tour_filter == "ATP_TOUR" or tour_filter == "ATP") and match_type == "WTA":
        anomalies.append(
            "Contradictory filter state: Tour tier is set to ATP, but match type is set to WTA. "
            "No matches can satisfy both filters simultaneously."
        )
    elif (tour_filter == "WTA_TOUR" or tour_filter == "WTA") and match_type == "ATP":
        anomalies.append(
            "Contradictory filter state: Tour tier is set to WTA, but match type is set to ATP. "
            "No matches can satisfy both filters simultaneously."
        )

    # Search query filter note
    if search_active and search_query:
        anomalies.append(f"Active search query `{search_query}` may be filtering out all candidate matches.")

    # Offline mode
    if is_offline:
        anomalies.append("Scoreboard is operating in OFFLINE mode, serving stale cache from local storage.")

    # Widget sync disabled
    widget_mode = settings.get("widget_sync_mode")
    if widget_mode == "DISABLED":
        anomalies.append("Settings indicate `widget_sync_mode` is DISABLED by user preference.")

    # Breadcrumbs errors
    for bc in breadcrumbs:
        if isinstance(bc, dict):
            msg = str(bc.get("message") or "")
            tag = str(bc.get("tag") or "")
            if any(err in msg for err in ["502", "500", "429", "SocketTimeoutException", "ConnectException", "Exception", "Error"]):
                anomalies.append(f"Error logged in diagnostic breadcrumb `[{tag}]`: {msg}")

    return anomalies


def correlate_git_history(
    suspected_files: List[Any],
    repo_root: Optional[str] = None,
    max_commits: int = 8,
) -> List[Dict[str, str]]:
    """Query git log for recent commits touching suspected files."""
    if not suspected_files:
        return []

    if not repo_root:
        repo_root = os.getcwd()

    # Extract file paths
    file_paths: List[str] = []
    for item in suspected_files:
        p = item.get("path") if isinstance(item, dict) else item
        if p and isinstance(p, str):
            file_paths.append(p)

    if not file_paths:
        return []

    # Check git work tree
    try:
        check = subprocess.run(
            ["git", "rev-parse", "--is-inside-work-tree"],
            cwd=repo_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=True,
        )
        if "true" not in check.stdout.strip().lower():
            return []
    except Exception:
        return []

    results: List[Dict[str, str]] = []
    seen_shas = set()

    cmd = ["git", "log", f"-n{max_commits}", "--pretty=format:%h|%an|%cr|%s", "--"] + file_paths
    try:
        proc = subprocess.run(
            cmd,
            cwd=repo_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=True,
        )
        for line in proc.stdout.strip().splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split("|", 3)
            if len(parts) >= 4 and parts[0] not in seen_shas:
                seen_shas.add(parts[0])
                results.append({
                    "hash": parts[0],
                    "sha": parts[0],
                    "author": parts[1],
                    "time": parts[2],
                    "message": parts[3],
                })
    except Exception:
        pass

    return results


def call_llm_analyzer(
    issue_info: Dict[str, Any],
    static_context: Dict[str, Any],
    provider: str = "auto",
) -> Optional[Dict[str, str]]:
    """Optional LLM analysis using Google Gemini or OpenAI with 5s timeout and fallback."""
    gemini_key = os.environ.get("GEMINI_API_KEY")
    openai_key = os.environ.get("OPENAI_API_KEY")

    if provider == "none" or (not gemini_key and not openai_key):
        return None

    prompt = (
        f"You are an expert Android/Kotlin engineer triaging a bug report for Tennis Tracker.\n"
        f"Category: {issue_info.get('category')}\n"
        f"Title: {issue_info.get('title')}\n"
        f"Description: {issue_info.get('description')}\n"
        f"Diagnostic Snapshot: {json.dumps(issue_info.get('snapshot'), indent=2)}\n"
        f"Suspected Files: {[f.get('path') for f in static_context.get('suspected_files', [])]}\n"
        f"State Anomalies: {static_context.get('anomalies', [])}\n"
        f"Provide a root-cause explanation and a candidate patch diff (```diff ... ```)."
    )

    # 1. Try Gemini
    if gemini_key and provider in ("auto", "gemini"):
        url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key={gemini_key}"
        payload = json.dumps({
            "contents": [{"parts": [{"text": prompt}]}]
        }).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                text = data["candidates"][0]["content"]["parts"][0]["text"]
                return {"analysis": text}
        except Exception as e:
            sys.stderr.write(f"Warning: Gemini API call failed ({e}), falling back to deterministic triage.\n")

    # 2. Try OpenAI
    if openai_key and provider in ("auto", "openai"):
        url = "https://api.openai.com/v1/chat/completions"
        payload = json.dumps({
            "model": "gpt-4o-mini",
            "messages": [
                {"role": "system", "content": "You are a senior Android Kotlin engineer."},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.2,
        }).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=payload,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {openai_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                text = data["choices"][0]["message"]["content"]
                return {"analysis": text}
        except Exception as e:
            sys.stderr.write(f"Warning: OpenAI API call failed ({e}), falling back to deterministic triage.\n")

    return None


def get_deterministic_candidate_patch(category: str, user_text: str = "") -> str:
    """Provide parameterized high-fidelity unified diff patch for category."""
    norm_cat = category.upper()
    lower_text = str(user_text or "").lower()
    if any(kw in lower_text for kw in ["tiebreak", "tie break", "tie-break", "30:30", "6:6"]):
        return """```diff
--- a/app/src/main/java/com/tennis/tracker/data/remote/EspnScoreboardApi.kt
+++ b/app/src/main/java/com/tennis/tracker/data/remote/EspnScoreboardApi.kt
@@ -435,6 +435,28 @@ class EspnScoreboardApi(
+            val p1LastSet = p1.setScores.lastOrNull()
+            val p2LastSet = p2.setScores.lastOrNull()
+            val isCurrentSetTiebreak = (p1LastSet != null && p2LastSet != null && p1LastSet >= 6 && p2LastSet >= 6 && p1LastSet == p2LastSet) ||
+                tb1 != null || tb2 != null
+
+            val resolvedP1Game = resolveLiveGameScore(p1, tb1, finalP1Serving, isCurrentSetTiebreak)
+            val resolvedP2Game = resolveLiveGameScore(p2, tb2, finalP2Serving, isCurrentSetTiebreak)
```"""
    if norm_cat == "FILTER_TOUR_MISMATCH":
        return """```diff
--- a/app/src/main/java/com/tennis/tracker/ui/viewmodel/ScoreboardViewModel.kt
+++ b/app/src/main/java/com/tennis/tracker/ui/viewmodel/ScoreboardViewModel.kt
@@ -520,6 +520,10 @@ class ScoreboardViewModel : ViewModel() {
         val newTournament = preservedTournament ?: defaultTierTournament
         val contextual = computeContextualTournaments(state.allMatches, tier, newTournament)
         repository.setActiveTournament(newTournament)
         state.copy(
             selectedTierFilter = tier,
             currentTournament = newTournament,
```"""
    elif norm_cat == "SCORE_LIVE_DATA":
        return """```diff
--- a/app/src/main/java/com/tennis/tracker/data/repository/ScoreboardRepositoryImpl.kt
+++ b/app/src/main/java/com/tennis/tracker/data/repository/ScoreboardRepositoryImpl.kt
@@ -175,6 +175,10 @@ class ScoreboardRepositoryImpl : ScoreboardRepository {
         } catch (e: Exception) {
             val cachedMatches = matchCacheManager.loadMatches(targetTournament.id)
             if (cachedMatches.isNotEmpty()) {
+                _scoreboardState.update { it.copy(isOffline = true, matches = cachedMatches) }
+                return cachedMatches
+            }
+            throw e
```"""
    elif norm_cat == "WATCH_HIGHLIGHTS":
        return """```diff
--- a/app/src/main/java/com/tennis/tracker/data/remote/EspnWatchMatcher.kt
+++ b/app/src/main/java/com/tennis/tracker/data/remote/EspnWatchMatcher.kt
@@ -35,6 +35,8 @@ object EspnWatchMatcher {
         val directStream = streams.firstOrNull { it.matchesMatch(match) }
         if (directStream != null) return directStream.playbackUri
+        val courtStream = streams.firstOrNull { it.matchesCourt(match.court) }
+        if (courtStream != null) return courtStream.playbackUri
         return null
```"""
    elif norm_cat == "HOME_WIDGET":
        return """```diff
--- a/app/src/main/java/com/tennis/tracker/ui/widget/WidgetAutoSyncManager.kt
+++ b/app/src/main/java/com/tennis/tracker/ui/widget/WidgetAutoSyncManager.kt
@@ -185,6 +185,8 @@ object WidgetAutoSyncManager {
         if (mode == WidgetSyncMode.DISABLED) return Long.MAX_VALUE
         val hasLiveFollowed = matches.any { it.isLive && followedIds.contains(it.id) }
         if (hasLiveFollowed) return 60_000L
+        val hasUpcomingFollowed = matches.any { !it.isFinished && followedIds.contains(it.id) }
+        if (hasUpcomingFollowed) return 300_000L
         return 900_000L
```"""
    elif norm_cat == "UI_LAYOUT":
        return """```diff
--- a/app/src/main/java/com/tennis/tracker/ui/util/AdaptiveLayoutUtils.kt
+++ b/app/src/main/java/com/tennis/tracker/ui/util/AdaptiveLayoutUtils.kt
@@ -58,6 +58,8 @@ object AdaptiveLayoutUtils {
     fun resolveFormFactor(widthDp: Int): FormFactor {
         return if (widthDp >= 600) FormFactor.LAPTOP else FormFactor.PHONE
     }
+    fun maxModalSheetWidth(widthDp: Int): Dp {
+        return if (widthDp >= 840) 640.dp else Dp.Unspecified
+    }
```"""
    else:
        return """```diff
--- a/app/src/main/java/com/tennis/tracker/ui/viewmodel/ScoreboardViewModel.kt
+++ b/app/src/main/java/com/tennis/tracker/ui/viewmodel/ScoreboardViewModel.kt
@@ -395,6 +395,8 @@ class ScoreboardViewModel : ViewModel() {
         } catch (e: Exception) {
             _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage) }
         }
```"""


def generate_triage_comment(
    issue_body: str,
    snapshot: Optional[Dict[str, Any]],
    category: str,
    suspected_files: List[Any],
    related_commits: List[Dict[str, str]],
    llm_result: Optional[Dict[str, str]] = None,
    agent_pr_info: Optional[Dict[str, Any]] = None,
) -> str:
    """Format structured 5-section Markdown triage report."""
    display_category = CATEGORY_DISPLAY_NAMES.get(category, category)
    anomalies = detect_state_anomalies(snapshot)
    user_desc = extract_issue_description(issue_body)

    # 1. Section 1: Diagnostic Summary
    summary_lines = [
        f"{BOT_MARKER_1}",
        f"{BOT_MARKER_2}",
        "",
        "## 🔍 Diagnostic Summary",
        "",
        "| Parameter | Diagnostic Value | Interpretation |",
        "|---|---|---|",
    ]

    if snapshot and isinstance(snapshot, dict):
        raw_app_v = snapshot.get("app_version") or "Unknown"
        raw_v_code = snapshot.get("version_code") or "N/A"
        app_v = f"{raw_app_v} ({raw_v_code})"

        raw_ff = snapshot.get("form_factor") or "Unknown"
        raw_sw = snapshot.get("screen_width_bucket") or "N/A"
        form_f = f"{raw_ff} ({raw_sw})"

        raw_sb = snapshot.get("scoreboard_state")
        scoreboard = raw_sb if isinstance(raw_sb, dict) else {}
        tour = scoreboard.get("tour_filter") or "ALL"
        tourn = scoreboard.get("current_tournament") or "All"
        mtype = scoreboard.get("match_type_filter") or "ALL"
        mfilter = scoreboard.get("match_filter") or "ALL"
        offline = "OFFLINE (local cache)" if scoreboard.get("is_offline") is True else "ONLINE"

        raw_total = scoreboard.get("total_matches")
        total = raw_total if isinstance(raw_total, (int, float)) else 0
        raw_filtered = scoreboard.get("filtered_matches")
        filtered = raw_filtered if isinstance(raw_filtered, (int, float)) else 0
        raw_live = scoreboard.get("live_matches")
        live = raw_live if isinstance(raw_live, (int, float)) else 0
        raw_followed = scoreboard.get("followed_matches")
        followed = raw_followed if isinstance(raw_followed, (int, float)) else 0
        counts = f"Total: {total} • Filtered: {filtered} • Live: {live} • Followed: {followed}"

        raw_settings = snapshot.get("settings_snapshot")
        settings = raw_settings if isinstance(raw_settings, dict) else {}
        refresh = settings.get("auto_refresh_interval") or "INTERVAL_60S"
        sync_mode = settings.get("widget_sync_mode") or "ADAPTIVE"

        match_ctx = snapshot.get("match_context")
        ctx_desc = "None (Scoreboard Screen)"
        if match_ctx and isinstance(match_ctx, dict):
            ctx_tourn = match_ctx.get("tournament_name") or "Match"
            ctx_group = match_ctx.get("event_grouping") or "Draw"
            ctx_state = match_ctx.get("state") or "State"
            ctx_desc = f"{ctx_tourn} ({ctx_group}, {ctx_state})"

        raw_bc = snapshot.get("breadcrumbs")
        breadcrumbs = raw_bc if isinstance(raw_bc, list) else []
        bc_errors = [
            b for b in breadcrumbs
            if isinstance(b, dict) and any(
                err in str(b.get("message") or "")
                for err in ["502", "500", "429", "SocketTimeoutException", "ConnectException", "Exception", "Error"]
            )
        ]
        bc_desc = f"{len(breadcrumbs)} logged ({len(bc_errors)} errors)"
        if bc_errors:
            last_err = bc_errors[-1]
            last_tag = str(last_err.get("tag") or "UNKNOWN")
            last_msg = str(last_err.get("message") or "")[:45]
            bc_desc += f" — Last error: `{last_tag}: {last_msg}`"

        summary_lines.extend([
            f"| **App Version** | `{app_v}` | Active client build |",
            f"| **Form Factor** | `{form_f}` | Device display layout |",
            f"| **Category** | `{display_category}` | Normalized issue taxonomy |",
            f"| **Active Filters** | Tier: `{tour}` • Tourn: `{tourn}` • Type: `{mtype}` • Filter: `{mfilter}` | Active scoreboard predicates |",
            f"| **Match Counts** | {counts} | In-memory vs displayed matches |",
            f"| **Network Status** | `{offline}` | Remote repository connectivity |",
            f"| **Settings** | Refresh: `{refresh}` • Widget: `{sync_mode}` | Active client settings |",
            f"| **Match Context** | {ctx_desc} | Contextual reporting anchor |",
            f"| **Recent Events** | {bc_desc} | Breadcrumb telemetry buffer |",
        ])
    else:
        env_match = re.search(
            r"\*Environment:\s*App\s+v([^\s]+)\s+\((\d+)\),\s*([^,]+),\s*([^*]+)\*",
            issue_body or "",
            re.IGNORECASE,
        )
        if env_match:
            summary_lines.extend([
                f"| **App Version** | `{env_match.group(1)} ({env_match.group(2)})` | Extracted from web fallback footer |",
                f"| **Form Factor** | `{env_match.group(3).strip()} ({env_match.group(4).strip()})` | Device display layout |",
                f"| **Category** | `{display_category}` | Extracted from issue labels/body |",
                "| **Diagnostic Bundle** | *Web fallback header* | Submitted via GitHub issue web fallback |",
            ])
        else:
            summary_lines.extend([
                f"| **Category** | `{display_category}` | Extracted from issue labels/body |",
                "| **Diagnostic Bundle** | *None attached* | Web or manual submission fallback |",
            ])

    if user_desc:
        quoted = "\n> ".join(user_desc.splitlines())
        summary_lines.extend([
            "",
            "**Reported Problem Description:**",
            f"> {quoted}",
        ])

    # 2. Section 2: Suspected Files & Symbols
    files_lines = [
        "",
        "## 📁 Suspected Files & Symbols",
        "",
        "| Suspected File | Key Symbols / Methods | Confidence | Rationale |",
        "|---|---|---|---|",
    ]
    for item in suspected_files:
        if isinstance(item, dict):
            p = item.get("path", "")
            syms = "<br>".join(f"`{s}`" for s in item.get("symbols", [])) if item.get("symbols") else "—"
            conf = f"**{item.get('confidence', 'MEDIUM')}**"
            reas = item.get("reason", "")
            files_lines.append(f"| `{p}` | {syms} | {conf} | {reas} |")
        else:
            files_lines.append(f"| `{item}` | — | **MEDIUM** | Identified by heuristic code mapping |")

    # 3. Section 3: Related Commits / Issues
    commits_lines = [
        "",
        "## 🔗 Related Commits / Issues",
        "",
    ]
    if related_commits:
        commits_lines.extend([
            "| Commit | Author & Time | Message / Correlated Feature |",
            "|---|---|---|",
        ])
        for c in related_commits:
            h = c.get("hash") or c.get("sha", "")
            author = f"{c.get('author', 'Dev')} ({c.get('time', '')})"
            msg = c.get("message", "")
            commits_lines.append(f"| `{h}` | {author} | {msg} |")
    else:
        commits_lines.append("*No recent commits directly modified suspected files or repository history was shallow.*")

    # 4. Section 4: Root-Cause Analysis
    rca_lines = [
        "",
        "## 🧠 Root-Cause Analysis",
        "",
    ]
    if llm_result and "analysis" in llm_result:
        rca_lines.append(llm_result["analysis"])
    else:
        rca_lines.append(f"The report is classified under **{display_category}**.")
        if user_desc:
            first_line = user_desc.splitlines()[0].strip()
            rca_lines.append(f"**Reported Symptom:** {first_line}")
        lower_desc = (issue_body or "").lower()
        if any(kw in lower_desc for kw in ["tiebreak", "tie break", "30:30", "6:6"]):
            rca_lines.extend([
                "",
                "**Tiebreak Live Score Root-Cause Analysis:**",
                "- In `EspnScoreboardApi.kt` (`parseCompetitors`), when a live match reaches `6-6` in games and ESPN's summary payload omits `linescores[].tiebreak` or `possession` flags, the fallback expression `lastSetTiebreak?.let { \"TB $it\" } ?: if (rawServing) \"40\" else \"30\"` evaluates `rawServing == false` for both players, assigning `\"30\"` (`30:30`) to both competitors during an active tiebreak.",
                "- Additionally, when `TennisStatsApi.fetchLiveGameScores` or `EspnScoreboardApi` receives numeric tiebreak points (e.g. `2` and `2`) at `6-6`, `ScoreboardRepositoryImpl` and `MatchCard` need to normalize tiebreak points as `TB 2` and populate the current set's `tiebreakScores` so the set score (`6²`) and live game badge (`TB 2`) never display standard `15/30/40` game points at `6-6`.",
            ])
        if anomalies:
            rca_lines.append("\n**State Anomalies & Inferences:**")
            for a in anomalies:
                rca_lines.append(f"- {a}")
        else:
            rca_lines.append(
                "Diagnostic state exhibits standard nominal parameters. Review suspected methods above "
                "for race conditions or unexpected remote API response payloads."
            )

    # 5. Section 5: Proposed Patch & Verification Steps
    patch_lines = [
        "",
        "## 🛠️ Proposed Patch & Verification Steps",
        "",
        "### Candidate Patch",
        get_deterministic_candidate_patch(category, user_text=issue_body),
        "",
        "### Independent Verification Steps",
        "Run the targeted Kotlin unit tests and project build inside the Linux VM redirecting the Gradle cache:",
        "```bash",
        "# 1. Run targeted unit tests for this subsystem",
    ]
    if category == "FILTER_TOUR_MISMATCH":
        patch_lines.append('PROJ_ID="$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)" && ./gradlew testDebugUnitTest --tests "*Filter*" --tests "*Scoreboard*" --project-cache-dir="$HOME/.cache/projects/$PROJ_ID/gradle"')
    elif category == "SCORE_LIVE_DATA":
        patch_lines.append('PROJ_ID="$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)" && ./gradlew testDebugUnitTest --tests "*ScoreboardRepository*" --tests "*MatchCache*" --project-cache-dir="$HOME/.cache/projects/$PROJ_ID/gradle"')
    elif category == "WATCH_HIGHLIGHTS":
        patch_lines.append('PROJ_ID="$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)" && ./gradlew testDebugUnitTest --tests "*Watch*" --tests "*Highlight*" --project-cache-dir="$HOME/.cache/projects/$PROJ_ID/gradle"')
    elif category == "HOME_WIDGET":
        patch_lines.append('PROJ_ID="$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)" && ./gradlew testDebugUnitTest --tests "*Widget*" --project-cache-dir="$HOME/.cache/projects/$PROJ_ID/gradle"')
    elif category == "UI_LAYOUT":
        patch_lines.append('PROJ_ID="$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)" && ./gradlew testDebugUnitTest --tests "*AdaptiveLayout*" --tests "*MatchCard*" --project-cache-dir="$HOME/.cache/projects/$PROJ_ID/gradle"')
    else:
        patch_lines.append('PROJ_ID="$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)" && ./gradlew testDebugUnitTest --project-cache-dir="$HOME/.cache/projects/$PROJ_ID/gradle"')

    patch_lines.extend([
        "",
        "# 2. Run full test suite & assemble APK",
        'PROJ_ID="$(basename "$PWD")-$(pwd -P | sha1sum | cut -c1-8)" && ./gradlew testDebugUnitTest assembleDebug --project-cache-dir="$HOME/.cache/projects/$PROJ_ID/gradle"',
        "```",
    ])

    if agent_pr_info and isinstance(agent_pr_info, dict):
        patch_lines.extend([
            "",
            "## 🚀 Agentic Fix Branch & Pull Request",
            "",
        ])
        if agent_pr_info.get("approved"):
            branch_name = agent_pr_info.get("branch_name", "")
            branch_url = agent_pr_info.get("branch_url", "")
            pr_url = agent_pr_info.get("pr_url", "")
            pr_number = agent_pr_info.get("pr_number", "")
            reason = agent_pr_info.get("reason", "Approved")
            patch_lines.append(f"- **Authorization**: ✅ {reason}")
            if branch_name and branch_url:
                patch_lines.append(f"- **Fix Branch**: [`{branch_name}`]({branch_url})")
            elif branch_name:
                patch_lines.append(f"- **Fix Branch**: `{branch_name}`")
            if pr_url:
                label = f"Pull Request #{pr_number}" if pr_number else "Review Pull Request"
                patch_lines.append(f"- **Pull Request for Review**: [**{label}**]({pr_url})")
        else:
            reason = agent_pr_info.get("reason", "Awaiting repository owner approval")
            patch_lines.extend([
                f"- **Status**: 🔒 {reason}",
                "- **How to Approve Auto-Fix**: `@yurilo` can comment `/approve` (or `/fix`) on this issue or add the `agent-approved` label to automatically create a dedicated fix branch and Pull Request.",
            ])

    return "\n".join(summary_lines + files_lines + commits_lines + rca_lines + patch_lines) + "\n"


def is_already_triaged(comments: Optional[List[Dict[str, Any]]]) -> bool:
    """Check if issue already contains automated bot triage comment."""
    if not comments or not isinstance(comments, list):
        return False
    for c in comments:
        if isinstance(c, dict):
            body = str(c.get("body") or "")
            if BOT_MARKER_1 in body or BOT_MARKER_2 in body:
                return True
    return False


def fetch_github_issue(repo: str, issue_number: int, token: str = "") -> Optional[Dict[str, Any]]:
    """Fetch issue details from GitHub REST API."""
    url = f"https://api.github.com/repos/{repo}/issues/{issue_number}"
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "TennisTracker-BugTriage/1.0",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        sys.stderr.write(f"Warning: Could not fetch issue #{issue_number} from GitHub ({e})\n")
        return None


def fetch_github_comments(repo: str, issue_number: int, token: str) -> List[Dict[str, Any]]:
    """Fetch existing issue comments from GitHub REST API."""
    url = f"https://api.github.com/repos/{repo}/issues/{issue_number}/comments"
    req = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "TennisTracker-BugTriage/1.0",
            "X-GitHub-Api-Version": "2022-11-28",
        },
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        sys.stderr.write(f"Warning: Could not fetch comments from GitHub ({e})\n")
        return []


def post_github_comment(repo: str, issue_number: int, comment_body: str, token: str, dry_run: bool = False) -> bool:
    """Post triage comment to GitHub issue."""
    if dry_run:
        print("[DRY-RUN] Skipping comment post to GitHub API.")
        return True

    if not token:
        sys.stderr.write("Error: No GitHub token provided to post comment.\n")
        return False

    url = f"https://api.github.com/repos/{repo}/issues/{issue_number}/comments"
    data = json.dumps({"body": comment_body}).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "TennisTracker-BugTriage/1.0",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status in (200, 201)
    except Exception as e:
        sys.stderr.write(f"Error posting comment to GitHub: {e}\n")
        return False


def evaluate_agent_approval(
    event: Optional[Dict[str, Any]] = None,
    issue_author: str = "",
    labels: Optional[List[str]] = None,
    approved_by: str = "",
    owner_login: str = "yurilo",
) -> Tuple[bool, str, bool]:
    """Evaluate whether an issue is authorized for automated branch & PR creation.

    Returns (is_approved, reason, is_explicit_retrigger).
    """
    owner = owner_login.lower().strip()
    author = (issue_author or "").lower().strip()
    actor = (approved_by or "").lower().strip()
    norm_labels = [str(l).lower().strip() for l in (labels or []) if l]

    if event and isinstance(event, dict):
        comment = event.get("comment")
        if isinstance(comment, dict):
            c_user = str((comment.get("user") or {}).get("login") or "").lower().strip()
            c_body = str(comment.get("body") or "").lower()
            if c_user == owner and any(cmd in c_body for cmd in ["/approve", "/fix", "/agent-fix"]):
                return True, f"Approved via command comment by @{owner_login}", True

        action = str(event.get("action") or "").lower()
        lbl_name = str((event.get("label") or {}).get("name") or "").lower().strip()
        if action == "labeled" and lbl_name in ("agent-approved", "auto-fix"):
            return True, f"Approved via `{lbl_name}` label by @{owner_login}", True

    if any(l in ("agent-approved", "auto-fix") for l in norm_labels):
        return True, f"Approved via issue label (`agent-approved` / `auto-fix`)", False

    if author == owner:
        return True, f"Auto-approved (reported directly by repository owner @{owner_login})", False

    if actor == owner and not event:
        return True, f"Approved via manual dispatch by @{owner_login}", True

    return False, f"Awaiting approval from @{owner_login}", False


def slugify_branch_suffix(title: str, max_len: int = 36) -> str:
    """Create a clean git branch slug from issue title."""
    cleaned = re.sub(r"^\[[^\]]+\]\s*", "", title or "").lower()
    slug = re.sub(r"[^a-z0-9]+", "-", cleaned).strip("-")
    if not slug:
        slug = "bugfix"
    return slug[:max_len].strip("-")


def create_or_find_pull_request(
    repo: str,
    branch_name: str,
    base_branch: str,
    title: str,
    body: str,
    token: str,
) -> Optional[Dict[str, Any]]:
    """Create a Pull Request on GitHub or return an existing open PR for branch_name."""
    if not token:
        return None
    owner = repo.split("/")[0]
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "TennisTracker-BugTriage/1.0",
        "Content-Type": "application/json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    url = f"https://api.github.com/repos/{repo}/pulls"
    payload = json.dumps({
        "title": title,
        "head": branch_name,
        "base": base_branch,
        "body": body,
    }).encode("utf-8")
    req = urllib.request.Request(url, data=payload, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 422:
            # PR may already exist; query open PRs for this head branch
            list_url = f"https://api.github.com/repos/{repo}/pulls?head={owner}:{branch_name}&state=open"
            list_req = urllib.request.Request(list_url, headers=headers, method="GET")
            try:
                with urllib.request.urlopen(list_req, timeout=10) as resp2:
                    prs = json.loads(resp2.read().decode("utf-8"))
                    if isinstance(prs, list) and prs:
                        return prs[0]
            except Exception:
                pass
        sys.stderr.write(f"Warning: Could not create PR for {branch_name} ({e})\n")
        return None
    except Exception as e:
        sys.stderr.write(f"Warning: Could not create PR for {branch_name} ({e})\n")
        return None


def create_fix_branch_and_pr(
    repo: str,
    issue_number: int,
    issue_title: str,
    category: str,
    issue_body: str,
    report_markdown: str,
    token: str,
    repo_root: Optional[str] = None,
    dry_run: bool = False,
) -> Dict[str, Any]:
    """Create a dedicated fix/feature branch, commit candidate patch & triage report, push, and open a PR."""
    if not repo_root:
        repo_root = os.getcwd()
    prefix = "feat" if category == "FEATURE" else "fix"
    slug = slugify_branch_suffix(issue_title)
    branch_name = f"{prefix}/issue-{issue_number}-{slug}"
    branch_url = f"https://github.com/{repo}/tree/{branch_name}"

    if dry_run:
        return {
            "branch_name": branch_name,
            "branch_url": branch_url,
            "pr_url": f"https://github.com/{repo}/pull/dry-run",
            "pr_number": "DRY-RUN",
        }

    # Check if branch already exists on remote and has an open PR
    existing_pr = create_or_find_pull_request(
        repo=repo,
        branch_name=branch_name,
        base_branch="main",
        title=f"fix(issue-#{issue_number}): {re.sub(r'^\\[[^\\]]+\\]\\s*', '', issue_title or f'Resolve issue #{issue_number}')}",
        body=f"Automated agentic fix branch for #{issue_number}.\n\nCloses #{issue_number}",
        token=token,
    )
    if existing_pr and existing_pr.get("html_url"):
        return {
            "branch_name": branch_name,
            "branch_url": branch_url,
            "pr_url": existing_pr.get("html_url", ""),
            "pr_number": existing_pr.get("number", ""),
        }

    # Otherwise create the branch locally in GitHub Actions / git repo, commit candidate patch artifact, push, and open PR
    try:
        cur_branch_proc = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            cwd=repo_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
        orig_branch = cur_branch_proc.stdout.strip() or "main"

        subprocess.run(["git", "checkout", "-B", branch_name], cwd=repo_root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        patches_dir = os.path.join(repo_root, ".patches")
        os.makedirs(patches_dir, exist_ok=True)
        patch_block = get_deterministic_candidate_patch(category, user_text=issue_body)
        raw_diff = patch_block.replace("```diff", "").replace("```", "").strip() + "\n"
        patch_file = os.path.join(patches_dir, f"issue-{issue_number}.patch")
        with open(patch_file, "w", encoding="utf-8") as pf:
            pf.write(raw_diff)

        triage_file = os.path.join(patches_dir, f"issue-{issue_number}-triage.md")
        with open(triage_file, "w", encoding="utf-8") as tf:
            tf.write(report_markdown)

        # Apply patch if it applies cleanly to current tree
        apply_check = subprocess.run(
            ["git", "apply", "--check", patch_file],
            cwd=repo_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
        if apply_check.returncode == 0:
            subprocess.run(["git", "apply", patch_file], cwd=repo_root, check=False)

        subprocess.run(["git", "add", ".patches/"], cwd=repo_root, check=True)
        subprocess.run(
            ["git", "commit", "-m", f"fix(issue-#{issue_number}): add candidate patch and triage analysis for #{issue_number}"],
            cwd=repo_root,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        subprocess.run(
            ["git", "push", "-u", "origin", branch_name, "--force"],
            cwd=repo_root,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        subprocess.run(["git", "checkout", orig_branch], cwd=repo_root, check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        clean_title = re.sub(r"^\[[^\]]+\]\s*", "", issue_title or f"Resolve issue #{issue_number}")
        pr_body = (
            f"## 🤖 Agentic Bug-Fix Pull Request for #{issue_number}\n\n"
            f"Closes #{issue_number}\n\n"
            f"---\n\n"
            f"{report_markdown}"
        )
        pr_obj = create_or_find_pull_request(
            repo=repo,
            branch_name=branch_name,
            base_branch="main",
            title=f"fix(issue-#{issue_number}): {clean_title}",
            body=pr_body,
            token=token,
        )
        return {
            "branch_name": branch_name,
            "branch_url": branch_url,
            "pr_url": (pr_obj or {}).get("html_url", ""),
            "pr_number": (pr_obj or {}).get("number", ""),
        }
    except Exception as e:
        sys.stderr.write(f"Warning: Failed to create/push fix branch {branch_name}: {e}\n")
        return {
            "branch_name": branch_name,
            "branch_url": branch_url,
            "pr_url": "",
            "pr_number": "",
        }


def is_merge_requested(
    event: Optional[Dict[str, Any]] = None,
    comments: Optional[List[Dict[str, Any]]] = None,
    owner_login: str = "yurilo",
    explicit_merge: bool = False,
) -> bool:
    """Check if repository owner requested merging the issue's PR via comment or flag."""
    if explicit_merge:
        return True
    owner = owner_login.lower().strip()
    merge_phrases = ("/merge", "merge it", "merge pr", "ship it", "lgtm")

    if event and isinstance(event, dict):
        comment = event.get("comment")
        if isinstance(comment, dict):
            c_user = str((comment.get("user") or {}).get("login") or "").lower().strip()
            c_body = str(comment.get("body") or "").lower().strip()
            if c_user == owner and any(p in c_body for p in merge_phrases):
                return True

    if comments and isinstance(comments, list):
        for c in reversed(comments):
            if isinstance(c, dict):
                c_user = str((c.get("user") or {}).get("login") or "").lower().strip()
                c_body = str(c.get("body") or "").lower().strip()
                if BOT_MARKER_1 in c_body or BOT_MARKER_2 in c_body:
                    continue
                if c_user == owner and any(p in c_body for p in merge_phrases):
                    return True
    return False


def merge_issue_pull_request(
    repo: str,
    issue_number: int,
    token: str,
    comments: Optional[List[Dict[str, Any]]] = None,
    dry_run: bool = False,
) -> Optional[Dict[str, Any]]:
    """Find the open PR associated with issue_number and merge it via GitHub REST API."""
    if not token:
        return None
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "TennisTracker-BugTriage/1.0",
        "Content-Type": "application/json",
        "X-GitHub-Api-Version": "2022-11-28",
    }

    pr_number: Optional[int] = None
    # 1. Check bot triage comment for PR number
    if comments:
        for c in reversed(comments):
            body = str((c or {}).get("body") or "")
            m = re.search(r"/pull/(\d+)", body)
            if m:
                pr_number = int(m.group(1))
                break

    # 2. Search open PRs referencing issue-#<number> in branch name or body
    if pr_number is None:
        list_url = f"https://api.github.com/repos/{repo}/pulls?state=open&per_page=30"
        req = urllib.request.Request(list_url, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                prs = json.loads(resp.read().decode("utf-8"))
                for pr in prs:
                    head_ref = str(((pr.get("head") or {}).get("ref")) or "")
                    pr_body = str(pr.get("body") or "")
                    if f"issue-{issue_number}-" in head_ref or f"#{issue_number}" in pr_body:
                        pr_number = int(pr["number"])
                        break
        except Exception as e:
            sys.stderr.write(f"Warning: Could not list open PRs ({e})\n")

    if pr_number is None:
        return None

    if dry_run:
        return {"pr_number": pr_number, "merged": True, "sha": "dry-run-sha"}

    merge_url = f"https://api.github.com/repos/{repo}/pulls/{pr_number}/merge"
    payload = json.dumps({
        "commit_title": f"Merge pull request #{pr_number} (Fixes #{issue_number})",
        "merge_method": "merge",
    }).encode("utf-8")
    merge_req = urllib.request.Request(merge_url, data=payload, headers=headers, method="PUT")
    try:
        with urllib.request.urlopen(merge_req, timeout=15) as resp:
            res = json.loads(resp.read().decode("utf-8"))
            return {
                "pr_number": pr_number,
                "merged": bool(res.get("merged", True)),
                "sha": res.get("sha", ""),
                "message": res.get("message", "Pull Request successfully merged"),
            }
    except Exception as e:
        sys.stderr.write(f"Error merging PR #{pr_number}: {e}\n")
        return None


def main(argv: Optional[List[str]] = None) -> int:
    """CLI entry point for triage script."""
    parser = argparse.ArgumentParser(description="Tennis Tracker Automated Bug Triage Engine")
    parser.add_argument("--issue-event", help="Path to GitHub Actions event payload JSON file")
    parser.add_argument("--issue-body", help="Verbatim issue body text")
    parser.add_argument("--issue-file", help="Path to file containing issue body text")
    parser.add_argument("--issue-number", type=int, help="GitHub Issue Number")
    parser.add_argument("--repo", default="yurilo/tennis_tracker", help="GitHub repo slug (owner/repo)")
    parser.add_argument("--github-token", "--token", dest="token", default=os.environ.get("GITHUB_TOKEN", ""), help="GitHub Personal Access Token")
    parser.add_argument("--output", help="Optional output path to save generated triage report")
    parser.add_argument("--dry-run", action="store_true", help="Run triage and generate report without posting comment")
    parser.add_argument("--no-llm", action="store_true", help="Disable LLM analysis and enforce deterministic static mode")
    parser.add_argument("--llm-provider", choices=["auto", "gemini", "openai", "none"], default="auto", help="LLM provider")
    parser.add_argument("--auto-branch-pr", action="store_true", help="Create fix branch and PR when approved or authored by owner")
    parser.add_argument("--merge-pr", action="store_true", help="Merge the open PR associated with this issue")
    parser.add_argument("--approved-by", default="", help="GitHub actor login triggering the workflow")
    parser.add_argument("--branch-name", default="", help="Optional pre-created branch name to link in comment")
    parser.add_argument("--pr-url", default="", help="Optional pre-created Pull Request URL to link in comment")
    parser.add_argument("--pr-number", default="", help="Optional pre-created Pull Request number to link in comment")

    args = parser.parse_args(argv)

    issue_body: Optional[str] = None
    issue_number: Optional[int] = args.issue_number
    issue_title: str = ""
    issue_author: str = ""
    labels: List[str] = []
    event_data: Optional[Dict[str, Any]] = None

    # 1. Parse GitHub event payload if provided
    if args.issue_event:
        try:
            with open(args.issue_event, "r", encoding="utf-8") as f:
                event_data = json.load(f)
            issue = event_data.get("issue", {})
            issue_body = issue.get("body", "")
            if issue_number is None:
                issue_number = issue.get("number")
            issue_title = issue.get("title", "")
            issue_author = str((issue.get("user") or {}).get("login") or "")
            raw_labels = issue.get("labels", [])
            labels = [l.get("name") if isinstance(l, dict) else str(l) for l in raw_labels]
        except Exception as e:
            sys.stderr.write(f"Error reading event JSON {args.issue_event}: {e}\n")
            return 1
    elif args.issue_file:
        try:
            with open(args.issue_file, "r", encoding="utf-8") as f:
                issue_body = f.read()
        except Exception as e:
            sys.stderr.write(f"Error reading issue file {args.issue_file}: {e}\n")
            return 1
    elif args.issue_body is not None:
        issue_body = args.issue_body
    elif issue_number is not None:
        fetched = fetch_github_issue(args.repo, issue_number, args.token)
        if fetched and isinstance(fetched, dict):
            issue_body = fetched.get("body") or ""
            issue_title = fetched.get("title") or ""
            issue_author = str((fetched.get("user") or {}).get("login") or "")
            raw_labels = fetched.get("labels", [])
            labels = [l.get("name") if isinstance(l, dict) else str(l) for l in raw_labels]

    if issue_body is None:
        sys.stderr.write("Error: Must provide one of --issue-event, --issue-file, --issue-body, or --issue-number.\n")
        return 2

    existing_comments: List[Dict[str, Any]] = []
    if issue_number and args.token:
        existing_comments = fetch_github_comments(args.repo, issue_number, args.token)

    # Check if owner requested merging the PR (e.g. commented "merge it" or "/merge")
    if issue_number and args.token and is_merge_requested(event=event_data, comments=existing_comments, explicit_merge=args.merge_pr):
        merge_res = merge_issue_pull_request(
            repo=args.repo,
            issue_number=issue_number,
            token=args.token,
            comments=existing_comments,
            dry_run=args.dry_run,
        )
        if merge_res and merge_res.get("merged"):
            pr_num = merge_res.get("pr_number")
            sha = merge_res.get("sha", "")[:7]
            merge_comment = (
                f"✅ **Agentic Auto-Merge Complete**: Merged [**Pull Request #{pr_num}**](https://github.com/{args.repo}/pull/{pr_num}) "
                f"into `main` (`{sha}`) per `@yurilo`'s comment on #{issue_number}."
            )
            post_github_comment(args.repo, issue_number, merge_comment, args.token, dry_run=args.dry_run)
            if args.output:
                with open(args.output, "w", encoding="utf-8") as f:
                    f.write(merge_comment + "\n")
            print(merge_comment)
            return 0

    is_approved, approval_reason, is_explicit_retrigger = evaluate_agent_approval(
        event=event_data,
        issue_author=issue_author,
        labels=labels,
        approved_by=args.approved_by,
    )

    # 2. Idempotency Check (skip only if already triaged AND not an explicit approval retrigger)
    if not args.dry_run and issue_number and args.token and not is_explicit_retrigger and not args.pr_url:
        if is_already_triaged(existing_comments):
            msg = f"[IDEMPOTENCY] Issue #{issue_number} already contains automated triage comment. Skipping.\n"
            sys.stdout.write(msg)
            if args.output:
                with open(args.output, "w", encoding="utf-8") as f:
                    f.write(msg)
            return 0

    # 3. Extract Snapshot & Category
    snapshot = extract_diagnostic_snapshot(issue_body)
    category = extract_category(issue_body=issue_body, labels=labels)

    # 4. Correlate Source Files
    suspected_files = correlate_source_files(category, snapshot, user_text=f"{issue_title}\n{issue_body}")

    # 5. Correlate Git History
    repo_root = os.getcwd()
    related_commits = correlate_git_history(suspected_files, repo_root=repo_root)

    # 6. Optional LLM Analysis
    llm_result = None
    if not args.no_llm and args.llm_provider != "none":
        llm_result = call_llm_analyzer(
            issue_info={
                "category": category,
                "title": issue_title,
                "description": issue_body,
                "snapshot": snapshot,
            },
            static_context={
                "suspected_files": suspected_files,
                "anomalies": detect_state_anomalies(snapshot),
            },
            provider=args.llm_provider,
        )

    # 7. Optional Agentic Branch & PR Creation
    agent_pr_info: Optional[Dict[str, Any]] = None
    if args.branch_name or args.pr_url:
        b_name = args.branch_name
        agent_pr_info = {
            "approved": True,
            "reason": approval_reason,
            "branch_name": b_name,
            "branch_url": f"https://github.com/{args.repo}/tree/{b_name}" if b_name else "",
            "pr_url": args.pr_url,
            "pr_number": args.pr_number,
        }
    elif args.auto_branch_pr and issue_number:
        if is_approved:
            prelim_report = generate_triage_comment(
                issue_body=issue_body,
                snapshot=snapshot,
                category=category,
                suspected_files=suspected_files,
                related_commits=related_commits,
                llm_result=llm_result,
            )
            pr_result = create_fix_branch_and_pr(
                repo=args.repo,
                issue_number=issue_number,
                issue_title=issue_title,
                category=category,
                issue_body=f"{issue_title}\n{issue_body}",
                report_markdown=prelim_report,
                token=args.token,
                repo_root=repo_root,
                dry_run=args.dry_run,
            )
            agent_pr_info = {
                "approved": True,
                "reason": approval_reason,
                **pr_result,
            }
        else:
            agent_pr_info = {
                "approved": False,
                "reason": approval_reason,
            }

    # 8. Generate Full Report
    report = generate_triage_comment(
        issue_body=issue_body,
        snapshot=snapshot,
        category=category,
        suspected_files=suspected_files,
        related_commits=related_commits,
        llm_result=llm_result,
        agent_pr_info=agent_pr_info,
    )

    # 9. Output handling
    if args.output:
        try:
            with open(args.output, "w", encoding="utf-8") as f:
                f.write(report)
        except Exception as e:
            sys.stderr.write(f"Error writing report to {args.output}: {e}\n")
            return 1

    if args.dry_run or not issue_number or not args.token:
        print(report)
    else:
        success = post_github_comment(args.repo, issue_number, report, args.token, dry_run=False)
        if not success:
            return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())


