package com.iconstudio.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.iconstudio.app.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = RowAdapter(
        onClick = { openEditor(it) },
        onDelete = { confirmDelete(it) }
    )
    private val io = Executors.newSingleThreadExecutor()

    private var apps: List<Row> = emptyList()
    private var mine: List<Row> = emptyList()
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        edgeToEdge(b.root)

        b.toolbar.inflateMenu(R.menu.main_menu)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_about -> { showAbout(); true }
                R.id.action_home -> { openHomeSettings(); true }
                else -> false
            }
        }

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.tabs.addTab(b.tabs.newTab().setText(R.string.tab_apps))
        b.tabs.addTab(b.tabs.newTab().setText(R.string.tab_mine))
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position
                render()
            }

            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        b.search.doAfterTextChanged { render() }

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        loadMine()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun loadApps() {
        io.execute {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val rows = pm.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    Row(
                        title = it.loadLabel(pm).toString(),
                        subtitle = it.activityInfo.packageName,
                        icon = it.loadIcon(pm),
                        pkg = it.activityInfo.packageName,
                        activity = it.activityInfo.name,
                        entryId = null
                    )
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps = rows
                render()
            }
        }
    }

    private fun loadMine() {
        val store = IconStore.get(this)
        mine = store.list().filter { !it.launcher }.map { e ->
            val bmp = BitmapFactory.decodeFile(store.iconFile(e.id).path)
            Row(
                title = e.label,
                subtitle = e.appLabel,
                icon = bmp?.let { BitmapDrawable(resources, it) },
                pkg = e.pkg,
                activity = e.activity,
                entryId = e.id
            )
        }
    }

    private fun render() {
        val q = b.search.text.toString().trim().lowercase()
        val base = if (tab == 0) apps else mine
        val filtered = if (q.isEmpty()) base else base.filter {
            it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
        }
        adapter.showDelete = tab == 1
        adapter.submit(filtered)

        if (filtered.isEmpty()) {
            b.empty.visibility = View.VISIBLE
            b.empty.setText(
                when {
                    q.isNotEmpty() -> R.string.empty_search
                    tab == 0 -> R.string.empty_apps
                    else -> R.string.empty_mine
                }
            )
        } else {
            b.empty.visibility = View.GONE
        }
    }

    private fun openEditor(row: Row) {
        val i = Intent(this, EditorActivity::class.java)
        if (row.entryId != null) {
            i.putExtra(EditorActivity.EXTRA_ENTRY_ID, row.entryId)
        } else {
            i.putExtra(EditorActivity.EXTRA_PKG, row.pkg)
            i.putExtra(EditorActivity.EXTRA_ACTIVITY, row.activity)
            i.putExtra(EditorActivity.EXTRA_APP_LABEL, row.title)
        }
        startActivity(i)
    }

    private fun confirmDelete(row: Row) {
        val id = row.entryId ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(R.string.dialog_delete_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                ShortcutHelper.disable(this, id)
                IconStore.get(this).delete(id)
                loadMine()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (e2: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
