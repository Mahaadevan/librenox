'use strict';
/* MC Host web UI. Server-provided text is only ever inserted as text nodes (never innerHTML). */
const $ = (s, r = document) => r.querySelector(s);
const S = { servers: [], by: {}, cur: null, logs: [], last: 0, connected: false, dead: false };
let view = null;
const cs = () => S.by[S.cur];

// ---------------------------------------------------------------- helpers
function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (k === 'class') el.className = v;
    else if (k === 'on') for (const [ev, fn] of Object.entries(v)) el.addEventListener(ev, fn);
    else if (v === true) el.setAttribute(k, '');
    else if (v !== false && v != null) el.setAttribute(k, v);
  }
  for (const kid of kids.flat(3)) if (kid != null && kid !== false) el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  return el;
}
const card = (title, ...kids) => h('section', { class: 'card' }, title ? h('h2', {}, title) : null, ...kids);
const kv = (label, el) => h('div', { class: 'row' }, h('span', { class: 'k' }, label), el);
const hms = (sec) => { sec = Math.max(0, sec | 0); return [sec / 3600 | 0, (sec % 3600) / 60 | 0, sec % 60].map((n) => String(n).padStart(2, '0')).join(':'); };
const fmtBytes = (n) => n >= 1 << 30 ? (n / (1 << 30)).toFixed(1) + ' GB' : n >= 1 << 20 ? (n / (1 << 20)).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB';
const fmtNum = (n) => n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'k' : String(n);
const running = (s) => s && (s.state === 'online' || s.state === 'starting');
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const iconUrl = (s) => `/api/icon?id=${encodeURIComponent(s.id)}&v=${s.icon}`;
const stKind = (st) => st === 'online' ? 'online' : st === 'offline' ? 'off' : 'wait';
const stText = { online: '● ONLINE', offline: '○ OFFLINE', starting: '◐ STARTING', preparing: '◐ PREPARING', stopping: '◐ STOPPING' };
const stCls = { online: 'c-green', offline: 'c-red', starting: 'c-yellow', preparing: 'c-yellow', stopping: 'c-yellow' };
const SW = { paper: ['Paper', 'Fast, plugin support (recommended)'], purpur: ['Purpur', 'Paper fork with extra options'], folia: ['Folia', 'Multithreaded Paper (big servers)'], vanilla: ['Vanilla', 'Official Mojang server'], fabric: ['Fabric', 'Lightweight mod loader'], custom: ['Custom jar', 'Upload your own server.jar (Forge etc.)'] };

function toast(msg, type = 'info') {
  const t = h('div', { class: 'toast ' + type }, msg);
  $('#toasts').append(t);
  setTimeout(() => t.classList.add('out'), 3300);
  setTimeout(() => t.remove(), 3700);
}

/** All per-server endpoints get ?id=<current server> unless global:true. */
async function api(path, { method = 'GET', body, quiet, global, raw, headers } = {}) {
  if (!global && S.cur) path += (path.includes('?') ? '&' : '?') + 'id=' + encodeURIComponent(S.cur);
  const opt = { method, headers: { ...(headers || {}) } };
  if (raw !== undefined) { opt.body = raw; opt.headers['X-MCHT'] = '1'; opt.headers['Content-Type'] = 'application/octet-stream'; }
  else if (method !== 'GET') { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body || {}); }
  let r;
  try { r = await fetch(path, opt); } catch (e) { if (!quiet) toast('Launcher not reachable', 'err'); throw e; }
  let data = null;
  try { data = await r.json(); } catch { /* not json */ }
  if (!r.ok) { const msg = (data && data.error) || 'HTTP ' + r.status; if (!quiet) toast(msg, 'err'); throw new Error(msg); }
  return data;
}
async function act(btn, fn, okMsg) {
  if (btn) btn.disabled = true;
  try { const r = await fn(); if (okMsg) toast(okMsg, 'ok'); return r; } catch { /* toasted */ } finally { if (btn) btn.disabled = false; }
}
function copy(text) {
  const done = () => toast('Copied: ' + text, 'ok');
  if (navigator.clipboard) navigator.clipboard.writeText(text).then(done, () => fb(text, done)); else fb(text, done);
}
function fb(text, done) { const t = h('textarea', { style: 'position:fixed;opacity:0' }); t.value = text; document.body.append(t); t.select(); try { document.execCommand('copy'); done(); } catch { /* ignore */ } t.remove(); }
const go = (r) => { location.hash = '#/' + r; };
const goS = (page, id = S.cur) => go(`s/${id}/${page}`);
const readFile = (file) => new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(r.result); r.onerror = rej; r.readAsDataURL(file); });
const pickFile = (accept) => new Promise((res) => { const i = h('input', { type: 'file', accept }); i.onchange = () => res(i.files[0] || null); i.click(); });

// ---------------------------------------------------------------- dialogs & popup menus
const ov = () => $('#overlay');
function closeModal() { ov().classList.add('hidden'); ov().replaceChildren(); ov().onclick = null; }
function openModal(node, dismiss) { ov().replaceChildren(node); ov().classList.remove('hidden'); ov().onclick = (e) => { if (e.target === ov() && dismiss) dismiss(); }; }
function dialog({ title, body, buttons, cls = '' }) {
  return new Promise((resolve) => {
    const done = (v) => { closeModal(); resolve(v); };
    openModal(h('div', { class: 'dialog ' + cls }, h('h3', {}, title), h('div', { style: 'margin-bottom:18px;color:#c3cad8' }, body),
      h('div', { class: 'actions' }, buttons.map((b) => h('button', { class: 'btn ' + (b.cls || ''), on: { click: () => done(b.value) } }, b.label)))), () => done(buttons[0].value));
  });
}
const confirmBox = (title, text, ok = 'Confirm', danger = false) => dialog({ title, body: [text], buttons: [{ label: 'Cancel', value: false }, { label: ok, cls: danger ? 'red' : 'green', value: true }] });
const eulaBox = () => dialog({ title: 'Minecraft EULA', body: ['By hosting a server you must accept the Minecraft EULA: ', h('a', { href: 'https://aka.ms/MinecraftEULA', target: '_blank', rel: 'noopener noreferrer', style: 'color:var(--blue)' }, 'https://aka.ms/MinecraftEULA')], buttons: [{ label: 'Cancel', value: false }, { label: 'I accept', cls: 'green', value: true }] });
function promptBox(title, label, value = '', ok = 'OK') {
  return new Promise((resolve) => {
    const inp = h('input', { value, autocomplete: 'off', spellcheck: 'false' });
    const done = (v) => { closeModal(); resolve(v); };
    inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') done(inp.value); });
    openModal(h('div', { class: 'dialog' }, h('h3', {}, title), h('div', { class: 'field' }, h('label', {}, label), inp),
      h('div', { class: 'actions' }, h('button', { class: 'btn', on: { click: () => done(null) } }, 'Cancel'), h('button', { class: 'btn green', on: { click: () => done(inp.value) } }, ok))), () => done(null));
    inp.focus(); inp.select();
  });
}

let openMenu = null;
function closeMenus() { if (openMenu) { openMenu.remove(); openMenu = null; } }
document.addEventListener('click', closeMenus);
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { closeMenus(); if (!ov().classList.contains('hidden')) ov().click(); } });
function popupMenu(anchor, items, above = false) {
  closeMenus();
  const build = (list, sub) => h('div', { class: 'pmenu' + (sub ? ' sub' : ''), on: { click: (e) => e.stopPropagation() } },
    list.map((it) => {
      if (it.head) return h('div', { class: 'pm-item head' }, it.head);
      const el = h('div', { class: 'pm-item' + (it.on ? ' on' : '') }, h('span', {}, it.icon ? [it.icon, ' ', it.label] : it.label), it.children ? h('span', {}, '▸') : it.on ? h('span', {}, '✓') : null);
      if (it.children) el.append(build(it.children, true)); else el.addEventListener('click', () => { closeMenus(); it.run(); });
      return el;
    }));
  const m = build(items, false);
  document.body.append(m);
  const r = anchor.getBoundingClientRect();
  m.style.left = Math.max(8, Math.min(r.left, innerWidth - m.offsetWidth - 8)) + 'px';
  if (above) m.style.bottom = (innerHeight - r.top + 6) + 'px'; else m.style.top = (r.bottom + 6) + 'px';
  openMenu = m;
}

// ---------------------------------------------------------------- logs
function logLine(e) {
  const cls = ['ln', 'k-' + e.kind];
  if (e.lvl) cls.push('lvl-' + e.lvl);
  if (e.kind === 'srv') {
    if (/^\S+ joined the game$/.test(e.text)) cls.push('join'); else if (/^\S+ left the game$/.test(e.text)) cls.push('leave'); else if (/^(\[Not Secure\] )?<[^>]+> /.test(e.text)) cls.push('chat');
  }
  const tag = { host: 'HOST', err: 'ERROR', tun: 'TUNL' }[e.kind] || (e.lvl && e.lvl !== 'TRACE' ? e.lvl : '');
  return h('div', { class: cls.join(' ') }, h('span', { class: 'ts' }, e.ts), h('span', { class: 'tag' }, tag), h('span', { class: 'msg' }, e.text));
}
function appendLogs(box, entries, max, stick) {
  for (const e of entries) box.append(logLine(e));
  while (box.childElementCount > max) box.firstElementChild.remove();
  if (stick) box.scrollTop = box.scrollHeight;
}

// ---------------------------------------------------------------- small widgets
function spark(values, max, cls = '') {
  const NS = 'http://www.w3.org/2000/svg', W = 300, H = 64;
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`); svg.setAttribute('preserveAspectRatio', 'none'); svg.setAttribute('class', 'spark ' + cls);
  const n = Math.max(values.length, 2), m = Math.max(max, 1e-6);
  const pts = values.map((v, i) => [i / (n - 1) * W, H - 3 - Math.min(1, Math.max(0, v) / m) * (H - 6)]);
  if (pts.length < 2) return svg;
  const d = pts.map((p, i) => (i ? 'L' : 'M') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' ');
  const a = document.createElementNS(NS, 'path'); a.setAttribute('class', 'a'); a.setAttribute('d', d + ` L${pts[pts.length - 1][0]} ${H} L0 ${H} Z`);
  const l = document.createElementNS(NS, 'path'); l.setAttribute('class', 'l'); l.setAttribute('d', d);
  svg.append(a, l);
  return svg;
}
const COL = { 0: '#000', 1: '#0000AA', 2: '#00AA00', 3: '#00AAAA', 4: '#AA0000', 5: '#AA00AA', 6: '#FFAA00', 7: '#AAAAAA', 8: '#555555', 9: '#5555FF', a: '#55FF55', b: '#55FFFF', c: '#FF5555', d: '#FF55FF', e: '#FFFF55', f: '#FFFFFF' };
function mcText(text) {
  const out = []; let color = '', st = {}, buf = '';
  const flush = () => { if (!buf) return; const s = h('span', {}, buf); if (color) s.style.color = color; if (st.l) s.style.fontWeight = '800'; if (st.o) s.style.fontStyle = 'italic'; s.style.textDecoration = [st.n ? 'underline' : '', st.m ? 'line-through' : ''].join(' '); out.push(s); buf = ''; };
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if ((c === '§' || c === '&') && i + 1 < text.length && /[0-9a-fk-orA-FK-OR]/.test(text[i + 1])) {
      flush(); const k = text[++i].toLowerCase();
      if (COL[k]) { color = COL[k]; st = {}; } else if (k === 'r') { color = ''; st = {}; } else st[k] = true;
    } else buf += c;
  }
  flush(); return out;
}
function motdBox(s, text) {
  const lines = String(text).split('\n');
  return h('div', { class: 'motd' }, h('img', { src: iconUrl(s), alt: '' }), h('div', {}, h('div', { class: 'l' }, mcText(lines[0] || '')), h('div', { class: 'l' }, mcText(lines[1] || ''))));
}
function playerMenu(anchor, name) {
  const c = (cmd) => () => api('/api/command', { method: 'POST', body: { cmd } }).then(() => toast('Sent: ' + cmd, 'ok')).catch(() => {});
  popupMenu(anchor, [{ head: name }, { label: 'Make operator', run: c('op ' + name) }, { label: 'Remove operator', run: c('deop ' + name) },
    { label: 'Game mode', children: ['survival', 'creative', 'adventure', 'spectator'].map((g) => ({ label: g, run: c(`gamemode ${g} ${name}`) })) },
    { label: 'Kick', run: c('kick ' + name) },
    { label: 'Ban', run: async () => { if (await confirmBox('Ban ' + name + '?', 'They cannot join until pardoned.', 'Ban', true)) c('ban ' + name)(); } }]);
}
const avatarColor = (n) => `hsl(${[...n].reduce((a, ch) => a + ch.charCodeAt(0) * 7, 0) % 360} 60% 62%)`;
function playerList(s, empty) {
  if (!s.players.length) return [h('div', { class: 'empty' }, empty || (s.state === 'online' ? 'Nobody is online yet.' : 'Start the server to see players.'))];
  return s.players.map((n) => h('div', { class: 'item' }, h('div', { class: 'avatar', style: 'background:' + avatarColor(n) }, n[0].toUpperCase()), h('div', { class: 'grow' }, h('b', {}, n)),
    h('button', { class: 'btn small', disabled: !/^[\w.\-]{1,32}$/.test(n), on: { click: (e) => { e.stopPropagation(); playerMenu(e.currentTarget, n); } } }, 'Manage ▾')));
}

// ---------------------------------------------------------------- server actions
async function toggleServer(s) {
  if (s.state === 'offline') {
    if (!s.eula) { if (!(await eulaBox())) return; try { await api('/api/eula', { method: 'POST', body: { accept: true } }); } catch { return; } }
    api('/api/start', { method: 'POST' }).catch(() => {});
  } else if (running(s)) {
    if (await confirmBox('Stop "' + s.name + '"?', s.state === 'starting' ? 'The server is still starting.' : 'Players will be disconnected and the world saved.', 'Stop server', true)) api('/api/stop', { method: 'POST' }).catch(() => {});
  }
}
const withServer = (id, fn) => { const old = S.cur; S.cur = id; try { return fn(); } finally { S.cur = old; } };

// ---------------------------------------------------------------- HOME
function homeView() {
  const grid = h('div', { class: 'servers' });
  let sig = '';
  const draw = (list) => {
    const next = list.map((s) => [s.id, s.name, s.state, s.players.length, s.icon, s.version, s.software, s.task, s.port, s.eula].join('|')).join('~');
    if (next === sig) return; sig = next;
    grid.replaceChildren(...list.map((s) => {
      const btn = h('button', { class: 'btn ' + (s.state === 'offline' ? 'green' : 'red'), disabled: ['preparing', 'stopping'].includes(s.state), on: { click: (e) => { e.stopPropagation(); withServer(s.id, () => toggleServer(s)); } } }, s.state === 'offline' ? '▶ Start' : '■ Stop');
      return h('div', { class: 'scard', on: { click: () => goS('overview', s.id) } },
        h('div', { class: 'top' }, h('img', { class: 'big', src: iconUrl(s), alt: '' }),
          h('div', {}, h('h3', {}, s.name), h('div', { class: 'sub' }, h('span', { class: 'tag2' }, SW[s.software] ? SW[s.software][0] : s.software), h('span', {}, s.version || 'newest'), h('span', {}, s.memory)))),
        h('div', { class: 'foot' }, h('span', { class: 'st ' + stCls[s.state] }, stText[s.state] + (s.state === 'online' ? ` · ${s.players.length}/${s.max_players}` : '')), btn),
        h('div', { class: 'sub' }, h('span', { class: 'mono', style: 'cursor:default' }, `${s.lan}:${s.port}`), s.task ? h('span', { class: 'c-yellow' }, s.task) : null));
    }), h('div', { class: 'scard add', on: { click: addServerDialog } }, h('div', { class: 'plus' }, '＋'), h('b', {}, 'Add a server'), h('small', {}, 'Paper, Purpur, Vanilla, Fabric…')));
  };
  return {
    el: h('div', { class: 'page' }, h('div', { class: 'head-row' }, h('div', {}, h('h1', {}, 'Your servers'), h('p', { class: 'lead', style: 'margin:0' }, 'Create, start and manage any number of Minecraft servers. Each has its own version, world and port.')),
      h('button', { class: 'btn green', on: { click: addServerDialog } }, '＋ Add server')), grid),
    update(_s, all) { draw(all); },
  };
}

async function addServerDialog() {
  let sw = 'paper', icon = '', versions = [];
  const name = h('input', { placeholder: 'My Survival Server', autocomplete: 'off', maxlength: 40 });
  const ver = h('select', {}, h('option', { value: '' }, 'Latest (pinned on first start)'));
  const mem = h('input', { value: '2G' });
  const eula = h('input', { type: 'checkbox' });
  const iconImg = h('img', { alt: '', style: 'width:64px;height:64px;border-radius:8px;image-rendering:pixelated;background:#0d1015;display:none' });
  const cards = h('div', { class: 'swcards' });
  const drawCards = () => cards.replaceChildren(...Object.entries(SW).map(([k, [t, d]]) => h('div', { class: 'swcard' + (k === sw ? ' on' : ''), on: { click: () => { sw = k; drawCards(); loadVersions(); } } }, h('b', {}, t), h('small', {}, d))));
  async function loadVersions() {
    ver.replaceChildren(h('option', { value: '' }, sw === 'custom' ? 'n/a (use your own jar)' : 'Loading…')); ver.disabled = sw === 'custom';
    if (sw === 'custom') return;
    try {
      versions = await api('/api/software/versions?software=' + sw, { global: true, quiet: true });
      ver.replaceChildren(h('option', { value: '' }, 'Latest (pinned on first start)'), ...versions.slice(0, 80).map((v) => h('option', { value: v }, v)));
    } catch { ver.replaceChildren(h('option', { value: '' }, 'Latest (version list unavailable offline)')); }
  }
  const create = h('button', { class: 'btn green' }, 'Create server');
  create.addEventListener('click', async () => {
    if (!name.value.trim()) { toast('Give the server a name.', 'err'); return; }
    if (!eula.checked) { toast('Please accept the Minecraft EULA.', 'err'); return; }
    create.disabled = true;
    try {
      const r = await api('/api/servers/create', { method: 'POST', global: true, body: { name: name.value, software: sw, version: ver.value, memory: mem.value, eula: true, icon } });
      closeModal(); toast('Server created.', 'ok'); await pollStatus(); goS('overview', r.id);
    } catch { create.disabled = false; }
  });
  openModal(h('div', { class: 'dialog wide' }, h('h3', {}, 'Add a server'),
    h('div', { class: 'field' }, h('label', {}, 'Server name'), name),
    h('div', { class: 'field' }, h('label', {}, 'Software'), cards),
    h('div', { class: 'grid2', style: 'margin-bottom:0' },
      h('div', { class: 'field' }, h('label', {}, 'Minecraft version'), ver),
      h('div', { class: 'field' }, h('label', {}, 'Memory (RAM)'), mem)),
    h('div', { class: 'field' }, h('label', {}, 'Icon (optional)'), h('div', { class: 'iconpick' }, iconImg,
      h('button', { class: 'btn small', on: { click: async () => { const f = await pickFile('image/*'); if (f) { icon = await readFile(f); iconImg.src = icon; iconImg.style.display = ''; } } } }, 'Choose image…'),
      h('span', { class: 'hint c-muted' }, 'Otherwise the icon inside the downloaded server file is used, or one is generated.'))),
    h('label', { style: 'display:flex;gap:10px;align-items:center;margin:8px 0 16px;cursor:pointer' }, eula, h('span', {}, 'I accept the ', h('a', { href: 'https://aka.ms/MinecraftEULA', target: '_blank', rel: 'noopener noreferrer', style: 'color:var(--blue)' }, 'Minecraft EULA'))),
    h('div', { class: 'actions' }, h('button', { class: 'btn', on: { click: closeModal } }, 'Cancel'), create)), closeModal);
  drawCards(); loadVersions(); name.focus();
}

// ---------------------------------------------------------------- OVERVIEW
function overviewView() {
  const v = {};
  const val = (k, cls = '') => (v[k] = h('span', { class: 'v ' + cls }));
  const cp = (k) => { const e = val(k, 'mono'); e.title = 'Click to copy'; e.addEventListener('click', () => e.dataset.copy && copy(e.dataset.copy)); return e; };
  const tile = (icon, title, sub, fn) => h('button', { class: 'tile', on: { click: fn } }, h('span', { class: 'ti' }, icon), h('b', {}, title), h('small', {}, sub));
  const players = h('div', { class: 'players' });
  const motd = h('div'); const mini = h('div', { class: 'term mini' });
  const charts = { cpu: h('div'), ram: h('div'), pl: h('div') };
  const hero = { img: h('img', { alt: '' }), name: h('h1'), st: h('div', { class: 'st' }), addr: h('div', { class: 'addr', title: 'Click to copy' }), sub: h('div', { class: 'c-muted' }) };
  hero.addr.addEventListener('click', () => hero.addr.dataset.copy && copy(hero.addr.dataset.copy));
  let sig = '', timer = 0;
  const el = h('div', { class: 'page' },
    h('div', { class: 'hero' }, hero.img, h('div', {}, hero.name, hero.st, hero.addr, hero.sub)),
    h('div', { class: 'tiles' },
      tile('💾', 'Backup now', 'Zip the world', (e) => act(e.currentTarget, () => api('/api/backup', { method: 'POST' }), 'Backup started')),
      tile('⌨', 'Console', 'Live log & commands', () => goS('console')), tile('⚙', 'Options', 'server.properties', () => goS('options')),
      tile('👥', 'Players', 'Whitelist, ops, bans', () => goS('players')), tile('🌐', 'Public address', 'Let friends join', () => goS('network'))),
    h('div', { class: 'grid2' },
      card('Server', kv('Status', val('state')), kv('Software', val('sw')), kv('Memory', val('memory')), kv('Players', val('players')), kv('Plugins / mods', val('ext')), kv('Uptime', val('uptime'))),
      card('Connect', kv('LAN (same Wi-Fi)', cp('lan')), kv('This computer', cp('local')), kv('Public address', cp('pub')), kv('Tunnel', val('tunnel')), kv('Accounts', val('acct')), kv('Whitelist', val('wl')))),
    h('div', { class: 'grid2' }, card('CPU', h('div', { class: 'metric' }, h('b', { id: 'mCpu' }, '—'), h('span', { class: 'c-muted' }, 'last 30 min')), charts.cpu),
      card('Memory used', h('div', { class: 'metric' }, h('b', { id: 'mRam' }, '—'), h('span', { class: 'c-muted' }, 'of ' + (cs() ? cs().memory : ''))), charts.ram)),
    h('div', { class: 'grid2' }, card('Players online', players), card('Server list preview', motd, h('div', { style: 'height:12px' }), h('h2', {}, 'Recent activity'), mini)));
  async function loadStats() {
    try {
      const st = await api('/api/stats', { quiet: true });
      charts.cpu.replaceChildren(spark(st.map((x) => x[1]), 100, 'g')); charts.ram.replaceChildren(spark(st.map((x) => Math.max(0, x[2])), Math.max(...st.map((x) => x[2]), 512), 'y'));
      const last = st[st.length - 1]; if (last) { $('#mCpu', el).textContent = last[1] + ' %'; $('#mRam', el).textContent = last[2] >= 0 ? last[2] + ' MB' : 'n/a'; }
    } catch { /* ignore */ }
  }
  return {
    el, init() { appendLogs(mini, S.logs.slice(-12), 12, true); loadStats(); timer = setInterval(loadStats, 10000); }, destroy() { clearInterval(timer); },
    onLog(e) { appendLogs(mini, e, 12, true); },
    update(s) {
      hero.img.src = iconUrl(s); hero.name.textContent = s.name; hero.st.textContent = stText[s.state]; hero.st.className = 'st ' + stCls[s.state];
      const addr = s.public || `${s.lan}:${s.port}`; hero.addr.textContent = addr; hero.addr.dataset.copy = addr; hero.sub.textContent = s.public ? 'Public address (share this)' : 'LAN address (same Wi-Fi). Set up a tunnel for friends elsewhere.';
      v.state.textContent = stText[s.state]; v.state.className = 'v ' + stCls[s.state];
      v.sw.textContent = `${SW[s.software] ? SW[s.software][0] : s.software} ${s.version || 'newest'}`; v.memory.textContent = s.memory;
      v.players.textContent = `${s.players.length} / ${s.max_players}`; v.ext.textContent = `${s.plugins} / ${s.mods}`; v.uptime.textContent = s.state === 'online' ? hms(s.uptime) : '—';
      v.lan.textContent = `${s.lan}:${s.port}`; v.lan.dataset.copy = v.lan.textContent; v.local.textContent = `localhost:${s.port}`; v.local.dataset.copy = v.local.textContent;
      v.pub.textContent = s.public || '— set up in Network'; v.pub.dataset.copy = s.public || '';
      v.tunnel.textContent = (s.tunnel.provider || 'none') + ' · ' + s.tunnel.status;
      v.acct.textContent = s.online_mode ? 'premium only' : 'offline mode (unsafe public)'; v.acct.className = 'v ' + (s.online_mode ? '' : 'c-red'); v.wl.textContent = s.whitelist ? 'on' : 'off';
      const ns = [s.players.join(','), s.state, s.icon, s.motd].join('|');
      if (ns !== sig) { sig = ns; players.replaceChildren(...playerList(s)); motd.replaceChildren(motdBox(s, s.motd)); }
    },
  };
}

// ---------------------------------------------------------------- CONSOLE
function consoleView() {
  let stick = true;
  const term = h('div', { class: 'term full' });
  term.addEventListener('scroll', () => { stick = term.scrollTop + term.clientHeight >= term.scrollHeight - 30; });
  const input = h('input', { placeholder: 'Type a server command (no slash needed) and press Enter', autocomplete: 'off', spellcheck: 'false' });
  const hist = []; let hi = 0;
  const send = async (cmd) => { if (!cmd.trim()) return; try { await api('/api/command', { method: 'POST', body: { cmd } }); hist.push(cmd); hi = hist.length; } catch { /* toasted */ } };
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { const c = input.value; input.value = ''; send(c); }
    else if (e.key === 'ArrowUp' && hist.length) { hi = Math.max(0, hi - 1); input.value = hist[hi]; e.preventDefault(); }
    else if (e.key === 'ArrowDown') { hi = Math.min(hist.length, hi + 1); input.value = hist[hi] || ''; e.preventDefault(); }
  });
  const q = (c) => () => send(c);
  const quick = h('button', { class: 'btn', on: { click: (e) => { e.stopPropagation(); popupMenu(e.currentTarget, [{ head: 'Quick commands' },
    { label: 'List players', run: q('list') }, { label: 'Save world', run: q('save-all') },
    { label: 'Time', children: [['Day', 'day'], ['Noon', 'noon'], ['Night', 'night'], ['Midnight', 'midnight']].map(([l, t]) => ({ label: l, run: q('time set ' + t) })) },
    { label: 'Weather', children: ['clear', 'rain', 'thunder'].map((w) => ({ label: w, run: q('weather ' + w) })) },
    { label: 'Difficulty', children: ['peaceful', 'easy', 'normal', 'hard'].map((d) => ({ label: d, run: q('difficulty ' + d) })) },
    { label: 'Default game mode', children: ['survival', 'creative', 'adventure', 'spectator'].map((g) => ({ label: g, run: q('defaultgamemode ' + g) })) },
    { label: 'Game rules', children: [['Keep inventory on', 'gamerule keepInventory true'], ['Keep inventory off', 'gamerule keepInventory false'], ['Mob griefing off', 'gamerule mobGriefing false'], ['Daylight cycle off', 'gamerule doDaylightCycle false'], ['Daylight cycle on', 'gamerule doDaylightCycle true']].map(([l, c]) => ({ label: l, run: q(c) })) },
    { label: 'Whitelist', children: [['On', 'whitelist on'], ['Off', 'whitelist off'], ['Reload', 'whitelist reload']].map(([l, c]) => ({ label: l, run: q(c) })) },
    { label: 'Broadcast…', run: async () => { const t = await promptBox('Broadcast', 'Message to all players'); if (t) send('say ' + t); } },
    { label: 'Stop server', run: async () => { if (await confirmBox('Stop the server?', 'Players will be disconnected.', 'Stop', true)) api('/api/stop', { method: 'POST' }).catch(() => {}); } }]); } } }, 'Quick commands ▾');
  return {
    el: h('div', { class: 'page fill' }, h('div', { class: 'toolbar' }, h('h1', { class: 'grow' }, 'Console'), quick, h('button', { class: 'btn ghost', on: { click: () => term.replaceChildren() } }, 'Clear view')), term,
      h('div', { class: 'cmdbar' }, h('span', { class: 'prompt' }, '>'), input, h('button', { class: 'btn green', on: { click: () => { const c = input.value; input.value = ''; send(c); input.focus(); } } }, 'Send'))),
    init() { appendLogs(term, S.logs, 1500, false); term.scrollTop = term.scrollHeight; input.focus(); }, onLog(e) { appendLogs(term, e, 1500, stick); },
  };
}

// ---------------------------------------------------------------- PLAYERS / ACCESS
function playersView() {
  const online = h('div', { class: 'players' });
  let sig = '';
  const listCard = (kind, title, ph, hint) => {
    const list = h('div', { class: 'plist' }); const inp = h('input', { placeholder: ph, autocomplete: 'off' });
    const load = async () => {
      try {
        const l = await api('/api/access?kind=' + kind, { quiet: true });
        const key = kind === 'banned-ips' ? 'ip' : 'name';
        list.replaceChildren(...(l.length ? l.map((e) => h('div', { class: 'pitem' }, h('span', {}, e[key], kind === 'ops' && e.level ? h('small', { class: 'c-muted' }, '  level ' + e.level) : null, kind.startsWith('banned') && e.reason ? h('small', { class: 'c-muted', title: e.reason }, '  ' + String(e.reason).slice(0, 30)) : null),
          h('button', { class: 'x', title: 'Remove', on: { click: async () => { try { await api('/api/access/remove', { method: 'POST', body: { kind, value: e[key] } }); setTimeout(load, 700); } catch { /* toasted */ } } } }, '✕'))) : [h('div', { class: 'empty' }, 'Empty')]));
      } catch { /* ignore */ }
    };
    const add = async () => { const v = inp.value.trim(); if (!v) return; try { await api('/api/access/add', { method: 'POST', body: { kind, value: v } }); inp.value = ''; setTimeout(load, 700); toast('Added ' + v, 'ok'); } catch { /* toasted */ } };
    inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') add(); });
    return { load, el: card(title, hint ? h('p', { class: 'c-muted', style: 'margin:0 0 10px' }, hint) : null, h('div', { class: 'inline' }, inp, h('button', { class: 'btn green', on: { click: add } }, 'Add')), list) };
  };
  const wl = listCard('whitelist', 'Whitelist', 'Player name', 'Only these players can join while the whitelist is on.');
  const ops = listCard('ops', 'Operators', 'Player name', 'Operators can run admin commands.');
  const bp = listCard('banned-players', 'Banned players', 'Player name');
  const bi = listCard('banned-ips', 'Banned IPs', '203.0.113.7');
  const wlSwitch = h('input', { type: 'checkbox' });
  wlSwitch.addEventListener('change', async () => {
    try {
      await api('/api/properties', { method: 'POST', body: { values: { 'white-list': String(wlSwitch.checked), 'enforce-whitelist': String(wlSwitch.checked) } } });
      if (cs() && cs().state === 'online') await api('/api/command', { method: 'POST', body: { cmd: 'whitelist ' + (wlSwitch.checked ? 'on' : 'off') } });
      toast('Whitelist ' + (wlSwitch.checked ? 'enabled' : 'disabled'), 'ok');
    } catch { wlSwitch.checked = !wlSwitch.checked; }
  });
  return {
    el: h('div', { class: 'page' }, h('h1', {}, 'Players & access'), h('p', { class: 'lead' }, 'While the server runs these use console commands; while stopped they edit the JSON files directly (UUIDs are looked up from Mojang for premium servers).'),
      card('Online now', online),
      card('Whitelist', h('div', { class: 'switch-row', style: 'padding-bottom:0' }, h('div', {}, h('b', {}, 'Enable whitelist'), h('span', { class: 'hint c-muted' }, 'Sets white-list and enforce-whitelist.')), h('label', { class: 'switch' }, wlSwitch, h('span')))),
      h('div', { class: 'grid2' }, wl.el, ops.el), h('div', { class: 'grid2' }, bp.el, bi.el)),
    init() { [wl, ops, bp, bi].forEach((x) => x.load()); },
    update(s) { const ns = s.players.join(',') + s.state; if (ns !== sig) { sig = ns; online.replaceChildren(...playerList(s)); } if (document.activeElement !== wlSwitch) wlSwitch.checked = !!s.whitelist; },
  };
}

// ---------------------------------------------------------------- OPTIONS (server.properties)
// [key, label, type(t text|a textarea|n number|b bool|s select|p password), default, hint, expert, extra]
const DIFF = ['peaceful', 'easy', 'normal', 'hard'], GM = ['survival', 'creative', 'adventure', 'spectator'];
const SCHEMA = [
  ['General', [
    ['motd', 'Server description (MOTD)', 'a', 'A Minecraft Server', 'Shown in the multiplayer list. Use & for colours (&a green, &l bold, &r reset). Two lines allowed.', 0],
    ['max-players', 'Max players', 'n', '20', 'Maximum simultaneous players.', 0, [0, 100000]],
    ['gamemode', 'Default game mode', 's', 'survival', 'Mode new players start in.', 0, GM],
    ['difficulty', 'Difficulty', 's', 'easy', '', 0, DIFF],
    ['hardcore', 'Hardcore', 'b', 'false', 'Players are banned on death; locks difficulty to hard.', 0],
    ['pvp', 'PvP', 'b', 'true', 'Players can damage each other.', 0],
    ['force-gamemode', 'Force game mode', 'b', 'false', 'Reset players to the default mode when they join.', 0],
    ['hide-online-players', 'Hide player list', 'b', 'false', 'Do not show players in the server list.', 1]]],
  ['World', [
    ['level-name', 'World folder name', 't', 'world', 'Folder used as the world. Change it to switch worlds.', 0],
    ['level-seed', 'Seed', 't', '', 'Leave blank for a random seed. Only used when the world is generated.', 0],
    ['level-type', 'World type', 's', 'minecraft:normal', 'Applies to newly generated worlds.', 0, ['minecraft:normal', 'minecraft:flat', 'minecraft:large_biomes', 'minecraft:amplified', 'minecraft:single_biome_surface']],
    ['generate-structures', 'Generate structures', 'b', 'true', 'Villages, strongholds and more.', 0],
    ['allow-nether', 'Allow the Nether', 'b', 'true', '', 0], ['spawn-animals', 'Spawn animals', 'b', 'true', '', 0], ['spawn-monsters', 'Spawn monsters', 'b', 'true', '', 0], ['spawn-npcs', 'Spawn villagers', 'b', 'true', '', 0],
    ['spawn-protection', 'Spawn protection radius', 'n', '16', 'Blocks around spawn only operators can edit (0 disables).', 0, [0, 1000]],
    ['max-world-size', 'Max world size (radius)', 'n', '29999984', 'World border limit in blocks.', 1, [1, 29999984]],
    ['generator-settings', 'Generator settings', 't', '{}', 'JSON used by superflat worlds.', 1]]],
  ['Players & access', [
    ['online-mode', 'Premium accounts only', 'b', 'true', 'Verifies accounts with Mojang. Keep ON for public servers.', 0],
    ['white-list', 'Whitelist', 'b', 'false', 'Only whitelisted players can join.', 0],
    ['enforce-whitelist', 'Enforce whitelist', 'b', 'false', 'Kick non-whitelisted players when the whitelist reloads.', 1],
    ['allow-flight', 'Allow flight', 'b', 'false', 'Stops kicks for flying (needed by some mods/plugins).', 0],
    ['enable-command-block', 'Command blocks', 'b', 'false', '', 0],
    ['op-permission-level', 'Operator permission level', 'n', '4', '1-4: bypass spawn / commands / player admin / server admin.', 1, [0, 4]],
    ['function-permission-level', 'Function permission level', 'n', '2', 'Permission level for functions.', 1, [1, 4]],
    ['player-idle-timeout', 'Idle kick (minutes)', 'n', '0', '0 = never.', 1, [0, 100000]],
    ['enforce-secure-profile', 'Enforce secure chat profile', 'b', 'true', 'Require players to have a Mojang public key.', 1],
    ['prevent-proxy-connections', 'Block proxy/VPN connections', 'b', 'false', '', 1],
    ['accept-transfers', 'Accept transfers', 'b', 'false', 'Allow players to be transferred from other servers.', 1]]],
  ['Network', [
    ['server-port', 'Server port', 'n', '25565', 'TCP port. Must be unique per running server.', 0, [1, 65535]],
    ['server-ip', 'Bind address', 't', '', 'Leave blank to listen on all interfaces.', 1],
    ['enable-status', 'Respond to server-list pings', 'b', 'true', '', 1],
    ['network-compression-threshold', 'Network compression threshold', 'n', '256', 'Bytes; -1 disables compression.', 1, [-1, 100000]],
    ['rate-limit', 'Packet rate limit', 'n', '0', 'Packets per second before kicking (0 = off).', 1, [0, 100000]],
    ['use-native-transport', 'Native transport (Linux)', 'b', 'true', 'Optimised packet handling on Linux.', 1],
    ['log-ips', 'Log player IPs', 'b', 'true', '', 1]]],
  ['Performance', [
    ['view-distance', 'View distance', 'n', '10', 'Chunks sent to players. Lower = faster.', 0, [2, 32]],
    ['simulation-distance', 'Simulation distance', 'n', '10', 'Chunks around players that tick.', 0, [3, 32]],
    ['entity-broadcast-range-percentage', 'Entity broadcast range (%)', 'n', '100', '', 1, [10, 1000]],
    ['max-tick-time', 'Watchdog max tick time (ms)', 'n', '60000', '-1 disables the crash watchdog.', 1, [-1, 2147483647]],
    ['max-chained-neighbor-updates', 'Max chained neighbour updates', 'n', '1000000', '', 1, [-1, 2147483647]],
    ['sync-chunk-writes', 'Synchronous chunk writes', 'b', 'true', 'Safer but slower on some disks.', 1],
    ['region-file-compression', 'Region compression', 's', 'deflate', '', 1, ['deflate', 'lz4', 'none']],
    ['pause-when-empty-seconds', 'Pause when empty (s)', 'n', '60', 'Pause ticking after this long with no players (-1 = never).', 1, [-1, 100000]]]],
  ['Resource pack', [
    ['resource-pack', 'Resource pack URL', 't', '', 'Direct download link to a .zip.', 1],
    ['resource-pack-sha1', 'Resource pack SHA-1', 't', '', '', 1], ['resource-pack-id', 'Resource pack UUID', 't', '', '', 1],
    ['resource-pack-prompt', 'Prompt message', 't', '', 'Shown to players when asked to download it.', 1],
    ['require-resource-pack', 'Require resource pack', 'b', 'false', 'Players who decline are disconnected.', 1]]],
  ['Remote access & monitoring', [
    ['enable-rcon', 'Enable RCON', 'b', 'false', 'Remote console. Set a strong password and never expose the port publicly.', 1],
    ['rcon.port', 'RCON port', 'n', '25575', '', 1, [1, 65535]], ['rcon.password', 'RCON password', 'p', '', '', 1],
    ['broadcast-rcon-to-ops', 'Broadcast RCON output to ops', 'b', 'true', '', 1], ['broadcast-console-to-ops', 'Broadcast console output to ops', 'b', 'true', '', 1],
    ['enable-query', 'Enable GameSpy4 query', 'b', 'false', '', 1], ['query.port', 'Query port', 'n', '25565', '', 1, [1, 65535]],
    ['enable-jmx-monitoring', 'Enable JMX monitoring', 'b', 'false', '', 1], ['debug', 'Debug logging', 'b', 'false', '', 1]]],
  ['Data packs & misc', [
    ['initial-enabled-packs', 'Initially enabled data packs', 't', 'vanilla', 'Comma separated.', 1], ['initial-disabled-packs', 'Initially disabled data packs', 't', '', 'Comma separated.', 1],
    ['text-filtering-config', 'Text filtering config', 't', '', '', 1], ['bug-report-link', 'Bug report link', 't', '', '', 1]]],
];
const KNOWN = new Set(SCHEMA.flatMap(([, f]) => f.map((x) => x[0])));

function optionsView() {
  let mode = localStorage.getItem('mcMode') === 'expert' ? 'expert' : 'normal', filter = '';
  let vals = {}; const dirty = {}; let extra = {};
  const body = h('div'); const bar = h('div', { class: 'savebar', hidden: true });
  const barText = h('span'); const restartBtn = h('button', { class: 'btn blue' }, 'Save & restart');
  const cur = (k, d) => (k in dirty ? dirty[k] : k in vals ? vals[k] : d);
  const setDirty = (k, v, def) => { if (v === (k in vals ? vals[k] : def)) delete dirty[k]; else dirty[k] = v; const n = Object.keys(dirty).length; bar.hidden = !n; barText.textContent = n + ' unsaved change' + (n > 1 ? 's' : ''); restartBtn.hidden = !running(cs()); };
  function field([k, label, type, def, hint, , ext]) {
    let ctl; const box = h('div', { class: 'opt' + (k in dirty ? ' dirty' : '') });
    const val = cur(k, def);
    const on = (v) => { setDirty(k, v, def); box.classList.toggle('dirty', k in dirty); if (k === 'motd') prev.replaceChildren(motdBox(cs(), v.replace(/&(?=[0-9a-fk-orA-FK-OR])/g, '§'))); };
    if (type === 'b') { ctl = h('input', { type: 'checkbox' }); ctl.checked = val === 'true'; ctl.addEventListener('change', () => on(String(ctl.checked))); ctl = h('label', { class: 'switch' }, ctl, h('span')); }
    else if (type === 's') { const opts = ext.includes(val) ? ext : [val, ...ext]; ctl = h('select', {}, opts.map((o) => h('option', { value: o }, o))); ctl.value = val; ctl.addEventListener('change', () => on(ctl.value)); }
    else if (type === 'a') { ctl = h('textarea', { rows: 2 }); ctl.value = val; ctl.addEventListener('input', () => on(ctl.value)); }
    else { ctl = h('input', { type: type === 'n' ? 'number' : type === 'p' ? 'password' : 'text', autocomplete: 'off', spellcheck: 'false' }); if (type === 'n' && ext) { ctl.min = ext[0]; ctl.max = ext[1]; } ctl.value = val; ctl.addEventListener('input', () => on(ctl.value)); }
    const rst = h('button', { class: 'rst', title: 'Reset to default (' + (def || 'blank') + ')', on: { click: () => { on(def); draw(); } } }, '↺');
    const prev = h('div');
    box.append(...[h('div', { class: 'lab' }, h('b', {}, label), h('code', {}, k)), h('div', { class: 'ctl' }, ctl, rst), hint ? h('div', { class: 'hint' }, hint) : null, k === 'motd' ? prev : null].filter(Boolean));
    if (k === 'motd' && cs()) prev.append(motdBox(cs(), val.replace(/&(?=[0-9a-fk-orA-FK-OR])/g, '§')));
    return box;
  }
  function draw() {
    const q = filter.toLowerCase(); const parts = [];
    for (const [sec, fields] of SCHEMA) {
      const fs = fields.filter((f) => (mode === 'expert' || !f[5]) && (!q || (f[0] + f[1] + f[4]).toLowerCase().includes(q)));
      if (fs.length) parts.push(h('div', { class: 'opt-sec' }, sec), h('div', { class: 'opt-grid' }, fs.map(field)));
    }
    if (mode === 'expert') {
      const others = Object.keys({ ...vals, ...extra }).filter((k) => !KNOWN.has(k) && (!q || k.toLowerCase().includes(q)));
      const addRow = h('div', { class: 'inline', style: 'margin-top:10px' }, h('input', { id: 'ck', placeholder: 'custom-key' }), h('input', { id: 'cv', placeholder: 'value' }),
        h('button', { class: 'btn small', on: { click: () => { const k = $('#ck', body).value.trim(); if (!/^[A-Za-z0-9._\-]{1,64}$/.test(k)) { toast('Invalid property name.', 'err'); return; } extra[k] = ''; setDirty(k, $('#cv', body).value, ''); draw(); } } }, 'Add property'));
      parts.push(h('div', { class: 'opt-sec' }, 'Other properties'), h('div', { class: 'opt-grid' }, others.map((k) => field([k, k, 't', '', 'Not part of the standard list.', 1]))), addRow);
    }
    body.replaceChildren(...parts);
    if (!parts.length) body.replaceChildren(h('div', { class: 'empty' }, 'No options match your search.'));
  }
  async function save(restart, btn) {
    const values = { ...dirty };
    if ('motd' in values) values.motd = values.motd.replace(/&(?=[0-9a-fk-orA-FK-OR])/g, '§');
    await act(btn, async () => {
      await api('/api/properties', { method: 'POST', body: { values } });
      Object.assign(vals, values); for (const k of Object.keys(dirty)) delete dirty[k]; bar.hidden = true; draw();
      if (restart) { await api('/api/restart', { method: 'POST' }); toast('Saved. Restarting…', 'ok'); } else toast(running(cs()) ? 'Saved. Restart the server to apply.' : 'Saved.', 'ok');
    });
  }
  const seg = h('div', { class: 'seg' }, ['normal', 'expert'].map((m) => h('button', { class: m === mode ? 'on' : '', 'data-m': m, on: { click: () => { mode = m; localStorage.setItem('mcMode', m); seg.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.m === m)); draw(); } } }, cap(m))));
  const search = h('input', { placeholder: 'Search options…', on: { input: (e) => { filter = e.target.value; draw(); } } });
  restartBtn.addEventListener('click', (e) => save(true, e.currentTarget));
  bar.append(barText, h('button', { class: 'btn ghost', on: { click: () => { for (const k of Object.keys(dirty)) delete dirty[k]; bar.hidden = true; draw(); } } }, 'Discard'),
    h('button', { class: 'btn green', on: { click: (e) => save(false, e.currentTarget) } }, 'Save'), restartBtn);
  return {
    el: h('div', { class: 'page' }, h('h1', {}, 'Options'), h('p', { class: 'lead' }, 'Everything in server.properties. Normal mode shows the common settings; Expert shows every option plus unknown keys. Changes apply after a restart.'),
      h('div', { class: 'opt-head' }, seg, search, h('button', { class: 'btn ghost small', on: { click: () => goS('files') } }, 'Edit raw file in Files')), body, bar),
    async init() { try { vals = await api('/api/properties', { quiet: true }); } catch { vals = {}; } draw(); },
  };
}

// ---------------------------------------------------------------- SOFTWARE
function softwareView() {
  let sw = 'paper'; const cards = h('div', { class: 'swcards' });
  const ver = h('select', {}); const backup = h('input', { type: 'checkbox', checked: true });
  const info = h('div'); const apply = h('button', { class: 'btn green' }, 'Apply change');
  const drawCards = () => cards.replaceChildren(...Object.entries(SW).map(([k, [t, d]]) => h('div', { class: 'swcard' + (k === sw ? ' on' : ''), on: { click: () => { sw = k; drawCards(); loadV(); } } }, h('b', {}, t), h('small', {}, d))));
  async function loadV(sel) {
    ver.replaceChildren(h('option', { value: '' }, sw === 'custom' ? 'n/a' : 'Loading…')); ver.disabled = sw === 'custom'; if (sw === 'custom') return;
    let vs = [];
    try { vs = await api('/api/software/versions?software=' + sw, { global: true, quiet: true }); } catch { /* offline */ }
    const cur = sel !== undefined ? sel : ver.value;
    ver.replaceChildren(h('option', { value: '' }, 'Latest (pinned on next start)'), ...(cur && !vs.includes(cur) ? [h('option', { value: cur }, cur)] : []), ...vs.slice(0, 120).map((v) => h('option', { value: v }, v))); ver.value = cur || '';
  }
  apply.addEventListener('click', async () => {
    const s = cs();
    if (!(await confirmBox('Change server software?', `Switch to ${SW[sw][0]} ${ver.value || '(newest)'}. Worlds can break when going to an older Minecraft version or a different software; a backup is made first if ticked.`, 'Change', true))) return;
    act(apply, () => api('/api/software', { method: 'POST', body: { software: sw, version: ver.value, backup: backup.checked } }), 'Changing software…');
  });
  return {
    el: h('div', { class: 'page' }, h('h1', {}, 'Software & version'), h('p', { class: 'lead' }, 'Switch the server software or Minecraft version. The jar downloads automatically on the next start.'),
      card('Current', info), card('Change', h('div', { class: 'field' }, h('label', {}, 'Software'), cards), h('div', { class: 'field' }, h('label', {}, 'Minecraft version'), ver),
        h('label', { style: 'display:flex;gap:10px;align-items:center;margin-bottom:14px;cursor:pointer' }, backup, h('span', {}, 'Create a backup first')),
        h('div', { class: 'actions' }, apply, h('button', { class: 'btn', on: { click: (e) => act(e.currentTarget, () => api('/api/software/update', { method: 'POST' }), 'Will fetch the newest build on next start') } }, 'Update to newest build')),
        h('p', { class: 'c-muted' }, 'Custom jar: upload your own server.jar (Forge, NeoForge, modpacks…) on the Files page.'))),
    async init() { const c = await api('/api/config', { quiet: true }).catch(() => null); if (c) { sw = c.software; drawCards(); await loadV(c.version); } },
    update(s) { const off = s.state === 'offline'; apply.disabled = !off; info.replaceChildren(kv('Software', h('span', { class: 'v' }, SW[s.software] ? SW[s.software][0] : s.software)), kv('Version', h('span', { class: 'v' }, s.version || 'newest')), kv('Installed build', h('span', { class: 'v mono', style: 'cursor:default' }, s.installed || '—')), off ? null : h('div', { class: 'note', style: 'margin-top:10px' }, 'Stop the server to change its software.')); },
  };
}

// ---------------------------------------------------------------- ADD-ONS
function extView(type) {
  const T = { plugin: ['Plugins', 'Paper / Purpur / Folia plugins from Modrinth. Restart to load new ones.', ['paper', 'purpur', 'folia']], mod: ['Mods', 'Mods from Modrinth (Fabric / Forge / NeoForge / Quilt).', ['fabric']], datapack: ['Datapacks', 'Data packs go into the active world\'s datapacks folder.', null] }[type];
  const results = h('div', { class: 'list' }), installed = h('div', { class: 'list' });
  const input = h('input', { placeholder: `Search Modrinth ${T[0].toLowerCase()}…`, autocomplete: 'off' });
  const note = h('div'); let wasBusy = false;
  async function search() {
    const q = input.value.trim(); if (!q) return;
    results.replaceChildren(h('div', { class: 'empty' }, 'Searching…'));
    try {
      const hits = await api(`/api/ext/search?type=${type}&q=${encodeURIComponent(q)}`);
      results.replaceChildren(...(hits.length ? hits.map((x) => h('div', { class: 'item' },
        h('div', { class: 'ico', style: x.icon_url && x.icon_url.startsWith('https://cdn.modrinth.com/') ? `background-image:url("${encodeURI(x.icon_url)}")` : '' }, x.icon_url ? '' : '📦'),
        h('div', { class: 'grow' }, h('b', {}, x.title), h('span', {}, x.description)), h('div', { class: 'meta' }, '⬇ ' + fmtNum(x.downloads)),
        h('button', { class: 'btn green small', on: { click: (e) => act(e.currentTarget, () => api('/api/ext/install', { method: 'POST', body: { type, project_id: x.project_id, title: x.title } }), 'Installing ' + x.title + '…') } }, 'Install'))) : [h('div', { class: 'empty' }, 'Nothing found for this version/software.')]));
    } catch { results.replaceChildren(h('div', { class: 'empty' }, 'Search failed.')); }
  }
  async function load() {
    try {
      const l = await api(`/api/ext/installed?type=${type}`, { quiet: true });
      installed.replaceChildren(...(l.length ? l.map((f) => h('div', { class: 'item' }, h('div', { class: 'ico' }, f.enabled ? '📦' : '⏸'),
        h('div', { class: 'grow' }, h('b', { style: f.enabled ? '' : 'opacity:.55' }, f.name.replace(/\.disabled$/, '')), h('span', {}, f.enabled ? 'enabled' : 'disabled')), h('div', { class: 'meta' }, fmtBytes(f.size)),
        h('label', { class: 'switch', title: f.enabled ? 'Disable' : 'Enable' }, h('input', { type: 'checkbox', checked: f.enabled, on: { change: async () => { await api('/api/ext/toggle', { method: 'POST', body: { type, name: f.name } }).catch(() => {}); load(); } } }), h('span')),
        h('button', { class: 'btn red small', on: { click: async () => { if (await confirmBox('Remove ' + f.name + '?', 'The file is deleted from the server folder.', 'Remove', true)) { await api('/api/ext/remove', { method: 'POST', body: { type, name: f.name } }).catch(() => {}); load(); } } } }, 'Remove'))) : [h('div', { class: 'empty' }, 'Nothing installed yet.')]));
    } catch { /* ignore */ }
  }
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') search(); });
  return {
    el: h('div', { class: 'page' }, h('h1', {}, T[0]), h('p', { class: 'lead' }, T[1]), note,
      card('Find & install', h('div', { class: 'inline' }, input, h('button', { class: 'btn blue', on: { click: search } }, 'Search')), h('div', { style: 'height:12px' }), results), card('Installed', installed)),
    init: load,
    update(s) { const b = !!s.busy; if (wasBusy && !b) load(); wasBusy = b; if (T[2] && !T[2].includes(s.software) && s.software !== 'custom') note.replaceChildren(h('div', { class: 'note' }, `${T[0]} do not load on ${SW[s.software][0]}. Switch software on the Software page (needs ${T[2].map((x) => SW[x][0]).join(' / ')}).`)); else note.replaceChildren(); },
  };
}
function bedrockView() {
  return { el: h('div', { class: 'page' }, h('h1', {}, 'Bedrock crossplay'), h('p', { class: 'lead' }, 'Let Bedrock players (phones, consoles, Windows 10/11) join your Java server with Geyser + Floodgate (needs Paper/Purpur/Folia).'),
    card('Install', h('p', {}, 'Downloads the latest Geyser and Floodgate plugins.'), h('div', { class: 'actions' }, h('button', { class: 'btn green', on: { click: (e) => act(e.currentTarget, () => api('/api/geyser', { method: 'POST' }), 'Installing Geyser + Floodgate…') } }, 'Install Geyser + Floodgate'))),
    card('After installing', kv('Restart', h('span', { class: 'v' }, 'required')), kv('Bedrock port', h('span', { class: 'v mono' }, 'UDP 19132')), kv('No Java account needed', h('span', { class: 'v mono' }, 'auth-type: floodgate')),
      h('p', { class: 'c-muted' }, 'Set auth-type in plugins/Geyser-Spigot/config.yml (edit it on the Files page).'))) };
}

// ---------------------------------------------------------------- WORLDS
function worldsView() {
  const list = h('div', { class: 'list' }); let level = 'world', wasBusy = false;
  async function load() {
    try {
      const p = await api('/api/properties', { quiet: true }); level = p['level-name'] || 'world';
      const l = await api('/api/worlds', { quiet: true }); const off = cs() && cs().state === 'offline';
      list.replaceChildren(...(l.length ? l.map((w) => h('div', { class: 'item' }, h('div', { class: 'ico' }, w.name === level ? '🌍' : '🗺'),
        h('div', { class: 'grow' }, h('b', {}, w.name, w.name === level ? h('span', { class: 'badge', style: 'margin-left:8px' }, 'active') : null), h('span', {}, new Date(w.modified).toLocaleString())), h('div', { class: 'meta' }, fmtBytes(w.size)),
        w.name === level ? null : h('button', { class: 'btn small', on: { click: async () => { await api('/api/properties', { method: 'POST', body: { values: { 'level-name': w.name } } }).catch(() => {}); toast('Active world set. Restart to apply.', 'ok'); load(); } } }, 'Use'),
        h('a', { class: 'btn small', href: `/api/worlds/download?id=${encodeURIComponent(S.cur)}&name=${encodeURIComponent(w.name)}` }, 'Download'),
        h('button', { class: 'btn red small', disabled: !off, title: off ? '' : 'Stop the server first', on: { click: async () => { if (await confirmBox('Delete world "' + w.name + '"?', 'This cannot be undone. Make a backup first if unsure.', 'Delete', true)) { await api('/api/worlds/delete', { method: 'POST', body: { name: w.name } }).catch(() => {}); load(); } } } }, 'Delete'))) : [h('div', { class: 'empty' }, 'No world yet. Start the server once to generate one, or upload one.')]));
    } catch { /* ignore */ }
  }
  async function upload(e) {
    const f = await pickFile('.zip'); if (!f) return;
    const name = 'upload-' + Date.now() + '.zip';
    await act(e.target, async () => { toast('Uploading ' + fmtBytes(f.size) + '…'); await api('/api/files/upload?path=&name=' + encodeURIComponent(name), { method: 'POST', raw: f }); await api('/api/files/unzip', { method: 'POST', body: { path: name } }); await api('/api/files/delete', { method: 'POST', body: { path: name } }); load(); }, 'World uploaded and extracted');
  }
  return {
    el: h('div', { class: 'page' }, h('h1', {}, 'Worlds'), h('p', { class: 'lead' }, 'Download, replace or reset worlds. A world zip should contain the world folder (with level.dat) at its top level.'),
      h('div', { class: 'toolbar' }, h('button', { class: 'btn blue', on: { click: upload } }, '⬆ Upload world (.zip)'), h('button', { class: 'btn ghost', on: { click: load } }, 'Refresh')), list),
    init: load, update(s) { const b = !!s.busy; if (wasBusy && !b) load(); wasBusy = b; },
  };
}

// ---------------------------------------------------------------- FILES
function filesView() {
  let path = ''; const list = h('div'); const crumbs = h('div', { class: 'crumbs' });
  const join = (a, b) => (a ? a + '/' + b : b);
  async function load(p = path) {
    try {
      const l = await api('/api/files?path=' + encodeURIComponent(p), { quiet: true }); path = p;
      const parts = p ? p.split('/') : [];
      crumbs.replaceChildren(h('a', { on: { click: () => load('') } }, '📁 server'), ...parts.flatMap((seg, i) => [h('span', {}, '/'), h('a', { on: { click: () => load(parts.slice(0, i + 1).join('/')) } }, seg)]));
      list.replaceChildren(h('div', { class: 'frow head' }, h('span'), h('span', {}, 'Name'), h('span', {}, 'Size'), h('span', {}, 'Modified'), h('span')),
        ...(p ? [h('div', { class: 'frow', on: { click: () => load(parts.slice(0, -1).join('/')) } }, h('span', {}, '↩'), h('span', {}, '..'), h('span'), h('span'), h('span'))] : []),
        ...l.map((f) => h('div', { class: 'frow', on: { click: () => (f.dir ? load(join(p, f.name)) : openFile(join(p, f.name))) } },
          h('span', {}, f.dir ? '📁' : /\.(png|jpe?g)$/i.test(f.name) ? '🖼' : /\.(zip|jar|gz)$/i.test(f.name) ? '📦' : '📄'), h('span', { style: 'font-weight:600;word-break:break-all' }, f.name), h('span', { class: 'meta' }, f.dir ? '' : fmtBytes(f.size)), h('span', { class: 'meta' }, new Date(f.modified).toLocaleString()),
          h('button', { class: 'btn small ghost', on: { click: (e) => { e.stopPropagation(); menu(e.currentTarget, f); } } }, '⋯'))));
      if (!l.length) list.append(h('div', { class: 'empty' }, 'This folder is empty.'));
    } catch { /* toasted */ }
  }
  function menu(anchor, f) {
    const full = join(path, f.name); const items = [{ head: f.name }];
    if (!f.dir) { items.push({ label: 'Edit / view', run: () => openFile(full) }, { label: 'Download', run: () => { location.href = `/api/files/download?id=${encodeURIComponent(S.cur)}&path=${encodeURIComponent(full)}`; } }); }
    if (/\.zip$/i.test(f.name)) items.push({ label: 'Extract here', run: () => act(null, async () => { await api('/api/files/unzip', { method: 'POST', body: { path: full } }); load(); }, 'Extracted') });
    if (/\.(png|jpe?g)$/i.test(f.name)) items.push({ label: 'Use as server icon', run: () => act(null, () => api('/api/icon', { method: 'POST', body: { path: full } }), 'Icon updated') });
    items.push({ label: 'Rename', run: async () => { const n = await promptBox('Rename', 'New name', f.name); if (n && n !== f.name) { await api('/api/files/rename', { method: 'POST', body: { path: full, name: n } }).catch(() => {}); load(); } } },
      { label: 'Delete', run: async () => { if (await confirmBox('Delete ' + f.name + '?', f.dir ? 'The folder and everything in it will be removed.' : 'This cannot be undone.', 'Delete', true)) { await api('/api/files/delete', { method: 'POST', body: { path: full } }).catch(() => {}); load(); } } });
    popupMenu(anchor, items);
  }
  async function openFile(full) {
    let text; try { text = (await api('/api/file?path=' + encodeURIComponent(full))).content; } catch { return; }
    const ta = h('textarea', { spellcheck: 'false' }); ta.value = text;
    ta.addEventListener('keydown', (e) => { if (e.key === 'Tab') { e.preventDefault(); const s = ta.selectionStart; ta.setRangeText('  ', s, ta.selectionEnd, 'end'); } });
    const save = h('button', { class: 'btn green' }, 'Save');
    save.addEventListener('click', () => act(save, async () => { await api('/api/file', { method: 'POST', body: { path: full, content: ta.value } }); closeModal(); load(); }, 'Saved'));
    openModal(h('div', { class: 'dialog editor' }, h('h3', {}, full), ta, h('div', { class: 'actions', style: 'margin-top:14px' }, h('button', { class: 'btn', on: { click: closeModal } }, 'Close'), save)));
    ta.focus();
  }
  async function upload(e) {
    const f = await pickFile(''); if (!f) return;
    await act(e.target, async () => { toast('Uploading ' + fmtBytes(f.size) + '…'); await api(`/api/files/upload?path=${encodeURIComponent(path)}&name=${encodeURIComponent(f.name)}`, { method: 'POST', raw: f }); load(); }, 'Uploaded ' + f.name);
  }
  return {
    el: h('div', { class: 'page' }, h('h1', {}, 'Files'), h('p', { class: 'lead' }, 'Browse and edit everything in the server folder. Upload plugins, configs or your own server.jar.'),
      h('div', { class: 'toolbar' }, h('button', { class: 'btn blue', on: { click: upload } }, '⬆ Upload'),
        h('button', { class: 'btn', on: { click: async () => { const n = await promptBox('New folder', 'Folder name'); if (n) { await api('/api/files/mkdir', { method: 'POST', body: { path: join(path, n) } }).catch(() => {}); load(); } } } }, '＋ Folder'),
        h('button', { class: 'btn', on: { click: async () => { const n = await promptBox('New file', 'File name'); if (n) { await api('/api/file', { method: 'POST', body: { path: join(path, n), content: '' } }).catch(() => {}); load(); } } } }, '＋ File'),
        h('button', { class: 'btn ghost', on: { click: () => load() } }, 'Refresh')), crumbs, card(null, list)),
    init: () => load(''),
  };
}

// ---------------------------------------------------------------- LOGS
function logsView() {
  const sel = h('select', { style: 'max-width:280px' }); const term = h('div', { class: 'term full' }); const flt = h('input', { placeholder: 'Filter lines…', style: 'max-width:240px' }); let lines = [];
  const draw = () => { const q = flt.value.toLowerCase(); term.replaceChildren(...lines.filter((l) => !q || l.toLowerCase().includes(q)).slice(-2500).map((l) => h('div', { class: 'ln ' + (/\b(ERROR|FATAL|Exception)\b/.test(l) ? 'errl' : /\bWARN\b/.test(l) ? 'warn' : 'plain') }, h('span', { class: 'msg' }, l)))); term.scrollTop = term.scrollHeight; };
  async function open() { if (!sel.value) { term.replaceChildren(h('div', { class: 'empty' }, 'No log files yet. Start the server once.')); return; } try { lines = (await api('/api/logfile?name=' + encodeURIComponent(sel.value))).text.split('\n'); draw(); } catch { /* toasted */ } }
  sel.addEventListener('change', open); flt.addEventListener('input', draw);
  return {
    el: h('div', { class: 'page fill' }, h('div', { class: 'toolbar' }, h('h1', { class: 'grow' }, 'Logs'), sel, flt, h('button', { class: 'btn', on: { click: open } }, '↻ Reload')), term),
    async init() { try { const f = await api('/api/logfiles', { quiet: true }); sel.replaceChildren(...f.map((n) => h('option', { value: n }, n))); } catch { /* ignore */ } open(); },
  };
}

// ---------------------------------------------------------------- BACKUPS
function backupsView() {
  const list = h('div', { class: 'list' }); let wasBusy = false;
  async function load() {
    try {
      const l = await api('/api/backups', { quiet: true }); const off = cs() && cs().state === 'offline';
      list.replaceChildren(...(l.length ? l.map((b) => h('div', { class: 'item' }, h('div', { class: 'ico' }, '🗜'), h('div', { class: 'grow' }, h('b', {}, b.name), h('span', {}, new Date(b.modified).toLocaleString())), h('div', { class: 'meta' }, fmtBytes(b.size)),
        h('button', { class: 'btn blue small', disabled: !off, title: off ? '' : 'Stop the server first', on: { click: async () => { if (await confirmBox('Restore ' + b.name + '?', 'World folders inside this backup are replaced. A safety backup of the current state is made first.', 'Restore', true)) act(null, () => api('/api/backups/restore', { method: 'POST', body: { name: b.name } }), 'Restoring…'); } } }, 'Restore'),
        h('a', { class: 'btn small', href: `/api/backups/download?id=${encodeURIComponent(S.cur)}&name=${encodeURIComponent(b.name)}` }, 'Download'),
        h('button', { class: 'btn red small', on: { click: async () => { if (await confirmBox('Delete backup?', b.name, 'Delete', true)) { await api('/api/backups/delete', { method: 'POST', body: { name: b.name } }).catch(() => {}); load(); } } } }, 'Delete'))) : [h('div', { class: 'empty' }, 'No backups yet.')]));
    } catch { /* ignore */ }
  }
  return {
    el: h('div', { class: 'page' }, h('h1', {}, 'Backups'), h('p', { class: 'lead' }, 'Worlds, configs and player data are zipped (plugins, mods and the server jar are skipped). A running server is paused from saving meanwhile, so it is safe online. Schedule automatic backups on the Automation page.'),
      h('div', { class: 'toolbar' }, h('button', { class: 'btn green', on: { click: (e) => act(e.currentTarget, () => api('/api/backup', { method: 'POST' }), 'Backup started') } }, '💾 Create backup')), list),
    init: load, update(s) { const b = !!s.busy; if (wasBusy && !b) load(); wasBusy = b; },
  };
}

// ---------------------------------------------------------------- generic settings form (config.json)
function cfgForm(title, lead, build) {
  const st = { c: {} }; const box = h('div');
  const save = h('button', { class: 'btn green' }, 'Save');
  return {
    el: h('div', { class: 'page' }, h('h1', {}, title), h('p', { class: 'lead' }, lead), box, h('div', { class: 'actions' }, save)),
    async init() { try { st.c = await api('/api/config', { quiet: true }); } catch { st.c = {}; } build(box, st, save, () => act(save, () => api('/api/config', { method: 'POST', body: st.out() }), 'Saved. Restart the server to apply.')); },
  };
}
const sw = (label, hint, checked, onchange) => { const i = h('input', { type: 'checkbox', checked }); i.addEventListener('change', () => onchange(i.checked)); return h('div', { class: 'switch-row' }, h('div', {}, h('b', {}, label), h('span', { class: 'hint c-muted' }, hint)), h('label', { class: 'switch' }, i, h('span'))); };

function automationView() {
  return cfgForm('Automation', 'Schedules, auto-stop and crash recovery.', (box, st, save, doSave) => {
    const c = st.c; let sched = (c.schedules || []).map((s) => ({ ...s })); const rows = h('div');
    const stopIn = h('input', { type: 'number', min: 0, max: 1440, value: c.autoStopMin || 0 }); const keep = h('input', { type: 'number', min: 1, max: 1000, value: c.backupKeep || 10 });
    const draw = () => rows.replaceChildren(...(sched.length ? sched : [null]).map((s, i) => {
      if (!s) return h('div', { class: 'empty' }, 'No schedules yet.');
      const type = h('select', {}, [['backup', 'Backup'], ['restart', 'Restart'], ['command', 'Command']].map(([v, l]) => h('option', { value: v }, l))); type.value = s.type;
      const cmd = h('input', { placeholder: 'e.g. say Hello!', value: s.command || '', disabled: s.type !== 'command' });
      type.addEventListener('change', () => { s.type = type.value; cmd.disabled = s.type !== 'command'; }); cmd.addEventListener('input', () => { s.command = cmd.value; });
      const ev = h('input', { type: 'number', min: 1, max: 10080, value: s.everyMinutes, title: 'Every N minutes' }); ev.addEventListener('input', () => { s.everyMinutes = Number(ev.value); });
      const en = h('input', { type: 'checkbox', checked: s.enabled !== false, title: 'Enabled' }); en.addEventListener('change', () => { s.enabled = en.checked; });
      return h('div', { class: 'sched' }, en, type, h('div', { class: 'inline' }, ev, h('span', { style: 'align-self:center;color:var(--muted)' }, 'min')), cmd, h('button', { class: 'x', on: { click: () => { sched.splice(i, 1); draw(); } } }, '✕'));
    }));
    draw();
    box.append(card('Schedules', h('p', { class: 'c-muted', style: 'margin-top:0' }, 'Run a backup, a restart (30 s warning) or a console command every N minutes while the launcher is open.'), rows, h('div', { class: 'actions', style: 'margin-top:10px' }, h('button', { class: 'btn small', on: { click: () => { sched.push({ type: 'backup', everyMinutes: 60, command: '', enabled: true }); draw(); } } }, '＋ Add schedule'))),
      card('Automatic backups', h('div', { class: 'field' }, h('label', {}, 'Keep the newest N scheduled backups'), keep, h('div', { class: 'hint' }, 'Only "auto-" backups are pruned; manual ones are never deleted.'))),
      card('Power', sw('Restart after a crash', 'Up to 3 automatic restarts per 10 minutes.', c.autoRestart, (v) => { c.autoRestart = v; }),
        sw('Start with the launcher', 'Start this server automatically when MC Host opens.', c.autostart, (v) => { c.autostart = v; }),
        h('div', { class: 'field' }, h('label', {}, 'Auto-stop when empty (minutes, 0 = off)'), stopIn, h('div', { class: 'hint' }, 'Saves resources like Aternos: stops after nobody was online for this long.'))));
    st.out = () => ({ schedules: sched, autoRestart: !!c.autoRestart, autostart: !!c.autostart, autoStopMin: Number(stopIn.value), backupKeep: Number(keep.value) });
    save.onclick = doSave;
  });
}

function startupView() {
  return cfgForm('Startup', 'Memory, JVM flags and Java runtime. Applied on the next start.', (box, st, save, doSave) => {
    const c = st.c; const mem = h('input', { value: c.memory }); const preset = h('select', {}, [['default', 'Default (G1GC)'], ['aikar', "Aikar's flags (best for Paper 4 GB+)"], ['none', 'None'], ['custom', 'Custom']].map(([v, l]) => h('option', { value: v }, l)));
    preset.value = c.jvmPreset; const args = h('textarea', { rows: 3, placeholder: '-Xss1M -XX:+UseZGC' }); args.value = c.jvmArgs || ''; const jp = h('input', { value: c.javaPath || '', placeholder: 'blank = automatic' });
    const upd = () => { args.disabled = preset.value !== 'custom'; }; preset.addEventListener('change', upd); upd();
    box.append(card('Memory', h('div', { class: 'field' }, h('label', {}, 'RAM for this server'), mem, h('div', { class: 'chips' }, ['1G', '2G', '3G', '4G', '6G', '8G', '12G', '16G'].map((v) => h('span', { class: 'chip', on: { click: () => { mem.value = v; } } }, v))), h('div', { class: 'hint' }, 'Do not exceed your free RAM. Running several servers adds up.'))),
      card('JVM', h('div', { class: 'field' }, h('label', {}, 'Flags preset'), preset), h('div', { class: 'field' }, h('label', {}, 'Custom flags (each must start with -)'), args),
        h('div', { class: 'field' }, h('label', {}, 'Java executable (expert)'), jp, h('div', { class: 'hint' }, 'Leave blank to use / download the right Java automatically.'))));
    st.out = () => ({ memory: mem.value, jvmPreset: preset.value, jvmArgs: args.value, javaPath: jp.value }); save.onclick = doSave;
  });
}

function networkView() {
  const v = {}; const val = (k, cls = '') => (v[k] = h('span', { class: 'v ' + cls })); const addr = h('input', { placeholder: 'play.example.com:25565', autocomplete: 'off' });
  const start = (p) => (e) => act(e.currentTarget, () => api('/api/tunnel', { method: 'POST', body: { provider: p } }), 'Starting ' + p + '…');
  const prov = (id, name, tag, cls, desc, notes) => card(null, h('h3', {}, name, h('span', { class: 'badge ' + cls }, tag)), h('div', { class: 'c-muted' }, desc), notes ? h('div', { class: 'c-muted' }, notes) : null, h('div', { class: 'actions' }, h('button', { class: 'btn green', on: { click: start(id) } }, 'Start tunnel')));
  const claim = h('a', { class: 'btn yellow', target: '_blank', rel: 'noopener noreferrer', hidden: true }, 'Open playit claim link');
  return {
    el: h('div', { class: 'page' }, h('h1', {}, 'Network'), h('p', { class: 'lead' }, 'Tunnels expose your server to the internet so friends outside your Wi-Fi can join. Keep premium-only accounts ON and consider the whitelist.'),
      card('Connect', kv('LAN (same Wi-Fi)', val('lan', 'mono')), kv('Tunnel provider', val('prov')), kv('Status', val('status')), kv('Public address', (() => { const e = val('addr', 'mono'); e.addEventListener('click', () => e.dataset.copy && copy(e.dataset.copy)); return e; })()),
        h('div', { class: 'actions', style: 'margin-top:10px' }, claim, h('button', { class: 'btn red', on: { click: (e) => act(e.currentTarget, () => api('/api/tunnel/stop', { method: 'POST' }), 'Tunnel stopped') } }, 'Stop tunnel'))),
      h('div', { class: 'provider' }, prov('playit', 'playit.gg', 'Recommended', '', 'Free, no router setup, TCP + UDP (Java + Bedrock).', 'First run shows a claim link: approve the agent in your browser.'), prov('bore', 'bore.pub', 'Instant', 'b2', 'Free, no account. Java Edition only.'), prov('ngrok', 'ngrok', 'Needs account', 'b3', 'Needs the ngrok program and an authtoken.', 'See Tools > Accounts & tokens.')),
      card('Custom address', h('p', { class: 'c-muted', style: 'margin-top:0' }, 'Already have a domain or port-forward? Save it to show as your public address. This tool never looks up your public IP.'), h('div', { class: 'inline' }, addr, h('button', { class: 'btn green', on: { click: (e) => act(e.currentTarget, () => api('/api/tunnel/address', { method: 'POST', body: { address: addr.value } }), 'Address saved') } }, 'Save')))),
    async init() { try { addr.value = (await api('/api/config', { quiet: true })).tunnelAddress || ''; } catch { /* ignore */ } },
    update(s) {
      const t = s.tunnel; const tl = { off: ['off', 'c-muted'], starting: ['connecting…', 'c-yellow'], claim: ['waiting for you to open the claim link', 'c-yellow'], running: ['running', 'c-green'], online: ['online', 'c-green'], stopping: ['stopping', 'c-yellow'], failed: ['failed - see console', 'c-red'] }[t.status] || ['off', 'c-muted'];
      v.lan.textContent = `${s.lan}:${s.port}`; v.prov.textContent = t.provider || 'none'; v.status.textContent = tl[0]; v.status.className = 'v ' + tl[1]; v.addr.textContent = t.address || '—'; v.addr.dataset.copy = t.address || '';
      const ok = t.claim && t.claim.startsWith('https://playit.gg/claim/'); claim.hidden = !ok; if (ok) claim.href = t.claim;
    },
  };
}

function settingsView() {
  const name = h('input', { autocomplete: 'off', maxlength: 40 }); const img = h('img', { alt: '' }); const del = h('input', { placeholder: 'Type the server name to confirm' });
  const refresh = () => { const s = cs(); if (s) img.src = iconUrl({ id: s.id, icon: Date.now() }); pollStatus(); };
  async function candidates() {
    let c; try { c = await api('/api/icon/candidates'); } catch { return; }
    openModal(h('div', { class: 'dialog wide' }, h('h3', {}, 'Icons found in your server files'), c.length ? h('div', { class: 'cands' }, c.map((x) => h('button', { on: { click: async () => { await act(null, () => api('/api/icon', { method: 'POST', body: { source: x.source, entry: x.entry } }), 'Icon updated'); closeModal(); refresh(); } } },
      h('img', { src: `/api/icon/candidate?id=${encodeURIComponent(S.cur)}&source=${encodeURIComponent(x.source)}&entry=${encodeURIComponent(x.entry)}`, alt: '' }), h('span', {}, x.source.split('/').pop()))))
      : h('div', { class: 'empty' }, 'No icon images were found inside server.jar or your plugins/mods. Upload your own instead.'), h('div', { class: 'actions', style: 'margin-top:14px' }, h('button', { class: 'btn', on: { click: closeModal } }, 'Close'))), closeModal);
  }
  return {
    el: h('div', { class: 'page' }, h('h1', {}, 'Server settings'),
      card('Name', h('div', { class: 'inline' }, name, h('button', { class: 'btn green', on: { click: (e) => act(e.currentTarget, async () => { await api('/api/config', { method: 'POST', body: { name: name.value } }); pollStatus(); }, 'Renamed') } }, 'Save'))),
      card('Icon', h('p', { class: 'c-muted', style: 'margin-top:0' }, 'Shown here and in the Minecraft server list (server-icon.png, 64×64). By default the icon inside the downloaded server file is used.'),
        h('div', { class: 'iconpick' }, img, h('div', { class: 'actions' },
          h('button', { class: 'btn blue', on: { click: async () => { const f = await pickFile('image/*'); if (f) { await act(null, async () => api('/api/icon', { method: 'POST', body: { data: await readFile(f) } }), 'Icon updated'); refresh(); } } } }, 'Upload image…'),
          h('button', { class: 'btn', on: { click: candidates } }, 'Pick from server files…'),
          h('button', { class: 'btn ghost', on: { click: async () => { await act(null, () => api('/api/icon/reset', { method: 'POST' }), 'Icon reset'); refresh(); } } }, 'Reset')))),
      h('section', { class: 'card danger' }, h('h2', {}, 'Danger zone'), h('p', { class: 'c-muted', style: 'margin-top:0' }, 'Deleting removes the server, its worlds and its backups permanently.'),
        h('div', { class: 'inline' }, del, h('button', { class: 'btn red', on: { click: async (e) => { const s = cs(); if (await act(e.currentTarget, async () => { await api('/api/servers/delete', { method: 'POST', body: { confirm: del.value } }); return true; }, 'Server deleted')) { go('home'); } } } }, 'Delete server')))),
    init() { const s = cs(); if (s) { name.value = s.name; img.src = iconUrl(s); } },
  };
}

// ---------------------------------------------------------------- TOOLS
function checksView() {
  const list = h('div', { class: 'list' });
  async function load() { try { const c = await api('/api/checks', { quiet: true, global: !S.cur }); list.replaceChildren(...c.map((x) => h('div', { class: 'item' }, h('div', { class: 'ico ' + (x.ok ? 'c-green' : 'c-red') }, x.ok ? '✓' : '✗'), h('div', { class: 'grow' }, h('b', {}, x.label), h('span', {}, x.detail))))); } catch { /* ignore */ } }
  return { el: h('div', { class: 'page' }, h('h1', {}, 'Setup checks'), h('p', { class: 'lead' }, 'Readiness checks. The public IP is never queried by this tool.'), h('div', { class: 'toolbar' }, h('button', { class: 'btn blue', on: { click: load } }, 'Re-run')), list), init: load };
}
function dirsView() {
  const list = h('div', { class: 'list' });
  return { el: h('div', { class: 'page' }, h('h1', {}, 'Directories'), h('p', { class: 'lead' }, 'Everything lives beside the jar in minecraft-host/. Move both together to relocate.'), list),
    async init() { try { const d = await api('/api/dirs', { quiet: true, global: true }); list.replaceChildren(...d.map((x) => h('div', { class: 'item' }, h('div', { class: 'ico' }, '📁'), h('div', { class: 'grow' }, h('b', {}, x.label), h('span', { class: 'mono' }, x.path)), h('button', { class: 'btn small', on: { click: () => copy(x.path) } }, 'Copy path')))); } catch { /* ignore */ } } };
}
function helpView() {
  const row = (k, v) => h('div', { class: 'row' }, h('span', { class: 'k' }, k), h('span', { class: 'v' }, v));
  return { el: h('div', { class: 'page' }, h('h1', {}, 'Accounts & tokens'), h('p', { class: 'lead' }, 'No token is needed for Paper, Purpur, Fabric, Vanilla, Modrinth or Geyser.'),
    card('Tunnels', row('playit.gg', 'Network > Start playit, open the claim link.'), row('bore.pub', 'No account. Java only.'), row('ngrok', 'Install ngrok, then:'), h('p', { class: 'mono', style: 'cursor:text' }, 'ngrok config add-authtoken YOUR_TOKEN'), h('p', { class: 'c-muted' }, 'Or set MCSHT_NGROK_AUTHTOKEN in the private .env beside the jar. Never commit .env.')),
    card('Google Drive backups', h('p', {}, 'Use rclone config and finish its browser sign-in. Credentials stay in rclone; this tool never asks you to paste Google tokens.'))) };
}

// ---------------------------------------------------------------- routing & sidebar
const PAGES = { overview: overviewView, console: consoleView, players: playersView, options: optionsView, software: softwareView, plugins: () => extView('plugin'), mods: () => extView('mod'), datapacks: () => extView('datapack'), bedrock: bedrockView,
  worlds: worldsView, files: filesView, logs: logsView, backups: backupsView, automation: automationView, startup: startupView, network: networkView, settings: settingsView };
const TOOLS = { checks: checksView, dirs: dirsView, help: helpView };
const SERVER_MENU = [
  { id: 'overview', icon: '🏠', label: 'Overview' }, { id: 'console', icon: '⌨', label: 'Console' }, { id: 'players', icon: '👥', label: 'Players' }, { id: 'options', icon: '⚙', label: 'Options' }, { id: 'software', icon: '🧬', label: 'Software' },
  { icon: '🧩', label: 'Extensions', sub: [['plugins', 'Plugins'], ['mods', 'Mods'], ['datapacks', 'Datapacks'], ['bedrock', 'Bedrock crossplay']] },
  { id: 'worlds', icon: '🌍', label: 'Worlds' }, { id: 'files', icon: '📁', label: 'Files' }, { id: 'logs', icon: '📜', label: 'Logs' }, { id: 'backups', icon: '💾', label: 'Backups' },
  { id: 'automation', icon: '⏱', label: 'Automation' }, { id: 'startup', icon: '🚀', label: 'Startup' }, { id: 'network', icon: '🌐', label: 'Network' }, { id: 'settings', icon: '🛠', label: 'Settings' },
];
let route = { kind: 'home', page: 'home' }, sbSig = '';

function parseRoute() {
  const p = location.hash.replace(/^#\//, '').split('/');
  if (p[0] === 's' && p[1]) return { kind: 'server', id: p[1], page: PAGES[p[2]] ? p[2] : 'overview' };
  if (p[0] === 'tools' && TOOLS[p[1]]) return { kind: 'tools', page: p[1] };
  return { kind: 'home', page: 'home' };
}
function buildSidebar() {
  const nav = $('#sidebar'); const s = cs(); const items = [];
  const item = (id, icon, label, fn, extra) => h('div', { class: 'nav-item', 'data-id': id, on: { click: fn } }, h('span', { class: 'ic' }, icon), h('span', {}, label), extra);
  if (route.kind === 'server' && s) {
    items.push(h('div', { class: 'sb-head' }, h('img', { src: iconUrl(s), alt: '' }), h('div', {}, h('b', {}, s.name), h('small', { id: 'sbState' }, stText[s.state]))));
    for (const m of SERVER_MENU) {
      if (!m.sub) { items.push(item(m.id, m.icon, m.label, () => goS(m.id))); continue; }
      const g = h('div', { class: 'nav-group' }, h('div', { class: 'nav-item', on: { click: () => g.classList.toggle('open') } }, h('span', { class: 'ic' }, m.icon), h('span', {}, m.label), h('span', { class: 'chev' }, '▶')),
        h('div', { class: 'submenu' }, m.sub.map(([id, l]) => h('div', { class: 'sub-item', 'data-id': id, on: { click: () => goS(id) } }, l))));
      items.push(g);
    }
    items.push(h('div', { class: 'nav-spacer' }), item('home', '⬅', 'All servers', () => go('home')));
  } else {
    items.push(item('home', '🏠', 'Home', () => go('home')), h('div', { class: 'sb-label' }, 'Servers'),
      ...S.servers.map((x) => item('srv-' + x.id, '', x.name, () => goS('overview', x.id), h('span', { class: 'dot ' + stKind(x.state) }))));
    const g = h('div', { class: 'nav-group' }, h('div', { class: 'nav-item', on: { click: () => g.classList.toggle('open') } }, h('span', { class: 'ic' }, '🧰'), h('span', {}, 'Tools'), h('span', { class: 'chev' }, '▶')),
      h('div', { class: 'submenu' }, [['checks', 'Setup checks'], ['dirs', 'Directories'], ['help', 'Accounts & tokens']].map(([id, l]) => h('div', { class: 'sub-item', 'data-id': 'tools-' + id, on: { click: () => go('tools/' + id) } }, l))));
    items.push(h('div', { class: 'sb-label' }, 'More'), g, h('div', { class: 'nav-spacer' }));
  }
  items.push(h('div', { class: 'nav-item nav-exit', on: { click: quit } }, h('span', { class: 'ic' }, '⏻'), h('span', {}, 'Exit launcher')));
  nav.replaceChildren(...items);
  // pictorial server rows
  nav.querySelectorAll('[data-id^="srv-"] .ic').forEach((ic, i) => { const x = S.servers[i]; if (x) ic.replaceChildren(h('img', { src: iconUrl(x), alt: '' })); });
  highlightNav();
}
function highlightNav() {
  const id = route.kind === 'tools' ? 'tools-' + route.page : route.kind === 'home' ? 'home' : route.page;
  document.querySelectorAll('#sidebar [data-id]').forEach((e) => e.classList.toggle('active', e.dataset.id === id));
  document.querySelectorAll('#sidebar .nav-group').forEach((g) => { if (g.querySelector('.sub-item.active')) g.classList.add('open'); });
}
function render() {
  closeMenus(); if (view && view.destroy) view.destroy();
  route = parseRoute();
  if (route.kind === 'server') { if (S.cur !== route.id) { S.cur = route.id; S.logs = []; S.last = 0; } if (S.connected && !S.by[route.id]) { go('home'); return; } } else if (S.cur) { S.cur = null; S.logs = []; S.last = 0; }
  $('#app').classList.toggle('home', route.kind !== 'server');
  view = (route.kind === 'server' ? PAGES[route.page] : route.kind === 'tools' ? TOOLS[route.page] : homeView)();
  $('#view').replaceChildren(view.el); $('#content').scrollTop = 0;
  sbSig = ''; buildSidebar(); applyStatus();
  if (view.init) view.init();
  if (route.kind === 'home' && view.update) view.update(null, S.servers); else if (cs() && view.update) view.update(cs(), S.servers);
}
async function quit() {
  if (!(await confirmBox('Exit launcher?', 'This stops every Minecraft server and tunnel, then closes MC Host.', 'Exit', true))) return;
  try { await api('/api/quit', { method: 'POST', global: true }); } catch { /* ignore */ }
  openModal(h('div', { class: 'dialog' }, h('h3', {}, 'Launcher closed'), h('p', {}, 'You can close this tab. Run the start script again to reopen it.'))); S.dead = true;
}

// ---------------------------------------------------------------- status bar / switcher
function applyStatus() {
  const s = cs(), pill = $('#pill'), txt = $('#pillText');
  if (!S.connected) { pill.className = 'pill off'; txt.textContent = 'LAUNCHER OFFLINE'; return; }
  const running_ = S.servers.filter((x) => x.state === 'online').length;
  if (s) { const k = stKind(s.state); pill.className = 'pill ' + k; txt.textContent = s.state.toUpperCase() + (s.state === 'online' ? ` · ${s.players.length}/${s.max_players}` : ''); }
  else { pill.className = 'pill ' + (running_ ? 'online' : 'off'); txt.textContent = `${running_} / ${S.servers.length} RUNNING`; }
  $('#swName').textContent = s ? s.name : 'Servers'; const si = $('#swIcon'); si.style.display = s ? '' : 'none'; if (s) si.src = iconUrl(s);
  const sbs = $('#sbState'); if (sbs && s) sbs.textContent = stText[s.state];
  if (!s) return;
  const ver = `${SW[s.software] ? SW[s.software][0] : s.software} ${s.version || 'newest'}`;
  $('#bbInfoText').textContent = `${ver} · ${s.memory}`;
  const c = { offline: ['START SERVER', ver, '', false], preparing: ['PREPARING…', 'Checking Java & jar', 'wait', true], starting: ['STARTING…', 'Click to cancel', 'wait', false], online: ['STOP SERVER', ver, 'stop', false], stopping: ['STOPPING…', 'Saving the world', 'wait', true] }[s.state];
  const play = $('#btnPlay'); play.className = 'play ' + c[2]; play.disabled = c[3]; play.replaceChildren(c[0], h('small', {}, c[1])); $('#btnRestart').hidden = !running(s);
  const bar = $('#bbBar'), fill = $('#bbFill'); bar.classList.remove('indet', 'warn');
  let task = 'Ready to launch', sub = `LAN ${s.lan}:${s.port}`, pct = 0;
  if (s.task) { task = s.task; if (s.progress >= 0) { pct = Math.round(s.progress * 100); sub = pct + '%'; } else { bar.classList.add('indet'); sub = ''; } }
  else if (s.state === 'starting') { task = 'Starting the server…'; sub = hms(s.uptime); bar.classList.add('indet', 'warn'); }
  else if (s.state === 'online') { task = 'Server is online'; sub = `up ${hms(s.uptime)} · ${s.players.length}/${s.max_players} players`; pct = 100; }
  else if (s.state === 'stopping') { task = 'Stopping…'; bar.classList.add('indet', 'warn'); sub = ''; } else if (s.busy) { task = s.busy + '…'; bar.classList.add('indet'); sub = ''; }
  $('#bbTask').textContent = task; $('#bbSub').textContent = sub; fill.style.width = pct + '%';
}
$('#btnPlay').addEventListener('click', () => { const s = cs(); if (s) toggleServer(s); });
$('#btnRestart').addEventListener('click', async () => { if (await confirmBox('Restart the server?', 'Players will be disconnected while it restarts.', 'Restart')) api('/api/restart', { method: 'POST' }).catch(() => {}); });
$('#bbInfo').addEventListener('click', (e) => {
  e.stopPropagation(); const s = cs(); if (!s) return;
  const setMem = (m) => api('/api/config', { method: 'POST', body: { memory: m } }).then(() => { toast(`Memory set to ${m}. Restart to apply.`, 'ok'); pollStatus(); }).catch(() => {});
  popupMenu($('#bbInfo'), [{ head: 'Memory (RAM)' }, ...['1G', '2G', '4G', '6G', '8G'].map((m) => ({ label: m, on: m === s.memory, run: () => setMem(m) })), { head: 'More' }, { label: 'Software & version…', run: () => goS('software') }, { label: 'Startup settings…', run: () => goS('startup') }], true);
});
$('#switcher').addEventListener('click', (e) => {
  e.stopPropagation();
  popupMenu(e.currentTarget, [{ head: 'Switch server' }, ...S.servers.map((x) => ({ label: `${x.name}  (${x.state})`, on: x.id === S.cur, run: () => goS(route.kind === 'server' ? route.page : 'overview', x.id) })), { head: 'Manage' }, { label: 'All servers', run: () => go('home') }, { label: '＋ Add server…', run: addServerDialog }]);
});

// ---------------------------------------------------------------- polling
let statusTimer = 0;
async function pollStatus() {
  clearTimeout(statusTimer); if (S.dead) return;
  try { S.servers = await api('/api/servers', { quiet: true, global: true }); S.by = Object.fromEntries(S.servers.map((x) => [x.id, x])); const was = S.connected; S.connected = true; if (!was) render(); } catch { S.connected = false; }
  applyStatus();
  const sig = S.servers.map((x) => [x.id, x.name, x.icon, route.kind === 'server' ? '' : x.state].join('|')).join('~') + route.kind;
  if (sig !== sbSig && S.connected) { sbSig = sig; buildSidebar(); }
  if (S.connected && view && view.update) { if (route.kind === 'home') view.update(null, S.servers); else if (cs()) view.update(cs(), S.servers); }
  if (route.kind === 'server' && S.connected && !cs()) go('home');
  statusTimer = setTimeout(pollStatus, 1000);
}
async function pollLogs() {
  if (S.dead) return;
  if (S.cur) {
    const id = S.cur;
    try {
      const r = await api('/api/logs?after=' + S.last, { quiet: true });
      if (id === S.cur) { if (r.last < S.last) S.logs = []; if (r.logs.length) { S.logs.push(...r.logs); if (S.logs.length > 1500) S.logs.splice(0, S.logs.length - 1500); if (view && view.onLog) view.onLog(r.logs); } S.last = r.last; }
    } catch { /* offline */ }
  }
  setTimeout(pollLogs, 700);
}
setInterval(() => { $('#clock').textContent = new Date().toLocaleTimeString(); }, 1000);
window.addEventListener('hashchange', render);
route = parseRoute(); $('#app').classList.toggle('home', route.kind !== 'server');
pollStatus(); pollLogs();
