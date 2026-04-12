// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.web.scihub

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SciHubLiveSmokeTest {
    @Ignore("Manual live smoke test against current Sci-Hub mirrors.")
    @Test
    fun liveResolveDoi() = runBlocking {
        val client = HttpClient(CIO) { install(HttpTimeout) }
        client.use {
            val api = KtorSciHubApi(it)
            val result = api.resolve(SciHubQuery.Doi("10.1145/3375633"))
            println(
                "LIVE_RESOLVE title=${result.title} doi=${result.doi} mirror=${result.mirror} pdf=${result.pdfUrl}"
            )
            assertTrue(result.pdfUrl.startsWith("https://") || result.pdfUrl.startsWith("http://"))
        }
    }

    @Ignore("Manual live smoke test against current Sci-Hub mirrors.")
    @Test
    fun liveResolveUrl() = runBlocking {
        val client = HttpClient(CIO) { install(HttpTimeout) }
        client.use {
            val api = KtorSciHubApi(it)
            val result = api.resolve(SciHubQuery.Url("https://doi.org/10.1145/3375633"))
            println(
                "LIVE_RESOLVE_URL title=${result.title} doi=${result.doi} mirror=${result.mirror} pdf=${result.pdfUrl}"
            )
            assertTrue(result.pdfUrl.startsWith("https://") || result.pdfUrl.startsWith("http://"))
        }
    }
}
