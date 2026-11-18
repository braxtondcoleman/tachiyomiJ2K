package eu.kanade.tachiyomi.data.library

import java.net.URI
import java.util.Locale

/**
 * Pure, advisory extension discovery. Only already-configured indexes are
 * queried by the UI; this class neither downloads APKs nor trusts signers.
 */
object ExtensionIntelligence {
    data class Source(
        val id: Long,
        val name: String,
        val lang: String,
        val website: String = "",
    )

    data class Installed(
        val pkg: String,
        val name: String,
        val versionCode: Long,
        val libVersion: Double,
        val sources: List<Source>,
        val updateFlag: Boolean,
        val obsoleteFlag: Boolean,
        val repoUrl: String?,
    )

    data class Available(
        val pkg: String,
        val name: String,
        val versionCode: Long,
        val libVersion: Double,
        val sources: List<Source>,
        val repoUrl: String,
    )

    enum class Status {
        INSTALLED, UPDATE_AVAILABLE, INDEX_NOT_LISTED, MISSING,
        UNTRUSTED_PACKAGE, LANGUAGE_DISABLED, SOURCE_HIDDEN, UNKNOWN_CATALOGUE,
    }

    data class Candidate(
        val pkg: String,
        val name: String,
        val status: Status,
        val score: Int,
        val version: Long,
        val repoHost: String?,
        val fromConfiguredRepo: Boolean,
        val matchingSource: Source?,
        val notes: List<String>,
    )

    fun host(url: String): String? = try {
        val parsed = URI(url.trim())
        parsed.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?.takeIf { parsed.scheme?.lowercase(Locale.ROOT) == "https" && it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** A configured repo is a discovery source, NOT a trusted signing identity. */
    fun configured(repoUrl: String, configuredUrls: Set<String>): Boolean =
        repoUrl.isNotBlank() && configuredUrls.any {
            it.trim().trimEnd('/') == repoUrl.trim().trimEnd('/')
        }

    private fun normalized(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    fun find(
        sourceId: Long?,
        sourceName: String?,
        sourceHost: String?,
        installed: List<Installed>,
        available: List<Available>,
        configuredRepos: Set<String>,
        enabledLanguages: Set<String>,
        hiddenSourceIds: Set<String>,
        untrustedPackages: Set<String>,
        maxResults: Int = 20,
    ): List<Candidate> {
        require(maxResults in 1..100)
        val allPackages = (installed.map { it.pkg } + available.map { it.pkg } + untrustedPackages)
            .distinct()
        val installedByPkg = installed.associateBy { it.pkg }
        val availableByPkg = available.associateBy { it.pkg }
        val normalizedName = sourceName?.let(::normalized).orEmpty()
        val queryHost = sourceHost?.let(::host)
        val indexLoaded = available.isNotEmpty()
        return allPackages.mapNotNull { pkg ->
            val local = installedByPkg[pkg]
            val remote = availableByPkg[pkg]
            val sources = (remote?.sources.orEmpty() + local?.sources.orEmpty()).distinctBy { it.id }
            val exact = sourceId != null && sources.any { it.id == sourceId }
            val byName = if (normalizedName.length >= 5) {
                sources.firstOrNull { normalized(it.name) == normalizedName }
            } else null
            val byHost = if (queryHost != null) sources.firstOrNull { host(it.website) == queryHost } else null
            val match = sources.firstOrNull { it.id == sourceId } ?: byName ?: byHost
            val score = when {
                exact -> 200
                byHost != null -> 100
                byName != null -> 75
                sourceId == null -> 40
                else -> 0
            }
            if (score == 0) return@mapNotNull null
            val status = when {
                pkg in untrustedPackages -> Status.UNTRUSTED_PACKAGE
                match?.id?.toString() in hiddenSourceIds -> Status.SOURCE_HIDDEN
                match != null && match.lang !in enabledLanguages -> Status.LANGUAGE_DISABLED
                local == null -> Status.MISSING
                remote != null && (remote.versionCode > local.versionCode || remote.libVersion > local.libVersion) ->
                    Status.UPDATE_AVAILABLE
                indexLoaded && remote == null -> Status.INDEX_NOT_LISTED
                local.obsoleteFlag && indexLoaded -> Status.INDEX_NOT_LISTED
                else -> Status.INSTALLED
            }
            val notes = buildList {
                if (exact) add("Exact source ID in extension metadata")
                else if (byHost != null) add("Same HTTPS website host; verify manga identity")
                else if (byName != null) add("Same displayed source name; verify website")
                if (status == Status.SOURCE_HIDDEN) add("Source is hidden in preferences")
                if (status == Status.LANGUAGE_DISABLED) add("Source language is disabled")
                if (status == Status.UPDATE_AVAILABLE) add("Newer extension version is indexed; update requires review")
                if (status == Status.INDEX_NOT_LISTED) add("Not listed in currently loaded catalogues; NOT proof of abandonment")
                if (status == Status.UNTRUSTED_PACKAGE) add("APK signer is untrusted; review signature separately")
                if (!indexLoaded) add("Repository index not loaded; availability and obsolescence unknown")
                if (remote != null && !configured(remote.repoUrl, configuredRepos)) add("Index origin does not match a configured repository")
                add("Discovery results do not establish APK signer trust")
            }
            Candidate(
                pkg = pkg,
                name = remote?.name ?: local?.name ?: pkg,
                status = status,
                score = score,
                version = remote?.versionCode ?: local?.versionCode ?: 0,
                repoHost = (remote?.repoUrl ?: local?.repoUrl)?.let(::host),
                fromConfiguredRepo = remote != null && configured(remote.repoUrl, configuredRepos),
                matchingSource = match,
                notes = notes,
            )
        }.sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenBy { it.status == Status.UNTRUSTED_PACKAGE }
                .thenBy { it.name },
        ).take(maxResults)
    }
}
