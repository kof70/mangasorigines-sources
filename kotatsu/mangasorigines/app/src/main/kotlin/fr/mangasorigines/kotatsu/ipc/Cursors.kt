package fr.mangasorigines.kotatsu.ipc

import android.database.AbstractCursor
import fr.mangasorigines.kotatsu.core.Manga
import fr.mangasorigines.kotatsu.core.MangaChapter
import fr.mangasorigines.kotatsu.core.MangaListFilterCapabilities
import fr.mangasorigines.kotatsu.core.MangaPage
import fr.mangasorigines.kotatsu.core.MangaTag

/**
 * Cursor shapes of the Kotatsu external-source protocol.
 *
 * The reader looks columns up by name and fails the whole source with "incompatible plugin"
 * when one is missing, so the names below are fixed by the protocol and must match what
 * Kotatsu's ExternalPluginContentSource asks for.
 */
abstract class MapperCursor<T>(private val dataset: List<T>) : AbstractCursor() {

    private var index = 0

    final override fun getCount(): Int = dataset.size

    final override fun getString(column: Int): String? = value(column)?.toString()

    final override fun getShort(column: Int): Short = (value(column) as? Number)?.toShort() ?: 0

    final override fun getInt(column: Int): Int = (value(column) as? Number)?.toInt() ?: 0

    final override fun getLong(column: Int): Long = (value(column) as? Number)?.toLong() ?: 0L

    final override fun getFloat(column: Int): Float = (value(column) as? Number)?.toFloat() ?: 0f

    final override fun getDouble(column: Int): Double = (value(column) as? Number)?.toDouble() ?: 0.0

    final override fun isNull(column: Int): Boolean = value(column) == null

    final override fun getType(column: Int): Int = when (value(column)) {
        null -> FIELD_TYPE_NULL
        is String -> FIELD_TYPE_STRING
        is Double, is Float -> FIELD_TYPE_FLOAT
        is Long, is Int, is Short, is Byte -> FIELD_TYPE_INTEGER
        else -> FIELD_TYPE_STRING
    }

    final override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
        index = newPosition
        return true
    }

    private fun value(column: Int): Any? = dataset[index].columnValue(getColumnName(column))

    protected abstract fun T.columnValue(column: String): Any?
}

class MangaCursor(dataset: List<Manga>) : MapperCursor<Manga>(dataset) {

    override fun getColumnNames(): Array<String> = COLUMNS

    override fun Manga.columnValue(column: String): Any? = when (column) {
        "id" -> id
        "title" -> title
        "alt_title" -> altTitle
        "url" -> url
        "public_url" -> publicUrl
        "rating" -> rating
        "is_nsfw" -> if (isNsfw) 1 else 0
        "cover_url" -> coverUrl
        "tags" -> tags.joinToString(":") { it.key + "=" + it.title }
        "state" -> state?.name
        "author" -> author
        "large_cover_url" -> largeCoverUrl
        "description" -> description
        else -> null
    }

    private companion object {
        val COLUMNS = arrayOf(
            "id", "title", "alt_title", "url", "public_url", "rating", "is_nsfw",
            "cover_url", "tags", "state", "author", "large_cover_url", "description",
        )
    }
}

class ChapterCursor(dataset: List<MangaChapter>) : MapperCursor<MangaChapter>(dataset) {

    override fun getColumnNames(): Array<String> = COLUMNS

    override fun MangaChapter.columnValue(column: String): Any? = when (column) {
        "id" -> id
        "name" -> name
        "number" -> number
        "volume" -> volume
        "url" -> url
        "scanlator" -> scanlator
        "upload_date" -> uploadDate
        "branch" -> branch
        else -> null
    }

    private companion object {
        val COLUMNS = arrayOf("id", "name", "number", "volume", "url", "scanlator", "upload_date", "branch")
    }
}

class PageCursor(dataset: List<MangaPage>) : MapperCursor<MangaPage>(dataset) {

    override fun getColumnNames(): Array<String> = COLUMNS

    override fun MangaPage.columnValue(column: String): Any? = when (column) {
        "id" -> id
        "url" -> url
        "preview" -> preview
        else -> null
    }

    private companion object {
        val COLUMNS = arrayOf("id", "url", "preview")
    }
}

class TagCursor(dataset: List<MangaTag>) : MapperCursor<MangaTag>(dataset) {

    override fun getColumnNames(): Array<String> = COLUMNS

    override fun MangaTag.columnValue(column: String): Any? = when (column) {
        "key" -> key
        "title" -> title
        else -> null
    }

    private companion object {
        val COLUMNS = arrayOf("key", "title")
    }
}

class EnumCursor<E : Enum<E>>(dataset: List<E>) : MapperCursor<E>(dataset) {

    override fun getColumnNames(): Array<String> = COLUMNS

    override fun E.columnValue(column: String): Any? = if (column == "name") name else null

    private companion object {
        val COLUMNS = arrayOf("name")
    }
}

class StringCursor(dataset: List<String>) : MapperCursor<String>(dataset) {

    override fun getColumnNames(): Array<String> = COLUMNS

    override fun String.columnValue(column: String): Any? = if (column == "value") this else null

    private companion object {
        val COLUMNS = arrayOf("value")
    }
}

/** Column is "name" — used for locales and the enum-filter endpoints (states/content
 * ratings/content types/demographics), matching `fetchEnumSet`/`fetchLocales` in Kotatsu's
 * `ExternalPluginContentSource`, which both read `COLUMN_NAME`. */
class NameCursor(dataset: List<String>) : MapperCursor<String>(dataset) {

    override fun getColumnNames(): Array<String> = COLUMNS

    override fun String.columnValue(column: String): Any? = if (column == "name") this else null

    private companion object {
        val COLUMNS = arrayOf("name")
    }
}

class CapabilitiesCursor(
    capabilities: MangaListFilterCapabilities,
) : MapperCursor<MangaListFilterCapabilities>(listOf(capabilities)) {

    override fun getColumnNames(): Array<String> = COLUMNS

    override fun MangaListFilterCapabilities.columnValue(column: String): Any? = when (column) {
        "sort_orders" -> availableSortOrders.joinToString(",") { it.name }
        "multiple_tags" -> isMultipleTagsSupported.toInt()
        "tags_exclusion" -> isTagsExclusionSupported.toInt()
        "search" -> isSearchSupported.toInt()
        "search_with_filters" -> isSearchWithFiltersSupported.toInt()
        "year" -> isYearSupported.toInt()
        "year_range" -> isYearRangeSupported.toInt()
        "original_locale" -> isOriginalLocaleSupported.toInt()
        "author" -> isAuthorSearchSupported.toInt()
        else -> null
    }

    private fun Boolean.toInt() = if (this) 1 else 0

    private companion object {
        val COLUMNS = arrayOf(
            "sort_orders", "multiple_tags", "tags_exclusion", "search", "search_with_filters",
            "year", "year_range", "original_locale", "author",
        )
    }
}
