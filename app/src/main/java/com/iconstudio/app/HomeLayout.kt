package com.iconstudio.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** One app, folder or widget placed on a home page, occupying w x h cells from (col, row). */
data class Placed(
    val key: String,
    var page: Int,          // page id (stable), not an index
    var col: Int,
    var row: Int,
    var w: Int = 1,
    var h: Int = 1
)

class Folder(val id: String, var name: String, val keys: MutableList<String>)

/**
 * Home screen data: a 2D grid of pages, placed items, folders, dock, hidden/locked apps, PIN.
 * Everything is stored in the app's private storage.
 */
class HomeLayout(ctx: Context) {

    companion object {
        const val COLS = 5
        const val ROWS = 6
    }

    private val file = File(ctx.filesDir, "home_layout.json")

    val grid = mutableListOf<MutableList<Int>>()
    private var nextId = 1
    var mainPage = 0
    var initialized = false
    val items = mutableListOf<Placed>()
    val dock = mutableListOf<String>()
    val known = mutableSetOf<String>()
    val folders = LinkedHashMap<String, Folder>()
    private var nextFolder = 1
    val locked = mutableSetOf<String>()
    val hidden = mutableSetOf<String>()
    private var pinSalt = ""
    private var pinHash = ""

    init {
        load()
        if (grid.isEmpty() || grid.all { it.isEmpty() }) {
            grid.clear()
            grid.add(mutableListOf(0))
            nextId = maxOf(nextId, 1)
        }
        if (mainPage !in allPages()) mainPage = allPages().first()
    }

    // ------------------------------------------------------------ storage

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            initialized = o.optBoolean("init", false)
            mainPage = o.optInt("main", 0)
            val g = o.optJSONArray("grid")
            if (g != null) {
                for (r in 0 until g.length()) {
                    val rowArr = g.getJSONArray(r)
                    val row = mutableListOf<Int>()
                    for (c in 0 until rowArr.length()) row.add(rowArr.getInt(c))
                    if (row.isNotEmpty()) grid.add(row)
                }
                nextId = o.optInt("nextId", (grid.flatten().maxOrNull() ?: 0) + 1)
            } else {
                val n = o.optInt("pages", 1).coerceAtLeast(1)   // older format: one row
                grid.add((0 until n).toMutableList())
                nextId = n
            }
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
            readStrings(o.optJSONArray("dock"), dock)
            readStrings(o.optJSONArray("known"), known)
            readStrings(o.optJSONArray("locked"), locked)
            readStrings(o.optJSONArray("hidden"), hidden)
            nextFolder = o.optInt("nextFolder", 1)
            o.optJSONArray("folders")?.let { a ->
                for (i in 0 until a.length()) {
                    val f = a.getJSONObject(i)
                    val keys = mutableListOf<String>()
                    readStrings(f.optJSONArray("keys"), keys)
                    folders[f.getString("id")] = Folder(f.getString("id"), f.optString("name", ""), keys)
                }
            }
            pinSalt = o.optString("pinSalt", "")
            pinHash = o.optString("pinHash", "")
        } catch (e: Exception) {
            // corrupted file: start with an empty layout
        }
    }

    private fun readStrings(a: JSONArray?, into: MutableCollection<String>) {
        if (a == null) return
        for (i in 0 until a.length()) into.add(a.getString(i))
    }

    fun save() {
        val o = JSONObject()
        o.put("init", initialized)
        o.put("main", mainPage)
        o.put("nextId", nextId)
        val g = JSONArray()
        grid.forEach { g.put(JSONArray(it)) }
        o.put("grid", g)
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
        o.put("locked", JSONArray(locked.toList()))
        o.put("hidden", JSONArray(hidden.toList()))
        o.put("nextFolder", nextFolder)
        val fa = JSONArray()
        folders.values.forEach {
            fa.put(JSONObject().put("id", it.id).put("name", it.name).put("keys", JSONArray(it.keys)))
        }
        o.put("folders", fa)
        o.put("pinSalt", pinSalt)
        o.put("pinHash", pinHash)
        val tmp = File(file.parentFile, "home_layout.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    // ------------------------------------------------------------ PIN

    fun hasPin() = pinHash.isNotEmpty()

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        pinSalt = salt.joinToString("") { "%02x".format(it) }
        pinHash = hash(pinSalt + pin)
    }

    fun checkPin(pin: String) = hasPin() && hash(pinSalt + pin) == pinHash

    // ------------------------------------------------------------ pages

    fun allPages(): List<Int> = grid.flatten()

    /** (row, column) of a page id. */
    fun locate(id: Int): Pair<Int, Int>? {
        for (r in grid.indices) {
            val c = grid[r].indexOf(id)
            if (c >= 0) return r to c
        }
        return null
    }

    fun addPageInRow(row: Int, index: Int): Int {
        val id = nextId++
        grid[row].add(index.coerceIn(0, grid[row].size), id)
        return id
    }

    fun addRow(index: Int): Int {
        val id = nextId++
        grid.add(index.coerceIn(0, grid.size), mutableListOf(id))
        return id
    }

    /** Removes a page (and its row if it becomes empty). Items on it are dropped. */
    fun removePage(id: Int) {
        items.removeAll { it.page == id }
        val loc = locate(id) ?: return
        grid[loc.first].remove(id)
        if (grid[loc.first].isEmpty()) grid.removeAt(loc.first)
        if (grid.isEmpty()) grid.add(mutableListOf(nextId++))
        if (mainPage !in allPages()) mainPage = allPages().first()
    }

    fun pageIsEmpty(id: Int) = items.none { it.page == id }

    /** Swaps the page with its left/right neighbour in its row. */
    fun movePageHorizontal(id: Int, delta: Int): Boolean {
        val (r, c) = locate(id) ?: return false
        val n = c + delta
        if (n < 0 || n >= grid[r].size) return false
        val row = grid[r]
        row[c] = row[n].also { row[n] = row[c] }
        return true
    }

    /** Moves the page to the row above/below (a new row is created at the ends). */
    fun movePageVertical(id: Int, delta: Int): Boolean {
        val (r, c) = locate(id) ?: return false
        if (grid[r].size == 1) {
            // Alone in its row: swap whole rows (nothing to do at the ends).
            val n = r + delta
            if (n < 0 || n >= grid.size) return false
            val row = grid[r]
            grid[r] = grid[n].also { grid[n] = row }
            return true
        }
        grid[r].removeAt(c)
        val target = r + delta
        when {
            target < 0 -> grid.add(0, mutableListOf(id))
            target >= grid.size -> grid.add(mutableListOf(id))
            else -> grid[target].add(minOf(c, grid[target].size), id)
        }
        return true
    }

    // ------------------------------------------------------------ placement

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

    fun inFolder(key: String) = folders.values.any { key in it.keys }

    fun isPlaced(key: String) = items.any { it.key == key } || inFolder(key)

    /**
     * Finds room for a w x h item starting from [startPage] and going through all pages.
     * If every page is full, a page is added at the end of row [newPageRow].
     */
    fun allocate(key: String, w: Int, h: Int, startPage: Int, newPageRow: Int): Placed {
        val pages = allPages()
        val start = pages.indexOf(startPage).coerceAtLeast(0)
        val order = pages.drop(start) + pages.take(start)
        for (p in order) {
            val free = findFree(p, w, h) ?: continue
            return Placed(key, p, free.first, free.second, w, h)
        }
        val row = newPageRow.coerceIn(0, grid.size - 1)
        val id = addPageInRow(row, grid[row].size)
        return Placed(key, id, 0, 0, w, h)
    }

    fun addAuto(key: String, startPage: Int = mainPage, newPageRow: Int = grid.size - 1) {
        items.add(allocate(key, 1, 1, startPage, newPageRow))
    }

    /**
     * Drops a 1x1 [item] on an occupied cell the way phones usually do: the icons around it
     * shift along (in reading order) up to the nearest empty cell to make room.
     */
    fun insertWithPush(item: Placed, page: Int, col: Int, row: Int): Boolean {
        if (item.w != 1 || item.h != 1) return false
        // Cells that can hold a small icon: all except those covered by bigger items.
        val cells = ArrayList<Pair<Int, Int>>()
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val cover = placedAt(page, c, r, item)
            if (cover == null || (cover.w == 1 && cover.h == 1)) cells.add(c to r)
        }
        val idx = cells.indexOf(col to row)
        if (idx < 0) return false
        fun occupant(i: Int): Placed? = placedAt(page, cells[i].first, cells[i].second, item)

        var free = -1
        for (d in 0 until cells.size) {
            val f = idx + d
            if (f < cells.size && occupant(f) == null) { free = f; break }
            val bk = idx - d
            if (bk >= 0 && occupant(bk) == null) { free = bk; break }
        }
        if (free == -1) return false

        if (free > idx) {
            for (k in free downTo idx + 1) {
                val o = occupant(k - 1) ?: continue
                o.col = cells[k].first; o.row = cells[k].second
            }
        } else if (free < idx) {
            for (k in free until idx) {
                val o = occupant(k + 1) ?: continue
                o.col = cells[k].first; o.row = cells[k].second
            }
        }
        item.page = page; item.col = col; item.row = row
        return true
    }

    // ------------------------------------------------------------ folders

    fun newFolder(name: String, keys: List<String>): Folder {
        val f = Folder("f" + nextFolder++, name, keys.toMutableList())
        folders[f.id] = f
        return f
    }
}
