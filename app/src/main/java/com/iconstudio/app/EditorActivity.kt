package com.iconstudio.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import com.iconstudio.app.databinding.ActivityEditorBinding
import java.util.concurrent.Executors

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_ACTIVITY = "activity"
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_APPLY_MODE = "apply_mode"
        const val EXTRA_W = "size_w"   // size in cells; 0 = not placed, hide the size picker
        const val EXTRA_H = "size_h"
        const val EXTRA_CELL_W = "cell_w"   // size of one home cell in px, to shape the frame
        const val EXTRA_CELL_H = "cell_h"

        private const val ICON_SIZE = 192
        private const val LAUNCHER_ICON_SIZE = 512
        private const val SOURCE_MAX = 1280
    }

    private lateinit var b: ActivityEditorBinding
    private var entry: IconEntry? = null
    private var pkg = ""
    private var activityName = ""
    private var appLabel = ""
    private var applyMode = false

    private var source: Bitmap? = null
    private var sourceDirty = false

    private val io = Executors.newSingleThreadExecutor()

    // Opens the gallery directly (no storage permission needed: the picked image is granted to us).
    private val pickGallery =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data
            if (r.resultCode == RESULT_OK && uri != null) loadFromUri(uri)
        }

    // Fallback if the device has no gallery app that answers ACTION_PICK.
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) loadFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(b.root)
        edgeToEdge(b.root)

        applyMode = intent.getBooleanExtra(EXTRA_APPLY_MODE, false)
        var existing = intent.getStringExtra(EXTRA_ENTRY_ID)?.let { IconStore.get(this).get(it) }
        pkg = existing?.pkg ?: intent.getStringExtra(EXTRA_PKG).orEmpty()
        if (pkg.isEmpty()) {
            finish()
            return
        }
        activityName = existing?.activity ?: intent.getStringExtra(EXTRA_ACTIVITY).orEmpty()
        if (applyMode && existing == null) {
            existing = IconStore.get(this).findLauncher(pkg, activityName)
        }
        entry = existing
        // Opened from the Zatchi shortcuts screen for an app that already has a home-screen icon:
        // start from that icon, so both ways of changing an icon work on the same image.
        val seed = if (!applyMode && existing == null) {
            IconStore.get(this).findLauncher(pkg, activityName)
        } else null
        appLabel = existing?.appLabel
            ?: intent.getStringExtra(EXTRA_APP_LABEL).orEmpty().ifEmpty { pkg }

        b.toolbar.setNavigationIcon(R.drawable.ic_back)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.btnPick.setOnClickListener {
            val gallery = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                .setType("image/*")
            try {
                pickGallery.launch(gallery)
            } catch (ex: ActivityNotFoundException) {
                pickFile.launch("image/*")
            }
        }
        b.compatMode.isChecked = true
        if (applyMode) {
            b.compatMode.visibility = View.GONE
            b.editorNote.visibility = View.GONE
            b.showName.visibility = View.VISIBLE
            b.showName.isChecked = entry?.showLabel ?: true
            val sw = intent.getIntExtra(EXTRA_W, 0)
            val sh = intent.getIntExtra(EXTRA_H, 0)
            if (sw in 1..HomeLayout.COLS && sh in 1..HomeLayout.ROWS) {
                b.sizeLabel.visibility = View.VISIBLE
                b.sizeRow.visibility = View.VISIBLE
                b.sizeW.minValue = 1
                b.sizeW.maxValue = HomeLayout.COLS
                b.sizeW.value = sw
                b.sizeW.wrapSelectorWheel = false
                b.sizeH.minValue = 1
                b.sizeH.maxValue = HomeLayout.ROWS
                b.sizeH.value = sh
                b.sizeH.wrapSelectorWheel = false
                // The frame always has the shape of the tile it will fill.
                val update = {
                    val w = b.sizeW.value
                    val h = b.sizeH.value
                    b.editor.aspect = tileAspect(w, h)
                    b.editor.cornerFrac = if (w == 1 && h == 1) 0.24f else 0.12f
                    b.sizeInfo.text = getString(R.string.size_info, w, h)
                }
                b.sizeW.setOnValueChangedListener { _, _, _ -> update() }
                b.sizeH.setOnValueChangedListener { _, _, _ -> update() }
                b.showName.setOnCheckedChangeListener { _, _ -> update() }
                update()
            }
        }
        b.btnOriginal.setOnClickListener { loadOriginalIcon() }
        b.btnRotate.setOnClickListener {
            b.editor.angle = normalize(b.editor.angle + 90f)
            syncControls()
        }
        b.btnFit.setOnClickListener { b.editor.fitInside(); syncControls() }
        b.btnFill.setOnClickListener { b.editor.fillFrame(); syncControls() }
        b.btnCenter.setOnClickListener { b.editor.centerImage() }
        b.nudgeLeft.setOnClickListener { b.editor.nudge(-0.01f, 0f) }
        b.nudgeRight.setOnClickListener { b.editor.nudge(0.01f, 0f) }
        b.nudgeUp.setOnClickListener { b.editor.nudge(0f, -0.01f) }
        b.nudgeDown.setOnClickListener { b.editor.nudge(0f, 0.01f) }
        b.btnReset.setOnClickListener {
            b.editor.resetTransform()
            syncControls()
        }
        b.btnSave.setOnClickListener { save() }

        // Zoom slider (exponential scale) kept in step with pinch zoom.
        val zMin = b.editor.minZoom()
        val zMax = b.editor.maxZoom()
        val zRange = Math.log((zMax / zMin).toDouble())
        b.seekZoom.max = 100
        var zoomFromCode = false
        b.editor.onZoomChanged = { z ->
            zoomFromCode = true
            b.seekZoom.progress = (Math.log((z / zMin).toDouble()) / zRange * 100).toInt().coerceIn(0, 100)
            zoomFromCode = false
        }
        b.seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !zoomFromCode) {
                    b.editor.setZoomValue((zMin * Math.exp(zRange * progress / 100.0)).toFloat())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        b.seekRotation.max = 360
        b.seekRotation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) b.editor.angle = progress - 180f
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        b.bgGroup.setOnCheckedChangeListener { _, checkedId ->
            b.editor.bgMode = bgFromId(checkedId)
        }

        if (existing != null) restore(existing) else if (seed != null) restore(seed) else {
            b.nameInput.setText(appLabel)
            loadOriginalIcon()
        }
        refreshTexts()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    /** Frame shape = the tile's own shape: 1x1 square, 2x2 square, 3x2 is 3:2, 4x3 is 4:3. */
    private fun tileAspect(w: Int, h: Int): Float = (w.toFloat() / h).coerceIn(0.2f, 5f)

    // ---------------------------------------------------------------- loading images

    private fun restore(e: IconEntry) {
        b.nameInput.setText(e.label)
        val bmp = BitmapFactory.decodeFile(IconStore.get(this).sourceFile(e.srcFile).path)
        if (bmp == null) {
            loadOriginalIcon()
            return
        }
        source = bmp
        sourceDirty = false
        b.editor.setSource(bmp, false)
        b.editor.setState(EditorState(e.zoom, e.tx, e.ty, e.angle, e.bg))
        syncControls()
    }

    private fun loadOriginalIcon() {
        try {
            val bmp = packageManager.getApplicationIcon(pkg).toBitmap(256, 256, Bitmap.Config.ARGB_8888)
            useSource(bmp)
        } catch (ex: Exception) {
            toast(R.string.toast_app_missing)
        }
    }

    private fun loadFromUri(uri: Uri) {
        io.execute {
            val bmp = ImageUtils.decodeSampled(this, uri, SOURCE_MAX)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (bmp == null) toast(R.string.toast_image_failed) else useSource(bmp)
            }
        }
    }

    private fun useSource(bmp: Bitmap) {
        source = bmp
        sourceDirty = true
        b.editor.setSource(bmp, false)
        syncControls()
    }

    // ---------------------------------------------------------------- controls

    private fun normalize(v: Float): Float {
        var a = v % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    private fun syncControls() {
        b.seekRotation.progress = (b.editor.angle + 180f).toInt().coerceIn(0, 360)
        b.bgGroup.check(idFromBg(b.editor.bgMode))
    }

    private fun bgFromId(id: Int): Int = when (id) {
        R.id.bgBlack -> EditorView.BG_BLACK
        R.id.bgWhite -> EditorView.BG_WHITE
        R.id.bgGray -> EditorView.BG_GRAY
        else -> EditorView.BG_TRANSPARENT
    }

    private fun idFromBg(bg: Int): Int = when (bg) {
        EditorView.BG_BLACK -> R.id.bgBlack
        EditorView.BG_WHITE -> R.id.bgWhite
        EditorView.BG_GRAY -> R.id.bgGray
        else -> R.id.bgTransparent
    }

    private fun refreshTexts() {
        if (applyMode) {
            b.toolbar.setTitle(R.string.editor_title_apply)
            b.btnSave.setText(R.string.btn_apply)
        } else if (entry != null) {
            b.toolbar.setTitle(R.string.editor_title_edit)
            b.btnSave.setText(R.string.btn_update)
        } else {
            b.toolbar.setTitle(R.string.editor_title_new)
            b.btnSave.setText(R.string.btn_add_home)
        }
    }

    // ---------------------------------------------------------------- saving

    private fun save() {
        val src = source
        if (src == null || !b.editor.hasSource()) {
            toast(R.string.toast_pick_first)
            return
        }
        val label = b.nameInput.text.toString().trim().ifEmpty { appLabel }
        val state = b.editor.getState()
        val output = b.editor.renderOutput(if (applyMode) LAUNCHER_ICON_SIZE else ICON_SIZE) ?: return
        val previous = entry
        val needSource = sourceDirty || previous == null
        val frameAspect = b.editor.aspect

        b.btnSave.isEnabled = false
        io.execute {
            val store = IconStore.get(this)
            val id = previous?.id ?: IconStore.newId()
            val saved = try {
                val srcName = if (needSource) store.saveSource(id, src) else previous!!.srcFile
                store.saveIcon(id, output)
                val e = IconEntry(
                    id = id,
                    pkg = pkg,
                    activity = activityName,
                    appLabel = appLabel,
                    label = label,
                    srcFile = srcName,
                    zoom = state.zoom,
                    tx = state.tx,
                    ty = state.ty,
                    angle = state.angle,
                    bg = state.bg,
                    updatedAt = System.currentTimeMillis(),
                    launcher = applyMode,
                    showLabel = if (applyMode) b.showName.isChecked else true
                )
                store.upsert(e)
                // One icon per app: a shortcut made from the Zatchi screens also becomes the icon
                // the Zatchi home screen draws, with the same image and the same adjustments.
                if (!applyMode) syncLauncherIcon(store, src, state, label, frameAspect)
                e
            } catch (ex: Exception) {
                null
            }

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                b.btnSave.isEnabled = true
                if (saved == null) {
                    toast(R.string.toast_failed)
                    return@runOnUiThread
                }
                entry = saved
                sourceDirty = false
                refreshTexts()
                if (applyMode) {
                    toast(R.string.toast_icon_applied)
                    val shown = b.sizeRow.visibility == View.VISIBLE
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(EXTRA_W, if (shown) b.sizeW.value else 0)
                            .putExtra(EXTRA_H, if (shown) b.sizeH.value else 0)
                    )
                    finish()
                    return@runOnUiThread
                }
                var result = if (b.compatMode.isChecked) {
                    ShortcutHelper.pinLegacy(this, saved, output)
                } else {
                    ShortcutHelper.pinOrUpdate(this, saved, output)
                }
                // No launcher handles the old method: fall back to the standard one.
                if (b.compatMode.isChecked && result == ShortcutHelper.Result.UNSUPPORTED) {
                    result = ShortcutHelper.pinOrUpdate(this, saved, output)
                }
                when (result) {
                    ShortcutHelper.Result.REQUESTED_LEGACY -> toast(R.string.toast_legacy_sent)
                    ShortcutHelper.Result.REQUESTED -> toast(R.string.toast_pin_requested)
                    ShortcutHelper.Result.UPDATED -> toast(R.string.toast_updated)
                    ShortcutHelper.Result.UNSUPPORTED -> toast(R.string.toast_pin_unsupported)
                    ShortcutHelper.Result.FAILED -> toast(R.string.toast_failed)
                }
            }
        }
    }

    /** Keeps the home-screen (launcher) icon of this app in step with what was just saved. */
    private fun syncLauncherIcon(
        store: IconStore, src: Bitmap, state: EditorState, label: String, aspect: Float
    ) {
        try {
            val old = store.findLauncher(pkg, activityName)
            val id = old?.id ?: IconStore.newId()
            val srcName = store.saveSource(id, src)
            store.saveIcon(id, ImageUtils.render(src, state, aspect, LAUNCHER_ICON_SIZE))
            store.upsert(
                IconEntry(
                    id = id,
                    pkg = pkg,
                    activity = activityName,
                    appLabel = appLabel,
                    label = label,
                    srcFile = srcName,
                    zoom = state.zoom,
                    tx = state.tx,
                    ty = state.ty,
                    angle = state.angle,
                    bg = state.bg,
                    updatedAt = System.currentTimeMillis(),
                    launcher = true,
                    showLabel = old?.showLabel ?: true
                )
            )
        } catch (ex: Exception) {
            // The shortcut itself was saved; the home screen icon will simply stay as it was.
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }
}
