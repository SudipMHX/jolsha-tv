package com.jolsha.tv.ui.player.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jolsha.tv.data.model.AudioTrackInfo
import com.jolsha.tv.databinding.ItemDialogAudioTrackBinding
import com.jolsha.tv.ui.common.FocusHighlightHelper

class AudioTrackAdapter(
    private val onTrackSelected: (AudioTrackInfo) -> Unit
) : ListAdapter<AudioTrackInfo, AudioTrackAdapter.AudioTrackViewHolder>(AudioTrackDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioTrackViewHolder {
        val binding = ItemDialogAudioTrackBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AudioTrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AudioTrackViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AudioTrackViewHolder(
        private val binding: ItemDialogAudioTrackBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            FocusHighlightHelper.attachFocusAnimation(binding.root, scale = 1.03f, focusedElevation = 6f)
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onTrackSelected(getItem(pos))
                }
            }
        }

        fun bind(track: AudioTrackInfo) {
            binding.textTrackName.text = track.label
            binding.imageTrackCheck.visibility = if (track.isSelected) View.VISIBLE else View.GONE
            binding.root.isSelected = track.isSelected
        }
    }

    companion object AudioTrackDiffCallback : DiffUtil.ItemCallback<AudioTrackInfo>() {
        override fun areItemsTheSame(oldItem: AudioTrackInfo, newItem: AudioTrackInfo): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AudioTrackInfo, newItem: AudioTrackInfo): Boolean = oldItem == newItem
    }
}
