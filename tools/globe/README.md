# Globe bundle

Builds the offline JavaScript the globe screen loads in its WebView:

- `app/src/main/assets/globe/vendor.js` — [globe.gl](https://github.com/vasturiano/globe.gl) and
  [three.js](https://threejs.org/) bundled as one IIFE that exposes `window.Globe` and `window.THREE`
  (a single three.js instance, so our own scene objects mix cleanly with globe.gl's).
- `app/src/main/assets/globe/img/` — Earth and star-field textures from the three-globe package
  (Earth imagery: NASA Blue Marble, public domain).

The app's own globe code lives in `app/src/main/assets/globe/globe.js` and is not bundled.

Rebuild after changing versions in `package.json`:

```bash
npm install --ignore-scripts
npm run build
```

Versions are pinned exactly (and `three-globe` via `overrides`) so the bundle is reproducible.
