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
    private val onCategoryClick: (Category) -> Unit,
    private val onCategoryFocus: ((Category) -> Unit)? = null
) : ListAdapter<Category, CategoryAdapter.CategoryViewHolder>(CategoryDiffCallback) {

    var selectedCategoryId: String = Category.ID_ALL
        set(value) {
            val oldVal = field
            field = value
            if (oldVal != value) {
                notifyDataSetChanged()
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

    inner class CategoryViewHolder(
        private val binding: ItemCategoryTabBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            FocusHighlightHelper.attachFocusAnimation(
                binding.root,
                scale = 1.06f,
                focusedElevation = 8f
            ) { _, hasFocus ->
                if (hasFocus && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    onCategoryFocus?.invoke(getItem(bindingAdapterPosition))
                }
            }

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
    }

    companion object CategoryDiffCallback : DiffUtil.ItemCallback<Category>() {
        override fun areItemsTheSame(oldItem: Category, newItem: Category): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Category, newItem: Category): Boolean {
            return oldItem == newItem
        }
    }
}
