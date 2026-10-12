package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Rotates the whole display through the system rotation lock.
 *
 * Android 16 ignores orientation requests from apps on large screens, so on unfolded foldables the
 * reader can only rotate its own views. Locking the system rotation instead makes the status bar,
 * notification shade, gestures and every dialog follow the reader orientation too.
 *
 * The rotation settings that were active before locking are persisted so they can be restored
 * even if the process died while the display was locked.
 */
internal class FoldableSystemRotation(
    private val context: Context,
    private val preferences: ReaderPreferences,
) {

    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    /**
     * Locks the display to [rotation], one of the `Surface.ROTATION_*` constants.
     *
     * @return whether the lock was applied.
     */
    fun lock(rotation: Int): Boolean {
        if (!canWriteSettings()) return false
        val resolver = context.contentResolver
        return try {
            if (preferences.foldableSavedSystemRotation.get().isEmpty()) {
                val autoRotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
                val userRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0)
                preferences.foldableSavedSystemRotation.set("$autoRotate,$userRotation")
            }
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(resolver, Settings.System.USER_ROTATION, rotation)
            true
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to lock system rotation" }
            false
        }
    }

    /** Restores the rotation settings saved by [lock], if any. */
    fun restore() {
        val saved = preferences.foldableSavedSystemRotation.get()
        if (saved.isEmpty()) return
        val (autoRotate, userRotation) = saved.split(",").mapNotNull { it.toIntOrNull() }
            .takeIf { it.size == 2 }
            ?: run {
                preferences.foldableSavedSystemRotation.delete()
                return
            }
        if (!canWriteSettings()) return
        try {
            val resolver = context.contentResolver
            Settings.System.putInt(resolver, Settings.System.USER_ROTATION, userRotation)
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, autoRotate)
            preferences.foldableSavedSystemRotation.delete()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to restore system rotation" }
        }
    }

    companion object {
        fun permissionIntent(context: Context): Intent {
            return Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri())
        }
    }
}
