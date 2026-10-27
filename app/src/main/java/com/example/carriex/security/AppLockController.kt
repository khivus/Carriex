package com.example.carriex.security

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.carriex.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Covers the whole activity, including restored navigation and notification deep links. */
class AppLockController(
    private val activity: AppCompatActivity,
    private val container: FrameLayout,
    private val content: View
) {
    companion object {
        // Deliberately never serialized into activity state or preferences.
        internal val session = LockSession()
    }
    val store = AppLockStore(activity)
    private var gate: View? = null
    private var status: TextView? = null
    private var password: TextInputEditText? = null
    private var submit: MaterialButton? = null
    private var pendingAction: (() -> Unit)? = null
    private var confirming = false
    private var attempt = 0L
    private var busy = false
    private var automaticBiometric = false
    private var biometricSuccess: (() -> Unit)? = null
    private var completedBiometric: (() -> Unit)? = null
    private var credentialPromptActive = false
    private var offerCredential = false
    private val credentialResult = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        credentialPromptActive = false
        val action = biometricSuccess
        biometricSuccess = null
        if (result.resultCode == Activity.RESULT_OK) {
            completedBiometric = action
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) completeBiometric()
        }
    }
    private val shade = FrameLayout(activity).apply {
        setBackgroundColor(MaterialColors.getColor(activity, android.R.attr.colorBackground, 0xff101010.toInt()))
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        visibility = View.GONE
    }
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (confirming && session.authenticated) cancelConfirmation()
            else activity.moveTaskToBack(true)
        }
    }
    private val biometricPrompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val success = biometricSuccess
                biometricSuccess = null
                credentialPromptActive = false
                completedBiometric = success
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) completeBiometric()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (offerCredential && Build.VERSION.SDK_INT < 30 && errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    offerCredential = false
                    val success = biometricSuccess
                    biometricSuccess = null
                    if (success != null && requestCredential(success)) return
                }
                biometricSuccess = null
                credentialPromptActive = false
                status?.text = errString
                focusPassword()
            }
            override fun onAuthenticationFailed() { status?.setText(R.string.lock_fingerprint_failed) }
        })

    init {
        container.addView(shade, FrameLayout.LayoutParams(-1, -1))
        refreshProtection()
        if (session.needsUnlock(store.enabled)) showGate()
    }

    fun attachBackHandler() { activity.onBackPressedDispatcher.addCallback(activity, back) }

    fun refreshProtection() {
        if (store.enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun resume() {
        shade.visibility = View.GONE
        if (session.needsUnlock(store.enabled) && gate == null) showGate()
        if (gate == null) showContent()
        completeBiometric()
        if (automaticBiometric) {
            automaticBiometric = false
            // Posting waits until the activity and prompt host have fully resumed.
            container.post { if (gate != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) unlockBiometric() }
        } else focusPassword()
    }

    private fun focusPassword() {
        val input = password ?: return
        input.post {
            if (password === input && gate != null && biometricSuccess == null && !credentialPromptActive &&
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                input.requestFocus()
                WindowCompat.getInsetsController(activity.window, input).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    fun pause() {
        if (store.enabled || gate != null) {
            content.visibility = View.INVISIBLE
            shade.visibility = View.VISIBLE
            shade.bringToFront()
            ProtectedDialogs.dismiss(activity)
        }
    }

    fun stop(changingConfiguration: Boolean) {
        session.background(store.enabled, store.everyReturn, changingConfiguration)
        // AndroidX launches Android's credential activity on older devices. Its result belongs
        // to this authentication attempt and is only consumed once our activity resumes.
        if (credentialPromptActive || completedBiometric != null) return
        attempt++ // Never accept a password result after leaving the app.
        biometricSuccess = null
        biometricPrompt.cancelAuthentication()
        if (!changingConfiguration) {
            // A protected settings operation cannot finish after the app has left the foreground.
            pendingAction = null
            confirming = false
            removeGate()
        }
    }

    /** Reauthentication is required even if the foreground session is already unlocked. */
    fun authorize(action: () -> Unit) {
        if (!store.enabled) { action(); return }
        pendingAction = action
        confirming = true
        showGate()
        resume()
    }

    fun lockNow() {
        if (!store.enabled) return
        session.lock()
        pendingAction = null
        confirming = false
        showGate()
        resume()
    }

    fun protectionChanged() {
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.unlock() else session.lock()
        refreshProtection()
    }

    fun canEnableBiometric() = BiometricManager.from(activity)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Enabling biometrics requires a successful biometric, not only a device PIN. */
    fun confirmBiometric(action: () -> Unit) {
        prompt(allowCredential = false, action)
    }

    private fun prompt(allowCredential: Boolean, success: () -> Unit) {
        if (biometricSuccess != null) return
        biometricSuccess = success
        offerCredential = allowCredential
        credentialPromptActive = allowCredential && Build.VERSION.SDK_INT >= 30
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_biometric_title))
            .setSubtitle(activity.getString(R.string.lock_biometric_detail))
        if (allowCredential && Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(activity.getString(when {
                    store.hasPassword -> R.string.lock_use_password
                    allowCredential -> R.string.lock_phone_screen_lock
                    else -> R.string.ui_cancel
                }))
        }
        runCatching { biometricPrompt.authenticate(builder.build()) }.onFailure {
            biometricSuccess = null
            credentialPromptActive = false
            status?.setText(R.string.lock_biometric_unavailable)
        }
    }

    private fun unlockBiometric() {
        if (!store.biometric) return
        val generation = attempt
        prompt(allowCredential = !store.hasPassword) {
            if (generation == attempt && gate != null) authenticated()
        }
    }

    @Suppress("DEPRECATION")
    private fun requestCredential(success: () -> Unit): Boolean {
        val intent = activity.getSystemService(KeyguardManager::class.java)
            .createConfirmDeviceCredentialIntent(activity.getString(R.string.lock_biometric_title), activity.getString(R.string.lock_biometric_detail))
            ?: return false
        biometricSuccess = success
        credentialPromptActive = true
        return runCatching { credentialResult.launch(intent); true }.getOrElse {
            biometricSuccess = null; credentialPromptActive = false; false
        }
    }

    private fun showGate() {
        removeGate()
        attempt++
        busy = false
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(32), dp(28), dp(32))
        }
        body.addView(TextView(activity).apply {
            text = activity.getString(if (confirming) R.string.lock_confirm_title else R.string.lock_unlock_title)
            textSize = 26f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        })
        body.addView(TextView(activity).apply {
            setText(if (confirming) R.string.lock_confirm_detail else R.string.lock_unlock_detail)
            textSize = 15f
            setPadding(0, dp(12), 0, dp(20))
        })
        status = TextView(activity).also { body.addView(it) }
        if (store.hasPassword) {
            val field = TextInputLayout(activity).apply {
                setHint(R.string.lock_password)
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
            password = passwordInput(activity).also {
                it.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                it.setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_DONE) { unlockPassword(); true } else false
                }
                field.addView(it)
            }
            body.addView(field)
            submit = MaterialButton(activity).apply {
                setText(R.string.lock_unlock_password)
                setOnClickListener { unlockPassword() }
                body.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        if (store.biometric) body.addView(MaterialButton(activity).apply {
            setText(R.string.lock_unlock_fingerprint)
            setOnClickListener { unlockBiometric() }
        }, LinearLayout.LayoutParams(-1, -2))
        if (store.biometric && !store.hasPassword) {
            body.addView(TextView(activity).apply { setText(R.string.lock_device_fallback) })
            if (Build.VERSION.SDK_INT < 30) body.addView(MaterialButton(activity).apply {
                setText(R.string.lock_phone_screen_lock)
                setOnClickListener {
                    val generation = attempt
                    if (!requestCredential { if (generation == attempt && gate != null) authenticated() })
                        status?.setText(R.string.lock_biometric_unavailable)
                }
            })
        }
        if (confirming) body.addView(MaterialButton(activity).apply {
            setText(R.string.ui_cancel)
            setOnClickListener { cancelConfirmation() }
        })
        gate = ScrollView(activity).apply {
            tag = "app_lock_gate"
            isFillViewport = true
            setBackgroundColor(MaterialColors.getColor(activity, android.R.attr.colorBackground, 0xff101010.toInt()))
            addView(body)
            container.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
        content.visibility = View.INVISIBLE
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        content.findFocus()?.clearFocus()
        if (password != null) password?.requestFocus()
        else gate?.apply { isFocusableInTouchMode = true; requestFocus() }
        if (store.biometric || password == null) container.post {
            WindowCompat.getInsetsController(activity.window, container).hide(WindowInsetsCompat.Type.ime())
        }
        back.isEnabled = true
        automaticBiometric = store.biometric
        refreshProtection()
    }

    private fun unlockPassword() {
        if (busy) return
        val wait = store.retryAfterMillis()
        if (wait > 0) { status?.text = activity.getString(R.string.lock_retry, (wait + 999) / 1000); return }
        val value = password?.text?.toString()?.toCharArray() ?: return
        password?.text?.clear()
        val generation = attempt
        busy = true
        submit?.isEnabled = false
        status?.setText(R.string.lock_checking)
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    runCatching { store.verifyPassword(value).also { store.passwordAttempt(it) } }
                } finally { value.fill('\u0000') }
            }
            if (generation != attempt || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            busy = false
            submit?.isEnabled = true
            when {
                result.isFailure -> status?.setText(R.string.lock_storage_error)
                result.getOrDefault(false) -> authenticated()
                else -> status?.setText(R.string.lock_wrong_password)
            }
        }
    }

    private fun authenticated() {
        WindowCompat.getInsetsController(activity.window, container).hide(WindowInsetsCompat.Type.ime())
        session.unlock()
        val action = pendingAction
        pendingAction = null
        confirming = false
        biometricSuccess = null
        biometricPrompt.cancelAuthentication()
        removeGate()
        showContent()
        action?.invoke()
    }

    private fun cancelConfirmation() {
        WindowCompat.getInsetsController(activity.window, container).hide(WindowInsetsCompat.Type.ime())
        attempt++
        pendingAction = null
        confirming = false
        biometricSuccess = null
        biometricPrompt.cancelAuthentication()
        removeGate()
        showContent()
    }

    private fun completeBiometric() {
        val action = completedBiometric
        completedBiometric = null
        action?.invoke()
    }

    private fun removeGate() {
        gate?.let(container::removeView)
        gate = null; password = null; submit = null; status = null
        back.isEnabled = false
    }

    private fun showContent() {
        content.visibility = View.VISIBLE
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}

internal fun passwordInput(activity: AppCompatActivity) = TextInputEditText(activity).apply {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    filters = arrayOf(android.text.InputFilter.LengthFilter(PasswordVerifier.MAX_LENGTH))
    isSingleLine = true
    isSaveEnabled = false
    if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    if (Build.VERSION.SDK_INT >= 29) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
}
