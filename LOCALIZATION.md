# Reviewing English and Russian

**English** | [Русский](LOCALIZATION.ru.md)

@@ENGLISH@@

## Choose and compare languages

Open the drawer, **Settings → Language**, then choose **English**, **Русский**, or **System default**. The choice applies immediately and survives restart. The current settings page, selected conversation and draft are retained during activity recreation. On Android 13+, **Android Settings → Apps → Carriex → Language** changes the same app language. Earlier Android versions use Carriex's own selector. Both languages are packaged for offline switching, including app bundles. A language switch can briefly reconnect an active connection.

The default follows Android. Unsupported system languages fall back to English. Changing the app language does not translate contact/server/group names, message text, IDs, fingerprints, protocol fields, URLs, JSON, CSV, or technical diagnostic values. Known app validation, security and transport errors have Russian resources; unknown OS/library/server failures are shown under **Technical details** with their original diagnostic text. Android-owned permission dialogs, settings and keyboard also depend on the device/OS language and provider.

For a controlled comparison, use the same device, theme, display size, font scale, server and data. Select English, take a screenshot, switch to Russian, and repeat the same action. Avoid clearing app data: that destroys this installation's identity and local history. Language changes do not require reinstalling.

## Searchable text and documentation report

From the Android repository, with Python 3.10+:

```powershell
python tools/check_localization.py --report build/localization-review.html
Start-Process build/localization-review.html
```

The standalone report opens without a server or internet. It shows each resource key, English text and Russian text side by side. Search either language or a resource key. Expand **Documentation comparison** to read complete Markdown sources in parallel. If the sibling `../Carriex-server` checkout exists, it is included automatically. Each README/document also links to its other language using `.ru.md` companions; English originals stay available.

The checker exits nonzero for hardcoded text in the chat UI sources, missing/empty/unchanged translations, duplicate resource keys, mismatched positional placeholders or types, missing Russian plural categories, untranslated documentation, changed fenced command/protocol examples, broken local documentation links, and English documentation edited since its translation was reviewed. After manually updating and reviewing translated documents, run `python tools/check_localization.py --stamp-docs` to record the new English source revisions. It checks catalog structure, not translation quality or every visual state. English and Russian can reorder positional placeholders. Shared brand/language names use `translatable="false"`; machine identifiers are intentionally unchanged. The checker's negative fixtures run with `python -m unittest discover -s tools`.

Edit English in `app/src/main/res/values` and Russian in `values-ru`. UI strings use `getString`/Android plurals. Stable settings IDs and wire/storage values must never be translated. `localization/ErrorResources.kt` lists known existing domain diagnostics and marks only diagnostic arguments for recursive translation; contact/group/server names must remain opaque. If an English domain error changes, update its resource and adapter entry together. Use `LocalizedFormat.interval` for locale-aware quantities; interval input accepts both decimal point and comma.

## Build and runtime checks

Use an installed Android SDK and compatible JDK:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.carriex.localization.LocalizationAndroidTest'
python tools/export_localization_screenshots.py
Start-Process build/localization-screenshots/index.html
```

Quote the whole Gradle `-P...` argument in PowerShell. The focused instrumented class checks every diagnostic resource in both locales, nested translated errors while preserving a contact named `Blue`, integer/fractional Russian intervals, plural cases 1/2/5/11/21/22/25/101, notification quantity formatting, decimal comma input, language switching and retained settings state after recreation. It produces matched screenshots for Settings, Chats preferences, Privacy and Appearance. The export script reads only those debug-app screenshots; use `--serial` when several devices are connected. Build output and captures stay under ignored `build/`.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Instrumentation and unit-test reports are under `app/build/reports`. The language test restores the previous language after completion. Run tests on an emulator/test device; the app's other integration tests may populate or reset their own test fixtures.

## Manual acceptance checklist

Use two test installations on one updated relay. Keep one in English and one in Russian, then exchange roles.

| Surface | Compare and verify |
|---|---|
| Drawer and navigation | My ID, Chats, Servers, Settings; Back, toolbar, overflow and floating-action accessibility labels |
| Server setup | QR scanner prompt/cancel, text import, malformed JSON, review/save, key fingerprint, connect/cancel/disconnect, auto-connect, details/forget, offline history |
| Identity and contacts | Own QR, copy/share chooser, full/short IDs, optional local names, rename/clear, match/mismatch/own-ID verification, verified badge, remove/block/restart confirmations |
| Settings | Every category, themes/accents/preset names, custom hex validation/preview, per-sender colors and restored state; switch language and theme independently |
| Requests | Zero/one/many personal requests and group invitations, accept/retry/discard/block, join/decline, disconnected review and progress |
| Groups | Create/rename/invite by text, saved contact and QR; members/admin/invited labels, local rename, revoke/remove/leave/delete; signed activity and saved history |
| Conversation | Empty chat, Cyrillic/Latin/emoji/newlines, long names, current/older dates, Sending/Sent/Received/Read/Expired/Failed, timestamp accessibility; scrolling/focus/draft unchanged |
| Notifications | Separate request and group alerts, count-only alert, channel names/descriptions, foreground ongoing text/stop, interval units/validation, permissions, battery dialog, last-check/error states |
| Failure paths | Invalid ID/pairing/color/interval, unavailable server, TLS/key mismatch, missing one-time keys, quarantined messages and group security progress |

Inspect narrow portrait, landscape, dark/light themes and increased font size (for example 1.3× and 2×). Labels should wrap without hiding actions; no overlapping, cropped text or shifted timestamps. Test TalkBack descriptions. Check English grammar too: Russian text is often longer. For Russian numeric forms include 0, 1, 2, 5, 11, 21, 22, 25, 101 and 1.5 seconds.

A screen-by-screen check cannot be replaced by resource parity. Physical-device camera/permissions, vendor background scheduling, Android 7–12 app-language persistence and real two-phone error/delivery flows require device coverage beyond the focused emulator test.

## Server pages and documentation

Open generated `pairing.html` and the local statistics viewer (`python -m src.statistics serve`). Both have **Language / Язык** controls, initially based on the browser language. A saved choice takes precedence; `?lang=en` or `?lang=ru` forces an initial language for a comparison. The pairing HTML works offline with `pairing.png` beside it and embeds its own translations. Keep copied pairing files current after server reconfiguration.

Check titles, instructions, image/field descriptions, language selection, statistics cards/empty/error states, number formatting and refresh after a switch. User-supplied server names and pairing JSON stay intact. Statistics metric identifiers, UTC chart data and JSON/CSV export remain stable for reproducible measurements. Terminal/CLI output is a developer/operator diagnostic interface and remains English; Russian setup instructions explain the same commands.

Do not edit archived Kotlin/TLS snapshots to add live UI behavior: they are not compiled. Their Russian documentation explicitly identifies the historical setup.
