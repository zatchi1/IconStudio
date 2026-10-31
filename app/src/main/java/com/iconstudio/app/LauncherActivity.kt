package com.iconstudio.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iconstudio.app.databinding.ActivityLauncherBinding
import java.util.concurrent.Executors

/**
 * zatchi home screen. Shows every installed app; apps with a custom icon/name are drawn with it
 * directly, so no duplicate shortcuts and no badge are involved.
 */
class LauncherActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "launcher"
        private const val KEY_DOCK = "dock"
        private const val DOCK_MAX = 5
        private const val COLUMNS = 5
    }

    private lateinit var b: ActivityLauncherBinding
    private val io = Executors.newSingleThreadExecutor()

    private var apps: List<AppItem> = emptyList()
    private var custom: Map<String, IconEntry> = emptyMap()
    private val bitmapCache = HashMap<String, Pair<Long, Drawable>>()

    private val gridAdapter = AppGridAdapter({ open(it) }, { showMenu(it) })
    private val dockAdapter = AppGridAdapter({ open(it) }, { showMenu(it) })

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Draw behind the status and navigation bars; fitsSystemWindows adds the padding back.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        b.grid.layoutManager = GridLayoutManager(this, COLUMNS)
        b.grid.adapter = gridAdapter
        b.dock.layoutManager = GridLayoutManager(this, COLUMNS)
        b.dock.adapter = dockAdapter

        b.search.doAfterTextChanged { render() }
        b.menuBtn.setOnClickListener { showTopMenu(it) }

        loadApps()
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, f)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
            // not registered
        }
    }

    override fun onResume() {
        super.onResume()
        // The editor may have changed icons while we were in the background.
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while already on the home screen.
        b.search.setText("")
        b.search.clearFocus()
        hideKeyboard()
        b.grid.scrollToPosition(0)
    }

    override fun onBackPressed() {
        // A home screen never closes. Back only clears the search.
        if (b.search.text.isNotEmpty()) b.search.setText("")
        hideKeyboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---------------------------------------------------------------- data

    private fun loadApps() {
        io.execute {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(query, 0)
                .map {
                    val pkg = it.activityInfo.packageName
                    val act = it.activityInfo.name
                    AppItem("$pkg/$act", pkg, act, it.loadLabel(pm).toString(), it.loadIcon(pm))
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps = list
                ensureDefaultDock()
                render()
            }
        }
    }

    private fun refreshCustom() {
        custom = IconStore.get(this).list()
            .filter { it.launcher }
            .reversed()                       // newest wins if there were duplicates
            .associateBy { it.pkg + "/" + it.activity }
    }

    private fun customDrawable(e: IconEntry): Drawable? {
        val cached = bitmapCache[e.id]
        if (cached != null && cached.first == e.updatedAt) return cached.second
        val bmp = BitmapFactory.decodeFile(IconStore.get(this).iconFile(e.id).path) ?: return null
        val d = BitmapDrawable(resources, bmp)
        bitmapCache[e.id] = e.updatedAt to d
        return d
    }

    private fun cellFor(a: AppItem): Cell {
        val e = custom[a.key]
        val d = e?.let { customDrawable(it) }
        return if (e != null && d != null) Cell(a, e.label, d, true) else Cell(a, a.label, a.icon, false)
    }

    private fun render() {
        refreshCustom()
        val q = b.search.text.toString().trim().lowercase()
        val cells = apps.map { cellFor(it) }
        val shown = if (q.isEmpty()) cells else cells.filter {
            it.label.lowercase().contains(q) || it.app.label.lowercase().contains(q)
        }
        gridAdapter.submit(shown)

        val dockKeys = dockKeys()
        val dockCells = dockKeys.mapNotNull { k -> apps.firstOrNull { it.key == k } }
            .take(DOCK_MAX).map { cellFor(it) }
        dockAdapter.submit(dockCells)
        b.dock.visibility = if (dockCells.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- dock

    private fun dockKeys(): List<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DOCK, "").orEmpty()
            .split("\n").filter { it.isNotEmpty() }

    private fun saveDock(keys: List<String>) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_DOCK, keys.joinToString("\n")).apply()
    }

    /** First run: put phone, messages, camera and browser in the dock when they can be found. */
    private fun ensureDefaultDock() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.contains(KEY_DOCK) || apps.isEmpty()) return
        val probes = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        )
        val keys = ArrayList<String>()
        for (p in probes) {
            val pkg = packageManager.resolveActivity(p, 0)?.activityInfo?.packageName ?: continue
            val app = apps.firstOrNull { it.pkg == pkg } ?: continue
            if (!keys.contains(app.key)) keys.add(app.key)
        }
        saveDock(keys)
    }

    private fun toggleDock(app: AppItem) {
        val keys = dockKeys().toMutableList()
        if (keys.contains(app.key)) {
            keys.remove(app.key)
        } else {
            if (keys.size >= DOCK_MAX) {
                Toast.makeText(this, R.string.toast_dock_full, Toast.LENGTH_SHORT).show()
                return
            }
            keys.add(app.key)
        }
        saveDock(keys)
        render()
    }

    // ---------------------------------------------------------------- actions

    private fun open(cell: Cell) {
        val intent = ShortcutHelper.targetIntent(this, cell.app.pkg, cell.app.activity)
        try {
            if (intent == null) throw IllegalStateException("missing")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_app_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(cell: Cell) {
        val app = cell.app
        val inDock = dockKeys().contains(app.key)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.menu_change_icon) to { openEditor(cell) })
        if (cell.custom) {
            actions.add(getString(R.string.menu_reset_icon) to { resetIcon(app) })
        }
        actions.add(
            getString(if (inDock) R.string.menu_remove_dock else R.string.menu_add_dock) to
                { toggleDock(app) }
        )
        actions.add(getString(R.string.menu_app_info) to {
            startSafely(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
            )
        })
        actions.add(getString(R.string.menu_uninstall) to {
            startSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(cell.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun openEditor(cell: Cell) {
        startActivity(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_PKG, cell.app.pkg)
                .putExtra(EditorActivity.EXTRA_ACTIVITY, cell.app.activity)
                .putExtra(EditorActivity.EXTRA_APP_LABEL, cell.app.label)
                .putExtra(EditorActivity.EXTRA_APPLY_MODE, true)
        )
    }

    private fun resetIcon(app: AppItem) {
        val e = custom[app.key] ?: return
        IconStore.get(this).delete(e.id)
        bitmapCache.remove(e.id)
        render()
    }

    private fun showTopMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, R.string.menu_shortcuts_mode)
        pm.menu.add(0, 2, 1, R.string.menu_home_settings)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> startActivity(Intent(this, MainActivity::class.java))
                2 -> startSafely(Intent(Settings.ACTION_HOME_SETTINGS))
            }
            true
        }
        pm.show()
    }

    private fun startSafely(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.search.windowToken, 0)
    }
}
