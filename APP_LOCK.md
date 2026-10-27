# App lock

**English** | [Русский](APP_LOCK.ru.md)

Open **Settings > App lock**. Protection is off until you add a password or enable
fingerprint unlock. Use either method on its own, or enable both so either one can
unlock Carriex. Enabling fingerprint requires successful Android biometric
authentication first. Fingerprint templates stay with Android.

The app password uses 4–128 characters and must be entered twice when created or
changed. Changing or removing protection, or changing the lock timing, requires
authentication. Repeated incorrect passwords temporarily delay further attempts;
closing the app does not reset the delay.

Choose **On app launch only** to keep an unlocked session while briefly switching
apps. Choose **Every time I leave and reopen the app** to lock after Home, switching
apps, or opening an external screen such as the QR scanner. **Lock now** immediately
locks the current session. Rotation, theme changes and language changes preserve an
already unlocked session. A new process or a fresh app launch requires unlocking.

Server connections and background checks continue while the app is locked.
Notification links open behind the lock. Passwords are never saved in activity
state; dialogs close when the protected app leaves the foreground. Protected
windows hide screenshots and recent-app previews.

Remember your app password. There is no password recovery service. With both
methods enabled, fingerprint provides another way in. With fingerprint alone,
Android offers the phone screen lock as a fallback; Android 7–10 also show an
explicit phone-screen-lock button. Without a working unlock method, resetting app
data loses saved chats and device identity.

The password verifier uses a random salt and PBKDF2, is encrypted with a dedicated
Android Keystore AES-GCM key, and stays in private local storage. UI unlocking is
independent of chat encryption and relay authentication. Existing encrypted chat
storage and connection credentials continue to work in the background.

Android biometric integration follows the [Android biometric authentication guide](https://developer.android.com/identity/sign-in/biometric-auth).
