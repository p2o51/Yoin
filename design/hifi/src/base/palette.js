/* Colour: M3 roles from a seed, the way the app swaps tokens for the playing cover.
   The app builds SchemeExpressive (MCU, SPEC_2025) from the cover's seed (CoverSeedExtractor →
   ExpressiveColorSchemeFactory). Here each demo cover has a fixed seed read off the real screenshots:
   primary hue / chroma, the secondary and the (rotated) tertiary hue, and a neutral chroma. Tones follow
   the M3 tone table; tone → OKLCH lightness goes through CIE L* → Y → cube root, which is what OKLab's L is
   for a neutral, so "T80" here lands close to HCT tone 80. Approximation, not MCU. */
const SEEDS = {
  wallpaper: { name: 'Wallpaper', h: 182, c: .105, s: 198, sc: .04, t: 244, tc: .085, n: .026 },
  glass: { name: 'Glass Orchard', h: 22, c: .13, s: 236, sc: .05, t: 212, tc: .12, n: .02 },
  yehang: { name: '夜航', h: 174, c: .10, s: 200, sc: .04, t: 64, tc: .095, n: .016 },
  harbor: { name: 'Harbor Lights', h: 48, c: .11, s: 40, sc: .045, t: 195, tc: .07, n: .014 },
  north: { name: 'Northern Field Recordings', h: 272, c: .1, s: 150, sc: .05, t: 215, tc: .07, n: .016 },
};
function toneL(t) { const y = t > 8 ? Math.pow((t + 16) / 116, 3) : t / 903.3; return Math.cbrt(y); }
function tone(h, c, t) {
  const L = toneL(t), k = Math.min(1, Math.sqrt(Math.max(0, 5 * L * (1 - L))));
  return `oklch(${(L * 100).toFixed(1)}% ${(c * k).toFixed(3)} ${h})`;
}
const ROLES = 'primary onPrimary primaryContainer onPrimaryContainer wash secondary onSecondary secondaryContainer onSecondaryContainer tertiary onTertiary tertiaryContainer onTertiaryContainer vivid onVivid surface surfaceDim surfaceBright surfaceContainerLowest surfaceContainerLow surfaceContainer surfaceContainerHigh surfaceContainerHighest onSurface surfaceVariant onSurfaceVariant outline outlineVariant inverseSurface inverseOnSurface error'.split(' ');
function scheme(key, dark) {
  const s = SEEDS[key] || SEEDS.wallpaper;
  const P = (t) => tone(s.h, s.c, t), S = (t) => tone(s.s, s.sc, t), T = (t) => tone(s.t, s.tc, t);
  const N = (t) => tone(s.h, s.n, t), V = (t) => tone(s.h, s.n * 2, t);
  // SPEC_2025 Expressive: dark primaryContainer is the max-chroma tone in 30–93 (a bright salmon on Glass Orchard,
  // as the lyric keys / tab group show), not M3-classic T30. "wash" keeps the classic T30 / T90 container tone for
  // the NP background fields, the rating track and the album wash (decorative, not an M3 role).
  // "vivid" = the bright tertiary the NP uses for repeat / heart (cyan on Glass Orchard in both themes)
  return dark ? {
    primary: P(82), onPrimary: P(20), primaryContainer: tone(s.h, s.c * 1.1, 74), onPrimaryContainer: P(12), wash: P(30),
    secondary: S(80), onSecondary: S(20), secondaryContainer: S(30), onSecondaryContainer: S(90),
    tertiary: T(80), onTertiary: T(20), tertiaryContainer: T(30), onTertiaryContainer: T(90),
    vivid: tone(s.t, s.tc * 1.25, 80), onVivid: T(15),
    surface: N(6), surfaceDim: N(6), surfaceBright: N(24), surfaceContainerLowest: N(4), surfaceContainerLow: N(10),
    surfaceContainer: N(12), surfaceContainerHigh: N(17), surfaceContainerHighest: N(22), onSurface: N(92),
    surfaceVariant: V(28), onSurfaceVariant: V(78), outline: V(60), outlineVariant: V(30),
    inverseSurface: N(90), inverseOnSurface: N(20), error: tone(25, .14, 80),
  } : {
    primary: P(42), onPrimary: P(100), primaryContainer: P(90), onPrimaryContainer: P(28), wash: P(90),
    secondary: S(40), onSecondary: S(100), secondaryContainer: S(90), onSecondaryContainer: S(28),
    tertiary: T(40), onTertiary: T(100), tertiaryContainer: T(90), onTertiaryContainer: T(28),
    vivid: tone(s.t, s.tc * 1.3, 76), onVivid: T(15),
    surface: N(98), surfaceDim: N(87), surfaceBright: N(98), surfaceContainerLowest: N(100), surfaceContainerLow: N(96),
    surfaceContainer: N(94), surfaceContainerHigh: N(92), surfaceContainerHighest: N(90), onSurface: N(10),
    surfaceVariant: V(90), onSurfaceVariant: V(32), outline: V(50), outlineVariant: V(80),
    inverseSurface: N(20), inverseOnSurface: N(95), error: tone(25, .16, 40),
  };
}
/** Writes the roles as --primary … on an element; children read var(--primary). */
function applyScheme(el, key, dark) {
  const sc = scheme(key, dark);
  for (const r of ROLES) el.style.setProperty('--' + r, sc[r]);
  el.dataset.scheme = key; el.dataset.theme = dark ? 'dark' : 'light';
  return sc;
}
