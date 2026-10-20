# Carriex Android

## Connect and chat

1. On **Servers**, tap the floating **+** at the bottom right, then **Scan server QR** or **Import pairing text**. **Add server** is also available in the navigation drawer. Start the relay using `start-server.bat` or paired Docker; see `Carriex-server/PAIRING.md`. Review the server name, address, and trusted key before saving. Use pairing information obtained from the owner through a trusted channel.
2. **Servers** shows compact saved-server cards. Tap a card to browse its saved chats; use the always-visible **Connect** / **Disconnect** and **View messages** buttons. **View messages** opens saved history without connecting. Startup settings, server details and forgetting remain in the three-dot menu. Startup selection is exclusive. Connection setup keeps saved history and the composer available; sending becomes available after authentication.
3. **My ID** in the drawer shows this installation's public device ID and QR, with copy/share actions. Each installation uses a non-exportable Android Keystore P-256 key, preferring StrongBox where available.
4. From a server's chats, tap the floating pencil button to expand **Scan QR** and **Import text**, then choose an optional contact name. In a conversation, use the top-right three-dot menu for contact details, verification, rename, remove, block, and secure-session restart. **Remove chat** deletes local messages and the draft without blocking the contact; secure-session state and message IDs are retained to prevent old redeliveries restoring deleted content. The chat list's three-dot menu contains **Message requests**, **Chat security**, and connection controls.
5. The conversation's back arrow or Android Back returns to its chat list. Back from the chat list opens the drawer; choose **Servers** there. Rightward swipes open the drawer; swipe-back remains disabled. The small **Connect** button in the chat list connects the viewed server directly. **Chats** in the drawer returns to the viewed server's list or asks which server to open. Browsing another server's saved history keeps the existing foreground connection active. Connecting to another server closes the previous connection first.
6. **Notifications** in the drawer controls **message request alerts** and count-only background checks. New contact requests alert by default, including while Carriex is open; allow Android notification permission when prompted. Tap the alert to open that server's **Message requests**. Repeated messages from a pending requester do not alert again until the request is resolved. Request alerts work independently of background polling. The default background check interval is **15 minutes**. Enter a custom interval in seconds, minutes, or hours (decimals accepted), then choose **Apply interval**. Intervals below 15 minutes use an ongoing foreground-service notification; longer intervals use Android's network-aware periodic scheduler. Select the servers to check. The optional battery optimization prompt lets you keep optimization enabled or choose an exemption in Android settings. Checks remain best effort and may be delayed.

The top bar identifies the current page: **Carriex - servers**, **server name - chats**, or **Custom name (short ID)** inside a conversation. Page navigation and account actions stay in the drawer; conversation options are in the top-right three-dot menu.

For groups, choose **pencil > New group** on a connected server's chat list.
The creator is the permanent admin. Use the group's **three-dot menu > Invite
person** to invite a device ID or scan the person's **My ID** QR code; invited
people choose **Join group** or **Decline**. Joining shows progress immediately.
Sending clears the composer and shows a pending message before network work;
repeated taps cannot queue the same submission again. In **Group members**,
select a person to rename them locally for this server. Signed membership
changes appear as activity messages for the participants, including the person
who leaves or is removed, and remain in local history after reopening the app.
New members receive activity starting at their own join. Invitations and
rejoining do not reveal earlier events or events from an absence. Update the
relay and Android clients together for private history. If changes occurred
between invitation and join, joining waits for the admin's app to come online
and automatically confirm the new member's security checkpoint.
The admin can remove people or revoke invitations through **Group members**.
Members may leave; the admin can close the group with **Delete group**. Admin
rights cannot be transferred. Each join, leave or removal automatically refreshes
every current member's sender and signing keys; an offline member does this on
return before sending. Group messages use one signed sender-key ciphertext,
with fresh keys distributed through encrypted one-to-one sessions. Update both
the relay and app. See [group protocol and limits](../Carriex-server/GROUPS.md).

Leaving Carriex closes the live messaging connection. Background notification checks fetch only pending counts and an arrival revision using a cached, encrypted, restricted credential. They never download/decrypt/acknowledge messages. Opening the app restores its active connection and page; tapping a message notification opens its server. **Check now** and last-check details are in notification settings. Update the relay for this feature; see [notification protocol, scheduling, and limitations](../Carriex-server/NOTIFICATIONS.md).

Send using **Send**, the keyboard's Send action, or **Enter** on a hardware keyboard. **Shift+Enter** inserts a new line. Message status updates preserve the conversation's scroll position and the composer's focus and selection. New messages scroll to the bottom when you are already there; sending your own message also scrolls to it.

Chat lists and conversation headers show the first eight characters of a contact's ID. When you first open a chat, choose an optional local name: a named contact appears as **Name (1234abcd)**, and an unnamed contact as **1234abcd**. Names are saved separately for each server. Use the chat's **three-dot menu > Rename chat** to change or clear a name, and **Contact ID** to view or copy the complete ID. Verification always compares the full ID.

Chat rows show the latest message's sender time and are sorted newest first.
Today's messages show the time; older messages show the date and time. Delayed
older messages do not replace a newer preview or move a chat backward. Empty
chats and legacy messages without a known sender time appear below dated chats.

Server settings, the local user ID, contacts, and chat history are saved. Contacts, encrypted session keys, and chat history are isolated by server public-key fingerprint. Chat message bodies and pending wire payloads are encrypted with a phone-local Android Keystore key. Tap **Chats** beside a saved server to read its history offline. App backup/device transfer remains disabled. Older contacts stored under the previous address-based namespace are not automatically migrated; add their IDs again after pairing. Unread counts and drafts are session-only. A recipient who has published X3DH prekeys can receive an encrypted first message while offline.

New messages use X3DH session setup with signed prekeys and device-ID binding,
followed by classical Double Ratchet encryption (wire version 3).
Both phones must upgrade together; fixed-key v2 wire messages are rejected.

Text messages are limited to 90 KiB in UTF-8. Oversized text is rejected before
consuming a ratchet key. Permanent relay rejection shows **Failed**, while a
temporary connection/capacity error preserves exact ciphertext for retry. Saved
ciphertext remains retryable through the session's receiving retention window,
including after its 30-day new-message cutoff. If a long gap makes further
messages unreadable, use **ID & verify > Restart secure session** before sending
again; existing history and keys for delayed messages are preserved.

Private one-time prekeys are limited to 2,048 per server scope. Replenishment is
at most 50 keys every six hours and 200 per day; depleted pools pause replenishment
when a budget is exhausted, while established chats remain usable. Prekey cleanup
runs during publication maintenance, rather than scanning every key per message.
The existing v2 prekey relay API remains compatible and stores no ratchet secrets.
Existing local history is preserved, and unsent legacy messages are encrypted again.
Use **ID & verify > Verify friend's ID** to scan directly from their phone and
mark the contact verified. **Contact ID** also shows the encryption fingerprint
after the first exchange. Encryption-key changes are blocked.
**Chat security** controls explicit fallback when a contact has no one-time keys;
fallback is off by default. **Contact ID** shows whether the recorded session used
a one-time key. Invalid encrypted messages enter encrypted local quarantine and
do not obstruct later messages or generate delivery receipts. Requests can be
retried, discarded or blocked; blocked devices can be managed in **Chat security**.
Request storage is bounded; excess requests are discarded with an error.
The app supports 128 contacts per server and sends contact/block lists to that
relay for inbox capacity reservation. Sessions send for 30 days before a fresh
X3DH exchange; expired roots are removed during maintenance after a queue-delivery
grace period. Local history remains readable. A saved outgoing message whose
session expired shows **Expired**; copy its text and send again.
Double Ratchet derives a fresh message key for every send and refreshes root/chain
keys when a new peer ratchet public key arrives. Bounded, expiring skipped keys
support reordered messages across restarts. Exact saved ciphertext is retried
without advancing the ratchet again. Upgrading removes fixed-key session roots
and gives unsent fixed-key messages new IDs before resealing them; uncertain old
delivery can produce a duplicate. Already queued v2 ciphertext cannot be converted
by the relay. Saved history remains readable. These protections depend on clean
devices and key deletion; saved history, skipped keys and restored old database
snapshots remain relevant risks. This is not post-quantum or independently audited.
See the server's
[X3DH protocol and limits](../Carriex-server/X3DH.md).

Replace [`icon.png`](icon.png) in the Android repository root:
`D:\Uni\Carriex\Project\Carriex\icon.png`. Use a square PNG, preferably 512 x 512
or larger, with the main mark centered. Transparency is supported. Normal Android
Studio builds, `assembleDebug`, `assembleRelease`, and bundle builds regenerate
legacy, round, adaptive and themed launcher artwork automatically when this file
changes. The task uses the build JDK; Python and Pillow are not required. Generated
resources stay under `app/build/generated/launcherIcons/<variant>/res`; do not edit
them. The optional `python tools/update_launcher_icons.py <image.png>` command
only copies artwork into `icon.png`.

Outgoing bubbles show **Sending…** until the server accepts the message, **Sent** after server acceptance, and **Received** after the recipient saves it locally and acknowledges it. Messages waiting for acceptance are retried while connected and after restarting the app. The server holds encrypted messages until acknowledgement or expiry. There is no viewed/read receipt. Optional count-only background notifications are configured in the drawer. Receipt reporting can later be disabled independently of the recipient acknowledgement used to clear the server queue.

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

Connection status is separate from message status. X3DH identity keys, prekeys,
sessions and exact outgoing envelopes are encrypted locally and survive restart.
These tests do not replace device checks for Wi-Fi/mobile switching or Android
backgrounding.
