package com.iconstudio.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One app placed on a home page, occupying w x h cells starting at (col, row). */
data class Placed(
    val key: String,
    var page: Int,
    var col: Int,
    var row: Int,
    var w: Int = 1,
    var h: Int = 1
)

/** Home screen layout: pages, placed apps, dock. Saved to private storage. */
class HomeLayout(ctx: Context) {

    companion object {
        const val COLS = 5
        const val ROWS = 6
    }

    private val file = File(ctx.filesDir, "home_layout.json")

    var pages = 1
    var initialized = false
    val items = mutableListOf<Placed>()
    val dock = mutableListOf<String>()
    val known = mutableSetOf<String>()

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            pages = o.optInt("pages", 1).coerceAtLeast(1)
            initialized = o.optBoolean("init", false)
            o.optJSONArray("items")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    items.add(
                        Placed(
                            x.getString("k"), x.getInt("p"), x.getInt("c"), x.getInt("r"),
                            x.optInt("w", 1), x.optInt("h", 1)
                        )
                    )
                }
            }
            o.optJSONArray("dock")?.let { a -> for (i in 0 until a.length()) dock.add(a.getString(i)) }
            o.optJSONArray("known")?.let { a -> for (i in 0 until a.length()) known.add(a.getString(i)) }
        } catch (e: Exception) {
            // corrupted file: start with an empty layout
        }
    }

    fun save() {
        val o = JSONObject()
        o.put("pages", pages)
        o.put("init", initialized)
        val a = JSONArray()
        items.forEach {
            a.put(
                JSONObject().put("k", it.key).put("p", it.page).put("c", it.col)
                    .put("r", it.row).put("w", it.w).put("h", it.h)
            )
        }
        o.put("items", a)
        o.put("dock", JSONArray(dock))
        o.put("known", JSONArray(known.toList()))
        val tmp = File(file.parentFile, "home_layout.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** The item covering cell (col,row) of [page], ignoring [ignore]. */
    fun placedAt(page: Int, col: Int, row: Int, ignore: Placed?): Placed? =
        items.firstOrNull {
            it !== ignore && it.page == page &&
                col >= it.col && col < it.col + it.w && row >= it.row && row < it.row + it.h
        }

    fun fits(page: Int, col: Int, row: Int, w: Int, h: Int, ignore: Placed?): Boolean {
        if (col < 0 || row < 0 || col + w > COLS || row + h > ROWS) return false
        for (c in col until col + w) for (r in row until row + h) {
            if (placedAt(page, c, r, ignore) != null) return false
        }
        return true
    }

    fun findFree(page: Int, w: Int, h: Int, ignore: Placed? = null): Pair<Int, Int>? {
        for (r in 0..(ROWS - h)) for (c in 0..(COLS - w)) {
            if (fits(page, c, r, w, h, ignore)) return c to r
        }
        return null
    }

    fun isPlaced(key: String) = items.any { it.key == key }

    /** Puts the app on the first free cell, starting from [startPage]; adds a page if full. */
    fun addAuto(key: String, startPage: Int = 0) {
        val order = (startPage until pages) + (0 until startPage)
        for (p in order) {
            val free = findFree(p, 1, 1) ?: continue
            items.add(Placed(key, p, free.first, free.second))
            return
        }
        pages++
        items.add(Placed(key, pages - 1, 0, 0))
    }

    fun pageIsEmpty(page: Int) = items.none { it.page == page }

    fun removePage(page: Int) {
        items.filter { it.page > page }.forEach { it.page-- }
        pages--
    }
}
