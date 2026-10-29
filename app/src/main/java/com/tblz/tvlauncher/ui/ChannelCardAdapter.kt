package com.tblz.tvlauncher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.tblz.tvlauncher.R
import com.tblz.tvlauncher.databinding.ItemChannelCardBinding
import com.tblz.tvlauncher.engine.ConfigStore
import com.tblz.tvlauncher.model.ChannelData

class ChannelCardAdapter(
    private val onChannelClick: (ChannelData) -> Unit,
    private val onChannelLongClick: (ChannelData) -> Unit
) : RecyclerView.Adapter<ChannelCardAdapter.ChannelViewHolder>() {

    companion object {
        /** Live-thumbnail cache bucket: a re-bind after this long fetches a fresh frame. */
        private const val THUMB_REFRESH_MS = 60_000L
        /** Partial-rebind marker: update only the changing bits, keep focus/scroll. */
        private const val PAYLOAD_PROGRAM = "payload_program"
    }

    private val items = mutableListOf<ChannelData>()

    fun submitList(newItems: List<ChannelData>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /**
     * Periodic "지금 방송중" refresh. When the channel set is unchanged (same ids, same order)
     * we swap in the freshly-fetched objects and rebind ONLY the program text / progress /
     * thumbnail via a payload, so the user's focus and scroll position on the row are kept.
     * If the set actually changed, fall back to a full rebind.
     */
    fun refreshData(newItems: List<ChannelData>) {
        val sameSet = newItems.size == items.size &&
            newItems.indices.all { newItems[it].id == items[it].id }
        if (sameSet) {
            items.clear()
            items.addAll(newItems)
            notifyItemRangeChanged(0, items.size, PAYLOAD_PROGRAM)
        } else {
            submitList(newItems)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRAM)) {
            holder.bindDynamic(items[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ChannelViewHolder(private val binding: ItemChannelCardBinding) : RecyclerView.ViewHolder(binding.root) {

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
                    onChannelLongClick(items[pos])
                    notifyItemChanged(pos)
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

        fun bind(channel: ChannelData) {
            val context = itemView.context
            binding.tvTitle.text = channel.title

            if (channel.siteType == ChannelData.SITE_WAVVE) {
                binding.tvBadge.text = "WAVVE"
                binding.tvBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_wavve)
            } else {
                binding.tvBadge.text = "TVING"
                binding.tvBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_tving)
            }

            bindDynamic(channel)
        }

        /** The parts that change over time: current program, progress, fav star, live thumbnail. */
        fun bindDynamic(channel: ChannelData) {
            val context = itemView.context

            val currentEpg = channel.getCurrentEPG()
            if (currentEpg != null) {
                binding.tvProgram.text = currentEpg.programName
                binding.cardProgressBar.progress = currentEpg.getProgressPercent()
                binding.cardProgressBar.isVisible = true
            } else {
                binding.tvProgram.text = if (channel.programName.isNotEmpty()) channel.programName else channel.title
                binding.cardProgressBar.isVisible = false
            }

            val isFav = ConfigStore.isFavorite(channel.id)
            binding.ivFavIcon.isVisible = isFav

            if (channel.stillImageUrl.isNotEmpty()) {
                // stillshot.tving.com/thumbnail_current/... is a LIVE current-frame image at a
                // fixed URL. With plain URL-keyed caching Glide keeps showing the very first
                // frame forever, which is why the home list looked frozen. A time-bucketed
                // signature makes the cache key change every THUMB_REFRESH_MS so a re-bind
                // (e.g. returning from a video) pulls a fresh frame, while binds within the
                // same bucket still reuse the cache.
                Glide.with(context)
                    .load(channel.stillImageUrl)
                    .signature(ObjectKey(System.currentTimeMillis() / THUMB_REFRESH_MS))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(binding.ivThumbnail)
            } else {
                binding.ivThumbnail.setImageResource(R.drawable.ic_channel_placeholder)
            }
        }
    }
}
