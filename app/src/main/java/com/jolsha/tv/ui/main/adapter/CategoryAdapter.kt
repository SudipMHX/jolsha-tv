package com.jolsha.tv.ui.main.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jolsha.tv.data.model.Category
import com.jolsha.tv.databinding.ItemCategoryTabBinding
import com.jolsha.tv.ui.common.FocusHighlightHelper

class CategoryAdapter(
    private val onCategoryClick: (Category) -> Unit
) : ListAdapter<Category, CategoryAdapter.CategoryViewHolder>(CategoryDiffCallback) {

    companion object {
        private const val PAYLOAD_SELECTION = "payload_selection"

        val CategoryDiffCallback = object : DiffUtil.ItemCallback<Category>() {
            override fun areItemsTheSame(oldItem: Category, newItem: Category): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: Category, newItem: Category): Boolean {
                return oldItem == newItem
            }
        }
    }

    var selectedCategoryId: String = Category.ID_ALL
        set(value) {
            val oldVal = field
            field = value
            if (oldVal != value) {
                val oldIndex = currentList.indexOfFirst { it.id == oldVal }
                val newIndex = currentList.indexOfFirst { it.id == value }
                if (oldIndex != -1) notifyItemChanged(oldIndex, PAYLOAD_SELECTION)
                if (newIndex != -1) notifyItemChanged(newIndex, PAYLOAD_SELECTION)
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryTabBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class CategoryViewHolder(
        private val binding: ItemCategoryTabBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            FocusHighlightHelper.attachFocusAnimation(
                binding.root,
                scale = 1.06f,
                focusedElevation = 8f
            )

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val category = getItem(pos)
                    selectedCategoryId = category.id
                    onCategoryClick(category)
                }
            }
        }

        fun bind(category: Category) {
            binding.textCategoryName.text = category.name
            binding.textCategoryCount.text = category.count.toString()
            binding.root.isSelected = (category.id == selectedCategoryId)
        }

        fun bindSelection(category: Category) {
            binding.root.isSelected = (category.id == selectedCategoryId)
        }
    }
}
