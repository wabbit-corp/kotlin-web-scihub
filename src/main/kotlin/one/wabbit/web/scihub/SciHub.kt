package one.wabbit.web.scihub

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.jsoup.Jsoup

object SciHub {
    // https://github.com/zaytoun/scihub.py
    // https://github.com/markkvdb/pyscihub
    // https://github.com/star2dust/scihub.py
    // https://github.com/Tishacy/SciDownl

    suspend fun getAvailableSciHubUrls(httpClient: HttpClient): Set<String> {
        val urls = mutableSetOf<String>()
        val res = httpClient.get("https://sci-hub.ru/mirrors") {
            // empty
        }
        val jsoup = Jsoup.parse(res.bodyAsText())
        val aTags = jsoup.select("a[href]")
        for (a in aTags) {
            val href = a.attr("href")
            if (href.startsWith("//sci-hub.") || href.startsWith("//sci-hub.")) {
                urls.add("https:$href")
            }
        }
        return urls
    }
}
