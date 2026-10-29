package com.tblz.tvlauncher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.tblz.tvlauncher.R
import com.tblz.tvlauncher.databinding.ItemAppSelectCardBinding

class AppSelectAdapter(
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<AppSelectAdapter.AppSelectViewHolder>() {

    private val items = mutableListOf<AppItem>()
    val selectedPackages = mutableSetOf<String>()

    fun submitList(newItems: List<AppItem>, preselected: Set<String> = emptySet()) {
        items.clear()
        items.addAll(newItems)
        selectedPackages.clear()
        selectedPackages.addAll(preselected)
        notifyDataSetChanged()
        onSelectionChanged(selectedPackages.size)
    }

    fun toggleSelection(pkg: String) {
        if (selectedPackages.contains(pkg)) {
            selectedPackages.remove(pkg)
        } else {
            selectedPackages.add(pkg)
        }
        val idx = items.indexOfFirst { it.id == pkg }
        if (idx >= 0) {
            notifyItemChanged(idx)
        }
        onSelectionChanged(selectedPackages.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppSelectViewHolder {
        val binding = ItemAppSelectCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppSelectViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppSelectViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class AppSelectViewHolder(private val binding: ItemAppSelectCardBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val item = items[pos]
                    toggleSelection(item.id)
                }
            }

            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.04f).scaleY(1.04f).translationZ(6f).setDuration(150).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
                }
            }
        }

        fun bind(app: AppItem) {
            val isSelected = selectedPackages.contains(app.id)
            val context = itemView.context

            binding.tvAppName.text = app.title

            if (app.icon != null) {
                binding.ivAppIcon.setImageDrawable(app.icon)
            } else if (app.iconRes != null) {
                binding.ivAppIcon.setImageDrawable(ContextCompat.getDrawable(context, app.iconRes))
            }

            binding.ivCheck.isVisible = isSelected
            if (isSelected) {
                binding.cardContainer.background = ContextCompat.getDrawable(context, R.drawable.bg_app_card_selected)
            } else {
                binding.cardContainer.background = ContextCompat.getDrawable(context, R.drawable.bg_app_card)
            }
        }
    }
}
