#![no_std]
use aidoku::{
	alloc::{vec, String, Vec},
	imports::{defaults::defaults_get, html::{Document, Element}, net::Request, std::parse_date},
	prelude::*,
	Chapter, ContentRating, DeepLinkHandler, DeepLinkResult, FilterValue, Home, HomeComponent,
	HomeComponentValue, HomeLayout, ImageRequestProvider, Listing, ListingProvider,
	Manga, MangaPageResult, MangaStatus, Page, PageContent, PageContext, Result, Source, Viewer,
};

const BASE_URL: &str = "https://mangas-origines.fr";
const USER_AGENT: &str = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) GSA/300.0.598994205 Mobile/15E148 Safari/604";

struct MangasOrigines;

fn urlencode(string: &str) -> String {
	let mut result: Vec<u8> = Vec::with_capacity(string.len() * 3);
	let hex = "0123456789abcdef".as_bytes();
	for &byte in string.as_bytes() {
		if byte.is_ascii_alphanumeric() {
			result.push(byte);
		} else {
			result.push(b'%');
			result.push(hex[(byte >> 4) as usize]);
			result.push(hex[(byte & 15) as usize]);
		}
	}
	String::from_utf8(result).unwrap_or_default()
}

// Reads the src/data-src/data-lazy-src/srcset attribute of a lazy-loaded <img>.
fn get_image_url(el: &Element) -> Option<String> {
	for attr in ["data-src", "data-lazy-src", "src", "srcset"] {
		if let Some(value) = el.attr(attr) {
			let trimmed = value.trim();
			if !trimmed.is_empty() {
				return Some(String::from(trimmed));
			}
		}
	}
	None
}

// Confirmed markup: `a.ori-card.ori-cat-card` cards inside `#ori-cat-grid`,
// each with a cover <img>, `.ori-card-title` and `.ori-card-sub` (genre · format).
fn extract_catalogue_entries(doc: &Document) -> Vec<Manga> {
	let mut entries: Vec<Manga> = Vec::new();
	if let Some(cards) = doc.select("a.ori-cat-card") {
		for card in cards {
			let href = card.attr("href").unwrap_or_default();
			let slug = href
				.trim_end_matches('/')
				.rsplit('/')
				.next()
				.unwrap_or_default();
			if slug.is_empty() {
				continue;
			}

			let title = card
				.select_first(".ori-card-title")
				.and_then(|el| el.text())
				.unwrap_or_default();

			let cover = card.select_first(".ori-card-cover img").and_then(|el| get_image_url(&el));

			entries.push(Manga {
				key: String::from(slug),
				title,
				cover,
				..Default::default()
			});
		}
	}
	entries
}

// The admin-ajax `madara_load_more` response doesn't use the child theme's
// custom markup — it renders the stock Madara `manga_archives_item_layout=
// big_thumbnail` template: `div.page-item-detail` cards with `h3.h5 > a` for
// the title/link and a plain `img` for the cover. Same markup the other
// Madara-based sources in this repo already parse.
fn extract_stock_madara_entries(doc: &Document) -> Vec<Manga> {
	let mut entries: Vec<Manga> = Vec::new();
	if let Some(cards) = doc.select("div.page-item-detail") {
		for card in cards {
			let link = match card.select_first("h3.h5 > a") {
				Some(el) => el,
				None => continue,
			};
			let href = link.attr("href").unwrap_or_default();
			let slug = href
				.trim_end_matches('/')
				.rsplit('/')
				.next()
				.unwrap_or_default();
			if slug.is_empty() {
				continue;
			}

			let title = link.text().unwrap_or_default();
			let cover = card.select_first("img").and_then(|el| get_image_url(&el));

			entries.push(Manga {
				key: String::from(slug),
				title,
				cover,
				..Default::default()
			});
		}
	}
	entries
}

// A server-rendered catalogue/search view (used only for `?s=` search — real
// listing pages go through parse_catalogue_listing, see below).
fn parse_catalogue(url: &str) -> Result<MangaPageResult> {
	let doc = Request::get(url)?
		.header("User-Agent", USER_AGENT)
		.html()?;
	let entries = extract_catalogue_entries(&doc);
	Ok(MangaPageResult {
		has_next_page: !entries.is_empty(),
		entries,
	})
}

// Listing pages (Tendance/Populaire/Dernières mises à jour) all go through
// WordPress' admin-ajax.php `madara_load_more` action — the same call the
// site's own "Voir plus" button makes, and the same mechanism already used by
// the other Madara-based sources in this repo. This is used for every page,
// including the first: the static /catalogues/ page's `?m_orderby=` query
// param isn't confirmed to affect server-side sorting, so relying on it only
// for page 1 risked a mismatched, seemingly-stuck-at-"today" first batch.
// `meta_key` selects the sort (weekly views/all-time views/latest update
// timestamp), matching the site's own listing definitions.
fn parse_catalogue_listing(meta_key: &str, page: i32) -> Result<MangaPageResult> {
	let url = format!("{BASE_URL}/wp-admin/admin-ajax.php");
	let referer = format!("{BASE_URL}/catalogues/");
	let body = format!(
		"action=madara_load_more&page={}&template=madara-core%2Fcontent%2Fcontent-archive&vars%5Bpaged%5D=1&vars%5Borderby%5D=meta_value_num&vars%5Btemplate%5D=archive&vars%5Bsidebar%5D=full&vars%5Bpost_type%5D=wp-manga&vars%5Bpost_status%5D=publish&vars%5Bmeta_key%5D={}&vars%5Border%5D=desc&vars%5Bmeta_query%5D%5Brelation%5D=OR&vars%5Bmanga_archives_item_layout%5D=big_thumbnail",
		page - 1,
		meta_key,
	);
	let doc = Request::post(url)?
		.header("User-Agent", USER_AGENT)
		.header("Referer", referer.as_str())
		.header("Content-Type", "application/x-www-form-urlencoded")
		.body(body.as_bytes())
		.html()?;
	let entries = extract_stock_madara_entries(&doc);
	Ok(MangaPageResult {
		has_next_page: !entries.is_empty(),
		entries,
	})
}

impl Source for MangasOrigines {
	fn new() -> Self {
		Self
	}

	fn get_search_manga_list(
		&self,
		query: Option<String>,
		page: i32,
		_filters: Vec<FilterValue>,
	) -> Result<MangaPageResult> {
		match query {
			Some(q) if !q.is_empty() => {
				parse_catalogue(&format!("{BASE_URL}/catalogues/?s={}", urlencode(&q)))
			}
			_ => self.get_manga_list(
				Listing {
					id: String::from("latest"),
					name: String::from("Dernières mises à jour"),
					kind: aidoku::ListingKind::Default,
				},
				page,
			),
		}
	}

	fn get_manga_update(
		&self,
		mut manga: Manga,
		needs_details: bool,
		needs_chapters: bool,
	) -> Result<Manga> {
		let url = format!("{BASE_URL}/oeuvre/{}/", manga.key);
		let html = Request::get(&url)?
			.header("User-Agent", USER_AGENT)
			.html()?;

		if needs_details {
			manga.title = html
				.select_first("h1.ori-sr-title")
				.and_then(|el| el.text())
				.unwrap_or(manga.title);

			manga.cover = html
				.select_first(".ori-sr-cover img")
				.and_then(|el| get_image_url(&el));

			if let Some(signature) = html.select(".ori-sr-signature a") {
				let mut names = signature.into_iter().filter_map(|el| el.text());
				manga.authors = names.next().map(|n| vec![n]);
				manga.artists = names.next().map(|n| vec![n]);
			}

			manga.description = html
				.select_first(".ori-sr-syn-texte")
				.and_then(|el| el.text());

			let mut tags: Vec<String> = Vec::new();
			if let Some(genres) = html.select("a.ori-sr-genre") {
				for el in genres {
					if let Some(text) = el.text() {
						tags.push(text);
					}
				}
			}
			manga.tags = Some(tags.clone());

			let status_text = html
				.select_first(".ori-sr-badge-statut")
				.and_then(|el| el.text())
				.unwrap_or_default()
				.to_lowercase();
			manga.status = if status_text.contains("en cours") {
				MangaStatus::Ongoing
			} else if status_text.contains("termin") {
				MangaStatus::Completed
			} else if status_text.contains("annul") {
				MangaStatus::Cancelled
			} else if status_text.contains("pause") {
				MangaStatus::Hiatus
			} else {
				MangaStatus::Unknown
			};

			manga.content_rating = ContentRating::Safe;
			manga.url = Some(url.clone());

			// Format tags (webtoon/manhwa/...) are checked across every tag before
			// falling back to demographic-independent manga/japan hints, so a later
			// "Webcomic" tag isn't shadowed by an earlier "Shonen"/"Seinen" one —
			// those say nothing about reading direction on their own.
			let webtoon_tags = ["manhwa", "manhua", "webtoon", "webcomic", "vertical", "korean", "chinese"];
			let rtl_tags = ["manga", "japan"];
			let tags_lower: Vec<String> = tags.iter().map(|t| t.to_lowercase()).collect();
			manga.viewer = if tags_lower.iter().any(|t| webtoon_tags.iter().any(|tag| t.contains(tag))) {
				Viewer::Webtoon
			} else if tags_lower.iter().any(|t| rtl_tags.iter().any(|tag| t.contains(tag))) {
				Viewer::RightToLeft
			} else {
				Viewer::Webtoon
			};

			if let Some(setting) = defaults_get::<String>("defaultViewer") {
				manga.viewer = match setting.as_str() {
					"rtl" => Viewer::RightToLeft,
					"ltr" => Viewer::LeftToRight,
					"vertical" => Viewer::Vertical,
					"webtoon" => Viewer::Webtoon,
					_ => manga.viewer,
				};
			}
		}

		if needs_chapters {
			let mut chapters: Vec<Chapter> = Vec::new();
			if let Some(rows) = html.select("div.ori-chl-row") {
				for row in rows {
					let href = row
						.select_first("a.ori-chl-corps")
						.and_then(|el| el.attr("href"))
						.unwrap_or_default();
					let slug = href
						.trim_end_matches('/')
						.rsplit('/')
						.next()
						.unwrap_or_default();
					if slug.is_empty() {
						continue;
					}

					let mut title = row.select_first(".ori-chl-nom-long").and_then(|el| el.text());
					if title.is_none() {
						title = row.select_first(".ori-chl-nom").and_then(|el| el.text());
					}

					let chapter_number = row
						.attr("data-num")
						.and_then(|s| s.parse::<f32>().ok());

					let date_text = row
						.select_first(".ori-chl-date")
						.and_then(|el| el.text())
						.unwrap_or_default();
					let date_uploaded = parse_date(&date_text, "dd/MM/yy");

					chapters.push(Chapter {
						key: String::from(slug),
						title,
						chapter_number,
						date_uploaded,
						url: Some(String::from(href.as_str())),
						language: Some(String::from("fr")),
						..Default::default()
					});
				}
			}
			manga.chapters = Some(chapters);
		}

		Ok(manga)
	}

	fn get_page_list(&self, manga: Manga, chapter: Chapter) -> Result<Vec<Page>> {
		let url = chapter
			.url
			.clone()
			.unwrap_or_else(|| format!("{BASE_URL}/oeuvre/{}/{}/", manga.key, chapter.key));
		let html = Request::get(&url)?
			.header("User-Agent", USER_AGENT)
			.html()?;

		let mut pages: Vec<Page> = Vec::new();
		if let Some(images) = html.select("div.page-break > img") {
			for img in images {
				if let Some(src) = get_image_url(&img) {
					pages.push(Page {
						content: PageContent::url(src),
						..Default::default()
					});
				}
			}
		}
		Ok(pages)
	}
}

impl ListingProvider for MangasOrigines {
	fn get_manga_list(&self, listing: Listing, page: i32) -> Result<MangaPageResult> {
		let meta_key = match listing.id.as_str() {
			"populaire" => "_wp_manga_views",
			"latest" => "_latest_update",
			_ => "_wp_manga_week_views_value",
		};
		parse_catalogue_listing(meta_key, page)
	}
}

impl Home for MangasOrigines {
	fn get_home(&self) -> Result<HomeLayout> {
		let tendance_listing = Listing {
			id: String::from("tendance"),
			name: String::from("Tendance"),
			kind: aidoku::ListingKind::Default,
		};
		let populaire_listing = Listing {
			id: String::from("populaire"),
			name: String::from("Populaire"),
			kind: aidoku::ListingKind::Default,
		};
		let latest_listing = Listing {
			id: String::from("latest"),
			name: String::from("Dernières mises à jour"),
			kind: aidoku::ListingKind::Default,
		};
		let tendance = self.get_manga_list(tendance_listing.clone(), 1)?;
		let populaire = self.get_manga_list(populaire_listing.clone(), 1)?;
		let latest = self.get_manga_list(latest_listing.clone(), 1)?;

		Ok(HomeLayout {
			components: vec![
				HomeComponent {
					title: Some(String::from("Tendance")),
					subtitle: None,
					value: HomeComponentValue::Scroller {
						entries: tendance.entries.into_iter().map(Into::into).collect(),
						listing: Some(tendance_listing),
					},
				},
				HomeComponent {
					title: Some(String::from("Populaire")),
					subtitle: None,
					value: HomeComponentValue::Scroller {
						entries: populaire.entries.into_iter().map(Into::into).collect(),
						listing: Some(populaire_listing),
					},
				},
				HomeComponent {
					title: Some(String::from("Dernières mises à jour")),
					subtitle: None,
					value: HomeComponentValue::Scroller {
						entries: latest.entries.into_iter().map(Into::into).collect(),
						listing: Some(latest_listing),
					},
				},
			],
		})
	}
}

impl ImageRequestProvider for MangasOrigines {
	fn get_image_request(&self, url: String, _context: Option<PageContext>) -> Result<Request> {
		Ok(Request::get(&url)?
			.header("Referer", BASE_URL)
			.header("User-Agent", USER_AGENT))
	}
}

impl DeepLinkHandler for MangasOrigines {
	fn handle_deep_link(&self, url: String) -> Result<Option<DeepLinkResult>> {
		let parts: Vec<&str> = url.trim_end_matches('/').split('/').collect();
		if let Some(pos) = parts.iter().position(|p| *p == "oeuvre") {
			if let Some(manga_key) = parts.get(pos + 1) {
				if let Some(chapter_key) = parts.get(pos + 2) {
					return Ok(Some(DeepLinkResult::Chapter {
						manga_key: String::from(*manga_key),
						key: String::from(*chapter_key),
					}));
				}
				return Ok(Some(DeepLinkResult::Manga {
					key: String::from(*manga_key),
				}));
			}
		}
		Ok(None)
	}
}

register_source!(
	MangasOrigines,
	ListingProvider,
	Home,
	ImageRequestProvider,
	DeepLinkHandler
);
