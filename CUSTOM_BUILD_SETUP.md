# J2K Custom: installation and updates

This repository is a personal, modified fork of TachiyomiJ2K. It retains upstream notices and licensing.

## Safety

- The original official app uses application ID `eu.kanade.tachiyomi.j2k`.
- This fork's release app uses `eu.kanade.tachiyomi.customj2k` and can coexist with the original.
- Debug builds use `eu.kanade.tachiyomi.customj2k.debug`, and beta builds use `eu.kanade.tachiyomi.customj2k.beta`.
- The upstream in-app APK updater is disabled in the custom standard flavor so it will not offer APKs with incompatible package IDs or signing keys.
- Use a new J2K backup when restoring reading history into the custom app; don't uninstall your original.
- **Samsung Auto Blocker may still block manual APK installs/updates** outside approved stores. This fork does not attempt to circumvent device security.

## Repeatable signed release builds

The GitHub Actions workflow `.github/workflows/custom_signed_release.yml` only runs when explicitly triggered. It requires four repository Actions secrets:

- `J2K_SIGNING_KEY_B64`: Base64 of a **private keystore you own**.
- `J2K_STORE_PASSWORD`: That keystore's password.
- `J2K_KEY_ALIAS`: Key alias in the keystore.
- `J2K_KEY_PASSWORD`: Key password.

Do not commit the keystore, passwords, or an APK signing private key. Keep an offline backup of the keystore in your control. Without the original signing key, Android cannot install newer builds over a previous signed release while preserving app data.

Version codes must increase for every future release (currently 114 in `buildSrc/src/main/kotlin/Dependencies.kt`), and the release application ID must stay unchanged.

**A debug APK is not a substitute for a consistently signed release.** GitHub's temporary debug signing key can change between runs, requiring a reinstall and losing data unless exported first.

## Limitations

The signed release workflow has not been validated with your signing secrets or on your Samsung phone. Library update/report features have only undergone CI compilation so far.
