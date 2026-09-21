# Carriex Android

## Connect and chat

1. Open the app and enter your Carriex server's domain or IP and port. Do not include a URL scheme or path. HTTPS and port 443 are the defaults; existing saved settings are retained. For the Android emulator, `10.0.2.2` reaches the host computer; physical devices need an accessible LAN or public address.
2. Tap **Connect**. The app checks the server's `/health` endpoint before opening Chats. The address and port are remembered for the next launch.
3. Each app installation has one generated user ID. Use **Copy my ID** to share it with another person.
4. On another device connected to the same server, tap **New chat**, enter that ID, and send a message. Incoming conversations appear automatically.
5. Tap a conversation to open it; use **All chats** or Android Back to return to the list. **Server** lets you disconnect and change the address.

Server settings, the local user ID, and contacts are saved. Contacts and encrypted session keys are isolated by server address. Messages, unread counts, and drafts survive screen rotation but are held only for the current app session; disconnecting or terminating the process clears them. There is no background notification service or delivery/read receipt support. A local message bubble does not confirm delivery. A recipient must connect to finish the initial key exchange.

The existing server routes and wire format are unchanged. Recipient selection uses shared user IDs; the server has no searchable user directory or account authentication flow.

## Local TLS

Use the server repository's `compose.https.yaml` and `HTTPS.md` to run Caddy with a private CA on your LAN. Enter the exact configured server IP/hostname and port 443 in the app. Choose **Local server certificate**, import the exported root CA, and compare the SHA-256 certificate fingerprint directly with the server before approving it. Trust is saved for that HTTPS endpoint only; it does not change Android's global trust store.

The selected CA is validated as a currently valid self-signed CA and used with the platform trust manager. Both HTTPS requests and WebSockets retain certificate-chain and hostname verification. Redirects are disabled; failed TLS never triggers a downgrade to HTTP. Without a local CA override, platform certificate trust is used. Remove the override to return to platform trust.

TLS protects the transport. Server-side user authentication and authenticated peer key exchange are still missing; this is not yet a production secure messenger.

## Verification

Run `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest` with the Android SDK and a compatible JDK installed. Endpoint tests cover domain/IP/IPv6 parsing, port bounds, malformed addresses, and recipient ID validation.

For device verification, connect two installations to the same server, exchange their IDs, send in both directions, add a third conversation to check routing, rotate the screen with a draft, and reopen the app to check saved settings and contacts. Check invalid/unreachable server addresses and keyboard resizing as well.
