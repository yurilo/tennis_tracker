package com.tennis.tracker.e2e

import org.junit.runner.JUnitCore
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import kotlin.system.exitProcess

/**
 * Standalone E2E Test Runner for Tennis Tracker Multi-Tournament Support.
 *
 * Executes the complete 4-tier opaque-box test suite:
 * - Tier 1: Feature Coverage (60 test cases)
 * - Tier 2: Boundary & Corner Cases (60 test cases)
 * - Tier 3: Pairwise Combinatorial (12 test cases)
 * - Tier 4: Real-World Scenarios (6 scenarios)
 * Total: 138 test cases
 */
object E2ETestRunner {

    val TEST_SUITES = arrayOf(
        Tier1FeatureCoverageTest::class.java,
        Tier2BoundaryCornerCaseTest::class.java,
        Tier3PairwiseCombinatorialTest::class.java,
        Tier4RealWorldScenariosTest::class.java
    )

    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println("🎾 TENNIS TRACKER MULTI-TOURNAMENT E2E OPAQUE-BOX TEST RUNNER")
        println("================================================================================")
        println("Executing 4-Tier Test Suite across all 12 Features...\n")

        val startTime = System.currentTimeMillis()
        val result = JUnitCore.runClasses(*TEST_SUITES)
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
            println("✅ ALL ${result.runCount} E2E TESTS PASSED SUCCESSFULLY! (100% Pass Rate)")
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
