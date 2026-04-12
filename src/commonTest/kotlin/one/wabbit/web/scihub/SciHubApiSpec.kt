// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.web.scihub

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SciHubApiSpec {
    @Test
    fun `discovers mirrors from yovisun-style html`() = runBlocking {
        val provider = YoviSunMirrorProvider("https://mirror-source.test")
        val client = mockClient { request ->
            assertEquals("https://mirror-source.test", request.url.toString())
            respond(
                content =
                    """
                    <html><body>
                      <a href="http://sci-hub.se">one</a>
                      <a href="https://www.sci-hub.st">two</a>
                      <a href="https://sci-hub.ru">three</a>
                    </body></html>
                    """
                        .trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
            )
        }

        val api =
            KtorSciHubApi(
                client,
                config = SciHubApi.Config(mirrorProviders = listOf(provider), retryPolicy = null),
            )

        val mirrors = api.mirrors(forceRefresh = true)
        assertEquals(
            listOf("http://sci-hub.se", "https://sci-hub.ru", "https://sci-hub.st"),
            mirrors.map { it.baseUrl }.sorted(),
        )
    }

    @Test
    fun `resolves paper using citation meta tags`() = runBlocking {
        val provider = StaticMirrorProvider(listOf("https://sci-hub.st"))
        val client = mockClient { request ->
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content =
                    """
                    <html>
                      <head>
                        <title>Sci-Hub. Visualizing Distributed System Executions / ACM Transactions on Software Engineering and Methodology, 2020</title>
                        <meta name="citation_title" content="Visualizing Distributed System Executions">
                        <meta name="citation_author" content="Beschastnikh, Ivan">
                        <meta name="citation_author" content="Liu, Perry">
                        <meta name="citation_doi" content="10.1145/3375633">
                        <meta name="citation_journal_title" content="ACM Transactions on Software Engineering and Methodology">
                        <meta name="citation_publication_date" content="2020">
                        <meta name="citation_pdf_url" content="/storage/zero/8637/example.pdf">
                      </head>
                    </html>
                    """
                        .trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
            )
        }

        val api =
            KtorSciHubApi(
                client,
                config = SciHubApi.Config(mirrorProviders = listOf(provider), retryPolicy = null),
            )
        val result = api.resolve(SciHubQuery.Doi("10.1145/3375633"))

        assertEquals("https://sci-hub.st", result.mirror)
        assertEquals("https://sci-hub.st/storage/zero/8637/example.pdf", result.pdfUrl)
        assertEquals("Visualizing Distributed System Executions", result.title)
        assertEquals("10.1145/3375633", result.doi)
        assertEquals(listOf("Beschastnikh, Ivan", "Liu, Perry"), result.authors)
    }

    @Test
    fun `falls back to next mirror when first one fails`() = runBlocking {
        val provider = StaticMirrorProvider(listOf("https://sci-hub.bad", "https://sci-hub.ru"))
        val client = mockClient { request ->
            when (request.url.toString()) {
                "https://sci-hub.bad" ->
                    respond(
                        content = "article not found",
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                "https://sci-hub.ru" ->
                    respond(
                        content =
                            """
                            <html><head>
                              <meta name="citation_title" content="Recovered paper">
                              <meta name="citation_pdf_url" content="/storage/tail/recovered.pdf">
                            </head></html>
                            """
                                .trimIndent(),
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                else -> error("Unexpected URL: ${request.url}")
            }
        }

        val api =
            KtorSciHubApi(
                client,
                config = SciHubApi.Config(mirrorProviders = listOf(provider), retryPolicy = null),
            )
        val result = api.resolve(SciHubQuery.Title("Recovered paper"))

        assertEquals("https://sci-hub.ru", result.mirror)
        assertEquals("https://sci-hub.ru/storage/tail/recovered.pdf", result.pdfUrl)
    }

    @Test
    fun `uses cached mirrors without re-fetching provider`() = runBlocking {
        var calls = 0
        val provider =
            object : MirrorProvider {
                override val name: String = "test"

                override suspend fun discover(
                    httpClient: HttpClient,
                    config: SciHubApi.Config,
                ): List<Mirror> {
                    calls += 1
                    return listOf(Mirror("https://sci-hub.ru", name))
                }
            }
        val client = mockClient { error("HTTP should not be called") }
        val api =
            KtorSciHubApi(
                client,
                config = SciHubApi.Config(mirrorProviders = listOf(provider), retryPolicy = null),
            )

        val first = api.mirrors(forceRefresh = true)
        val second = api.mirrors(forceRefresh = false)

        assertEquals(1, calls)
        assertEquals(first, second)
        assertTrue(second.isNotEmpty())
    }

    private fun mockClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine { request -> handler(request) }) { install(HttpTimeout) }
}
