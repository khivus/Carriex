# Archived local TLS implementation

**English** | [Русский](ARCHIVE.ru.md)

Removed from the active application at the user's request: local CA provisioning and certificate import made the Docker-and-phone setup too complicated.

This directory preserves the complete TLS-era versions of the affected client source files, the CA validator, localhost TLS tests, and client instructions. Files here are outside Gradle source sets and are not compiled into the APK. The matching server deployment is archived under `D:/Git/Carriex-server/deprecated/local-tls/`.

The active app defaults to HTTP on port 5000. The pre-existing optional HTTPS toggle still supports servers with certificates trusted by Android; there is no custom-CA import flow. Existing saved connection preferences are retained.

To revisit this implementation, port the relevant changes from these snapshots into current source and restore the matching server deployment. Do not blindly overwrite newer source files. The archived README describes the old behavior, not the active app.
