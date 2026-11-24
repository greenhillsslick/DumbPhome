// Radio for the preview.
//
// The APK decodes the station streams with MediaCodec; here the browser does it
// with an <audio> element pointed at the same URL, which is the closest possible
// twin: same catalogue, same dial, real audio.
//
// A stream that will not play is reported rather than faked, exactly like the
// app's "SIN SEÑAL".

const RadioAudio = (() => {
  let el = null;          // <audio>
  let current = null;     // station object or null
  let vol = 0.7;
  let wantPlay = false;
  let lastError = '';
  let startedAt = 0;
  let watchdog = 0;

  function ensure() {
    if (el) return el;
    el = new Audio();
    el.preload = 'none';
    // Deliberately NOT setting crossOrigin: the audio is only ever played, never
    // read sample by sample, and asking for CORS would make real stations (which
    // do not send Access-Control-Allow-Origin) fail to load at all.
    el.volume = vol;
    el.addEventListener('playing', () => { lastError = ''; startedAt = Date.now(); });
    el.addEventListener('error', () => {
      // A network/decode failure is a real "no signal", not an autoplay block.
      lastError = 'SIN SEÑAL';
      wantPlay = false;
    });
    el.addEventListener('stalled', () => { if (wantPlay) lastError = 'BUFFER'; });
    el.addEventListener('waiting', () => { if (wantPlay) lastError = 'BUFFER'; });
    // A stream that simply ends (a dropped connection, a finite file) must not
    // leave the dial claiming "SONANDO" for ever.
    el.addEventListener('ended', () => { if (wantPlay) { lastError = 'SIN SEÑAL'; wantPlay = false; } });
    el.addEventListener('pause', () => { if (wantPlay && el.ended) { lastError = 'SIN SEÑAL'; wantPlay = false; } });
    el.addEventListener('timeupdate', () => { if (wantPlay && !el.paused) { lastError = ''; startedAt = startedAt || Date.now(); } });
    // A stream can die silently: <audio> does not always fire 'error' when the
    // server stops sending. Compare the clock against the wall clock instead.
    watchdog = setInterval(() => {
      if (!wantPlay || !el) return;
      el.__pos = el.currentTime;
      clearTimeout(el.__t);
      el.__t = setTimeout(() => {
        if (wantPlay && el && !el.paused && el.currentTime === el.__pos) {
          lastError = 'SIN SEÑAL';
          wantPlay = false;
          try { el.pause(); } catch (e) {}
        }
      }, 6000);
    }, 4000);
    return el;
  }

  function urlOf(station) {
    return station ? station.url : null;
  }

  function on(station, volume) {
    ensure();
    if (typeof volume === 'number') vol = volume;
    el.volume = vol;
    current = station || null;
    if (!current) { off(); return; }
    lastError = '';
    wantPlay = true;
    el.src = current.url;
    const attempt = el.play();
    if (attempt && attempt.catch) {
      attempt.catch((err) => {
        // NotAllowedError = the browser blocked autoplay (a real limit: the
        // preview needs one user gesture). Anything else is a stream problem.
        const blocked = err && err.name === 'NotAllowedError';
        lastError = blocked ? 'PULSA OK PARA SONAR' : 'SIN SEÑAL';
        wantPlay = false;
      });
    }
  }

  function off() {
    wantPlay = false;
    if (el) {
      el.pause();
      el.removeAttribute('src');
      el.load();
    }
    current = null;
    lastError = '';
  }

  /** Tuning: same station -> keep playing, different -> switch stream. */
  function setStation(station) {
    if (!station) {
      if (wantPlay) { off(); lastError = 'ENTRE EMISORAS'; }
      else current = null;
      return;
    }
    if (current && current.key === station.key) return;
    if (wantPlay) on(station, vol);
    else current = station;
  }

  function setVolume(v) {
    vol = v;
    if (el) el.volume = Math.max(0, Math.min(1, v));
  }

  function status() {
    if (lastError) return lastError;
    if (!wantPlay) return '';
    if (!startedAt) return 'CONECTANDO';
    return 'SONANDO';
  }

  /** True only when audio is genuinely playing. */
  function isOn() {
    return !!(wantPlay && el && !el.paused && el.currentTime > 0);
  }

  /** True when the user asked for sound and nothing failed yet. */
  function isWanted() { return wantPlay; }

  return { ensure, on, off, setStation, setVolume, status, isOn, isWanted };
})();
