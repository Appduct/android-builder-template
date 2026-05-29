package %%PACKAGE_NAME%%

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Adapter for the native multi-link landing page. Each tile shows a title plus
 * an image. Image source resolution order:
 *   1. Bundled drawable named tile_0, tile_1, … (downloaded at build time).
 *   2. Remote `imageUrl` from the JSON payload — fetched on demand and cached
 *      in-memory so users see the real tile artwork even if the build-time
 *      download silently failed (e.g. network blip on the GitHub runner).
 */
class LandingAdapter(
    private val context: Context,
    private val items: List<Map<String, String>>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<LandingAdapter.VH>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newFixedThreadPool(3)

    // Small in-memory cache (~8MB) so scrolling doesn't refetch tile bitmaps.
    private val bitmapCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

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
        holder.image.setImageDrawable(null)

        // First, try a build-time bundled drawable named tile_N.
        val resName = "tile_$position"
        val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
        if (resId != 0) {
            holder.image.setImageResource(resId)
        }

        // Always try the remote URL too — if the build-time download failed or
        // the user updated images after the build, this still shows the right art.
        val url = item["imageUrl"] ?: ""
        if (url.isNotEmpty()) {
            val cached = bitmapCache.get(url)
            if (cached != null) {
                holder.image.setImageBitmap(cached)
            } else {
                holder.image.tag = url
                ioExecutor.execute {
                    val bmp = downloadBitmap(url)
                    if (bmp != null) {
                        bitmapCache.put(url, bmp)
                        mainHandler.post {
                            if (holder.image.tag == url) holder.image.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }

        val destination = item["url"] ?: ""
        holder.itemView.setOnClickListener {
            if (destination.isNotEmpty()) onClick(destination)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.doInput = true
            conn.connect()
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
}
