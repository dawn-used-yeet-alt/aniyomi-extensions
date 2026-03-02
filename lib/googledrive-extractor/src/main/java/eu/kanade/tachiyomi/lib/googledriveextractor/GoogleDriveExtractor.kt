package eu.kanade.tachiyomi.lib.googledriveextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class GoogleDriveExtractor(private val client: OkHttpClient, private val headers: Headers) {

    companion object {
        private const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    }

    private val cookieList = client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl())

    fun videosFromUrl(itemId: String, videoName: String = "Video"): List<Video> {
        val url = "https://drive.usercontent.google.com/download?id=$itemId"
        val docHeaders = headers.newBuilder().apply {
            add("Accept", ACCEPT)
            add("Cookie", cookieList.toStr())
        }.build()

        val docResp = client.newCall(
            GET(url, docHeaders)
        ).execute()

        try {
            val isHtml = docResp.peekBody(15).string().equals("<!DOCTYPE html>", true)
            var itemSize = ""

            val finalUrl = if (isHtml) {
                val document = docResp.asJsoup()
                itemSize = document.selectFirst("span.uc-name-size")
                    ?.let { " ${it.ownText().trim()} " }
                    ?: ""

                val videoUrl = url.toHttpUrl().newBuilder().apply {
                    document.select("input[type=hidden]").forEach {
                        setQueryParameter(it.attr("name"), it.attr("value"))
                    }
                }.build().toString()
                
                val finalResp = client.newCall(GET(videoUrl, docHeaders)).execute()
                val resolvedUrl = finalResp.request.url.toString()
                finalResp.close()
                resolvedUrl
            } else {
                docResp.request.url.toString()
            }

            val videoHeaders = headers.newBuilder()
                .removeAll("Cookie")
                .removeAll("Host")
                .build()

            return listOf(
                Video(finalUrl, videoName + itemSize, finalUrl, videoHeaders)
            )
        } finally {
            docResp.close()
        }
    }

    private fun List<Cookie>.toStr(): String {
        return this.joinToString("; ") { "${it.name}=${it.value}" }
    }
}
