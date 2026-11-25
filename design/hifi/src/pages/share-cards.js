/* Page: share images — PROTOTYPE (not in the app) for the About and Note share styles next to the lyrics one
   (components/share.js). kind = what the image shows (sheet = the share sheet with the style switcher, drawn on a
   phone); v = the variant; fmt = Card / Story; tone = the four tone slots.
   An image state (kind ≠ sheet) draws the bare image at its logical size in place of the device screen, so
   export.mjs writes the share PNG itself: Card at --scale 4 (1440 px wide), Story at --scale 3 (1080 × 1920).
   Use --frame tablet so the viewport is larger than the image. The song follows the language (?lang=zh:
   苏打绿《他夏了夏天》, en: Weyes Blood "A Lot’s Gonna Change"), with real covers on ?catalog=real.
   Round 2 (owner 2026-11-25): all three About and Note layouts stay; lyrics get Poster / Timeline / Vertical next
   to today's card; the sheet gets a layout row (picker = thumbs, recommended, or named chips). */
const SC_CTL = [
  { key: 'kind', label: 'Style', options: [['about', 'About'], ['note', 'Note'], ['lyrics', 'Lyrics (today)'], ['sheet', 'Share sheet']], def: 'about' },
  { key: 'v', label: 'Variant', options: [['A', 'A'], ['B', 'B'], ['C', 'C'], ['D', 'D (lyrics, CJK)']], def: 'A' },
  { key: 'fmt', label: 'Format', options: [['card', 'Card'], ['story', 'Story']], def: 'card' },
  { key: 'tab', label: 'Sheet shows', options: [['lyrics', 'Lyrics'], ['about', 'About'], ['note', 'Note']], def: 'about' },
  { key: 'picker', label: 'Layout row', options: [['thumbs', 'Thumbnails'], ['chips', 'Named chips']], def: 'thumbs' },
  { key: 'tone', label: 'Tone', options: [['soft', 'Soft'], ['deep', 'Deep'], ['vivid', 'Vivid'], ['paper', 'Paper']], def: 'soft' },
];
function renderShare(st, size) {
  if (st.kind === 'sheet') return shareSheet(st, size);
  return shareImage(st);
}
function seatShare(dev, st) {
  dev.classList.toggle('img', st.kind !== 'sheet');
  seatStoryZoom(dev);
  if (st.kind !== 'sheet') {
    dev.style.width = '360px';
    dev.style.height = st.fmt === 'story' ? '640px' : 'auto';
    // a fractional height would leave the PNG's last pixel row transparent: grow the image to whole px (measured unscaled: Proto.fit may have scaled the device)
    const img = dev.querySelector('.si');
    if (img && st.fmt !== 'story') { img.style.minHeight = ''; const k = dev.getBoundingClientRect().width / dev.offsetWidth, h = Math.ceil(img.getBoundingClientRect().height / k - 0.01); img.style.minHeight = h + 'px'; dev.style.height = h + 'px'; }
    const wrap = document.querySelector('.dev-wrap');
    wrap.style.width = '360px'; wrap.style.height = dev.offsetHeight + 'px';
  } else {
    seatShareThumbs(dev);
    seatSharePreview(dev);
  }
}
Proto.init({ title: 'Share images (prototype)', controls: SC_CTL,
  scheme: (st) => (st.catalog === 'real' ? shareSong().k : 'glass'),
  render: renderShare,
  after: (dev, st) => { seatShare(dev, st); document.fonts.ready.then(() => seatShare(dev, st)); } });
