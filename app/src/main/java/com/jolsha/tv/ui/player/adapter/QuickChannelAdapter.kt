package com.jolsha.tv.ui.player.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.jolsha.tv.data.model.Channel
import com.jolsha.tv.databinding.ItemQuickChannelBinding
import com.jolsha.tv.ui.common.FocusHighlightHelper

class QuickChannelAdapter(
    private val onChannelSelected: (Channel) -> Unit
) : ListAdapter<Channel, QuickChannelAdapter.QuickChannelViewHolder>(QuickChannelDiffCallback) {

    var currentPlayingChannelId: String? = null
        set(value) {
            val oldVal = field
            field = value
            if (oldVal != value) {
                notifyDataSetChanged()
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuickChannelViewHolder {
        val binding = ItemQuickChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return QuickChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QuickChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class QuickChannelViewHolder(
        private val binding: ItemQuickChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            FocusHighlightHelper.attachFocusAnimation(
                binding.root,
                scale = 1.03f,
                focusedElevation = 6f
            )

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelSelected(getItem(pos))
                }
            }
        }

        fun bind(channel: Channel) {
            binding.textQuickName.text = channel.displayName
            binding.textQuickCategory.text = channel.category.uppercase()

            if (channel.formattedNumber.isNotBlank()) {
                binding.textQuickNumber.text = channel.formattedNumber
                binding.textQuickNumber.visibility = View.VISIBLE
            } else {
                binding.textQuickNumber.visibility = View.GONE
            }

            val isCurrent = (channel.id == currentPlayingChannelId)
            binding.root.isSelected = isCurrent
            binding.imageQuickPlaying.visibility = if (isCurrent) View.VISIBLE else View.GONE

            val logoUrl = channel.logoUrl.trim()
            if (logoUrl.isNotBlank()) {
                binding.textQuickFallback.visibility = View.GONE
                binding.imageQuickLogo.visibility = View.VISIBLE
                binding.imageQuickLogo.load(logoUrl) {
                    crossfade(true)
                    transformations(RoundedCornersTransformation(6f))
                    listener(
                        onError = { _, _ -> showFallback(channel) },
                        onSuccess = { _, _ ->
                            binding.textQuickFallback.visibility = View.GONE
                            binding.imageQuickLogo.visibility = View.VISIBLE
                        }
                    )
                }
            } else {
                showFallback(channel)
            }
        }

        private fun showFallback(channel: Channel) {
            binding.imageQuickLogo.visibility = View.GONE
            binding.textQuickFallback.visibility = View.VISIBLE
            val words = channel.displayName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
            binding.textQuickFallback.text = when {
                words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
                words.size == 1 && words[0].length >= 3 -> words[0].take(3).uppercase()
                words.size == 1 -> words[0].uppercase()
                else -> "TV"
            }
        }
    }

    companion object QuickChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem == newItem
    }
}
