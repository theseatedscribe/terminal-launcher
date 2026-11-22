package com.terminallauncher.android11

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class InstalledApp(
    val label: String,
    val packageName: String
)

class AppIndexManager(private val context: Context) {

    @Volatile
    private var cachedApps: List<InstalledApp> = emptyList()

    init {
        refreshSync()
    }

    fun refreshAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            refreshSync()
        }
    }

    fun refreshSync() {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        // Requires QUERY_ALL_PACKAGES in AndroidManifest.xml on Android 11 (API 30)
        val resolved = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        cachedApps = resolved
            .map { info ->
                InstalledApp(
                    label = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun allApps(): List<InstalledApp> = cachedApps

    fun findBestMatch(query: String): InstalledApp? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        return cachedApps.firstOrNull { it.label.lowercase() == q }
            ?: cachedApps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: cachedApps.firstOrNull { it.label.lowercase().contains(q) }
    }

    fun launch(app: InstalledApp): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }
}
