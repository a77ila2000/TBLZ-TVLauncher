package com.tblz.tvlauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.util.Log
import androidx.recyclerview.widget.RecyclerView

internal fun nextChannelRowPosition(position: Int, direction: Int, count: Int): Int? {
    if (count <= 0) return null
    if (position !in 0 until count) return if (direction > 0) 0 else count - 1
    return Math.floorMod(position + direction, count)
}

/** Own horizontal navigation before Android can search outside the channel row. */
class LoopingChannelRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {
    private var lastFocusedPosition = 0
    private var navigationPosition: Int? = null
    private var pendingPosition: Int? = null
    private var focusAttempts = 0
    private val applyFocus = Runnable { focusPendingPosition() }
    private val focusObserver = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (navigationPosition != null && newFocus != null && newFocus !== this &&
            findContainingItemView(newFocus) == null) {
            Log.w("LoopingChannelRow", "Horizontal focus left channel row")
            clearNavigation()
        }
    }

    init {
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isFocusable = true
        preserveFocusAfterLayout = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> {
                if (event.action == KeyEvent.ACTION_DOWN) clearNavigation()
                return super.dispatchKeyEvent(event)
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focusedPosition = findFocus()?.let { findContainingViewHolder(it)?.bindingAdapterPosition }
                ?.takeIf { it != NO_POSITION }
            val current = navigationPosition ?: focusedPosition ?: lastFocusedPosition
            val target = nextChannelRowPosition(current, direction, adapter?.itemCount ?: 0)
            if (target != null) {
                navigationPosition = target
                pendingPosition = target
                focusAttempts = 0
                // The row owns focus before the old card is detached or recycled.
                descendantFocusability = FOCUS_BLOCK_DESCENDANTS
                requestFocus()
                descendantFocusability = FOCUS_AFTER_DESCENDANTS
                stopScroll()
                if (!isComputingLayout) scrollToPosition(target)
                requestLayout()
                removeCallbacks(applyFocus)
                postOnAnimation(applyFocus)
            }
        }
        // Consume every horizontal event, including repeats and key-up during layout.
        return true
    }

    private fun focusPendingPosition() {
        val count = adapter?.itemCount ?: 0
        if (!isAttachedToWindow || !isShown || !hasWindowFocus() || count == 0) {
            clearPendingFocus()
            return
        }
        val target = pendingPosition?.coerceIn(0, count - 1) ?: return
        pendingPosition = target
        val card = findViewHolderForAdapterPosition(target)?.itemView
        if (card != null && card.requestFocus()) {
            lastFocusedPosition = target
            clearPendingFocus()
        } else if (focusAttempts++ < 4) {
            if (!isComputingLayout) scrollToPosition(target)
            requestLayout()
            postOnAnimation(applyFocus)
        } else {
            // Keep the logical target for the next key instead of leaving the row.
            requestFocus()
        }
    }

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        findContainingViewHolder(focused)?.bindingAdapterPosition?.takeIf { it != NO_POSITION }?.let {
            lastFocusedPosition = it
        }
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        if (direction == FOCUS_LEFT || direction == FOCUS_RIGHT) return this
        return super.focusSearch(focused, direction)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) clearNavigation()
        return super.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) clearNavigation()
    }

    private fun clearNavigation() {
        navigationPosition = null
        clearPendingFocus()
    }

    private fun clearPendingFocus() {
        removeCallbacks(applyFocus)
        pendingPosition = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusObserver)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusObserver)
        clearNavigation()
        super.onDetachedFromWindow()
    }
}
