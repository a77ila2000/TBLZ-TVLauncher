package com.tblz.tvlauncher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tblz.tvlauncher.databinding.ItemAppCardBinding

class AppLauncherAdapter(
    private val onAppClick: (AppItem) -> Unit,
    private val onAppLongClick: ((AppItem) -> Unit)? = null
) : RecyclerView.Adapter<AppLauncherAdapter.AppViewHolder>() {

    private val items = mutableListOf<AppItem>()

    fun submitList(newItems: List<AppItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class AppViewHolder(private val binding: ItemAppCardBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onAppClick(items[pos])
                }
            }

            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && onAppLongClick != null) {
                    onAppLongClick.invoke(items[pos])
                    true
                } else false
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
            binding.tvAppName.text = app.title

            if (app.icon != null) {
                binding.ivAppIcon.setImageDrawable(app.icon)
            } else if (app.iconRes != null) {
                binding.ivAppIcon.setImageDrawable(ContextCompat.getDrawable(itemView.context, app.iconRes))
            }
        }
    }
}
