package com.example.audiobookplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.audiobookplayer.databinding.ItemBookmarkNodeBinding
import java.io.File
import java.util.concurrent.Executors
import android.util.LruCache
import com.example.audiobookplayer.bookmarks.BookmarkEntity

class BookmarkTreeAdapter(
    private val onNodeSelected: (BookmarkTreeNode) -> Unit,
    private val onClipToggle: (BookmarkEntity) -> Unit
) : ListAdapter<BookmarkTreeNode, BookmarkTreeAdapter.BookmarkViewHolder>(DiffCallback) {

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    var expandedIds: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var playingBookmarkId: Long? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): BookmarkViewHolder {
        val inflater = android.view.LayoutInflater.from(parent.context)
        val binding = ItemBookmarkNodeBinding.inflate(inflater, parent, false)
        return BookmarkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BookmarkViewHolder(private val binding: ItemBookmarkNodeBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(node: BookmarkTreeNode) {
            val density = binding.root.resources.displayMetrics.density
            val basePadding = (12 * density).toInt()
            val indentPadding = (node.depth * 16 * density).toInt()
            binding.root.setPaddingRelative(
                basePadding + indentPadding,
                binding.root.paddingTop,
                binding.root.paddingEnd,
                binding.root.paddingBottom
            )
            binding.title.text = node.title
            binding.title.setTypeface(null, if (node.isFolder) Typeface.BOLD else Typeface.NORMAL)

            if (node.isFolder) {
                val isExpanded = expandedIds.contains(node.id)
                binding.expandIndicator.isVisible = true
                binding.expandIndicator.setImageResource(
                    if (isExpanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
                )
                binding.icon.tag = node.coverImagePath
                loadCoverImage(node.coverImagePath, binding.icon)
                binding.playToggle.isVisible = false
            } else {
                binding.expandIndicator.isVisible = false
                binding.icon.setImageResource(R.drawable.ic_bookmark)
                ImageViewCompat.setImageTintList(
                    binding.icon,
                    ContextCompat.getColorStateList(binding.root.context, android.R.color.white)
                )
                val bookmark = node.bookmark
                val clipPath = bookmark?.audioClipPath
                if (bookmark != null && !clipPath.isNullOrBlank()) {
                    val isPlaying = bookmark.id == playingBookmarkId
                    binding.playToggle.isVisible = true
                    binding.playToggle.setImageResource(
                        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                    )
                    binding.playToggle.contentDescription = binding.root.context.getString(
                        if (isPlaying) R.string.stop_bookmark_clip else R.string.play_bookmark_clip
                    )
                    binding.playToggle.setOnClickListener {
                        onClipToggle(bookmark)
                    }
                } else {
                    binding.playToggle.isVisible = false
                    binding.playToggle.setOnClickListener(null)
                }
            }

            binding.root.setOnClickListener {
                onNodeSelected(node)
            }
        }
    }

    private fun loadCoverImage(path: String?, imageView: android.widget.ImageView) {
        if (path.isNullOrBlank()) {
            imageView.setImageResource(R.drawable.ic_bookmark)
            ImageViewCompat.setImageTintList(
                imageView,
                ContextCompat.getColorStateList(imageView.context, android.R.color.white)
            )
            return
        }
        val cached = cache.get(path)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            ImageViewCompat.setImageTintList(imageView, null)
            return
        }
        imageView.setImageResource(R.drawable.ic_bookmark)
        ImageViewCompat.setImageTintList(
            imageView,
            ContextCompat.getColorStateList(imageView.context, android.R.color.white)
        )
        executor.execute {
            val file = File(path)
            val bitmap = if (file.exists()) {
                decodeSampledBitmap(path, THUMBNAIL_SIZE)
            } else {
                null
            }
            if (bitmap != null) {
                cache.put(path, bitmap)
            }
            mainHandler.post {
                if (imageView.tag == path && bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    ImageViewCompat.setImageTintList(imageView, null)
                }
            }
        }
    }

    private fun decodeSampledBitmap(path: String, reqSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options, reqSize, reqSize)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    companion object {
        private const val THUMBNAIL_SIZE = 96
        private const val MAX_CACHE_SIZE = 2 * 1024 * 1024

        private val DiffCallback = object : DiffUtil.ItemCallback<BookmarkTreeNode>() {
            override fun areItemsTheSame(oldItem: BookmarkTreeNode, newItem: BookmarkTreeNode): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: BookmarkTreeNode, newItem: BookmarkTreeNode): Boolean {
                return oldItem == newItem
            }
        }
    }
}
