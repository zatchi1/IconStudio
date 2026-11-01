package com.iconstudio.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iconstudio.app.databinding.ActivityLauncherBinding
import com.iconstudio.app.databinding.ItemAppBinding
import com.iconstudio.app.databinding.ItemTileBinding
import java.util.concurrent.Executors

/**
 * zatchi home screen: swipeable pages, free placement by drag and drop, resizable app tiles,
 * widgets, a dock and an app drawer. Custom icons/names are drawn directly (no shortcuts, no badge).
 */
class LauncherActivity : AppCompatActivity() {

    companion object {
        private const val DOCK_MAX = 5
        private const val HOST_ID = 1024
        private const val REQUEST_CONFIG = 701
        private const val WIDGET_PREFIX = "widget:"
        private val WIDGET_SIZES = listOf(
            2 to 1, 2 to 2, 3 to 1, 3 to 2, 4 to 1, 4 to 2, 4 to 3, 4 to 4, 5 to 1, 5 to 2, 5 to 3, 5 to 4
        )
    }

    private lateinit var b: ActivityLauncherBinding
    private lateinit var layout: HomeLayout
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var widgetManager: AppWidgetManager
    private val io = Executors.newSingleThreadExecutor()
    private val density by lazy { resources.displayMetrics.density }

    private var apps: List<AppItem> = emptyList()
    private var appByKey: Map<String, AppItem> = emptyMap()
    private var custom: Map<String, IconEntry> = emptyMap()
    private val bitmapCache = HashMap<String, Pair<Long, Drawable>>()

    private lateinit var pageAdapter: PageAdapter
    private val drawerAdapter = AppGridAdapter({ closeDrawer(); open(it) }, { showDrawerMenu(it) })

    private var dragging = false
    private var lastEdgeAt = 0L

    private var pendingEditKey: String? = null
    private var pendingWidgetId = -1
    private var pendingProvider: AppWidgetProviderInfo? = null

    private val editorResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val key = pendingEditKey
            pendingEditKey = null
            if (r.resultCode == RESULT_OK && key != null) {
                val s = r.data?.getIntExtra(EditorActivity.EXTRA_SIZE, 0) ?: 0
                val item = layout.items.firstOrNull { it.key == key }
                if (item != null && s in 1..4 && (item.w != s || item.h != s)) resize(item, s, s)
            }
        }

    private val bindResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) onWidgetBound() else cancelPendingWidget()
        }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // An app being updated is briefly "removed"; ignore that so its place is kept.
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) return
            loadApps()
        }
    }

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(b.root)
        layout = HomeLayout(this)
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, HOST_ID)

        pageAdapter = PageAdapter()
        b.pager.adapter = pageAdapter
        b.pager.offscreenPageLimit = 1
        (b.pager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots()
            }
        })

        b.grid.layoutManager = GridLayoutManager(this, HomeLayout.COLS)
        b.grid.adapter = drawerAdapter

        b.btnSearch.setOnClickListener { openDrawer(true) }
        b.menuBtn.setOnClickListener { showTopMenu(it) }

        // The cursor only blinks while the search field is actually being used.
        b.search.setOnFocusChangeListener { _, focused -> b.search.isCursorVisible = focused }
        b.search.doAfterTextChanged { renderDrawer() }

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
        try {
            widgetHost.startListening()
        } catch (e: Exception) {
            // some devices refuse; widgets then just don't live-update
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
            // not registered
        }
        try {
            widgetHost.stopListening()
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onResume() {
        super.onResume()
        if (apps.isNotEmpty()) render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while already on the home screen.
        closeDrawer()
        b.pager.setCurrentItem(0, true)
    }

    override fun onBackPressed() {
        // A home screen never closes. Back only closes the drawer.
        if (isDrawerOpen()) closeDrawer()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CONFIG) {
            if (resultCode == RESULT_OK) finishWidgetAdd() else cancelPendingWidget()
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun dp(v: Int) = (v * density).toInt()

    // ================================================================ data

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
                appByKey = list.associateBy { it.key }
                sync()
                render()
            }
        }
    }

    /** Keeps the saved layout in step with what is installed. */
    private fun sync() {
        if (apps.isEmpty()) return
        val keys = apps.map { it.key }.toSet()
        layout.items.removeAll { !it.key.startsWith(WIDGET_PREFIX) && it.key !in keys }
        layout.dock.removeAll { it !in keys }
        if (!layout.initialized) {
            layout.initialized = true
            defaultDock()
        }
        for (a in apps) {
            // Newly seen apps go on the home screen; apps the user removed from it stay off.
            if (layout.known.add(a.key) && !layout.isPlaced(a.key) && !layout.dock.contains(a.key)) {
                layout.addAuto(a.key)
            }
        }
        layout.save()
    }

    /** First run: phone, messages, camera and browser go to the dock when they can be found. */
    private fun defaultDock() {
        val probes = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        )
        for (p in probes) {
            val pkg = packageManager.resolveActivity(p, 0)?.activityInfo?.packageName ?: continue
            val app = apps.firstOrNull { it.pkg == pkg } ?: continue
            if (!layout.dock.contains(app.key) && layout.dock.size < DOCK_MAX) layout.dock.add(app.key)
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
        return if (e != null && d != null) Cell(a, e.label, d, true, e.showLabel) else Cell(a, a.label, a.icon, false)
    }

    private fun render() {
        refreshCustom()
        pageAdapter.notifyDataSetChanged()
        updateDots()
        renderDock()
        renderDrawer()
    }

    // ================================================================ pages

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {

        inner class VH(val grid: HomeGridView) : RecyclerView.ViewHolder(grid)

        override fun getItemCount(): Int = layout.pages

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val g = HomeGridView(parent.context)
            g.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            g.setPadding(dp(6), dp(2), dp(6), dp(2))
            g.onDropItem = { item, page, col, row -> onDrop(item, page, col, row) }
            g.onEdge = { dir -> onEdge(dir) }
            g.onDragEnded = { onDragEnded() }
            return VH(g)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.grid.pageIndex = position
            holder.grid.removeAllViews()
            for (item in layout.items.filter { it.page == position }) {
                val v = makeView(item) ?: continue
                holder.grid.addView(v, HomeGridView.LP(item.col, item.row, item.w, item.h))
            }
        }
    }

    private fun updateDots() {
        val d = b.dots
        d.removeAllViews()
        if (layout.pages <= 1) return
        val cur = b.pager.currentItem
        for (i in 0 until layout.pages) {
            val dot: View
            if (i == 0) {
                // The main page is marked with a little house, like the original home screen.
                dot = ImageView(this).apply {
                    setImageResource(R.drawable.ic_home_dot)
                    alpha = if (i == cur) 1f else 0.4f
                }
            } else {
                dot = View(this)
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == cur) Color.WHITE else 0x66FFFFFF)
                }
            }
            val size = if (i == 0) dp(11) else dp(7)
            d.addView(dot, LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
    }

    // ================================================================ item views

    private fun makeView(item: Placed): View? {
        if (item.key.startsWith(WIDGET_PREFIX)) return makeWidgetView(item)
        val app = appByKey[item.key] ?: return null
        val cell = cellFor(app)
        return if (item.w == 1 && item.h == 1) {
            val vb = ItemAppBinding.inflate(layoutInflater)
            bindIcon(vb, cell)
            fitIcon(vb)
            vb.root.setOnClickListener { open(cell) }
            vb.root.setOnLongClickListener { startDrag(it, item, vb.icon) }
            vb.root
        } else {
            val tb = ItemTileBinding.inflate(layoutInflater)
            tb.image.setImageDrawable(cell.icon)
            if (cell.custom) {
                tb.image.scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                tb.image.scaleType = ImageView.ScaleType.FIT_CENTER
                tb.image.setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            tb.tile.outlineProvider = TileOutline
            tb.tile.clipToOutline = true
            tb.label.text = cell.label
            tb.label.visibility = if (cell.showLabel) View.VISIBLE else View.GONE
            tb.tile.setOnClickListener { open(cell) }
            tb.tile.setOnLongClickListener { startDrag(tb.root, item, tb.tile) }
            tb.root
        }
    }

    /**
     * Sizes the icon from the real cell size so the icon and its name always fit, whatever the
     * screen, instead of using a fixed size that can push the name out of the cell.
     */
    private fun fitIcon(vb: ItemAppBinding) {
        vb.root.addOnLayoutChangeListener { _, l, t, r, bt, _, _, _, _ ->
            val w = r - l
            val h = bt - t
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val reserved = if (vb.label.visibility == View.VISIBLE) dp(24) else dp(8)   // name line + gaps
            val s = minOf((w * 0.70f).toInt(), h - reserved).coerceAtLeast(dp(28))
            val lp = vb.icon.layoutParams
            if (lp.width != s || lp.height != s) {
                lp.width = s
                lp.height = s
                vb.icon.layoutParams = lp
            }
        }
    }

    private fun bindIcon(vb: ItemAppBinding, cell: Cell) {
        vb.icon.setImageDrawable(cell.icon)
        vb.icon.outlineProvider = IconOutline
        vb.icon.clipToOutline = cell.custom
        vb.label.text = cell.label
        vb.label.visibility = if (cell.showLabel) View.VISIBLE else View.GONE
    }

    private fun renderDock() {
        val dock = b.dock
        dock.removeAllViews()
        val cells = layout.dock.mapNotNull { appByKey[it] }.take(DOCK_MAX).map { cellFor(it) }
        for (cell in cells) {
            val vb = ItemAppBinding.inflate(layoutInflater, dock, false)
            bindIcon(vb, cell)
            vb.label.visibility = View.GONE
            vb.root.setOnClickListener { open(cell) }
            vb.root.setOnLongClickListener { showDockMenu(cell); true }
            // Equal share of the full width: spread out like the original home screen.
            dock.addView(vb.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        dock.visibility = if (cells.isEmpty()) View.GONE else View.VISIBLE
    }

    // ================================================================ drag and drop

    /** [shadowOf] is what follows the finger: just the icon/tile, without any frame around it. */
    private fun startDrag(v: View, item: Placed, shadowOf: View = v): Boolean {
        dragging = true
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val ok = v.startDragAndDrop(
            ClipData.newPlainText("item", item.key), View.DragShadowBuilder(shadowOf), item, 0
        )
        if (ok) v.alpha = 0.3f else dragging = false
        return ok
    }

    private fun onEdge(dir: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeAt < 800) return
        val target = b.pager.currentItem + dir
        if (target < 0) return
        if (target >= layout.pages) {
            layout.pages++
            layout.save()
            pageAdapter.notifyItemInserted(layout.pages - 1)
        }
        lastEdgeAt = now
        b.pager.setCurrentItem(target, true)
    }

    private fun onDrop(item: Placed, page: Int, col: Int, row: Int) {
        if (item.page == page && item.col == col && item.row == row) {
            // Picked up and put back in the same place: show the item's menu.
            showItemMenu(item)
            return
        }
        if (layout.fits(page, col, row, item.w, item.h, item)) {
            item.page = page; item.col = col; item.row = row
        } else {
            val other = layout.placedAt(page, col, row, item)
            if (item.w == 1 && item.h == 1 && other != null && other.w == 1 && other.h == 1) {
                // Dropped on another small icon: swap them.
                other.page = item.page; other.col = item.col; other.row = item.row
                item.page = page; item.col = col; item.row = row
            } else {
                toast(R.string.toast_no_room)
            }
        }
        layout.save()
    }

    private fun onDragEnded() {
        if (!dragging) return
        dragging = false
        pageAdapter.notifyDataSetChanged()
        updateDots()
    }

    // ================================================================ menus

    private fun showActions(title: String, actions: List<Pair<String, () -> Unit>>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun showItemMenu(item: Placed) {
        if (item.key.startsWith(WIDGET_PREFIX)) {
            showWidgetMenu(item)
            return
        }
        val app = appByKey[item.key] ?: return
        val cell = cellFor(app)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.menu_change_icon) to { openEditor(cell, if (item.w == item.h) item.w else 1) })
        if (cell.custom) actions.add(getString(R.string.menu_reset_icon) to { resetIcon(app) })
        actions.add(getString(R.string.menu_size) to { showSizeDialog(item, false) })
        actions.add(getString(R.string.menu_add_dock) to { moveToDock(app.key) })
        actions.add(getString(R.string.menu_remove_home) to { removeFromHome(app.key) })
        actions.add(getString(R.string.menu_app_info) to { appInfo(app) })
        actions.add(getString(R.string.menu_uninstall) to { uninstall(app) })
        showActions(cell.label, actions)
    }

    private fun showDockMenu(cell: Cell) {
        val app = cell.app
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.menu_change_icon) to { openEditor(cell, 0) })
        if (cell.custom) actions.add(getString(R.string.menu_reset_icon) to { resetIcon(app) })
        actions.add(getString(R.string.menu_to_home) to { moveToHome(app.key) })
        actions.add(getString(R.string.menu_app_info) to { appInfo(app) })
        actions.add(getString(R.string.menu_uninstall) to { uninstall(app) })
        showActions(cell.label, actions)
    }

    private fun showDrawerMenu(cell: Cell) {
        val app = cell.app
        val onHome = layout.isPlaced(app.key)
        val inDock = layout.dock.contains(app.key)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.menu_change_icon) to { closeDrawer(); openEditor(cell, 0) })
        if (cell.custom) actions.add(getString(R.string.menu_reset_icon) to { resetIcon(app) })
        if (!onHome) actions.add(getString(R.string.menu_add_home) to { closeDrawer(); moveToHome(app.key) })
        if (!inDock) actions.add(getString(R.string.menu_add_dock) to { moveToDock(app.key) })
        actions.add(getString(R.string.menu_app_info) to { appInfo(app) })
        actions.add(getString(R.string.menu_uninstall) to { uninstall(app) })
        showActions(cell.label, actions)
    }

    private fun showWidgetMenu(item: Placed) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.menu_size) to { showSizeDialog(item, true) })
        actions.add(getString(R.string.menu_remove_widget) to { removeWidget(item) })
        showActions(getString(R.string.title_widgets), actions)
    }

    private fun showSizeDialog(item: Placed, widget: Boolean) {
        val sizes = if (widget) WIDGET_SIZES else listOf(1 to 1, 2 to 2, 3 to 3, 4 to 4)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_size)
            .setItems(sizes.map { "${it.first}×${it.second}" }.toTypedArray()) { _, i ->
                resize(item, sizes[i].first, sizes[i].second)
            }
            .show()
    }

    private fun showTopMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, R.string.menu_add_widget)
        pm.menu.add(0, 2, 1, R.string.menu_add_page_before)
        pm.menu.add(0, 6, 2, R.string.menu_add_page_after)
        pm.menu.add(0, 3, 3, R.string.menu_delete_page)
        pm.menu.add(0, 7, 4, R.string.menu_all_apps)
        pm.menu.add(0, 4, 5, R.string.menu_shortcuts_mode)
        pm.menu.add(0, 5, 6, R.string.menu_home_settings)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showWidgetPicker()
                2 -> addPageAt(true)
                6 -> addPageAt(false)
                3 -> deleteCurrentPage()
                7 -> openDrawer(false)
                4 -> startActivity(Intent(this, MainActivity::class.java))
                5 -> startSafely(Intent(Settings.ACTION_HOME_SETTINGS))
            }
            true
        }
        pm.show()
    }

    // ================================================================ actions

    private fun open(cell: Cell) {
        val intent = ShortcutHelper.targetIntent(this, cell.app.pkg, cell.app.activity)
        try {
            if (intent == null) throw IllegalStateException("missing")
            startActivity(intent)
        } catch (e: Exception) {
            toast(R.string.toast_app_missing)
        }
    }

    /** [size] is the tile size (1..4) shown in the editor, or 0 to hide the size picker. */
    private fun openEditor(cell: Cell, size: Int) {
        pendingEditKey = cell.app.key
        editorResult.launch(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_PKG, cell.app.pkg)
                .putExtra(EditorActivity.EXTRA_ACTIVITY, cell.app.activity)
                .putExtra(EditorActivity.EXTRA_APP_LABEL, cell.app.label)
                .putExtra(EditorActivity.EXTRA_APPLY_MODE, true)
                .putExtra(EditorActivity.EXTRA_SIZE, size)
        )
    }

    private fun resetIcon(app: AppItem) {
        val e = custom[app.key] ?: return
        IconStore.get(this).delete(e.id)
        bitmapCache.remove(e.id)
        render()
    }

    private fun resize(item: Placed, w: Int, h: Int) {
        var col = item.col.coerceAtMost(HomeLayout.COLS - w)
        var row = item.row.coerceAtMost(HomeLayout.ROWS - h)
        if (!layout.fits(item.page, col, row, w, h, item)) {
            val free = layout.findFree(item.page, w, h, item)
            if (free == null) {
                toast(R.string.toast_no_room)
                return
            }
            col = free.first
            row = free.second
        }
        item.col = col; item.row = row; item.w = w; item.h = h
        layout.save()
        pageAdapter.notifyDataSetChanged()
    }

    private fun moveToDock(key: String) {
        if (layout.dock.contains(key)) return
        if (layout.dock.size >= DOCK_MAX) {
            toast(R.string.toast_dock_full)
            return
        }
        layout.items.removeAll { it.key == key }
        layout.dock.add(key)
        layout.save()
        render()
    }

    private fun moveToHome(key: String) {
        layout.dock.remove(key)
        if (!layout.isPlaced(key)) layout.addAuto(key, b.pager.currentItem)
        layout.save()
        render()
    }

    private fun removeFromHome(key: String) {
        layout.items.removeAll { it.key == key }
        layout.save()
        render()
    }

    /** Adds an empty page right before or right after the current one and goes to it. */
    private fun addPageAt(before: Boolean) {
        val at = if (before) b.pager.currentItem else b.pager.currentItem + 1
        layout.insertPage(at)
        layout.save()
        pageAdapter.notifyDataSetChanged()
        b.pager.setCurrentItem(at, false)
        updateDots()
    }

    private fun deleteCurrentPage() {
        val p = b.pager.currentItem
        if (layout.pages <= 1 || !layout.pageIsEmpty(p)) {
            toast(R.string.toast_page_not_empty)
            return
        }
        layout.removePage(p)
        layout.save()
        render()
    }

    private fun appInfo(app: AppItem) =
        startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))

    private fun uninstall(app: AppItem) =
        startSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))

    private fun startSafely(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast(R.string.toast_failed)
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    // ================================================================ drawer

    private fun isDrawerOpen() = b.drawer.visibility == View.VISIBLE

    private fun openDrawer(focusSearch: Boolean) {
        b.search.setText("")
        b.drawer.visibility = View.VISIBLE
        b.grid.scrollToPosition(0)
        renderDrawer()
        if (focusSearch) {
            b.search.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(b.search, InputMethodManager.SHOW_IMPLICIT)
        } else {
            // Take focus away from the search field so no cursor blinks.
            b.drawer.requestFocus()
            hideKeyboard()
        }
    }

    private fun closeDrawer() {
        if (!isDrawerOpen()) return
        b.drawer.visibility = View.GONE
        b.search.clearFocus()
        hideKeyboard()
    }

    private fun renderDrawer() {
        val q = b.search.text.toString().trim().lowercase()
        val cells = apps.map { cellFor(it) }
        val shown = if (q.isEmpty()) cells else cells.filter {
            it.label.lowercase().contains(q) || it.app.label.lowercase().contains(q)
        }
        drawerAdapter.submit(shown)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.search.windowToken, 0)
    }

    // ================================================================ widgets

    private fun cellPx(): Pair<Int, Int> {
        val cw = ((b.pager.width - dp(12)) / HomeLayout.COLS).coerceAtLeast(dp(40))
        val ch = ((b.pager.height - dp(4)) / HomeLayout.ROWS).coerceAtLeast(dp(40))
        return cw to ch
    }

    private fun widgetCells(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val (cw, ch) = cellPx()
        val w = ((info.minWidth + cw - 1) / cw).coerceIn(1, HomeLayout.COLS)
        val h = ((info.minHeight + ch - 1) / ch).coerceIn(1, HomeLayout.ROWS)
        return w to h
    }

    private fun showWidgetPicker() {
        io.execute {
            val pm = packageManager
            val options = try {
                widgetManager.installedProviders.map { info ->
                    val appLabel = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
                    } catch (e: Exception) {
                        info.provider.packageName
                    }
                    val (w, h) = widgetCells(info)
                    WidgetOption(
                        info, appLabel, info.loadLabel(pm) ?: appLabel, "${w}×${h}",
                        info.loadPreviewImage(this, 0) ?: info.loadIcon(this, 0)
                    )
                }.sortedWith(compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() }))
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (options.isEmpty()) {
                    toast(R.string.toast_widget_failed)
                    return@runOnUiThread
                }
                val list = RecyclerView(this)
                list.layoutManager = LinearLayoutManager(this)
                var dialog: androidx.appcompat.app.AlertDialog? = null
                list.adapter = WidgetAdapter(options) {
                    dialog?.dismiss()
                    addWidget(it.info)
                }
                list.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(440))
                dialog = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.title_widgets)
                    .setView(list)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        pendingProvider = info
        val allowed = try {
            widgetManager.bindAppWidgetIdIfAllowed(id, info.provider)
        } catch (e: Exception) {
            false
        }
        if (allowed) {
            onWidgetBound()
        } else {
            // First time: the system asks the user to allow zatchi to host widgets.
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            try {
                bindResult.launch(i)
            } catch (e: Exception) {
                cancelPendingWidget()
                toast(R.string.toast_widget_failed)
            }
        }
    }

    private fun onWidgetBound() {
        val info = pendingProvider
        val id = pendingWidgetId
        if (info?.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIG, null)
            } catch (e: Exception) {
                finishWidgetAdd()
            }
        } else {
            finishWidgetAdd()
        }
    }

    private fun finishWidgetAdd() {
        val info = pendingProvider
        val id = pendingWidgetId
        pendingProvider = null
        pendingWidgetId = -1
        if (info == null || id == -1) return
        val (w, h) = widgetCells(info)
        val start = b.pager.currentItem
        var placed: Placed? = null
        for (p in (start until layout.pages) + (0 until start)) {
            val free = layout.findFree(p, w, h) ?: continue
            placed = Placed(WIDGET_PREFIX + id, p, free.first, free.second, w, h)
            break
        }
        if (placed == null) {
            layout.pages++
            placed = Placed(WIDGET_PREFIX + id, layout.pages - 1, 0, 0, w, h)
        }
        layout.items.add(placed)
        layout.save()
        pageAdapter.notifyDataSetChanged()
        b.pager.setCurrentItem(placed.page, false)
        updateDots()
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId != -1) {
            try {
                widgetHost.deleteAppWidgetId(pendingWidgetId)
            } catch (e: Exception) {
                // ignore
            }
        }
        pendingWidgetId = -1
        pendingProvider = null
    }

    private fun removeWidget(item: Placed) {
        val id = item.key.removePrefix(WIDGET_PREFIX).toIntOrNull()
        if (id != null) {
            try {
                widgetHost.deleteAppWidgetId(id)
            } catch (e: Exception) {
                // ignore
            }
        }
        layout.items.remove(item)
        layout.save()
        pageAdapter.notifyDataSetChanged()
    }

    private fun makeWidgetView(item: Placed): View? {
        val id = item.key.removePrefix(WIDGET_PREFIX).toIntOrNull() ?: return null
        val info = widgetManager.getAppWidgetInfo(id)
        val frame = WidgetFrame(this)
        frame.setPadding(dp(4), dp(4), dp(4), dp(4))
        frame.onLongPress = { startDrag(frame, item) }
        if (info == null) {
            val t = TextView(this)
            t.setText(R.string.widget_unavailable)
            t.setTextColor(Color.WHITE)
            t.gravity = Gravity.CENTER
            t.setBackgroundResource(R.drawable.bg_tile)
            frame.addView(t, FrameLayoutParams.match())
            return frame
        }
        // Application context: the AppCompat activity context breaks widget inflation.
        val hv = widgetHost.createView(applicationContext, id, info)
        frame.addView(hv, FrameLayoutParams.match())
        // Tell the widget how much room it really has, so it picks a matching layout.
        frame.post {
            val wDp = (frame.width / density).toInt()
            val hDp = (frame.height / density).toInt()
            if (wDp > 0 && hDp > 0) hv.updateAppWidgetSize(Bundle(), wDp, hDp, wDp, hDp)
        }
        return frame
    }

    private object FrameLayoutParams {
        fun match() = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}
