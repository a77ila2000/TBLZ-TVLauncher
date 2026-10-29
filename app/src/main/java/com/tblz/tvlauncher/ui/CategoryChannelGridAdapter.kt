package com.tblz.tvlauncher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.tblz.tvlauncher.R
import com.tblz.tvlauncher.databinding.ItemChannelGridCardBinding
import com.tblz.tvlauncher.engine.ConfigStore
import com.tblz.tvlauncher.model.ChannelData

class CategoryChannelGridAdapter(
    private val onChannelClick: (ChannelData) -> Unit,
    private val onChannelLongClick: (ChannelData, Int) -> Unit
) : RecyclerView.Adapter<CategoryChannelGridAdapter.ChannelGridViewHolder>() {

    val items = mutableListOf<ChannelData>()

    fun submitList(newItems: List<ChannelData>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelGridViewHolder {
        val binding = ItemChannelGridCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelGridViewHolder(binding)
    }

    companion object {
        const val PAYLOAD_FAV = "PAYLOAD_FAVORITE"
    }

    override fun onBindViewHolder(holder: ChannelGridViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: ChannelGridViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_FAV)) {
            holder.bindFavoriteOnly(items[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ChannelGridViewHolder(private val binding: ItemChannelGridCardBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelClick(items[pos])
                }
            }

            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelLongClick(items[pos], pos)
                    true
                } else false
            }

            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.05f).scaleY(1.05f).translationZ(8f).setDuration(150).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
                }
            }
        }

        fun bindFavoriteOnly(channel: ChannelData) {
            val isFav = ConfigStore.isFavorite(channel.id)
            binding.ivFavIcon.isVisible = isFav
        }

        fun bind(channel: ChannelData) {
            val context = itemView.context
            binding.tvTitle.text = channel.title

            val currentEpg = channel.getCurrentEPG()
            binding.tvProgram.text = when {
                currentEpg != null -> currentEpg.programName
                channel.programName.isNotEmpty() -> channel.programName
                else -> channel.title
            }

            if (channel.siteType == ChannelData.SITE_WAVVE) {
                binding.tvBadge.text = "WAVVE"
                binding.tvBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_wavve)
            } else {
                binding.tvBadge.text = "TVING"
                binding.tvBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_tving)
            }

            val isFav = ConfigStore.isFavorite(channel.id)
            binding.ivFavIcon.isVisible = isFav

            if (channel.stillImageUrl.isNotEmpty()) {
                Glide.with(context)
                    .load(channel.stillImageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(binding.ivThumbnail)
            } else {
                binding.ivThumbnail.setImageResource(R.drawable.ic_channel_placeholder)
            }
        }
    }
}
