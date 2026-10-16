// Globe screen: Earth, every LEO satellite, the user's camera view pyramid and locked satellites.
//
// Data comes from the app:
//   GET /api/catalog    → { ids: [...], names: [...] }            (once, same order as positions)
//   GET /api/positions  → Float64 epochMillis, then Float32 [x,y,z,vx,vy,vz] per satellite
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
  const POLL_MS = 5000;

  const world = new Globe(document.getElementById('globe'), { animateIn: false })
    .globeImageUrl('img/earth-blue-marble.jpg')
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
  let state = null;          // Float32Array [x,y,z,vx,vy,vz]*n
  let stateEpoch = 0;

  const allGeom = new THREE.BufferGeometry();
  const allPoints = new THREE.Points(allGeom, new THREE.PointsMaterial({
    size: 1.1, map: DOT, color: 0x9fb8d8, transparent: true, opacity: 0.75, depthWrite: false,
  }));
  allPoints.frustumCulled = false;
  scene.add(allPoints);

  const inViewGeom = new THREE.BufferGeometry();
  const inViewPoints = new THREE.Points(inViewGeom, new THREE.PointsMaterial({
    size: 2.4, map: DOT, color: 0xffd54f, transparent: true, depthWrite: false,
  }));
  inViewPoints.frustumCulled = false;
  inViewPoints.renderOrder = 2;
  inViewPoints.visible = false;
  scene.add(inViewPoints);

  let allPos = null;
  let inViewPos = null;

  async function loadCatalog() {
    const res = await fetch('/api/catalog', { cache: 'no-store' });
    if (res.status !== 200) return false;
    const cat = await res.json();
    ids = cat.ids;
    names = cat.names;
    indexById = new Map(ids.map((id, i) => [id, i]));
    allPos = new Float32Array(ids.length * 3);
    inViewPos = new Float32Array(ids.length * 3);
    allGeom.setAttribute('position', new THREE.BufferAttribute(allPos, 3));
    inViewGeom.setAttribute('position', new THREE.BufferAttribute(inViewPos, 3));
    inViewGeom.setDrawRange(0, 0);
    return true;
  }

  async function poll() {
    try {
      if (!ids && !(await loadCatalog())) return;
      const res = await fetch('/api/positions', { cache: 'no-store' });
      if (res.status !== 200) return;
      const buf = await res.arrayBuffer();
      const epoch = new Float64Array(buf, 0, 1)[0];
      const data = new Float32Array(buf, 8);
      if (data.length !== ids.length * 6) { ids = null; return; } // catalog changed: reload
      state = data;
      stateEpoch = epoch;
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

  const pyramidGeom = new THREE.BufferGeometry();
  pyramidGeom.setAttribute('position', new THREE.BufferAttribute(new Float32Array(5 * 3), 3));
  pyramidGeom.setIndex([0, 1, 2, 0, 2, 3, 0, 3, 4, 0, 4, 1, 1, 2, 3, 1, 3, 4]);
  const pyramid = new THREE.Mesh(pyramidGeom, new THREE.MeshBasicMaterial({
    color: 0xffd54f, transparent: true, opacity: 0.16, side: THREE.DoubleSide, depthWrite: false,
  }));
  pyramid.frustumCulled = false;
  pyramid.visible = false;
  scene.add(pyramid);

  const edgesGeom = new THREE.BufferGeometry();
  edgesGeom.setAttribute('position', new THREE.BufferAttribute(new Float32Array(16 * 3), 3));
  const edges = new THREE.LineSegments(edgesGeom, new THREE.LineBasicMaterial({
    color: 0xffd54f, transparent: true, opacity: 0.7,
  }));
  edges.frustumCulled = false;
  edges.visible = false;
  scene.add(edges);

  // Inward normals of the pyramid's four side planes (relative to the observer), for the in-view test.
  let planes = null;

  function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
  function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

  function updatePyramid() {
    const o = pose.obs;
    const p = pyramidGeom.attributes.position.array;
    setThree(p, 0, o[0], o[1], o[2]);
    const corners = pose.dirs.slice(1);
    corners.forEach((d, i) => {
      setThree(p, (i + 1) * 3, o[0] + d[0] * FAR_KM, o[1] + d[1] * FAR_KM, o[2] + d[2] * FAR_KM);
    });
    pyramidGeom.attributes.position.needsUpdate = true;
    pyramidGeom.computeBoundingSphere();

    // Edges: apex→each corner, then the far rectangle.
    const e = edgesGeom.attributes.position.array;
    const pairs = [[0, 1], [0, 2], [0, 3], [0, 4], [1, 2], [2, 3], [3, 4], [4, 1]];
    pairs.forEach(([a, b], k) => {
      e.set(p.subarray(a * 3, a * 3 + 3), k * 6);
      e.set(p.subarray(b * 3, b * 3 + 3), k * 6 + 3);
    });
    edgesGeom.attributes.position.needsUpdate = true;

    const bore = pose.dirs[0];
    planes = corners.map((d, i) => {
      const n = cross(d, corners[(i + 1) % 4]);
      return dot(n, bore) >= 0 ? n : [-n[0], -n[1], -n[2]];
    });

    const m = v3(o[0], o[1], o[2]);
    userMarker.position.copy(m);
    userMarker.visible = pyramid.visible = edges.visible = true;
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

  function frame() {
    requestAnimationFrame(frame);
    if (!state || !allPos) return;
    const dt = (Date.now() - stateEpoch) / 1000;
    const n = ids.length;
    const o = pose ? pose.obs : null;
    const up = pose ? pose.up : null;
    let inView = 0;

    for (let i = 0; i < n; i++) {
      const k = i * 6;
      const x = state[k] + state[k + 3] * dt;
      const y = state[k + 1] + state[k + 4] * dt;
      const z = state[k + 2] + state[k + 5] * dt;
      if (Number.isNaN(x)) { allPos[i * 3] = allPos[i * 3 + 1] = allPos[i * 3 + 2] = 0; continue; }
      setThree(allPos, i * 3, x, y, z);

      if (planes) {
        const r = [x - o[0], y - o[1], z - o[2]];
        if (dot(r, up) <= 0) continue;                          // below the user's horizon
        if (dot(r, r) > FAR_KM * FAR_KM) continue;              // out of range
        if (dot(r, planes[0]) < 0 || dot(r, planes[1]) < 0 ||
            dot(r, planes[2]) < 0 || dot(r, planes[3]) < 0) continue;
        setThree(inViewPos, inView * 3, x, y, z);
        inView++;
      }
    }
    allGeom.attributes.position.needsUpdate = true;
    inViewGeom.setDrawRange(0, inView);
    inViewPoints.visible = inView > 0; // drawing an empty range makes WebGL log warnings
    inViewGeom.attributes.position.needsUpdate = true;

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
        userMarker.visible = pyramid.visible = edges.visible = false;
        inViewGeom.setDrawRange(0, 0);
        inViewPoints.visible = false;
        return;
      }
      updatePyramid();
      if (!flewToUser) {
        flewToUser = true;
        world.pointOfView({ lat: p.lat, lng: p.lon, altitude: 1.4 }, 1500);
      }
    },
    setLocked,
    flyToUser() {
      if (pose) world.pointOfView({ lat: pose.lat, lng: pose.lon, altitude: 1.4 }, 1000);
    },
    pause() { world.pauseAnimation(); },
    resume() { world.resumeAnimation(); },
  };
})();
