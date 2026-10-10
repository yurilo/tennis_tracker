package com.usopen.tracker.challenger

import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import kotlin.system.exitProcess

object Challenger2Runner {
    @JvmStatic
    fun main(args: Array<String>) {
        println("================================================================================")
        println("⚔️  CHALLENGER 2: ADVERSARIAL STRESS TEST & EMPIRICAL PROBE RUNNER")
        println("================================================================================")

        val suites = arrayOf(
            ScoreboardRepositoryConcurrencyTest::class.java,
            DualDrawFilterPermutationsTest::class.java,
            CacheFreshnessAndConsistencyTest::class.java
        )

        val jUnitCore = JUnitCore()
        val result = jUnitCore.run(*suites)

        println("--------------------------------------------------------------------------------")
        println("CHALLENGER 2 EXECUTION SUMMARY")
        println("--------------------------------------------------------------------------------")
        println("Total Tests Run:  ${result.runCount}")
        println("Successful Tests: ${result.runCount - result.failureCount}")
        println("Failures:         ${result.failureCount}")
        println("Ignored Tests:    ${result.ignoreCount}")
        println("Execution Time:   ${result.runTime}ms")
        println("--------------------------------------------------------------------------------")

        if (result.wasSuccessful()) {
            println("✅ ALL CHALLENGER 2 STRESS TESTS PASSED SUCCESSFULLY!")
        } else {
            println("❌ FAILURES DETECTED IN CHALLENGER 2 PROBES:")
            for (failure: Failure in result.failures) {
                println("  - ${failure.testHeader}: ${failure.message}")
                failure.exception?.printStackTrace()
            }
        }
        println("================================================================================")

        exitProcess(if (result.wasSuccessful()) 0 else 1)
    }
}
