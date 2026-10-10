# Carriex Android

## Connect and chat

1. Start your server using its `start-server.bat` or paired Docker deployment; see `Carriex-server/PAIRING.md`. Open its generated `pairing.html`. In the app choose **Scan server QR**, or **Import pairing text** and paste the complete JSON. Review the name, address and key, then choose **Save server**. Only use pairing information obtained from the owner through a trusted channel. No extra scanner app, domain registration, or system certificate installation is needed.
2. Tap **Connect** beside a saved server. Chats opens immediately so you can browse saved messages and prepare a draft while the app connects in the background. The app checks the pinned server key, certificate validity and hostname, checks `/health`, and signs a one-time challenge using its device key. Sending becomes available once connected. Multiple saved servers remain available after restarting the app. **Forget** removes a saved entry; importing a different key for an existing address requires explicitly forgetting it first.
3. Each installation generates a non-exportable P-256 signing key in Android Keystore, preferring StrongBox where available. The full public-key fingerprint is its user ID. Open a server's **Chats**, then **My ID** to show its QR, copy it, or share its text.
4. On another device connected to the same server, tap **New chat**, then **Scan QR**, and scan the QR directly from their phone. Review and choose **Open chat**. Choose **Import text** to paste a user ID or contact QR text instead. Inside a conversation, the **ID & verify** menu beside **All chats** offers **My ID** and **Verify friend's ID**. Verification scans and compares against that conversation's ID, showing match, mismatch, own-ID, or invalid-QR results. Incoming conversations appear automatically.
5. Tap a conversation to open it; use **All chats** or Android Back to return to the chat list. **Servers**, or Back from the chat list, opens the saved servers without disconnecting. Each server shows its connection status and unread count. **Chats** returns to the current session for a connected server, or opens saved history for another server while your current connection stays active. **Disconnect** explicitly closes the connection. Connecting to another server closes the previous connection and cancels its retries first.
6. Check **Connect on app startup** beside the server you want to use automatically. Only one server can be selected; selecting another replaces the previous choice. Uncheck it to disable startup autoconnect. Forgetting the selected server clears the choice. Startup autoconnect opens Chats immediately. Connection failures keep saved chats available with an error and a manual retry; **Servers** remains available while connecting.

Send using **Send**, the keyboard's Send action, or **Enter** on a hardware keyboard. **Shift+Enter** inserts a new line. Message status updates preserve the conversation's scroll position and the composer's focus and selection. New messages scroll to the bottom when you are already there; sending your own message also scrolls to it.

Chat lists and conversation headers show the first eight characters of a contact's ID. When you first open a chat, choose an optional local name: a named contact appears as **Name (1234abcd)**, and an unnamed contact as **1234abcd**. Names are saved separately for each server. Use **ID & verify > Rename chat** to change or clear a name, and **Contact ID** to view or copy the complete ID. Verification always compares the full ID.

Server settings, the local user ID, contacts, and chat history are saved. Contacts, encrypted session keys, and chat history are isolated by server public-key fingerprint. Chat message bodies and pending wire payloads are encrypted with a phone-local Android Keystore key. Tap **Chats** beside a saved server to read its history offline. App backup/device transfer remains disabled. Older contacts stored under the previous address-based namespace are not automatically migrated; add their IDs again after pairing. Unread counts and drafts are session-only. A recipient must connect to finish the initial key exchange.

Outgoing bubbles show **Sending…** until the server accepts the message, **Sent** after server acceptance, and **Received** after the recipient saves it locally and acknowledges it. Messages waiting for acceptance are retried while connected and after restarting the app. The server holds encrypted messages until acknowledgement or expiry. There is no viewed/read receipt or background notification service yet. Receipt reporting can later be disabled independently of the recipient acknowledgement used to clear the server queue.

Message footers show the sender's original send time using the phone's time format. For outgoing messages, the status sits nearer the center and the time nearer the screen edge; reserved status space keeps the time fixed during updates. The send time travels inside the encrypted payload and survives delayed delivery, retries, and history reloads. Received messages from older app versions have no recorded sender time and show no timestamp. Update both devices to read the new timestamped message format.

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

Connection status is separate from message status. Pending first-message key
exchange material is also encrypted locally and restored after an app restart.
These tests do not replace device checks for Wi-Fi/mobile switching or Android
backgrounding.
