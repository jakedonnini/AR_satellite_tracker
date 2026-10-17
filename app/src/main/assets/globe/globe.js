// Globe screen: a day/night Earth lit by the real Sun, every LEO satellite, the user's visibility
// wall and camera view pyramid, and locked satellites. Satellites are coloured by tier:
//   green  — golden zone: sunlit, over ground where the Sun is > 6° below the horizon (anyone
//            beneath it has it in their naked-eye window; design doc §12.3). Overrides the rest.
//            Brightness is a gradient: brightest = easiest to see (bright satellite, dark sky).
//   orange — golden zone *and* inside the user's camera view (green + yellow), same gradient.
//   yellow — inside the user's camera view;  cyan — above the user's horizon;  dim — everything else.
//
// Data comes from the app:
//   GET /api/catalog    → { ids: [...], names: [...] }            (once, same order as positions)
//   GET /api/positions  → Float64 epochMillis, Float64 sun direction xyz (unit, ECEF),
//                         then Float32 [x,y,z,vx,vy,vz] per satellite
//                         (Earth-fixed, km and km/s; NaN = could not propagate)
//   SatGlobe.setPose({ obs, up, dirs, lat, lon })   ← ~10 Hz from Kotlin (ECEF km / unit vectors)
//   SatGlobe.setLocked([{ id, name, color, path }]) ← when locks change / paths refresh
//
// three-globe draws Earth as a sphere of radius 100 with (lat 0, lon 0) on +z, lon 90° on +x and
// the north pole on +y, so an ECEF vector maps to three.js as (y, z, x) · 100 / 6371 km.
(function () {
  'use strict';

  const EARTH_KM = 6371.0;
  const S = 100 / EARTH_KM;
  const FAR_KM = 2500;           // view pyramid length; beyond any LEO satellite near the horizon
  const LEO_TOP_KM = 2000;       // top of LEO: sizes the visibility wall
  const POLL_MS = 5000;

  // Day/night globe: blend Blue Marble (day) and city lights (night) by the local Sun elevation,
  // with a faint green wash on the ground where the Sun is 6–18° below the horizon (the band the
  // green golden-zone satellites sit over).
  const SIN_6 = Math.sin(6 * Math.PI / 180);
  const textures = new THREE.TextureLoader();
  function srgb(t) { t.colorSpace = THREE.SRGBColorSpace; return t; }
  const globeUniforms = {
    uDay: { value: srgb(textures.load('img/earth-blue-marble.jpg')) },
    uNight: { value: srgb(textures.load('img/earth-night.jpg')) },
    uSunDir: { value: new THREE.Vector3(1, 0, 0) },   // three.js axes
    uWash: { value: 1 },                               // green twilight band on/off (Layers menu)
  };
  const globeMaterial = new THREE.ShaderMaterial({
    uniforms: globeUniforms,
    vertexShader: `
      varying vec2 vUv;
      varying vec3 vNormalW;
      void main() {
        vUv = uv;
        vNormalW = normalize(mat3(modelMatrix) * normal);
        gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      }`,
    fragmentShader: `
      uniform sampler2D uDay;
      uniform sampler2D uNight;
      uniform vec3 uSunDir;
      uniform float uWash;
      varying vec2 vUv;
      varying vec3 vNormalW;
      void main() {
        float c = dot(normalize(vNormalW), normalize(uSunDir)); // sin(Sun elevation) here
        vec3 day = texture2D(uDay, vUv).rgb;
        vec3 night = texture2D(uNight, vUv).rgb * 1.15;
        vec3 col = mix(night, day, smoothstep(-0.08, 0.08, c));
        float golden = smoothstep(-0.33, -0.29, c) * (1.0 - smoothstep(-0.12, -0.09, c));
        col += vec3(0.2, 0.78, 0.35) * 0.05 * golden * uWash;                      // sky-dark band
        gl_FragColor = vec4(col, 1.0);
        #include <colorspace_fragment>
      }`,
  });

  const world = new Globe(document.getElementById('globe'), { animateIn: false })
    .globeMaterial(globeMaterial)
    .backgroundImageUrl('img/night-sky.png')
    .showAtmosphere(true)
    .atmosphereColor('#7ab8ff')
    .atmosphereAltitude(0.15);
  world.controls().enableDamping = true;
  world.controls().rotateSpeed = 0.6;
  world.controls().zoomSpeed = 0.8;
  world.controls().minDistance = 115;
  const scene = world.scene();

  function onResize() { world.width(window.innerWidth).height(window.innerHeight); }
  window.addEventListener('resize', onResize);
  onResize();

  // ---------- helpers ----------
  function setThree(out, i, x, y, z) {  // ECEF km → three.js units
    out[i] = y * S; out[i + 1] = z * S; out[i + 2] = x * S;
  }
  function v3(x, y, z) { return new THREE.Vector3(y * S, z * S, x * S); }

  function dotTexture() {
    const c = document.createElement('canvas');
    c.width = c.height = 64;
    const g = c.getContext('2d');
    const grad = g.createRadialGradient(32, 32, 0, 32, 32, 32);
    grad.addColorStop(0, 'rgba(255,255,255,1)');
    grad.addColorStop(0.45, 'rgba(255,255,255,0.9)');
    grad.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = grad;
    g.fillRect(0, 0, 64, 64);
    return new THREE.CanvasTexture(c);
  }
  const DOT = dotTexture();

  function labelSprite(text, color) {
    const c = document.createElement('canvas');
    const g = c.getContext('2d');
    const font = 'bold 44px sans-serif';
    g.font = font;
    c.width = Math.ceil(g.measureText(text).width) + 24;
    c.height = 64;
    g.font = font;
    g.fillStyle = 'rgba(0,0,0,0.55)';
    g.fillRect(0, 0, c.width, c.height);
    g.fillStyle = color;
    g.textBaseline = 'middle';
    g.fillText(text, 12, 34);
    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, depthTest: false, transparent: true }));
    const h = 3.2;
    sprite.scale.set(h * c.width / c.height, h, 1);
    sprite.center.set(-0.08, 0.5); // label sits to the right of its anchor
    sprite.renderOrder = 10;
    return sprite;
  }

  // ---------- satellites ----------
  let ids = null;            // Int array, catalog order
  let names = null;
  let indexById = new Map();
  // Visual layers the user can switch off from the globe screen's Layers menu (all on by default).
  const layers = { visibility: true, fov: true, horizon: true, inView: true, outOfView: true };
  let m1000 = null;          // Float32Array: rough 1000-km magnitude per satellite (design doc §12.2)

  let state = null;          // Float32Array [x,y,z,vx,vy,vz]*n
  let stateEpoch = 0;
  let sunDir = null;         // unit vector toward the Sun, ECEF

  // Tier styles: [r, g, b, alpha], size (world units).
  const TIER = {
    dim:    { c: [0.62, 0.72, 0.85, 0.75], s: 1.1 },
    above:  { c: [0.50, 0.87, 0.92, 1.0], s: 1.6 },
    inView: { c: [1.00, 0.84, 0.31, 1.0], s: 2.4 },
    golden: { c: [0.20, 0.78, 0.35, 1.0], s: 1.8 },
    goldenInView: { c: [1.00, 0.58, 0.00, 1.0], s: 2.6 },
  };
  // Likely-visible gradient end points: [dullest, brightest] colour (rgba) and size.
  const GREEN = { lo: [0.16, 0.50, 0.26, 0.80], hi: [0.45, 1.00, 0.55, 1.0], sLo: 1.5, sHi: 2.6 };
  const ORANGE = { lo: [0.62, 0.36, 0.05, 0.85], hi: [1.00, 0.70, 0.15, 1.0], sLo: 2.1, sHi: 3.1 };

  /** Same name heuristics as Visibility.intrinsicMagnitude in the app. */
  function intrinsicMagnitude(name, id) {
    const n = name.toUpperCase();
    if (id === 25544 || n.startsWith('ISS')) return -1.3;
    if (n.startsWith('CSS') || n.includes('TIANHE')) return 0.0;
    if (n === 'HST') return 2.2;
    if (n.includes(' DEB') || n.endsWith('DEB')) return 8.0;
    if (n.includes('R/B')) return 4.0;
    if (n.startsWith('STARLINK')) return 5.5;
    if (n.startsWith('ONEWEB')) return 7.5;
    return 6.0;
  }

  /**
   * 0..1 "how easy to see" for a golden-zone satellite, judged for someone standing directly
   * beneath it: its estimated magnitude overhead (brightness), weighted by how dark their sky is
   * (Sun 6° below the horizon = just dark enough, 18° = fully dark).
   */
  function visibilityScore(i, r, sinSunElev) {
    const altKm = Math.max(150, r - EARTH_KM);
    // Phase angle for an overhead observer: between the Sun and the satellite→ground direction.
    const phase = Math.acos(Math.min(1, Math.max(-1, -sinSunElev)));
    const f = Math.max(1e-6, Math.sin(phase) + (Math.PI - phase) * Math.cos(phase));
    const mag = m1000[i] + 5 * Math.log10(altKm / 1000) - 2.5 * Math.log10(f);
    const brightness = Math.min(1, Math.max(0, (6 - mag) / 4));     // mag 2 → 1, mag 6 → 0
    const sunDeg = Math.asin(sinSunElev) * 180 / Math.PI;
    const darkness = Math.min(1, Math.max(0, (-sunDeg - 6) / 12));  // −6° → 0, −18° → 1
    return brightness * (0.5 + 0.5 * darkness);
  }

  function setGradient(i, g, t) {
    const o = i * 4;
    for (let c = 0; c < 4; c++) colors[o + c] = g.lo[c] + (g.hi[c] - g.lo[c]) * t;
    sizes[i] = g.sLo + (g.sHi - g.sLo) * t;
  }

  const satGeom = new THREE.BufferGeometry();
  const satUniforms = { uDot: { value: DOT }, uScale: { value: 1 } };
  const satPoints = new THREE.Points(satGeom, new THREE.ShaderMaterial({
    uniforms: satUniforms,
    transparent: true,
    depthWrite: false,
    vertexShader: `
      attribute vec4 aColor;
      attribute float aSize;
      uniform float uScale;
      varying vec4 vColor;
      void main() {
        vColor = aColor;
        vec4 mv = modelViewMatrix * vec4(position, 1.0);
        gl_PointSize = aSize * uScale / -mv.z;   // same attenuation as PointsMaterial
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: `
      uniform sampler2D uDot;
      varying vec4 vColor;
      void main() {
        float a = texture2D(uDot, gl_PointCoord).a * vColor.a;
        if (a < 0.02) discard;
        gl_FragColor = vec4(vColor.rgb, a);
      }`,
  }));
  satPoints.frustumCulled = false;
  satPoints.renderOrder = 2;
  scene.add(satPoints);

  let allPos = null;
  let colors = null;
  let sizes = null;
  const drawSize = new THREE.Vector2();

  async function loadCatalog() {
    const res = await fetch('/api/catalog', { cache: 'no-store' });
    if (res.status !== 200) return false;
    const cat = await res.json();
    ids = cat.ids;
    names = cat.names;
    m1000 = Float32Array.from(ids, (id, i) => intrinsicMagnitude(names[i] || '', id));
    indexById = new Map(ids.map((id, i) => [id, i]));
    allPos = new Float32Array(ids.length * 3);
    colors = new Float32Array(ids.length * 4);
    sizes = new Float32Array(ids.length);
    satGeom.setAttribute('position', new THREE.BufferAttribute(allPos, 3));
    satGeom.setAttribute('aColor', new THREE.BufferAttribute(colors, 4));
    satGeom.setAttribute('aSize', new THREE.BufferAttribute(sizes, 1));
    return true;
  }

  async function poll() {
    try {
      if (!ids && !(await loadCatalog())) return;
      const res = await fetch('/api/positions', { cache: 'no-store' });
      if (res.status !== 200) return;
      const buf = await res.arrayBuffer();
      const head = new Float64Array(buf, 0, 4);
      const epoch = head[0];
      const data = new Float32Array(buf, 32);
      if (data.length !== ids.length * 6) { ids = null; return; } // catalog changed: reload
      state = data;
      stateEpoch = epoch;
      sunDir = [head[1], head[2], head[3]];
      globeUniforms.uSunDir.value.set(sunDir[1], sunDir[2], sunDir[0]);
    } catch (e) {
      console.warn('globe poll failed', e);
    } finally {
      setTimeout(poll, state ? POLL_MS : 1000);
    }
  }
  poll();

  // ---------- user + view pyramid ----------
  let pose = null;
  let flewToUser = false;

  const userMarker = new THREE.Mesh(
    new THREE.SphereGeometry(0.9, 16, 12),
    new THREE.MeshBasicMaterial({ color: 0x4fc3f7 }),
  );
  userMarker.visible = false;
  scene.add(userMarker);

  // The view pyramid fades out along its length: vertex alpha goes from FADE_ALPHAS[0] at the
  // apex to 0 at the far end, through intermediate rings so the fade isn't linear-looking.
  const FADE_STEPS = [0, 0.15, 0.35, 0.6, 1];          // fraction of FAR_KM for each ring
  const FADE_FACE = [0.32, 0.22, 0.12, 0.04, 0];        // face alpha at each ring
  const FADE_EDGE = [0.95, 0.7, 0.4, 0.15, 0];          // edge alpha at each ring
  const RINGS = FADE_STEPS.length;

  function rgba(hex, alphas, perRing) {
    const c = new THREE.Color(hex);
    const out = new Float32Array(RINGS * perRing * 4);
    for (let r = 0; r < RINGS; r++) {
      for (let k = 0; k < perRing; k++) out.set([c.r, c.g, c.b, alphas[r]], (r * perRing + k) * 4);
    }
    return out;
  }

  // Vertices: ring r, corner k → index r*4 + k (ring 0 collapses onto the apex).
  const pyramidGeom = new THREE.BufferGeometry();
  pyramidGeom.setAttribute('position', new THREE.BufferAttribute(new Float32Array(RINGS * 4 * 3), 3));
  pyramidGeom.setAttribute('color', new THREE.BufferAttribute(rgba(0xffd54f, FADE_FACE, 4), 4));
  const faceIdx = [];
  for (let r = 0; r < RINGS - 1; r++) {
    for (let k = 0; k < 4; k++) {
      const a = r * 4 + k, b = r * 4 + (k + 1) % 4, c = (r + 1) * 4 + k, d = (r + 1) * 4 + (k + 1) % 4;
      faceIdx.push(a, c, d, a, d, b);
    }
  }
  pyramidGeom.setIndex(faceIdx);
  const pyramid = new THREE.Mesh(pyramidGeom, new THREE.MeshBasicMaterial({
    vertexColors: true, transparent: true, side: THREE.DoubleSide, depthWrite: false,
  }));
  pyramid.frustumCulled = false;
  pyramid.visible = false;
  scene.add(pyramid);

  // Edges: the four corner rays, faded the same way (no hard far rectangle).
  const edgesGeom = new THREE.BufferGeometry();
  edgesGeom.setAttribute('position', pyramidGeom.attributes.position);
  edgesGeom.setAttribute('color', new THREE.BufferAttribute(rgba(0xffd54f, FADE_EDGE, 4), 4));
  const edgeIdx = [];
  for (let r = 0; r < RINGS - 1; r++) for (let k = 0; k < 4; k++) edgeIdx.push(r * 4 + k, (r + 1) * 4 + k);
  edgesGeom.setIndex(edgeIdx);
  const edges = new THREE.LineSegments(edgesGeom, new THREE.LineBasicMaterial({
    vertexColors: true, transparent: true, depthWrite: false,
  }));
  edges.frustumCulled = false;
  edges.visible = false;
  scene.add(edges);

  // Visibility wall: a ring of light standing on the ground around the user.
  // Take a cone from Earth's center through the user with half-angle acos(R / (R + h)); its base
  // projected onto the surface is the circle beneath which a satellite at altitude h sits exactly
  // on the user's horizon. With h = top of LEO, no LEO satellite outside the wall can be seen.
  // The wall rises radially from that circle, fades toward space, and its top edge ripples with
  // waves travelling around the circumference.
  const WALL_ALT_KM = LEO_TOP_KM;
  const WALL_HALF_ANGLE = Math.acos(EARTH_KM / (EARTH_KM + WALL_ALT_KM));   // ≈ 40.4°
  const WALL_HEIGHT_KM = LEO_TOP_KM;

  function wallGeometry(segments, rows) {
    // Local frame: +y is the cone axis (toward the user); built once, oriented per pose.
    const pos = [], uv = [], idx = [];
    const sinA = Math.sin(WALL_HALF_ANGLE), cosA = Math.cos(WALL_HALF_ANGLE);
    for (let j = 0; j <= rows; j++) {
      const r = (EARTH_KM + WALL_HEIGHT_KM * (j / rows)) * S;
      for (let i = 0; i <= segments; i++) {
        const phi = (i / segments) * Math.PI * 2;
        pos.push(r * sinA * Math.cos(phi), r * cosA, r * sinA * Math.sin(phi));
        uv.push(i / segments, j / rows);
      }
    }
    for (let j = 0; j < rows; j++) {
      for (let i = 0; i < segments; i++) {
        const a = j * (segments + 1) + i, b = a + 1, c = a + segments + 1, d = c + 1;
        idx.push(a, c, b, b, c, d);
      }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.setIndex(idx);
    return g;
  }

  const wallUniforms = {
    uTime: { value: 0 },
    uColor: { value: new THREE.Color(0x4dd0e1) },
  };
  const fence = new THREE.Mesh(
    wallGeometry(256, 32),
    new THREE.ShaderMaterial({
      uniforms: wallUniforms,
      transparent: true,
      depthWrite: false,
      side: THREE.DoubleSide,
      blending: THREE.AdditiveBlending,
      vertexShader: `
        varying vec2 vUv;
        void main() {
          vUv = uv;
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
        }`,
      fragmentShader: `
        varying vec2 vUv;
        uniform float uTime;
        uniform vec3 uColor;
        const float TAU = 6.28318530718;
        void main() {
          float u = vUv.x;   // around the circle, 0..1 (wave counts are integers so it wraps)
          float v = vUv.y;   // ground (0) → space (1)
          // Wavy top edge: waves travelling around the ring in both directions.
          float top = 0.72
            + 0.10 * sin(u * TAU * 9.0 - uTime * 1.4)
            + 0.05 * sin(u * TAU * 17.0 + uTime * 2.1);
          float cut = 1.0 - smoothstep(top - 0.22, top, v);         // soft, rippling top
          float body = 0.60 * pow(1.0 - v, 1.2);                    // fades toward space
          float foot = 0.70 * exp(-v * 25.0);                       // bright line on the ground
          float crest = 0.65 * exp(-pow((v - (top - 0.10)) / 0.035, 2.0)); // glow riding the wave
          float a = (body + crest) * cut + foot;
          if (a < 0.004) discard;
          gl_FragColor = vec4(uColor * a, a);
        }`,
    }),
  );
  fence.frustumCulled = false;
  fence.renderOrder = 1;
  fence.visible = false;
  scene.add(fence);

  function updateFence() {
    // Cone axis: Earth's center → the user (geocentric direction of the GPS position).
    const o = pose.obs;
    const axis = new THREE.Vector3(o[1], o[2], o[0]).normalize();
    fence.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), axis);
    fence.visible = layers.horizon;
  }

  // Inward normals of the pyramid's four side planes (relative to the observer), for the in-view test.
  let planes = null;

  function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
  function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

  function updatePyramid() {
    const o = pose.obs;
    const p = pyramidGeom.attributes.position.array;
    const corners = pose.dirs.slice(1);
    FADE_STEPS.forEach((f, r) => {
      corners.forEach((d, k) => {
        const len = f * FAR_KM;
        setThree(p, (r * 4 + k) * 3, o[0] + d[0] * len, o[1] + d[1] * len, o[2] + d[2] * len);
      });
    });
    pyramidGeom.attributes.position.needsUpdate = true;

    const bore = pose.dirs[0];
    planes = corners.map((d, i) => {
      const n = cross(d, corners[(i + 1) % 4]);
      return dot(n, bore) >= 0 ? n : [-n[0], -n[1], -n[2]];
    });

    const m = v3(o[0], o[1], o[2]);
    userMarker.position.copy(m);
    userMarker.visible = true;
    pyramid.visible = edges.visible = layers.fov;
    updateFence();
  }

  // ---------- locked satellites ----------
  let locked = [];   // [{ id, name, color, marker, label, line }]

  function clearLocked() {
    locked.forEach((l) => {
      scene.remove(l.marker); scene.remove(l.label); if (l.line) scene.remove(l.line);
      l.marker.geometry.dispose(); l.marker.material.dispose();
      l.label.material.map.dispose(); l.label.material.dispose();
      if (l.line) { l.line.geometry.dispose(); l.line.material.dispose(); }
    });
    locked = [];
  }

  function setLocked(list) {
    clearLocked();
    locked = (list || []).map((s) => {
      const color = new THREE.Color(s.color);
      const marker = new THREE.Mesh(new THREE.SphereGeometry(1.3, 12, 8), new THREE.MeshBasicMaterial({ color }));
      marker.renderOrder = 3;
      const label = labelSprite(s.name, s.color);
      let line = null;
      if (s.path && s.path.length >= 6) {
        const pts = new Float32Array(s.path.length);
        for (let i = 0; i < s.path.length; i += 3) setThree(pts, i, s.path[i], s.path[i + 1], s.path[i + 2]);
        const g = new THREE.BufferGeometry();
        g.setAttribute('position', new THREE.BufferAttribute(pts, 3));
        line = new THREE.Line(g, new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.85 }));
        line.frustumCulled = false;
        scene.add(line);
      }
      scene.add(marker);
      scene.add(label);
      return { id: s.id, marker, label, line };
    });
  }

  // ---------- per-frame update ----------
  const tmp = new THREE.Vector3();

  const startMs = performance.now();

  function frame() {
    requestAnimationFrame(frame);
    wallUniforms.uTime.value = (performance.now() - startMs) / 1000;
    if (!state || !allPos) return;
    const dt = (Date.now() - stateEpoch) / 1000;
    const n = ids.length;
    const o = pose ? pose.obs : null;
    const up = pose ? pose.up : null;
    const sun = sunDir;
    const R2 = EARTH_KM * EARTH_KM;

    for (let i = 0; i < n; i++) {
      const k = i * 6;
      const x = state[k] + state[k + 3] * dt;
      const y = state[k + 1] + state[k + 4] * dt;
      const z = state[k + 2] + state[k + 5] * dt;
      if (Number.isNaN(x)) {
        allPos[i * 3] = allPos[i * 3 + 1] = allPos[i * 3 + 2] = 0;
        sizes[i] = 0;
        continue;
      }
      setThree(allPos, i * 3, x, y, z);

      let tier = TIER.dim;
      let inView = false;
      if (planes) {
        const rx = x - o[0], ry = y - o[1], rz = z - o[2];
        if (rx * up[0] + ry * up[1] + rz * up[2] > 0) {             // above the user's horizon
          tier = TIER.above;
          const r = [rx, ry, rz];
          if (dot(r, r) <= FAR_KM * FAR_KM &&
              dot(r, planes[0]) >= 0 && dot(r, planes[1]) >= 0 &&
              dot(r, planes[2]) >= 0 && dot(r, planes[3]) >= 0) {
            tier = TIER.inView;
            inView = true;
          }
        }
      }
      if (sun && layers.visibility) {
        const along = x * sun[0] + y * sun[1] + z * sun[2];
        const r2 = x * x + y * y + z * z;
        const sunlit = along >= 0 || r2 - along * along > R2;          // outside Earth's shadow
        // Sun elevation at the ground point beneath the satellite: sin(el) = (p̂ · sun).
        const r = Math.sqrt(r2);
        const sinSunElev = along / r;
        if (sunlit && sinSunElev < -SIN_6) {
          // Likely visible: always shown, whatever the other layers, with a brightness gradient.
          setGradient(i, inView ? ORANGE : GREEN, visibilityScore(i, r, sinSunElev));
          continue;
        }
      }
      const shown = tier === TIER.dim ? layers.outOfView : layers.inView;
      colors.set(tier.c, i * 4);
      sizes[i] = shown ? tier.s : 0;
    }
    satGeom.attributes.position.needsUpdate = true;
    satGeom.attributes.aColor.needsUpdate = true;
    satGeom.attributes.aSize.needsUpdate = true;
    world.renderer().getDrawingBufferSize(drawSize);
    satUniforms.uScale.value = drawSize.y * 0.5;

    for (const l of locked) {
      const i = indexById.get(l.id);
      if (i === undefined) continue;
      l.marker.position.set(allPos[i * 3], allPos[i * 3 + 1], allPos[i * 3 + 2]);
      tmp.copy(l.marker.position).normalize().multiplyScalar(2.5);
      l.label.position.copy(l.marker.position).add(tmp);
    }
  }
  requestAnimationFrame(frame);

  // ---------- API for the app ----------
  window.SatGlobe = {
    setPose(p) {
      pose = p;
      if (!p) {
        planes = null;
        userMarker.visible = pyramid.visible = edges.visible = fence.visible = false;
        return;
      }
      updatePyramid();
      if (!flewToUser) {
        flewToUser = true;
        world.pointOfView({ lat: p.lat, lng: p.lon, altitude: 1.4 }, 1500);
      }
    },
    setLocked,
    /** Show/hide visual layers; any subset of { visibility, fov, horizon, inView, outOfView }. */
    setLayers(next) {
      Object.assign(layers, next);
      // The green ground band belongs to the "likely visible" layer.
      globeUniforms.uWash.value = layers.visibility ? 1 : 0;
      if (pose) {
        pyramid.visible = edges.visible = layers.fov;
        fence.visible = layers.horizon;
      }
    },
    flyToUser() {
      if (pose) world.pointOfView({ lat: pose.lat, lng: pose.lon, altitude: 1.4 }, 1000);
    },
    pause() { world.pauseAnimation(); },
    /** The globe.gl instance, for dev tools (tools/globe/dev-server.mjs). */
    world,
    resume() { world.resumeAnimation(); },
  };
})();
