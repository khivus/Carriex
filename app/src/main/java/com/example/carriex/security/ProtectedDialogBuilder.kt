package com.example.carriex.security

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Activity overlays cannot cover a dialog window. Close our dialogs before backgrounding. */
class ProtectedDialogBuilder(context: Context) : MaterialAlertDialogBuilder(context) {
    override fun create(): AlertDialog = super.create().also { dialog ->
        ProtectedDialogs.register(dialog, context)
        if (AppLockStore(context).enabled) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

internal object ProtectedDialogs {
    private val dialogs = WeakHashMap<Dialog, WeakReference<Activity>>()
    fun register(dialog: Dialog, context: Context) {
        var current = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        (current as? Activity)?.let { dialogs[dialog] = WeakReference(it) }
    }
    fun dismiss(activity: Activity) {
        dialogs.entries.filter { it.value.get() === activity }.map { it.key }.forEach { it.dismiss() }
    }
}
