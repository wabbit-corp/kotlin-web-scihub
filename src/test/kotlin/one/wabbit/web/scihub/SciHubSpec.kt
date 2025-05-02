package one.wabbit.web.scihub

import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class SciHubSpec {
    @Test fun test() {
        runBlocking {
            val httpClient = HttpClient() { }
            println(SciHub.getAvailableSciHubUrls(httpClient))
        }
    }
}
