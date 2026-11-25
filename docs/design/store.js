// The Play Store's pictures, from the icon gen_icon.py writes: the 512 px app icon and the 1024 x 500 feature graphic.
//   node docs/design/store.js        (Playwright's Chromium; writes docs/play/icon-512.png and docs/play/feature-graphic.png)
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const icon = fs.readFileSync(path.join(root, 'docs/design/icon/icon.svg'), 'utf8');
// The bird alone (no square), on the 108 canvas.
const bird = icon.replace(/^<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '').replace(/<rect[^>]*\/>/, '');

// Play masks the 512 icon itself (a rounded square), so it is the full square, like the launcher's middle 72.
const iconPage = `<html><body style="margin:0">
<svg width="512" height="512" viewBox="18 18 72 72"><rect x="0" y="0" width="108" height="108" fill="#111111"/>${bird}</svg>
</body></html>`;

// The feature graphic: the night, the bird asleep, the name and one line on what it does. Play may crop the edges,
// so everything keeps to the middle.
const graphicPage = `<html><body style="margin:0">
<div style="width:1024px;height:500px;background:#111111;color:#fff;display:flex;align-items:center;justify-content:center;gap:36px;font-family:'Inter Display','Inter',Roboto,sans-serif">
  <svg width="280" height="280" viewBox="22 22 64 64" style="flex:none">${bird}</svg>
  <div>
    <div style="font-size:112px;font-weight:800;letter-spacing:-0.035em;line-height:1">Sleepers</div>
    <div style="font-size:34px;font-weight:500;color:#a3a3a3;margin-top:18px;letter-spacing:-0.01em">Sleep on the bus.<br>We'll wake you before your stop.</div>
  </div>
</div></body></html>`;

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 512, height: 512 }, deviceScaleFactor: 1 });
  await p.setContent(iconPage);
  await p.screenshot({ path: path.join(root, 'docs/play/icon-512.png'), clip: { x: 0, y: 0, width: 512, height: 512 } });
  await p.setViewportSize({ width: 1024, height: 500 });
  await p.setContent(graphicPage);
  await p.screenshot({ path: path.join(root, 'docs/play/feature-graphic.png'), clip: { x: 0, y: 0, width: 1024, height: 500 } });
  await b.close();
  console.log('wrote docs/play/icon-512.png and docs/play/feature-graphic.png');
})();
