# Public app releases

The app checks `https://api.github.com/repos/khivus/Carriex/releases/latest`
once per launch, without an account token. A private repository or a repository
without a release returns 404 and leaves normal startup alone. Publishing the
repository and a release enables the existing checker; no embedded secret is needed.
The build property `-PcarriexUpdateRepository=owner/repository` changes this source.

Publish a stable GitHub release with a numeric tag such as `v1.11`, a description
of its changes, and a **signed universal APK**. Its Android version name must
match the tag and its version code must exceed the installed app's code. The
current development version is `1.10`, code `11`. Use the same application ID
and signing key for every update. Android Studio's signed APK export can create
the release artifact; `assembleRelease` alone does not configure release signing.
Do not publish a debug APK. A single `Carriex-v1.11-universal.apk` is recommended;
ABI-specific assets may instead use Android ABI names in their filenames.

The update screen displays release notes, Download/Install, Dismiss, and a
checkbox to skip exactly that tag. Manual checking in Settings can revisit a
skipped release. Drafts, prereleases, older tags and releases without a matching
APK are ignored. Downloads are bounded to the advertised size and 300 MB,
checked against GitHub's SHA-256 asset digest when present, and inspected for
package ID, increasing version code, minimum Android version and signing key.
Android requests install-source permission and shows its own installer; Carriex
does not silently install an APK. A failed or interrupted download can be retried.

The server's `/health` response separately reports its version and a small
compatibility manifest. App/server release numbers are independent. Protocol 3
and the advertised app-code range determine full, limited or incompatible use.
Absent metadata is shown as limited compatibility. Incompatible servers are
stopped before device authentication. Guidance links to app releases and asks
the server owner to update when appropriate. Server maintainers must review the
advertised range whenever they change the protocol or support a new app release.

Live downloading and installation from GitHub can be exercised after a newer
signed release is public. Local tests cover release selection, version ordering,
compatibility and Android UI independently of repository visibility.
