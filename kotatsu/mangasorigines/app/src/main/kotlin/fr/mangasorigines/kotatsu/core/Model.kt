package fr.mangasorigines.kotatsu.core

/**
 * Mirrors the columns Kotatsu's external-source protocol expects from each content-provider
 * cursor. Field names here map 1:1 to what [fr.mangasorigines.kotatsu.ipc] cursors expose —
 * see ExternalPluginContentSource in the KotatsuApp/Kotatsu source for the authoritative list.
 */

/**
 * Names must match constants of `org.koitharu.kotatsu.parsers.model.SortOrder` in the real
 * Kotatsu app (see KotatsuApp/kotatsu-parsers) — Kotatsu resolves the sort order we advertise
 * in [fr.mangasorigines.kotatsu.ipc.CapabilitiesCursor] purely by name, so an invented name
 * would just silently vanish from the picker.
 */
enum class SortOrder {
    /** Dernières mises à jour — `_latest_update` */
    UPDATED,

    /** Populaire (vues totales) — `_wp_manga_views` */
    POPULARITY,

    /** Tendance (vues de la semaine) — `_wp_manga_week_views_value` */
    POPULARITY_WEEK,
}

enum class MangaState {
    ONGOING, FINISHED, ABANDONED, PAUSED
}

data class MangaTag(
    val key: String,
    val title: String,
)

data class Manga(
    val id: Long,
    val title: String,
    val altTitle: String? = null,
    val url: String,
    val publicUrl: String,
    val rating: Float = -1f,
    val isNsfw: Boolean = false,
    val coverUrl: String? = null,
    val tags: Set<MangaTag> = emptySet(),
    val state: MangaState? = null,
    val author: String? = null,
    val largeCoverUrl: String? = null,
    val description: String? = null,
)

data class MangaChapter(
    val id: Long,
    val name: String?,
    val number: Float,
    val volume: Int = 0,
    val url: String,
    val scanlator: String? = null,
    val uploadDate: Long = 0L,
    val branch: String? = null,
)

data class MangaPage(
    val id: Long,
    val url: String,
    val preview: String? = null,
)

data class MangaListFilterCapabilities(
    val availableSortOrders: Set<SortOrder> = setOf(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.POPULARITY_WEEK),
    val isMultipleTagsSupported: Boolean = false,
    val isTagsExclusionSupported: Boolean = false,
    val isSearchSupported: Boolean = true,
    val isSearchWithFiltersSupported: Boolean = false,
    val isYearSupported: Boolean = false,
    val isYearRangeSupported: Boolean = false,
    val isOriginalLocaleSupported: Boolean = false,
    val isAuthorSearchSupported: Boolean = false,
)

data class MangaListFilter(
    val query: String? = null,
    val tags: Set<MangaTag> = emptySet(),
    val tagsExclude: Set<MangaTag> = emptySet(),
    val states: Set<MangaState> = emptySet(),
    val author: String? = null,
)
