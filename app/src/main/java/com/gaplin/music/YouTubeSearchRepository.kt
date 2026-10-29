package com.gaplin.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class RemoteMediaKind { VIDEO, PLAYLIST, CHANNEL }

data class RemoteMediaResult(
    val id: String,
    val title: String,
    val author: String,
    val duration: String,
    val thumbnailUrl: String?,
    val url: String,
    val kind: RemoteMediaKind = RemoteMediaKind.VIDEO,
    val subtitle: String = ""
)

object YouTubeSearchRepository {
    suspend fun search(query: String): List<RemoteMediaResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotEmpty()) { "Digite o nome de uma música, artista ou vídeo." }

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val connection = (URL("https://www.youtube.com/results?search_query=$encoded&hl=pt-BR&gl=BR")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.7")
            setRequestProperty("Referer", "https://www.youtube.com/")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("O YouTube não respondeu à pesquisa (HTTP $status). Tente novamente.")
            }
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val initialData = extractInitialData(html)
            val results = mutableListOf<RemoteMediaResult>()
            val seen = hashSetOf<String>()
            collectRenderers(initialData, results, seen)
            results
        } finally {
            connection.disconnect()
        }
    }

    private fun extractInitialData(html: String): JSONObject {
        // YouTube can inject unrelated inline scripts between its bootstrap data
        // and other JSON blocks. Only parse an object immediately following an
        // actual ytInitialData assignment.
        val assignment = Regex(
            """(?:var\\s+ytInitialData|let\\s+ytInitialData|const\\s+ytInitialData|window\\[\"ytInitialData\"\\]|window\\['ytInitialData'\\]|ytInitialData)\\s*="""
        )
        val matches = assignment.findAll(html).toList()
        for (match in matches) {
            var jsonStart = match.range.last + 1
            while (jsonStart < html.length && html[jsonStart].isWhitespace()) jsonStart++
            if (jsonStart >= html.length || html[jsonStart] != '{') continue

            var depth = 0
            var inString = false
            var escaped = false
            for (index in jsonStart until html.length) {
                val char = html[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        char == '\\\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                } else {
                    when (char) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                val parsed = runCatching {
                                    JSONObject(html.substring(jsonStart, index + 1))
                                }.getOrNull()
                                if (parsed != null) return parsed
                                break
                            }
                        }
                    }
                }
            }
        }

        val lower = html.lowercase()
        when {
            "consent.youtube.com" in lower || "before you continue to youtube" in lower ->
                throw IllegalStateException("O YouTube pediu confirmação de consentimento. Tente novamente mais tarde.")
            "unusual traffic" in lower || "automated requests" in lower ->
                throw IllegalStateException("O YouTube limitou temporariamente esta pesquisa. Aguarde um pouco e tente novamente.")
            "sign in to confirm" in lower ->
                throw IllegalStateException("O YouTube exige uma verificação para mostrar estes resultados.")
            else ->
                throw IllegalStateException("O YouTube não entregou resultados em um formato reconhecido. Tente novamente.")
        }
    }

    private fun collectRenderers(
        value: Any?,
        output: MutableList<RemoteMediaResult>,
        seen: MutableSet<String>
    ) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("videoRenderer")?.let { renderer ->
                    parseVideo(renderer)?.let { item ->
                        if (seen.add(item.id)) output += item
                    }
                }
                value.optJSONObject("playlistRenderer")?.let { renderer ->
                    parsePlaylist(renderer)?.let { item ->
                        if (seen.add("playlist:${item.id}")) output += item
                    }
                }
                value.optJSONObject("channelRenderer")?.let { renderer ->
                    parseChannel(renderer)?.let { item ->
                        if (seen.add("channel:${item.id}")) output += item
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) collectRenderers(value.opt(keys.next()), output, seen)
            }
            is JSONArray -> {
                for (index in 0 until value.length()) collectRenderers(value.opt(index), output, seen)
            }
        }
    }

    private fun parseVideo(renderer: JSONObject): RemoteMediaResult? {
        val id = renderer.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = text(renderer.optJSONObject("title")).ifBlank { "Vídeo do YouTube" }
        val author = text(renderer.optJSONObject("ownerText"))
            .ifBlank { text(renderer.optJSONObject("shortBylineText")) }
            .ifBlank { text(renderer.optJSONObject("longBylineText")) }
            .ifBlank { "YouTube" }
        val duration = text(renderer.optJSONObject("lengthText"))
            .ifBlank { renderer.optJSONObject("thumbnailOverlays")
                ?.let { findTextWithKey(it, "text") }.orEmpty() }
        return RemoteMediaResult(
            id = id,
            title = title,
            author = author,
            duration = duration,
            thumbnailUrl = thumbnail(renderer),
            url = "https://www.youtube.com/watch?v=$id",
            kind = RemoteMediaKind.VIDEO,
            subtitle = text(renderer.optJSONObject("viewCountText"))
        )
    }

    private fun parsePlaylist(renderer: JSONObject): RemoteMediaResult? {
        val id = renderer.optString("playlistId").takeIf { it.isNotBlank() } ?: return null
        val title = text(renderer.optJSONObject("title")).ifBlank { "Playlist do YouTube" }
        val author = text(renderer.optJSONObject("shortBylineText"))
            .ifBlank { text(renderer.optJSONObject("longBylineText")) }
            .ifBlank { "YouTube" }
        val count = text(renderer.optJSONObject("videoCountText"))
        return RemoteMediaResult(
            id = id,
            title = title,
            author = author,
            duration = "",
            thumbnailUrl = thumbnail(renderer),
            url = "https://www.youtube.com/playlist?list=$id",
            kind = RemoteMediaKind.PLAYLIST,
            subtitle = count
        )
    }

    private fun parseChannel(renderer: JSONObject): RemoteMediaResult? {
        val id = renderer.optString("channelId").takeIf { it.isNotBlank() } ?: return null
        val title = text(renderer.optJSONObject("title")).ifBlank { return null }
        return RemoteMediaResult(
            id = id,
            title = title,
            author = "Canal do YouTube",
            duration = "",
            thumbnailUrl = thumbnail(renderer),
            url = "https://www.youtube.com/channel/$id",
            kind = RemoteMediaKind.CHANNEL,
            subtitle = text(renderer.optJSONObject("videoCountText"))
        )
    }

    private fun text(value: JSONObject?): String {
        if (value == null) return ""
        value.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = value.optJSONArray("runs") ?: return ""
        return buildString {
            for (index in 0 until runs.length()) {
                append(runs.optJSONObject(index)?.optString("text").orEmpty())
            }
        }.trim()
    }

    private fun thumbnail(renderer: JSONObject): String? {
        val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return null
        for (index in thumbnails.length() - 1 downTo 0) {
            val url = thumbnails.optJSONObject(index)?.optString("url").orEmpty()
            if (url.isNotBlank()) return url.replace("&amp;", "&").let {
                if (it.startsWith("//")) "https:$it" else it
            }
        }
        return null
    }

    private fun findTextWithKey(value: JSONObject, key: String): String {
        val direct = value.optString(key)
        if (direct.isNotBlank()) return direct
        val keys = value.keys()
        while (keys.hasNext()) {
            val child = value.opt(keys.next())
            when (child) {
                is JSONObject -> findTextWithKey(child, key).takeIf { it.isNotBlank() }?.let { return it }
                is JSONArray -> for (index in 0 until child.length()) {
                    val item = child.optJSONObject(index) ?: continue
                    findTextWithKey(item, key).takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return ""
    }
}
