package com.iconstudio.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Creates / updates / disables launcher shortcuts through the official ShortcutManager API
 * (Android 8.0+, so it works on Android 9).
 *
 * Android does not let an app replace another app's real icon. What we do instead is ask the
 * launcher to pin a NEW shortcut that has our image + name and that launches the original app.
 * The system always shows its own confirmation dialog - that is how the user's permission is
 * requested.
 */
object ShortcutHelper {

    enum class Result { REQUESTED, REQUESTED_LEGACY, UPDATED, UNSUPPORTED, FAILED }

    fun launchIntent(ctx: Context, e: IconEntry): Intent? {
        val intent: Intent? = if (e.activity.isNotEmpty()) {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(e.pkg, e.activity))
        } else {
            ctx.packageManager.getLaunchIntentForPackage(e.pkg)?.apply {
                action = Intent.ACTION_MAIN
            }
        }
        return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    private fun isPinned(ctx: Context, id: String): Boolean {
        return try {
            val sm = ctx.getSystemService(ShortcutManager::class.java)
            sm?.pinnedShortcuts?.any { it.id == id } == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * If the shortcut is already on the home screen, update its icon/name in place.
     * Otherwise ask the launcher to pin it (the user confirms in the system dialog).
     */
    fun pinOrUpdate(ctx: Context, e: IconEntry, icon: Bitmap): Result {
        val intent = launchIntent(ctx, e) ?: return Result.FAILED
        val info = ShortcutInfoCompat.Builder(ctx, e.id)
            .setShortLabel(e.label)
            .setLongLabel(e.label)
            .setIcon(IconCompat.createWithBitmap(icon))
            .setIntent(intent)
            .build()

        return try {
            if (isPinned(ctx, e.id)) {
                if (ShortcutManagerCompat.updateShortcuts(ctx, listOf(info))) Result.UPDATED else Result.FAILED
            } else {
                if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
                    Result.UNSUPPORTED
                } else if (ShortcutManagerCompat.requestPinShortcut(ctx, info, null)) {
                    Result.REQUESTED
                } else {
                    Result.FAILED
                }
            }
        } catch (ex: Exception) {
            Result.FAILED
        }
    }

    /**
     * Compatibility mode: the old "INSTALL_SHORTCUT" broadcast. The launcher stores the name,
     * the image and the launch intent itself, so it does not depend on ShortcutManager.
     * Such a shortcut can't be updated or disabled later: editing creates a new one.
     */
    fun pinLegacy(ctx: Context, e: IconEntry, icon: Bitmap): Result {
        val intent = launchIntent(ctx, e) ?: return Result.FAILED
        return try {
            val i = Intent("com.android.launcher.action.INSTALL_SHORTCUT")
                .putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent)
                .putExtra(Intent.EXTRA_SHORTCUT_NAME, e.label)
                .putExtra(Intent.EXTRA_SHORTCUT_ICON, icon)
                .putExtra("duplicate", true)
            ctx.sendBroadcast(i)
            Result.REQUESTED_LEGACY
        } catch (ex: Exception) {
            Result.FAILED
        }
    }

    /**
     * Apps cannot remove a pinned shortcut from the home screen themselves. We disable it, so
     * it stops working, and the user removes the leftover icon by long-pressing it.
     */
    fun disable(ctx: Context, id: String) {
        try {
            ShortcutManagerCompat.disableShortcuts(
                ctx,
                listOf(id),
                ctx.getString(R.string.shortcut_removed_msg)
            )
        } catch (e: Exception) {
            // Nothing else to do: the entry is removed from the app anyway.
        }
    }
}
