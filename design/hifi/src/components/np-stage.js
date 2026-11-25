/* Now Playing stage pieces (ui/nowplaying: NowPlayingScreen, NowPlayingRatingBar, PlaybackControls,
   WaveProgressBar, BottomPills, SingControls). Sizes from YoinTokens.NowPlaying:
   column padding 24 · cover ≤ 312 (radius 16) · rating column 56 (48 capsule, bar = cover − 58, 8 gap, 44 heart)
   · transport 56 controls in rows 56 + 4 + 56 · pills 44 · hero artist + title. */
const NP_TRACKS = {
  glass: { title: 'Goodnight, Tokyo', album: CAT.glassAlbum, artist: 'The Meridians', rating: 8.6, fmt: 'WAV 16/44.1', pos: 153, dur: 224, from: 'Glass Orchard' },
  yehang: { title: '灯塔', album: CAT.yehang, artist: '林间', rating: 9.2, fmt: 'FLAC 24/48', pos: 36, dur: 247, from: '夜航' },
  harbor: { title: 'Night Ferry', album: CAT.harbor, artist: 'Kiri Ono', rating: 7.8, fmt: 'MP3 320', pos: 96, dur: 211, from: 'Harbor Lights' },
};
const mmss = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;

/** Background: dark = surfaceContainer → background with four cover-colour fields; light = the "room"
   (background ground, neighbours' primaryContainer bleeding in at top / bottom, primary glow centre, haze). */
function npBackground() { return '<div class="np-bg"><i class="f1"></i><i class="f2"></i><i class="f3"></i><i class="f4"></i></div>'; }

/** StageTopBar: ⌄ + "PLAYING FROM ALBUM" / name; docked = ‹ + 44 cover (lyrics / note pages). */
function npTopBar(t, { docked = false, end = '' } = {}) {
  const lead = docked ? `<span class="np-tb-lead">${ic('chevron_left', 24)}</span>${cover(t.album.k, 'np-tb-cv')}` : `<span class="np-tb-lead">${ic('chevron_down', 28)}</span>`;
  return `<div class="np-tb">${lead}<span class="np-from"><small>${loc('PLAYING FROM ALBUM', '播放自专辑')}</small><span class="ell">${t.from}</span></span>${end}</div>`;
}

/** Cover + rating column. sing: the rating bar becomes the vocal slider, the heart the mic-off exit. */
function npCoverRating(t, coverPx, { sing = false, level = SING_DEFAULT } = {}) {
  const barH = coverPx - 58;
  return `<div class="np-cr" style="--cover:${coverPx}px">${cover(t.album.k, 'np-cover')}<div class="np-rcol">` +
    (sing ? vocalSlider(barH, level) : ratingBar(barH, t.rating)) +
    (sing ? `<button class="np-heart exit" aria-label="Turn off Sing">${ic('mic_off', 22)}</button>` : `<button class="np-heart" aria-label="Favourite">${ic('favorite', 22)}</button>`) + '</div></div>';
}
function ratingBar(h, r, { horizontal = false } = {}) {
  const f = r / 10, onFill = f >= .8; // RATING_LABEL_CROSSOVER
  // the score sits at the top of the track, or just inside the fill once the fill reaches it
  const top = onFill ? Math.round((1 - f) * h + 10) : 12;
  return `<div class="rbar" style="height:${h}px"><i class="rfill" style="height:${(f * 100).toFixed(1)}%"></i><span class="rnum num ${onFill ? 'on' : ''}" style="top:${top}px">${r.toFixed(1)}</span></div>`;
}
/** SingVocalSlider: flat-edged primary fill clipped by the capsule, the detent notch at the default gain's position
   (≥ 36 from the end), dual-tone mic at the bottom, no percentage text. SingGainCurve (sing branch, 2026-11-24):
   RANGE_DB 28, KNEE 0.05, DEFAULT_GAIN 0.12 → p = 1 + 20·log10(0.12) / 28 ≈ 0.342; snaps within 0.286–0.379. */
const SING_DEFAULT = 1 + 20 * Math.log10(0.12) / 28;
function vocalSlider(h, level = SING_DEFAULT) {
  const notch = Math.max(36, h * SING_DEFAULT), lit = level * h >= notch;
  return `<div class="rbar vocal" style="height:${h}px"><i class="vfill" style="height:${level * 100}%"></i><i class="vnotch ${lit ? 'on' : ''}" style="bottom:${notch}px"></i>` +
    `<span class="vmic">${ic('mic', 20)}</span><span class="vmic on" style="clip-path:inset(${Math.min(100, Math.max(0, (32 - level * h) / 20 * 100)).toFixed(1)}% 0 0 0)">${ic('mic', 20)}</span></div>`;
}

/** Wave progress: wavy from 0 to the playhead while playing (no flat start / end), flat track after it. */
function waveSvg(w, p) {
  const x1 = Math.max(0, w * p - 4), amp = 3, wl = 20; let d = 'M2 10';
  for (let x = 2; x <= x1; x += 1) d += `L${x.toFixed(0)} ${(10 + amp * Math.sin((x / wl) * Math.PI * 2)).toFixed(2)}`;
  return `<svg class="wave" width="${w}" height="20" viewBox="0 0 ${w} 20"><path d="M${(x1 + 8).toFixed(1)} 10H${w - 2}" class="wtrack"/><path d="${d}" class="wplay"/><circle cx="${w - 2}" cy="10" r="2" class="wstop"/></svg>`;
}
/** PlaybackControls: [PAUSE][⏭] … [repeat] / [⏮] 2:33 ~~~ −1:11. Width = the column's inner width. */
function npTransport(t, w) {
  const waveW = Math.max(60, w - 56 - 8 - 44 - 52 - 16);
  return `<div class="np-tr"><div class="tr1"><button class="tr-play">PAUSE</button><button class="tr-c tr-next" aria-label="Next">${ic('skip_next_filled', 24)}</button><span class="sp"></span>` +
    `<button class="tr-c tr-mode" aria-label="Play mode: repeat all">${ic('repeat', 24)}</button></div>` +
    `<div class="tr2"><button class="tr-c tr-prev" aria-label="Previous">${ic('skip_previous_filled', 24)}</button><span class="tt0 num">${mmss(t.pos)}</span>${waveSvg(waveW, t.pos / t.dur)}<span class="tt1 num">-${mmss(t.dur - t.pos)}</span></div></div>`;
}
function npHero(t, { small = false } = {}) {
  return `<div class="np-hero ${small ? 'sm' : ''}"><div class="np-artist"><span>${t.artist}</span><span class="np-chip">${t.fmt}</span></div><div class="np-title ell">${t.title}</div></div>`;
}
function npPills() {
  return `<div class="np-pills">${[['queue', loc('Queue', '队列')], ['devices', loc('Devices', '设备')], ['edit_note', loc('Write', '写笔记')]].map(([i, l]) => `<button class="np-pill">${ic(i, 18)}<span>${l}</span></button>`).join('')}</div>`;
}
