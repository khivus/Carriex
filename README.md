# Carriex Android

**English** | [Русский](README.ru.md)

Choose **Settings > Language** for **English**, **Русский**, or **System default**.
See [localization review](LOCALIZATION.md) for a searchable side-by-side catalog,
documentation comparison, paired emulator screenshots, and the acceptance checklist.

## Connect and chat

1. On **Servers**, tap the floating **+** at the bottom right, then **Scan server QR** or **Import pairing text**. Start the relay using `start-server.bat` or paired Docker; see [server pairing](../Carriex-server/docs/PAIRING.md). Review the server name, address, and trusted key before saving. Use pairing information obtained from the owner through a trusted channel.
2. **Servers** shows compact saved-server cards. Tap a card to browse its saved chats; use the always-visible **Connect** / **Disconnect** and **View messages** buttons. **View messages** opens saved history without connecting. Startup settings, server details and forgetting remain in the three-dot menu. Startup selection is exclusive. Connection setup keeps saved history and the composer available; sending becomes available after authentication.
3. **My ID (short ID)** at the top of the drawer shows this installation's public device ID and QR, with copy/share actions. Each installation uses a non-exportable Android Keystore P-256 key, preferring StrongBox where available.
4. Use the chat-list pencil action to **Scan contact QR**, **Add contact by ID or QR text**, or **Create group**. In a direct chat, its menu offers verification, rename, deletion, blocking and session restart. Deletion asks whether to block the contact and/or remove their encryption keys. An authenticated lifecycle notice is queued first. Temporary failures retry; a permanent server refusal is reported and does not prevent local deletion. Interrupted actions retain their exact packet for retry. Blocking takes effect locally immediately and its notice remains queued if unavailable. Read and delivery receipts remain separate from these system notices. Requests and group invitations share **Requests**.
5. Back returns from a conversation to its chat list. The drawer starts with **My ID (short ID)** and **Settings**, followed by **Servers** and a tree of saved servers and their chats. Tap any server or chat to open it and connect that server directly; the current destination is highlighted. Direct chats and groups share one list sorted by the latest message time. Browsing cached history remains available offline. Connecting another server closes the previous connection.
6. **Settings** has consistent cards for **Chats**, **Privacy**, **Notifications**, and **Appearance**. Language, theme and accent use inline selectors in Appearance; message samples have a separate preview card. Privacy shows server selection, session protection and blocked contacts directly. Background checks default to **on**, every **15 minutes**, for all servers. Notifications has separate request and group-invitation alerts, custom check intervals, Android notification permission and battery optimization status. Intervals below 15 minutes use a foreground service; longer intervals use the Android scheduler. Timing is best effort.

**Settings > Chats** has opt-in auto-accept for message requests and group invitations, applying to all saved servers. Blocked senders stay blocked; invalid encrypted messages still require review. Existing pending requests are processed when auto-accept is enabled while connected. Automatic group joins keep the current page open.

**Settings > Privacy > Send message read status** is **off by default**. When enabled, incoming direct-chat messages are reported as read only when their rows are visible in the foreground conversation. Reports retry while connected and are deduplicated across restarts. Turning the option off discards unsent read reports. Sender labels distinguish **Sent** (relay accepted), **Received** (recipient stored), and **Read** (recipient reported viewing). Read status is relay-reported metadata, not cryptographic proof; it requires the updated relay. Group messages continue to show delivery status only.

**Settings > Appearance** offers **System default** (the default), **White**, and **Black**, plus Purple, Blue, Green, Orange, and Rose accents. Choices persist across restarts, and changing them keeps the current settings category open. **Message bubbles** lets you set separate outgoing and incoming colors using presets or a custom `#RRGGBB` color, with a live preview. Your bubbles follow the current accent color by default; incoming bubbles use the theme surface by default. Text, sender names and timestamps automatically use contrasting light or dark text. The optional **Different colors for group senders** setting assigns a distinct bubble color to each sender and saves those assignments locally per server and group, including across restarts and membership changes. Your own group messages retain your outgoing color. With this option off, incoming group messages use the selected incoming color.

The top bar identifies the current page: **Carriex - servers**, **server name - chats**, or **Custom name (short ID)** inside a conversation. Page navigation and account actions stay in the drawer; conversation options are in the top-right three-dot menu.

For groups, choose **pencil > New group** on a connected server's chat list.
The creator is the permanent admin. Use the group's **three-dot menu > Invite
person** to invite a device ID or scan the person's **My ID** QR code; invited
people review it in **Requests > Group invitations** and choose **Join group** or **Decline**. Group invitation alerts are enabled by default and work independently of message request alerts. Joining shows progress immediately.
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
The admin can use **three-dot menu > Rename group** to change the shared name
for all current members. Renames are signed, appear in group activity, and preserve
membership and message keys. Older pending invitations remain joinable and adopt
the current name after the admin confirms the join. The admin can remove people
or revoke invitations through **Group members**.
Members may leave; the admin can close the group with **Delete group**. Admin
rights cannot be transferred. Each join, leave or removal automatically refreshes
every current member's sender and signing keys; an offline member does this on
return before sending. Group messages use one signed sender-key ciphertext,
with fresh keys distributed through encrypted one-to-one sessions. Update both
the relay and app. See [group protocol and limits](../Carriex-server/docs/GROUPS.md).

Switching to another app or the Home screen keeps the authenticated messaging connection and in-flight operations while Android keeps the process alive; the five-second HTTP refresh pauses. Incoming live messages are saved and acknowledged and show a private server-only notification. Returning resumes refresh without reconnecting or losing the selected page and drafts. Android may reclaim the process or suspend networking; normal reconnection handles a lost socket. When no live connection is available, scheduled checks fetch only pending counts with an encrypted restricted credential. They never download or acknowledge message bodies. See [notification protocol](../Carriex-server/docs/NOTIFICATIONS.md).

On Android 14+, you can swipe away the fast-check service's status notification.
Checks and message alerts continue. Reopening Carriex keeps that status hidden;
it returns after a real service restart. Visible status text follows interval and
language changes without restoring a dismissed notification. Intervals of at
least 15 minutes use periodic jobs without this service notification.

Send using **Send**, the keyboard's Send action, or **Enter** on a hardware keyboard. **Shift+Enter** inserts a new line. Message status updates preserve the conversation's scroll position and the composer's focus and selection. When you are at the bottom, the latest messages stay visible as the keyboard opens/closes, the composer grows, or new messages arrive. When you scroll up to read earlier messages, those changes preserve your reading position. This applies to direct and group chats.

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
[X3DH protocol and limits](../Carriex-server/docs/X3DH.md).

Replace [`icon.png`](icon.png) in the Android repository root:
`D:\Uni\Carriex\Project\Carriex\icon.png`. Use a square PNG, preferably 512 x 512
or larger, with the main mark centered. Transparency is supported. Normal Android
Studio builds, `assembleDebug`, `assembleRelease`, and bundle builds regenerate
legacy, round, adaptive and themed launcher artwork automatically when this file
changes. The task uses the build JDK; Python and Pillow are not required. Generated
resources stay under `app/build/generated/launcherIcons/<variant>/res`; do not edit
them. The optional `python tools/update_launcher_icons.py <image.png>` command
only copies artwork into `icon.png`.

Outgoing bubbles show **Sending…** until the server accepts the message, **Sent** after server acceptance, and **Received** after the recipient saves it locally and acknowledges it. Messages waiting for acceptance are retried while connected and after restarting the app. The server holds encrypted messages until acknowledgement or expiry. Optional direct-chat **Read** status is controlled by **Settings > Privacy > Send message read status**, off by default. Delivery acknowledgements still clear the queue independently. Count-only background notifications are configured in **Settings > Notifications**.

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

## App updates and server compatibility

On launch, Carriex checks the public GitHub release and shows its changes with Download/Install, Dismiss and Skip this version. See [release preparation and compatibility](UPDATES.md). Private-repository checks are silent until a public release is available. Server versions and full/limited/incompatible status appear after checking the server.

## Chat and group history actions

Group members have a dedicated page with visible rename controls, inline name editing, and an admin removal button with confirmation. Local names remain private to this device and server. Leaving or closing a group offers **Delete saved history**. After leaving, being removed or closure, the same action remains in the group menu, including offline. It erases local messages and displayed event history while retaining membership checkpoints and IDs that prevent deleted packets from returning. It does not delete other members' copies.

Direct-chat lifecycle notices are encrypted and authenticated inside the existing version-3 ratchet envelope. History deletion, blocking, forgetting keys and restarting the secure session appear as separate system entries. Both contacts need app 1.10/code 11 to display these typed notices; basic messages retain their prior format. Key removal forgets this contact's pinned encryption identity, sessions, chain keys and skipped keys, and clears verification. Device identity, other contacts and deletion deduplication IDs remain. A future contact can establish new keys; the peer is prompted by the notice and retires its old sending session automatically. Server acceptance of a notice does not prove that its recipient has viewed it. An offline or blocked recipient may receive it later or reject it.

This update asks existing users to complete setup once too; saved servers, messages and preferences are retained. Interrupted setup resumes its current step. Completing it prevents it from reopening on subsequent launches or future builds using the same setup revision. The first successful connection offers startup autoconnect once. The server chat-list menu also has **Connect on app startup** and **Server details** beside Connect/Disconnect. Notification server switches and manual-check status use the same cards as other settings.
