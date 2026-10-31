package com.iconstudio.app

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Local-only persistence in the app's private storage (survives app restarts and reboots).
 *  - filesDir/entries.json          : list of [IconEntry]
 *  - filesDir/icons/<id>.png        : final 192px icon used by the shortcut
 *  - filesDir/icons/<id>_src.*      : the source image, so the icon can be re-edited later
 */
class IconStore private constructor(ctx: Context) {

    private val dir = File(ctx.filesDir, "icons").apply { mkdirs() }
    private val dataFile = File(ctx.filesDir, "entries.json")
    private val lock = Any()

    fun list(): List<IconEntry> = synchronized(lock) {
        readAll().sortedByDescending { it.updatedAt }
    }

    fun get(id: String): IconEntry? = synchronized(lock) {
        readAll().firstOrNull { it.id == id }
    }

    /** The home-screen (launcher) icon already saved for this app, if any. */
    fun findLauncher(pkg: String, activity: String): IconEntry? = synchronized(lock) {
        readAll().filter { it.launcher && it.pkg == pkg && it.activity == activity }
            .maxByOrNull { it.updatedAt }
    }

    fun upsert(entry: IconEntry) = synchronized(lock) {
        writeAll(readAll().filter { it.id != entry.id } + entry)
    }

    fun delete(id: String) = synchronized(lock) {
        writeAll(readAll().filter { it.id != id })
        iconFile(id).delete()
        deleteSources(id)
    }

    fun iconFile(id: String): File = File(dir, "$id.png")

    fun sourceFile(name: String): File = File(dir, name)

    fun saveIcon(id: String, bitmap: Bitmap) {
        writeBitmap(iconFile(id), bitmap, Bitmap.CompressFormat.PNG)
    }

    /** Saves the source image and returns its file name. PNG if it has transparency, else JPEG. */
    fun saveSource(id: String, bitmap: Bitmap): String {
        deleteSources(id)
        val png = bitmap.hasAlpha()
        val name = if (png) "${id}_src.png" else "${id}_src.jpg"
        writeBitmap(
            File(dir, name),
            bitmap,
            if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        )
        return name
    }

    private fun deleteSources(id: String) {
        File(dir, "${id}_src.png").delete()
        File(dir, "${id}_src.jpg").delete()
    }

    private fun writeBitmap(file: File, bitmap: Bitmap, format: Bitmap.CompressFormat) {
        FileOutputStream(file).use { out -> bitmap.compress(format, 92, out) }
    }

    private fun readAll(): List<IconEntry> {
        if (!dataFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(dataFile.readText())
            (0 until arr.length()).map { IconEntry.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(entries: List<IconEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        val tmp = File(dataFile.parentFile, "entries.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(dataFile)) {
            dataFile.delete()
            tmp.renameTo(dataFile)
        }
    }

    companion object {
        @Volatile
        private var instance: IconStore? = null

        fun get(ctx: Context): IconStore =
            instance ?: synchronized(this) {
                instance ?: IconStore(ctx.applicationContext).also { instance = it }
            }

        fun newId(): String = "icon_" + UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
