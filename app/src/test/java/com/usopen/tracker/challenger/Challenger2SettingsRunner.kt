package com.usopen.tracker.challenger

import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import kotlin.system.exitProcess

/**
 * Standalone JUnitCore runner for Challenger 2 Settings Adversarial Test Suite.
 */
object Challenger2SettingsRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println("⚔️  CHALLENGER 2: SETTINGS ADVERSARIAL CHALLENGE & EMPIRICAL PROBE RUNNER")
        println("================================================================================")
        println("Executing empirical stress harness across all 4 focus areas...\n")

        val startTime = System.currentTimeMillis()
        val jUnitCore = JUnitCore()
        val result = jUnitCore.run(SettingsAdversarialChallengeTest::class.java)
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0

        println("\n--------------------------------------------------------------------------------")
        println("CHALLENGER 2 EXECUTION SUMMARY")
        println("--------------------------------------------------------------------------------")
        println("Total Tests Run:  ${result.runCount}")
        println("Successful Tests: ${result.runCount - result.failureCount}")
        println("Failures:         ${result.failureCount}")
        println("Ignored Tests:    ${result.ignoreCount}")
        println("Execution Time:   ${String.format("%.3f", elapsed)}s")
        println("--------------------------------------------------------------------------------")

        if (result.wasSuccessful()) {
            println("✅ ALL ${result.runCount} ADVERSARIAL CHALLENGES PASSED SUCCESSFULLY! (VERDICT: APPROVE)")
            println("================================================================================")
            exitProcess(0)
        } else {
            println("❌ FAILURES DETECTED IN CHALLENGER 2 PROBES (${result.failureCount} failed):")
            for (failure: Failure in result.failures) {
                println("  - [FAIL] ${failure.testHeader}: ${failure.message}")
                println("    ${failure.trace?.lines()?.take(4)?.joinToString("\n    ")}")
            }
            println("================================================================================")
            exitProcess(1)
        }
    }
}
