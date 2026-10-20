package com.example.carriex.ui.chat

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import kotlin.math.abs

/** Rightward navigation anywhere in content; an open drawer keeps its normal drag behavior. */
class BackSwipeDrawerLayout(context: Context) : DrawerLayout(context) {
    var onBackSwipe: (() -> Unit)? = null
    private val distance = 72 * resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var blocked = false
    private var consumed = false
    private var drawerTouch = false
    private var selectableText = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                consumed = false
                drawerTouch = isDrawerVisible(GravityCompat.START)
                val target = targetAt(this, event.x, event.y)
                selectableText = target is TextView && target.isTextSelectable
                blocked = drawerTouch || target is EditText || target is SeekBar ||
                    target is HorizontalScrollView || (target is TextView && target.hasSelection())
            }
            MotionEvent.ACTION_POINTER_DOWN -> blocked = true
            MotionEvent.ACTION_MOVE -> if(!blocked && !consumed) {
                val dx = event.x - startX
                val dy = event.y - startY
                // Once a vertical scroll or a leftward gesture starts, leave it with the child.
                if((abs(dy) > slop && abs(dy) >= abs(dx)) || dx < -slop ||
                    (selectableText && event.eventTime-event.downTime >= ViewConfiguration.getLongPressTimeout())) blocked = true
                else if(dx >= distance && dx > abs(dy)*1.5f) {
                    // Cancel before navigation: a row/button under the finger must not click.
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    consumed = true
                    onBackSwipe?.invoke()
                    return true
                }
            }
        }
        if(consumed) return true
        return super.dispatchTouchEvent(event)
    }

    // A closed drawer must not steal its edge gesture before content can navigate back.
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        if(drawerTouch || isDrawerVisible(GravityCompat.START)) super.onInterceptTouchEvent(event) else false

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if(drawerTouch || isDrawerVisible(GravityCompat.START)) super.onTouchEvent(event) else true

    private fun targetAt(parent: View, x: Float, y: Float): View {
        if(parent is ViewGroup) {
            for(index in parent.childCount-1 downTo 0) {
                val child = parent.getChildAt(index)
                val localX = x + parent.scrollX - child.left
                val localY = y + parent.scrollY - child.top
                if(child.visibility == View.VISIBLE && localX >= 0 && localY >= 0 && localX < child.width && localY < child.height)
                    return targetAt(child, localX, localY)
            }
        }
        return parent
    }
}
