package com.iconstudio.app

import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iconstudio.app.databinding.ItemWidgetBinding

/** One widget offered by an installed app. */
data class WidgetOption(
    val info: AppWidgetProviderInfo,
    val appLabel: String,
    val label: String,
    val size: String,
    val preview: Drawable?
)

/**
 * Compact widget list grouped by app: a small app-name header, then that app's widgets.
 * [filter] narrows it by app or widget name.
 */
class WidgetAdapter(
    private val all: List<WidgetOption>,
    private val onPick: (WidgetOption) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class VH(val b: ItemWidgetBinding) : RecyclerView.ViewHolder(b.root)
    class HeaderVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private var rows: List<Any> = build("")

    private fun build(q: String): List<Any> {
        val query = q.trim().lowercase()
        val out = ArrayList<Any>()
        var last: String? = null
        for (o in all) {
            if (query.isNotEmpty() &&
                !o.label.lowercase().contains(query) && !o.appLabel.lowercase().contains(query)
            ) continue
            if (o.appLabel != last) {
                out.add(o.appLabel)
                last = o.appLabel
            }
            out.add(o)
        }
        return out
    }

    fun filter(q: String) {
        rows = build(q)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = if (rows[position] is String) 0 else 1

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) {
            val d = parent.resources.displayMetrics.density
            val tv = TextView(parent.context)
            tv.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tv.setTextColor(0xFF9A9A9A.toInt())
            tv.textSize = 11f
            tv.maxLines = 1
            tv.setPadding((4 * d).toInt(), (10 * d).toInt(), 0, (2 * d).toInt())
            return HeaderVH(tv)
        }
        return VH(ItemWidgetBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (holder is HeaderVH) {
            holder.tv.text = row as String
            return
        }
        val o = row as WidgetOption
        val vh = holder as VH
        vh.b.preview.setImageDrawable(o.preview)
        vh.b.title.text = o.label
        vh.b.subtitle.text = o.size
        vh.b.root.setOnClickListener { onPick(o) }
    }
}
