# Tennis Tracker 🎾

A modern Android application for tracking ATP Tour, WTA Tour, and Grand Slam tennis tournaments in real time, built with Jetpack Compose, Material 3, and Clean Architecture.

[![Latest Release](https://img.shields.io/github/v/release/yurilo/tennis_tracker?label=Download%20APK&color=brightgreen)](https://github.com/yurilo/tennis_tracker/releases/latest)

📥 **[Download Latest APK (v1.0.24)](https://github.com/yurilo/tennis_tracker/releases/download/v1.0.24/TennisTracker-v1.0.24.apk)**

---

## 🌟 Features

- **Adaptive Phone & Laptop UI**:
  - Dynamically switches between single-column layout on phones and 2- or 3-column responsive grid on laptops/tablets.
  - Centered `1280dp` max-width container on wide desktop/laptop screens preventing stretched horizontal content.
  - Form-factor optimized modal bottom sheets (`widthIn(max = 640.dp)`) with centered horizontal alignment.
  - Density-aware TopAppBar action icon distribution and compact player score rows for mobile screens.
- **Global Tournament Coverage**: Real-time match scores, schedules, and results across all Grand Slams, ATP Tour, and WTA Tour tournaments (including Shanghai Open, Indian Wells, Miami, Madrid, Rome, Cincinnati, and year-round 500/250 events).
- **Copy-Friendly UX**:
  - Interactive **Match Copy Bottom Sheet** with 1-tap copy actions for quick sharing.
  - Granular copy options: full match recap, clean score summary (e.g. `Alcaraz d. Sinner 6-4, 3-6, 7-6(3)`), competitor names, and tournament/round details.
  - Haptic feedback and native Android clipboard confirmations.
- **Intelligent Tournament Name Limiting**:
  - `shortDisplayName` abbreviations for verbose tournament titles ensuring clean, readable UI chips and header banners.
  - Single-line chips with graceful ellipsis truncation preventing visual clutter or multi-line breaks.
- **Live Tennis Scoreboard**: Real-time match scores, set scores, game status, serve indicators, and seed badges for ATP Men's and WTA Women's tournaments.
- **Dynamic Player Headshots & Avatars**:
  - Scalable, zero-hardcoding resolution engine fetching official player portraits from the WTA Tour API (`api.wtatennis.com`) and Wikipedia/Wikimedia REST APIs.
  - Persistent multi-tier on-device caching (`SharedPreferences` + disk cache + LRU memory cache) for instant offline and repeat rendering across H2H, Match Stats, and Doubles views.
- **Head-to-Head (H2H) & Match Stats**:
  - Comprehensive comparison sheets with Tale of the Tape, key stats, serve percentages, break points, and winners/unforced errors.
  - Resilient statistics fallback with in-memory caching ensuring completed and live matches always display rich court statistics even when remote providers face network restrictions.
- **Centralized Settings & Material 3 Preferences**:
  - Modal bottom sheet with immediate reactive persistence (no manual save required).
  - Customizable scoreboard auto-refresh intervals: 30s, 60s (default), 2m, or Manual/Off.
  - Followed match retention policies: 12h, 24h (default), 48h, 7d, or Never auto-clear.
  - Adaptive home screen widget sync mode controls.
  - Startup default tournament tier preferences (All, Grand Slams, ATP Tour, WTA Tour).
  - One-tap "Reset to Defaults" confirmation action.
- **Singles & Doubles Support**:
  - Full doubles representation with dual overlapping portraits, partner names, dual country flags, and seed indicators.
  - Interactive **Doubles Profile Picker Dialog** for choosing either partner.
- **Official ATP & WTA Profiles**:
  - Direct 1-tap navigation to official ATP Tour and WTA Tennis player profile pages from Head-to-Head (H2H) and Match Stats screens.
  - Precision resolution engine handling diacritics, Eastern naming orders, and site-scoped search fallbacks.
- **Head-to-Head (H2H) & Match Stats**:
  - Comprehensive comparison sheets with Tale of the Tape, key stats, serve percentages, break points, and winners/unforced errors.
- **Watch & Highlights Integration**:
  - Deep linking into ESPN app with web fallback.
  - Quick YouTube highlights search integration.
- **Home Screen App Widget**:
  - Interactive Glance / RemoteViews widget with automatic 1-minute live match refresh.
- **Anonymous Heartbeat Telemetry & Live Dashboard**:
  - Zero-PII active user installation heartbeat to track daily/monthly active users.
  - Transparent user opt-out toggle in Settings bottom sheet and About dialog, GDPR erasure, and auto-backup isolation.
  - Free serverless Cloudflare Workers live telemetry dashboard (`telemetry_dashboard/`).
- **Offline Resilience**:
  - Dual-source repository with bundled realistic offline fixtures when network is unreachable.

---

## 🛠 Tech Stack & Architecture

- **UI**: Jetpack Compose, Material 3, Dynamic Color theming
- **Architecture**: Clean Architecture (Domain, Data, Presentation / MVVM)
- **Asynchronous**: Kotlin Coroutines & `StateFlow`
- **Networking**: ESPN REST API with custom robust JSON parsing and error handling
- **Testing**: JUnit 4, Mockk, Robolectric unit and UI test suite (100% test pass rate)

---

## 📂 Project Structure

```
us_open_tracker/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/tennis/tracker/
│   │   │   │   ├── data/          # Remote ESPN API, local fixtures, repository impl
│   │   │   │   ├── domain/        # Match & Competitor models, profile provider, use cases
│   │   │   │   ├── ui/            # Compose screens, components, theme, view models
│   │   │   │   ├── util/          # Intent launcher, deep linking, formatters
│   │   │   │   └── widget/        # Android home screen widget provider
│   │   │   └── res/               # Vector drawables, strings, layouts, widget configs
│   │   └── test/                  # Domain, data, and presentation unit tests
│   └── build.gradle.kts
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 🚀 Building & Testing

### Prerequisites
- JDK 17+
- Android SDK 34+

### Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

### Build Debug APK
```bash
./gradlew assembleDebug
```
The resulting APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License
This project is open-source under the Apache License 2.0.
