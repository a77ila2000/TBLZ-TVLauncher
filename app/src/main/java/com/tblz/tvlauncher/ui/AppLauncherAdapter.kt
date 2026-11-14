package com.tblz.tvlauncher.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.tblz.tvlauncher.R
import com.tblz.tvlauncher.databinding.ItemAppCardBinding

class AppLauncherAdapter(
    private val onAppClick: (AppItem) -> Unit,
    private val onAppOrderChanged: ((List<String>) -> Unit)? = null,
    private val onAppRemoved: ((AppItem) -> Unit)? = null,
    private val onEditModeChanged: ((Boolean) -> Unit)? = null
) : RecyclerView.Adapter<AppLauncherAdapter.AppViewHolder>() {

    private val items = mutableListOf<AppItem>()
    private var editingId: String? = null
    private var ignoreConfirmRelease = false
    private var originalItems: List<AppItem> = emptyList()
    private var recycler: RecyclerView? = null
    val isEditing: Boolean get() = editingId != null
    private val editingSupported: Boolean get() = onAppOrderChanged != null && onAppRemoved != null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { recycler = recyclerView }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { recycler = null }

    fun submitList(newItems: List<AppItem>) {
        cancelEditing()
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun cancelEditing() { finishEditing(commit = false) }

    private fun finishEditing(commit: Boolean) {
        val id = editingId ?: return
        editingId = null
        ignoreConfirmRelease = false
        if (commit) {
            onAppOrderChanged?.invoke(items.filterNot { it.isAction }.map { it.id })
        } else {
            items.clear()
            items.addAll(originalItems)
        }
        originalItems = emptyList()
        notifyDataSetChanged()
        onEditModeChanged?.invoke(false)
        focusApp(id)
    }

    private fun focusApp(id: String?) {
        val view = recycler ?: return
        val position = items.indexOfFirst { it.id == id }
        if (position < 0) return
        view.scrollToPosition(position)
        view.doOnNextLayout {
            (view.findViewHolderForAdapterPosition(position) as? AppViewHolder)?.focusCard()
        }
        view.requestLayout()
    }

    private fun moveEditingApp(direction: Int) {
        val from = items.indexOfFirst { it.id == editingId }
        val to = from + direction
        if (from < 0 || to !in items.indices || items[to].isAction) return
        val app = items.removeAt(from)
        items.add(to, app)
        notifyItemMoved(from, to)
        focusApp(app.id)
    }

    private fun removeEditingApp() {
        val position = items.indexOfFirst { it.id == editingId }
        if (position < 0) return
        val app = items.removeAt(position)
        editingId = null
        ignoreConfirmRelease = false
        originalItems = emptyList()
        notifyDataSetChanged()
        onAppRemoved?.invoke(app)
        onAppOrderChanged?.invoke(items.filterNot { it.isAction }.map { it.id })
        onEditModeChanged?.invoke(false)
        focusApp(items.getOrNull(position.coerceAtMost(items.lastIndex))?.id)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
        AppViewHolder(ItemAppCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class AppViewHolder(private val binding: ItemAppCardBinding) : RecyclerView.ViewHolder(binding.root) {
        private var confirmKeyDown = false

        init {
            binding.appCardContainer.setOnClickListener {
                val app = items.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                if (isEditing) finishEditing(commit = true) else onAppClick(app)
            }
            binding.appCardContainer.setOnLongClickListener {
                val app = items.getOrNull(bindingAdapterPosition)
                if (editingSupported && app != null && !app.isAction && !isEditing) {
                    editingId = app.id
                    originalItems = items.toList()
                    ignoreConfirmRelease = confirmKeyDown
                    notifyDataSetChanged()
                    focusApp(app.id)
                    onEditModeChanged?.invoke(true)
                    true
                } else isEditing
            }
            binding.appCardContainer.setOnKeyListener { _, keyCode, event ->
                val isConfirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_SPACE
                if (isConfirm) confirmKeyDown = event.action == KeyEvent.ACTION_DOWN
                if (!isEditing) return@setOnKeyListener false
                if (isConfirm && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    ignoreConfirmRelease = false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            moveEditingApp(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.action == KeyEvent.ACTION_DOWN) binding.btnRemoveShortcut.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> true
                    KeyEvent.KEYCODE_BACK -> {
                        if (event.action == KeyEvent.ACTION_UP) cancelEditing()
                        true
                    }
                    else -> if (isConfirm) {
                        if (event.action == KeyEvent.ACTION_UP) {
                            if (ignoreConfirmRelease) ignoreConfirmRelease = false
                            else finishEditing(commit = true)
                        }
                        true
                    } else false
                }
            }
            binding.btnRemoveShortcut.setOnClickListener { removeEditingApp() }
            binding.btnRemoveShortcut.setOnKeyListener { _, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.action == KeyEvent.ACTION_DOWN) focusCard()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    KeyEvent.KEYCODE_BACK -> {
                        if (event.action == KeyEvent.ACTION_UP) cancelEditing()
                        true
                    }
                    else -> false
                }
            }
            binding.appCardContainer.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.04f else 1f
                view.animate().scaleX(scale).scaleY(scale).translationZ(if (hasFocus) 6f else 0f)
                    .setDuration(150).start()
            }
        }

        fun focusCard() { binding.appCardContainer.requestFocus() }

        fun bind(app: AppItem) {
            val moving = app.id == editingId
            binding.root.layoutParams.height = ((if (editingSupported && isEditing) 144 else 100) *
                binding.root.resources.displayMetrics.density).toInt()
            binding.appCardContainer.isActivated = moving
            binding.appCardContainer.contentDescription = if (moving) {
                binding.root.context.getString(R.string.shortcut_moving, app.title)
            } else app.title
            binding.btnRemoveShortcut.isVisible = moving
            binding.tvAppName.text = app.title
            binding.ivAppIcon.setImageDrawable(app.icon ?: app.iconRes?.let {
                ContextCompat.getDrawable(itemView.context, it)
            })
        }
    }
}
