package com.example.audiobookplayer

import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.audiobookplayer.databinding.ItemShelfBookBinding
import com.example.audiobookplayer.databinding.ItemShelfFolderBinding

class ShelfAdapter(
    private val artLoader: AudioArtLoader,
    private val onFolderSelected: (AudioTreeNode) -> Unit,
    private val onBookSelected: (AudioTreeNode) -> Unit
) : ListAdapter<AudioTreeNode, RecyclerView.ViewHolder>(DiffCallback) {

    var selectedUri: Uri? = null
        set(value) {
            if (field == value) {
                return
            }
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isFolder) TYPE_FOLDER else TYPE_BOOK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            FolderViewHolder(ItemShelfFolderBinding.inflate(inflater, parent, false))
        } else {
            BookViewHolder(ItemShelfBookBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is FolderViewHolder -> holder.bind(getItem(position))
            is BookViewHolder -> holder.bind(getItem(position))
        }
    }

    fun playEntranceAnimation(recyclerView: RecyclerView) {
        val density = recyclerView.resources.displayMetrics.density
        val offsetPx = 36f * density
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index) ?: continue
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = offsetPx
            child.translationX = -offsetPx / 2f
            child.scaleX = 0.92f
            child.scaleY = 0.92f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((index * ENTRANCE_STAGGER_MS).coerceAtMost(ENTRANCE_MAX_DELAY_MS))
                .setDuration(ENTRANCE_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun attachPressAnimation(view: View) {
        view.setOnTouchListener { pressedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressedView.animate()
                    .scaleX(PRESS_SCALE)
                    .scaleY(PRESS_SCALE)
                    .setDuration(PRESS_DOWN_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressedView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(PRESS_UP_DURATION_MS)
                    .setInterpolator(OvershootInterpolator(1.6f))
                    .start()
            }
            false
        }
    }

    inner class FolderViewHolder(private val binding: ItemShelfFolderBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(node: AudioTreeNode) {
            binding.folderName.text = node.title
            val count = node.children.size
            binding.folderCount.text = binding.root.resources
                .getQuantityString(R.plurals.shelf_folder_items, count, count)
            attachPressAnimation(binding.root)
            binding.root.setOnClickListener { onFolderSelected(node) }
        }
    }

    inner class BookViewHolder(private val binding: ItemShelfBookBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(node: AudioTreeNode) {
            binding.shelfTitle.text = node.title
            binding.coverCard.strokeColor = if (node.uri == selectedUri) {
                ContextCompat.getColor(binding.root.context, R.color.teal_700)
            } else {
                Color.TRANSPARENT
            }
            binding.cover.tag = node.uri
            binding.cover.setImageResource(R.drawable.album_art_placeholder_thumb)
            node.audioFile?.let { audioFile ->
                artLoader.load(audioFile.uri, THUMBNAIL_SIZE) { bitmap ->
                    if (binding.cover.tag == node.uri && bitmap != null) {
                        binding.cover.setImageBitmap(bitmap)
                    }
                }
            }
            attachPressAnimation(binding.root)
            binding.root.setOnClickListener { onBookSelected(node) }
        }
    }

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_BOOK = 1
        private const val THUMBNAIL_SIZE = 320
        private const val ENTRANCE_STAGGER_MS = 35L
        private const val ENTRANCE_MAX_DELAY_MS = 420L
        private const val ENTRANCE_DURATION_MS = 260L
        private const val PRESS_DOWN_DURATION_MS = 80L
        private const val PRESS_UP_DURATION_MS = 160L
        private const val PRESS_SCALE = 0.94f

        private val DiffCallback = object : DiffUtil.ItemCallback<AudioTreeNode>() {
            override fun areItemsTheSame(oldItem: AudioTreeNode, newItem: AudioTreeNode): Boolean {
                return oldItem.uri == newItem.uri
            }

            override fun areContentsTheSame(oldItem: AudioTreeNode, newItem: AudioTreeNode): Boolean {
                return oldItem == newItem
            }
        }
    }
}
