# Milestone A — library safety, testable backups, and repeatable Android upgrades

**Current status:** implementation in [PR #3](https://github.com/braxtondcoleman/tachiyomiJ2K/pull/3), stacked onto [PR #2](https://github.com/braxtondcoleman/tachiyomiJ2K/pull/2). **Do not merge or replace the original J2K installation without CI + device verification.**

## 1. What changed

1. Portable alternative-source links remain source ID + manga URL, not local database row IDs.
2. Restoring a backup over an existing manga now **unions existing and backup links** (deduped by source ID and URL) instead of discarding the backup's links during `copyFrom`. It retains unrelated existing extension memo fields.
3. Backup validation and actual restoration both reject malformed linked-source records **before** modifying the database. Missing extensions are allowed because they may be installed later.
4. A `.tachibk` protobuf JVM regression suite checks linked-source identity, chapter read/bookmark/last-page data, history and legacy no-links backups.
5. Automatic backup filenames now include seconds and milliseconds. The existing filename matcher still recognizes legacy minute-only backup names. Existing automatic backups are only pruned *after* the new one validates.
6. An explicit `J2K_RELEASE_VERSION_CODE` override allows monotonically increasing release versions without changing package identity or signing.
7. The **signed release** workflow refuses to upload without a user-owned signing key and an expected certificate SHA256 fingerprint. It verifies package name, versionCode and exact signing certificate *after* building the APK.
8. A **disposable Android emulator smoke test** generates a temporary key inside CI, builds versions 114/115 with the same key, verifies signatures and package identity, installs v114, writes a private marker file, upgrades in-place to v115 and checks the marker and Android app UID survived. The CI key is destroyed, never published.

## 2. What remains unverified without the phone

- Actual installation and future upgrades on your Galaxy with Samsung Auto Blocker enabled.
- A real `.tachibk` backup round-trip restoring into a disposable J2K Custom installation with extensions installed.
- Downloaded chapter file ownership and content-provider migration on the real device.
- Permanently signed release updates: the **user must supply and preserve the private signing key**. No GitHub connector available here can safely set the repository's Actions secrets.
- Android emulator success is evidence for upgrade mechanics, not a guarantee across Samsung-specific policies.
- The current J2K debug app is `eu.kanade.tachiyomi.customj2k.debug`, the stable personal release app is `eu.kanade.tachiyomi.customj2k`, and the original is `eu.kanade.tachiyomi.j2k`. **A release APK does not update an installed debug APK** because these are intentionally distinct apps.

## 3. Create your permanent signing identity ONCE (private and user-owned)

Use an up-to-date Java JDK with `keytool` installed on a device you control. Example in PowerShell on Windows:

```powershell
keytool -genkeypair -v -storetype PKCS12 -keystore "$HOME\J2K-Custom.jks" -alias j2kcustom -keyalg RSA -keysize 3072 -validity 10000
keytool -list -v -keystore "$HOME\J2K-Custom.jks" -alias j2kcustom
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\J2K-Custom.jks"))
```

Record the keystore password, key password, alias `j2kcustom`, and the `SHA256` certificate fingerprint. The Base64 result is **secret**, just like the keystore file.

In the repository's **Settings → Secrets and variables → Actions → New repository secret**, add:

| Secret name | Value |
| --- | --- |
| `J2K_SIGNING_KEY_B64` | Base64-encoded full private keystore bytes (NOT the fingerprint) |
| `J2K_STORE_PASSWORD` | Keystore password |
| `J2K_KEY_ALIAS` | `j2kcustom` |
| `J2K_KEY_PASSWORD` | Key password (can match keystore password) |
| `J2K_SIGNING_CERT_SHA256` | Public SHA256 certificate fingerprint from `keytool -list -v` |

**Never commit, paste into a PR/issue, or publish your keystore, password, or Base64 key.** Keep an offline encrypted backup. If that signing key is lost, you cannot install a future APK update over the existing signed release without reinstalling and restoring your data.

After the code is reviewed and merged into `master`, use **Actions → Signed J2K Custom APK → Run workflow**. Set `version_code=114` for your first signed release (unless a higher version was already installed), then 115, 116, etc. Every new release must use **the same key, same `eu.kanade.tachiyomi.customj2k` app ID, and a higher version code**.

The signed workflow deliberately requires a correct signing fingerprint and will **not upload** the APK if the signature, package name or versionCode disagrees.

On the phone: download the verified signed release artifact ZIP from GitHub Actions, extract the APK, and install or update it. Samsung Auto Blocker can still require installation approval; this project **does not circumvent** Android security.

## 4. Development build / private data smoke test

For the CI tests:
- [Android compile + JVM backup tests](https://github.com/braxtondcoleman/tachiyomiJ2K/actions/workflows/pr_build.yml): `:app:testStandardDebugUnitTest` then `:app:assembleStandardDebug`.
- [Signed upgrade safety smoke](https://github.com/braxtondcoleman/tachiyomiJ2K/actions/workflows/upgrade_smoke.yml): temporary CI key, two versions, APK metadata/certificate verification and Android emulator in-place install.
- [Signed user release workflow](https://github.com/braxtondcoleman/tachiyomiJ2K/blob/feature/milestone-a-library-safety/.github/workflows/custom_signed_release.yml): manual-only, user-owned key.

The disposable CI key proves that Android allows an in-place upgrade with the **same** key; it is **not** the key to use on your actual phone.

## 5. Phone restore checklist (test app only)

1. Keep the original J2K installed, and export a recent `.tachibk` into a safe location.
2. Use **J2K Custom**, not the original app. Choose a small, harmless sample library.
3. Link source B to a favorite manga A using **Find best sources → Link**, confirming both series really match.
4. Create a backup from J2K Custom. Check that it exists and is nonempty.
5. Link another source C, then restore the prior backup onto the **same custom app**. Verify links B and C remain (non-destructive union). Record chapter read/bookmark/last-page data before and after.
6. Restore the backup into a separate disposable custom installation/profile if available, and confirm that link B (source ID + URL) survives with the original read history. The source may initially show missing if its extension isn't installed.
7. In Android Settings, verify that both original J2K and J2K Custom still exist independently. **Never uninstall your original app for this test.**
8. Download a future **same-package, same-cert, higher-versionCode** release; it must install over the existing *release* app and retain the custom app's library. Samsung approval may still be necessary.

**Open limitation:** The exact full merged-chapter timeline is not implemented. Source links provide navigation, not shared chapter progress.

## 6. Status tally policy

- **Committed** means source files were written to the fork.
- **CI tested** means the named job completed with a success result on that commit.
- **On-phone verified** requires the user to actually install, restore, and report results.
- **Release-ready** requires all three plus a stable key stored privately. Don't mark this milestone complete simply because code was committed.
