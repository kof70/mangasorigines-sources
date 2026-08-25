# mangasorigines-sources

Reader extensions for [mangas-origines.fr](https://mangas-origines.fr), for multiple manga apps.

| App | Status | Add source list |
| --- | --- | --- |
| [Aidoku](https://aidoku.app) (iOS) | Working | `https://kof70.github.io/mangasorigines-sources/aidoku/index.min.json` |
| [Paperback](https://paperback.moe) (iOS) | Working (bundles cleanly; untested on-device) | `https://kof70.github.io/mangasorigines-sources/paperback/versioning.json` |
| [Kotatsu](https://kotatsu.app) (Android) | Builds successfully (untested on-device); no CI/releases yet | sideload the APK — see `kotatsu/mangasorigines/README.md` |

## Structure

- `aidoku/mangasorigines/` — Rust source for Aidoku (aidoku-rs / new WASM format)
- `paperback/src/Mangasorigines/` — TypeScript source for Paperback (0.8.0-alpha.47 toolchain; `paperback/` is the toolchain project root — the `paperback bundle` CLI requires that layout)
- `kotatsu/mangasorigines/` — Android companion app (Kotlin/Gradle) exposing mangas-origines.fr to Kotatsu as an external-source `ContentProvider` — a separate APK to sideload, not a URL-installed source like the other two

CI builds every source and publishes them to GitHub Pages (`gh-pages` branch) on every push to `main`.
