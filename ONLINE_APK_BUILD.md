# Build TachiyomiJ2K online with Codemagic

**No Windows PC needed for the compilation step.**

1. On your phone, open https://codemagic.io and sign in with GitHub.
2. Use a Personal/Individual Codemagic account to qualify for the 500 free macOS M2 build minutes each month. The free minutes are not for Teams.
3. Choose Add application, connect braxtondcoleman/tachiyomiJ2K, and select Native Android.
4. Select the **feature/unified-reading-experience** branch (not master). If asked, choose to check for **codemagic.yaml**.
5. Select the **J2K custom - Android test APK** workflow and press Start new build.
6. Once finished, open its Artifacts to download the .apk. If the build fails, use its logs to identify the compilation errors.

The branch already includes codemagic.yaml with Java 21, a macOS M2 free-tier build machine, the project's Gradle tests, assembleStandardDebug, and APK artifact upload.

## IMPORTANT: testing is not updating your existing app

This is a temporary debug-signed APK. It may not update your existing custom debug app, since Android requires the same package name and signing certificate as the installed app and a newer version code. It will not overwrite the original J2K installation either.

Never uninstall the current reader simply to force an install. Back up your library, history, categories, tracking, and settings. After tests pass, build a separately installed stable custom version with a user-owned persistent signing key, restore the backup once, and keep that same key for future updates.

No private signing key or password is stored in the GitHub repository or used by this Codemagic test workflow.
