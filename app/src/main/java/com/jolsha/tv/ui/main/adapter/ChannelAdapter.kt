package com.jolsha.tv.ui.main.adapter

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.jolsha.tv.R
import com.jolsha.tv.data.model.Channel
import com.jolsha.tv.databinding.ItemChannelCardBinding
import com.jolsha.tv.ui.common.FocusHighlightHelper

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelLongClick: (Channel) -> Boolean,
    private val onChannelFocused: ((Channel, Int) -> Unit)? = null
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChannelViewHolder(
        private val binding: ItemChannelCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            FocusHighlightHelper.attachFocusAnimation(
                binding.root,
                scale = 1.05f,
                focusedElevation = 14f
            ) { _, hasFocus ->
                if (hasFocus && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    onChannelFocused?.invoke(getItem(bindingAdapterPosition), bindingAdapterPosition)
                }
            }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelClick(getItem(pos))
                }
            }

            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelLongClick(getItem(pos))
                } else false
            }

            // Remote Control Key Listener: Handle DPAD_CENTER long click or PROG_YELLOW key
            binding.root.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.isLongPress && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            return@setOnKeyListener onChannelLongClick(getItem(pos))
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_PROG_YELLOW) {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            return@setOnKeyListener onChannelLongClick(getItem(pos))
                        }
                    }
                }
                false
            }
        }

        fun bind(channel: Channel) {
            // Channel Name
            binding.textChannelName.text = channel.displayName

            // Channel Number
            if (channel.formattedNumber.isNotBlank()) {
                binding.textChannelNumber.text = "#${channel.formattedNumber}"
                binding.textChannelNumber.visibility = View.VISIBLE
            } else {
                binding.textChannelNumber.visibility = View.GONE
            }

            // Category Tag
            if (channel.category.isNotBlank() && channel.category != "ALL CHANNELS") {
                binding.textCategoryTag.text = channel.category.uppercase()
                binding.textCategoryTag.visibility = View.VISIBLE
            } else {
                binding.textCategoryTag.visibility = View.GONE
            }

            // Favorite Star
            binding.imageFavoriteBadge.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE

            // Channel Logo with Coil + Fallback text badge
            val logoUrl = channel.logoUrl.trim()
            if (logoUrl.isNotBlank()) {
                binding.textLogoFallback.visibility = View.GONE
                binding.imageChannelLogo.visibility = View.VISIBLE
                binding.imageChannelLogo.load(logoUrl) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(8f))
                    listener(
                        onError = { _, _ ->
                            showFallbackMonogram(channel)
                        },
                        onSuccess = { _, _ ->
                            binding.textLogoFallback.visibility = View.GONE
                            binding.imageChannelLogo.visibility = View.VISIBLE
                        }
                    )
                }
            } else {
                showFallbackMonogram(channel)
            }
        }

        private fun showFallbackMonogram(channel: Channel) {
            binding.imageChannelLogo.visibility = View.GONE
            binding.textLogoFallback.visibility = View.VISIBLE
            binding.textLogoFallback.text = extractMonogram(channel.displayName)
        }

        private fun extractMonogram(name: String): String {
            val words = name.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
            return when {
                words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
                words.size == 1 && words[0].length >= 3 -> words[0].take(3).uppercase()
                words.size == 1 -> words[0].uppercase()
                else -> "TV"
            }
        }
    }

    companion object ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
}
