/* Home's top edge: the ⌄ that pulls Memories down, and the bubble that speaks from it (HomeMemoryBubble,
   HomeBubbleSay). memory = a memory with its cover, a tail up into the ⌄, a sticker at the end; it folds
   back into the title, so the title hides while it shows. tip = a plain capsule (no tail, both ends round)
   with a round symbol block on the left. */
function memoryChevron() { return `<div class="mchev">${ic('chevron_down', 20)}</div>`; }
function memoryBubble(kind) {
  if (kind === 'tip') {
    return `<div class="mbub tip" style="${artVars('yehang')}"><span class="mb-sym">${ic('chevron_down', 20)}</span><span class="col"><b class="ell">Pull down on Home</b><span class="ell">to open your Memories</span></span></div>`;
  }
  const it = CAT.harbor;
  return `<div class="mbub mem" style="${artVars(it.k)}"><svg class="mb-tail" viewBox="0 0 44 12" preserveAspectRatio="none"><path d="M0 12C10 12 14 0 22 0S34 12 44 12Z"/></svg>` +
    `${cover(it.k, 'mb-cv')}<span class="col"><b class="ell">${it.t}</b><span class="ell">You rated it 8.4 · 18h ago</span></span><span class="mb-stk">${shapeSvg('SoftBurst')}</span></div>`;
}
