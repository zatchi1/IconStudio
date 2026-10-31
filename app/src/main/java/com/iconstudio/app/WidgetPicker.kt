package com.iconstudio.app

import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
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

class WidgetAdapter(
    private val items: List<WidgetOption>,
    private val onPick: (WidgetOption) -> Unit
) : RecyclerView.Adapter<WidgetAdapter.VH>() {

    class VH(val b: ItemWidgetBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemWidgetBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val o = items[position]
        holder.b.preview.setImageDrawable(o.preview)
        holder.b.title.text = o.label
        holder.b.subtitle.text = o.appLabel + "  ·  " + o.size
        holder.b.root.setOnClickListener { onPick(o) }
    }
}
