package com.iconstudio.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.app.Dialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Window
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iconstudio.app.databinding.ActivityLauncherBinding
import com.iconstudio.app.databinding.ItemAppBinding
import com.iconstudio.app.databinding.ItemFolderBinding
import com.iconstudio.app.databinding.ItemTileBinding
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * zatchi home screen: a 2D grid of pages (swipe left/right inside a row, up/down between rows),
 * free placement by drag and drop, free item sizes, folders, widgets, a dock, an app drawer,
 * and locked / hidden apps. Custom icons and names are drawn directly (no shortcuts, no badge).
 */
class LauncherActivity : AppCompatActivity() {

    companion object {
        private const val DOCK_MAX = 5
        private const val HOST_ID = 1024
        private const val REQUEST_CONFIG = 701
        private const val WIDGET_PREFIX = "widget:"
        private const val FOLDER_PREFIX = "folder:"
        private const val EDGE_DWELL_MS = 700L
        private const val EDGE_REPEAT_MS = 900L
    }

    private lateinit var b: ActivityLauncherBinding
    private lateinit var layout: HomeLayout
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var widgetManager: AppWidgetManager
    private val io = Executors.newSingleThreadExecutor()
    private val density by lazy { resources.displayMetrics.density }
    private val handler = Handler(Looper.getMainLooper())

    private var apps: List<AppItem> = emptyList()
    private var appByKey: Map<String, AppItem> = emptyMap()
    private var custom: Map<String, IconEntry> = emptyMap()
    private val bitmapCache = HashMap<String, Pair<Long, Drawable>>()

    private lateinit var outerAdapter: OuterAdapter
    private val rowCol = HashMap<Int, Int>()     // row index -> column currently shown in it
    private var openedMain = false

    /**
     * One icon size for the whole launcher (home pages, dock, folders, app drawer). It depends only
     * on the screen width, so icons never change size when something else on the screen changes.
     */
    private val iconPx: Int by lazy {
        val cellW = (resources.displayMetrics.widthPixels - dp(12)) / HomeLayout.COLS
        (cellW * 0.72f).toInt().coerceIn(dp(44), dp(62))
    }

    private val drawerAdapter = AppGridAdapter(
        { closeDrawer(); open(it) },
        { showDrawerMenu(it) },
        { iconPx }
    )

    private class Snap(val item: Placed, val page: Int, val col: Int, val row: Int)

    private val grids = HashSet<HomeGridView>()
    private var snapshot: List<Snap> = emptyList()
    private var dropped = false
    private var previewActive = false
    private var hoverKey = ""
    private var hoverItem: Placed? = null
    private var hoverPage = 0
    private var hoverCol = 0
    private var hoverRow = 0
    private var hoverCentre = false
    private var hoverTarget: View? = null
    private val hoverRunnable = Runnable { applyHover() }

    private var dragging = false
    private var edgeDx = 0
    private var edgeDy = 0
    private val edgeRunnable = Runnable { edgeNavigate() }

    /**
     * The widget being edited (frame + handles shown). It is STATE, not a view: pages are rebuilt
     * often (resume, drag end, package changes...), and every rebuild re-attaches the frame, so
     * it can only end when the user finishes or cancels the edit.
     */
    private var editKey: String? = null
    /** A widget picked up for a page change: edit mode comes back once the drop is over. */
    private var pendingReselect: String? = null
    private val prefs by lazy { getSharedPreferences("launcher_ui", Context.MODE_PRIVATE) }

    private var pendingEditKey: String? = null
    private var pendingWidgetId = -1
    private var pendingProvider: AppWidgetProviderInfo? = null

    private val editorResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val key = pendingEditKey
            pendingEditKey = null
            if (r.resultCode == RESULT_OK && key != null) {
                val w = r.data?.getIntExtra(EditorActivity.EXTRA_W, 0) ?: 0
                val h = r.data?.getIntExtra(EditorActivity.EXTRA_H, 0) ?: 0
                val item = layout.items.firstOrNull { it.key == key }
                if (item != null && w >= 1 && h >= 1 && (item.w != w || item.h != h)) resize(item, w, h)
            }
        }

    private val exportResult =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) exportBackup(uri)
        }

    private val importResult =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importBackup(uri)
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
        edgeToEdge(b.content, b.drawer)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // A home screen never closes. Back only closes the drawer.
            override fun handleOnBackPressed() {
                if (editKey != null) endWidgetEdit()
                else if (isDrawerOpen()) closeDrawer()
            }
        })
        layout = HomeLayout(this)
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, HOST_ID)

        outerAdapter = OuterAdapter()
        b.pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        b.pager.adapter = outerAdapter
        b.pager.offscreenPageLimit = 1
        (b.pager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots()
            }
        })

        b.grid.layoutManager = GridLayoutManager(this, HomeLayout.COLS)
        b.grid.adapter = drawerAdapter

        b.dock.setOnDragListener { _, e -> onDockDrag(e) }
        // The strip with the page dots sits right above the dock: dropping there counts as dropping
        // on the dock, so moving an app down from the pages is easy to hit.
        b.dots.setOnDragListener { _, e -> onDockDrag(e) }
        b.dots.setOnLongClickListener { showPagesMenu(); true }
        b.vdots.setOnLongClickListener { showPagesMenu(); true }
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
        ContextCompat.registerReceiver(this, packageReceiver, f, ContextCompat.RECEIVER_EXPORTED)
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
        // Home button pressed while already on the home screen: go to the main page.
        endWidgetEdit()
        closeDrawer()
        goToPage(layout.mainPage, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
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

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

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

    private fun isApp(key: String) = !key.startsWith(WIDGET_PREFIX) && !key.startsWith(FOLDER_PREFIX)

    private fun visibleApps() = apps.filter { it.key !in layout.hidden }

    /** Keeps the saved layout in step with what is installed. */
    private fun sync() {
        if (apps.isEmpty()) return
        val keys = apps.map { it.key }.toSet()
        layout.items.removeAll { isApp(it.key) && it.key !in keys }
        layout.dock.removeAll { it !in keys }
        layout.hidden.removeAll { it !in keys }
        layout.locked.removeAll { it !in keys }
        for (f in layout.folders.values) f.keys.removeAll { it !in keys }
        layout.items.removeAll {
            it.key.startsWith(FOLDER_PREFIX) && !layout.folders.containsKey(it.key.removePrefix(FOLDER_PREFIX))
        }
        cleanFolders()
        stashHiddenWidgets()
        val first = !layout.initialized
        if (first) {
            layout.initialized = true
            defaultDock()
        }
        for (a in apps) {
            // Newly seen apps go on the home screen; apps the user removed from it stay off.
            if (layout.known.add(a.key) && a.key !in layout.hidden &&
                !layout.isPlaced(a.key) && !layout.dock.contains(a.key)
            ) {
                if (first) layout.addAuto(a.key, layout.mainPage, 0)   // first run: fill pages
                else placeIfRoom(a.key)                                  // later: never add pages by itself
            }
        }
        layout.save()
    }

    /** Puts a 1x1 app on the first page with a free cell. Returns false when every page is full. */
    private fun placeIfRoom(key: String, startPage: Int = layout.mainPage): Boolean {
        val pages = layout.allPages()
        val start = pages.indexOf(startPage).coerceAtLeast(0)
        for (p in pages.drop(start) + pages.take(start)) {
            val free = layout.findFree(p, 1, 1) ?: continue
            layout.items.add(Placed(key, p, free.first, free.second))
            return true
        }
        return false
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
        refreshPages()
        renderDock()
        renderDrawer()
        if (!openedMain && apps.isNotEmpty()) {
            openedMain = true
            goToPage(layout.mainPage, false)
        }
        updateDots()
    }

    private fun refreshPages() {
        resizeOverlay = null
        outerAdapter.notifyDataSetChanged()
    }

    // ================================================================ pages (2D grid)

    private fun curRow(): Int = b.pager.currentItem.coerceIn(0, layout.grid.size - 1)

    private fun colInRow(row: Int): Int {
        val pages = layout.grid.getOrNull(row) ?: return 0
        val c = rowCol[row] ?: pages.indexOf(layout.mainPage).coerceAtLeast(0)
        return c.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    }

    private fun curPageId(): Int {
        val row = curRow()
        return layout.grid[row].getOrNull(colInRow(row)) ?: layout.mainPage
    }

    private fun innerPager(row: Int): ViewPager2? {
        val rv = b.pager.getChildAt(0) as? RecyclerView ?: return null
        return (rv.findViewHolderForAdapterPosition(row) as? OuterAdapter.VH)?.pager
    }

    private fun goToPage(id: Int, smooth: Boolean) {
        val (r, c) = layout.locate(id) ?: return
        rowCol[r] = c
        innerPager(r)?.setCurrentItem(c, smooth)
        b.pager.setCurrentItem(r, smooth)
        updateDots()
    }

    private inner class OuterAdapter : RecyclerView.Adapter<OuterAdapter.VH>() {

        inner class VH(val pager: ViewPager2) : RecyclerView.ViewHolder(pager) {
            var callback: ViewPager2.OnPageChangeCallback? = null
        }

        override fun getItemCount(): Int = layout.grid.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val p = ViewPager2(parent.context)
            p.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            p.isSaveEnabled = false
            p.offscreenPageLimit = 1
            (p.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
            return VH(p)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.callback?.let { holder.pager.unregisterOnPageChangeCallback(it) }
            holder.pager.adapter = InnerAdapter(position)
            holder.pager.setCurrentItem(colInRow(position), false)
            val cb = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(pos: Int) {
                    rowCol[position] = pos
                    if (b.pager.currentItem == position) updateDots()
                }
            }
            holder.callback = cb
            holder.pager.registerOnPageChangeCallback(cb)
        }
    }

    private inner class InnerAdapter(val row: Int) : RecyclerView.Adapter<InnerAdapter.VH>() {

        inner class VH(val grid: HomeGridView) : RecyclerView.ViewHolder(grid)

        override fun getItemCount(): Int = layout.grid.getOrNull(row)?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val g = HomeGridView(parent.context)
            g.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            g.setPadding(dp(6), dp(2), dp(6), dp(2))
            g.onDropItem = { item, page, col, r, centre -> onDrop(item, page, col, r, centre) }
            g.onEdge = { dx, dy -> onEdge(dx, dy) }
            g.onDragEnded = { onDragEnded() }
            g.onHover = { item, page, col, r, centre -> onHover(item, page, col, r, centre) }
            g.onEmptyLongPress = { showPagesMenu() }
            grids.add(g)
            return VH(g)
        }

        override fun onViewRecycled(holder: VH) {
            grids.remove(holder.grid)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val pageId = layout.grid.getOrNull(row)?.getOrNull(position) ?: return
            holder.grid.pageIndex = pageId
            holder.grid.removeAllViews()
            for (item in layout.items.filter { it.page == pageId }) {
                val v = makeView(item) ?: continue
                v.tag = item
                holder.grid.addView(v, HomeGridView.lpFor(item))
            }
            val ek = editKey
            if (ek != null) {
                layout.items.firstOrNull { it.key == ek && it.page == pageId }
                    ?.let { attachEditOverlay(holder.grid, it) }
            }
        }
    }

    private fun dot(active: Boolean, size: Int): View {
        val v = View(this)
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) Color.WHITE else 0x66FFFFFF)
        }
        v.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginStart = dp(3); marginEnd = dp(3); topMargin = dp(3); bottomMargin = dp(3)
        }
        return v
    }

    private fun updateDots() {
        if (!::layout.isInitialized || !::outerAdapter.isInitialized) return
        val row = curRow()
        val pages = layout.grid[row]
        val cur = colInRow(row)
        val d = b.dots
        d.removeAllViews()
        if (pages.size > 1) {
            for ((i, id) in pages.withIndex()) {
                if (layout.mainSet && id == layout.mainPage) {
                    // The main page (chosen by the user) is marked with a little house.
                    val h = ImageView(this)
                    h.setImageResource(R.drawable.ic_home_dot)
                    h.alpha = if (i == cur) 1f else 0.4f
                    d.addView(h, LinearLayout.LayoutParams(dp(11), dp(11)).apply {
                        marginStart = dp(4); marginEnd = dp(4)
                    })
                } else {
                    d.addView(dot(i == cur, dp(7)))
                }
            }
        }
        val vd = b.vdots
        vd.removeAllViews()
        if (layout.grid.size > 1) {
            vd.visibility = View.VISIBLE
            for (r in layout.grid.indices) vd.addView(dot(r == row, dp(6)))
        } else {
            vd.visibility = View.GONE
        }
    }

    // ================================================================ item views

    private fun makeView(item: Placed): View? {
        if (item.key.startsWith(WIDGET_PREFIX)) return makeWidgetView(item)
        if (item.key.startsWith(FOLDER_PREFIX)) return makeFolderView(item)
        val app = appByKey[item.key] ?: return null
        if (app.key in layout.hidden) return null
        val cell = cellFor(app)
        return if (item.w == 1 && item.h == 1) {
            val vb = ItemAppBinding.inflate(layoutInflater)
            bindIcon(vb, cell)
            fitIcon(vb.root, vb.icon, vb.label)
            vb.root.setOnClickListener { open(cell) }
            vb.root.setOnLongClickListener { beginDrag(it, item, vb.icon) }
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
            tb.tile.setOnLongClickListener { beginDrag(tb.root, item, tb.tile) }
            fitTile(tb.root, tb.tile, tb.label, item.w, item.h)
            tb.root
        }
    }

    private fun makeFolderView(item: Placed): View? {
        val f = layout.folders[item.key.removePrefix(FOLDER_PREFIX)] ?: return null
        val fb = ItemFolderBinding.inflate(layoutInflater)
        fb.icon.icons = f.keys.mapNotNull { appByKey[it] }.mapNotNull { cellFor(it).icon }
        fb.label.text = f.name.ifBlank { getString(R.string.folder_default_name) }
        fitIcon(fb.root, fb.icon, fb.label)
        fb.root.setOnClickListener { openFolder(f) }
        fb.root.setOnLongClickListener { beginDrag(it, item, fb.icon) }
        return fb.root
    }

    /**
     * Gives every icon the same fixed size ([iconPx]). It only gets smaller when a cell is too
     * short to hold the icon and its name, and then it does so identically for all icons.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun fitIcon(root: View, icon: View, label: View) {
        // Same size for every icon, set right away (no waiting for a layout pass).
        setSquare(icon, iconPx)
        root.addOnLayoutChangeListener { _, _, t, _, bt, _, _, _, _ ->
            val h = bt - t
            if (h <= 0) return@addOnLayoutChangeListener
            // The name line is always reserved (even when hidden), so the size does not depend on
            // it. Icons only get smaller when a cell is too short to hold an icon and a name.
            val s = minOf(iconPx, (h - dp(24)).coerceAtLeast(dp(28)))
            setSquare(icon, s)
        }
    }

    private fun setSquare(v: View, s: Int) {
        val lp = v.layoutParams ?: return
        if (lp.width != s || lp.height != s) {
            lp.width = s
            lp.height = s
            v.layoutParams = lp
        }
    }

    /**
     * Gives a big tile exactly the w:h shape of its size (2x2 is a square, 4x3 a 4:3 rectangle),
     * as large as fits inside the cells it covers, centred. The editor frame has the same
     * shape, so what is cropped there is what is shown here.
     */
    private fun fitTile(root: android.widget.LinearLayout, tile: View, label: View, w: Int, h: Int) {
        root.gravity = Gravity.CENTER
        root.addOnLayoutChangeListener { _, l, t, r, bt, _, _, _, _ ->
            val aw = (r - l) - dp(8)
            val labelH = if (label.visibility == View.VISIBLE) dp(20) else 0
            val ah = (bt - t) - dp(8) - labelH
            if (aw <= 0 || ah <= 0) return@addOnLayoutChangeListener
            val ratio = w.toFloat() / h
            var tw = aw
            var th = (tw / ratio).toInt()
            if (th > ah) {
                th = ah
                tw = (th * ratio).toInt()
            }
            val lp = tile.layoutParams as android.widget.LinearLayout.LayoutParams
            if (lp.width != tw || lp.height != th || lp.weight != 0f) {
                lp.width = tw
                lp.height = th
                lp.weight = 0f
                tile.layoutParams = lp
            }
        }
    }

    private fun bindIcon(vb: ItemAppBinding, cell: Cell) {
        vb.icon.setImageDrawable(cell.icon)
        vb.icon.outlineProvider = IconOutline
        vb.icon.clipToOutline = cell.custom
        vb.label.text = cell.label
        // A hidden name keeps its space, so every icon sits at exactly the same height.
        vb.label.visibility = if (cell.showLabel) View.VISIBLE else View.INVISIBLE
    }

    private fun renderDock() {
        val dock = b.dock
        dock.removeAllViews()
        for ((i, key) in layout.dock.withIndex()) {
            if (i >= DOCK_MAX) break
            val app = appByKey[key] ?: continue
            if (app.key in layout.hidden) continue
            val cell = cellFor(app)
            val vb = ItemAppBinding.inflate(layoutInflater, dock, false)
            bindIcon(vb, cell)
            vb.label.visibility = View.GONE
            setSquare(vb.icon, iconPx)
            vb.root.setOnClickListener { open(cell) }
            // A dock icon is dragged as Placed(key, page -1, col = its place in the dock).
            vb.root.setOnLongClickListener {
                beginDrag(it, Placed(cell.app.key, -1, i, 0), vb.icon)
            }
            // Equal share of the full width: spread out like the original home screen.
            dock.addView(vb.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        dock.visibility = View.VISIBLE
    }

    // ================================================================ drag and drop

    /** What follows the finger: the icon/tile itself, a bit bigger, with no frame around it. */
    private class PopShadow(v: View, private val scale: Float) : View.DragShadowBuilder(v) {
        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            val w = (view.width * scale).toInt()
            val h = (view.height * scale).toInt()
            outShadowSize.set(w, h)
            outShadowTouchPoint.set(w / 2, h / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            canvas.scale(scale, scale)
            view.draw(canvas)
        }
    }

    private fun popScale(item: Placed) = if (item.w * item.h == 1) 1.15f else 1.03f

    /** Long press: a quick "pop" bounce, then the item follows the finger. */
    private fun beginDrag(v: View, item: Placed, shadowOf: View = v): Boolean {
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val s = popScale(item)
        v.animate().scaleX(s).scaleY(s).rotation(6f).setDuration(120).start()
        // A timer instead of an animation end-callback: the callback is skipped when the animation
        // is cancelled, which used to leave an icon stuck bigger and tilted.
        handler.postDelayed({
            v.animate().cancel()
            v.scaleX = 1f
            v.scaleY = 1f
            v.rotation = 0f
            if (v.isAttachedToWindow) startDrag(v, item, shadowOf)
        }, 120)
        return true
    }

    private fun startDrag(v: View, item: Placed, shadowOf: View) {
        dragging = true
        dropped = false
        previewActive = false
        hoverKey = ""
        snapshot = layout.items.map { Snap(it, it.page, it.col, it.row) } +
            Snap(item, item.page, item.col, item.row)
        val ok = v.startDragAndDrop(
            ClipData.newPlainText("item", item.key), PopShadow(shadowOf, popScale(item)), item, 0
        )
        // The item itself follows the finger: nothing is left behind in its old place.
        if (ok) v.visibility = View.INVISIBLE else dragging = false
    }

    private fun restoreSnapshot(sync: Boolean) {
        for (s in snapshot) {
            s.item.page = s.page; s.item.col = s.col; s.item.row = s.row
        }
        previewActive = false
        if (sync) grids.forEach { it.syncPositions() }
    }

    private fun clearHighlight() {
        hoverTarget?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        hoverTarget = null
        dockHoverIdx = -1
    }

    /** The finger moved over a cell: after a short pause the icons there slide aside. */
    private fun onHover(item: Placed, page: Int, col: Int, row: Int, centre: Boolean) {
        val key = "$page:$col:$row:$centre"
        if (key == hoverKey) return
        hoverKey = key
        handler.removeCallbacks(hoverRunnable)
        clearHighlight()
        if (previewActive) restoreSnapshot(true)
        hoverItem = item
        hoverPage = page
        hoverCol = col
        hoverRow = row
        hoverCentre = centre
        handler.postDelayed(hoverRunnable, 170)
    }

    private fun applyHover() {
        val item = hoverItem ?: return
        if (!dragging) return
        val other = layout.placedAt(hoverPage, hoverCol, hoverRow, item)
        if (hoverCentre && other != null && other.w == 1 && other.h == 1 &&
            item.w == 1 && item.h == 1 && isApp(item.key)
        ) {
            // About to make / fill a folder: just enlarge the target a little.
            val v = grids.firstOrNull { it.pageIndex == hoverPage }?.viewOf(other)
            v?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(120)?.start()
            hoverTarget = v
            return
        }
        if (layout.fits(hoverPage, hoverCol, hoverRow, item.w, item.h, item)) return
        if (layout.insertWithPush(item, hoverPage, hoverCol, hoverRow)) {
            previewActive = true
            grids.forEach { it.syncPositions() }
        }
    }

    /**
     * While dragging at a screen edge, switch to the neighbouring page after a short wait.
     * Only pages that already exist are visited; pages are never created by dragging.
     */
    private fun onEdge(dx: Int, dy: Int) {
        if (dx == edgeDx && dy == edgeDy) return
        edgeDx = dx
        edgeDy = dy
        handler.removeCallbacks(edgeRunnable)
        if (dx != 0 || dy != 0) handler.postDelayed(edgeRunnable, EDGE_DWELL_MS)
    }

    private fun edgeNavigate() {
        if (!dragging || (edgeDx == 0 && edgeDy == 0)) return
        val row = curRow()
        var moved = false
        if (edgeDx != 0) {
            val target = colInRow(row) + edgeDx
            val inner = innerPager(row)
            if (inner != null && target in 0 until layout.grid[row].size) {
                inner.setCurrentItem(target, true)
                moved = true
            }
        } else {
            val target = row + edgeDy
            if (target in 0 until layout.grid.size) {
                b.pager.setCurrentItem(target, true)
                moved = true
            }
        }
        if (moved) handler.postDelayed(edgeRunnable, EDGE_REPEAT_MS)
    }

    private fun onDrop(item: Placed, page: Int, col: Int, row: Int, inCentre: Boolean) {
        val offX = if (item.isFree) item.fx - item.col else 0f
        val offY = if (item.isFree) item.fy - item.row else 0f
        handler.removeCallbacks(hoverRunnable)
        clearHighlight()
        restoreSnapshot(false)
        dropped = true
        // An icon dragged out of the dock carries page -1 and its place in the dock in [col].
        val fromDockIdx = if (item.page == -1) item.col else -1
        if (item.page == page && item.col == col && item.row == row) {
            // Picked up and put back in the same place: show the item's menu.
            showItemMenu(item)
            return
        }
        val other = layout.placedAt(page, col, row, item)

        // App dropped right onto another app (or a folder): make / fill a folder.
        // (Two copies of the same app never make a folder together.)
        if (inCentre && other != null && other.w == 1 && other.h == 1 &&
            item.w == 1 && item.h == 1 && isApp(item.key) && other.key != item.key
        ) {
            if (isApp(other.key)) {
                val f = layout.newFolder(getString(R.string.folder_default_name), listOf(other.key, item.key))
                layout.items.removeAll { it === item }
                if (fromDockIdx in layout.dock.indices) layout.dock.removeAt(fromDockIdx)
                layout.items.removeAll { it === other }
                layout.items.add(Placed(FOLDER_PREFIX + f.id, other.page, other.col, other.row))
                layout.save()
                return
            }
            val folder = layout.folders[other.key.removePrefix(FOLDER_PREFIX)]
            if (folder != null) {
                folder.keys.add(item.key)
                layout.items.removeAll { it === item }
                if (fromDockIdx in layout.dock.indices) layout.dock.removeAt(fromDockIdx)
                layout.save()
                return
            }
        }

        var placed = true
        if (layout.fits(page, col, row, item.w, item.h, item)) {
            item.page = page; item.col = col; item.row = row
        } else if (!layout.insertWithPush(item, page, col, row)) {
            toast(R.string.toast_no_room)
            placed = false
        }
        if (placed && item.isFree) {
            item.setFree(item.col + offX, item.row + offY, item.fw, item.fh)
        }
        if (placed && layout.items.none { it === item }) {
            // Came from the dock: now it lives on the page (only that dock slot is emptied).
            layout.items.add(item)
            if (fromDockIdx in layout.dock.indices) layout.dock.removeAt(fromDockIdx)
        }
        layout.save()
    }

    private fun onDragEnded() {
        hideMarker()
        if (!dragging) return
        dragging = false
        edgeDx = 0
        edgeDy = 0
        handler.removeCallbacks(edgeRunnable)
        handler.removeCallbacks(hoverRunnable)
        clearHighlight()
        if (!dropped) restoreSnapshot(false)   // dropped nowhere: everything goes back
        previewActive = false
        if (pendingReselect != null) {
            editKey = pendingReselect
            pendingReselect = null
        }
        renderDock()
        refreshPages()
        updateDots()
    }

    private var dropMarker: View? = null

    private fun marker(): View {
        dropMarker?.let { return it }
        val m = View(this)
        m.setBackgroundResource(R.drawable.bg_drop_marker)
        m.visibility = View.GONE
        b.root.addView(m, android.widget.FrameLayout.LayoutParams(dp(4), dp(56), Gravity.TOP or Gravity.START))
        dropMarker = m
        return m
    }

    private fun hideMarker() {
        dropMarker?.visibility = View.GONE
    }

    /** Where in the dock a dragged app would go: the slot index, for the dock's current icons. */
    private fun dockSlot(inDock: Boolean, n: Int, x: Float): Int {
        if (n == 0) return 0
        val sw = b.dock.width.coerceAtLeast(1).toFloat() / n
        return if (inDock) (x / sw).toInt().coerceIn(0, n - 1) else Math.round(x / sw).coerceIn(0, n)
    }

    private fun showDockMarker(inDock: Boolean, n: Int, idx: Int) {
        val w = b.dock.width.coerceAtLeast(1).toFloat()
        val bx = when {
            n == 0 -> w / 2
            inDock -> {
                val old = idx
                val sw = w / n
                (if (old <= dockDragFrom) old * sw else (old + 1) * sw)
            }
            else -> idx * (w / n)
        }
        val m = marker()
        val d = IntArray(2)
        val r = IntArray(2)
        b.dock.getLocationInWindow(d)
        b.root.getLocationInWindow(r)
        val x = (d[0] - r[0] + bx - dp(2)).coerceIn(dp(2).toFloat(), (b.root.width - dp(6)).toFloat())
        m.translationX = x
        m.translationY = (d[1] - r[1] + (b.dock.height - dp(56)) / 2f)
        m.visibility = View.VISIBLE
        m.bringToFront()
    }

    private var dockDragFrom = 0
    private var dockHoverIdx = -1

    /** Dock is full: lights up the icon under the finger, which the dragged app will replace. */
    private fun highlightDockSlot(idx: Int) {
        if (idx == dockHoverIdx && hoverTarget != null) return
        clearHighlight()
        val v = b.dock.getChildAt(idx) ?: return
        dockHoverIdx = idx
        v.animate().scaleX(1.18f).scaleY(1.18f).setDuration(120).start()
        hoverTarget = v
    }

    /**
     * Drag events over the dock (and the dots strip right above it, so the drop zone is easy to
     * hit from the pages): drop an app here to put it in the dock at that spot. When the dock is
     * already full the dropped app replaces the icon under it, and that icon takes the place the
     * dropped app came from (a swap) instead of refusing the drop.
     */
    private fun onDockDrag(e: android.view.DragEvent): Boolean {
        val item = e.localState as? Placed
        when (e.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> return item != null && isApp(item.key)
            android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                onEdge(0, 0)
                hoverKey = ""
                handler.removeCallbacks(hoverRunnable)
                if (previewActive) restoreSnapshot(true)
                if (item != null) {
                    val fromDock = item.page == -1
                    val n = layout.dock.size
                    if (!fromDock && n >= DOCK_MAX) {
                        hideMarker()
                        highlightDockSlot(dockSlot(true, n, e.x))
                    } else {
                        clearHighlight()
                        dockDragFrom = if (fromDock) item.col else -1
                        showDockMarker(fromDock, n, dockSlot(fromDock, n, e.x))
                    }
                }
            }
            android.view.DragEvent.ACTION_DRAG_EXITED -> {
                hideMarker()
                clearHighlight()
            }
            android.view.DragEvent.ACTION_DROP -> {
                hideMarker()
                if (item != null) dropOnDock(item, e.x)
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                hideMarker()
                onDragEnded()
            }
        }
        return true
    }

    private fun dropOnDock(item: Placed, x: Float) {
        handler.removeCallbacks(hoverRunnable)
        clearHighlight()
        restoreSnapshot(false)
        dropped = true
        val key = item.key
        val app = appByKey[key] ?: return
        val fromDock = item.page == -1
        val n = layout.dock.size
        if (fromDock) {
            // Re-ordering inside the dock; dropped on its own place = that icon's menu.
            val old = item.col
            if (old !in layout.dock.indices) return
            val idx = dockSlot(true, n, x)
            if (old == idx) {
                showDockMenu(cellFor(app), old)
                return
            }
            layout.dock.removeAt(old)
            layout.dock.add(idx.coerceAtMost(layout.dock.size), key)
        } else if (n >= DOCK_MAX) {
            // Full dock: swap. The dropped app takes the slot, the app that was there goes to the
            // home-screen cell the dropped app has just left.
            val idx = dockSlot(true, n, x)
            val replaced = layout.dock[idx]
            layout.dock[idx] = key
            layout.items.removeAll { it === item }
            layout.items.add(Placed(replaced, item.page, item.col, item.row))
        } else {
            // Only this one copy leaves the home screen (other copies of the app stay where they are).
            layout.items.removeAll { it === item }
            layout.dock.add(dockSlot(false, n, x).coerceAtMost(layout.dock.size), key)
        }
        layout.save()
        render()
    }

    private fun pinField(hintRes: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setHint(hintRes)
        maxLines = 1
    }

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
        views.forEach { addView(it) }
    }

    /** Asks for the PIN (when one is set), then runs [onOk]. */
    private fun askPin(onOk: () -> Unit) {
        if (!layout.hasPin()) {
            onOk()
            return
        }
        val f = pinField(R.string.pin_hint)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pin_title_enter)
            .setView(column(f))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (layout.checkPin(f.text.toString())) onOk() else toast(R.string.pin_wrong)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Makes sure a PIN exists (asking the user to create one the first time), then runs [onOk]. */
    private fun ensurePin(onOk: () -> Unit) {
        if (layout.hasPin()) {
            onOk()
            return
        }
        val a = pinField(R.string.pin_hint)
        val c = pinField(R.string.pin_confirm_hint)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pin_title_set)
            .setView(column(a, c))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val p = a.text.toString()
                when {
                    p.length < 4 -> toast(R.string.pin_short)
                    p != c.text.toString() -> toast(R.string.pin_mismatch)
                    else -> {
                        layout.setPin(p)
                        layout.save()
                        onOk()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun lockApp(app: AppItem) = ensurePin {
        layout.locked.add(app.key)
        layout.save()
        toast(R.string.toast_locked)
    }

    private fun unlockApp(app: AppItem) = askPin {
        layout.locked.remove(app.key)
        layout.save()
        toast(R.string.toast_unlocked)
    }

    private fun hideApp(app: AppItem) = ensurePin {
        layout.hidden.add(app.key)
        layout.items.removeAll { it.key == app.key }
        layout.dock.removeAll { it == app.key }
        for (f in layout.folders.values) f.keys.remove(app.key)
        cleanFolders()
        stashHiddenWidgets()
        layout.save()
        render()
        toast(R.string.toast_hidden)
    }

    private fun unhideApp(app: AppItem) {
        layout.hidden.remove(app.key)
        if (!layout.isPlaced(app.key) && !layout.dock.contains(app.key)) {
            if (!placeIfRoom(app.key, curPageId())) layout.addAuto(app.key, curPageId(), curRow())
        }
        restoreWidgetsOf(app.pkg)
        layout.save()
        render()
    }

    private fun showHiddenApps() {
        if (layout.hidden.isEmpty()) {
            toast(R.string.toast_no_hidden)
            return
        }
        askPin {
            val list = layout.hidden.mapNotNull { appByKey[it] }.map { cellFor(it) }
                .sortedBy { it.label.lowercase() }
            if (list.isEmpty()) {
                toast(R.string.toast_no_hidden)
            } else {
                showActions(
                    getString(R.string.menu_hidden_apps),
                    list.map { c ->
                        c.label to {
                            showActions(
                                c.label,
                                listOf(
                                    getString(R.string.menu_open) to { launch(c) },
                                    getString(R.string.menu_unhide) to { unhideApp(c.app) }
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    // ================================================================ folders

    /** Folders with fewer than two apps are dissolved (the remaining app takes the folder's place). */
    private fun cleanFolders() {
        for (f in layout.folders.values.toList()) {
            if (f.keys.size >= 2) continue
            val fi = layout.items.firstOrNull { it.key == FOLDER_PREFIX + f.id }
            layout.folders.remove(f.id)
            if (fi != null) {
                layout.items.remove(fi)
                f.keys.firstOrNull()?.let { layout.items.add(Placed(it, fi.page, fi.col, fi.row)) }
            }
        }
    }

    private fun dissolveFolder(f: Folder) {
        val fi = layout.items.firstOrNull { it.key == FOLDER_PREFIX + f.id }
        val page = fi?.page ?: curPageId()
        layout.folders.remove(f.id)
        if (fi != null) layout.items.remove(fi)
        f.keys.forEachIndexed { i, k ->
            if (i == 0 && fi != null) layout.items.add(Placed(k, fi.page, fi.col, fi.row))
            else layout.addAuto(k, page, curRow())
        }
        layout.save()
        render()
    }

    private fun removeFromFolder(f: Folder, key: String) {
        val fi = layout.items.firstOrNull { it.key == FOLDER_PREFIX + f.id }
        f.keys.remove(key)
        layout.addAuto(key, fi?.page ?: curPageId(), curRow())
        cleanFolders()
        layout.save()
        render()
    }

    private fun renameFolder(f: Folder) {
        val e = EditText(this)
        e.setText(f.name)
        e.setSelection(e.text.length)
        e.maxLines = 1
        e.inputType = InputType.TYPE_CLASS_TEXT
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_rename)
            .setView(column(e))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                f.name = e.text.toString().trim()
                layout.save()
                refreshPages()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openFolder(f: Folder) {
        val rv = RecyclerView(this)
        rv.layoutManager = GridLayoutManager(this, 4)
        rv.setPadding(dp(8), dp(8), dp(8), dp(8))
        var dlg: AlertDialog? = null
        val ad = AppGridAdapter(
            { cell -> dlg?.dismiss(); open(cell) },
            { cell ->
                dlg?.dismiss()
                showActions(
                    cell.label,
                    listOf(
                        getString(R.string.menu_open) to { open(cell) },
                        getString(R.string.menu_remove_from_folder) to { removeFromFolder(f, cell.app.key) },
                        getString(R.string.menu_change_icon) to { openEditor(cell, 0, 0) }
                    )
                )
            }
        )
        ad.submit(f.keys.mapNotNull { appByKey[it] }.map { cellFor(it) })
        rv.adapter = ad
        dlg = MaterialAlertDialogBuilder(this)
            .setTitle(f.name.ifBlank { getString(R.string.folder_default_name) })
            .setView(rv)
            .setPositiveButton(R.string.menu_rename) { _, _ -> renameFolder(f) }
            .setNeutralButton(R.string.menu_dissolve) { _, _ -> dissolveFolder(f) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ================================================================ menus

    private fun showActions(title: String, actions: List<Pair<String, () -> Unit>>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun lockActions(app: AppItem, into: MutableList<Pair<String, () -> Unit>>) {
        if (app.key in layout.locked) into.add(getString(R.string.menu_unlock) to { unlockApp(app) })
        else into.add(getString(R.string.menu_lock) to { lockApp(app) })
        into.add(getString(R.string.menu_hide) to { hideApp(app) })
    }

    private fun showItemMenu(item: Placed) {
        if (item.key.startsWith(WIDGET_PREFIX)) {
            startWidgetEdit(item)
            return
        }
        if (item.key.startsWith(FOLDER_PREFIX)) {
            val f = layout.folders[item.key.removePrefix(FOLDER_PREFIX)] ?: return
            showActions(
                f.name.ifBlank { getString(R.string.folder_default_name) },
                listOf(
                    getString(R.string.menu_open) to { openFolder(f) },
                    getString(R.string.menu_rename) to { renameFolder(f) },
                    getString(R.string.menu_dissolve) to { dissolveFolder(f) }
                )
            )
            return
        }
        val app = appByKey[item.key] ?: return
        val cell = cellFor(app)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.menu_change_icon) to { openEditor(cell, item.w, item.h) })
        if (cell.custom) actions.add(getString(R.string.menu_reset_icon) to { resetIcon(app) })
        actions.add(getString(R.string.menu_resize_drag) to { startResize(item) })
        actions.add(getString(R.string.menu_size) to { showSizeDialog(item) })
        actions.add(getString(R.string.menu_clone) to { cloneApp(app.key) })
        actions.add(getString(R.string.menu_add_dock) to { moveItemToDock(item) })
        actions.add(getString(R.string.menu_remove_home) to { removeItem(item) })
        lockActions(app, actions)
        actions.add(getString(R.string.menu_app_info) to { appInfo(app) })
        actions.add(getString(R.string.menu_uninstall) to { uninstall(app) })
        showActions(cell.label, actions)
    }

    /** [dockIdx] = the place of this icon in the dock (the dock may hold copies of the same app). */
    private fun showDockMenu(cell: Cell, dockIdx: Int) {
        val app = cell.app
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.menu_change_icon) to { openEditor(cell, 0, 0) })
        if (cell.custom) actions.add(getString(R.string.menu_reset_icon) to { resetIcon(app) })
        actions.add(getString(R.string.menu_clone) to { cloneApp(app.key) })
        actions.add(getString(R.string.menu_to_home) to { moveDockToHome(dockIdx) })
        actions.add(getString(R.string.menu_remove_dock) to { removeFromDock(dockIdx) })
        lockActions(app, actions)
        actions.add(getString(R.string.menu_app_info) to { appInfo(app) })
        actions.add(getString(R.string.menu_uninstall) to { uninstall(app) })
        showActions(cell.label, actions)
    }

    private fun showDrawerMenu(cell: Cell) {
        val app = cell.app
        val onHome = layout.isPlaced(app.key)
        val inDock = layout.dock.contains(app.key)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.menu_change_icon) to { closeDrawer(); openEditor(cell, 0, 0) })
        if (cell.custom) actions.add(getString(R.string.menu_reset_icon) to { resetIcon(app) })
        if (!onHome) actions.add(getString(R.string.menu_add_home) to { closeDrawer(); moveToHome(app.key) })
        if (!inDock) actions.add(getString(R.string.menu_add_dock) to { moveToDock(app.key) })
        actions.add(getString(R.string.menu_clone) to { closeDrawer(); cloneApp(app.key) })
        lockActions(app, actions)
        actions.add(getString(R.string.menu_app_info) to { appInfo(app) })
        actions.add(getString(R.string.menu_uninstall) to { uninstall(app) })
        showActions(cell.label, actions)
    }

    private fun showWidgetMenu(item: Placed) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.menu_size) to { showSizeDialog(item) })
        actions.add(getString(R.string.menu_remove_widget) to { removeWidget(item) })
        showActions(getString(R.string.title_widgets), actions)
    }

    /** Free size: any width (1..5) x height (1..6) that fits. */
    private fun showSizeDialog(item: Placed) {
        val pw = NumberPicker(this)
        pw.minValue = 1; pw.maxValue = HomeLayout.COLS; pw.value = item.w; pw.wrapSelectorWheel = false
        val ph = NumberPicker(this)
        ph.minValue = 1; ph.maxValue = HomeLayout.ROWS; ph.value = item.h; ph.wrapSelectorWheel = false
        val x = TextView(this)
        x.text = "×"
        x.textSize = 24f
        x.setPadding(dp(20), 0, dp(20), 0)
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(12), 0, 0)
        row.addView(pw)
        row.addView(x)
        row.addView(ph)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_size)
            .setView(row)
            .setPositiveButton(android.R.string.ok) { _, _ -> resize(item, pw.value, ph.value) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private var topMenu: android.widget.PopupWindow? = null

    /** The three-dots menu: a dark rounded card with an icon per entry. */
    private fun showTopMenu(anchor: View) {
        topMenu?.dismiss()
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.bg_menu)
        card.setPadding(0, dp(8), 0, dp(8))
        val pw = android.widget.PopupWindow(card, dp(230), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.elevation = dp(12).toFloat()
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        pw.animationStyle = android.R.style.Animation_Dialog

        fun entry(icon: Int, text: Int, divider: Boolean = false, run: () -> Unit) {
            if (divider) {
                val line = View(this)
                line.setBackgroundColor(0x22FFFFFF)
                card.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    .apply { setMargins(dp(14), dp(6), dp(14), dp(6)) })
            }
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(16), 0, dp(16), 0)
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            row.setBackgroundResource(tv.resourceId)
            val iv = ImageView(this)
            iv.setImageResource(icon)
            iv.alpha = 0.9f
            row.addView(iv, LinearLayout.LayoutParams(dp(20), dp(20)))
            val t = TextView(this)
            t.setText(text)
            t.setTextColor(0xFFF2F2F2.toInt())
            t.textSize = 13f
            t.maxLines = 1
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14) })
            row.setOnClickListener {
                pw.dismiss()
                run()
            }
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
        entry(R.drawable.ic_m_widget, R.string.menu_add_widget) { showWidgetPicker() }
        entry(R.drawable.ic_m_pages, R.string.menu_pages) { showPagesMenu() }
        entry(R.drawable.ic_m_hidden, R.string.menu_hidden_apps) { showHiddenApps() }
        entry(R.drawable.ic_apps, R.string.menu_all_apps) { openDrawer(false) }
        entry(R.drawable.ic_m_edit, R.string.menu_shortcuts_mode, true) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        entry(R.drawable.ic_m_backup, R.string.menu_backup) { showBackupMenu() }
        entry(R.drawable.ic_m_tune, R.string.menu_home_settings) {
            startSafely(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        topMenu = pw
        pw.showAsDropDown(anchor, 0, dp(2), Gravity.END)
    }

    // ---- compact card UI shared by the Pages and Widgets menus (same look as the three-dots menu)

    private fun cardDialog(widthDp: Int): Pair<Dialog, LinearLayout> {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.bg_menu)
        card.setPadding(dp(14), dp(12), dp(14), dp(10))
        dlg.setContentView(card)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.55f)
            setGravity(Gravity.CENTER)
        }
        return dlg to card
    }

    private fun cardTitle(text: CharSequence) = TextView(this).apply {
        this.text = text
        setTextColor(0xFFF2F2F2.toInt())
        textSize = 15f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(2), 0, 0, dp(4))
    }

    private fun cardSection(text: CharSequence) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF9A9A9A.toInt())
        textSize = 11f
        setPadding(dp(2), dp(10), 0, dp(5))
    }

    private fun cardRow(label: CharSequence, danger: Boolean = false, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (danger) 0xFFFF6B6B.toInt() else 0xFFF2F2F2.toInt())
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(40)
            setPadding(dp(10), 0, dp(10), 0)
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }

    /** A row of equal square-ish buttons. */
    private class Chip(val glyph: String, val desc: String, val action: () -> Unit)

    private fun chipRow(chips: List<Chip>): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (chip in chips) {
            val glyph = chip.glyph
            val desc = chip.desc
            val action = chip.action
            val t = TextView(this)
            t.text = glyph
            t.textSize = 17f
            t.setTextColor(0xFFF2F2F2.toInt())
            t.gravity = Gravity.CENTER
            t.contentDescription = desc
            t.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x22FFFFFF)
            }
            t.setOnClickListener { action() }
            row.addView(t, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        return row
    }

    /**
     * Pages: a small map of all pages (tap one to go there, the current one is lit, the main one
     * carries the little house), then compact actions for the page being shown.
     */
    private fun showPagesMenu() {
        val id = curPageId()
        val (r, c) = layout.locate(id) ?: return
        val (dlg, card) = cardDialog(290)
        card.addView(cardTitle(getString(R.string.title_pages)))

        val map = LinearLayout(this)
        map.orientation = LinearLayout.VERTICAL
        map.gravity = Gravity.CENTER_HORIZONTAL
        for (pagesRow in layout.grid) {
            val rowL = LinearLayout(this)
            rowL.orientation = LinearLayout.HORIZONTAL
            rowL.gravity = Gravity.CENTER
            for (pid in pagesRow) {
                val current = pid == id
                val cell = android.widget.FrameLayout(this)
                cell.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (current) 0xFFF2F2F2.toInt() else 0x33FFFFFF)
                    if (!current) setStroke(dp(1), 0x33FFFFFF)
                }
                if (layout.mainSet && pid == layout.mainPage) {
                    val h = ImageView(this)
                    h.setImageResource(R.drawable.ic_home_dot)
                    h.setColorFilter(if (current) 0xFF1C1C1E.toInt() else 0xCCFFFFFF.toInt())
                    cell.addView(h, android.widget.FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                }
                cell.setOnClickListener {
                    if (!current) {
                        dlg.dismiss()
                        goToPage(pid, false)
                        showPagesMenu()
                    }
                }
                rowL.addView(cell, LinearLayout.LayoutParams(dp(30), dp(40)).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            map.addView(rowL)
        }
        card.addView(map, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        card.addView(cardSection(getString(R.string.menu_add_page)))
        card.addView(chipRow(listOf(
            Chip("←", getString(R.string.menu_page_left)) { dlg.dismiss(); addPageAndGo(layout.addPageInRow(r, c)) },
            Chip("→", getString(R.string.menu_page_right)) { dlg.dismiss(); addPageAndGo(layout.addPageInRow(r, c + 1)) },
            Chip("↑", getString(R.string.menu_page_above)) { dlg.dismiss(); addPageAndGo(layout.addRow(r)) },
            Chip("↓", getString(R.string.menu_page_below)) { dlg.dismiss(); addPageAndGo(layout.addRow(r + 1)) }
        )))
        card.addView(cardSection(getString(R.string.section_move_page)))
        card.addView(chipRow(listOf(
            Chip("←", getString(R.string.page_move_left)) { dlg.dismiss(); movePage(id) { layout.movePageHorizontal(id, -1) } },
            Chip("→", getString(R.string.page_move_right)) { dlg.dismiss(); movePage(id) { layout.movePageHorizontal(id, 1) } },
            Chip("↑", getString(R.string.page_move_up)) { dlg.dismiss(); movePage(id) { layout.movePageVertical(id, -1) } },
            Chip("↓", getString(R.string.page_move_down)) { dlg.dismiss(); movePage(id) { layout.movePageVertical(id, 1) } }
        )))

        val line = View(this)
        line.setBackgroundColor(0x22FFFFFF)
        card.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            .apply { setMargins(0, dp(10), 0, dp(4)) })
        card.addView(cardRow(getString(R.string.page_set_main)) {
            dlg.dismiss()
            layout.mainPage = id
            layout.mainSet = true
            layout.save()
            updateDots()
            toast(R.string.toast_main_set)
        })
        if (layout.mainSet) card.addView(cardRow(getString(R.string.page_clear_main)) {
            dlg.dismiss()
            layout.mainSet = false
            layout.save()
            updateDots()
        })
        card.addView(cardRow(getString(R.string.menu_delete_page), danger = true) {
            dlg.dismiss()
            deletePage(id)
        })
        dlg.show()
    }

    private fun addPageAndGo(newId: Int) {
        layout.save()
        rowCol.clear()
        refreshPages()
        goToPage(newId, false)
    }

    private fun movePage(id: Int, op: () -> Boolean) {
        if (!op()) return
        layout.save()
        rowCol.clear()
        refreshPages()
        goToPage(id, false)
    }

    private fun deletePage(id: Int) {
        if (layout.allPages().size <= 1 || !layout.pageIsEmpty(id)) {
            toast(R.string.toast_page_not_empty)
            return
        }
        layout.removePage(id)
        layout.save()
        rowCol.clear()
        refreshPages()
        goToPage(layout.mainPage, false)
    }

    // ================================================================ actions

    /** Opens the app, asking for the PIN first when it is locked. */
    private fun open(cell: Cell) {
        if (cell.app.key in layout.locked) askPin { launch(cell) } else launch(cell)
    }

    private fun launch(cell: Cell) {
        val intent = ShortcutHelper.targetIntent(this, cell.app.pkg, cell.app.activity)
        try {
            if (intent == null) throw IllegalStateException("missing")
            startActivity(intent)
        } catch (e: Exception) {
            toast(R.string.toast_app_missing)
        }
    }

    /** [w] x [h] is the tile size shown in the editor, or 0 x 0 to hide the size picker. */
    private fun openEditor(cell: Cell, w: Int, h: Int) {
        pendingEditKey = cell.app.key
        editorResult.launch(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_PKG, cell.app.pkg)
                .putExtra(EditorActivity.EXTRA_ACTIVITY, cell.app.activity)
                .putExtra(EditorActivity.EXTRA_APP_LABEL, cell.app.label)
                .putExtra(EditorActivity.EXTRA_APPLY_MODE, true)
                .putExtra(EditorActivity.EXTRA_W, w)
                .putExtra(EditorActivity.EXTRA_H, h)
                .putExtra(EditorActivity.EXTRA_CELL_W, cellPx().first)
                .putExtra(EditorActivity.EXTRA_CELL_H, cellPx().second)
        )
    }

    private fun resetIcon(app: AppItem) {
        val e = custom[app.key] ?: return
        IconStore.get(this).delete(e.id)
        bitmapCache.remove(e.id)
        render()
    }

    private fun resize(item: Placed, w: Int, h: Int) {
        if (item.w == w && item.h == h) return
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
        item.clearFree()
        item.col = col; item.row = row; item.w = w; item.h = h
        layout.save()
        refreshPages()
        if (item.key.startsWith(WIDGET_PREFIX)) return
    }

    private fun moveToDock(key: String) {
        if (layout.dock.contains(key)) return
        if (layout.dock.size >= DOCK_MAX) {
            toast(R.string.toast_dock_full)
            return
        }
        layout.items.removeAll { it.key == key }
        for (f in layout.folders.values) f.keys.remove(key)
        cleanFolders()
        layout.dock.add(key)
        layout.save()
        render()
    }

    private fun moveToHome(key: String) {
        layout.dock.remove(key)
        if (!layout.isPlaced(key)) layout.addAuto(key, curPageId(), curRow())
        layout.save()
        render()
    }

    /** Takes just this one icon off the home screen (copies of the same app are not touched). */
    private fun removeItem(item: Placed) {
        layout.items.removeAll { it === item }
        layout.save()
        render()
    }

    /** Moves just this one home-screen icon into the dock. */
    private fun moveItemToDock(item: Placed) {
        if (layout.dock.size >= DOCK_MAX) {
            toast(R.string.toast_dock_full)
            return
        }
        layout.items.removeAll { it === item }
        layout.dock.add(item.key)
        layout.save()
        render()
    }

    /** Moves the dock icon at [idx] to the home screen (when the app is not already there). */
    private fun moveDockToHome(idx: Int) {
        val key = layout.dock.getOrNull(idx) ?: return
        layout.dock.removeAt(idx)
        if (!layout.isPlaced(key)) layout.addAuto(key, curPageId(), curRow())
        layout.save()
        render()
    }

    private fun removeFromDock(idx: Int) {
        if (idx !in layout.dock.indices) return
        layout.dock.removeAt(idx)
        layout.save()
        render()
    }

    // ---- clones: the same app twice (or more), e.g. one icon in the dock and one on the page

    /** Asks where the extra copy of the app goes. */
    private fun cloneApp(key: String) {
        val app = appByKey[key] ?: return
        showActions(
            getString(R.string.clone_where_title, cellFor(app).label),
            listOf(
                getString(R.string.clone_to_home) to { cloneToHome(key) },
                getString(R.string.clone_to_dock) to { cloneToDock(key) }
            )
        )
    }

    private fun cloneToHome(key: String) {
        // First free cell, starting with the page being shown. Pages are never created for this.
        if (!placeIfRoom(key, curPageId())) {
            toast(R.string.toast_no_room)
            return
        }
        layout.save()
        render()
        toast(R.string.toast_cloned)
    }

    private fun cloneToDock(key: String) {
        if (layout.dock.size >= DOCK_MAX) {
            toast(R.string.toast_dock_full)
            return
        }
        layout.dock.add(key)
        layout.save()
        render()
        toast(R.string.toast_cloned)
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
        val cells = visibleApps().map { cellFor(it) }
        val shown = if (q.isEmpty()) cells else cells.filter {
            it.label.lowercase().contains(q) || it.app.label.lowercase().contains(q)
        }
        drawerAdapter.submit(shown)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.search.windowToken, 0)
    }

    // ================================================================ resize by dragging

    private var resizeOverlay: ResizeOverlay? = null

    private fun startResize(item: Placed) {
        val g = grids.firstOrNull { it.pageIndex == item.page } ?: return
        val ov = ResizeOverlay(this, item)
        val isWidget = item.key.startsWith(WIDGET_PREFIX)
        if (isWidget) {
            val id = item.key.removePrefix(WIDGET_PREFIX).toIntOrNull()
            val info = id?.let { widgetManager.getAppWidgetInfo(it) }
            if (info != null) {
                ov.allowH = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
                ov.allowV = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
                val (cw, ch) = cellPx()
                if (info.minResizeWidth > 0) ov.minW = ((info.minResizeWidth + cw - 1) / cw).coerceIn(1, HomeLayout.COLS)
                if (info.minResizeHeight > 0) ov.minH = ((info.minResizeHeight + ch - 1) / ch).coerceIn(1, HomeLayout.ROWS)
            }
            if (!ov.allowH && !ov.allowV) toast(R.string.toast_no_resize)
            ov.onDelete = {
                resizeOverlay = null
                g.removeView(ov)
                removeWidget(item)
            }
        }
        ov.onTry = { c, r, w, h ->
            if (layout.fits(item.page, c, r, w, h, item)) {
                item.col = c; item.row = r; item.w = w; item.h = h
                g.syncPositions(false)
                true
            } else false
        }
        ov.onCommit = {
            // The widget view reports its new size itself from its layout listener
            // (see makeWidgetView), so only the layout needs saving here.
            layout.save()
        }
        ov.onDone = {
            layout.save()
            resizeOverlay = null
            refreshPages()
        }
        // Press and hold inside the frame: leave resize mode and pick the item up to move it.
        ov.onBodyLongPress = {
            val v = g.viewOf(item)
            layout.save()
            resizeOverlay = null
            g.removeView(ov)
            if (v != null) beginDrag(v, item, v)
        }
        resizeOverlay = ov
        g.addView(ov, HomeGridView.LP(0, 0, HomeLayout.COLS, HomeLayout.ROWS))
    }

    // ================================================================ widget edit mode

    private fun widgetInfo(item: Placed): AppWidgetProviderInfo? =
        item.key.removePrefix(WIDGET_PREFIX).toIntOrNull()?.let { widgetManager.getAppWidgetInfo(it) }

    private fun removeEditOverlays() {
        for (g in grids) {
            for (i in g.childCount - 1 downTo 0) {
                if (g.getChildAt(i) is WidgetEditOverlay) g.removeViewAt(i)
            }
        }
    }

    /** Enters edit mode for a widget: a permanent frame with handles, move, delete and done. */
    private fun startWidgetEdit(item: Placed) {
        if (editKey != null && editKey != item.key) endWidgetEdit()
        if (!item.isFree) item.setFree(item.col.toFloat(), item.row.toFloat(), item.w.toFloat(), item.h.toFloat())
        editKey = item.key
        val info = widgetInfo(item)
        if (info != null &&
            info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL == 0 &&
            info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL == 0
        ) toast(R.string.toast_no_resize)
        grids.firstOrNull { it.pageIndex == item.page }?.let { attachEditOverlay(it, item) }
    }

    private fun attachEditOverlay(g: HomeGridView, item: Placed) {
        for (i in g.childCount - 1 downTo 0) {
            if (g.getChildAt(i) is WidgetEditOverlay) g.removeViewAt(i)
        }
        if (!item.isFree) item.setFree(item.col.toFloat(), item.row.toFloat(), item.w.toFloat(), item.h.toFloat())
        val ov = WidgetEditOverlay(this, item)
        val info = widgetInfo(item)
        if (info != null) {
            ov.allowH = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
            ov.allowV = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
            val (cw, ch) = cellPx()
            ov.minW = maxOf(0.5f, info.minResizeWidth / cw.toFloat())
            ov.minH = maxOf(0.5f, info.minResizeHeight / ch.toFloat())
        }
        ov.snap = prefs.getBoolean("snap_widgets", false)
        ov.tryApply = { x, y, w, h ->
            val c = coverCells(x, y, w, h)
            if (layout.fits(item.page, c[0], c[1], c[2], c[3], item)) {
                item.setFree(x, y, w, h)
                g.syncPositions(false)
                true
            } else false
        }
        ov.onCommit = { layout.save() }
        ov.onDelete = {
            editKey = null
            g.removeView(ov)
            removeWidget(item)
        }
        ov.onDone = { endWidgetEdit() }
        ov.onToggleSnap = {
            val on = !ov.snap
            ov.snap = on
            prefs.edit().putBoolean("snap_widgets", on).apply()
            toast(if (on) R.string.toast_snap_on else R.string.toast_snap_off)
            if (on) {
                // Turning the grid on aligns the widget to it right away (when there is room).
                val w = Math.round(item.fw).coerceIn(1, HomeLayout.COLS)
                val h = Math.round(item.fh).coerceIn(1, HomeLayout.ROWS)
                val x = Math.round(item.fx).coerceIn(0, HomeLayout.COLS - w)
                val y = Math.round(item.fy).coerceIn(0, HomeLayout.ROWS - h)
                if (ov.tryApply?.invoke(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()) == true) {
                    layout.save()
                }
            }
            ov.invalidate()
        }
        // Press and hold (without moving) inside the frame: pick the widget up to move it to
        // another page. Edit mode returns when it has been dropped.
        ov.onPickUp = {
            val v = g.viewOf(item)
            layout.save()
            pendingReselect = item.key
            editKey = null
            g.removeView(ov)
            if (v != null) beginDrag(v, item, v) else pendingReselect = null
        }
        g.addView(ov, HomeGridView.LP(0, 0, HomeLayout.COLS, HomeLayout.ROWS))
    }

    private fun endWidgetEdit() {
        val k = editKey
        editKey = null
        pendingReselect = null
        removeEditOverlays()
        if (k == null) return
        layout.items.firstOrNull { it.key == k }?.normalizeIfAligned()
        layout.save()
        grids.forEach { it.syncPositions(false) }
    }

    // ================================================================ widgets of hidden apps

    private fun widgetPkg(key: String): String? =
        key.removePrefix(WIDGET_PREFIX).toIntOrNull()
            ?.let { widgetManager.getAppWidgetInfo(it)?.provider?.packageName }

    private fun hiddenPkgs(): Set<String> = layout.hidden.map { it.substringBefore('/') }.toSet()

    /** Widgets that belong to hidden apps disappear from the pages (and are kept aside). */
    private fun stashHiddenWidgets() {
        val pk = hiddenPkgs()
        if (pk.isEmpty()) return
        val ws = layout.items.filter { it.key.startsWith(WIDGET_PREFIX) && widgetPkg(it.key) in pk }
        layout.items.removeAll(ws.toSet())
        layout.stash.addAll(ws)
    }

    private fun restoreWidgetsOf(pkg: String) {
        val back = layout.stash.filter { widgetPkg(it.key) == pkg }
        for (w in back) {
            layout.stash.remove(w)
            var ok = w.page in layout.allPages() && layout.fits(w.page, w.col, w.row, w.w, w.h, null)
            if (!ok) {
                for (p in layout.allPages()) {
                    val f = layout.findFree(p, w.w, w.h) ?: continue
                    w.page = p; w.col = f.first; w.row = f.second
                    ok = true
                    break
                }
            }
            if (ok) layout.items.add(w) else layout.stash.add(w)
        }
    }

    // ================================================================ backup

    private fun showBackupMenu() {
        showActions(
            getString(R.string.menu_backup),
            listOf(
                getString(R.string.backup_export) to { exportResult.launch("zatchi-backup.zip") },
                getString(R.string.backup_import) to {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.backup_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            importResult.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            )
        )
    }

    private fun exportBackup(uri: Uri) {
        layout.save()
        io.execute {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    ZipOutputStream(os.buffered()).use { z ->
                        fun add(name: String, f: File) {
                            if (!f.isFile) return
                            z.putNextEntry(ZipEntry(name))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                        add("entries.json", File(filesDir, "entries.json"))
                        add("home_layout.json", File(filesDir, "home_layout.json"))
                        File(filesDir, "icons").listFiles()?.forEach { add("icons/" + it.name, it) }
                    }
                } != null
            } catch (e: Exception) {
                false
            }
            runOnUiThread { toast(if (ok) R.string.toast_backup_done else R.string.toast_failed) }
        }
    }

    private fun importBackup(uri: Uri) {
        io.execute {
            val tmp = File(cacheDir, "restore")
            val ok = try {
                tmp.deleteRecursively()
                File(tmp, "icons").mkdirs()
                contentResolver.openInputStream(uri)?.use { ins ->
                    ZipInputStream(ins.buffered()).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            val n = e.name
                            val dest = when {
                                e.isDirectory -> null
                                n == "entries.json" || n == "home_layout.json" -> File(tmp, n)
                                n.startsWith("icons/") && n.length > 6 &&
                                    !n.removePrefix("icons/").contains('/') && !n.contains("..") -> File(tmp, n)
                                else -> null
                            }
                            dest?.outputStream()?.use { z.copyTo(it) }
                            z.closeEntry()
                            e = z.nextEntry
                        }
                    }
                }
                if (!File(tmp, "entries.json").isFile) {
                    false
                } else {
                    val icons = File(filesDir, "icons")
                    icons.deleteRecursively()
                    icons.mkdirs()
                    File(tmp, "icons").listFiles()?.forEach { it.copyTo(File(icons, it.name), true) }
                    File(tmp, "entries.json").copyTo(File(filesDir, "entries.json"), true)
                    val lay = File(tmp, "home_layout.json")
                    if (lay.isFile) lay.copyTo(File(filesDir, "home_layout.json"), true)
                    true
                }
            } catch (e: Exception) {
                false
            }
            tmp.deleteRecursively()
            runOnUiThread {
                if (ok) recreate() else toast(R.string.toast_restore_failed)
            }
        }
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
                val hiddenPk = hiddenPkgs()
                widgetManager.installedProviders.filter { it.provider.packageName !in hiddenPk }.map { info ->
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
                val dm = resources.displayMetrics
                val widthDp = minOf(340, (dm.widthPixels / density).toInt() - 32)
                val (dlg, card) = cardDialog(widthDp)
                card.addView(cardTitle(getString(R.string.title_widgets)))
                val search = EditText(this)
                search.setBackgroundResource(R.drawable.bg_search)
                search.hint = getString(R.string.search_widgets_hint)
                search.setHintTextColor(0xFFBBBBBB.toInt())
                search.setTextColor(Color.WHITE)
                search.textSize = 13f
                search.maxLines = 1
                search.inputType = InputType.TYPE_CLASS_TEXT
                search.setPadding(dp(14), 0, dp(14), 0)
                card.addView(search, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
                ).apply { topMargin = dp(2); bottomMargin = dp(6) })
                val list = RecyclerView(this)
                list.layoutManager = LinearLayoutManager(this)
                val adapter = WidgetAdapter(options) {
                    dlg.dismiss()
                    addWidget(it.info)
                }
                list.adapter = adapter
                search.doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }
                card.addView(list, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    minOf(dp(360), (dm.heightPixels * 0.5f).toInt())
                ))
                dlg.show()
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
        val pages = layout.allPages()
        val start = pages.indexOf(curPageId()).coerceAtLeast(0)
        var placed: Placed? = null
        for (p in pages.drop(start) + pages.take(start)) {
            val free = layout.findFree(p, w, h) ?: continue
            placed = Placed(WIDGET_PREFIX + id, p, free.first, free.second, w, h)
            break
        }
        if (placed == null) {
            // No page has room: pages are only ever created by the user.
            try {
                widgetHost.deleteAppWidgetId(id)
            } catch (e: Exception) {
                // ignore
            }
            toast(R.string.toast_no_room)
            return
        }
        layout.items.add(placed)
        layout.save()
        refreshPages()
        goToPage(placed.page, false)
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
        if (editKey == item.key) editKey = null
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
        refreshPages()
    }

    private fun makeWidgetView(item: Placed): View? {
        val id = item.key.removePrefix(WIDGET_PREFIX).toIntOrNull() ?: return null
        val info = widgetManager.getAppWidgetInfo(id)
        val frame = WidgetFrame(this)
        frame.setPadding(dp(4), dp(4), dp(4), dp(4))
        frame.onLongPress = {
            frame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startWidgetEdit(item)
        }
        if (info == null) {
            val t = TextView(this)
            t.setText(R.string.widget_unavailable)
            t.setTextColor(Color.WHITE)
            t.gravity = Gravity.CENTER
            t.setBackgroundResource(R.drawable.bg_tile)
            frame.addView(t, matchParams())
            return frame
        }
        // Application context: the AppCompat activity context breaks widget inflation.
        val hv = widgetHost.createView(applicationContext, id, info)
        frame.addView(hv, matchParams())
        // Tell the widget how much room it really has, so it picks a matching layout. This must
        // follow the widget view's REAL laid-out size: a post() right after creation runs before
        // the first layout (size 0), so the size used to be skipped and the widget kept a stale
        // layout (e.g. a cropped Google logo) until some later rebuild.
        var sentW = -1
        var sentH = -1
        hv.addOnLayoutChangeListener { v, l, t, r, bt, _, _, _, _ ->
            val wDp = ((r - l) / density).toInt()
            val hDp = ((bt - t) / density).toInt()
            if (wDp > 0 && hDp > 0 && (wDp != sentW || hDp != sentH)) {
                sentW = wDp
                sentH = hDp
                (v as android.appwidget.AppWidgetHostView)
                    .updateAppWidgetSize(Bundle(), wDp, hDp, wDp, hDp)
            }
        }
        return frame
    }

    private fun matchParams() = android.widget.FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )
}
