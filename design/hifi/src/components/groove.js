/* The Groove emblem (ui/memories/emblem: GrooveEmblem; AlbumScoreMark on the album page). A record seen from
   above: one ring per track, the outer ring = track 1; rated tracks are cut as solid grooves, unrated ones are
   dotted. Centre label: album score = filled Cookie12Sided with the number; track average = outlined circle with
   "Avg." over the number; unrated = a dashed cookie mould with the word "Unrated", in neutral colours.
   Ring budget: 12 rings at ≥ 110, 8 at ≥ 88, 6 at ≥ 64, else 3. Colours: the album's palette (--pal / --acc on the
   element, see artVars) mixed toward the page; unrated stays neutral (onSurfaceVariant on surfaceContainerHigh). */
function grooveEmblem(px, { kind = 'album', score = '', rated = [], art = 'harbor', label = 'Unrated' } = {}) {
  const budget = px >= 110 ? 12 : px >= 88 ? 8 : px >= 64 ? 6 : 3;
  const n = Math.min(budget, Math.max(rated.length, 3)), r0 = 46, r1 = 25, step = (r0 - r1) / Math.max(1, n - 1);
  let rings = '';
  for (let i = 0; i < n; i++) {
    const r = (r0 - i * step).toFixed(2), on = rated[i];
    rings += on ? `<circle r="${r}" class="gr-cut"/>` : `<circle r="${r}" class="gr-dot"/>`;
  }
  const cookie = `<path class="${kind === 'unrated' ? 'gr-mould' : 'gr-lab'}" transform="translate(-23 -23) scale(46)" d="${SHAPES.Cookie12Sided}" vector-effect="non-scaling-stroke"/>`;
  const centre = kind === 'avg'
    ? `<circle r="21" class="gr-ring"/><text y="-6" class="gr-avg">Avg.</text><text y="9" class="gr-num sm">${score}</text>`
    : kind === 'unrated' ? `${cookie}<text y="4" class="gr-un">${label}</text>` : `${cookie}<text y="7" class="gr-num">${score}</text>`;
  return `<span class="gv ${kind}" style="--gv:${px}px;${artVars(art)}"><svg viewBox="-50 -50 100 100"><circle r="49" class="gr-disc"/>${rings}${centre}</svg></span>`;
}
