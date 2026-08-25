# mangasorigines-sources

Reader extensions for [mangas-origines.fr](https://mangas-origines.fr), for multiple manga apps.

| App | Status | Add source list |
| --- | --- | --- |
| [Aidoku](https://aidoku.app) (iOS) | Working | `https://<user>.github.io/mangasorigines-sources/aidoku/index.min.json` |
| [Paperback](https://paperback.moe) (iOS) | In progress | — |
| [Kotatsu](https://kotatsu.app) (Android) | In progress | — |

## Structure

- `aidoku/mangasorigines/` — Rust source for Aidoku (aidoku-rs / new WASM format)
- `paperback/mangasorigines/` — TypeScript source for Paperback (0.8 toolchain)
- `kotatsu/` — Android companion app exposing a Kotatsu external source plugin

CI builds every source and publishes them to GitHub Pages (`gh-pages` branch) on every push to `main`.
