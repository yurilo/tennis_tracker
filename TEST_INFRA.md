# E2E Test Infra: Tennis Tracker Multi-Tournament Support

## Test Philosophy
- Opaque-box, requirement-driven derived from `ORIGINAL_REQUEST.md`.
- Verifies outward API behavior, domain abstractions, date dynamics, UI filter contracts, and collection performance without depending on implementation internals.
- Methodology: 4-Tier test suite (Category-Partition, Boundary Value Analysis, Pairwise Combinatorial, Real-World Workloads) followed by Tier 5 Adversarial Coverage Hardening.

## Feature Inventory
| # | Feature | Source | Tier 1 | Tier 2 | Tier 3 |
|---|---|---|:---:|:---:|:---:|
| 1 | Dynamic Dates & Year Resolution | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 2 | Dynamic Mock Relative Timestamps | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 3 | Date Parser Robustness & Harmonization | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 4 | ATP & WTA Tour Types (ATP, WTA, COMBINED) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 5 | Tournament Categories (Grand Slam, 1000, 500, 250, Finals) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 6 | Court Surfaces (Hard, Clay, Grass, Carpet) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 7 | Dynamic Tournament Matching & Discovery from Feeds | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 8 | Tiered UI Filter Chips (All, Grand Slams, ATP, WTA, 1000s, 500s/250s) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 9 | Contextual Active Tournament Chips | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 10 | Loop Refactoring & O(N+M) Deduplication | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 11 | Stream Matcher & Highlight Pre-Indexing Performance | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 12 | Safe Parsing, Immutability & Concurrency Safety | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |

## Test Architecture
- **E2E Test Runner**: Dedicated executable JVM runner or Kotlin test runner executing comprehensive opaque-box test scenarios.
- **Pass/Fail Semantics**: All test cases must assert expected contracts and exit with code 0 on 100% pass.
- **Location**: `app/src/test/java/com/tennis/tracker/e2e/` and standalone test harness.

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|---|---|---|
| 1 | Full Tournament Transition: Indian Wells Masters 1000 to Miami Open | F1, F4, F5, F7, F8, F9, F10 | High |
| 2 | Combined Tournament Ingest: China Open with Men's ATP 500 & Women's WTA 1000 | F4, F5, F6, F7, F8, F9 | High |
| 3 | Calendar Year Boundary Rollover: United Cup (Late Dec) to Australian Open (Early Jan) | F1, F2, F3, F5, F7 | High |
| 4 | Multi-Tournament Live Feed with 1000 Streams & Highlights | F7, F10, F11, F12 | High |
| 5 | Offline / Cache Startup with Dynamic Fallback Timestamps and Tier Filtering | F1, F2, F8, F9, F12 | Medium |
| 6 | Year-End Finals (ATP Finals in Turin & WTA Finals in Riyadh) Discovery & UI Filtering | F4, F5, F6, F7, F8, F9 | High |

## Coverage Thresholds
- Tier 1: ≥60 test cases (≥5 per feature across 12 features)
- Tier 2: ≥60 test cases (boundary values, invalid inputs, edge cases)
- Tier 3: ≥12 test cases (pairwise interactions across tour, category, surface, filter, year)
- Tier 4: ≥6 realistic application-level scenarios
- Total Target: ≥138 opaque-box test cases in `TEST_READY.md`.
