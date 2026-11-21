# Releasing (in-app updates)

The app checks `https://raw.githubusercontent.com/quantari-solutions/TheatreSync-/main/releases/latest.json` (this repository is public; a separate `theatresync-releases` repo is also checked, but not needed)
(on start, at most every 10 minutes, and when the owner taps "Check for updates"). If its `versionCode` is
higher than the installed one, Settings → About offers **Update**: the app downloads `apkUrl`, checks the
SHA-256, and installs with Android's PackageInstaller (Android shows "Update this app?").

`theatresync-releases` is a **public** repository holding only finished APKs and `latest.json`, because this
code repository is private (a private repo can't serve downloads without putting a token in the app).

Only when the owner says "release":
1. Merge to `main`, then `scripts/build-android.sh`.
2. `scripts/publish-release.sh releases "What's new" https://raw.githubusercontent.com/quantari-solutions/TheatreSync-/main/releases` — refuses any APK whose
   signing certificate isn't the one in `scripts/release-signing-cert.sha256` (otherwise Android would refuse
   the update and the owner would have to uninstall).
3. Commit `releases/` in a PR and merge it: phones see the update once it's on `main`.

Signing: releases are signed with the key the owner's installed app already has (SHA-256 in
`scripts/release-signing-cert.sha256`). The key itself never goes in a repository; it's kept as a secret.

## Owner's rule (2026-11-21)

All updates reach the phone through the app (Settings → About → Update) unless the owner asks otherwise:
no APK files in chat. Only the newest APK is kept in `releases/` (older ones are removed in the same PR).
