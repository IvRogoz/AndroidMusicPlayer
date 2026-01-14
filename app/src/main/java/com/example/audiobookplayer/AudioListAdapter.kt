package com.example.audiobookplayer

import android.graphics.Color
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.audiobookplayer.databinding.ItemAudioFileBinding

class AudioListAdapter(
    private val artLoader: AudioArtLoader,
    private val onItemSelected: (AudioFile) -> Unit
) : ListAdapter<AudioFile, AudioListAdapter.AudioViewHolder>(DiffCallback) {

    var selectedUri: Uri? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): AudioViewHolder {
        val inflater = android.view.LayoutInflater.from(parent.context)
        val binding = ItemAudioFileBinding.inflate(inflater, parent, false)
        return AudioViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).uri == selectedUri)
    }

    inner class AudioViewHolder(private val binding: ItemAudioFileBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(audioFile: AudioFile, isSelected: Boolean) {
            binding.title.text = audioFile.title
            binding.duration.text = formatDuration(audioFile.durationMs)
            binding.thumbnail.setImageResource(android.R.drawable.ic_media_play)
            binding.thumbnail.tag = audioFile.uri
            artLoader.load(audioFile.uri, THUMBNAIL_SIZE) { bitmap ->
                if (binding.thumbnail.tag == audioFile.uri) {
                    if (bitmap != null) {
                        binding.thumbnail.setImageBitmap(bitmap)
                    } else {
                        binding.thumbnail.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
            }
            val backgroundColor = if (isSelected) {
                ContextCompat.getColor(binding.root.context, R.color.teal_200)
            } else {
                Color.TRANSPARENT
            }
            binding.root.setBackgroundColor(backgroundColor)
            binding.root.setOnClickListener {
                onItemSelected(audioFile)
            }
        }
    }

    companion object {
        private const val THUMBNAIL_SIZE = 96

        private val DiffCallback = object : DiffUtil.ItemCallback<AudioFile>() {
            override fun areItemsTheSame(oldItem: AudioFile, newItem: AudioFile): Boolean {
                return oldItem.uri == newItem.uri
            }

            override fun areContentsTheSame(oldItem: AudioFile, newItem: AudioFile): Boolean {
                return oldItem == newItem
            }
        }
    }
}
