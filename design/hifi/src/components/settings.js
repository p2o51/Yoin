/* Settings, v4 IA (ui/settings: SettingsScreen, SettingsComponents, SettingsColors; PR #300) + Music links
   (MusicLinksPage.kt, branch claude/open-in-yoin). Page = surfaceContainer, rows = surfaceBright (both themes).
   Big title displaySmall (40 above, 36 below); group headers labelLarge in primary, 8 to the rows; segmented rows
   2 apart (first 20 / 4, middle 4, last 4 / 20, lone 20; selected 28), min 72, 16 / 14 padding, 40 icon box with a
   24 line icon, titleMedium + bodyMedium summary; groups 28 apart. Compact / Medium: no trailing chevrons.
   Service colours (the only non-token colours): HCT hue / chroma, tones per role — see SVC. */
const SVC = {
  subsonic: { name: 'Subsonic', glyph: 'cloud', t: { 20: '#00315B', 30: '#0E487C', 40: '#2F6196', 80: '#A1C9FF', 90: '#D3E4FF' } },
  spotify: { name: 'Spotify', glyph: 'spotify', t: { 20: '#00390F', 30: '#185121', 40: '#326A37', 80: '#99D597', 90: '#B4F2B1' } },
  apple: { name: 'Apple Music', glyph: 'apple', t: { 20: '#5F1225', 30: '#7D293B', 40: '#9B4051', 80: '#FFB2BC', 90: '#FFD9DD' } },
};
const ACCOUNTS = [
  { svc: 'apple', name: 'Apple Music', init: 'D', shape: 'Ghostish', scope: 'Song and artist names' },
  { svc: 'spotify', name: 'dana.k', init: 'D', shape: 'Cookie6Sided', scope: 'Connection and devices' },
  { svc: 'subsonic', name: 'dana', init: 'D', shape: 'Clover4Leaf', host: 'music.example.net', scope: 'Quality and downloads' },
  { svc: 'subsonic', name: 'demo', init: 'D', shape: 'Gem', host: '127.0.0.1', scope: 'Quality and downloads', active: true },
];
/** CSS vars for a service in a theme: --sc container, --son onContainer, --sa accent, --soa onAccent, --si iconContainer, --sic iconContent. */
function svcVars(svc, dark) {
  const t = SVC[svc].t;
  return dark ? `--sc:${t[30]};--son:${t[90]};--sa:${t[80]};--soa:${t[20]};--si:${t[80]};--sic:${t[30]}` : `--sc:${t[90]};--son:${t[30]};--sa:${t[40]};--soa:#fff;--si:${t[90]};--sic:${t[30]}`;
}
function svcGlyph(g, s) {
  if (g === 'spotify') return `<svg class="ic" width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M5.5 9.2c4.3-1.4 9-1 13 1.2M6.4 12.6c3.5-1 7.3-.7 10.4 1M7.3 15.8c2.7-.7 5.5-.5 7.9.8"/></svg>`;
  if (g === 'apple') return `<svg class="ic" width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M9 17.2V6.6l9.5-2v10.6"/><circle cx="7" cy="17.2" r="2.1"/><circle cx="16.4" cy="15.2" r="2.1"/><path d="M9 9.2l9.5-2"/></svg>`;
  return ic(g, s);
}
/** Account avatar: the account's MaterialShape in the service accent, the service glyph nudged up-left, a badge
   (iconContainer) with the initial at the bottom end, cut out by a 2dp ring in the surface colour behind. */
function svcAvatar(a, px, dark) {
  const b = Math.round(px * .46);
  return `<span class="sv-av" style="--av:${px}px;--bd:${b}px;${svcVars(a.svc, dark)}">${shapeSvg(a.shape, 'sv-sh')}<span class="sv-g">${svcGlyph(SVC[a.svc].glyph, Math.round(px * .54))}</span><span class="sv-b">${a.init}</span></span>`;
}
function accountCard(a, dark) {
  const sub = (a.host ? `<span>${a.host}</span>` : '') + `<span class="${a.host ? 'dim' : ''}">${a.scope}</span>`;
  return `<div class="acard ${a.active ? 'on' : ''}" style="${svcVars(a.svc, dark)}">${svcAvatar(a, 48, dark)}<span class="ac-more">${ic('more_vertical', 20)}</span><span class="sp"></span>` +
    `<b class="ell">${a.name}</b><span class="ac-sub">${sub}</span><span class="ac-pill">Settings ›</span></div>`;
}
function accountRow(a, dark, sel) {
  return `<div class="srow acc ${sel ? 'sel' : ''}" style="${svcVars(a.svc, dark)}">${svcAvatar(a, 40, dark)}<span class="col"><b class="ell">${a.name}</b><span class="ell">${a.host ? `${a.host}<i></i>` : ''}${a.scope}</span>${a.active ? '<span class="inuse">In use</span>' : ''}</span>${ic('more_vertical', 20)}</div>`;
}
function srow(icon, title, sub, { trail = '', sub2 = '', sel = false, chevron = false } = {}) {
  return `<div class="srow ${sel ? 'sel' : ''}"><span class="sr-ic">${ic(icon, 24)}</span><span class="col"><b>${title}</b>${sub ? `<span>${sub}</span>` : ''}${sub2 ? `<span>${sub2}</span>` : ''}</span>${trail}${chevron ? ic('chevron_right', 24, 'sr-ch') : ''}</div>`;
}
function sgroup(title, rows, end = '') { return `<section class="sgrp">${title ? `<h3><span>${title}</span>${end ? `<small>${end}</small>` : ''}</h3>` : ''}<div class="sseg">${rows.join('')}</div></section>`; }
const meta = (...g) => g.map((x, i) => (i ? `<i>${x}</i>` : x)).join('');
function rootGroups({ chevron = false, sel = '', links = true } = {}) {
  const o = (k) => ({ chevron, sel: sel === k });
  return sgroup('Features and appearance', [srow('sparkle', 'AI features', 'Song info, Ask Gemini, translation and Memory titles', o('ai')), srow('palette', 'Appearance', meta('Tide line', 'Match screen'), o('look')),
    srow('link', 'Music links', links ? 'Clipboard detection on' : 'Clipboard detection off', o('links'))]) +
    sgroup('Sync and storage', [srow('cloud', 'Sync and backups', 'Off', o('sync')), srow('storage', 'Storage', 'Playback cache 499.6 MB', o('storage'))]) +
    sgroup('Help and about', [srow('comment', 'Help and feedback', '3 of 34 tips read', o('help')), srow('info', 'About Yoin', 'Version 0.6.3', o('about'))]);
}
function sHead(title, { back = true } = {}) { return `${back ? `<span class="ad-back s-back">${ic('back', 22)}</span>` : ''}<h1 class="s-title">${title}</h1>`; }
function settingsRoot(dark, { links = true } = {}) {
  return `${sHead('Settings')}<section class="sgrp"><h3><span>Accounts</span><small>4 of 5</small></h3><div class="acards">${ACCOUNTS.map((a) => accountCard(a, dark)).join('')}<div class="acard add">${ic('add', 28)}<span>Add</span></div></div></section>${rootGroups({ links })}`;
}
function settingsList(dark, sel, links) {
  return `${sHead('Settings')}<section class="sgrp"><h3><span>Accounts</span><small>4 of 5</small></h3><div class="sseg">${ACCOUNTS.map((a) => accountRow(a, dark, sel === 'account' && a.active)).join('')}${srow('add', 'Add account', '')}</div></section>${rootGroups({ chevron: true, sel, links })}`;
}
function switchEl(on) { return `<span class="sw ${on ? 'on' : ''}"><i>${on ? ic('check', 16) : ''}</i></span>`; }
function tensorDieSvg() {
  const N = 25, m = 11, span = 100 - m * 2, step = span / (N - 1), r = step * 0.27;
  let dots = '';
  for (let row = 0; row < N; row++) for (let col = 0; col < N; col++) {
    if (col >= 6 && col <= 18 && row >= 7 && row <= 17) continue;
    dots += `<circle cx="${(m + col * step).toFixed(2)}" cy="${(m + row * step).toFixed(2)}" r="${r.toFixed(2)}" fill="rgba(255,255,255,.88)"/>`;
  }
  return `<svg class="t-die-svg" viewBox="0 0 100 100" aria-hidden="true">${dots}</svg>`;
}
function settingsPage(page, dark, { links = true, labs = 'ready' } = {}) {
  if (page === 'labs') {
    // LabsPage.kt / SingLabsRow.kt + strings_settings.xml (values / values-zh-rCN), 0.6.3 (33)
    const sing = labs !== 'was';
    const rows = [
      srow('sparkle', 'Gemini Nano', loc('Ready on this device', '本机已就绪')),
      ...(sing ? [srow('mic', 'Lyra Sing', loc('Ready on this device (12.2 MB)', '本机已就绪（12.2 MB）'), {
        sub2: loc('Optimized for Google Tensor G6', '已为 Google Tensor G6 优化'),
        trail: `<button type="button" class="s-tbtn">${loc('Remove', '删除')}</button>`,
      })] : []),
      srow('insights', loc('Gemini Nano diagnostics', 'Gemini Nano 诊断'), loc('Check model availability and run a test', '查看本机模型状态与运行测试'), { chevron: true }),
      srow('music_note', loc('Lyric Cards', '歌词卡片'), loc('Show lyrics picked on this device on Home, and tap to play', '在首页显示本机挑出的歌词，轻点播放'), { trail: switchEl(true) }),
      srow('light_mode', loc('Home greeting', '首页问候'), loc('Generate greetings based on recent activity', '根据最近状态生成问候语'), { trail: switchEl(true) }),
      srow('sparkle', loc('Suggested questions', '推荐追问'), loc('Follow-up questions from About', '在「关于」里根据正文给出追问'), { trail: switchEl(true) }),
    ];
    return `${sHead('Yoin Labs')}<p class="s-intro">${loc("Experimental features we're trying out. They may change or be removed.", '正在尝试的实验性功能，之后可能会调整或移除。')}</p>${sgroup(loc('Experimental features', '实验功能'), rows)}`;
  }
  if (page === 'tensor') {
    return `<div class="t-egg"><span class="ad-back t-back">${ic('back', 22)}</span>` +
      `<div class="t-die">${tensorDieSvg()}<span class="t-code">G6</span></div>` +
      `<b>Google Tensor G6</b><small>Lyra Sing</small></div>`;
  }
  if (page === 'links') {
    // strings_open_in.xml (values / values-zh-rCN), Rebecca 2026-11-24
    return `${sHead(loc('Music links', '音乐链接'))}<section class="sgrp"><div class="sseg">${srow('link', loc('Detect music links in clipboard', '识别剪贴板中的音乐链接'), loc('Checks the clipboard only when you open Yoin. Only looks for music links; nothing else is uploaded or saved.', '仅在打开 Yoin 时检查剪贴板，只识别音乐链接，其他内容不会上传或保存。'), { trail: switchEl(links) })}</div></section>` +
      `<section class="sgrp"><div class="sseg">${srow('share', loc('Open in Yoin', '在 Yoin 中打开'), loc('Choose "Open in Yoin" from the share menu in other apps to send a song or link straight to Yoin.', '在其他应用的分享菜单里选择「在 Yoin 中打开」，即可直接传入歌曲或链接。'))}${srow('search', loc('Open or search across services', '跨服务打开与搜索'), loc('Links from a service you\'re signed into open directly; links from other services search in your current service.', '已登录对应服务的链接会直接打开，未登录对应服务时会在你当前使用的服务中搜索。'))}</div></section>`;
  }
  if (page === 'sync') {
    return `${sHead('Sync and backups')}${sgroup('', [srow('devices', 'Cloud sync', 'On<br>Synced 5 min ago'), srow('reviews', 'NeoDB', 'Sync album ratings and reviews'), srow('playlist', 'Playlist cloud backup', '')])}`;
  }
  const a = ACCOUNTS[3];
  return `<span class="ad-back s-back">${ic('back', 22)}</span><div class="s-hero">${svcAvatar(a, 96, dark)}<h1>${a.name}</h1><span><i class="mut">Subsonic</i>${a.host}</span></div>` +
    `<section class="sgrp"><h3><span>Server</span></h3><div class="sseg"><div class="srow form"><label><small>Server address</small>http://127.0.0.1:4533</label><label><small>Username</small>demo</label><label><small>Password</small>••••••••${ic('visibility', 18)}</label><span class="s-ok">${ic('check', 18)}Server found</span><span class="s-btns"><button class="ob">Test</button><button class="fb">Save</button></span></div></div></section>` +
    sgroup('Memories', [srow('forward', 'Transfer Memories', 'Copy notes, ratings, and reviews to another account without changing the original')]) +
    sgroup('Playback', [srow('equalizer', 'Streaming quality', 'Wi‑Fi Auto, mobile data 320 kbps'), srow('volume_up', 'Volume leveling', 'By album')]) +
    sgroup('Storage', [srow('download', 'Downloads', 'Lossless  1.2 GB on this device')]);
}
