package fr.mangasorigines.kotatsu

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.util.Log
import fr.mangasorigines.kotatsu.core.MangaListFilter
import fr.mangasorigines.kotatsu.core.MangaState
import fr.mangasorigines.kotatsu.core.MangaTag
import fr.mangasorigines.kotatsu.core.SortOrder
import fr.mangasorigines.kotatsu.data.MangasOriginesRepository
import fr.mangasorigines.kotatsu.ipc.CapabilitiesCursor
import fr.mangasorigines.kotatsu.ipc.ChapterCursor
import fr.mangasorigines.kotatsu.ipc.EnumCursor
import fr.mangasorigines.kotatsu.ipc.MangaCursor
import fr.mangasorigines.kotatsu.ipc.NameCursor
import fr.mangasorigines.kotatsu.ipc.PageCursor
import fr.mangasorigines.kotatsu.ipc.StringCursor
import fr.mangasorigines.kotatsu.ipc.TagCursor

/**
 * Exposes mangas-origines.fr to Kotatsu (and compatible forks) as an external manga source.
 *
 * The reader discovers this provider through the `*.parser.PROVIDE_MANGA` intent filter in the
 * manifest and then talks to it purely through content URIs; the paths and query parameters
 * handled below are the whole protocol, as reverse-engineered from
 * KotatsuApp/Kotatsu's `ExternalPluginContentSource.kt`.
 */
class MangasOriginesContentProvider : ContentProvider() {

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)
    private val repository = MangasOriginesRepository()

    override fun onCreate(): Boolean {
        val authority = (context?.packageName ?: return false) + AUTHORITY_SUFFIX
        uriMatcher.addURI(authority, "manga", URI_MANGA_LIST)
        uriMatcher.addURI(authority, "manga/chapters/*", URI_CHAPTERS)
        uriMatcher.addURI(authority, "manga/pages/*", URI_PAGE_URL)
        uriMatcher.addURI(authority, "manga/*", URI_MANGA_DETAILS)
        uriMatcher.addURI(authority, "chapters/*", URI_PAGES)
        uriMatcher.addURI(authority, "capabilities", URI_CAPABILITIES)
        uriMatcher.addURI(authority, "filter/tags", URI_TAGS)
        uriMatcher.addURI(authority, "filter/states", URI_STATES)
        uriMatcher.addURI(authority, "filter/content_ratings", URI_CONTENT_RATINGS)
        uriMatcher.addURI(authority, "filter/content_types", URI_CONTENT_TYPES)
        uriMatcher.addURI(authority, "filter/demographics", URI_DEMOGRAPHICS)
        uriMatcher.addURI(authority, "filter/locales", URI_LOCALES)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = runSafely(uri) {
        when (uriMatcher.match(uri)) {
            URI_MANGA_LIST -> MangaCursor(
                repository.getList(
                    offset = uri.getQueryParameter("offset")?.toIntOrNull() ?: 0,
                    order = resolveSortOrder(sortOrder),
                    filter = parseFilter(uri),
                ),
            )

            URI_MANGA_DETAILS -> MangaCursor(listOf(repository.getDetails(uri.requireLastSegment())))

            URI_CHAPTERS -> ChapterCursor(repository.getChapters(uri.requireLastSegment()))

            // The chapter's own url IS the request url (see MangasOriginesRepository.getPages) —
            // it was captured in full from the chapter list and is passed straight through here.
            URI_PAGES -> PageCursor(repository.getPages(uri.requireLastSegment()))

            URI_TAGS -> TagCursor(repository.getTags())

            URI_CAPABILITIES -> CapabilitiesCursor(repository.filterCapabilities)

            URI_STATES -> EnumCursor(repository.availableStates.toList())

            URI_CONTENT_RATINGS -> NameCursor(emptyList()) // not applicable to this source
            URI_CONTENT_TYPES -> NameCursor(emptyList())
            URI_DEMOGRAPHICS -> NameCursor(emptyList())
            URI_LOCALES -> NameCursor(listOf("fr"))

            // Every image/page URL this source hands out is already absolute, so there's
            // nothing to resolve.
            URI_PAGE_URL -> uri.getQueryParameter("url")?.let { StringCursor(listOf(it)) }

            else -> null
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    /** Only a handful of exception types survive a Binder round trip; anything else reaches
     * the reader as a bare "plugin died". Everything is funnelled into IllegalStateException,
     * whose message Kotatsu shows to the user as-is. */
    private inline fun runSafely(uri: Uri, block: () -> Cursor?): Cursor? = try {
        block()
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Failed to answer $uri", e)
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "Failed to answer $uri", e)
        throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
    }

    private fun Uri.requireLastSegment(): String = lastPathSegment
        ?: throw IllegalStateException("Malformed request: $this")

    private fun resolveSortOrder(name: String?): SortOrder =
        SortOrder.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: repository.filterCapabilities.availableSortOrders.first()

    private fun parseFilter(uri: Uri): MangaListFilter = MangaListFilter(
        query = uri.getQueryParameter("query")?.takeIf { it.isNotEmpty() },
        states = uri.getQueryParameters("state").mapNotNullTo(LinkedHashSet()) { name ->
            MangaState.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        },
        author = uri.getQueryParameter("author")?.takeIf { it.isNotEmpty() },
    )

    private companion object {
        const val TAG = "MangasOriginesProvider"
        const val AUTHORITY_SUFFIX = ".provider"

        const val URI_MANGA_LIST = 1
        const val URI_TAGS = 2
        const val URI_MANGA_DETAILS = 3
        const val URI_CHAPTERS = 4
        const val URI_PAGES = 5
        const val URI_CAPABILITIES = 6
        const val URI_STATES = 7
        const val URI_CONTENT_RATINGS = 8
        const val URI_CONTENT_TYPES = 9
        const val URI_DEMOGRAPHICS = 10
        const val URI_LOCALES = 11
        const val URI_PAGE_URL = 12
    }
}
