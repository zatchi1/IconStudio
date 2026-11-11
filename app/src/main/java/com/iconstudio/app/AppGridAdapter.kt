package com.iconstudio.app

import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.recyclerview.widget.RecyclerView
import com.iconstudio.app.databinding.ItemAppBinding

/** Rounded-square clip for custom icons. */
object IconOutline : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.24f)
    }
}

/** Rounded clip for big (multi-cell) tiles. */
object TileOutline : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, minOf(view.width, view.height) * 0.12f)
    }
}

/** An installed launchable app. */
data class AppItem(
    val key: String,
    val pkg: String,
    val activity: String,
    val label: String,
    val icon: Drawable?
)

/** What is drawn for one app: the original or the custom icon/name. */
data class Cell(
    val app: AppItem,
    val label: String,
    val icon: Drawable?,
    val custom: Boolean,
    val showLabel: Boolean = true
)

class AppGridAdapter(
    private val onClick: (Cell) -> Unit,
    private val onLongClick: (Cell) -> Unit,
    /** Fixed icon size in px (same as on the home screen); 0 = keep the size from the layout file. */
    private val iconSize: () -> Int = { 0 }
) : RecyclerView.Adapter<AppGridAdapter.VH>() {

    private var items: List<Cell> = emptyList()

    class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<Cell>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        // Custom (square) images get the same rounded shape as other icons.
        vh.b.icon.outlineProvider = IconOutline
        val s = iconSize()
        if (s > 0) {
            val lp = vh.b.icon.layoutParams
            lp.width = s
            lp.height = s
            vh.b.icon.layoutParams = lp
        }
        return vh
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cell = items[position]
        holder.b.icon.setImageDrawable(cell.icon)
        holder.b.icon.clipToOutline = cell.custom
        holder.b.label.text = cell.label
        holder.b.root.setOnClickListener { onClick(cell) }
        holder.b.root.setOnLongClickListener {
            onLongClick(cell)
            true
        }
    }
}
