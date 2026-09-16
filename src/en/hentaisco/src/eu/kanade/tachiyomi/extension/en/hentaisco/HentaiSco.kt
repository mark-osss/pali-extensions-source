package eu.kanade.tachiyomi.extension.en.hentaisco

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.randomua.UserAgentType
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Source
abstract class HentaiSco : KeiSource() {
    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2, 1.seconds) { it.host == baseUrlHost }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = setRandomUserAgent(UserAgentType.MOBILE)

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(mangaListUrl(page, SORT_VIEWS))

    // =============================== Latest ==============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(mangaListUrl(page, null))

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank() || page > 1) {
            return MangasPage(emptyList(), false)
        }
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return parseMangaList(url.toString())
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // ============================== Details ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val mangaPath = mangaPathFromUrl(url) ?: return null
        return client.get("$baseUrl$mangaPath").asJsoup().toSManga(mangaPath)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}").asJsoup()
        return SMangaUpdate(
            document.toSManga(manga.url) ?: manga,
            document.chapterList(),
        )
    }

    // ============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$baseUrl${chapter.url}").asJsoup()
        .select("img.reader-page")
        .mapIndexed { index, img -> Page(index, imageUrl = img.absUrl("src")) }

    // ============================== Helpers ==============================

    private fun mangaListUrl(page: Int, sort: String?): String {
        val pagePart = if (page <= 1) "" else "/page/$page"
        val sortPart = sort?.let { "?m_orderby=$it" } ?: ""
        return "$baseUrl/hentai-list$pagePart/$sortPart"
    }

    private suspend fun parseMangaList(url: String): MangasPage {
        val document = client.get(url).asJsoup()
        val mangas = document.select("article.cover-card").mapNotNull { it.toSMangaCard() }
        val hasNextPage = document.selectFirst("a[href*='/page/${pageFromUrl(url) + 1}/']") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun pageFromUrl(url: String): Int {
        val segments = url.toHttpUrl().pathSegments
        val index = segments.indexOf("page")
        return if (index != -1 && index + 1 < segments.size) {
            segments[index + 1].toIntOrNull() ?: 1
        } else {
            1
        }
    }

    private fun Element.toSMangaCard(): SManga? {
        val link = selectFirst("a[href^='/hentai/']") ?: return null
        val title = selectFirst(".cover-title")?.text()?.ifEmpty { return null } ?: return null
        return SManga.create().apply {
            url = link.attr("href")
            this.title = title
            thumbnail_url = selectFirst("img[src*='covers']")?.absUrl("src")
        }
    }

    private fun Document.toSManga(urlPath: String): SManga? {
        val title = selectFirst("h1") ?: return null
        return SManga.create().apply {
            url = urlPath
            this.title = title.text()
            thumbnail_url = selectFirst("img[src*='covers']")?.absUrl("src")
            description = selectFirst("div.prose-hs")?.text()
            author = fieldValue("Author") ?: fieldValue("Artist")
            genre = select("a[href*='/genre/']")
                .mapNotNull { it.text().ifEmpty { null } }
                .distinct()
                .joinToString()
                .ifBlank { null }
            status = parseStatus(selectFirst("p.text-xs.font-medium")?.text())
        }
    }

    private fun Document.fieldValue(label: String): String? = selectFirst("dt:contains($label)")?.nextElementSibling()?.text()?.ifEmpty { null }

    private fun parseStatus(text: String?): Int {
        val lower = text?.lowercase() ?: return SManga.UNKNOWN
        return when {
            "completed" in lower -> SManga.COMPLETED
            "ongoing" in lower -> SManga.ONGOING
            "hiatus" in lower -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.chapterList(): List<SChapter> = select("li[data-chapter-number]").mapNotNull { item ->
        val link = item.selectFirst("a[href^='/hentai/']") ?: return@mapNotNull null
        val number = item.attr("data-chapter-number").toFloatOrNull() ?: return@mapNotNull null
        SChapter.create().apply {
            url = link.attr("href")
            name = "Chapter ${number.toString().removeSuffix(".0")}"
            chapter_number = number
            date_upload = link.selectFirst("time")?.attr("datetime")?.let(Instant::tryParse) ?: 0L
        }
    }

    private fun mangaPathFromUrl(url: HttpUrl): String? {
        val segments = url.pathSegments
        if (segments.size >= 2 && segments.first() == "hentai") {
            return "/hentai/${segments[1]}/"
        }
        return null
    }

    companion object {
        private const val SORT_VIEWS = "views"
    }
}
