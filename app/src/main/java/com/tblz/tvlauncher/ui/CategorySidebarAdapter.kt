package com.tblz.tvlauncher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tblz.tvlauncher.R
import com.tblz.tvlauncher.model.CategoryData

class CategorySidebarAdapter(
    private val onCategorySelected: (CategoryData) -> Unit
) : RecyclerView.Adapter<CategorySidebarAdapter.CategoryViewHolder>() {

    val items = mutableListOf<CategoryData>()
    var selectedPosition = 0

    fun submitList(newItems: List<CategoryData>, maintainPosition: Boolean = false, currentCategoryId: String? = null) {
        items.clear()
        items.addAll(newItems)

        if (!maintainPosition) {
            selectedPosition = 0
            notifyDataSetChanged()
            if (items.isNotEmpty()) {
                onCategorySelected(items[0])
            }
        } else {
            // Find matched position by ID or keep selectedPosition
            if (currentCategoryId != null) {
                val foundIdx = items.indexOfFirst { it.id == currentCategoryId }
                if (foundIdx != -1) {
                    selectedPosition = foundIdx
                }
            }
            if (selectedPosition >= items.size) {
                selectedPosition = (items.size - 1).coerceAtLeast(0)
            }
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category_sidebar, parent, false) as TextView
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(items[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = items.size

    inner class CategoryViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && selectedPosition != pos) {
                    val prev = selectedPosition
                    selectedPosition = pos
                    itemView.post {
                        notifyItemChanged(prev)
                        notifyItemChanged(selectedPosition)
                    }
                    onCategorySelected(items[pos])
                }
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && selectedPosition != pos) {
                        val prev = selectedPosition
                        selectedPosition = pos
                        itemView.post {
                            notifyItemChanged(prev)
                            notifyItemChanged(selectedPosition)
                        }
                        onCategorySelected(items[pos])
                    }
                }
            }
        }

        fun bind(category: CategoryData, isSelected: Boolean) {
            textView.text = category.title
            textView.isSelected = isSelected
        }
    }
}
