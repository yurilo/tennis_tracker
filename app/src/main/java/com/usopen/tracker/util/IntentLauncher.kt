package com.usopen.tracker.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.DeepLink
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.TennisProfileProvider

sealed class IntentLaunchResult {
    data object LaunchedApp : IntentLaunchResult()
    data object LaunchedWebFallback : IntentLaunchResult()
    data class Failed(val exception: Throwable) : IntentLaunchResult()
}

object IntentLauncher {

    private const val TAG = "IntentLauncher"
    const val ESPN_PACKAGE_NAME = "com.espn.score_center"
    val ESPN_PACKAGE_CANDIDATES = listOf(
        "com.espn.score_center",
        "com.espn.score_center.debug",
        "com.espn.sportscenter"
    )

    const val YOUTUBE_PACKAGE_NAME = "com.google.android.youtube"
    val YOUTUBE_PACKAGE_CANDIDATES = listOf(
        "com.google.android.youtube",
        "com.google.android.youtube.tv"
    )

    val KNOWN_BROWSER_PACKAGES = listOf(
        "com.android.chrome",
        "org.chromium.chrome",
        "org.mozilla.firefox",
        "com.sec.android.app.sbrowser",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.opera.browser",
        "com.google.android.apps.chrome"
    )

    fun launchWatchIntent(context: Context, deepLink: DeepLink): IntentLaunchResult {
        // 1. First attempt to launch into the ESPN Android application if installed
        val espnPkg = findInstalledEspnPackage(context)
        if (espnPkg != null && deepLink.appUri.isNotBlank()) {
            try {
                val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink.appUri)).apply {
                    setPackage(espnPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(appIntent)
                Log.d(TAG, "Successfully launched ESPN app: ${deepLink.appUri} (pkg: $espnPkg)")
                return IntentLaunchResult.LaunchedApp
            } catch (t: Throwable) {
                Log.w(TAG, "ESPN app direct launch failed, falling back to web browser: ${t.message}")
            }
        }

        // 2. Web fallback: open specific match page/stream in verified browser
        val targetUrl = when {
            deepLink.webFallbackUrl.isNotBlank() -> deepLink.webFallbackUrl
            else -> "https://www.espn.com/tennis/scoreboard"
        }

        Log.d(TAG, "Launching watch stream in web browser: $targetUrl")

        return try {
            val uri = Uri.parse(targetUrl)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Only set package if an actual verified web browser is installed.
            // Never set package to generic query results that might resolve to a file manager.
            val installedBrowser = findInstalledBrowserPackage(context)
            if (installedBrowser != null) {
                intent.setPackage(installedBrowser)
                Log.d(TAG, "Targeting verified browser package: $installedBrowser")
            }

            context.startActivity(intent)
            Log.d(TAG, "Successfully launched browser: $targetUrl (pkg: ${intent.`package`})")
            IntentLaunchResult.LaunchedWebFallback
        } catch (t: Throwable) {
            Log.w(TAG, "Browser package launch failed, falling back to generic ACTION_VIEW: ${t.message}")
            try {
                val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(genericIntent)
                IntentLaunchResult.LaunchedWebFallback
            } catch (t2: Throwable) {
                Log.e(TAG, "Failed to launch web browser: ${t2.message}", t2)
                IntentLaunchResult.Failed(t2)
            }
        }
    }

    fun findInstalledBrowserPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in KNOWN_BROWSER_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return null
    }

    fun findInstalledEspnPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in ESPN_PACKAGE_CANDIDATES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return null
    }

    fun findInstalledYouTubePackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in YOUTUBE_PACKAGE_CANDIDATES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return null
    }

    fun launchYouTubeIntent(context: Context, deepLink: DeepLink): IntentLaunchResult {
        // 1. Attempt to launch via YouTube application if installed
        val ytPkg = findInstalledYouTubePackage(context)
        if (ytPkg != null) {
            try {
                val targetUri = if (deepLink.appUri.startsWith("vnd.youtube:")) {
                    Uri.parse(deepLink.appUri)
                } else {
                    Uri.parse(deepLink.webFallbackUrl)
                }
                val appIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                    setPackage(ytPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(appIntent)
                Log.d(TAG, "Successfully launched YouTube app: $targetUri (pkg: $ytPkg)")
                return IntentLaunchResult.LaunchedApp
            } catch (t: Throwable) {
                Log.w(TAG, "YouTube app launch failed, falling back to web browser: ${t.message}")
            }
        }

        // 2. Web fallback: open specific match highlight/search in verified browser
        val targetUrl = when {
            deepLink.webFallbackUrl.isNotBlank() -> deepLink.webFallbackUrl
            else -> "https://www.youtube.com/@usopen"
        }

        Log.d(TAG, "Launching YouTube highlight in web browser: $targetUrl")

        return try {
            val uri = Uri.parse(targetUrl)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val installedBrowser = findInstalledBrowserPackage(context)
            if (installedBrowser != null) {
                intent.setPackage(installedBrowser)
                Log.d(TAG, "Targeting verified browser package: $installedBrowser")
            }

            context.startActivity(intent)
            Log.d(TAG, "Successfully launched browser: $targetUrl (pkg: ${intent.`package`})")
            IntentLaunchResult.LaunchedWebFallback
        } catch (t: Throwable) {
            Log.w(TAG, "Browser package launch failed, falling back to generic ACTION_VIEW: ${t.message}")
            try {
                val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(genericIntent)
                IntentLaunchResult.LaunchedWebFallback
            } catch (t2: Throwable) {
                Log.e(TAG, "Failed to launch YouTube in web browser: ${t2.message}", t2)
                IntentLaunchResult.Failed(t2)
            }
        }
    }

    /**
     * Launches a web URL in a verified browser or system fallback.
     */
    fun launchWebUrl(context: Context, targetUrl: String): IntentLaunchResult {
        Log.d(TAG, "Launching web URL: $targetUrl")
        return try {
            val uri = Uri.parse(targetUrl)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val installedBrowser = findInstalledBrowserPackage(context)
            if (installedBrowser != null) {
                intent.setPackage(installedBrowser)
                Log.d(TAG, "Targeting verified browser package: $installedBrowser")
            }

            context.startActivity(intent)
            Log.d(TAG, "Successfully launched browser: $targetUrl (pkg: ${intent.`package`})")
            IntentLaunchResult.LaunchedWebFallback
        } catch (t: Throwable) {
            Log.w(TAG, "Browser package launch failed, falling back to generic ACTION_VIEW: ${t.message}")
            try {
                val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(genericIntent)
                IntentLaunchResult.LaunchedWebFallback
            } catch (t2: Throwable) {
                Log.e(TAG, "Failed to launch web URL: ${t2.message}", t2)
                IntentLaunchResult.Failed(t2)
            }
        }
    }

    /**
     * Resolves the official ATP or WTA profile for the given competitor and opens it in the browser.
     */
    fun launchPlayerProfile(context: Context, competitor: Competitor, match: Match? = null): IntentLaunchResult {
        val url = TennisProfileProvider.getProfileUrl(competitor, match)
        return launchWebUrl(context, url)
    }
}
