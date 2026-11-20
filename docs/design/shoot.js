// Renders docs/design/mockups.html to PNGs: node docs/design/shoot.js <docs/design dir> <out dir>
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const dir = process.argv[2];
  const out = process.argv[3] || path.join(dir, 'screens');
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1800, height: 1200 }, deviceScaleFactor: 2 });
  await p.goto('file://' + path.join(dir, 'mockups.html'));
  await p.waitForLoadState('networkidle');
  await p.evaluate(() => document.fonts.ready);
  for (const el of await p.$$('.frame .phone')) {
    const id = await el.evaluate(e => e.id);
    await el.screenshot({ path: path.join(out, id + '.png') });
  }
  for (const el of await p.$$('.flow')) {
    const id = await el.evaluate(e => e.id);
    await el.screenshot({ path: path.join(out, id + '.png') });
  }
  await b.close();
})();
