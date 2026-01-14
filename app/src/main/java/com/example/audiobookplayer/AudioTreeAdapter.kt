package com.example.audiobookplayer

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.audiobookplayer.databinding.ItemTreeNodeBinding

class AudioTreeAdapter(
    private val artLoader: AudioArtLoader,
    private val onNodeSelected: (AudioTreeNode) -> Unit
) : ListAdapter<AudioTreeNode, AudioTreeAdapter.TreeViewHolder>(DiffCallback) {

    var selectedUri: Uri? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var expandedUris: Set<Uri> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): TreeViewHolder {
        val inflater = android.view.LayoutInflater.from(parent.context)
        val binding = ItemTreeNodeBinding.inflate(inflater, parent, false)
        return TreeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TreeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TreeViewHolder(private val binding: ItemTreeNodeBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(node: AudioTreeNode) {
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
                val isExpanded = expandedUris.contains(node.uri)
                binding.icon.setImageResource(
                    if (isExpanded) android.R.drawable.ic_menu_agenda else android.R.drawable.ic_menu_more
                )
                ImageViewCompat.setImageTintList(
                    binding.icon,
                    ContextCompat.getColorStateList(binding.root.context, android.R.color.white)
                )
            } else {
                ImageViewCompat.setImageTintList(binding.icon, null)
                binding.icon.setImageResource(android.R.drawable.ic_media_play)
                binding.icon.tag = node.uri
                artLoader.load(node.uri, THUMBNAIL_SIZE) { bitmap ->
                    if (binding.icon.tag == node.uri) {
                        if (bitmap != null) {
                            binding.icon.setImageBitmap(bitmap)
                        } else {
                            binding.icon.setImageResource(android.R.drawable.ic_media_play)
                        }
                    }
                }
            }

            val isSelected = !node.isFolder && node.uri == selectedUri
            val backgroundColor = if (isSelected) {
                ContextCompat.getColor(binding.root.context, R.color.teal_700)
            } else {
                Color.TRANSPARENT
            }
            binding.root.setBackgroundColor(backgroundColor)

            binding.root.setOnClickListener {
                onNodeSelected(node)
            }
        }
    }

    companion object {
        private const val THUMBNAIL_SIZE = 96

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
