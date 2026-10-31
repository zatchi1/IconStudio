package com.iconstudio.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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

        private const val ICON_SIZE = 192
        private const val SOURCE_MAX = 1280
    }

    private lateinit var b: ActivityEditorBinding
    private var entry: IconEntry? = null
    private var pkg = ""
    private var activityName = ""
    private var appLabel = ""

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

        val existing = intent.getStringExtra(EXTRA_ENTRY_ID)?.let { IconStore.get(this).get(it) }
        entry = existing
        pkg = existing?.pkg ?: intent.getStringExtra(EXTRA_PKG).orEmpty()
        if (pkg.isEmpty()) {
            finish()
            return
        }
        activityName = existing?.activity ?: intent.getStringExtra(EXTRA_ACTIVITY).orEmpty()
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
        b.compatMode.isChecked = false
        b.btnOriginal.setOnClickListener { loadOriginalIcon() }
        b.btnRotate.setOnClickListener {
            b.editor.angle = normalize(b.editor.angle + 90f)
            syncControls()
        }
        b.btnReset.setOnClickListener {
            b.editor.resetTransform()
            syncControls()
        }
        b.btnSave.setOnClickListener { save() }

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

        if (existing != null) restore(existing) else {
            b.nameInput.setText(appLabel)
            loadOriginalIcon()
        }
        refreshTexts()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

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
        if (entry != null) {
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
        val output = b.editor.renderOutput(ICON_SIZE) ?: return
        val previous = entry
        val needSource = sourceDirty || previous == null

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
                    updatedAt = System.currentTimeMillis()
                )
                store.upsert(e)
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
                val result = if (b.compatMode.isChecked) {
                    ShortcutHelper.pinLegacy(this, saved, output)
                } else {
                    ShortcutHelper.pinOrUpdate(this, saved, output)
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

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }
}
