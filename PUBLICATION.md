# Publication privacy

Commit source files, not local state or signing material. The ignore rules cover
local environment/configuration files, signing keys, databases and sidecars,
pairing exports, backups, screenshots and generated `app/release/` files.
Keep the signing keystore outside the repository and outside uploaded archives.
Release APKs intentionally expose their public signing certificate; keep the
same signing key so installed apps can accept future updates.

Before making an existing repository public, review every pushed branch/tag and
commit author/committer email, not just the current files. A deletion commit or
new ignore rule does not erase previous revisions. Rewriting remote history
requires coordination and separate authorization; do not force-push casually.

Also inspect GitHub Releases, Actions logs and artifacts, issue/PR comments and
attachments. Actions history and logs become public with the repository.
Share only the intended signed APK and release notes, not a ZIP of the whole
development workspace. A workspace may contain untracked keys or private data
outside the repositories that `.gitignore` cannot protect from a manual ZIP.

References: [GitHub visibility changes](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility),
[removing sensitive history](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository).
