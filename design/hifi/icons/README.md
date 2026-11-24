# Icons

`*.svg` here are copies of [Yoin Symbols](https://github.com/p2o51/yoin-symbols) (`svg/`, version 0.2.0, 2026-11-13), the icon library the app is built with. They are © 2026 p2o51 under the MIT License; see `LICENSE` in this folder. They are vendored so `node design/hifi/build.mjs` needs nothing outside this folder.

`drawn/` holds the few glyphs the prototypes need that the library doesn't have yet (`mic_off`, `compress`, `bold`), drawn in the same 1.5 dp stroke. When the library gains one, delete it here.

To refresh after an icon change in yoin-symbols: `node design/hifi/tools/sync-icons.mjs ../yoin-symbols` (copies every SVG already here from the library, and any new name a prototype uses with `ic('name')`).
