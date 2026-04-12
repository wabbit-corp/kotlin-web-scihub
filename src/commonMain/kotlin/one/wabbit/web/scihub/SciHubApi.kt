// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(ExperimentalTime::class)

package one.wabbit.web.scihub

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.accept
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.wabbit.lang.html.HtmlDocument
import one.wabbit.web.common.Etiquette
import one.wabbit.web.common.RetryAction
import one.wabbit.web.common.RetryPolicy
import one.wabbit.web.common.Schedule
import one.wabbit.web.common.Timeouts
import one.wabbit.web.common.applyEtiquette
import one.wabbit.web.common.applyTimeouts
import one.wabbit.web.common.consumeRawBodyPrefixUtf8
import one.wabbit.web.common.runWithRetry

sealed interface SciHubQuery {
    val value: String

    data class Doi(override val value: String) : SciHubQuery

    data class Pmid(override val value: String) : SciHubQuery

    data class Title(override val value: String) : SciHubQuery

    data class Url(override val value: String) : SciHubQuery
}

data class Mirror(val baseUrl: String, val source: String)

data class MirrorEntry(
    val baseUrl: String,
    val source: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastSuccessAt: Instant? = null,
    val lastFailureAt: Instant? = null,
)

data class MirrorCacheEntry(val mirrors: List<MirrorEntry>, val expiresAt: Instant)

data class ResolvedPaper(
    val query: SciHubQuery,
    val mirror: String,
    val pdfUrl: String,
    val title: String?,
    val doi: String?,
    val journal: String?,
    val year: String?,
    val authors: List<String>,
)

sealed class SciHubError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoMirrorsDiscovered(val reasons: List<String>) :
        SciHubError(
            buildString {
                append("No Sci-Hub mirrors discovered")
                if (reasons.isNotEmpty()) {
                    append(": ")
                    append(reasons.joinToString("; "))
                }
            }
        )

    class MirrorDiscovery(val provider: String, cause: Throwable) :
        SciHubError(
            "Mirror discovery failed for $provider: ${cause::class.simpleName}: ${cause.message}",
            cause,
        )

    class Http(val url: String, val status: Int, val bodySample: String?) :
        SciHubError(
            buildString {
                append("HTTP ")
                append(status)
                append(" from ")
                append(url)
                if (!bodySample.isNullOrBlank()) {
                    append(", body sample: ")
                    append(bodySample.take(256))
                }
            }
        )

    class Network(val url: String, cause: Throwable) :
        SciHubError(
            "Network failure talking to $url: ${cause::class.simpleName}: ${cause.message}",
            cause,
        )

    class NotFound(val mirror: String, val query: SciHubQuery, val bodySample: String) :
        SciHubError("Paper not found on $mirror for ${query.value}: ${bodySample.take(256)}")

    class Captcha(val mirror: String, val query: SciHubQuery, val bodySample: String) :
        SciHubError(
            "Captcha or anti-bot challenge on $mirror for ${query.value}: ${bodySample.take(256)}"
        )

    class Parse(val mirror: String, val query: SciHubQuery, val bodySample: String) :
        SciHubError(
            "Could not extract PDF link from $mirror for ${query.value}: ${bodySample.take(256)}"
        )

    class AllMirrorsFailed(val query: SciHubQuery, val errors: List<SciHubError>) :
        SciHubError(
            buildString {
                append("All Sci-Hub mirrors failed for ")
                append(query.value)
                if (errors.isNotEmpty()) {
                    append(": ")
                    append(
                        errors.joinToString("; ") {
                            it.message ?: it::class.simpleName ?: "SciHubError"
                        }
                    )
                }
            }
        )
}

typealias SciHubRetryPolicy = RetryPolicy<SciHubError>

interface MirrorProvider {
    val name: String

    suspend fun discover(httpClient: HttpClient, config: SciHubApi.Config): List<Mirror>
}

interface SciHubApi {
    data class Config(
        val mirrorProviders: List<MirrorProvider> = defaultMirrorProviders(),
        val mirrorCacheTtl: Duration = 6.hours,
        val staleWhileError: Duration = 24.hours,
        val etiquette: Etiquette = Etiquette("one.wabbit.scihub/1.0"),
        val timeouts: Timeouts = Timeouts(),
        val retryPolicy: SciHubRetryPolicy? = defaultSciHubRetryPolicy(),
        val onMirrorRefresh:
            ((duration: Duration, success: Boolean, error: SciHubError?) -> Unit)? =
            null,
    ) {
        init {
            require(mirrorProviders.isNotEmpty()) { "mirrorProviders must not be empty" }
            require(mirrorCacheTtl > Duration.ZERO) { "mirrorCacheTtl must be positive" }
            require(staleWhileError >= Duration.ZERO) { "staleWhileError must be non-negative" }
        }
    }

    interface Cache {
        suspend fun getMirrors(): MirrorCacheEntry?

        suspend fun putMirrors(entry: MirrorCacheEntry)

        suspend fun recordSuccess(baseUrl: String)

        suspend fun recordFailure(baseUrl: String)

        companion object {
            fun inMemory(clock: Clock = Clock.System): Cache =
                object : Cache {
                    private val mutex = Mutex()
                    private var snapshot: MirrorCacheEntry? = null

                    override suspend fun getMirrors(): MirrorCacheEntry? =
                        mutex.withLock { snapshot }

                    override suspend fun putMirrors(entry: MirrorCacheEntry) {
                        mutex.withLock {
                            val previous = snapshot?.mirrors.orEmpty().associateBy { it.baseUrl }
                            val merged =
                                entry.mirrors.map { current ->
                                    val old = previous[current.baseUrl]
                                    if (old == null) current
                                    else
                                        current.copy(
                                            successCount = old.successCount,
                                            failureCount = old.failureCount,
                                            lastSuccessAt = old.lastSuccessAt,
                                            lastFailureAt = old.lastFailureAt,
                                        )
                                }
                            snapshot = entry.copy(mirrors = merged)
                        }
                    }

                    override suspend fun recordSuccess(baseUrl: String) {
                        record(baseUrl, success = true, clock = clock)
                    }

                    override suspend fun recordFailure(baseUrl: String) {
                        record(baseUrl, success = false, clock = clock)
                    }

                    private suspend fun record(baseUrl: String, success: Boolean, clock: Clock) {
                        mutex.withLock {
                            val current = snapshot ?: return
                            val now = clock.now()
                            snapshot =
                                current.copy(
                                    mirrors =
                                        current.mirrors.map { mirror ->
                                            if (mirror.baseUrl != baseUrl) mirror
                                            else if (success) {
                                                mirror.copy(
                                                    successCount = mirror.successCount + 1,
                                                    lastSuccessAt = now,
                                                )
                                            } else {
                                                mirror.copy(
                                                    failureCount = mirror.failureCount + 1,
                                                    lastFailureAt = now,
                                                )
                                            }
                                        }
                                )
                        }
                    }
                }
        }
    }

    suspend fun mirrors(forceRefresh: Boolean = false): List<MirrorEntry>

    suspend fun resolve(query: SciHubQuery, preferredMirror: String? = null): ResolvedPaper

    companion object {
        fun defaultMirrorProviders(): List<MirrorProvider> =
            listOf(
                YoviSunMirrorProvider(),
                Mirror41610Provider(),
                StaticMirrorProvider(listOf("https://sci-hub.st", "https://sci-hub.ru")),
            )

        fun defaultSciHubRetryPolicy(): SciHubRetryPolicy {
            val schedule =
                Schedule.retries(
                    maxRetries = 3,
                    baseDelay = 250.milliseconds,
                    maxDelay = 5.seconds,
                    jitterFactor = 0.2,
                )

            return RetryPolicy(schedule) { error, _ ->
                when (error) {
                    is SciHubError.Network -> RetryAction.Retry()
                    is SciHubError.Http -> {
                        when (error.status) {
                            429 -> RetryAction.Retry()
                            in 500..599 -> RetryAction.Retry()
                            else -> RetryAction.Stop
                        }
                    }
                    is SciHubError.MirrorDiscovery -> RetryAction.Retry()
                    is SciHubError.NoMirrorsDiscovered,
                    is SciHubError.NotFound,
                    is SciHubError.Captcha,
                    is SciHubError.Parse,
                    is SciHubError.AllMirrorsFailed -> RetryAction.Stop
                }
            }
        }
    }
}

class YoviSunMirrorProvider(private val sourceUrl: String = "http://tool.yovisun.com/scihub/") :
    RegexMirrorProvider(name = "yovisun", sourceUrl = sourceUrl)

class Mirror41610Provider(
    private val sourceUrl: String = "https://sci-hub.41610.org/sci-hub-mirrors"
) : RegexMirrorProvider(name = "41610", sourceUrl = sourceUrl)

class StaticMirrorProvider(private val urls: List<String>, override val name: String = "static") :
    MirrorProvider {
    override suspend fun discover(httpClient: HttpClient, config: SciHubApi.Config): List<Mirror> =
        urls.mapNotNull { raw -> normalizeMirrorBaseUrl(raw) }.distinct().map { Mirror(it, name) }
}

open class RegexMirrorProvider(override val name: String, private val sourceUrl: String) :
    MirrorProvider {
    override suspend fun discover(httpClient: HttpClient, config: SciHubApi.Config): List<Mirror> {
        val response =
            try {
                httpClient.get(sourceUrl) {
                    expectSuccess = false
                    applyEtiquette(config.etiquette)
                    applyTimeouts(config.timeouts)
                    accept(ContentType.Text.Html)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw SciHubError.MirrorDiscovery(name, SciHubError.Network(sourceUrl, t))
            }

        if (!response.status.isSuccess()) {
            throw SciHubError.MirrorDiscovery(
                name,
                SciHubError.Http(
                    sourceUrl,
                    response.status.value,
                    response.consumeRawBodyPrefixUtf8(2048),
                ),
            )
        }

        val html = response.bodyAsText()
        val mirrors = extractMirrorUrls(html).map { Mirror(it, name) }
        if (mirrors.isEmpty()) {
            throw SciHubError.MirrorDiscovery(
                name,
                SciHubError.Parse(sourceUrl, SciHubQuery.Title(name), collapseWhitespace(html)),
            )
        }
        return mirrors
    }
}

class KtorSciHubApi(
    private val httpClient: HttpClient,
    val config: SciHubApi.Config = SciHubApi.Config(),
    private val clock: Clock = Clock.System,
    private val cache: SciHubApi.Cache = SciHubApi.Cache.inMemory(clock),
) : SciHubApi {
    private val refreshMutex = Mutex()

    init {
        check(runCatching { httpClient.pluginOrNull(HttpTimeout) }.getOrNull() != null) {
            "HttpTimeout plugin must be installed on the provided HttpClient for per-request timeouts to work."
        }
    }

    override suspend fun mirrors(forceRefresh: Boolean): List<MirrorEntry> {
        val now = clock.now()
        if (!forceRefresh) {
            cache.getMirrors()?.let { cached ->
                if (now < cached.expiresAt) {
                    return sortMirrors(cached.mirrors)
                }
            }
        }

        return refreshMutex.withLock {
            val lockedNow = clock.now()
            val cached = cache.getMirrors()
            if (!forceRefresh && cached != null && lockedNow < cached.expiresAt) {
                return@withLock sortMirrors(cached.mirrors)
            }

            val started = lockedNow
            try {
                val discovered = discoverMirrors()
                val merged = mergeMirrors(discovered, cached?.mirrors.orEmpty())
                val entry = MirrorCacheEntry(merged, clock.now() + config.mirrorCacheTtl)
                cache.putMirrors(entry)
                config.onMirrorRefresh?.invoke(clock.now() - started, true, null)
                sortMirrors(entry.mirrors)
            } catch (error: SciHubError) {
                config.onMirrorRefresh?.invoke(clock.now() - started, false, error)
                if (cached != null && clock.now() < cached.expiresAt + config.staleWhileError) {
                    sortMirrors(cached.mirrors)
                } else {
                    throw error
                }
            }
        }
    }

    override suspend fun resolve(query: SciHubQuery, preferredMirror: String?): ResolvedPaper {
        val discovered = mirrors(forceRefresh = false)
        val candidates = orderedCandidates(discovered, preferredMirror)
        if (candidates.isEmpty()) {
            throw SciHubError.NoMirrorsDiscovered(emptyList())
        }

        val errors = mutableListOf<SciHubError>()
        for (mirror in candidates) {
            try {
                val resolved = resolveAgainstMirror(query, mirror.baseUrl)
                cache.recordSuccess(mirror.baseUrl)
                return resolved
            } catch (error: SciHubError) {
                cache.recordFailure(mirror.baseUrl)
                errors += error
            }
        }

        throw SciHubError.AllMirrorsFailed(query, errors)
    }

    private suspend fun discoverMirrors(): List<Mirror> {
        val policy = config.retryPolicy
        return if (policy == null) discoverMirrorsOnce()
        else runWithRetry(policy) { discoverMirrorsOnce() }
    }

    private suspend fun discoverMirrorsOnce(): List<Mirror> {
        val found = LinkedHashMap<String, Mirror>()
        val reasons = mutableListOf<String>()

        for (provider in config.mirrorProviders) {
            try {
                provider.discover(httpClient, config).forEach { mirror ->
                    if (mirror.baseUrl !in found) {
                        found[mirror.baseUrl] = mirror
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val error =
                    if (t is SciHubError) t else SciHubError.MirrorDiscovery(provider.name, t)
                reasons += (error.message ?: error::class.simpleName ?: "SciHubError")
            }
        }

        if (found.isEmpty()) {
            throw SciHubError.NoMirrorsDiscovered(reasons)
        }
        return found.values.toList()
    }

    private suspend fun resolveAgainstMirror(
        query: SciHubQuery,
        mirrorBaseUrl: String,
    ): ResolvedPaper {
        val lookupUrl = mirrorBaseUrl
        val response =
            try {
                httpClient.post(lookupUrl) {
                    expectSuccess = false
                    applyEtiquette(config.etiquette)
                    applyTimeouts(config.timeouts)
                    accept(ContentType.Text.Html)
                    setBody(
                        FormDataContent(
                            Parameters.build { append("request", query.requestValue()) }
                        )
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                throw SciHubError.Network(lookupUrl, t)
            }

        val finalResponse = followRedirectsIfNeeded(response, mirrorBaseUrl)
        val body = finalResponse.bodyAsText()
        if (!finalResponse.status.isSuccess()) {
            throw SciHubError.Http(lookupUrl, finalResponse.status.value, collapseWhitespace(body))
        }

        return when (val parsed = SciHubParser.parse(query, mirrorBaseUrl, body)) {
            is SciHubParser.ParseResult.Success -> parsed.paper
            is SciHubParser.ParseResult.NotFound ->
                throw SciHubError.NotFound(mirrorBaseUrl, query, parsed.sample)
            is SciHubParser.ParseResult.Captcha ->
                throw SciHubError.Captcha(mirrorBaseUrl, query, parsed.sample)
            is SciHubParser.ParseResult.Suspicious ->
                throw SciHubError.Parse(mirrorBaseUrl, query, parsed.sample)
        }
    }

    private suspend fun followRedirectsIfNeeded(
        response: io.ktor.client.statement.HttpResponse,
        mirrorBaseUrl: String,
        maxRedirects: Int = 5,
    ): io.ktor.client.statement.HttpResponse {
        var current = response
        repeat(maxRedirects) {
            val location = current.headers[HttpHeaders.Location] ?: return current
            val status = current.status.value
            if (status !in redirectStatusCodes) return current
            val absolute = normalizeRedirectUrl(mirrorBaseUrl, location)
            current =
                try {
                    httpClient.get(absolute) {
                        expectSuccess = false
                        applyEtiquette(config.etiquette)
                        applyTimeouts(config.timeouts)
                        accept(ContentType.Text.Html)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    throw SciHubError.Network(absolute, t)
                }
        }
        return current
    }

    private fun orderedCandidates(
        discovered: List<MirrorEntry>,
        preferredMirror: String?,
    ): List<MirrorEntry> {
        val preferred = preferredMirror?.let(::normalizeMirrorBaseUrl)
        if (preferred == null) return discovered

        val preferredEntry = discovered.find { it.baseUrl == preferred }
        return if (preferredEntry != null) {
            listOf(preferredEntry) + discovered.filterNot { it.baseUrl == preferred }
        } else {
            listOf(MirrorEntry(baseUrl = preferred, source = "preferred")) + discovered
        }
    }
}

private val redirectStatusCodes = setOf(301, 302, 303, 307, 308)

private fun normalizeRedirectUrl(mirrorBaseUrl: String, raw: String): String {
    val trimmed = decodeHtmlEntities(raw.trim())
    return when {
        trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("/") -> mirrorBaseUrl + trimmed
        else -> mirrorBaseUrl + "/" + trimmed.trimStart('/')
    }
}

object SciHubParser {
    sealed class ParseResult {
        data class Success(val paper: ResolvedPaper) : ParseResult()

        data class NotFound(val sample: String) : ParseResult()

        data class Captcha(val sample: String) : ParseResult()

        data class Suspicious(val sample: String) : ParseResult()
    }

    fun parse(query: SciHubQuery, mirrorBaseUrl: String, html: String): ParseResult {
        val sample = collapseWhitespace(html)
        val document = HtmlDocument.parseWithTextOnlySpans(html)
        val pdfRaw =
            extractMetaContents(document, "citation_pdf_url").firstOrNull()
                ?: document
                    .descendantsWithClass("download")
                    .firstOrNull()
                    ?.findFirstDescendant("a")
                    ?.attrString("href")

        if (pdfRaw == null) {
            val lower = html.lowercase()
            return when {
                "article not found" in lower -> ParseResult.NotFound(sample)
                "captcha" in lower || "капчу" in lower || "altcha" in lower ->
                    ParseResult.Captcha(sample)
                else -> ParseResult.Suspicious(sample)
            }
        }

        val title =
            extractMetaContents(document, "citation_title").firstOrNull() ?: extractTitle(document)
        val doi = extractMetaContents(document, "citation_doi").firstOrNull()
        val journal = extractMetaContents(document, "citation_journal_title").firstOrNull()
        val year = extractMetaContents(document, "citation_publication_date").firstOrNull()
        val authors = extractMetaContents(document, "citation_author")

        return ParseResult.Success(
            ResolvedPaper(
                query = query,
                mirror = mirrorBaseUrl,
                pdfUrl = normalizePdfUrl(mirrorBaseUrl, pdfRaw),
                title = title,
                doi = doi,
                journal = journal,
                year = year,
                authors = authors,
            )
        )
    }

    private fun extractTitle(document: HtmlDocument<*>): String? {
        val raw = document.findFirstDescendant("title")?.innerText() ?: return null
        val cleaned = decodeHtmlEntities(collapseWhitespace(raw))
        return cleaned.removePrefix("Sci-Hub.").substringBefore(" / ").trim().ifBlank { null }
    }

    private fun extractMetaContents(document: HtmlDocument<*>, name: String): List<String> {
        val wanted = name.lowercase()
        return document
            .descendants("meta")
            .filter { it.attrString("name")?.lowercase() == wanted }
            .mapNotNull { it.attrString("content")?.takeIf(String::isNotBlank) }
    }
}

private fun SciHubQuery.requestValue(): String = value.trim()

private fun mergeMirrors(discovered: List<Mirror>, previous: List<MirrorEntry>): List<MirrorEntry> {
    val previousByUrl = previous.associateBy { it.baseUrl }
    return discovered.map { mirror ->
        val old = previousByUrl[mirror.baseUrl]
        if (old == null) {
            MirrorEntry(baseUrl = mirror.baseUrl, source = mirror.source)
        } else {
            old.copy(source = mirror.source)
        }
    }
}

private fun sortMirrors(mirrors: List<MirrorEntry>): List<MirrorEntry> =
    mirrors.sortedWith(
        compareBy<MirrorEntry> {
                val total = it.successCount + it.failureCount
                if (total == 0) 0.0 else it.failureCount.toDouble() / total.toDouble()
            }
            .thenByDescending { it.successCount }
            .thenByDescending { it.baseUrl.startsWith("https://") }
            .thenBy { it.baseUrl }
    )

private fun extractMirrorUrls(html: String): List<String> {
    val preferred = LinkedHashMap<String, String>()
    val document = HtmlDocument.parseWithTextOnlySpans(html)
    for (anchor in document.descendants("a")) {
        val candidates = buildList {
            anchor.attrString("href")?.let(::add)
            val text = anchor.innerText().trim()
            if (
                text.startsWith("http://") || text.startsWith("https://") || text.startsWith("//")
            ) {
                add(text)
            }
        }
        for (candidate in candidates) {
            val normalized = normalizeMirrorBaseUrl(candidate) ?: continue
            if (normalized !in preferred) {
                preferred[normalized] = normalized
            }
        }
    }
    return preferred.values.toList()
}

private fun normalizeMirrorBaseUrl(raw: String): String? {
    var value = raw.trim()
    if (value.startsWith("//")) value = "https:$value"
    value = value.substringBefore('#').substringBefore('?').trimEnd('/')
    val match =
        Regex("""^(https?://)(?:www\.)?([A-Za-z0-9.-]+)$""").matchEntire(value) ?: return null
    val scheme = match.groupValues[1].lowercase()
    val host = match.groupValues[2].lowercase()
    if (!host.contains("sci-hub.")) return null
    return scheme + host
}

private fun normalizePdfUrl(mirrorBaseUrl: String, raw: String): String {
    val trimmed = decodeHtmlEntities(raw.trim())
    return when {
        trimmed.startsWith("https://") || trimmed.startsWith("http://") ->
            trimmed.substringBefore('#')
        trimmed.startsWith("//") -> "https:$trimmed".substringBefore('#')
        trimmed.startsWith("/") -> mirrorBaseUrl + trimmed.substringBefore('#')
        else -> mirrorBaseUrl + "/" + trimmed.substringBefore('#')
    }
}

private fun decodeHtmlEntities(text: String): String =
    text
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")

private fun collapseWhitespace(text: String): String =
    text.replace(Regex("""\s+"""), " ").trim().take(512)
