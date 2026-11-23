// dumbOS interactive preview: a 3310-shaped body around the live pixel UI.
// NOTE: plain scripts sharing the global scope, so the helpers declared in
// apps.js (drawChrome, body) are visible here.

const canvas = document.getElementById('screen');
const ctx = canvas.getContext('2d');
ctx.imageSmoothingEnabled = false;

const View = {
  ctx,
  keys: ['BACK', 'OK', 'MENU'],
  menu: null,       // { title, items, sel, onPick }
  modal: null,      // 'mode' | 'theme' | 'about' | ...
  input: null,      // { title, value, numeric, onSubmit }
  toast: '',
  dragging: false,
};

// ---------------------------------------------------------------------------
// device sizing
// ---------------------------------------------------------------------------
function resize() {
  const dpr = Math.min(3, window.devicePixelRatio || 1);
  canvas.width = D.W * dpr;
  canvas.height = D.H * dpr;
  canvas.style.width = D.W + 'px';
  canvas.style.height = D.H + 'px';
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.imageSmoothingEnabled = false;
  draw();
}

// ---------------------------------------------------------------------------
// drawing
// ---------------------------------------------------------------------------
function draw() {
  ctx.save();
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const dpr = Math.min(3, window.devicePixelRatio || 1);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.imageSmoothingEnabled = false;

  drawChrome(ctx, titleFor(), { keys: View.keys });
  body(drawApp, null);

  if (View.toast) {
    const w = Pixel.measure(ctx, View.toast, SZ.SMALL) + 16;
    pill(ctx, (D.W - w) / 2, D.H - D.keybarH - 26, w, 20, State.theme.panel);
    Pixel.frame(ctx, (D.W - w) / 2, D.H - D.keybarH - 26, w, 20, State.theme.ink);
    Pixel.drawCenter(ctx, View.toast, D.W / 2, D.H - D.keybarH - 20, SZ.SMALL, State.theme.ink);
  }

  if (View.menu) drawMenu();
  if (View.modal) drawModal();
  if (View.input) drawInput();
  ctx.restore();
}

function titleFor() {
  switch (State.screen) {
    case 'home': return 'DUMBOS';
    case 'phone': return 'TELEFONO';
    case 'sms': return 'MENSAJES';
    case 'settings': return 'AJUSTES';
    case 'mail': return 'CORREO';
    case 'notes': return 'NOTAS';
    case 'browser': return 'WEB';
    case 'whatsapp': return 'WHATSAPP';
    case 'radio': return 'RADIO FM';
    case 'alarm': return 'ALARMAS';
    case 'apps': return 'APPS DEL SISTEMA';
    default: return 'DUMBOS';
  }
}

function drawApp(c, h) {
  switch (State.screen) {
    case 'home': return Home.draw(c, h);
    case 'phone': return Phone.draw(c, h);
    case 'sms': return Msgs.draw(c, h);
    case 'settings': return Settings.draw(c, h);
    case 'mail': return MailApp.draw(c, h);
    case 'notes': return NotesApp.draw(c, h);
    case 'browser': return BrowserApp.draw(c, h);
    case 'whatsapp': return WA.draw(c, h);
    case 'radio': return drawRadio(c, h);
    case 'alarm': return AlarmApp.draw(c, h);
    case 'apps': return drawSystemApps(c, h);
  }
}

const SYSTEM_APPS = [
  { icon:'BROWSER', name:'CHROME', pkg:'com.android.chrome' },
  { icon:'CHAT', name:'CAMARA', pkg:'com.android.camera' },
  { icon:'RADIO', name:'CALCULADORA', pkg:'com.android.calculator' },
  { icon:'FLASH', name:'FOTOS', pkg:'com.google.android.apps.photos' },
  { icon:'SEARCH', name:'MAPAS', pkg:'com.google.android.apps.maps' },
  { icon:'SYSTEM', name:'RELOJ', pkg:'com.android.deskclock' },
];
let sysSel = 0;

function drawSystemApps(c, h) {
  const th = State.theme;
  Pixel.draw(c, 'ESTO SIGUE SIENDO ANDROID.', 6, 5, SZ.SMALL, th.dim);
  Pixel.rule(c, 0, 22, D.W, th.dim);
  const rowH = 34;
  const scroll = scrollFor(sysSel, rowH, h - 24, SYSTEM_APPS.length);
  drawList(c, 0, 24, D.W, h - 24, {
    rowH, selected: sysSel, scroll, theme: th, count: SYSTEM_APPS.length,
    draw(g, i, sel) {
      const ink = sel ? th.bg : th.ink, dim = sel ? th.bg : th.dim;
      Pixel.icon(g, SYSTEM_APPS[i].icon, 4, (rowH - 32)/2, 2, dim);
      Pixel.draw(g, SYSTEM_APPS[i].name, 40, (rowH - 14)/2, SZ.BODY, ink);
    }
  });
}

// menus and dialogs -----------------------------------------------------------
function menu(title, items, onPick) {
  View.menu = { title, items, sel: 0, onPick };
  draw();
}

function drawMenu() {
  const th = State.theme;
  const m = View.menu;
  ctx.save();
  pill(ctx, 0, 0, D.W, D.H, '#000', 0.55);
  const rowH = 30;
  const h = Math.min(m.items.length * rowH + 34, D.H - 120);
  const y = (D.H - h) / 2;
  pill(ctx, 12, y, D.W - 24, h, th.bg);
  Pixel.frame(ctx, 12, y, D.W - 24, h, th.ink);
  Pixel.draw(ctx, m.title, 22, y + 8, SZ.SMALL, th.ink);
  Pixel.rule(ctx, 12, y + 24, D.W - 12, th.dim);
  const viewH = h - 34;
  const scroll = scrollFor(m.sel, rowH, viewH, m.items.length);
  drawList(ctx, 12, y + 26, D.W - 24, viewH, {
    rowH, selected: m.sel, scroll, theme: th, count: m.items.length,
    draw(c, i, sel) {
      const ink = sel ? th.bg : th.ink;
      Pixel.drawClipped(c, (i + 1) + ' ' + m.items[i], 6, 4, D.W - 60, SZ.BODY, ink);
    }
  });
  Pixel.drawRight(ctx, 'OK', D.W - 22, y + h - 14, SZ.SMALL, th.dim);
  ctx.restore();
}

function drawModal() {
  const th = State.theme;
  ctx.save();
  pill(ctx, 0, 0, D.W, D.H, '#000', 0.6);
  const m = View.modal;
  if (m.kind === 'about') {
    const lines = [
      'DUMBOS 0.1.0',
      '',
      'UN LAUNCHER Y UN ECOSISTEMA',
      'MONOCROMO PARA ANDROID.',
      '',
      'TODO LO QUE HACE FALTA Y NADA',
      'DE LO QUE NO.',
      '',
      'TIPOGRAFIA DE 5X7 PIXELES.',
      'ICONOS DE 16X16.',
      '',
      'ESTA VISTA ES LA PREVIA WEB',
      'DEL MISMO MOTOR QUE DIBUJA',
      'LA APP DE ANDROID.',
    ];
    const w = D.W - 40, hh = lines.length * 13 + 52;
    const y = (D.H - hh) / 2;
    pill(ctx, 20, y, w, hh, th.bg);
    Pixel.frame(ctx, 20, y, w, hh, th.ink);
    lines.forEach((l, i) => Pixel.draw(ctx, l, 30, y + 16 + i * 13, SZ.SMALL, i === 0 ? th.ink : th.dim));
    Pixel.draw(ctx, '[OK] CERRAR', 30, y + hh - 18, SZ.SMALL, th.ink);
  }
  ctx.restore();
}

function drawInput() {
  const th = State.theme;
  ctx.save();
  pill(ctx, 0, 0, D.W, D.H, '#000', 0.6);
  const i = View.input;
  const hh = 66;
  const y = (D.H - hh) / 2;
  pill(ctx, 16, y, D.W - 32, hh, th.bg);
  Pixel.frame(ctx, 16, y, D.W - 32, hh, th.ink);
  Pixel.draw(ctx, i.title, 26, y + 8, SZ.SMALL, th.dim);
  const cursor = (Math.floor(Date.now() / 500) % 2) ? '_' : '';
  Pixel.drawClipped(ctx, (i.value || '') + cursor, 26, y + 24, D.W - 52, SZ.BODY, th.ink);
  Pixel.draw(ctx, '[OK] ENVIAR', 26, y + hh - 16, SZ.SMALL, th.ink);
  ctx.restore();
}

function drawRail() {
  // the rail is the hardware strip under the screen; redrawn by #rail canvas
}

// ---------------------------------------------------------------------------
// navigation
// ---------------------------------------------------------------------------
function openApp(id) {
  State.screen = id;
  View.menu = null;
  View.input = null;
  if (id === 'phone') { Phone.view = 'contacts'; Phone.sel = 0; }
  if (id === 'sms') { Msgs.view = 'threads'; Msgs.sel = 0; }
  if (id === 'settings') Settings.sel = 0;
  if (id === 'mail') { MailApp.view = 'inbox'; MailApp.sel = 0; }
  if (id === 'notes') { NotesApp.view = 'list'; NotesApp.sel = 0; }
  if (id === 'browser') { BrowserApp.view = 'home'; BrowserApp.sel = 0; }
  if (id === 'whatsapp') { WA.view = 'list'; WA.sel = 0; }
  if (id === 'alarm') AlarmApp.sel = 0;
  updateKeys();
  draw();
}

function back() {
  if (View.input) { View.input = null; updateKeys(); draw(); return; }
  if (View.menu) { View.menu = null; updateKeys(); draw(); return; }
  if (View.modal) { View.modal = null; updateKeys(); draw(); return; }
  switch (State.screen) {
    case 'home': toast('EL LAUNCHER YA ESTA EN INICIO'); return;
    case 'phone': if (Phone.view === 'keypad') { Phone.view = 'contacts'; Phone.dialed = ''; } else return goHome(); break;
    case 'sms': if (Msgs.view === 'thread') { Msgs.view = 'threads'; } else return goHome(); break;
    case 'mail': if (MailApp.view === 'message') MailApp.view = 'inbox'; else return goHome(); break;
    case 'notes': if (NotesApp.view === 'note') NotesApp.view = 'list'; else return goHome(); break;
    case 'browser': if (BrowserApp.view === 'page') { BrowserApp.view = 'home'; } else return goHome(); break;
    case 'whatsapp': if (WA.view === 'conv') WA.view = 'list'; else return goHome(); break;
    default: return goHome();
  }
  updateKeys();
  draw();
}

function goHome() {
  State.screen = 'home';
  updateKeys();
  draw();
}

function updateKeys() {
  View.keys = ['BACK', 'OK', 'MENU'];
  if (State.screen === 'home') View.keys = ['APPS', 'ABRIR', 'MENU'];
  if (State.screen === 'phone' && Phone.view === 'keypad') View.keys = ['BORRAR', 'LLAMAR', 'OPCIONES'];
  if (State.screen === 'radio') View.keys = ['ATRAS', State.radioOn ? 'APAGAR' : 'ENCENDER', 'EMISORAS'];
  if (View.menu) View.keys = ['CERRAR', 'ELEGIR', 'ABAJO'];
  if (View.input) View.keys = ['CANCELAR', 'ENVIAR', '.'];
}

function toast(msg) {
  View.toast = msg;
  draw();
  setTimeout(() => { View.toast = ''; draw(); }, 1400);
}

function ok() {
  if (View.input) { submitInput(); return; }
  if (View.menu) {
    const m = View.menu;
    const pick = m.onPick;
    const sel = m.sel;
    View.menu = null;
    pick && pick(sel);
    updateKeys();
    draw();
    return;
  }
  if (View.modal) { View.modal = null; updateKeys(); draw(); return; }
  switch (State.screen) {
    case 'home': openApp(Home.tiles[Home.sel].app); return;
    case 'apps': openAppFromSystem(); return;
    case 'phone':
      if (Phone.view === 'contacts') {
        const ct = State.contacts[Phone.sel];
        toast('LLAMANDO A ' + ct.name);
        return;
      }
      if (Phone.dialed) toast('LLAMANDO AL ' + Phone.dialed);
      else toast('MARCA UN NUMERO');
      return;
    case 'sms':
      if (Msgs.view === 'threads') {
        const t = State.threads[Msgs.sel];
        t.unread = 0;
        Msgs.thread = t;
        Msgs.view = 'thread';
        Msgs.page = Math.max(0, Math.ceil(t.msgs.length / 4) - 1);
      } else {
        Msgs.page = (Msgs.page + 1) % Math.max(1, Math.ceil(Msgs.thread.msgs.length / 4));
      }
      updateKeys(); draw(); return;
    case 'settings': {
      const it = Settings.items()[Settings.sel];
      if (it && !it.section) SettingsAct(it.act);
      return;
    }
    case 'mail':
      if (MailApp.view === 'inbox') {
        const m = State.mail[MailApp.sel];
        m.unread = false;
        MailApp.msg = m;
        MailApp.view = 'message';
      } else MailApp.view = 'inbox';
      updateKeys(); draw(); return;
    case 'notes':
      if (NotesApp.view === 'list') { NotesApp.note = State.notes[NotesApp.sel]; NotesApp.view = 'note'; }
      else NotesApp.view = 'list';
      updateKeys(); draw(); return;
    case 'browser':
      if (BrowserApp.view === 'home') BrowserApp.go(BrowserApp.HOME[BrowserApp.sel]);
      else if (BrowserApp.view === 'page' && !BrowserApp.loading) toast('SIN ENLACES EN ESTA PAGINA');
      return;
    case 'whatsapp':
      if (WA.view === 'list') { WA.conv = WA.convs()[WA.sel][0]; WA.view = 'conv'; }
      else View.input = { title: 'RESPUESTA A ' + WA.conv, value: '', onSubmit: (t) => {
        if (!t) return;
        State.wa.push({ conv: WA.conv, app: 'TU', text: t.toUpperCase(), t: Date.now() });
        toast('ENVIADO POR WHATSAPP');
      }};
      updateKeys(); draw(); return;
    case 'radio': {
      State.radioOn = !State.radioOn;
      if (State.radioOn) RadioAudio.on(StationNear(), State.radioVol); else RadioAudio.off();
      updateKeys(); draw(); return;
    }
    case 'alarm':
      State.alarms[AlarmApp.sel].on = !State.alarms[AlarmApp.sel].on;
      draw(); return;
  }
}

function openAppFromSystem() {
  toast(SYSTEM_APPS[sysSel].name + ' ABRIRIA ANDROID');
}

function SettingsAct(act) {
  switch (act) {
    case 'mode':
      View.menu = {
        title: 'NOTIFICACIONES', sel: 0, items: ['SILENCIO - SOLO LLAMADAS', 'NO MOLESTAR - NADA PASA', 'TODO - NORMAL'],
        onPick(i) { State.mode = ['SILENCIO', 'NO MOLESTAR', 'TODO'][i]; toast(State.mode); }
      };
      break;
    case 'sound':
      State.sound = !State.sound;
      toast(State.sound ? 'TIMBRE SI' : 'TIMBRE NO');
      break;
    case 'dnd':
      State.mode = State.mode === 'NO MOLESTAR' ? 'SILENCIO' : 'NO MOLESTAR';
      toast(State.mode);
      break;
    case 'theme':
      View.menu = {
        title: 'TEMA', sel: 0, items: Object.values(THEMES).map(t => t.name),
        onPick(i) { State.theme = Object.values(THEMES)[i]; toast(State.theme.name); }
      };
      break;
    case 'about': View.modal = { kind: 'about' }; break;
    case 'alarms': openApp('alarm'); return;
    case 'sms': openApp('sms'); return;
    default: toast('OK');
  }
  updateKeys();
  draw();
}

function menuKey() {
  if (View.input || View.menu || View.modal) { ok(); return; }
  switch (State.screen) {
    case 'home':
      menu('MENU', ['TODAS LAS APPS', 'BUSCAR APP', 'SILENCIO', 'NO MOLESTAR', 'TODO', 'AJUSTES'], (i) => {
        if (i === 0) openApp('apps');
        else if (i === 1) View.input = { title: 'BUSCAR APP', value: '', onSubmit: () => toast('BUSCANDO...') };
        else if (i === 5) openApp('settings');
        else { State.mode = ['SILENCIO', 'NO MOLESTAR', 'TODO'][i - 2]; toast(State.mode); }
        updateKeys(); draw();
      });
      return;
    case 'phone':
      if (Phone.view === 'contacts') { Phone.view = 'keypad'; toast('TECLADO'); }
      else menu('OPCIONES', ['BORRAR TODO', 'GUARDAR CONTACTO'], (i) => {
        if (i === 0) Phone.dialed = '';
        else if (Phone.dialed) toast('CONTACTO GUARDADO');
        draw();
      });
      updateKeys(); draw(); return;
    case 'sms':
      if (Msgs.view === 'threads') menu('MENSAJES', ['NUEVO MENSAJE', 'ACTUALIZAR'], () => draw());
      else View.input = { title: 'MENSAJE', value: '', onSubmit: (t) => {
        if (!t) return;
        Msgs.thread.msgs.push({ me: true, text: t.toUpperCase(), t: Date.now() });
        Msgs.thread.t = Date.now();
        Msgs.page = Math.ceil(Msgs.thread.msgs.length / 4) - 1;
        toast('ENVIADO');
      }};
      updateKeys(); draw(); return;
    case 'settings':
      menu('MODO RAPIDO', ['SILENCIO', 'NO MOLESTAR', 'TODO', 'CAMBIAR TEMA'], (i) => {
        if (i === 3) SettingsAct('theme');
        else { State.mode = ['SILENCIO', 'NO MOLESTAR', 'TODO'][i]; toast(State.mode); }
      });
      return;
    case 'mail':
      View.input = { title: 'PARA', value: '', onSubmit: (to) => {
        if (!to) return;
        View.input = { title: 'ASUNTO', value: '', onSubmit: (sub) => {
          View.input = { title: 'MENSAJE', value: '', onSubmit: (body) => {
            toast('SIN APP DE CORREO EN LA PREVIA');
          }};
          draw();
        }};
        draw();
      }};
      updateKeys(); draw(); return;
    case 'notes':
      if (NotesApp.view === 'list') View.input = { title: 'TITULO DE NOTA', value: '', onSubmit: (t) => {
        if (!t) return;
        State.notes.unshift({ title: t.toUpperCase(), body: '(NUEVA NOTA)', t: Date.now() });
        toast('NOTA CREADA');
      }};
      else menu('NOTA', ['BORRAR', 'VOLVER'], (i) => {
        if (i === 0) {
          State.notes = State.notes.filter(n => n !== NotesApp.note);
          NotesApp.view = 'list';
        } else NotesApp.view = 'list';
        updateKeys(); draw();
      });
      updateKeys(); draw(); return;
    case 'browser':
      View.input = { title: 'URL', value: 'https://', onSubmit: (u) => {
        if (u) BrowserApp.go(u);
      }};
      updateKeys(); draw(); return;
    case 'whatsapp':
      menu('PUENTE', ['ABRIR WHATSAPP', 'REFRESCAR', 'BORRAR BUZON'], (i) => {
        if (i === 0) toast('WHATSAPP NO ESTA EN LA PREVIA');
        if (i === 2) State.wa = [];
        draw();
      });
      return;
    case 'radio':
      menu('EMISORAS', STATIONS.map(([f, n]) => f.toFixed(1) + ' ' + n), (i) => {
        State.radioFreq = STATIONS[i][0];
        if (!State.radioOn) { State.radioOn = true; RadioAudio.on(StationNear(), State.radioVol); }
        else RadioAudio.setStation(StationNear());
        updateKeys(); draw();
      });
      return;
    case 'alarm':
      menu('ALARMAS', State.alarms.map(a => `${String(a.h).padStart(2,'0')}:${String(a.m).padStart(2,'0')} ${a.label}`), (i) => {
        const a = State.alarms[i];
        State.alarms[i] = { ...a, h: (a.h + 1) % 24 };
        toast('HORA +1');
        draw();
      });
      return;
  }
}

function submitInput() {
  const i = View.input;
  View.input = null;
  const v = i.value;
  if (i.onSubmit) i.onSubmit(v);
  updateKeys();
  draw();
}

// ---------------------------------------------------------------------------
// input handling
// ---------------------------------------------------------------------------
const down = (act) => {
  if (View.input) {
    const i = View.input;
    if (act === 'UP' || act === 'DOWN' || act === 'LEFT' || act === 'RIGHT') return;
    if (act === 'OK') { submitInput(); return; }
    if (act === 'CLEAR') { i.value = i.value.slice(0, -1); draw(); return; }
    if (act === 'MENU') { View.input = null; updateKeys(); draw(); return; }
    return;
  }
  if (View.menu) {
    if (act === 'UP') View.menu.sel = (View.menu.sel - 1 + View.menu.items.length) % View.menu.items.length;
    if (act === 'DOWN') View.menu.sel = (View.menu.sel + 1) % View.menu.items.length;
    if (act === 'OK') { ok(); return; }
    if (act === 'BACK' || act === 'MENU') { View.menu = null; }
    updateKeys(); draw(); return;
  }
  if (View.modal) { View.modal = null; updateKeys(); draw(); return; }
  switch (act) {
    case 'UP': move(-1); break;
    case 'DOWN': move(1); break;
    case 'OK': ok(); return;
    case 'BACK': back(); return;
    case 'MENU': menuKey(); return;
    default: if (act.startsWith('KEY:')) key(act.slice(4));
  }
  draw();
}

function move(delta) {
  if (State.screen === 'home') Home.sel = clamp(Home.sel + delta, 0, Home.tiles.length - 1);
  if (State.screen === 'apps') sysSel = clamp(sysSel + delta, 0, SYSTEM_APPS.length - 1);
  if (State.screen === 'phone' && Phone.view === 'contacts') Phone.sel = clamp(Phone.sel + delta, 0, State.contacts.length - 1);
  if (State.screen === 'sms' && Msgs.view === 'threads') Msgs.sel = clamp(Msgs.sel + delta, 0, State.threads.length - 1);
  if (State.screen === 'settings') Settings.sel = clamp(Settings.sel + delta, 0, Settings.items().length - 1);
  if (State.screen === 'mail' && MailApp.view === 'inbox') MailApp.sel = clamp(MailApp.sel + delta, 0, State.mail.length - 1);
  if (State.screen === 'notes' && NotesApp.view === 'list') NotesApp.sel = clamp(NotesApp.sel + delta, 0, State.notes.length - 1);
  if (State.screen === 'browser' && BrowserApp.view === 'home') BrowserApp.sel = clamp(BrowserApp.sel + delta, 0, BrowserApp.HOME.length - 1);
  if (State.screen === 'whatsapp' && WA.view === 'list') WA.sel = clamp(WA.sel + delta, 0, WA.convs().length - 1);
  if (State.screen === 'alarm') AlarmApp.sel = clamp(AlarmApp.sel + delta, 0, State.alarms.length - 1);
  if (State.screen === 'radio') {
    State.radioFreq = clamp(Math.round((State.radioFreq + delta * 0.1) * 10) / 10, 87.5, 108);
    RadioAudio.setStation(StationNear());
  }
}

function key(k) {
  if (State.screen === 'phone' && Phone.view === 'keypad') {
    if (k === '*') Phone.dialed = Phone.dialed.slice(0, -1);
    else if (k === '#') Phone.dialed = '';
    else if (Phone.dialed.length < 20) Phone.dialed += k;
    draw();
    return;
  }
  if (View.input) {
    if (k === '*') View.input.value = View.input.value.slice(0, -1);
    else View.input.value += k;
    draw();
    return;
  }
}

function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }

function StationNear() {
  let near = null, best = 0.45;
  for (const [f] of STATIONS) { const d = Math.abs(f - State.radioFreq); if (d < best) { best = d; near = f; } }
  return near;
}

// ---------------------------------------------------------------------------
// wire up the hardware body
// ---------------------------------------------------------------------------
function wireRail() {
  document.querySelectorAll('[data-act]').forEach(el => {
    const act = el.dataset.act;
    const fire = (e) => {
      e.preventDefault();
      RadioAudio.ensure();
      el.classList.add('pressed');
      setTimeout(() => el.classList.remove('pressed'), 90);
      down(act);
    };
    el.addEventListener('mousedown', fire);
    el.addEventListener('touchstart', (e) => { e.preventDefault(); fire(e); }, { passive: false });
  });
  document.querySelectorAll('[data-key]').forEach(el => {
    const k = el.dataset.key;
    const fire = (e) => {
      e.preventDefault();
      el.classList.add('pressed');
      setTimeout(() => el.classList.remove('pressed'), 90);
      key(k);
    };
    el.addEventListener('mousedown', fire);
    el.addEventListener('touchstart', (e) => { e.preventDefault(); fire(e); }, { passive: false });
  });
  // keyboard shortcuts
  window.addEventListener('keydown', (e) => {
    const map = { ArrowUp:'UP', ArrowDown:'DOWN', ArrowLeft:'LEFT', ArrowRight:'RIGHT', Enter:'OK', Backspace:'BACK', Escape:'BACK' };
    if (map[e.key]) { e.preventDefault(); down(map[e.key]); return; }
    if (e.key === 'm') { down('MENU'); return; }
    if (/^[0-9*#]$/.test(e.key)) key(e.key);
  });
}

// drag the radio dial directly on the screen
canvas.addEventListener('mousedown', (e) => {
  if (State.screen !== 'radio') return;
  View.dragging = true;
  radioDrag(e);
});
canvas.addEventListener('mousemove', (e) => { if (View.dragging) radioDrag(e); });
window.addEventListener('mouseup', () => { View.dragging = false; });
canvas.addEventListener('touchstart', (e) => {
  if (State.screen !== 'radio') return;
  View.dragging = true;
  radioDrag(e.touches[0]);
  e.preventDefault();
}, { passive: false });
canvas.addEventListener('touchmove', (e) => {
  if (!View.dragging) return;
  radioDrag(e.touches[0]);
  e.preventDefault();
}, { passive: false });
window.addEventListener('touchend', () => { View.dragging = false; });

function radioDrag(e) {
  const r = canvas.getBoundingClientRect();
  const x = (e.clientX - r.left) * (D.W / r.width);
  const margin = 14;
  const f = 87.5 + (x - margin) / (D.W - margin * 2) * (108 - 87.5);
  State.radioFreq = clamp(Math.round(f * 10) / 10, 87.5, 108);
  RadioAudio.setStation(StationNear());
  draw();
}

// boot
window.addEventListener('resize', resize);
down('__boot');
wireRail();
resize();
setInterval(() => { if (!View.menu && !View.input && !View.modal) draw(); }, 1000);
