// The preview's radio: a tiny WebAudio carrier + static, the browser twin of
// the AudioTrack synth in RadioActivity.kt.
'use strict';

const RadioAudio = (() => {
  let ac = null, carrier = null, noise = null, gain = null, noiseGain = null;
  let playing = false;

  function ensure() {
    if (ac) return;
    const Ctor = window.AudioContext || window.webkitAudioContext;
    if (!Ctor) return;
    ac = new Ctor();
    gain = ac.createGain();
    gain.gain.value = 0;
    gain.connect(ac.destination);
    noiseGain = ac.createGain();
    noiseGain.gain.value = 0;
    noiseGain.connect(gain);

    // pink-ish static
    const len = ac.sampleRate * 2;
    const buffer = ac.createBuffer(1, len, ac.sampleRate);
    const data = buffer.getChannelData(0);
    let last = 0;
    for (let i = 0; i < len; i++) {
      const white = Math.random() * 2 - 1;
      last = (last + 0.02 * white) / 1.02;
      data[i] = last * 3.2;
    }
    noise = ac.createBufferSource();
    noise.buffer = buffer;
    noise.loop = true;
    noise.connect(noiseGain);
    noise.start();

    carrier = ac.createOscillator();
    carrier.type = 'triangle';
    carrier.frequency.value = 220;
    const cg = ac.createGain();
    cg.gain.value = 0.5;
    carrier.connect(cg);
    cg.connect(gain);
    carrier.start();
  }

  function volume(v) {
    if (!gain || !ac) return;
    gain.gain.setTargetAtTime(playing ? v * 0.18 : 0, ac.currentTime, 0.06);
  }

  function on(stationFreq, v) {
    ensure();
    playing = true;
    if (!ac) return;
    if (ac.state === 'suspended') ac.resume();
    setStation(stationFreq);
    volume(v);
  }

  function off() {
    playing = false;
    if (!ac) return;
    gain.gain.setTargetAtTime(0, ac.currentTime, 0.08);
  }

  function setStation(f) {
    if (!ac || !carrier) return;
    const locked = f !== null && f !== undefined;
    carrier.frequency.setTargetAtTime(locked ? 180 + f * 1.2 : 180, ac.currentTime, 0.15);
    noiseGain.gain.setTargetAtTime(locked ? 0.05 : 0.35, ac.currentTime, 0.12);
    if (playing) volume(0.55);
  }

  return { ensure, on, off, setStation, volume };
})();
