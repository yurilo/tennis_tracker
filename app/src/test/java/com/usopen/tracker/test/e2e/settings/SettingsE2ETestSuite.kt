package com.usopen.tracker.test.e2e.settings

import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.junit.runners.Suite
import kotlin.system.exitProcess

/**
 * Master E2E Test Suite & Standalone Runner for Centralized Settings Architecture.
 *
 * Executes the complete 4-tier opaque-box test suite:
 * - Tier 1: Feature Coverage (60 test cases)
 * - Tier 2: Boundary & Corner Cases (60 test cases)
 * - Tier 3: Pairwise Combinatorial (12 test cases)
 * - Tier 4: Real-World Scenarios (6 scenarios)
 * Total: 138 test cases
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    SettingsTier1FeatureCoverageTest::class,
    SettingsTier2BoundaryCornerCaseTest::class,
    SettingsTier3CrossFeatureTest::class,
    SettingsTier4RealWorldScenariosTest::class
)
class SettingsE2ETestSuite {

    companion object {
        val SUITES = arrayOf(
            SettingsTier1FeatureCoverageTest::class.java,
            SettingsTier2BoundaryCornerCaseTest::class.java,
            SettingsTier3CrossFeatureTest::class.java,
            SettingsTier4RealWorldScenariosTest::class.java
        )

        @JvmStatic
        fun main(args: Array<String>) {
            println("================================================================================")
            println("⚙️  TENNIS TRACKER CENTRALIZED SETTINGS E2E OPAQUE-BOX TEST RUNNER")
            println("================================================================================")
            println("Executing 4-Tier Test Suite across all 12 Features...\n")

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
                println("✅ ALL ${result.runCount} E2E SETTINGS TESTS PASSED SUCCESSFULLY! (100% Pass Rate)")
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
