# Phase E — Source-aware extension intelligence

## Implementation

- **Extension intelligence** in Settings → Library and **Diagnose extension and find compatible source** from manga's Source recovery menu.
- Exact source-ID matches outrank website-host and display-name matches. HTTPS website matches are advisory; a short title/name cannot masquerade as a confident match. No third-party service, scraping, or unconfigured repository is queried.
- Current installed extensions, user-configured repository catalogues, signature-untrusted packages, enabled languages, hidden sources, and installer steps are assessed **without modifying them**.
- Status distinctions: installed and current, update indexed, not listed by current index (not proof of abandonment), not installed, untrusted signer, hidden source, language disabled, and unloaded repository catalogues.
- On-demand **Refresh index** only queries repositories the user already configured. It never installs, trusts, or replaces extensions. The built-in extension manager remains the only installation/trust action.
- The extension-card UI now displays update, not-indexed and signer-review warnings alongside its content classification.
- Fixed stale obsolete/update status logic so an installed extension is removed from obsolete when it reappears in the index, or previous update targets disappear. An empty index does not falsely mark all installed extensions obsolete.
- Installer diagnostics reflect actual InstallStep state. Android package install approvals and Samsung protections are not bypassed, and package signatures are not automatically trusted.
- Repository membership is *only discovery provenance*. It is never described as proof that an APK is signed by a trusted publisher.

## Automated coverage

Unit tests in ExtensionIntelligenceTest.kt cover exact source ID, weaker website and display-name evidence, update/missing/index status, blank catalogs, untrusted signatures, disabled languages/hidden sources, short-name mismatches and configured repository semantics.

## Remaining release/device checks

- [ ] Current PR JVM + Android compilation pass.
- [ ] Same-certificate emulator upgrade with retained app data passes.
- [ ] Device: installed/missing/untrusted/obsolete, language disabled, alternative website, index refresh failure, pending/failed installer, manual signature review and Samsung installation approval.
- [ ] Device: production signed app using user-owned keystore and verified backup/restore.

Phase E is an extension **diagnosis and controlled discovery** milestone. It cannot magically repair broken source parsers, guarantee an APK is safe, or automate trust/installation. A future opt-in extension compatibility test harness and trusted source provenance audit could be added separately.
