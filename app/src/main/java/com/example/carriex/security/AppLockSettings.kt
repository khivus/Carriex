package com.example.carriex.security

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.carriex.R
import com.google.android.material.button.MaterialButton
import com.example.carriex.security.ProtectedDialogBuilder as MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppLockSettings(
    private val activity: AppCompatActivity,
    private val lock: AppLockController,
    private val card: (LinearLayout, String, String) -> LinearLayout,
    private val refresh: () -> Unit
) {
    private val store get() = lock.store
    private fun text(id: Int) = activity.getString(id)
    private fun button(parent: LinearLayout, id: Int, action: () -> Unit) = MaterialButton(activity).apply {
        setText(id); setOnClickListener { action() }; parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun build(content: LinearLayout) {
        card(content, text(R.string.lock_title), text(if (store.enabled) R.string.lock_enabled_detail else R.string.lock_disabled_detail))
        val password = card(content, text(R.string.lock_password), text(R.string.lock_password_detail))
        button(password, if (store.hasPassword) R.string.lock_change_password else R.string.lock_add_password) {
            lock.authorize { editPassword() }
        }
        if (store.hasPassword) button(password, R.string.lock_remove_password) {
            lock.authorize {
                MaterialAlertDialogBuilder(activity).setTitle(R.string.lock_remove_password)
                    .setMessage(if (store.biometric) R.string.lock_remove_password_detail else R.string.lock_turn_off_detail)
                    .setNegativeButton(R.string.ui_cancel, null)
                    .setPositiveButton(R.string.lock_remove_password) { _, _ ->
                        store.removePassword(); lock.protectionChanged(); refresh()
                    }.show().window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        val biometric = card(content, text(R.string.lock_fingerprint), text(R.string.lock_fingerprint_detail))
        val switch = SwitchMaterial(activity).apply {
            text = text(R.string.lock_fingerprint)
            contentDescription = text
            isChecked = store.biometric
        }
        switch.setOnCheckedChangeListener { _, checked ->
            if (checked != store.biometric) {
                switch.isChecked = store.biometric
                lock.authorize {
                    if (checked) lock.confirmBiometric { store.biometric = true; lock.protectionChanged(); refresh() }
                    else { store.biometric = false; lock.protectionChanged(); refresh() }
                }
            }
        }
        switch.isEnabled = store.biometric || lock.canEnableBiometric()
        biometric.addView(switch)
        if (!lock.canEnableBiometric()) {
            biometric.addView(TextView(activity).apply { setText(R.string.lock_biometric_unavailable) })
            button(biometric, R.string.lock_android_security) {
                val intent = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                    .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    else Intent(Settings.ACTION_SECURITY_SETTINGS)
                runCatching { activity.startActivity(intent) }
            }
        }
        val timing = card(content, text(R.string.lock_timing), text(R.string.lock_timing_detail))
        val group = RadioGroup(activity)
        val options = listOf(R.string.lock_on_launch, R.string.lock_on_return).map { id ->
            MaterialRadioButton(activity).apply {
                this.id = View.generateViewId(); setText(id)
                group.addView(this, RadioGroup.LayoutParams(-1, -2))
                isEnabled = store.enabled
            }
        }
        group.check(options[if (store.everyReturn) 1 else 0].id)
        group.setOnCheckedChangeListener { _, id ->
            val selected = id == options[1].id
            if (selected != store.everyReturn) {
                group.check(options[if (store.everyReturn) 1 else 0].id)
                lock.authorize { store.everyReturn = selected; refresh() }
            }
        }
        timing.addView(group)
        if (store.enabled) button(timing, R.string.lock_now) { lock.lockNow() }
        card(content, text(R.string.lock_recovery), text(R.string.lock_recovery_detail))
    }

    private fun editPassword() {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        fun input(hint: Int) = passwordInput(activity).also { edit ->
            body.addView(TextInputLayout(activity).apply {
                setHint(hint); endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE; addView(edit)
            })
        }
        val first = input(R.string.lock_new_password)
        val second = input(R.string.lock_repeat_password)
        val status = TextView(activity).also(body::addView)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (store.hasPassword) R.string.lock_change_password else R.string.lock_add_password)
            .setMessage(R.string.lock_password_minimum).setView(body)
            .setNegativeButton(R.string.ui_cancel, null).setPositiveButton(R.string.ui_save, null).create()
        var saving = false
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saving) return@setOnClickListener
                val value = first.text?.toString()?.toCharArray() ?: charArrayOf()
                val confirmation = second.text?.toString()?.toCharArray() ?: charArrayOf()
                when {
                    value.size !in PasswordVerifier.MIN_LENGTH..PasswordVerifier.MAX_LENGTH -> {
                        value.fill('\u0000'); confirmation.fill('\u0000'); status.setText(R.string.lock_password_minimum)
                    }
                    !value.contentEquals(confirmation) -> {
                        value.fill('\u0000'); confirmation.fill('\u0000'); status.setText(R.string.lock_password_mismatch)
                    }
                    else -> {
                        confirmation.fill('\u0000'); first.text?.clear(); second.text?.clear()
                        saving = true; status.setText(R.string.lock_saving)
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                        dialog.setCancelable(false)
                        activity.lifecycleScope.launch {
                            val result = withContext(Dispatchers.Default) {
                                try { runCatching { store.setPassword(value) } } finally { value.fill('\u0000') }
                            }
                            if (result.isSuccess) {
                                lock.protectionChanged(); dialog.dismiss(); refresh()
                            } else {
                                saving = false; status.setText(R.string.lock_storage_error)
                                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                                dialog.setCancelable(true)
                            }
                        }
                    }
                }
            }
        }
        dialog.setOnDismissListener { first.text?.clear(); second.text?.clear() }
        dialog.show()
    }
}
