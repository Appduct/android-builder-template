package %%PACKAGE_NAME%%

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for the native multi-link landing page. Each tile shows a pre-bundled
 * drawable (named tile_0, tile_1, …) and a title; tapping a tile invokes the
 * supplied callback with the destination URL.
 */
class LandingAdapter(
    private val context: Context,
    private val items: List<Map<String, String>>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<LandingAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.tileImage)
        val title: TextView = itemView.findViewById(R.id.tileTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.tile_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item["title"] ?: ""
        val resName = "tile_$position"
        val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
        if (resId != 0) {
            holder.image.setImageResource(resId)
        } else {
            holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        holder.itemView.setOnClickListener {
            val url = item["url"] ?: ""
            if (url.isNotEmpty()) onClick(url)
        }
    }

    override fun getItemCount(): Int = items.size
}
