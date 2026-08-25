package fr.mangasorigines.kotatsu.data

import fr.mangasorigines.kotatsu.core.Manga
import fr.mangasorigines.kotatsu.core.MangaChapter
import fr.mangasorigines.kotatsu.core.MangaListFilter
import fr.mangasorigines.kotatsu.core.MangaListFilterCapabilities
import fr.mangasorigines.kotatsu.core.MangaPage
import fr.mangasorigines.kotatsu.core.MangaState
import fr.mangasorigines.kotatsu.core.MangaTag
import fr.mangasorigines.kotatsu.core.SortOrder
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Scrapes https://mangas-origines.fr (WordPress + the Madara manga theme, with a custom child
 * theme). This is a Kotlin port of the same scraping logic already validated live on a real
 * device by the sibling Aidoku source (`aidoku/mangasorigines`) — see that source's comments
 * for the original discovery notes; only the two spots called out below are new/unconfirmed.
 */
class MangasOriginesRepository {

    private val client = OkHttpClient()

    val filterCapabilities = MangaListFilterCapabilities(
        availableSortOrders = setOf(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.POPULARITY_WEEK),
        isSearchSupported = true,
    )

    val availableStates: Set<MangaState> = emptySet()

    fun getTags(): List<MangaTag> = emptyList() // Genre list page was never confirmed against the live site.

    fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            // Only the first page is available this way — offset-based paging into search
            // results was never confirmed against the live site (Cloudflare blocked direct
            // test requests from the dev machine every time).
            return if (offset == 0) searchCatalogue(filter.query) else emptyList()
        }
        // Kotatsu's protocol paginates by offset, not page number, but the site's AJAX
        // "load more" only understands page numbers. PAGE_SIZE is an assumed average batch
        // size to translate between the two — it may drift a little at page boundaries if a
        // real batch turns out bigger or smaller than assumed, but pagination still
        // terminates correctly (see fetchListingPage: it stops on the first empty page).
        val page = offset / PAGE_SIZE + 1
        val metaKey = when (order) {
            SortOrder.POPULARITY -> "_wp_manga_views"
            SortOrder.POPULARITY_WEEK -> "_wp_manga_week_views_value"
            else -> "_latest_update"
        }
        return fetchListingPage(metaKey, page)
    }

    fun getDetails(url: String): Manga {
        val doc = fetchDocument("$BASE_URL/oeuvre/$url/")

        val title = doc.selectFirst("h1.ori-sr-title")?.text().orEmpty()
        val cover = doc.selectFirst(".ori-sr-cover img")?.let(::imageUrl)
        val signatures = doc.select(".ori-sr-signature a")
        val author = signatures.getOrNull(0)?.text()?.takeIf { it.isNotBlank() }
        val description = doc.selectFirst(".ori-sr-syn-texte")?.text()

        val tags = doc.select("a.ori-sr-genre").mapNotNullTo(LinkedHashSet()) { el ->
            el.text().takeIf { it.isNotBlank() }?.let { MangaTag(key = it.lowercase(), title = it) }
        }

        val statusText = doc.selectFirst(".ori-sr-badge-statut")?.text()?.lowercase().orEmpty()
        val state = when {
            statusText.contains("en cours") -> MangaState.ONGOING
            statusText.contains("termin") -> MangaState.FINISHED
            statusText.contains("annul") -> MangaState.ABANDONED
            statusText.contains("pause") -> MangaState.PAUSED
            else -> null
        }

        val chapters = doc.select("div.ori-chl-row").mapIndexedNotNull { index, row ->
            val link = row.selectFirst("a.ori-chl-corps") ?: return@mapIndexedNotNull null
            val href = link.attr("href")
            val slug = href.trimEnd('/').substringAfterLast('/')
            if (slug.isEmpty()) return@mapIndexedNotNull null

            val name = row.selectFirst(".ori-chl-nom-long")?.text()
                ?: row.selectFirst(".ori-chl-nom")?.text()
            val number = row.attr("data-num").toFloatOrNull() ?: 0f
            val dateText = row.selectFirst(".ori-chl-date")?.text().orEmpty()
            val uploadDate = parseFrenchShortDate(dateText)

            MangaChapter(
                id = (index + 1).toLong(),
                name = name,
                number = number,
                url = href,
                uploadDate = uploadDate,
                branch = null,
            )
        }

        ChapterHolder.put(url, chapters)

        return Manga(
            id = 0L,
            title = title,
            url = url,
            publicUrl = "$BASE_URL/oeuvre/$url/",
            coverUrl = cover,
            tags = tags,
            state = state,
            author = author,
            description = description,
        )
    }

    /** The protocol asks for chapters separately from details, but the site's manga page
     * renders both in one request — [getDetails] populates [ChapterHolder] as a side effect,
     * so the common "details then chapters" call sequence only hits the network once. */
    fun getChapters(mangaUrl: String): List<MangaChapter> =
        ChapterHolder.get(mangaUrl) ?: run { getDetails(mangaUrl); ChapterHolder.get(mangaUrl).orEmpty() }

    /** `chapterUrl` here is the full href captured from the chapter row — reconstructing it
     * from manga-slug + chapter-slug parts was a bug hit and fixed in the Rust version;
     * this port uses the same full-href approach from the start. */
    fun getPages(chapterUrl: String): List<MangaPage> {
        val doc = fetchDocument(chapterUrl)
        return doc.select("div.page-break > img").mapIndexed { index, img ->
            MangaPage(id = index.toLong(), url = imageUrl(img) ?: "")
        }.filter { it.url.isNotEmpty() }
    }

    // --- internals ---

    private fun searchCatalogue(query: String): List<Manga> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = fetchDocument("$BASE_URL/catalogues/?s=$encoded")
        return doc.select("a.ori-cat-card").mapNotNull(::mangaFromCustomCard)
    }

    private fun mangaFromCustomCard(card: Element): Manga? {
        val href = card.attr("href")
        val slug = href.trimEnd('/').substringAfterLast('/')
        if (slug.isEmpty()) return null
        val title = card.selectFirst(".ori-card-title")?.text().orEmpty()
        val cover = card.selectFirst(".ori-card-cover img")?.let(::imageUrl)
        return Manga(id = 0L, title = title, url = slug, publicUrl = "$BASE_URL/oeuvre/$slug/", coverUrl = cover)
    }

    /**
     * The site's own "Voir plus" button has no page-number URL — it calls WordPress'
     * admin-ajax.php `madara_load_more` action instead (this is the Madara theme). Confirmed
     * live via device logs: the AJAX response does NOT use the child theme's custom markup,
     * it renders stock Madara `manga_archives_item_layout=big_thumbnail` markup instead —
     * `div.page-item-detail` cards, `h3.h5 > a` for title/link, plain `img` for cover.
     */
    private fun fetchListingPage(metaKey: String, page: Int): List<Manga> {
        val body = FormBody.Builder()
            .add("action", "madara_load_more")
            .add("page", (page - 1).toString())
            .add("template", "madara-core/content/content-archive")
            .add("vars[paged]", "1")
            .add("vars[orderby]", "meta_value_num")
            .add("vars[template]", "archive")
            .add("vars[sidebar]", "full")
            .add("vars[post_type]", "wp-manga")
            .add("vars[post_status]", "publish")
            .add("vars[meta_key]", metaKey)
            .add("vars[order]", "desc")
            .add("vars[meta_query][relation]", "OR")
            .add("vars[manga_archives_item_layout]", "big_thumbnail")
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/wp-admin/admin-ajax.php")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE_URL/catalogues/")
            .post(body)
            .build()

        val html = client.newCall(request).execute().use { it.body?.string() }.orEmpty()
        val doc = Jsoup.parse(html, BASE_URL)
        return doc.select("div.page-item-detail").mapNotNull { card ->
            val link = card.selectFirst("h3.h5 > a") ?: return@mapNotNull null
            val href = link.attr("href")
            val slug = href.trimEnd('/').substringAfterLast('/')
            if (slug.isEmpty()) return@mapNotNull null
            Manga(
                id = 0L,
                title = link.text(),
                url = slug,
                publicUrl = "$BASE_URL/oeuvre/$slug/",
                coverUrl = card.selectFirst("img")?.let(::imageUrl),
            )
        }
    }

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val html = client.newCall(request).execute().use { it.body?.string() }.orEmpty()
        return Jsoup.parse(html, url)
    }

    /** Reads the src/data-src/data-lazy-src/srcset attribute of a lazy-loaded `<img>`. */
    private fun imageUrl(el: Element): String? {
        for (attr in listOf("data-src", "data-lazy-src", "src", "srcset")) {
            val value = el.attr(attr).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun parseFrenchShortDate(text: String): Long = runCatching {
        SimpleDateFormat("dd/MM/yy", Locale.FRENCH).parse(text.trim())?.time ?: 0L
    }.getOrDefault(0L)

    private companion object {
        const val BASE_URL = "https://mangas-origines.fr"
        const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) GSA/300.0.598994205 Mobile/15E148 Safari/604"

        /** Assumed average items-per-AJAX-batch — see the comment on [getList]. */
        const val PAGE_SIZE = 20
    }
}

/**
 * The protocol splits manga details and chapter list into two separate cursor endpoints
 * ([MangasOriginesRepository.getDetails] / [MangasOriginesRepository.getChapters]), but the
 * site's manga page renders both in one request — this in-memory cache avoids a second HTTP
 * round trip for the (overwhelmingly common) case where Kotatsu asks for both right after
 * each other. Small and process-lifetime-scoped only; never persisted.
 */
private object ChapterHolder {
    private val cache = LinkedHashMap<String, List<MangaChapter>>()

    @Synchronized
    fun put(mangaUrl: String, chapters: List<MangaChapter>) {
        if (cache.size > 32) cache.remove(cache.keys.first())
        cache[mangaUrl] = chapters
    }

    @Synchronized
    fun get(mangaUrl: String): List<MangaChapter>? = cache[mangaUrl]
}
