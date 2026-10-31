package com.iconstudio.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible trampoline. Home-screen shortcuts point to this activity (inside our own app,
 * which every launcher handles reliably) and it immediately starts the real app.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(ShortcutHelper.EXTRA_PKG).orEmpty()
        val act = intent.getStringExtra(ShortcutHelper.EXTRA_ACTIVITY).orEmpty()
        val target = if (pkg.isNotEmpty()) ShortcutHelper.targetIntent(this, pkg, act) else null
        try {
            if (target == null) throw IllegalStateException("app missing")
            startActivity(target)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_app_missing, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
