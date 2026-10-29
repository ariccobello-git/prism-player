package dev.red74.prismplayer.device

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/** An app installed on the TV that can be opened from the launcher. */
class InstalledApp(val packageName: String, val label: String, val icon: Drawable?)

/** Lists and opens the apps installed on this device (Android TV launcher apps first, then ordinary ones). */
object InstalledApps {
    fun list(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, InstalledApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            val infos = try { pm.queryIntentActivities(query, 0) } catch (e: Exception) { emptyList() }
            for (info in infos) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || pkg in found) continue
                val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
                found[pkg] = InstalledApp(pkg, label, runCatching { info.loadIcon(pm) }.getOrNull())
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** Opens the app; false if it is gone or cannot be started. */
    fun open(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }
}
