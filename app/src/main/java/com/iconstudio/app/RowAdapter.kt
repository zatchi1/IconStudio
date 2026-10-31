package com.iconstudio.app

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.iconstudio.app.databinding.ItemRowBinding

/** One line in either list: an installed app, or a shortcut the user already created. */
data class Row(
    val title: String,
    val subtitle: String,
    val icon: Drawable?,
    val pkg: String,
    val activity: String,
    val entryId: String?
)

class RowAdapter(
    private val onClick: (Row) -> Unit,
    private val onDelete: (Row) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    private var items: List<Row> = emptyList()
    var showDelete = false

    class VH(val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<Row>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.b.icon.setImageDrawable(row.icon)
        holder.b.title.text = row.title
        holder.b.subtitle.text = row.subtitle
        holder.b.btnDelete.visibility = if (showDelete) View.VISIBLE else View.GONE
        holder.b.root.setOnClickListener { onClick(row) }
        holder.b.btnDelete.setOnClickListener { onDelete(row) }
    }
}
