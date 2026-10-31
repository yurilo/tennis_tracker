package com.tennis.tracker.e2e

import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.junit.runners.Suite
import kotlin.system.exitProcess

/**
 * Master E2E Test Suite & Standalone JVM Runner for In-App Bug Reporting & Automated Triage.
 *
 * Aggregates all five verification tiers and integrated E2E test suites:
 * - Tier 1: Feature Coverage (41 test cases across F1-F8)
 * - Tier 2: Boundary & Corner Cases (30 test cases)
 * - Tier 3: Cross-Feature Interactions (15 test cases)
 * - Tier 4: Real-World Scenarios (6 test cases)
 * - Tier 5: Adversarial Hardening (14 test cases)
 * - BugReportingE2ETest: In-App reporting integration (8 test cases)
 * - TriageWorkflowE2ETest: GitHub Actions triage integration (6 test cases)
 * Total: 120 test cases
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    BugReportTier1FeatureCoverageTest::class,
    BugReportTier2BoundaryCornerCaseTest::class,
    BugReportTier3CrossFeatureTest::class,
    BugReportTier4RealWorldScenariosTest::class,
    BugReportTier5AdversarialTest::class,
    BugReportingE2ETest::class,
    TriageWorkflowE2ETest::class
)
class BugReportingE2ETestSuite {

    companion object {
        val SUITES = arrayOf(
            BugReportTier1FeatureCoverageTest::class.java,
            BugReportTier2BoundaryCornerCaseTest::class.java,
            BugReportTier3CrossFeatureTest::class.java,
            BugReportTier4RealWorldScenariosTest::class.java,
            BugReportTier5AdversarialTest::class.java,
            BugReportingE2ETest::class.java,
            TriageWorkflowE2ETest::class.java
        )

        @JvmStatic
        fun main(args: Array<String>) {
            println("================================================================================")
            println("🐞 TENNIS TRACKER IN-APP BUG REPORTING & TRIAGE E2E TEST RUNNER")
            println("================================================================================")
            println("Executing 4-Tier Test Suite across all 8 Core Features...\n")

            val startTime = System.currentTimeMillis()
            val result = JUnitCore.runClasses(*SUITES)
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0

            println("\n--------------------------------------------------------------------------------")
            println("TEST SUITE EXECUTION SUMMARY")
            println("--------------------------------------------------------------------------------")
            println("Total Tests Run:  ${result.runCount}")
            println("Successful Tests: ${result.runCount - result.failureCount}")
            println("Failures:         ${result.failureCount}")
            println("Ignored Tests:    ${result.ignoreCount}")
            println("Execution Time:   ${String.format("%.3f", elapsed)}s")
            println("--------------------------------------------------------------------------------")

            if (result.wasSuccessful()) {
                println("✅ ALL ${result.runCount} BUG REPORTING E2E TESTS PASSED SUCCESSFULLY! (100% Pass Rate)")
                println("================================================================================")
                exitProcess(0)
            } else {
                println("❌ E2E TEST FAILURES DETECTED (${result.failureCount} failed):")
                for (failure in result.failures) {
                    println("  - [FAIL] ${failure.testHeader}: ${failure.message}")
                    println("    ${failure.trace.lines().take(3).joinToString("\n    ")}")
                }
                println("================================================================================")
                exitProcess(1)
            }
        }
    }
}
