// Copies the textures the globe page uses from the three-globe package into the app's assets.
import { copyFileSync, mkdirSync } from 'node:fs';

const out = '../../app/src/main/assets/globe/img';
mkdirSync(out, { recursive: true });
for (const name of ['earth-blue-marble.jpg', 'earth-night.jpg', 'night-sky.png']) {
  copyFileSync(`node_modules/three-globe/example/img/${name}`, `${out}/${name}`);
}
