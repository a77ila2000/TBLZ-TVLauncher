package com.tblz.tvlauncher.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import com.tblz.tvlauncher.databinding.DialogChannelCategoryBinding
import com.tblz.tvlauncher.engine.LiveTvManager
import com.tblz.tvlauncher.model.CategoryData
import com.tblz.tvlauncher.player.LivePlayerActivity

class ChannelCategoryDialog(
    context: Context,
    private val onFavoritesUpdated: () -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogChannelCategoryBinding
    private lateinit var sidebarAdapter: CategorySidebarAdapter
    private lateinit var gridAdapter: CategoryChannelGridAdapter

    private var currentCategory: CategoryData? = null
    private val categoryDefinitions = listOf(
        Pair("FAVORITES", "⭐ 즐겨찾기"),
        Pair("ALL", "📺 전체 채널"),
        Pair("ALL_TVING", "🔴 TVING 전체"),
        Pair("ALL_WAVVE", "🔵 Wavve 전체"),
        Pair("MERGED_TERRESTRIAL", "지상파 (전체)"),
        Pair("MERGED_COMPREHENSIVE", "종합편성 (WAVVE)"),
        Pair("MERGED_DRAMA_ENT", "드라마 / 예능"),
        Pair("MERGED_NEWS_ECON", "뉴스 / 경제"),
        Pair("MERGED_SPORTS", "스포츠"),
        Pair("MERGED_MOVIE", "영화 / 시리즈"),
        Pair("MERGED_HOMESHOPPING", "홈쇼핑"),
        Pair("MERGED_KIDS", "어린이 / 키즈 / 애니"),
        Pair("WAVVE_BINGE", "정주행 채널 (WAVVE)"),
        Pair("WAVVE_RADIO", "라디오 / 음악 (WAVVE)"),
        Pair("TVING_DOCU", "다큐 / 정보 (TVING)")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogChannelCategoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.94).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.92).toInt()
        )

        binding.btnClose.setOnClickListener { dismiss() }

        setupViews()
    }

    override fun onStart() {
        super.onStart()
        binding.rvCategories.post {
            binding.rvCategories.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun setupViews() {
        // Grid setup
        gridAdapter = CategoryChannelGridAdapter(
            onChannelClick = { channel ->
                val intent = Intent(context, LivePlayerActivity::class.java).apply {
                    putExtra(LivePlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                    // Zap inside the category the user was browsing, not the whole list.
                    putExtra(LivePlayerActivity.EXTRA_CATEGORY_ID, currentCategory?.id ?: "ALL")
                }
                context.startActivity(intent)
                dismiss()
            },
            onChannelLongClick = { channel, pos ->
                val isFav = LiveTvManager.toggleFavorite(channel)
                val msg = if (isFav) "즐겨찾기에 추가되었습니다 ★" else "즐겨찾기에서 해제되었습니다"
                Toast.makeText(context, "${channel.title} $msg", Toast.LENGTH_SHORT).show()

                onFavoritesUpdated()

                if (currentCategory?.id == "FAVORITES") {
                    if (!isFav && pos in 0 until gridAdapter.items.size) {
                        gridAdapter.items.removeAt(pos)
                        gridAdapter.notifyItemRemoved(pos)
                        binding.rvCategoryChannels.post {
                            val nextPos = pos.coerceAtMost(gridAdapter.items.size - 1)
                            if (nextPos >= 0) {
                                binding.rvCategoryChannels.findViewHolderForAdapterPosition(nextPos)?.itemView?.requestFocus()
                            }
                        }
                    }
                } else {
                    gridAdapter.notifyItemChanged(pos, CategoryChannelGridAdapter.PAYLOAD_FAV)
                    binding.rvCategoryChannels.post {
                        binding.rvCategoryChannels.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    }
                }

                // Update category counts in sidebar without moving focus or selection
                updateSidebarCategories(keepSelection = true)
            }
        )

        binding.rvCategoryChannels.layoutManager = GridLayoutManager(context, 3)
        binding.rvCategoryChannels.adapter = gridAdapter

        // Sidebar setup
        sidebarAdapter = CategorySidebarAdapter { category ->
            currentCategory = category
            loadChannelsForCategory(category)
        }
        binding.rvCategories.adapter = sidebarAdapter

        updateSidebarCategories(keepSelection = false)
    }

    private fun updateSidebarCategories(keepSelection: Boolean) {
        val catList = mutableListOf<CategoryData>()
        for ((id, title) in categoryDefinitions) {
            val count = LiveTvManager.getChannelsByCategory(id).size
            if (id in listOf("FAVORITES", "ALL", "ALL_TVING", "ALL_WAVVE") || count > 0) {
                catList.add(CategoryData(id, "$title ($count)"))
            }
        }

        sidebarAdapter.submitList(
            newItems = catList,
            maintainPosition = keepSelection,
            currentCategoryId = currentCategory?.id
        )
    }

    private fun loadChannelsForCategory(category: CategoryData) {
        val channels = LiveTvManager.getChannelsByCategory(category.id)
        gridAdapter.submitList(channels)
    }
}
