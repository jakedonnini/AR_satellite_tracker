// Desktop preview of the globe page without the app: serves app/src/main/assets at /assets/ and
// fakes /api/catalog + /api/positions with synthetic circular LEO orbits.
//
//   node dev-server.mjs        → http://localhost:5174/assets/globe/index.html?demo
//
// With ?demo the page is also fed a sample pose and two locked satellites (see the end of globe.js
// calls below, injected by this server), so the view pyramid and lock rendering can be checked.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const PORT = 5174;
const ASSETS = normalize(join(import.meta.dirname, '../../app/src/main/assets'));
const N = 16000;
const MU = 398600.4418; // km^3/s^2

// Deterministic pseudo-random orbits.
let seed = 42;
const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 2 ** 32);
const orbits = Array.from({ length: N }, () => ({
  r: 6371 + 340 + rnd() * 900,
  inc: (rnd() < 0.6 ? 53 : 30 + rnd() * 68) * Math.PI / 180,
  raan: rnd() * 2 * Math.PI,
  phase: rnd() * 2 * Math.PI,
}));

function state(o, tSec) {
  const n = Math.sqrt(MU / o.r ** 3);
  const u = o.phase + n * tSec;
  const v = Math.sqrt(MU / o.r);
  // Position/velocity in the orbital plane, rotated by inclination then RAAN.
  const px = o.r * Math.cos(u), py = o.r * Math.sin(u);
  const vx = -v * Math.sin(u), vy = v * Math.cos(u);
  const ci = Math.cos(o.inc), si = Math.sin(o.inc), cO = Math.cos(o.raan), sO = Math.sin(o.raan);
  const rot = (x, y) => [x * cO - y * ci * sO, x * sO + y * ci * cO, y * si];
  return [...rot(px, py), ...rot(vx, vy)];
}

// Approximate Sun direction (ECEF unit vector): subsolar longitude from UTC time of day,
// declination from day of year. Good to a few degrees, which is plenty for a preview.
function sunDirection(ms) {
  const d = new Date(ms);
  const hours = d.getUTCHours() + d.getUTCMinutes() / 60;
  const doy = (ms - Date.UTC(d.getUTCFullYear(), 0, 0)) / 86400000;
  const lon = (12 - hours) * 15 * Math.PI / 180;
  const dec = 23.44 * Math.sin(2 * Math.PI * (doy - 81) / 365) * Math.PI / 180;
  return [Math.cos(dec) * Math.cos(lon), Math.cos(dec) * Math.sin(lon), Math.sin(dec)];
}

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.jpg': 'image/jpeg', '.png': 'image/png' };

createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  if (url.pathname === '/api/catalog') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ids: orbits.map((_, i) => 90000 + i), names: orbits.map((_, i) => `DEMO-${i}`) }));
    return;
  }
  if (url.pathname === '/api/positions') {
    const now = Date.now();
    const buf = Buffer.alloc(32 + N * 6 * 4);
    buf.writeDoubleLE(now, 0);
    sunDirection(now).forEach((x, k) => buf.writeDoubleLE(x, 8 + k * 8));
    orbits.forEach((o, i) => state(o, now / 1000).forEach((x, k) => buf.writeFloatLE(x, 32 + (i * 6 + k) * 4)));
    res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(buf);
    return;
  }
  if (url.pathname.startsWith('/assets/')) {
    const file = normalize(join(ASSETS, url.pathname.slice('/assets/'.length)));
    if (!file.startsWith(ASSETS)) { res.writeHead(403).end(); return; }
    try {
      let body = await readFile(file);
      if (url.pathname.endsWith('/index.html')) {
        body = body.toString().replace('</body>', '<script src="/demo.js"></script></body>');
      }
      res.writeHead(200, { 'Content-Type': MIME[extname(file)] ?? 'application/octet-stream' });
      res.end(body);
    } catch {
      res.writeHead(404).end();
    }
    return;
  }
  if (url.pathname === '/demo.js') {
    res.writeHead(200, { 'Content-Type': 'text/javascript' });
    res.end(DEMO_JS);
    return;
  }
  res.writeHead(404).end();
}).listen(PORT, () => console.log(`http://localhost:${PORT}/assets/globe/index.html?demo`));

// Sample pose: Philadelphia, camera facing south-east, tilted 40° up (ECEF unit vectors computed
// the same way GlobeController does), plus two locked demo satellites with straight-ish paths.
const DEMO_JS = `
if (location.search.includes('demo')) setTimeout(() => {
  const d2r = Math.PI / 180, lat = 39.95 * d2r, lon = -75.19 * d2r, a = 6378.137, e2 = 0.00669437999;
  const N = a / Math.sqrt(1 - e2 * Math.sin(lat) ** 2);
  const obs = [N * Math.cos(lat) * Math.cos(lon), N * Math.cos(lat) * Math.sin(lon), N * (1 - e2) * Math.sin(lat)];
  const enu = (e, n, u) => [
    -Math.sin(lon) * e - Math.sin(lat) * Math.cos(lon) * n + Math.cos(lat) * Math.cos(lon) * u,
     Math.cos(lon) * e - Math.sin(lat) * Math.sin(lon) * n + Math.cos(lat) * Math.sin(lon) * u,
     Math.cos(lat) * n + Math.sin(lat) * u];
  const dir = (az, el) => enu(Math.cos(el * d2r) * Math.sin(az * d2r), Math.cos(el * d2r) * Math.cos(az * d2r), Math.sin(el * d2r));
  const pose = { obs, up: enu(0, 0, 1), lat: 39.95, lon: -75.19,
    dirs: [dir(135, 40), dir(117, 61), dir(153, 61), dir(145, 19), dir(125, 19)] };
  SatGlobe.setPose(pose);
  const path = (k) => { const p = []; for (let i = -45; i <= 45; i++) { const t = i * 0.07;
    p.push(6800 * Math.cos(t + k), 6800 * Math.sin(t + k) * 0.6, 6800 * Math.sin(t + k) * 0.8); } return p; };
  SatGlobe.setLocked([
    { id: 90000, name: 'DEMO-0', color: '#00E5FF', path: path(0) },
    { id: 90001, name: 'DEMO-1', color: '#FF4081', path: path(2) },
  ]);
}, 1500);
`;
