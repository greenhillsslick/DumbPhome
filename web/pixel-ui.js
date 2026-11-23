// dumbOS pixel UI kit for the browser preview.
// Mirrors app/src/main/java/com/dumbos/ui (Typo, Sz, PixelList) so what you
// see here is the same bitmap font, icons and layout logic the APK uses.
'use strict';

const THEMES = {
  PHOSPHOR: { id:'PHOSPHOR', name:'FOSFORO VERDE', bg:'#0B1408', ink:'#9BFF6B', dim:'#4E8A38', panel:'#12220B' },
  AMBER:    { id:'AMBER',    name:'AMBAR NOCTURNO', bg:'#100C02', ink:'#FFC145', dim:'#8A6521', panel:'#1C1403' },
  ICE:      { id:'ICE',      name:'AZUL HIELO', bg:'#07101A', ink:'#8FD8FF', dim:'#3C6F8E', panel:'#0C1A26' },
  PAPER:    { id:'PAPER',    name:'PAPEL VERDE', bg:'#C7DDB0', ink:'#16220F', dim:'#6B8157', panel:'#BCD3A4' },
  MONO:     { id:'MONO',     name:'MONOCROMO', bg:'#000000', ink:'#D8F0D0', dim:'#5A6B55', panel:'#0A0F0A' },
};

const SZ = {
  SMALL: { unit: 2, small: true,  adv: 5, gw: 4, gh: 6 },
  BODY:  { unit: 2, small: false, adv: 6, gw: 5, gh: 7 },
  TITLE: { unit: 3, small: false, adv: 6, gw: 5, gh: 7 },
  HUGE:  { unit: 4, small: false, adv: 6, gw: 5, gh: 7 },
};

const Pixel = {
  normalize(s) {
    let out = '';
    for (const ch of String(s)) {
      const c = ACCENT_FOLD[ch] || ch;
      if (c === '\n') out += ' ';
      else if ("\u2019\u2018\u00B4`".includes(c)) out += "'";
      else if ("\u201C\u201D".includes(c)) out += '"';
      else if ("\u2013\u2014".includes(c)) out += '-';
      else if (c === '\u2026') out += '...';
      else if (c === '\u00B7') out += '.';
      else if (c === '\u00BA' || c === '\u00B0') out += 'o';
      else if (c === '\u20AC') out += 'EUR';
      else if (c === '\t') out += '  ';
      else out += c;
    }
    return out.toUpperCase();
  },

  rowsFor(c, small) {
    const code = c.charCodeAt(0);
    if (code >= 1 && code <= 30) return symbolGlyph(c);
    if (c === '\u00D1') return small ? SMALL_GLYPHS['\u00D1'] : null;
    const map = small ? SMALL_GLYPHS : BIG_GLYPHS;
    return map[c] || null;
  },

  measure(ctx, text, size) {
    const s = Pixel.normalize(text);
    let units = 0;
    for (const c of s) units += c === ' ' ? 3 : (Pixel.rowsFor(c, size.small) ? size.adv : size.adv - 1);
    return Math.max(0, units - 1) * size.unit;
  },

  lineH(size) { return size.gh * size.unit; },

  draw(ctx, text, x, y, size, color) {
    const s = Pixel.normalize(text);
    ctx.fillStyle = color;
    const u = size.unit;
    let cx = Math.round(x);
    for (const c of s) {
      if (c === ' ') { cx += 3 * u; continue; }
      const rows = Pixel.rowsFor(c, size.small);
      if (!rows) { cx += (size.adv - 1) * u; continue; }
      for (let ry = 0; ry < rows.length; ry++) {
        const row = rows[ry];
        for (let rx = 0; rx < row.length; rx++) {
          if (row[rx] === '#') ctx.fillRect(cx + rx * u, Math.round(y) + ry * u, u, u);
        }
      }
      cx += size.adv * u;
    }
    return cx - Math.round(x);
  },

  drawRight(ctx, text, right, y, size, color) {
    Pixel.draw(ctx, text, right - Pixel.measure(ctx, text, size), y, size, color);
  },

  drawCenter(ctx, text, cx, y, size, color) {
    Pixel.draw(ctx, text, cx - Pixel.measure(ctx, text, size) / 2, y, size, color);
  },

  drawClipped(ctx, text, x, y, maxW, size, color) {
    if (Pixel.measure(ctx, text, size) <= maxW) return Pixel.draw(ctx, text, x, y, size, color);
    let s = Pixel.normalize(text);
    while (s.length && Pixel.measure(ctx, s + '...', size) > maxW) s = s.slice(0, -1);
    return Pixel.draw(ctx, s.trimEnd() + '...', x, y, size, color);
  },

  wrap(ctx, text, maxW, size) {
    const out = [];
    for (const para of String(text).split('\n')) {
      if (!para.trim()) { out.push(''); continue; }
      const words = para.trim().split(' ').filter(Boolean);
      let line = '';
      for (const w of words) {
        const cand = line ? line + ' ' + w : w;
        if (line && Pixel.measure(ctx, cand, size) > maxW) { out.push(line); line = ''; }
        if (!line) {
          let rest = w;
          while (Pixel.measure(ctx, rest, size) > maxW && rest.length > 1) {
            let cut = rest.length;
            while (cut > 1 && Pixel.measure(ctx, rest.slice(0, cut), size) > maxW) cut--;
            out.push(rest.slice(0, cut));
            rest = rest.slice(cut);
          }
          line = rest;
        } else {
          line += ' ' + w;
        }
      }
      if (line) out.push(line);
    }
    return out;
  },

  icon(ctx, name, x, y, px, color) {
    const rows = ICON_GLYPHS[name] || SYMBOL_GLYPHS[name];
    if (!rows) return;
    ctx.fillStyle = color;
    for (let ry = 0; ry < rows.length; ry++) {
      const row = rows[ry];
      for (let rx = 0; rx < row.length; rx++) {
        if (row[rx] === '#') ctx.fillRect(x + rx * px, y + ry * px, px, px);
      }
    }
  },

  iconSize(px) { return 16 * px; },

  rule(ctx, x1, y, x2, color, dash) {
    ctx.fillStyle = color;
    if (!dash) { ctx.fillRect(x1, y, x2 - x1, 1); return; }
    for (let x = x1; x < x2; x += 4) ctx.fillRect(x, y, 2, 1);
  },

  frame(ctx, x, y, w, h, color) {
    ctx.fillStyle = color;
    ctx.fillRect(x, y, w, 1);
    ctx.fillRect(x, y + h - 1, w, 1);
    ctx.fillRect(x, y, 1, h);
    ctx.fillRect(x + w - 1, y, 1, h);
  },
};

// ---------------------------------------------------------------------------
// Layout helpers (CSS px == device independent px in the preview)
// ---------------------------------------------------------------------------
const PAD = 6;

function pill(ctx, x, y, w, h, color, alpha = 1) {
  ctx.save(); ctx.globalAlpha = alpha; ctx.fillStyle = color; ctx.fillRect(x, y, w, h); ctx.restore();
}

/**
 * Draws a scrollable/selected list of rows.
 * opts: { rowH, selected, scroll, theme, draw(ctx, i, selected, rowH, y), count }
 */
function drawList(ctx, x, y, w, h, opts) {
  const { rowH, selected, theme, count } = opts;
  const scroll = Math.max(0, Math.min(opts.scroll || 0, Math.max(0, count * rowH - h)));
  ctx.save();
  ctx.beginPath(); ctx.rect(x, y, w, h); ctx.clip();
  const first = Math.floor(scroll / rowH);
  const last = Math.min(count - 1, Math.floor((scroll + h) / rowH) + 1);
  for (let i = first; i <= last; i++) {
    if (i < 0 || i >= count) continue;
    const ry = y + i * rowH - scroll;
    const sel = i === selected;
    ctx.save();
    ctx.translate(x, ry);
    if (sel) pill(ctx, 0, 0, w, rowH, theme.ink);
    ctx.beginPath(); ctx.rect(3, 0, w - 6, rowH); ctx.clip();
    (opts.draw || opts.render)(ctx, i, sel, rowH, ry);
    ctx.restore();
  }
  ctx.restore();
  return scroll;
}

function scrollFor(selected, rowH, viewH, count) {
  return Math.max(0, Math.min(selected * rowH - (viewH - rowH) / 2, Math.max(0, count * rowH - viewH)));
}

// ---------------------------------------------------------------------------
// Fake device state (the preview's "OS")
// ---------------------------------------------------------------------------
const State = {
  theme: THEMES.PHOSPHOR,
  mode: 'SILENCIO',            // SILENCIO | NO MOLESTAR | TODO
  sound: true,
  clockOffset: 0,
  battery: 78,
  charging: false,
  screen: 'home',
  app: null,
  dialed: '',
  radioFreq: 98.3,
  radioOn: false,
  radioVol: 0.55,
  mail: [
    { from:'BIBLIOTECA', subject:'VENCIMIENTO DE PRESTAMO', body:"EL LIBRO 'EL NOMBRE DE LA ROSA' VENCE EL VIERNES.\nRECUERDA DEVOLVERLO EN RECEPCION.", t:Date.now()-7200000, unread:true },
    { from:'TALLER SANJUAN', subject:'BICI LISTA', body:'SU BICICLETA ESTA REPARADA. HORARIO 9:00 A 14:00.\nTOTAL: 18 EUR.', t:Date.now()-86400000, unread:false },
    { from:'RADIO LOCAL 87.9', subject:'PROGRAMA DE MADRUGADA', body:'ESTA NOCHE A LAS 00:00, SESION DE ROCK EN DIRECTO.\nNO HACE FALTA ENTRADA.', t:Date.now()-172800000, unread:false },
  ],
  notes: [
    { title:'COMPRA', body:'PAN\nLECHE\nHUEVOS\nPLATANOS', t:Date.now()-86400000 },
    { title:'BICI', body:'PINCHARON LAS DOS RUEDAS. EL TALLER ABRE A LAS 9.', t:Date.now()-3600000 },
    { title:'IDEA', body:'UNA RADIO QUE SOLO SUENE A LAS 7:00 Y DIGA EL TIEMPO.', t:Date.now()-600000 },
  ],
  alarms: [
    { h:6, m:30, label:'TRABAJO', days:[1,2,3,4,5], on:true },
    { h:9, m:0, label:'SABADO BICI', days:[6], on:true },
  ],
  threads: [
    { name:'MAMA', number:'+34 600 111 222', unread:1, t:Date.now()-720000, msgs:[
      { me:false, text:'HOLA, HAS COMPRADO PAN?', t:Date.now()-1800000 },
      { me:true,  text:'SI, Y LECHE. LLEGO A LAS 7', t:Date.now()-1500000 },
      { me:false, text:'LLEGO A CASA A LAS 20:00, NO TARDES', t:Date.now()-720000 },
    ]},
    { name:'HUGO', number:'+34 611 333 444', unread:0, t:Date.now()-5700000, msgs:[
      { me:false, text:'QUE HACEMOS EL SABADO?', t:Date.now()-7600000 },
      { me:true,  text:'IR A VER LA BANDA DEL BARRIO', t:Date.now()-7000000 },
      { me:false, text:'EL CONCIERTO ERA A LAS 9 NO A LAS 8', t:Date.now()-5700000 },
    ]},
    { name:'TALLER', number:'+34 622 555 666', unread:0, t:Date.now()-93600000, msgs:[
      { me:false, text:'SU BICI ESTA LISTA PARA RECOGER', t:Date.now()-93600000 },
    ]},
    { name:'89391', number:'89391', unread:0, t:Date.now()-180000000, msgs:[
      { me:false, text:'INFO: SALDO 4,50 EUR. GRACIAS', t:Date.now()-180000000 },
    ]},
  ],
  wa: [
    { conv:'MAMA', app:'WHATSAPP', text:'TE HAS DEJADO LAS LLAVES EN CASA', t:Date.now()-300000 },
    { conv:'MAMA', app:'WHATSAPP', text:'ABREME QUE ESTOY EN LA PUERTA', t:Date.now()-180000 },
    { conv:'BANDA DEL BARRIO', app:'TELEGRAM', text:'ENSAYO EL JUEVES 19:00', t:Date.now()-7200000 },
  ],
  browseStack: [],
  page: null,
  contacts: [
    { name:'MAMA', number:'+34 600 111 222' },
    { name:'HUGO', number:'+34 611 333 444' },
    { name:'TALLER SANJUAN', number:'+34 622 555 666' },
    { name:'BIBLIOTECA', number:'+34 633 777 888' },
  ],
};
