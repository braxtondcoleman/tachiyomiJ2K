# J2K APK update and signing continuity — 19 November 2026

**Development branch:** `feature/unified-reading-experience` / draft PR #14.
**Current release status:** NOT BUILD-VERIFIED. No APK produced by this branch's GitHub Actions yet.

## Why the existing test APK might not update in place

Android updates require:
1. Exactly the same Android package ID
2. Exactly the same signing certificate
3. A higher `versionCode` (and compatible Android target/SDK)

This fork's app variants are:
- Original upstream J2K package: `eu.kanade.tachiyomi`. The fork cannot replace this original app without its signing key.
- Temporary custom debug package: `eu.kanade.tachiyomi.customj2k.debug`. Prior CI test APKs used disposable/debug signing; the temporary key was not preserved for future matching updates.
- Permanent custom release package: `eu.kanade.tachiyomi.customj2k`. The build below uses a NEW, privately held, stable signing certificate. After one backup/restore migration, future APKs can update this release in place using that exact key.

**Never uninstall the old app solely to force an APK update.** Android will reject a signature mismatch; an uninstall can erase reader settings and local progress.

## Before building

1. In your existing J2K app, create an in-app backup containing your library, tracking, history, categories, and settings. Copy the backup outside the app's private storage (for example to your PC).
2. Keep the existing J2K app installed until the replacement's backup restoration and library are verified.
3. On Windows, have a full repository checkout or ZIP of the `feature/unified-reading-experience` branch, Android Studio's SDK with build tools and platform tools, and JDK 21.
4. Because the latest branch has **no CI results**, run and pass unit tests and Android compilation before installing.

## Path A — first permanent custom APK (expected when old APK used temporary CI signing)

1. In Windows Explorer, open the checked-out repository directory.
2. Double-click **`BUILD_STABLE_APK.cmd`**. First run: accept version code 115.
3. Create your own private keystore and passwords when prompted. The script stores `j2k-user-owned.jks` under your Documents/J2K Signing directory. **Back up that JKS and passwords offline.** Losing the key breaks future in-place updates.
4. The script runs Gradle's release unit tests and `assembleStandardRelease`, then checks APK identity and signature. If either step fails, it does **not** claim success.
5. The approved file, if built, is `update-apk/J2K-stable-release-v115.apk`. This is a **separate app**, never an overwrite of original J2K or the old debug app.
6. Install this stable APK, restore your J2K backup, and verify library/history/tracking/categories before deciding whether to remove an old test app. Keep your original J2K installation if desired.
7. Future versions: double-click the same launcher, enter 116 (then 117, etc.), and use **the same existing JKS**. The new stable app can update in place, preserving its Android app data.

Note: The script does not install APKs, access the phone without permission, or upload signing materials anywhere. Production APK version codes must always increase.

## Path B — direct in-place update of an existing custom debug app

Run the safety-check script `scripts/prepare_j2k_update.ps1` from a full repo checkout on a Windows PC with the phone connected and USB debugging authorized. Supply the **existing keystore that signed the installed debug app** and its key alias. It compares the installed APK's package/certificate/version with a freshly built debug APK. If anything differs, the output is blocked and no file is offered as update-ready.

With disposable GitHub Actions debug signing, the matching key was not retained. **A different key cannot sign an in-place update.** Use Path A instead; back up and restore into the permanently signed, separate app.

## How to trigger compile validation for this draft branch

The existing `.github/workflows/pr_build.yml` contains JVM tests, Android assembly, and a debug APK artifact named `J2K-Custom-Test-APK`. Because connector-authored commits may not generate an Actions run, it can be triggered by a **user-originated push** to the development branch (for example an innocuous README edit committed through the GitHub web editor) once Actions are enabled. Check the Actions tab for the actual run; do not assume it passed. The test artifact has temporary debug signing and **is not** the user-owned permanently signed release.

The `.github/workflows/upgrade_smoke.yml` uses a disposable test key to verify v114-to-v115 package, signature, and emulator data continuity. This proves upgrade mechanics for its synthetic test packages, not signature continuity with the user's installed phone APK.

## What a passing APK gate requires

- `./gradlew :app:testStandardDebugUnitTest :app:assembleStandardDebug` passes with this exact head commit.
- `./gradlew :app:testStandardReleaseUnitTest :app:assembleStandardRelease` passes for the user-owned release.
- Verify the built APK's **package ID, signer SHA-256 fingerprint, and versionCode**.
- Emulator and phone smoke tests: Fix issues dialog, library quick actions, 404 and stale-source recovery, source-aware unified chapter opening, progress, bookmarks, downloads, MAL import/export and additive read counts.
- A backup/restore check into a separate app before retiring any old install.
- Do not merge to master or replace the installed app until the tests are green.

**Current blocker:** The assistant cannot access your Windows Android SDK/keystore or perform a GitHub Actions write/dispatch, so it has NOT compiled or delivered the latest APK. The scripts prepare a fail-closed local build, not a fabricated success.
