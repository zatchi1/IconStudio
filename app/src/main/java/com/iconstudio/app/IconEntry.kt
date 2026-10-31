package com.iconstudio.app

import org.json.JSONObject

/**
 * One customised shortcut. Everything needed to re-open the editor exactly as it was
 * (source image + crop/zoom/rotation/background) is stored here.
 */
data class IconEntry(
    val id: String,
    val pkg: String,
    val activity: String,
    val appLabel: String,
    val label: String,
    val srcFile: String,
    val zoom: Float,
    val tx: Float,
    val ty: Float,
    val angle: Float,
    val bg: Int,
    val updatedAt: Long,
    /** true = icon used by the zatchi home screen; false = icon of a home-screen shortcut. */
    val launcher: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("pkg", pkg)
        put("activity", activity)
        put("appLabel", appLabel)
        put("label", label)
        put("srcFile", srcFile)
        put("zoom", zoom.toDouble())
        put("tx", tx.toDouble())
        put("ty", ty.toDouble())
        put("angle", angle.toDouble())
        put("bg", bg)
        put("updatedAt", updatedAt)
        put("launcher", launcher)
    }

    companion object {
        fun fromJson(o: JSONObject): IconEntry = IconEntry(
            id = o.getString("id"),
            pkg = o.getString("pkg"),
            activity = o.optString("activity", ""),
            appLabel = o.optString("appLabel", ""),
            label = o.optString("label", ""),
            srcFile = o.optString("srcFile", ""),
            zoom = o.optDouble("zoom", 1.0).toFloat(),
            tx = o.optDouble("tx", 0.0).toFloat(),
            ty = o.optDouble("ty", 0.0).toFloat(),
            angle = o.optDouble("angle", 0.0).toFloat(),
            bg = o.optInt("bg", 0),
            updatedAt = o.optLong("updatedAt", 0L),
            launcher = o.optBoolean("launcher", false)
        )
    }
}
