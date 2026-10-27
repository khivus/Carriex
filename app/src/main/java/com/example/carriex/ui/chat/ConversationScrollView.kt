package com.example.carriex.ui.chat

import android.content.Context
import android.widget.ScrollView

/** Keep the bottom attached across content/IME layout changes, or retain the reading offset. */
class ConversationScrollView(context: Context) : ScrollView(context) {
    private data class Position(val offset: Int, val atBottom: Boolean)
    private val bottomTolerance = (24 * resources.displayMetrics.density).toInt()
    private var beforeResize: Position? = null
    private var showLatestOnLayout = true

    private fun scrollRange(viewportHeight: Int = height) = maxOf(0,
        (getChildAt(0)?.height ?: 0) - (viewportHeight - paddingTop - paddingBottom))

    private fun position(viewportHeight: Int = height) =
        Position(scrollY, scrollY >= scrollRange(viewportHeight) - bottomTolerance)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // At this point height already contains the new size. Use the previous
        // viewport to decide whether the reader was at the bottom before the IME.
        if(oldh > 0) beforeResize = position(oldh)
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // Child height still describes the previous message layout until super.
        // Capture it before appends, wrapping or viewport changes affect the range.
        val previous = beforeResize ?: position()
        val followBottom = showLatestOnLayout || previous.atBottom
        super.onLayout(changed, l, t, r, b)
        beforeResize = null
        showLatestOnLayout = false
        scrollTo(scrollX, if(followBottom) scrollRange() else previous.offset)
    }

    fun showLatest() {
        showLatestOnLayout = true
        requestLayout()
    }
}
