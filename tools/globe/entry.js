// One three.js instance shared by globe.gl and our own scene objects (avoids the
// "multiple instances of three.js" problem you get mixing UMD builds).
import * as THREE from 'three';
import Globe from 'globe.gl';

window.THREE = THREE;
window.Globe = Globe;
