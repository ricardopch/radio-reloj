/*
 * Lógica común de las pantallas de Radio Reloj (hifi.html y valvulas.html).
 * Cada pantalla pone el mismo juego de ids; lo que es propio de un aspecto
 * (ojo mágico, medidores…) lo hace en window.Skin.render / window.Skin.tuning.
 */
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var radio = $('radio'), dial = $('dial'), track = $('track'), scale = $('scale');
  var SPACING = +dial.getAttribute('data-spacing') || 112;   // distancia entre emisoras (px)
  var EDGE = +dial.getAttribute('data-edge') || 70;          // margen antes de la primera
  var KNOB_STEP = 30;                                        // grados de sintonía por emisora
  var skin = window.Skin || {};

  // ---------- Puente con Android (o simulación en el ordenador) ----------
  var bridge = window.RadioApp || makeMock();
  function call(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    try { bridge[name].apply(bridge, args); } catch (e) { /* sin puente */ }
  }

  var S = { active: false, state: 'idle', stations: [], volume: 0, maxVolume: 15 };
  var stationsSig = '';
  var sel = 0;              // emisora bajo la aguja (puede no ser la que suena)
  var offset = 0;           // desplazamiento actual del dial
  var dragging = false;
  var turning = false;      // girando el mando de sintonía
  var followedId;           // última emisora sonando que ha seguido la aguja
  var pending = null;       // emisora pedida que el móvil aún no ha confirmado

  // ---------- Dial ----------
  function center() { return dial.clientWidth / 2; }
  function xOf(i) { return EDGE + i * SPACING; }
  function clampIndex(i) { return Math.max(0, Math.min(S.stations.length - 1, i)); }
  function setOffset(px, animate) {
    offset = px;
    track.classList.toggle('animate', !!animate);
    track.style.transform = 'translateX(' + px + 'px)';
    if (skin.tuning) {
      var pos = (center() - px - EDGE) / SPACING;
      var dev = S.stations.length ? (pos - clampIndex(Math.round(pos))) * 2 : 0;
      skin.tuning(Math.max(-1, Math.min(1, dev)));
    }
  }
  function offsetFor(i) { return center() - xOf(i); }
  function indexForOffset(px) { return clampIndex(Math.round((center() - px - EDGE) / SPACING)); }
  function highlight() {
    var els = track.querySelectorAll('.station');
    for (var k = 0; k < els.length; k++) els[k].classList.toggle('sel', k === sel);
  }
  function moveNeedle(i, animate) {
    sel = clampIndex(i);
    setOffset(offsetFor(sel), animate);
    highlight();
    renderPlate();
  }
  function indexOfId(id) {
    for (var i = 0; i < S.stations.length; i++) if (S.stations[i].id === id) return i;
    return -1;
  }
  function renderStations() {
    var sig = S.stations.map(function (s) { return s.id + '=' + s.name; }).join('|');
    if (sig === stationsSig) return;
    stationsSig = sig;
    var old = track.querySelectorAll('.station');
    for (var k = 0; k < old.length; k++) old[k].remove();
    var width = EDGE * 2 + Math.max(0, S.stations.length - 1) * SPACING;
    track.style.width = width + 'px';
    scale.style.width = width + 'px';
    S.stations.forEach(function (s, i) {
      var b = document.createElement('button');
      b.className = 'station ' + (i % 2 ? 'down' : 'up');
      b.style.left = xOf(i) + 'px';
      b.textContent = s.name;
      b.setAttribute('aria-label', 'Escuchar ' + s.name);
      b.addEventListener('click', function (ev) { ev.preventDefault(); });
      b.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') tuneTo(i, true); });
      track.appendChild(b);
    });
    var idx = indexOfId(S.stationId);
    moveNeedle(idx >= 0 ? idx : Math.min(sel, Math.max(0, S.stations.length - 1)), false);
  }

  /**
   * Deja la aguja en la emisora i. Si la radio está encendida y no es la que suena,
   * la sintoniza; con forcePlay (tocar su nombre) la enciende aunque esté apagada.
   */
  function tuneTo(i, forcePlay) {
    if (!S.stations.length) return;
    moveNeedle(i, true);
    var st = S.stations[sel];
    var isCurrent = S.active && st.id === S.stationId;
    if (isCurrent) {
      if (forcePlay && S.state === 'paused') call('toggle');
      return;
    }
    if (S.active || forcePlay) {
      // Se da por sintonizada ya: así la aguja no vuelve atrás mientras llega el nuevo estado
      S.stationId = st.id; S.stationName = st.name; S.state = 'buffering'; S.active = true;
      followedId = st.id;
      pending = { id: st.id, until: Date.now() + 2500 };
      call('play', st.id);
      renderAll();
    }
  }

  // Arrastrar el dial (con inercia y ajuste a la emisora más cercana)
  var startX = 0, startOffset = 0, samples = [];
  dial.addEventListener('pointerdown', function (e) {
    if (!S.stations.length) return;
    dragging = true; dial.setPointerCapture(e.pointerId);
    startX = e.clientX; startOffset = offset; samples = [{ x: e.clientX, t: e.timeStamp }];
    track.classList.remove('animate');
  });
  dial.addEventListener('pointermove', function (e) {
    if (!dragging) return;
    var px = startOffset + (e.clientX - startX);
    var min = offsetFor(S.stations.length - 1), max = offsetFor(0);
    if (px > max) px = max + (px - max) * .35;
    if (px < min) px = min + (px - min) * .35;
    setOffset(px, false);
    samples.push({ x: e.clientX, t: e.timeStamp });
    if (samples.length > 5) samples.shift();
    var i = indexForOffset(px);
    if (i !== sel) { sel = i; highlight(); renderPlate(); }
  });
  function endDrag(e) {
    if (!dragging) return;
    dragging = false;
    if (Math.abs(e.clientX - startX) < 8) {
      // Toque: sintonizar la emisora tocada (la más cercana al dedo)
      var rect = dial.getBoundingClientRect();
      tuneTo(Math.round((e.clientX - rect.left - offset - EDGE) / SPACING), true);
      return;
    }
    var a = samples[0], b = samples[samples.length - 1];
    var v = b.t > a.t ? (b.x - a.x) / (b.t - a.t) : 0;   // px por ms
    tuneTo(indexForOffset(offset + v * 180), false);
  }
  dial.addEventListener('pointerup', endDrag);
  dial.addEventListener('pointercancel', endDrag);

  // ---------- Mandos giratorios ----------
  function knob(el, onDelta) {
    var active = false, last = 0;
    function angle(e) {
      var r = el.getBoundingClientRect();
      return Math.atan2(e.clientY - (r.top + r.height / 2), e.clientX - (r.left + r.width / 2)) * 180 / Math.PI;
    }
    el.addEventListener('pointerdown', function (e) { active = true; last = angle(e); el.setPointerCapture(e.pointerId); onDelta(0, 'start'); });
    el.addEventListener('pointermove', function (e) {
      if (!active) return;
      var a = angle(e), d = a - last;
      if (d > 180) d -= 360;
      if (d < -180) d += 360;
      last = a; onDelta(d, 'move');
    });
    function up() { if (active) { active = false; onDelta(0, 'end'); } }
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', up);
    el.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowRight' || e.key === 'ArrowUp') { e.preventDefault(); onDelta(1, 'key'); }
      if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') { e.preventDefault(); onDelta(-1, 'key'); }
    });
  }

  // Volumen: el mando recorre 270º, de 0 al máximo
  var volKnob = $('volKnob'), volBody = volKnob.querySelector('.body');
  var volAngle = -135, volDragging = false, volSent = -1;
  function volToAngle(v) { return -135 + 270 * (S.maxVolume ? v / S.maxVolume : 0); }
  function drawVolume(angle) {
    volBody.style.setProperty('--angle', angle + 'deg');
    var level = Math.round((angle + 135) / 270 * S.maxVolume);
    var dots = $('volDots').children;
    for (var k = 0; k < dots.length; k++) dots[k].classList.toggle('lit', k <= level && level > 0);
    volKnob.setAttribute('aria-valuemax', S.maxVolume);
    volKnob.setAttribute('aria-valuenow', level);
  }
  function buildVolDots() {
    var box = $('volDots');
    if (box.children.length === S.maxVolume + 1) return;
    box.innerHTML = '';
    for (var k = 0; k <= S.maxVolume; k++) {
      var a = (-135 + 270 * k / S.maxVolume - 90) * Math.PI / 180;
      var i = document.createElement('i');
      i.style.left = (50 + 50 * Math.cos(a)) + '%';
      i.style.top = (50 + 50 * Math.sin(a)) + '%';
      box.appendChild(i);
    }
  }
  knob(volKnob, function (d, phase) {
    if (phase === 'start') { volDragging = true; volSent = S.volume; return; }
    if (phase === 'end') { volDragging = false; drawVolume(volToAngle(S.volume)); return; }
    if (phase === 'key') {
      S.volume = Math.max(0, Math.min(S.maxVolume, S.volume + d));
      drawVolume(volToAngle(S.volume));
      call('setVolume', S.volume);
      return;
    }
    volAngle = Math.max(-135, Math.min(135, volAngle + d));
    drawVolume(volAngle);
    var level = Math.round((volAngle + 135) / 270 * S.maxVolume);
    if (level !== volSent) { volSent = level; S.volume = level; call('setVolume', level); }
  });

  // Sintonía: cada 30º la aguja pasa a la emisora siguiente o anterior; al soltar se sintoniza
  var tuneKnob = $('tuneKnob'), tuneBody = tuneKnob.querySelector('.body'), tuneAngle = 0, tuneAcc = 0;
  knob(tuneKnob, function (d, phase) {
    if (phase === 'start') { turning = true; tuneAcc = 0; return; }
    if (phase === 'end') { turning = false; tuneAcc = 0; tuneTo(sel, false); return; }
    if (phase === 'key') { tuneTo(sel + d, false); return; }
    tuneAngle += d; tuneAcc += d;
    tuneBody.style.setProperty('--angle', tuneAngle + 'deg');
    while (Math.abs(tuneAcc) >= KNOB_STEP && S.stations.length) {
      var step = tuneAcc > 0 ? 1 : -1;
      tuneAcc -= step * KNOB_STEP;
      if (clampIndex(sel + step) !== sel) moveNeedle(sel + step, true);
    }
  });

  // ---------- Teclas ----------
  function on(id, fn) { var el = $(id); if (el) el.addEventListener('click', fn); }
  on('kOff', function () { call('stop'); });
  on('kBack5', function () { call('seek', -300); });
  on('kBack30', function () { call('seek', -30); });
  on('kFwd30', function () { call('seek', 30); });
  on('kLive', function () { call('live'); });
  on('kPlay', function () {
    var st = S.stations[sel];
    if (!S.active) { if (st) tuneTo(sel, true); }
    else if (st && st.id !== S.stationId) tuneTo(sel, true);
    else call('toggle');
  });
  on('bStations', function () { call('openStations'); });
  on('bSettings', function () { call('openSettings'); });

  // ---------- Pintar el estado ----------
  function fmt(s) {
    s = Math.max(0, Math.floor(s || 0));
    var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60;
    var p = function (n) { return n < 10 ? '0' + n : '' + n; };
    return h ? h + ':' + p(m) + ':' + p(x) : m + ':' + p(x);
  }
  function renderPlate() {
    var st = S.stations[sel];
    var tunedHere = S.active && st && st.id === S.stationId;
    var name = st ? st.name : (S.stationName || 'Radio Reloj');
    var state = '', extra = '', isError = false;
    if (!S.active) {
      if (S.error) { state = S.error; isError = true; } else state = 'Apagada';
      extra = st ? 'Pulsa Sonar para escucharla' : '';
    } else if (!tunedHere) {
      state = dragging || turning ? 'Suelta para sintonizarla' : 'Sintonizando…';
    } else {
      switch (S.state) {
        case 'buffering': state = 'Sintonizando…'; break;
        case 'paused': state = 'En pausa, ' + fmt(S.behind) + ' por detrás'; break;
        case 'playing': state = S.behind > 0 ? 'En diferido, ' + fmt(S.behind) + ' por detrás' : 'En directo'; break;
        default: state = '';
      }
      if (S.title) extra = S.title;
      else if (S.canSeek && S.recorded > 30) extra = 'Puedes retroceder ' + fmt(Math.min(S.recorded, 7200));
      else if (!S.canSeek && S.state === 'playing') extra = 'Esta emisora no permite retroceder';
    }
    $('name').textContent = name;
    var stEl = $('state');
    stEl.textContent = state;
    stEl.classList.toggle('error', isError);
    $('extra').textContent = extra;
  }
  function setKey(id, down, disabled) {
    var el = $(id);
    if (!el) return;
    el.classList.toggle('down', !!down);
    el.disabled = !!disabled;
  }
  function renderKeys() {
    var paused = S.active && S.state === 'paused';
    var seekable = !!S.active && !!S.canSeek;
    var live = seekable && S.state === 'playing' && !(S.behind > 0);
    var other = S.active && S.stations[sel] && S.stations[sel].id !== S.stationId;
    $('kPlayLbl').textContent = !S.active || other ? 'Sonar' : paused ? 'Seguir' : 'Pausa';
    $('kPlayGlyph').textContent = !S.active || paused || other ? '▶' : '❚❚';
    setKey('kPlay', paused, false);
    setKey('kBack5', false, !seekable);
    setKey('kBack30', false, !seekable);
    setKey('kFwd30', false, !seekable || !(S.behind > 0));
    setKey('kLive', live, !seekable);
    setKey('kOff', false, !S.active);
  }
  function renderAll() {
    radio.classList.toggle('on', !!S.active && S.state !== 'paused');
    radio.classList.toggle('paused', !!S.active && S.state === 'paused');
    radio.classList.toggle('tuning', !!S.active && S.state === 'buffering');
    renderPlate();
    renderKeys();
    if (skin.render) skin.render(S);
  }

  window.radioUpdate = function (json) {
    var s = typeof json === 'string' ? JSON.parse(json) : json;
    if (pending) {
      if (s.stationId === pending.id || Date.now() > pending.until) pending = null;
      else { delete s.stationId; delete s.stationName; delete s.state; delete s.active; }
    }
    for (var k in s) S[k] = s[k];
    renderStations();
    // La aguja solo va sola a otra emisora si ha cambiado la que suena (desde el reloj,
    // la notificación…); si no, se queda donde la ha dejado el usuario.
    if (S.stationId !== followedId) {
      followedId = S.stationId;
      var idx = indexOfId(S.stationId);
      if (idx >= 0 && !dragging && !turning) moveNeedle(idx, true);
    }
    buildVolDots();
    if (!volDragging) { volAngle = volToAngle(S.volume); drawVolume(volAngle); }
    renderAll();
  };
  window.addEventListener('resize', function () { setOffset(offsetFor(sel), false); });

  if (skin.render) skin.render(S);
  call('ready');

  // ---------- Simulación para ver el diseño fuera del móvil ----------
  function makeMock() {
    var names = ['Cadena SER', 'COPE', 'Onda Cero', 'Radio Nacional', 'RNE Madrid', 'esRadio', 'Radio Marca', 'Radio 5', 'Onda Madrid',
      'IB3 Ràdio', 'LOS40', 'LOS40 Classic', 'Cadena 100', 'Cadena Dial', 'Rock FM', 'Europa FM', 'Kiss FM', 'MegaStar FM',
      'Melodía FM', 'Hit FM', 'Radiolé', 'Radio 3', 'Radio Clásica'];
    var st = { active: false, state: 'idle', stationId: 'st3', stationName: 'Radio Nacional', title: '', error: '', behind: 0,
      recorded: 0, canSeek: false, volume: 8, maxVolume: 15,
      stations: names.map(function (n, i) { return { id: 'st' + i, name: n }; }) };
    var scen = (location.hash || '#off').slice(1);
    if (scen !== 'off') { st.active = true; st.canSeek = true; st.recorded = 1810; }
    if (scen === 'playing') { st.state = 'playing'; st.stationId = 'st0'; st.stationName = 'Cadena SER'; st.title = 'Hoy por hoy'; }
    if (scen === 'behind') { st.state = 'playing'; st.stationId = 'st12'; st.stationName = 'Cadena 100'; st.behind = 754; }
    if (scen === 'paused') { st.state = 'paused'; st.stationId = 'st10'; st.stationName = 'LOS40'; st.behind = 185; }
    if (scen === 'buffering') { st.state = 'buffering'; st.stationId = 'st21'; st.stationName = 'Radio 3'; st.recorded = 0; st.canSeek = false; }
    function push() { setTimeout(function () { window.radioUpdate(JSON.parse(JSON.stringify(st))); }, 0); }
    window.mockLog = [];
    return {
      ready: push,
      play: function (id) { window.mockLog.push('play ' + id); st.active = true; st.state = 'playing'; st.stationId = id; st.stationName = st.stations[+id.slice(2)].name; st.behind = 0; st.canSeek = true; push(); },
      toggle: function () { window.mockLog.push('toggle'); st.state = st.state === 'paused' ? 'playing' : 'paused'; push(); },
      stop: function () { window.mockLog.push('stop'); st.active = false; st.state = 'idle'; push(); },
      seek: function (by) { window.mockLog.push('seek ' + by); st.behind = Math.max(0, st.behind - by); push(); },
      live: function () { window.mockLog.push('live'); st.behind = 0; st.state = 'playing'; push(); },
      setVolume: function (v) { st.volume = v; push(); },
      openStations: function () { window.mockLog.push('stations'); },
      openSettings: function () { window.mockLog.push('settings'); }
    };
  }
})();
