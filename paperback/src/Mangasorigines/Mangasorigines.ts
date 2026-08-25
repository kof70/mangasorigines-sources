import {
    Chapter,
    ChapterDetails,
    ContentRating,
    HomeSection,
    PagedResults,
    PartialSourceManga,
    Request,
    RequestManager,
    Response,
    SearchRequest,
    Source,
    SourceInfo,
    SourceInterceptor,
    SourceManga,
    TagSection,
} from '@paperback/types'
import * as cheerio from 'cheerio'
import type { CheerioAPI, Cheerio, Element } from 'cheerio'

const BASE_URL = 'https://mangas-origines.fr'
const USER_AGENT =
    'Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) GSA/300.0.598994205 Mobile/15E148 Safari/604'

// Tendance/Populaire/Dernières mises à jour all go through the same
// WordPress admin-ajax.php `madara_load_more` call the site's own "Voir
// plus" button makes (this is the Madara WP theme). meta_key picks the sort.
const LISTINGS: Record<string, { title: string; metaKey: string }> = {
    tendance: { title: 'Tendance', metaKey: '_wp_manga_week_views_value' },
    populaire: { title: 'Populaire', metaKey: '_wp_manga_views' },
    latest: { title: 'Dernières mises à jour', metaKey: '_latest_update' },
}

export const MangasoriginesInfo: SourceInfo = {
    version: '1.0.0',
    name: 'Mangas Origines',
    icon: 'icon.png',
    author: 'kof70',
    description: 'Extension for mangas-origines.fr',
    contentRating: ContentRating.EVERYONE,
    websiteBaseURL: BASE_URL,
    language: 'fr',
}

class MangasOriginesInterceptor implements SourceInterceptor {
    async interceptRequest(request: Request): Promise<Request> {
        const headers = request.headers ?? {}
        if (!headers['User-Agent']) headers['User-Agent'] = USER_AGENT
        request.headers = headers
        return request
    }

    async interceptResponse(response: Response): Promise<Response> {
        return response
    }
}

// Reads the src/data-src/data-lazy-src/srcset attribute of a lazy-loaded <img>,
// same fallback order used by the Aidoku version of this source.
function getImageUrl($: CheerioAPI, el: Cheerio<Element>): string | undefined {
    for (const attr of ['data-src', 'data-lazy-src', 'src', 'srcset']) {
        const value = el.attr(attr)?.trim()
        if (value) return value
    }
    return undefined
}

function slugFromHref(href: string | undefined): string | undefined {
    if (!href) return undefined
    const trimmed = href.replace(/\/+$/, '')
    const slug = trimmed.split('/').pop()
    return slug ? slug : undefined
}

// dd/MM/yy, as used in the chapter list on the manga page.
function parseFrenchShortDate(text: string): Date | undefined {
    const match = /^(\d{2})\/(\d{2})\/(\d{2})$/.exec(text.trim())
    if (!match) return undefined
    const [, day, month, year] = match
    return new Date(2000 + Number(year), Number(month) - 1, Number(day))
}

export class Mangasorigines extends Source {
    requestManager: RequestManager = App.createRequestManager({
        requestsPerSecond: 4,
        requestTimeout: 20000,
        interceptor: new MangasOriginesInterceptor(),
    })

    private async requestHtml(url: string, options?: { method?: string; data?: string; headers?: Record<string, string> }): Promise<CheerioAPI> {
        const request = App.createRequest({
            url,
            method: options?.method ?? 'GET',
            headers: options?.headers,
            data: options?.data,
        })
        const response = await this.requestManager.schedule(request, 1)
        return cheerio.load(response.data ?? '')
    }

    // AJAX-loaded listing pages don't use the child theme's custom markup —
    // they render stock Madara `manga_archives_item_layout=big_thumbnail`
    // markup: `div.page-item-detail` cards, `h3.h5 > a` for title/link, and a
    // plain `img` for the cover (confirmed via live device logs).
    private async fetchListingPage(metaKey: string, page: number): Promise<PartialSourceManga[]> {
        const body = new URLSearchParams({
            action: 'madara_load_more',
            page: String(page - 1),
            template: 'madara-core/content/content-archive',
            'vars[paged]': '1',
            'vars[orderby]': 'meta_value_num',
            'vars[template]': 'archive',
            'vars[sidebar]': 'full',
            'vars[post_type]': 'wp-manga',
            'vars[post_status]': 'publish',
            'vars[meta_key]': metaKey,
            'vars[order]': 'desc',
            'vars[meta_query][relation]': 'OR',
            'vars[manga_archives_item_layout]': 'big_thumbnail',
        }).toString()

        const $ = await this.requestHtml(`${BASE_URL}/wp-admin/admin-ajax.php`, {
            method: 'POST',
            data: body,
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                Referer: `${BASE_URL}/catalogues/`,
            },
        })

        const tiles: PartialSourceManga[] = []
        $('div.page-item-detail').each((_, card) => {
            const $card = $(card)
            const link = $card.find('h3.h5 > a').first()
            const slug = slugFromHref(link.attr('href'))
            if (!slug) return
            tiles.push(
                App.createPartialSourceManga({
                    mangaId: slug,
                    title: link.text().trim(),
                    image: getImageUrl($, $card.find('img').first()) ?? '',
                }),
            )
        })
        return tiles
    }

    override async getHomePageSections(sectionCallback: (section: HomeSection) => void): Promise<void> {
        await Promise.all(
            Object.entries(LISTINGS).map(async ([id, { title }]) => {
                const items = await this.fetchListingPage(LISTINGS[id]!.metaKey, 1)
                sectionCallback(
                    App.createHomeSection({
                        id,
                        title,
                        type: 'singleRowNormal',
                        items,
                        containsMoreItems: items.length > 0,
                    }),
                )
            }),
        )
    }

    override async getViewMoreItems(homepageSectionId: string, metadata: { page: number } | undefined): Promise<PagedResults> {
        const listing = LISTINGS[homepageSectionId]
        if (!listing) return App.createPagedResults({ results: [] })

        const page = metadata?.page ?? 2 // page 1 is already shown on the home section
        const results = await this.fetchListingPage(listing.metaKey, page)
        return App.createPagedResults({
            results,
            metadata: results.length === 0 ? undefined : { page: page + 1 },
        })
    }

    async getMangaDetails(mangaId: string): Promise<SourceManga> {
        const $ = await this.requestHtml(`${BASE_URL}/oeuvre/${mangaId}/`)

        const title = $('h1.ori-sr-title').first().text().trim()
        const image = getImageUrl($, $('.ori-sr-cover img').first()) ?? ''

        const signature = $('.ori-sr-signature a')
        const author = signature.eq(0).text().trim()
        const artist = signature.eq(1).text().trim()

        const desc = $('.ori-sr-syn-texte').first().text().trim()

        const tags: string[] = []
        $('a.ori-sr-genre').each((_, el) => {
            const text = $(el).text().trim()
            if (text) tags.push(text)
        })

        const statusText = $('.ori-sr-badge-statut').first().text().trim().toLowerCase()
        let status = 'UNKNOWN'
        if (statusText.includes('en cours')) status = 'ONGOING'
        else if (statusText.includes('termin')) status = 'COMPLETED'
        else if (statusText.includes('annul')) status = 'CANCELLED'
        else if (statusText.includes('pause')) status = 'HIATUS'

        const tagSections: TagSection[] = [
            App.createTagSection({
                id: 'genres',
                label: 'Genres',
                tags: tags.map((t) => App.createTag({ id: t, label: t })),
            }),
        ]

        return App.createSourceManga({
            id: mangaId,
            mangaInfo: App.createMangaInfo({
                titles: [title],
                image,
                author,
                artist,
                desc,
                status,
                tags: tagSections,
            }),
        })
    }

    async getChapters(mangaId: string): Promise<Chapter[]> {
        const $ = await this.requestHtml(`${BASE_URL}/oeuvre/${mangaId}/`)

        const chapters: Chapter[] = []
        $('div.ori-chl-row').each((_, row) => {
            const $row = $(row)
            const link = $row.find('a.ori-chl-corps').first()
            const slug = slugFromHref(link.attr('href'))
            if (!slug) return

            const name =
                $row.find('.ori-chl-nom-long').first().text().trim() ||
                $row.find('.ori-chl-nom').first().text().trim() ||
                undefined

            const chapNumRaw = $row.attr('data-num')
            const chapNum = chapNumRaw ? Number.parseFloat(chapNumRaw) : NaN

            const dateText = $row.find('.ori-chl-date').first().text().trim()
            const time = parseFrenchShortDate(dateText)

            chapters.push(
                App.createChapter({
                    id: slug,
                    chapNum: Number.isFinite(chapNum) ? chapNum : 0,
                    langCode: 'fr',
                    name,
                    time,
                }),
            )
        })
        return chapters
    }

    async getChapterDetails(mangaId: string, chapterId: string): Promise<ChapterDetails> {
        const $ = await this.requestHtml(`${BASE_URL}/oeuvre/${mangaId}/${chapterId}/`)

        const pages: string[] = []
        $('div.page-break > img').each((_, img) => {
            const src = getImageUrl($, $(img))
            if (src) pages.push(src)
        })

        return App.createChapterDetails({
            id: chapterId,
            mangaId,
            pages,
        })
    }

    // The site's WordPress-native `?s=` search has not been confirmed against
    // the live site (Cloudflare blocks direct requests from outside a real
    // browser/app session for testing), and is not known to paginate — this
    // only ever returns a single page, unlike the AJAX-backed listings above.
    override async getSearchResults(query: SearchRequest, _metadata: unknown): Promise<PagedResults> {
        const title = query.title?.trim()
        if (!title) return App.createPagedResults({ results: [] })

        const $ = await this.requestHtml(`${BASE_URL}/catalogues/?s=${encodeURIComponent(title)}`)

        const results: PartialSourceManga[] = []
        $('a.ori-cat-card').each((_, card) => {
            const $card = $(card)
            const slug = slugFromHref($card.attr('href'))
            if (!slug) return
            results.push(
                App.createPartialSourceManga({
                    mangaId: slug,
                    title: $card.find('.ori-card-title').first().text().trim(),
                    image: getImageUrl($, $card.find('.ori-card-cover img').first()) ?? '',
                }),
            )
        })

        return App.createPagedResults({ results })
    }

    override getMangaShareUrl(mangaId: string): string {
        return `${BASE_URL}/oeuvre/${mangaId}/`
    }

    override async getCloudflareBypassRequestAsync(): Promise<Request> {
        return App.createRequest({
            url: BASE_URL,
            method: 'GET',
            headers: { 'User-Agent': USER_AGENT },
        })
    }
}
