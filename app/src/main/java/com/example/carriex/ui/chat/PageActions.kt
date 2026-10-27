package com.example.carriex.ui.chat

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.carriex.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

/** Bottom-right page action with an animated QR/text speed dial for new chats. */
class PageActions(context: Context, content: View) : FrameLayout(context) {
    companion object { const val NONE = 0; const val SERVERS = 1; const val CHATS = 2 }
    var addServer: (() -> Unit)? = null
    var scanContact: (() -> Unit)? = null
    var importContact: (() -> Unit)? = null
    var createGroup: (() -> Unit)? = null
    var expanded = false
        private set
    private var mode = NONE
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val scrim = View(context).apply {
        setBackgroundColor(0x55000000)
        visibility = View.GONE
        contentDescription = context.getString(R.string.ui_close_new_chat_actions)
        setOnClickListener { close() }
    }
    private val options = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        clipChildren = false
        clipToPadding = false
        visibility = View.GONE
    }
    private val fab = FloatingActionButton(context).apply {
        visibility = View.GONE
        setOnClickListener {
            if(mode == SERVERS) addServer?.invoke()
            else if(expanded) close() else open()
        }
    }

    init {
        addView(content, LayoutParams(-1, -1))
        addView(scrim, LayoutParams(-1, -1))
        fun option(title: String, icon: Int, action: () -> Unit) {
            options.addView(MaterialButton(context).apply {
                text = title; isAllCaps = false; setIconResource(icon)
                setOnClickListener { close(false); action() }
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        }
        option(context.getString(R.string.ui_scan_qr), R.drawable.ic_scan_qr) { scanContact?.invoke() }
        option(context.getString(R.string.ui_import_text), R.drawable.ic_nav_edit) { importContact?.invoke() }
        option(context.getString(R.string.ui_new_group), R.drawable.ic_group) { createGroup?.invoke() }
        addView(options, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(20); bottomMargin = dp(88)
        })
        addView(fab, LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(20); bottomMargin = dp(20)
        })
    }

    fun showMode(value: Int) {
        if(mode == value) return
        close(false)
        mode = value
        fab.visibility = if(value == NONE) View.GONE else View.VISIBLE
        fab.contentDescription = if(value == SERVERS) context.getString(R.string.ui_add_server) else context.getString(R.string.ui_new_chat)
        fab.setImageResource(if(value == SERVERS) R.drawable.ic_nav_add else R.drawable.ic_nav_edit)
    }

    fun open() {
        if(mode != CHATS || expanded) return
        expanded = true
        scrim.visibility = View.VISIBLE
        options.visibility = View.VISIBLE
        for(index in 0 until options.childCount) options.getChildAt(index).apply {
            animate().cancel(); alpha = 0f; translationY = dp(16 + (options.childCount - index) * 56).toFloat()
            animate().alpha(1f).translationY(0f).setStartDelay((options.childCount-1-index)*35L).setDuration(180).start()
        }
        fab.setImageResource(R.drawable.ic_nav_add)
        fab.rotation = 45f
        fab.contentDescription = context.getString(R.string.ui_close_new_chat_actions)
    }

    fun close(animated: Boolean = true) {
        expanded = false
        scrim.visibility = View.GONE
        fab.rotation = 0f
        fab.setImageResource(if(mode == SERVERS) R.drawable.ic_nav_add else R.drawable.ic_nav_edit)
        fab.contentDescription = if(mode == SERVERS) context.getString(R.string.ui_add_server) else context.getString(R.string.ui_new_chat)
        if(!animated) {
            for(index in 0 until options.childCount) options.getChildAt(index).animate().cancel()
            options.visibility = View.GONE
        } else for(index in 0 until options.childCount) options.getChildAt(index).apply {
            animate().cancel()
            animate().alpha(0f).translationY(dp(16 + (options.childCount - index) * 56).toFloat()).setStartDelay(0).setDuration(120)
                .withEndAction { if(!expanded) options.visibility = View.GONE }.start()
        }
    }

    override fun onDetachedFromWindow() { close(false); super.onDetachedFromWindow() }
}
