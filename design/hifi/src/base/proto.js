/* The prototype shell every page uses: the switcher bar (width, theme + page controls), the device frame
   with system bars, URL state (?w=phone|tablet|landscape&theme=dark|light&…, so a state can be linked or
   screenshotted), and fit-to-width scaling for narrow embeds (Notion).
   Language: ?lang=zh draws the app's Simplified Chinese labels (values-zh-rCN) where a component wraps its words
   in loc('English', '中文'); demo content (titles, notes, lyrics) stays as it is. The scrapbook receipt is
   English in both, as in the app.
   A page calls Proto.init({ title, controls, scheme(state), render(state) → html, after(root, state) }). */
const SIZES = {
  phone: { w: 412, h: 915, cls: 'compact', label: 'Phone 412' },
  tablet: { w: 800, h: 1280, cls: 'medium', label: 'Tablet 800' },
  landscape: { w: 1280, h: 800, cls: 'expanded', label: 'Landscape 1280' },
};
const Proto = {
  state: {},
  init(opts) {
    this.opts = opts;
    const q = new URLSearchParams(location.search);
    const ctl = [{ key: 'w', label: 'Width', options: Object.entries(SIZES).map(([k, v]) => [k, v.label.split(' ')[0]]), def: 'phone' },
      { key: 'theme', label: 'Theme', options: [['dark', 'Dark'], ['light', 'Light']], def: 'dark' },
      { key: 'lang', label: 'Language', options: [['en', 'EN'], ['zh', '中文']], def: 'en' }, ...(opts.controls || [])];
    this.controls = ctl;
    for (const c of ctl) this.state[c.key] = q.get(c.key) && c.options.some((o) => o[0] === q.get(c.key)) ? q.get(c.key) : c.def;
    this.state.tall = q.get('tall') === '1';
    document.body.insertAdjacentHTML('afterbegin', coverSprite() +
      `<div class="pbar" role="toolbar"><h1>${opts.title}<small>Yoin Hi-Fi · Android</small></h1>${ctl.map((c) =>
        `<div class="pgrp" data-k="${c.key}"><span>${c.label}</span><div class="seg">${c.options.map((o) => `<button type="button" data-v="${o[0]}">${o[1]}</button>`).join('')}</div></div>`).join('')}</div>
      <div class="stage"><div><div class="dev-wrap"><div class="device"></div></div><div class="dev-cap"></div></div></div>`);
    document.querySelector('.pbar').addEventListener('click', (e) => {
      const b = e.target.closest('button[data-v]'); if (!b) return;
      this.set(b.parentElement.parentElement.dataset.k, b.dataset.v);
    });
    addEventListener('resize', () => this.fit());
    this.render();
  },
  set(k, v) { this.state[k] = v; this.render(); },
  render() {
    const s = this.state, size = SIZES[s.w], dark = s.theme === 'dark';
    document.body.classList.toggle('ui-dark', dark);
    document.documentElement.lang = s.lang === 'zh' ? 'zh-Hans' : 'en';
    for (const g of document.querySelectorAll('.pgrp')) for (const b of g.querySelectorAll('button')) b.setAttribute('aria-pressed', String(s[g.dataset.k] === b.dataset.v));
    const q = new URLSearchParams(); for (const c of this.controls) if (s[c.key] !== c.def) q.set(c.key, s[c.key]); if (s.tall) q.set('tall', '1');
    history.replaceState(null, '', q.toString() ? '?' + q : location.pathname);
    const dev = document.querySelector('.device');
    dev.dataset.size = size.cls; dev.dataset.w = s.w;
    dev.style.width = size.w + 'px'; dev.style.height = s.tall ? 'auto' : size.h + 'px';
    dev.classList.toggle('tall', s.tall);
    applyScheme(dev, this.opts.scheme ? this.opts.scheme(s) : 'wallpaper', dark);
    dev.innerHTML = `<div class="screen">${this.opts.render(s, size)}</div>
      <div class="sys-status"><span>2:23</span><span class="sys-cam"></span><span class="r">${sysIcons()}</span></div><div class="sys-nav"></div>`;
    if (this.opts.after) this.opts.after(dev, s, size);
    document.querySelector('.dev-cap').textContent = `${size.w} × ${s.tall ? 'auto' : size.h} dp · ${size.cls[0].toUpperCase() + size.cls.slice(1)} · ${dark ? 'dark' : 'light'}`;
    this.fit();
  },
  fit() {
    const size = SIZES[this.state.w], dev = document.querySelector('.device');
    const h = this.state.tall ? dev.offsetHeight : size.h;
    const k = Math.min(1, (document.documentElement.clientWidth - 32) / size.w);
    dev.style.transform = k < 1 ? `scale(${k})` : '';
    const wrap = document.querySelector('.dev-wrap'); wrap.style.width = size.w * k + 'px'; wrap.style.height = h * k + 'px';
  },
};
function sysIcons() {
  return '<svg width="16" height="12" viewBox="0 0 16 12" fill="currentColor"><path d="M8 11.5 0.3 3.6A11 11 0 0 1 15.7 3.6Z"/></svg>' +
    '<svg width="24" height="12" viewBox="0 0 24 12"><rect x=".5" y=".5" width="20" height="11" rx="3" fill="none" stroke="currentColor" opacity=".5"/><rect x="2" y="2" width="15" height="8" rx="1.6" fill="currentColor"/><rect x="21.5" y="4" width="1.6" height="4" rx=".8" fill="currentColor" opacity=".5"/></svg>';
}
/** The app's words in the prototype's language: loc('Queue', '队列'). Chinese = values-zh-rCN. */
function loc(en, zh) { return Proto.state.lang === 'zh' ? zh : en; }
