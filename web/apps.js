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
    // Leave a real margin on both sides: at 0.60 the clock clipped on 360dp.
    const unit = Math.max(2, Math.floor((D.W * 0.86) / units));
    const tw = units * unit;
    Pixel.draw(ctx, time, Math.round((D.W - tw) / 2), 10, { unit, small:false, adv:6, gw:5, gh:7 }, th.ink);
    const dateY = 10 + 7 * unit + 6;
    Pixel.drawCenter(ctx, today(), D.W/2, dateY, SZ.SMALL, th.dim);
    Pixel.rule(ctx, 8, dateY + SZ.SMALL.unit * 6 + 4, D.W - 8, th.dim);

    // telemetry two-liner
    const ty = 10 + 7 * unit + 6 + SZ.SMALL.unit * 6 + 4 + 8;
    Pixel.icon(ctx, 'BELL', 6, ty, 2, State.mode === 'TODO' ? th.dim : th.ink);
    Pixel.draw(ctx, State.mode === 'NO MOLESTAR' ? 'NO MOL.' : State.mode, 6 + 44, ty - 1, SZ.SMALL, th.ink);
    const a = State.alarms.find(x => x.on);
    Pixel.drawRight(ctx, 'AL. ' + (a ? `${String(a.h).padStart(2,'0')}:${String(a.m).padStart(2,'0')}` : '--:--'), D.W - 6, ty - 1, SZ.SMALL, th.dim);
    Pixel.icon(ctx, 'ENVELOPE', 6, ty + 18, 2, th.dim);
    const inbox = State.mail.filter(m=>m.unread).length + State.threads.reduce((x,t)=>x+t.unread,0);
    Pixel.draw(ctx, inbox > 0 ? `${inbox} PENDIENTES` : 'TODO LEIDO', 6 + 44, ty + 17, SZ.SMALL, th.dim);
    Pixel.drawRight(ctx, '78%', D.W - 6, ty + 17, SZ.SMALL, th.dim);

    // tiles: the row must fit body + small + air, or the badge overlaps the
    // next label (this is exactly what the user saw).
    const listTop = ty + 38;
    const rowH = SZ.BODY.unit * 7 + SZ.SMALL.unit * 6 + 14;
    const listH = h - listTop;
    const scroll = scrollFor(Home.sel, rowH, listH, Home.tiles.length);
    Home.scroll = drawList(ctx, 0, listTop, D.W, listH, {
      rowH, selected: Home.sel, scroll, theme: th, count: Home.tiles.length,
      draw(c, i, sel, rh) {
        const ink = sel ? th.bg : th.ink;
        const dim = sel ? th.bg : th.dim;
        const t = Home.tiles[i];
        Pixel.icon(c, t.icon, 4, (rh - 32) / 2, 2, ink);
        // Centre the two-line block in the real row height.
        const lineH = SZ.BODY.unit * 7, smallH = SZ.SMALL.unit * 6;
        const top = Math.round((rh - (lineH + 3 + smallH)) / 2);
        Pixel.draw(c, t.label, 40, top, SZ.BODY, ink);
        const b = typeof t.badge === 'function' ? t.badge() : t.badge;
        Pixel.drawClipped(c, b, 40, top + lineH + 3, D.W - 50, SZ.SMALL, dim);
        if (sel) { Pixel.drawRight(c, 'OK', D.W - 8, rh / 2 - smallH / 2, SZ.SMALL, ink); }
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
    const smallH = SZ.SMALL.unit * 6, bodyH = SZ.BODY.unit * 7;
    const head = 'CONTACTOS ' + State.contacts.length;
    Pixel.draw(ctx, head, 6, 4, SZ.SMALL, th.dim);
    Pixel.rule(ctx, 0, smallH + 6, D.W, th.dim);
    const listTop = smallH + 8;
    const rowH = bodyH + smallH + 14;
    const listH = h - listTop;
    const scroll = scrollFor(Phone.sel, rowH, listH, State.contacts.length);
    drawList(ctx, 0, listTop, D.W, listH, {
      rowH, selected: Phone.sel, scroll, theme: th, count: State.contacts.length,
      draw(c, i, sel, rh) {
        const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
        const ct = State.contacts[i];
        const top = Math.round((rh - (bodyH + 2 + smallH)) / 2);
        Pixel.drawClipped(c, ct.name, 6, top, D.W - 12, SZ.BODY, ink);
        Pixel.drawClipped(c, ct.number, 6, top + bodyH + 2, D.W - 12, SZ.SMALL, dim);
      }
    });
  },

  keypad(ctx, h) {
    const th = State.theme;
    const titleH = SZ.TITLE.unit * 7;
    Pixel.draw(ctx, Phone.dialed || 'MARCA UN NUMERO', 8, 4, SZ.TITLE, Phone.dialed ? th.ink : th.dim);
    const gy = 4 + titleH + 8;
    const g = { x: 6, y: gy, w: D.W - 12, h: h - gy - 4 };
    const cw = g.w / 3, ch = g.h / 4;
    KEYPAD.forEach((k, i) => {
      const x = g.x + (i % 3) * cw + 2, y = g.y + Math.floor(i / 3) * ch + 2;
      const kw = cw - 4, kh = ch - 4;
      pill(ctx, x, y, kw, kh, th.panel);
      Pixel.frame(ctx, x, y, kw, kh, th.dim);
      if (k[1]) {
        const bh = SZ.BODY.unit * 7, sh = SZ.SMALL.unit * 6;
        const blockTop = Math.round(y + kh / 2 - (bh + 2 + sh) / 2);
        Pixel.drawCenter(ctx, k[0], x + kw / 2, blockTop, SZ.BODY, th.ink);
        Pixel.drawCenter(ctx, k[1], x + kw / 2, blockTop + bh + 2, SZ.SMALL, th.dim);
      } else {
        Pixel.drawCenter(ctx, k[0], x + kw / 2, Math.round(y + kh / 2 - SZ.TITLE.unit * 7 / 2), SZ.TITLE, th.ink);
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
        draw(c, i, sel, rh) {
          const t = State.threads[i];
          const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
          // One centred two-line block: name+time on top, preview below.
          const lineH = SZ.BODY.unit * 7, smallH = SZ.SMALL.unit * 6;
          const top = Math.round((rh - (lineH + 3 + smallH)) / 2);
          Pixel.icon(c, 'SMS', 3, (rh - 32) / 2, 2, t.unread ? ink : dim);
          const tm = hhmm(t.t);
          const tw = Pixel.measure(c, tm, SZ.SMALL);
          Pixel.drawClipped(c, t.name, 40, top, D.W - 52 - tw, SZ.BODY, ink);
          if (t.unread) Pixel.drawRight(c, String(t.unread), D.W - 6, top, SZ.SMALL, ink);
          Pixel.drawRight(c, tm, D.W - 6, top + lineH + 3, SZ.SMALL, dim);
          const last = t.msgs[t.msgs.length - 1].text;
          Pixel.drawClipped(c, last, 40, top + lineH + 3, D.W - 56 - tw, SZ.SMALL, dim);
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
    // Everything is derived from the real font metrics: padding + n lines +
    // one stamp line. Hard-coding 12/22 put the timestamp on top of the text.
    const sLine = SZ.SMALL.unit * 6;
    const PAD = 5, GAP = 4;
    const bubbles = [];
    let total = 0;
    for (const m of slice) {
      const lines = Pixel.wrap(ctx, m.text || '', D.W - 44, SZ.SMALL).slice(0, 3);
      const wText = Math.min(D.W - 16, Math.max(...lines.map(l => Pixel.measure(ctx, l, SZ.SMALL))) + 16);
      const bh = PAD + lines.length * (sLine + 1) + GAP + sLine + PAD;
      bubbles.push({ m, lines, bh, bw: Math.max(120, wText) });
      total += bh + 8;
    }
    let y = Math.max(6, h - 16 - total);
    for (const b of bubbles) {
      const left = b.m.me ? Math.max(4, D.W - b.bw - 6) : 4;
      pill(ctx, left, y, b.bw, b.bh, th.panel);
      Pixel.frame(ctx, left, y, b.bw, b.bh, b.m.me ? th.ink : th.dim);
      let ty = y + PAD;
      for (const line of b.lines) { Pixel.draw(ctx, line, left + 7, ty, SZ.SMALL, th.ink); ty += sLine + 1; }
      const stampTxt = isFinite(b.m.t) ? hhmm(b.m.t) : '';
      // Stamp lives in its own reserved line, right-aligned, never over text.
      const stampY = ty + GAP - 2;
      Pixel.draw(ctx, stampTxt, b.m.me ? left + b.bw - 6 - Pixel.measure(ctx, stampTxt, SZ.SMALL) : left + 7, stampY, SZ.SMALL, th.dim);
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
    { label:'AVISOS', value: () => State.mode, act:'mode' },
    { label:'TIMBRE', value: () => State.sound ? 'SI' : 'NO', act:'sound' },
    { label:'NO MOLESTAR', value: () => State.mode === 'NO MOLESTAR' ? 'ACTIVO' : 'INACTIVO', act:'dnd' },
    { section:'PANTALLA' },
    { label:'TEMA / COLOR', value: () => State.theme.name, act:'theme' },
    { label:'TEXTO GRANDE', value: () => 'NO', act:'noop' },
    { label:'FONDO', value: () => 'MONOCROMO', act:'noop' },
    { section:'DUMBOS' },
    { label:'ACCESO AVISOS', value: () => 'PENDIENTE', act:'noop' },
    { label:'PERMISOS', value: () => 'CONCEDIDOS', act:'noop' },
    { label:'ALARMAS EXACTAS', value: () => 'OK', act:'noop' },
    { label:'SER LAUNCHER', value: () => 'SI', act:'noop' },
    { section:'ESTADO' },
    { label:'BATERIA', value: () => '78%', act:'noop' },
    { label:'ALARMAS ACTIVAS', value: () => String(State.alarms.filter(a=>a.on).length), act:'alarms' },
    { label:'MENSAJES SIN LEER', value: () => String(State.threads.reduce((x,t)=>x+t.unread,0)), act:'sms' },
    { label:'ACERCA DE DUMBOS', value: () => '1.0.6', act:'about' },
  ]),

  draw(ctx, h) {
    const th = State.theme;
    const items = Settings.items();
    // Rows are sized from the fonts; a section is a short row of its own
    // instead of sharing the row height and bleeding into the next item.
    const rowH = SZ.BODY.unit * 7 + 16;
    const secH = SZ.SMALL.unit * 6 + 8;
    const heights = items.map(it => it.section ? secH : rowH);
    const scroll = scrollFor(Settings.sel, rowH, h, items.length);
    drawList(ctx, 0, 0, D.W, h, {
      heights, rowH, selected: Settings.sel, scroll, theme: th, count: items.length,
      noSelectBar: (i) => !!items[i].section,
      draw(c, i, sel) {
        const it = items[i];
        const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
        if (it.section) {
          Pixel.draw(c, it.section, 6, Math.round((secH - SZ.SMALL.unit * 6) / 2), SZ.SMALL, dim);
          Pixel.rule(c, 40, Math.round(secH / 2), D.W - 8, sel ? th.bg : th.dim, true);
        } else {
          let val = typeof it.value === 'function' ? it.value() : it.value;
          if (val === 'CONCEDIDOS') val = 'OK';
          if (val === 'PENDIENTE') val = 'NO';
          if (val === 'INACTIVO') val = 'OFF';
          if (val === 'ACTIVO') val = 'ON';
          if (val === 'FOSFORO VERDE') val = 'FOSFORO';
          if (val === 'MONOCROMO') val = 'MONO';
          // Long values steal the room the label needs; shrink the value to a
          // short form instead of truncating the thing being configured.
          const SHORT = { 'SILENCIO':'SIL', 'NO MOLESTAR':'NMD', 'FOSFORO':'FOS' };
          if (Pixel.measure(c, val, SZ.SMALL) > D.W * 0.28 && SHORT[val]) val = SHORT[val];
          const vw = Pixel.measure(c, val, SZ.SMALL);
          const bodyH = SZ.BODY.unit * 7;
          // Label and value share one baseline, both vertically centred.
          const top = Math.round((rowH - bodyH) / 2);
          Pixel.drawClipped(c, it.label, 6, top, D.W - 22 - vw, SZ.BODY, ink);
          Pixel.draw(c, val, D.W - 8 - vw, top + bodyH - SZ.SMALL.unit * 6, SZ.SMALL, dim);
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
      const smallH = SZ.SMALL.unit * 6;
      Pixel.draw(ctx, 'BUZON LOCAL - SIN CUENTA', 6, 4, SZ.SMALL, th.dim);
      Pixel.rule(ctx, 0, smallH + 6, D.W, th.dim);
      const hdr = smallH + 8;
      // Rows come from the fonts: from + subject must each own a line.
      const rowH = SZ.BODY.unit * 7 + smallH + 14, listH = h - hdr;
      const scroll = scrollFor(MailApp.sel, rowH, listH, State.mail.length);
      drawList(ctx, 0, hdr, D.W, listH, {
        rowH, selected: MailApp.sel, scroll, theme: th, count: State.mail.length,
        draw(c, i, sel, rh) {
          const m = State.mail[i];
          const ink = sel ? th.bg : (m.unread ? th.ink : th.dim), dim = sel ? th.bg : th.dim;
          const lineH = SZ.BODY.unit * 7;   // smallH comes from the inbox header
          const top = Math.round((rh - (lineH + 3 + smallH)) / 2);
          if (m.unread) Pixel.icon(c, 'DOT', 2, Math.round((rh - 12) / 2), 2, ink);
          const tm = hhmm(m.t);
          Pixel.drawClipped(c, m.from, 12, top, D.W - 14 - Pixel.measure(c, tm, SZ.SMALL), SZ.BODY, ink);
          Pixel.drawRight(c, tm, D.W - 6, top, SZ.SMALL, dim);
          Pixel.drawClipped(c, m.subject, 12, top + lineH + 3, D.W - 20, SZ.SMALL, dim);
        }
      });
      return;
    }
    const m = MailApp.msg;
    const smallH = SZ.SMALL.unit * 6;
    let hy = 4;
    Pixel.draw(ctx, 'DE ' + m.from, 6, hy, SZ.SMALL, th.dim); hy += smallH + 2;
    Pixel.draw(ctx, stamp(m.t), 6, hy, SZ.SMALL, th.dim); hy += smallH + 6;
    Pixel.drawClipped(ctx, m.subject, 6, hy, D.W - 12, SZ.BODY, th.ink); hy += SZ.BODY.unit * 7 + 8;
    Pixel.rule(ctx, 0, hy, D.W, th.dim);
    let y = hy + 8;
    for (const line of Pixel.wrap(ctx, m.body, D.W - 12, SZ.SMALL)) {
      Pixel.draw(ctx, line, 6, y, SZ.SMALL, th.ink);
      y += smallH + 1;
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
      const rowH = SZ.BODY.unit * 7 + SZ.SMALL.unit * 6 + 14;
      const scroll = scrollFor(NotesApp.sel, rowH, h, State.notes.length);
      drawList(ctx, 0, 0, D.W, h, {
        rowH, selected: NotesApp.sel, scroll, theme: th, count: State.notes.length,
        draw(c, i, sel, rh) {
          const n = State.notes[i];
          const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
          const lineH = SZ.BODY.unit * 7, smallH = SZ.SMALL.unit * 6;
          const top = Math.round((rh - (lineH + 3 + smallH)) / 2);
          const tm = hhmm(n.t);
          const tw = Pixel.measure(c, tm, SZ.SMALL);
          Pixel.icon(c, 'PENCIL', 4, Math.round((rh - 32) / 2), 2, dim);
          // Title and time share the top line; the preview gets its own.
          Pixel.drawClipped(c, n.title, 40, top, D.W - 52 - tw, SZ.BODY, ink);
          Pixel.drawRight(c, tm, D.W - 6, top, SZ.SMALL, dim);
          Pixel.drawClipped(c, n.body.split('\n')[0], 40, top + lineH + 3, D.W - 48, SZ.SMALL, dim);
        }
      });
      return;
    }
    const n = NotesApp.note;
    const smallH = SZ.SMALL.unit * 6, bodyH = SZ.BODY.unit * 7;
    let y = 4;
    Pixel.draw(ctx, stamp(n.t), 6, y, SZ.SMALL, th.dim); y += smallH + 3;
    Pixel.drawClipped(ctx, n.title, 6, y, D.W - 12, SZ.BODY, th.ink); y += bodyH + 4;
    Pixel.rule(ctx, 0, y, D.W, th.dim);
    y += 5;
    for (const line of Pixel.wrap(ctx, n.body, D.W - 12, SZ.SMALL)) {
      Pixel.draw(ctx, line, 6, y, SZ.SMALL, th.ink);
      y += smallH + 1;
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
      const smallH = SZ.SMALL.unit * 6;
      let y = 6;
      for (const line of Pixel.wrap(ctx, 'WEB DE SOLO TEXTO.\nSIN IMAGENES NI ANUNCIOS.', D.W - 16, SZ.SMALL)) {
        Pixel.draw(ctx, line, 8, y, SZ.SMALL, th.dim); y += smallH + 2;
      }
      Pixel.rule(ctx, 0, y + 3, D.W, th.dim);
      const listTop = y + 6;
      const rowH = SZ.BODY.unit * 7 + 12, listH = h - listTop;
      const scroll = scrollFor(BrowserApp.sel, rowH, listH, BrowserApp.HOME.length);
      drawList(ctx, 0, listTop, D.W, listH, {
        rowH, selected: BrowserApp.sel, scroll, theme: th, count: BrowserApp.HOME.length,
        draw(c, i, sel, rh) {
          const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
          Pixel.icon(c, 'GLOBE', 4, Math.round((rh - 16) / 2), 1, dim);
          Pixel.drawClipped(c, (i + 1) + '. ' + PAGES[BrowserApp.HOME[i]].title, 26,
            Math.round((rh - SZ.BODY.unit * 7) / 2), D.W - 34, SZ.BODY, ink);
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
      const smallH = SZ.SMALL.unit * 6;
      Pixel.draw(ctx, 'PUENTE - BUSCAR CON OK', 6, 4, SZ.SMALL, th.dim);
      Pixel.rule(ctx, 0, smallH + 6, D.W, th.dim);
      const hdr = smallH + 8;
      const rowH = SZ.BODY.unit * 7 + smallH + 14, listH = h - hdr;
      const scroll = scrollFor(WA.sel, rowH, listH, convs.length);
      drawList(ctx, 0, hdr, D.W, listH, {
        rowH, selected: WA.sel, scroll, theme: th, count: convs.length,
        draw(c, i, sel, rh) {
          const [name, msgs] = convs[i];
          const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
          const lineH = SZ.BODY.unit * 7;
          const top = Math.round((rh - (lineH + 3 + smallH)) / 2);
          const last = msgs[msgs.length - 1];
          const tm = hhmm(last.t);
          Pixel.drawClipped(c, name, 6, top, D.W - 14 - Pixel.measure(c, tm, SZ.SMALL), SZ.BODY, ink);
          Pixel.drawRight(c, tm, D.W - 6, top, SZ.SMALL, dim);
          Pixel.drawClipped(c, last.text, 6, top + lineH + 3, D.W - 12, SZ.SMALL, dim);
        }
      });
      return;
    }
    const msgs = convs.find(c => c[0] === WA.conv)?.[1] || [];
    Pixel.rule(ctx, 0, 0, D.W, th.dim);
    const sH = SZ.SMALL.unit * 6;
    let y = 6;
    for (const m of msgs) {
      Pixel.draw(ctx, m.app, 4, y, SZ.SMALL, th.dim);
      Pixel.drawRight(ctx, hhmm(m.t), D.W - 4, y, SZ.SMALL, th.dim);
      y += sH + 3;
      for (const line of Pixel.wrap(ctx, m.text, D.W - 12, SZ.SMALL).slice(0, 2)) {
        Pixel.draw(ctx, line, 4, y, SZ.SMALL, th.ink);
        y += sH + 1;
      }
      y += 8;
    }
    Pixel.rule(ctx, 0, h - sH - 8, D.W, th.dim);
    Pixel.draw(ctx, '[ ESCRIBE UNA RESPUESTA ]', 6, h - sH - 4, SZ.SMALL, th.dim);
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
  const titleH = SZ.TITLE.unit * 7, smallH = SZ.SMALL.unit * 6;
  Pixel.drawCenter(ctx, txt, D.W/2, 6, SZ.TITLE, th.ink);
  Pixel.draw(ctx, 'MHZ', D.W/2 + Pixel.measure(ctx, txt, SZ.TITLE)/2 + 8, 6 + titleH - smallH, SZ.SMALL, th.dim);
  // Name of whatever the pointer is on: the dial has to say what you tuned.
  const here = (typeof StationMeta === 'function') ? StationMeta(freq) : null;
  const nameY = 6 + titleH + 3;
  Pixel.drawCenter(ctx, here ? here.name : 'SIN EMISORA', D.W/2, nameY, SZ.SMALL, here ? th.ink : th.dim);

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

  // station card - every line gets its real font height, so nothing stacks.
  const bodyH = SZ.BODY.unit * 7;   // smallH already declared above
  const lines = near ? 4 : 2;
  const cardH = 14 + bodyH + 4 + (lines - 1) * (smallH + 3);
  const cardY = Math.max(cy + 70, Math.floor(h * 0.60));
  pill(ctx, 6, cardY, D.W - 12, cardH, th.panel);
  Pixel.frame(ctx, 6, cardY, D.W - 12, cardH - 2, near ? th.ink : th.dim);
  if (near) {
    let y = cardY + 7;
    Pixel.drawCenter(ctx, near[1], D.W/2, y, SZ.BODY, th.ink); y += bodyH + 4;
    Pixel.drawCenter(ctx, near[0].toFixed(1) + ' MHZ', D.W/2, y, SZ.SMALL, th.dim); y += smallH + 3;
    // Real state from the player, never a decorative label.
    const audioStatus = (typeof RadioAudio !== 'undefined') ? RadioAudio.status() : '';
    const live = (typeof RadioAudio !== 'undefined') ? RadioAudio.isWanted() : State.radioOn;
    const st = audioStatus || (live ? 'CONECTANDO' : 'OK PARA SONAR + ALTAVOZ');
    Pixel.drawCenter(ctx, st, D.W/2, y, SZ.SMALL, live ? th.ink : th.dim); y += smallH + 3;
    // Say plainly which radio this is: the browser has no aerial either.
    Pixel.drawCenter(ctx, 'POR INTERNET', D.W/2, y, SZ.SMALL, th.dim);
  } else {
    Pixel.drawCenter(ctx, 'SIN EMISORA', D.W/2, cardY + 10, SZ.BODY, th.dim);
    Pixel.drawCenter(ctx, 'SINTONIZA', D.W/2, cardY + 12 + bodyH, SZ.SMALL, th.dim);
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
    const smallH = SZ.SMALL.unit * 6, titleH = SZ.TITLE.unit * 7;
    // Header rows follow the fonts: PROXIMA/TEMPORIZADOR plus the big time,
    // which is taller than two small lines.
    const headH = Math.max(smallH * 2 + 10, titleH + 14);
    pill(ctx, 0, 0, D.W, headH, th.panel);
    pill(ctx, 0, headH - 1, D.W, 1, th.dim);
    Pixel.draw(ctx, 'PROXIMA ALARMA', 6, 5, SZ.SMALL, th.dim);
    Pixel.draw(ctx, 'TEMPORIZADOR', 6, 5 + smallH + 4, SZ.SMALL, th.dim);
    const a = State.alarms.find(x => x.on);
    const timeTxt = a ? `${String(a.h).padStart(2,'0')}:${String(a.m).padStart(2,'0')}` : '--:--';
    Pixel.drawRight(ctx, timeTxt, D.W - 6, 4, SZ.TITLE, th.ink);
    Pixel.drawRight(ctx, '--:--', D.W - 6, 4 + titleH + 4, SZ.BODY, th.dim);

    const rowH = titleH + smallH + 14, listTop = headH + 2, listH = h - listTop;
    const scroll = scrollFor(AlarmApp.sel, rowH, listH, State.alarms.length);
    drawList(ctx, 0, listTop, D.W, listH, {
      rowH, selected: AlarmApp.sel, scroll, theme: th, count: State.alarms.length,
      draw(c, i, sel, rh) {
        const al = State.alarms[i];
        const ink = sel ? th.bg : (al.on ? th.ink : th.dim), dim = sel ? th.bg : th.dim;
        const top = Math.round((rh - (titleH + 3 + smallH)) / 2);
        const onTxt = al.on ? 'ON' : 'OFF';
        Pixel.draw(c, `${String(al.h).padStart(2,'0')}:${String(al.m).padStart(2,'0')}`, 6, top, SZ.TITLE, ink);
        Pixel.drawRight(c, onTxt, D.W - 6, top + titleH - smallH, SZ.SMALL, dim);
        Pixel.drawClipped(c, al.label, 6, top + titleH + 3, D.W - 60, SZ.SMALL, dim);
        const names = ['LU','MA','MI','JU','VI','SA','DO'];
        Pixel.drawRight(c, al.days.length === 7 ? 'DIARIO' : al.days.map(d=>names[d-1]).join(' '), D.W - 6, top + titleH + 3, SZ.SMALL, dim);
      }
    });
  },
};
