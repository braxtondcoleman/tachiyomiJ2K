package eu.kanade.tachiyomi.ui.extension

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.library.ExtensionIntelligence
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Read-only source-aware extension diagnostics. Repository membership is NOT a
 * signature trust decision. No auto install, trust, uninstall or security bypass.
 */
object ExtensionIntelligenceDialog {
    fun show(
        activity: AppCompatActivity,
        sourceId: Long? = null,
        sourceName: String? = null,
        openManager: () -> Unit,
    ) {
        val manager: ExtensionManager = Injekt.get()
        val preferences: PreferencesHelper = Injekt.get()
        val sourceManager: SourceManager = Injekt.get()
        val installed = manager.installedExtensionsFlow.value.map { ext ->
            ExtensionIntelligence.Installed(
                pkg = ext.pkgName,
                name = ext.name,
                versionCode = ext.versionCode,
                libVersion = ext.libVersion,
                sources = ext.sources.map { s ->
                    ExtensionIntelligence.Source(
                        id = s.id,
                        name = s.name,
                        lang = s.lang,
                        website = (s as? HttpSource)?.baseUrl.orEmpty(),
                    )
                },
                updateFlag = ext.hasUpdate,
                obsoleteFlag = ext.isObsolete,
                repoUrl = ext.repoUrl,
            )
        }
        val available = manager.availableExtensionsFlow.value.map { ext ->
            ExtensionIntelligence.Available(
                pkg = ext.pkgName,
                name = ext.name,
                versionCode = ext.versionCode,
                libVersion = ext.libVersion,
                sources = ext.sources.map { s ->
                    ExtensionIntelligence.Source(s.id, s.name, s.lang, s.baseUrl)
                },
                repoUrl = ext.repoUrl,
            )
        }
        val untrusted = manager.untrustedExtensionsFlow.value.map { it.pkgName }.toSet()
        val fallback = sourceId?.let { manager.getStubSource(it) }
        val knownSource = sourceId?.let { sourceManager.get(it) }
        val knownHost = (knownSource as? HttpSource)?.baseUrl ?: fallback?.baseUrl
        val candidates = ExtensionIntelligence.find(
            sourceId = sourceId,
            sourceName = sourceName ?: knownSource?.name ?: fallback?.name,
            sourceHost = knownHost,
            installed = installed,
            available = available,
            configuredRepos = preferences.extensionRepos().get().toSet(),
            enabledLanguages = preferences.enabledLanguages().get(),
            hiddenSourceIds = preferences.hiddenSources().get(),
            untrustedPackages = untrusted,
            maxResults = 40,
            minLibVersion = ExtensionLoader.LIB_VERSION_MIN,
            maxLibVersion = ExtensionLoader.LIB_VERSION_MAX,
        )
        val notable = if (sourceId == null) {
            candidates.filter { it.status != ExtensionIntelligence.Status.INSTALLED }
                .ifEmpty { candidates.take(20) }
        } else {
            candidates
        }
        if (notable.isEmpty()) {
            val reason = if (available.isEmpty()) {
                "No extension index is currently cached. Refresh the configured repositories or open the extension manager."
            } else {
                "No indexed extension matches this source ID, name or website. A missing listing does not prove a website or extension is dead."
            }
            activity.materialAlertDialog()
                .setTitle("Extension intelligence")
                .setMessage(reason + "\n\nUntrusted APK packages: ${untrusted.size}. They remain blocked unless you review the signer.")
                .setPositiveButton("Refresh index") { _, _ -> refresh(activity, sourceId, sourceName, openManager) }
                .setNeutralButton("Open extensions") { _, _ -> openManager() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val rows: Array<CharSequence> = notable.map { result ->
            val marker = when (result.status) {
                ExtensionIntelligence.Status.UPDATE_AVAILABLE -> "↑ Update"
                ExtensionIntelligence.Status.INCOMPATIBLE_LIBRARY -> "⚠ Incompatible extension API"
                ExtensionIntelligence.Status.INDEX_NOT_LISTED -> "? Not indexed"
                ExtensionIntelligence.Status.UNTRUSTED_PACKAGE -> "⚠ Untrusted"
                ExtensionIntelligence.Status.MISSING -> "＋ Not installed"
                ExtensionIntelligence.Status.LANGUAGE_DISABLED -> "⚠ Language disabled"
                ExtensionIntelligence.Status.SOURCE_HIDDEN -> "⚠ Source hidden"
                else -> "Installed"
            }
            "${result.name}\n$marker · ${result.matchingSource?.name ?: result.pkg}"
        }.map { it as CharSequence }.toTypedArray()
        activity.materialAlertDialog()
            .setTitle(if (sourceId == null) "Extension health · ${notable.size}" else "Extensions for this manga")
            .setItems(rows) { _, position ->
                detail(activity, notable[position], manager, untrusted.size, openManager)
            }
            .setPositiveButton("Refresh index") { _, _ ->
                refresh(activity, sourceId, sourceName, openManager)
            }
            .setNeutralButton("Open extensions") { _, _ -> openManager() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun detail(
        activity: AppCompatActivity,
        candidate: ExtensionIntelligence.Candidate,
        manager: ExtensionManager,
        untrustedCount: Int,
        openManager: () -> Unit,
    ) {
        val info = manager.getInstallInfo(candidate.pkg)?.first
        val installer = when (info) {
            InstallStep.Pending -> "Pending: waiting to begin installation or system approval"
            InstallStep.Downloading -> "Downloading extension APK"
            InstallStep.Loading -> "Preparing extension APK"
            InstallStep.Installing -> "Android package installer working; check for system prompts"
            InstallStep.Installed, InstallStep.Done -> "Install/upgrade completed; refresh source list if it is not visible"
            InstallStep.Error -> "Install failed; check Android system prompts, package signature and storage"
            null -> "No current extension installation attempt"
        }
        val notes = buildString {
            appendLine("${candidate.name} · ${candidate.pkg}")
            appendLine("Status: ${candidate.status.name.replace('_', ' ').lowercase()}")
            appendLine("Indexed version: ${candidate.version}")
            appendLine("Repository host: ${candidate.repoHost ?: "unknown"}")
            appendLine("Index from configured repository: ${if (candidate.fromConfiguredRepo) "yes (not signer validation)" else "unverified or unavailable"}")
            appendLine("Installer: $installer")
            appendLine("Untrusted APK packages detected: $untrustedCount")
            appendLine()
            candidate.notes.forEach { appendLine("• $it") }
            appendLine()
            appendLine("Never trust or install an extension solely because it appears in an index. Review its publisher and signing certificate.")
        }
        activity.materialAlertDialog()
            .setTitle("Extension diagnosis")
            .setMessage(notes)
            .setPositiveButton("Manage extension") { _, _ -> openManager() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refresh(
        activity: AppCompatActivity,
        sourceId: Long?,
        sourceName: String?,
        openManager: () -> Unit,
    ) {
        val manager: ExtensionManager = Injekt.get()
        val progress = activity.materialAlertDialog()
            .setTitle("Refreshing configured repositories")
            .setMessage("Checking only repositories already configured in the app. No APK will be installed.")
            .create()
        progress.show()
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { manager.findAvailableExtensions() }
                progress.dismiss()
                show(activity, sourceId, sourceName, openManager)
            } catch (cancel: CancellationException) {
                progress.dismiss()
                throw cancel
            } catch (error: Exception) {
                progress.dismiss()
                activity.materialAlertDialog()
                    .setTitle("Repository refresh failed")
                    .setMessage("Could not refresh the extension index. Existing installed extensions were not changed.")
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }
}
