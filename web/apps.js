// dumbOS screens for the browser preview. Each screen mirrors the matching
// Kotlin activity: same rows, same soft keys, same pixel metrics.
'use strict';

const D = {
  // device viewport in CSS px (the phone is 300x580 inside the shell)
  W: 300,
  H: 580,
  headerH: 22,
  keybarH: 46,
};

function clock() {
  const d = new Date(Date.now() + State.clockOffset);
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}
function clockSecs() {
  const d = new Date(Date.now() + State.clockOffset);
  return clock() + ':' + String(d.getSeconds()).padStart(2, '0');
}
function today() {
  const d = new Date(Date.now() + State.clockOffset);
  const days = ['DOM','LUN','MAR','MIE','JUE','VIE','SAB'];
  const months = ['ENE','FEB','MAR','ABR','MAY','JUN','JUL','AGO','SEP','OCT','NOV','DIC'];
  return days[d.getDay()] + ' ' + String(d.getDate()).padStart(2,'0') + ' ' + months[d.getMonth()];
}
function stamp(t) {
  const d = new Date(t);
  return String(d.getDate()).padStart(2,'0') + '/' + String(d.getMonth()+1).padStart(2,'0') + ' ' +
    String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0');
}
function hhmm(t) {
  const d = new Date(t);
  return String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0');
}

// ---------------------------------------------------------------------------
// Chrome: header + keybar, shared by every screen
// ---------------------------------------------------------------------------
function drawChrome(ctx, title, opts = {}) {
  const th = State.theme;
  ctx.fillStyle = th.bg;
  ctx.fillRect(0, 0, D.W, D.H);

  // header
  pill(ctx, 0, 0, D.W, D.headerH, th.panel);
  pill(ctx, 0, D.headerH - 1, D.W, 1, th.dim);
  Pixel.drawClipped(ctx, title, 6, (D.headerH - 12) / 2 + 1, D.W - 78, SZ.SMALL, th.ink);
  const right = opts.right || clock();
  Pixel.drawRight(ctx, right, D.W - 40, (D.headerH - 12) / 2 + 1, SZ.SMALL, th.dim);
  // signal bars
  const bars = navigator.onLine ? 4 : 0;
  for (let i = 0; i < 4; i++) {
    ctx.fillStyle = i < bars ? th.ink : th.dim;
    const bh = (i + 1) * 2;
    ctx.fillRect(D.W - 26 + i * 5, 17 - bh, 3, bh);
  }

  // key bar
  const ky = D.H - D.keybarH;
  pill(ctx, 0, ky, D.W, D.keybarH, th.panel);
  pill(ctx, 0, ky, D.W, 1, th.dim);
  const keys = opts.keys || ['BACK', 'OK', 'MENU'];
  const w = D.W / keys.length;
  keys.forEach((label, i) => {
    if (i > 0) pill(ctx, i * w, ky + 9, 1, D.keybarH - 18, th.dim);
    Pixel.drawCenter(ctx, label, i * w + w / 2, ky + D.keybarH / 2 - 6, SZ.SMALL, th.ink);
  });
}

function body(kind, args, ctx) {
  ctx = ctx || (typeof View !== 'undefined' ? View.ctx : null);
  const top = D.headerH;
  const h = D.H - D.headerH - D.keybarH;
  ctx.save();
  ctx.beginPath(); ctx.rect(0, top, D.W, h); ctx.clip();
  ctx.translate(0, top);
  kind(ctx, h);
  ctx.restore();
}

function keyBarLabels(labels) { View.keys = labels; }

// ---------------------------------------------------------------------------
// HOME
// ---------------------------------------------------------------------------
const Home = {
  sel: 0,
  tiles: [
    { icon:'PHONE', label:'TELEFONO', badge:'', app:'phone' },
    { icon:'SMS', label:'MENSAJES', badge:() => `${State.threads.reduce((a,t)=>a+t.unread,0)} SIN LEER`, app:'sms' },
    { icon:'MAIL', label:'CORREO', badge:() => `${State.mail.filter(m=>m.unread).length} SIN LEER`, app:'mail' },
    { icon:'NOTES', label:'NOTAS', badge:() => `${State.notes.length} NOTAS`, app:'notes' },
    { icon:'BROWSER', label:'WEB TEXTO', badge:'', app:'browser' },
    { icon:'WHATSAPP', label:'WHATSAPP', badge:'PUENTE', app:'whatsapp' },
    { icon:'RADIO', label:'RADIO FM', badge:() => `FM ${State.radioFreq.toFixed(1)} MHZ`, app:'radio' },
    { icon:'ALARM', label:'ALARMAS', badge:() => State.alarms.filter(a=>a.on).length ? `PROX ${String(State.alarms[0].h).padStart(2,'0')}:${String(State.alarms[0].m).padStart(2,'0')}` : 'SIN ALARMAS', app:'alarm' },
  ],

  draw(ctx, h) {
    const th = State.theme;
    // big clock (unit chosen like ClockView.onMeasure)
    const time = clock();
    const units = Pixel.normalize(time).split('').reduce((a,c)=>a+(c===' '?3:6),0) - 1;
    const unit = Math.max(2, Math.floor((D.W * 0.60) / units));
    const tw = units * unit;
    Pixel.draw(ctx, time, (D.W - tw) / 2, 10, { unit, small:false, adv:6, gw:5, gh:7 }, th.ink);
    Pixel.drawCenter(ctx, today(), D.W/2, 10 + 7*unit + 3, SZ.SMALL, th.dim);
    Pixel.rule(ctx, 8, 10 + 7*unit + 3 + 13, D.W - 8, th.dim);

    // telemetry two-liner
    const ty = 10 + 7*unit + 3 + 21;
    Pixel.icon(ctx, 'BELL', 6, ty, 2, State.mode === 'TODO' ? th.dim : th.ink);
    Pixel.draw(ctx, State.mode === 'NO MOLESTAR' ? 'NO MOL.' : State.mode, 6 + 44, ty - 1, SZ.SMALL, th.ink);
    const a = State.alarms.find(x => x.on);
    Pixel.drawRight(ctx, 'AL. ' + (a ? `${String(a.h).padStart(2,'0')}:${String(a.m).padStart(2,'0')}` : '--:--'), D.W - 6, ty - 1, SZ.SMALL, th.dim);
    Pixel.icon(ctx, 'ENVELOPE', 6, ty + 18, 2, th.dim);
    const inbox = State.mail.filter(m=>m.unread).length + State.threads.reduce((x,t)=>x+t.unread,0);
    Pixel.draw(ctx, inbox > 0 ? `${inbox} PENDIENTES` : 'TODO LEIDO', 6 + 44, ty + 17, SZ.SMALL, th.dim);
    Pixel.drawRight(ctx, '78%', D.W - 6, ty + 17, SZ.SMALL, th.dim);

    // tiles
    const listTop = ty + 38;
    const rowH = 40;
    const listH = h - listTop;
    const scroll = scrollFor(Home.sel, rowH, listH, Home.tiles.length);
    Home.scroll = drawList(ctx, 0, listTop, D.W, listH, {
      rowH, selected: Home.sel, scroll, theme: th, count: Home.tiles.length,
      draw(c, i, sel, rh) {
        const ink = sel ? th.bg : th.ink;
        const dim = sel ? th.bg : th.dim;
        const t = Home.tiles[i];
        Pixel.icon(c, t.icon, 4, (rh - 32) / 2, 2, ink);
        Pixel.draw(c, t.label, 40, 6, SZ.BODY, ink);
        const b = typeof t.badge === 'function' ? t.badge() : t.badge;
        Pixel.drawClipped(c, b, 40, 6 + 14 + 1, D.W - 50, SZ.SMALL, dim);
        if (sel) { Pixel.drawRight(c, 'OK', D.W - 8, rh / 2 - 6, SZ.SMALL, ink); }
      }
    });
  },
};

// ---------------------------------------------------------------------------
// PHONE
// ---------------------------------------------------------------------------
const Phone = {
  view: 'contacts', sel: 0, dialed: '',

  draw(ctx, h) {
    const th = State.theme;
    if (Phone.view === 'keypad') return Phone.keypad(ctx, h);
    const head = 'CONTACTOS ' + State.contacts.length;
    Pixel.draw(ctx, head, 6, 5, SZ.SMALL, th.dim);
    Pixel.rule(ctx, 0, 19, D.W, th.dim);
    const rowH = 34;
    const listH = h - 21;
    const scroll = scrollFor(Phone.sel, rowH, listH, State.contacts.length);
    drawList(ctx, 0, 21, D.W, listH, {
      rowH, selected: Phone.sel, scroll, theme: th, count: State.contacts.length,
      draw(c, i, sel) {
        const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
        const ct = State.contacts[i];
        Pixel.drawClipped(c, ct.name, 6, 3, D.W - 12, SZ.BODY, ink);
        Pixel.drawClipped(c, ct.number, 6, 19, D.W - 12, SZ.SMALL, dim);
      }
    });
  },

  keypad(ctx, h) {
    const th = State.theme;
    Pixel.draw(ctx, Phone.dialed || 'MARCA UN NUMERO', 8, 8, SZ.TITLE, Phone.dialed ? th.ink : th.dim);
    const g = { x: 6, y: 36, w: D.W - 12, h: h - 40 };
    const cw = g.w / 3, ch = g.h / 4;
    KEYPAD.forEach((k, i) => {
      const x = g.x + (i % 3) * cw + 2, y = g.y + Math.floor(i / 3) * ch + 2;
      const kw = cw - 4, kh = ch - 4;
      pill(ctx, x, y, kw, kh, th.panel);
      Pixel.frame(ctx, x, y, kw, kh, th.dim);
      if (k[1]) {
        Pixel.drawCenter(ctx, k[0], x + kw / 2, y + kh / 2 - 13, SZ.BODY, th.ink);
        Pixel.drawCenter(ctx, k[1], x + kw / 2, y + kh / 2 + 3, SZ.SMALL, th.dim);
      } else {
        Pixel.drawCenter(ctx, k[0], x + kw / 2, y + kh / 2 - 10, SZ.TITLE, th.ink);
      }
    });
  },
};
const KEYPAD = [['1',''],['2','ABC'],['3','DEF'],['4','GHI'],['5','JKL'],['6','MNO'],['7','PQRS'],['8','TUV'],['9','WXYZ'],['*','+'],['0','ESP'],['#','']];

// ---------------------------------------------------------------------------
// MESSAGES
// ---------------------------------------------------------------------------
const Msgs = {
  view: 'threads', sel: 0, thread: null, page: 0,

  draw(ctx, h) {
    const th = State.theme;
    if (Msgs.view === 'threads') {
      Pixel.draw(ctx, 'CONVERSACIONES ' + State.threads.length, 6, 5, SZ.SMALL, th.dim);
      Pixel.rule(ctx, 0, 22, D.W, th.dim);
      const rowH = 44, listH = h - 24;
      const scroll = scrollFor(Msgs.sel, rowH, listH, State.threads.length);
      drawList(ctx, 0, 24, D.W, listH, {
        rowH, selected: Msgs.sel, scroll, theme: th, count: State.threads.length,
        draw(c, i, sel) {
          const t = State.threads[i];
          const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
          Pixel.icon(c, 'SMS', 3, (rowH - 32) / 2, 2, t.unread ? ink : dim);
          const tm = hhmm(t.t);
          const tw = Pixel.measure(c, tm, SZ.SMALL);
          Pixel.drawClipped(c, t.name, 40, 5, D.W - 52 - tw, SZ.BODY, ink);
          if (t.unread) Pixel.drawRight(c, String(t.unread), D.W - 6, 5, SZ.SMALL, ink);
          Pixel.drawRight(c, tm, D.W - 6, 21, SZ.SMALL, dim);
          const last = t.msgs[t.msgs.length - 1].text;
          Pixel.drawClipped(c, last, 40, 21, D.W - 56 - tw, SZ.SMALL, dim);
        }
      });
      return;
    }
    // conversation: bubbles stack from the bottom like a real phone
    const t = Msgs.thread;
    const per = 3;
    const pages = Math.max(1, Math.ceil(t.msgs.length / per));
    Msgs.page = Math.min(Msgs.page, pages - 1);
    const slice = t.msgs.slice(Msgs.page * per, Msgs.page * per + per);
    const bubbles = [];
    let total = 0;
    for (const m of slice) {
      const lines = Pixel.wrap(ctx, m.text || '', D.W - 44, SZ.SMALL).slice(0, 3);
      const wText = Math.min(D.W - 16, Math.max(...lines.map(l => Pixel.measure(ctx, l, SZ.SMALL))) + 16);
      const bh = lines.length * 12 + 22;
      bubbles.push({ m, lines, bh, bw: Math.max(120, wText) });
      total += bh + 8;
    }
    let y = Math.max(6, h - 16 - total);
    for (const b of bubbles) {
      const left = b.m.me ? Math.max(4, D.W - b.bw - 6) : 4;
      pill(ctx, left, y, b.bw, b.bh, th.panel);
      Pixel.frame(ctx, left, y, b.bw, b.bh, b.m.me ? th.ink : th.dim);
      let ty = y + 5;
      for (const line of b.lines) { Pixel.draw(ctx, line, left + 7, ty, SZ.SMALL, th.ink); ty += 12; }
      const stampTxt = isFinite(b.m.t) ? hhmm(b.m.t) : '';
      Pixel.draw(ctx, stampTxt, b.m.me ? left + b.bw - 6 - Pixel.measure(ctx, stampTxt, SZ.SMALL) : left + 7, y + b.bh - 13, SZ.SMALL, th.dim);
      y += b.bh + 8;
    }
    // page dots
    for (let i = 0; i < pages; i++) {
      const on = i === Msgs.page;
      pill(ctx, D.W / 2 - pages * 5 + i * 10, h - 11, on ? 6 : 4, on ? 4 : 2, on ? th.ink : th.dim);
    }
  },
};

// ---------------------------------------------------------------------------
// SETTINGS
// ---------------------------------------------------------------------------
const Settings = {
  sel: 0,
  items: () => ([
    { section:'SONIDO' },
    { label:'MODO DE NOTIFICACIONES', value: () => State.mode, act:'mode' },
    { label:'TIMBRE / VIBRACION', value: () => State.sound ? 'SI' : 'NO', act:'sound' },
    { label:'NO MOLESTAR DEL SISTEMA', value: () => State.mode === 'NO MOLESTAR' ? 'ACTIVO' : 'INACTIVO', act:'dnd' },
    { section:'PANTALLA' },
    { label:'TEMA / COLOR', value: () => State.theme.name, act:'theme' },
    { label:'TEXTO GRANDE', value: () => 'NO', act:'noop' },
    { label:'FONDO DE PANTALLA', value: () => 'MONOCROMO', act:'noop' },
    { section:'DUMBOS' },
    { label:'ACCESO A NOTIFICACIONES', value: () => 'PENDIENTE', act:'noop' },
    { label:'PERMISOS SMS Y CONTACTOS', value: () => 'CONCEDIDOS', act:'noop' },
    { label:'ALARMAS EXACTAS', value: () => 'OK', act:'noop' },
    { label:'SER EL LAUNCHER', value: () => 'SI', act:'noop' },
    { section:'ESTADO' },
    { label:'BATERIA', value: () => '78%', act:'noop' },
    { label:'ALARMAS ACTIVAS', value: () => String(State.alarms.filter(a=>a.on).length), act:'alarms' },
    { label:'MENSAJES SIN LEER', value: () => String(State.threads.reduce((x,t)=>x+t.unread,0)), act:'sms' },
    { label:'ACERCA DE DUMBOS', value: () => '0.1.0', act:'about' },
  ]),

  draw(ctx, h) {
    const th = State.theme;
    const items = Settings.items();
    const rowH = 36;
    const scroll = scrollFor(Settings.sel, rowH, h, items.length);
    drawList(ctx, 0, 0, D.W, h, {
      rowH, selected: Settings.sel, scroll, theme: th, count: items.length,
      draw(c, i, sel) {
        const it = items[i];
        const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
        if (it.section) {
          Pixel.draw(c, it.section, 6, (rowH - 12)/2, SZ.SMALL, dim);
          Pixel.rule(c, 40, rowH/2 + 6, D.W - 8, sel ? th.bg : th.dim, true);
        } else {
          let val = typeof it.value === 'function' ? it.value() : it.value;
          if (val === 'CONCEDIDOS') val = 'OK';
          if (val === 'PENDIENTE') val = 'NO';
          if (val === 'INACTIVO') val = 'OFF';
          if (val === 'ACTIVO') val = 'ON';
          if (val === 'FOSFORO VERDE') val = 'FOSFORO';
          if (val === 'MONOCROMO') val = 'MONO';
          const vw = Pixel.measure(c, val, SZ.SMALL);
          Pixel.drawClipped(c, it.label, 6, 4, D.W - 22 - vw, SZ.BODY, ink);
          Pixel.draw(c, val, D.W - 8 - vw, 20, SZ.SMALL, dim);
        }
      }
    });
  },
};

// ---------------------------------------------------------------------------
// MAIL
// ---------------------------------------------------------------------------
const MailApp = {
  view: 'inbox', sel: 0, msg: null,
  draw(ctx, h) {
    const th = State.theme;
    if (MailApp.view === 'inbox') {
      Pixel.draw(ctx, 'BUZON LOCAL - SIN CUENTA', 6, 5, SZ.SMALL, th.dim);
      Pixel.rule(ctx, 0, 22, D.W, th.dim);
      const rowH = 48, listH = h - 24;
      const scroll = scrollFor(MailApp.sel, rowH, listH, State.mail.length);
      drawList(ctx, 0, 24, D.W, listH, {
        rowH, selected: MailApp.sel, scroll, theme: th, count: State.mail.length,
        draw(c, i, sel) {
          const m = State.mail[i];
          const ink = sel ? th.bg : (m.unread ? th.ink : th.dim), dim = sel ? th.bg : th.dim;
          if (m.unread) Pixel.icon(c, 'DOT', 4, rowH/2 - 6, 2, ink);
          Pixel.drawClipped(c, m.from, 10, 5, D.W - 60, SZ.BODY, ink);
          Pixel.drawRight(c, hhmm(m.t), D.W - 6, 5, SZ.SMALL, dim);
          Pixel.drawClipped(c, m.subject, 10, 22, D.W - 18, SZ.SMALL, dim);
        }
      });
      return;
    }
    const m = MailApp.msg;
    Pixel.draw(ctx, 'DE ' + m.from, 6, 5, SZ.SMALL, th.dim);
    Pixel.draw(ctx, stamp(m.t), 6, 17, SZ.SMALL, th.dim);
    Pixel.drawClipped(ctx, m.subject, 6, 34, D.W - 12, SZ.BODY, th.ink);
    Pixel.rule(ctx, 0, 52, D.W, th.dim);
    let y = 60;
    for (const line of Pixel.wrap(ctx, m.body, D.W - 12, SZ.SMALL)) {
      Pixel.draw(ctx, line, 6, y, SZ.SMALL, th.ink);
      y += 13;
    }
  },
};

// ---------------------------------------------------------------------------
// NOTES
// ---------------------------------------------------------------------------
const NotesApp = {
  view: 'list', sel: 0, note: null,
  draw(ctx, h) {
    const th = State.theme;
    if (NotesApp.view === 'list') {
      Pixel.rule(ctx, 0, 0, D.W, th.dim);
      const rowH = 46;
      const scroll = scrollFor(NotesApp.sel, rowH, h, State.notes.length);
      drawList(ctx, 0, 0, D.W, h, {
        rowH, selected: NotesApp.sel, scroll, theme: th, count: State.notes.length,
        draw(c, i, sel) {
          const n = State.notes[i];
          const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
          Pixel.icon(c, 'PENCIL', 4, rowH / 2 - 16, 2, dim);
          const tm = hhmm(n.t);
          Pixel.drawClipped(c, n.title, 12, 5, D.W - 24 - Pixel.measure(c, tm, SZ.SMALL), SZ.BODY, ink);
          Pixel.drawRight(c, tm, D.W - 6, 5, SZ.SMALL, dim);
          Pixel.drawClipped(c, n.body.split('\n')[0], 12, 21, D.W - 20, SZ.SMALL, sel ? th.bg : th.dim);
        }
      });
      return;
    }
    const n = NotesApp.note;
    Pixel.draw(ctx, stamp(n.t), 6, 5, SZ.SMALL, th.dim);
    Pixel.drawClipped(ctx, n.title, 6, 20, D.W - 12, SZ.BODY, th.ink);
    Pixel.rule(ctx, 0, 38, D.W, th.dim);
    let y = 46;
    for (const line of Pixel.wrap(ctx, n.body, D.W - 12, SZ.SMALL)) {
      Pixel.draw(ctx, line, 6, y, SZ.SMALL, th.ink);
      y += 13;
    }
  },
};

// ---------------------------------------------------------------------------
// BROWSER
// ---------------------------------------------------------------------------
const PAGES = {
  'https://example.com': { title:'EXAMPLE DOMAIN', text:"THIS DOMAIN IS FOR USE IN ILLUSTRATIVE EXAMPLES IN DOCUMENTS.\n\nYOU MAY USE THIS DOMAIN IN LITERATURE WITHOUT PRIOR COORDINATION OR PERMISSION.\n\nEXAMPLE.COM IS A SMALL, FAST PAGE. EXACTLY THE KIND OF PAGE DUMBOS WAS BUILT FOR.", links:[] },
  'https://lite.cnn.com': { title:'CNN LITE', text:'ULTIMA HORA\n\nEL PARLAMENTO APRUEBA LA LEY DE MOVILIDAD.\n\nEL PARTIDO DE ANOCHE TERMINO 3-2 EN EL ULTIMO MINUTO.\n\nMANANA: VIENTO DEL OESTE, 18 GRADOS.', links:[] },
  'https://text.npr.org': { title:'NPR TEXT ONLY', text:'NEWS\n\nA TEXT ONLY VIEW OF THE NEWS. NO VIDEO, NO AUTOPLAY, NO POPUPS.\n\nTHE STORY CONTINUES ON PAGE TWO.', links:[] },
};

const BrowserApp = {
  view: 'home', sel: 0, url: '', page: null, loading: false,
  HOME: [
    'https://example.com',
    'https://lite.cnn.com',
    'https://text.npr.org',
  ],
  draw(ctx, h) {
    const th = State.theme;
    if (BrowserApp.view === 'home') {
      let y = 8;
      for (const line of Pixel.wrap(ctx, 'WEB DE SOLO TEXTO.\nSIN IMAGENES NI ANUNCIOS.', D.W - 16, SZ.SMALL)) {
        Pixel.draw(ctx, line, 8, y, SZ.SMALL, th.dim); y += 13;
      }
      Pixel.rule(ctx, 0, y + 6, D.W, th.dim);
      const listTop = y + 10;
      const rowH = 32, listH = h - listTop;
      const scroll = scrollFor(BrowserApp.sel, rowH, listH, BrowserApp.HOME.length);
      drawList(ctx, 0, listTop, D.W, listH, {
        rowH, selected: BrowserApp.sel, scroll, theme: th, count: BrowserApp.HOME.length,
        draw(c, i, sel) {
          const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
          Pixel.icon(c, 'GLOBE', 4, (rowH-16)/2, 1, dim);
          Pixel.drawClipped(c, (i+1) + '. ' + PAGES[BrowserApp.HOME[i]].title, 26, (rowH-14)/2, D.W - 34, SZ.BODY, ink);
        }
      });
      return;
    }
    if (BrowserApp.loading) {
      Pixel.draw(ctx, 'CONECTANDO...', 8, 10, SZ.BODY, th.ink);
      Pixel.draw(ctx, BrowserApp.url.replace('https://',''), 8, 30, SZ.SMALL, th.dim);
      pill(ctx, 8, 48, D.W - 16, 1, th.dim);
      return;
    }
    const p = BrowserApp.page;
    Pixel.drawClipped(ctx, BrowserApp.url, 6, 5, D.W - 12, SZ.SMALL, th.dim);
    Pixel.rule(ctx, 0, 22, D.W, th.dim);
    Pixel.drawClipped(ctx, p.title, 6, 27, D.W - 12, SZ.BODY, th.ink);
    let y = 48;
    for (const line of Pixel.wrap(ctx, p.text, D.W - 12, SZ.SMALL)) {
      if (y > h - 14) break;
      Pixel.draw(ctx, line, 6, y, SZ.SMALL, th.ink);
      y += 13;
    }
  },
  go(url) {
    BrowserApp.url = url;
    BrowserApp.loading = true;
    BrowserApp.view = 'page';
    setTimeout(() => {
      BrowserApp.loading = false;
      BrowserApp.page = PAGES[url] || { title: url.replace('https://','').toUpperCase(), text:'PAGINA DE DEMOSTRACION.\n\nEN EL TELEFONO, DUMBOS DESCARGA LA PAGINA REAL, QUITA TODAS LAS ETIQUETAS Y MUESTRA SOLO EL TEXTO.', links: [] };
    }, 420);
  },
};

// ---------------------------------------------------------------------------
// WHATSAPP BRIDGE
// ---------------------------------------------------------------------------
const WA = {
  view: 'list', sel: 0, conv: null,
  convs() {
    const map = new Map();
    for (const m of State.wa) {
      if (!map.has(m.conv)) map.set(m.conv, []);
      map.get(m.conv).push(m);
    }
    return [...map.entries()].sort((a,b)=>b[1][b[1].length-1].t - a[1][a[1].length-1].t);
  },
  draw(ctx, h) {
    const th = State.theme;
    const convs = WA.convs();
    if (WA.view === 'list') {
      Pixel.draw(ctx, 'PUENTE DE NOTIFICACIONES', 6, 5, SZ.SMALL, th.dim);
      Pixel.rule(ctx, 0, 22, D.W, th.dim);
      const rowH = 44, listH = h - 24;
      const scroll = scrollFor(WA.sel, rowH, listH, convs.length);
      drawList(ctx, 0, 24, D.W, listH, {
        rowH, selected: WA.sel, scroll, theme: th, count: convs.length,
        draw(c, i, sel) {
          const [name, msgs] = convs[i];
          const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
          Pixel.drawClipped(c, name, 6, 5, D.W - 64, SZ.BODY, ink);
          Pixel.drawRight(c, hhmm(msgs[msgs.length-1].t), D.W - 6, 5, SZ.SMALL, dim);
          Pixel.drawClipped(c, msgs[msgs.length-1].text, 6, 21, D.W - 22, SZ.SMALL, dim);
        }
      });
      return;
    }
    const msgs = convs.find(c => c[0] === WA.conv)?.[1] || [];
    Pixel.rule(ctx, 0, 0, D.W, th.dim);
    let y = 6;
    for (const m of msgs) {
      Pixel.draw(ctx, m.app, 4, y, SZ.SMALL, th.dim);
      Pixel.drawRight(ctx, hhmm(m.t), D.W - 4, y, SZ.SMALL, th.dim);
      y += 14;
      for (const line of Pixel.wrap(ctx, m.text, D.W - 12, SZ.SMALL).slice(0, 2)) {
        Pixel.draw(ctx, line, 4, y, SZ.SMALL, th.ink);
        y += 12;
      }
      y += 6;
    }
    Pixel.rule(ctx, 0, h - 20, D.W, th.dim);
    Pixel.draw(ctx, '[ ESCRIBE UNA RESPUESTA ]', 6, h - 14, SZ.SMALL, th.dim);
  },
};

// ---------------------------------------------------------------------------
// RADIO FM (the dial)
// ---------------------------------------------------------------------------
// The catalogue comes from tools/stations.json via tools/gen_stations.py, so the
// preview and the APK always agree on frequencies and stream URLs.
const STATION_PAIRS = STATIONS.map(s => [s.freq, s.name]);

function drawRadio(ctx, h, dragging) {
  const th = State.theme;
  const margin = 14;
  const xOf = f => margin + (D.W - margin * 2) * ((f - 87.5) / (108 - 87.5));
  const freq = State.radioFreq;

  const txt = freq.toFixed(1);
  Pixel.drawCenter(ctx, txt, D.W/2, 8, SZ.TITLE, th.ink);
  Pixel.draw(ctx, 'MHZ', D.W/2 + Pixel.measure(ctx, txt, SZ.TITLE)/2 + 8, 8 + 21 - 12, SZ.SMALL, th.dim);
  // Name of whatever the pointer is on: the dial has to say what you tuned.
  const here = (typeof StationMeta === 'function') ? StationMeta(freq) : null;
  Pixel.drawCenter(ctx, here ? here.name : 'SIN EMISORA', D.W/2, 34, SZ.SMALL, here ? th.ink : th.dim);

  const cy = Math.floor(h * 0.30);
  pill(ctx, margin, cy - 30, D.W - margin*2, 1, th.dim);
  pill(ctx, margin, cy + 30, D.W - margin*2, 1, th.dim);
  for (let f = 87.5; f <= 108.01; f += 0.5) {
    const x = xOf(f);
    const whole = Math.abs((f * 10) % 10) < 0.001;
    const tick = whole ? 7 : 4;
    ctx.fillStyle = Math.abs(f - freq) < 0.25 ? th.ink : th.dim;
    // upward from the lower rail, so nothing can collide with the numbers
    ctx.fillRect(x, cy + 30 - tick, 1, tick);
    if (whole && Math.floor(f) % 3 === 0) {
      Pixel.drawCenter(ctx, String(Math.floor(f)), x, cy + 34, SZ.SMALL, th.dim);
    }
  }
  // nearest station lock
  let near = null, best = 0.45;
  for (const [f, name] of STATION_PAIRS) { const d = Math.abs(f - freq); if (d < best) { best = d; near = [f, name]; } }
  const strength = near ? Math.max(0, 1 - best / 0.45) : 0;

  // pointer
  const px = xOf(freq);
  pill(ctx, px - 1, cy - 34, 2, 68, th.ink);
  for (let i = 0; i < 6; i++) pill(ctx, px - i, cy - 38 + i, i*2+1, 1, th.ink);

  // signal bars
  for (let i = 0; i < 5; i++) {
    const bh = (i+1) * 3;
    ctx.fillStyle = strength * 5 > i ? th.ink : th.dim;
    ctx.fillRect(D.W - 52 + i * 8, cy - 20 - bh, 4, bh);
  }
  // static
  if (strength < 0.98) {
    let seed = Math.floor(freq * 31);
    const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
    for (let x = margin; x < D.W - margin; x += 4) {
      const hh = Math.floor(rnd() * (1 - strength) * 12);
      pill(ctx, x, cy + 52 - hh, 2, hh, th.dim, 0.75);
    }
  }

  // station card
  const cardY = Math.max(cy + 70, Math.floor(h * 0.62));
  pill(ctx, 6, cardY, D.W - 12, 78, th.panel);
  Pixel.frame(ctx, 6, cardY, D.W - 12, 76, near ? th.ink : th.dim);
  if (near) {
    Pixel.drawCenter(ctx, near[1], D.W/2, cardY + 8, SZ.BODY, th.ink);
    Pixel.drawCenter(ctx, near[0].toFixed(1) + ' MHZ', D.W/2, cardY + 25, SZ.SMALL, th.dim);
    // Real state from the player, never a decorative label.
    const audioStatus = (typeof RadioAudio !== 'undefined') ? RadioAudio.status() : '';
    const live = (typeof RadioAudio !== 'undefined') ? RadioAudio.isWanted() : State.radioOn;
    const st = audioStatus || (live ? 'CONECTANDO' : 'OK PARA SONAR');
    Pixel.drawCenter(ctx, st, D.W/2, cardY + 41, SZ.SMALL, live ? th.ink : th.dim);
    // Say plainly which radio this is: the browser has no aerial either.
    Pixel.drawCenter(ctx, 'POR INTERNET', D.W/2, cardY + 56, SZ.SMALL, th.dim);
  } else {
    Pixel.drawCenter(ctx, 'SIN EMISORA', D.W/2, cardY + 14, SZ.BODY, th.dim);
    Pixel.drawCenter(ctx, 'SINTONIZA', D.W/2, cardY + 32, SZ.SMALL, th.dim);
  }
  // hint
  Pixel.drawCenter(ctx, 'ARRASTRA EL DIAL', D.W/2, h - 12, SZ.SMALL, th.dim);
}

// ---------------------------------------------------------------------------
// ALARMS
// ---------------------------------------------------------------------------
const AlarmApp = {
  sel: 0,
  draw(ctx, h) {
    const th = State.theme;
    pill(ctx, 0, 0, D.W, 44, th.panel);
    pill(ctx, 0, 43, D.W, 1, th.dim);
    Pixel.draw(ctx, 'PROXIMA ALARMA', 6, 5, SZ.SMALL, th.dim);
    Pixel.draw(ctx, 'TEMPORIZADOR', 6, 23, SZ.SMALL, th.dim);
    const a = State.alarms.find(x => x.on);
    Pixel.drawRight(ctx, a ? `${String(a.h).padStart(2,'0')}:${String(a.m).padStart(2,'0')}` : '--:--', D.W - 6, 2, SZ.TITLE, th.ink);
    Pixel.drawRight(ctx, '--:--', D.W - 6, 21, SZ.BODY, th.dim);

    const rowH = 46, listTop = 46, listH = h - listTop;
    const scroll = scrollFor(AlarmApp.sel, rowH, listH, State.alarms.length);
    drawList(ctx, 0, listTop, D.W, listH, {
      rowH, selected: AlarmApp.sel, scroll, theme: th, count: State.alarms.length,
      draw(c, i, sel) {
        const al = State.alarms[i];
        const ink = sel ? th.bg : (al.on ? th.ink : th.dim), dim = sel ? th.bg : th.dim;
        Pixel.draw(c, `${String(al.h).padStart(2,'0')}:${String(al.m).padStart(2,'0')}`, 6, 4, SZ.TITLE, ink);
        Pixel.drawRight(c, al.on ? 'ON' : 'OFF', D.W - 6, 4, SZ.SMALL, dim);
        Pixel.drawClipped(c, al.label, 6, 29, D.W - 50, SZ.SMALL, dim);
        const names = ['LU','MA','MI','JU','VI','SA','DO'];
        Pixel.drawRight(c, al.days.length === 7 ? 'DIARIO' : al.days.map(d=>names[d-1]).join(' '), D.W - 6, 29, SZ.SMALL, dim);
      }
    });
  },
};
