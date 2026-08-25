# Mangas Origines — Kotatsu external source

A standalone Android app that exposes [mangas-origines.fr](https://mangas-origines.fr) to Kotatsu
(and compatible forks — Kotatsu-Redo, Futon, Usagi) as an **external source**, per the protocol
reverse-engineered from `KotatsuApp/Kotatsu`'s `core/parser/external/ExternalPluginContentSource.kt`.

## Why a separate app at all

Unlike Aidoku or Paperback, Kotatsu has no "add a source list by URL" mechanism. A source is either
compiled into the app via [kotatsu-parsers](https://github.com/KotatsuApp/kotatsu-parsers) (requires
a PR upstream + a new app release), or — the route taken here — a separate installable app that
answers the `app.kotatsu.parser.PROVIDE_MANGA` intent with a `ContentProvider`. Kotatsu discovers it
automatically via the package manager once it's installed; there's nothing to configure in this app
itself.

Reference implementation this was modeled on:
[thatagent/kotatsu-suwayomi-source](https://github.com/thatagent/kotatsu-suwayomi-source) (`app/`
module) — same protocol, different backend (a self-hosted Suwayomi server instead of a scraped site).

## Scraping logic

Ported from the Aidoku source (`aidoku/mangasorigines`) — same selectors, same admin-ajax
`madara_load_more` pagination for listings, same "reconstruct the chapter URL from the row's full
href, not from parts" fix. See `app/src/main/kotlin/fr/mangasorigines/kotatsu/data/MangasOriginesRepository.kt`
for the port and its comments on the two things that are Kotatsu-protocol-specific (not shared with
the Aidoku version):

- **Offset-based pagination**: Kotatsu's protocol paginates listings by item *offset*, not page
  number, but the site's AJAX endpoint only understands page numbers. `PAGE_SIZE` in the repository
  is an assumed average batch size used to translate between the two; it may drift slightly at page
  boundaries but pagination still terminates correctly.
- **Search is unconfirmed**: `?s=` search against `/catalogues/` was never verified against the live
  site — Cloudflare blocked every direct test request from the dev machine that built this, same as
  it did for the Aidoku source originally. Only real on-device testing (which the sibling Aidoku
  source got, this one hasn't) can confirm it or reveal the selector is wrong.

## Building

```sh
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Requires an Android SDK with platform 36 + build-tools installed (`local.properties` pointing
`sdk.dir` at it — not committed, create your own). Built and verified compiling in this repo's dev
environment against a locally-installed SDK.

## Installing

1. Build (above) or download the APK from CI (once the `kotatsu` workflow job exists — not yet
   wired up as part of this initial delivery, see the repo root README's status table).
2. Sideload the APK on your Android device.
3. Open it once (nothing to configure, just confirms it installed).
4. In Kotatsu (or your fork), open the source catalogue → external sources → enable "Mangas Origines".

## What's unverified

No Android device or emulator was available while building this — only `./gradlew assembleDebug`
(does it compile) was checked, not runtime behavior. Before trusting this for real reading:

- Install the APK and confirm Kotatsu actually lists/enables the source (the `PROVIDE_MANGA`
  intent-filter discovery is the part most likely to need small manifest tweaks per Kotatsu version).
- Browse the three sort orders (Dernières mises à jour / Populaire / Tendance) and confirm pagination
  doesn't stall or skip due to the offset↔page approximation described above.
- Try a real search — again, unconfirmed selectors.
- Open a chapter and confirm pages load (image `Referer`/`User-Agent` headers are set on the Aidoku
  side via a per-request header already; this port does not yet set an image-request `Referer`
  header for page loads since the Kotatsu protocol hands back raw page URLs for the app's own image
  loader to fetch — if pages 403 in practice, that loader may need a per-source header hook this
  plugin doesn't currently populate).
