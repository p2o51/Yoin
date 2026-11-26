/* Icons: Yoin Symbols outlines, vendored as SVG files in design/hifi/icons/ (copied from p2o51/yoin-symbols, the
   library the app depends on; MIT, see icons/LICENSE), plus a few drawn here in the same 1.5dp stroke where the
   library has none (icons/drawn/: mic_off, compress, bold). build.mjs reads those files into ICONS below:
   each entry is [svg body, stroke width]. ic(name, size) returns an inline <svg>. Tree-shaken per page at build time.
   To refresh from a newer yoin-symbols: node tools/sync-icons.mjs [path/to/yoin-symbols]. */
const ICONS={};
function ic(n,s=24,cls=""){const d=ICONS[n];if(!d)return "";return `<svg class="ic ${cls}" viewBox="0 0 24 24" width="${s}" height="${s}" fill="none" stroke="currentColor" stroke-width="${d[1]}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${d[0]}</svg>`}
