# Carriex Android

## Connect and chat

1. Start your server using its `start-server.bat` or paired Docker deployment; see `Carriex-server/PAIRING.md`. Open its generated `pairing.html`. In the app choose **Scan server QR**, or **Import pairing text** and paste the complete JSON. Review the name, address and key, then choose **Save server**. Only use pairing information obtained from the owner through a trusted channel. No extra scanner app, domain registration, or system certificate installation is needed.
2. Tap **Connect** beside a saved server. The app checks the pinned server key, certificate validity and hostname, checks `/health`, signs a one-time challenge using its device key, and enters Chats after authentication succeeds. Multiple saved servers remain available after restarting the app. **Forget** removes a saved entry; importing a different key for an existing address requires explicitly forgetting it first.
3. Each installation generates a non-exportable P-256 signing key in Android Keystore, preferring StrongBox where available. The full public-key fingerprint is its user ID. Open **My ID QR** to show it, copy it, or share its text.
4. On another device connected to the same server, tap **Scan friend's ID** and scan the QR directly from their phone. Review and choose **Open chat**. **New chat** still accepts pasted IDs. Inside a conversation, **Verify friend's ID** scans and compares against that conversation's ID, showing match, mismatch, own-ID, or invalid-QR results. Incoming conversations appear automatically.
5. Tap a conversation to open it; use **All chats** or Android Back to return to the list. **Servers** lets you disconnect and select another saved server.

Server settings, the local user ID, and contacts are saved. Contacts and encrypted session keys are isolated by server public-key fingerprint. Older contacts stored under the previous address-based namespace are not automatically migrated; add their IDs again after pairing. Messages, unread counts, and drafts survive screen rotation but are held only for the current app session; disconnecting or terminating the process clears them. There is no background notification service or delivery/read receipt support. A local message bubble does not confirm delivery. A recipient must connect to finish the initial key exchange.

Both HTTP and WebSocket messaging now require device authentication. Sessions renew automatically on expiry and server restart. The server holds public keys in memory; private keys stay on the device. See the server's `AUTHENTICATION.md` for the exact protocol. Enrollment proves key ownership; there is no searchable directory or administrator approval flow.

This changes old `user-...` IDs to 64-character key-derived IDs. Share your new ID with contacts; old contact IDs are excluded from the chat list. App backup/device transfer is disabled. Clearing app data or reinstalling loses the device key and creates a new identity; there is no recovery/export flow yet.

An ID QR contains only the public device ID, never a private key. Compare it in
person or through another trusted channel: anyone can copy a static QR. A match
compares device identities; it is not a live proof that the QR presenter owns the
key, a verification of a person's legal identity, or an audit of peer encryption.
No permanent verified badge is implied. Reinstalling with a new device key changes
the ID and correctly produces a mismatch with an old conversation.

## Archived TLS implementation

The old private-CA deployment and certificate-import flow remain in [deprecated/local-tls](deprecated/local-tls/ARCHIVE.md). The active app pairs individual self-signed server keys. Renewed certificates with the same key work without pairing again; a different key, wrong address, or expired live certificate is rejected. The pairing bundle contains no private keys. Camera permission is optional when using text import.

## Verification

Run `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest` with the Android SDK and a compatible JDK installed. Endpoint tests cover domain/IP/IPv6 parsing, port bounds, malformed addresses, and recipient ID validation.

For device verification, connect two installations to the same server, exchange their IDs, send in both directions, add a third conversation to check routing, rotate the screen with a draft, and reopen the app to check saved settings and contacts. Check invalid/unreachable server addresses and keyboard resizing as well.

To include actual Kotlin-to-Flask authentication tests, set `CARRIEX_TEST_PYTHON`
to the server virtual environment's Python executable and `CARRIEX_TEST_SERVER_ROOT`
to its project directory before running unit tests. Without these, only the three
cross-language tests are skipped. Run `:app:connectedDebugAndroidTest` on an
emulator/device to check Keystore persistence, non-exportable private keys, text
import/persistence/forget, embedded scanner launch/cancel, and pinned TLS renewal
and rejection on Android. JVM tests also decode the Python-generated QR PNG and
complete actual HTTPS authentication and WebSocket messaging with that pairing.

### Connection recovery

The chat list and conversation screen show the current WebSocket connection status.
After a failed handshake, connection loss, or server close, the app retries after
1, 2, 4, 8, 16, then at most 30 seconds. A successful connection resets the delay.
WebSocket pings run every 20 seconds to detect stalled connections. Changing the
server or closing the ViewModel cancels retries and releases transport resources.
Existing HTTP sending/polling fallback remains available during reconnection.

Run `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug` to test and build.
`ServerTransportTest` uses real localhost sockets for initial handshake failure,
abrupt connection loss, server close frames, and cancellation of pending retries.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

Connection status is not a delivery receipt: a locally accepted WebSocket send
can still be lost if the connection breaks before server processing. These tests
do not replace device checks for Wi-Fi/mobile switching or Android backgrounding.
