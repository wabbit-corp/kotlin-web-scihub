package one.wabbit.web.scihub

import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class SciHubSpec {
    @Test
    fun test() {
        runBlocking {
            val httpClient = HttpClient {}
            println(SciHub.getAvailableSciHubUrls(httpClient))
        }
    }
}
