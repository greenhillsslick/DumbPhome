#!/usr/bin/env node
/**
 * Checks that the preview actually paints: opens it in a headless browser and
 * counts lit pixels. A blank canvas with correct-looking code is the failure
 * mode this catches (glyph masks that end up transparent).
 *
 *   node tools/test_render.js [url]
 *
 * Needs puppeteer-core and a Chrome binary; both are optional developer tools,
 * so CI does not run this one (tools/test_pixels.py covers the logic).
 */
const puppeteer = require('puppeteer-core');

const URL = process.argv[2] || 'http://127.0.0.1:8099/';
const CHROME = process.env.CHROME || '/tmp/chromium';
const MIN_LIT = 20000; // a full home screen is ~99k lit pixels at 2x

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'shell',
    args: ['--no-sandbox', '--disable-gpu'],
    env: { ...process.env, LD_LIBRARY_PATH: process.env.LD_LIBRARY_PATH || '' },
  });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.setViewport({ width: 900, height: 1000, deviceScaleFactor: 2 });
  await page.goto(URL, { waitUntil: 'networkidle0' });
  await new Promise((r) => setTimeout(r, 900));

  const result = await page.evaluate(() => {
    const canvas = document.getElementById('screen');
    const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
    let lit = 0;
    for (let i = 0; i < data.length; i += 4) if (data[i] > 60) lit++;
    return { lit, total: canvas.width * canvas.height };
  });

  await browser.close();
  console.log(`lit pixels: ${result.lit} (${((result.lit / result.total) * 100).toFixed(1)}%)`);
  if (errors.length) {
    console.error('page errors:', errors);
    process.exit(1);
  }
  if (result.lit < MIN_LIT) {
    console.error(`FAIL: fewer than ${MIN_LIT} lit pixels — the screen is probably blank`);
    process.exit(1);
  }
  console.log('ok: the preview paints text and icons');
})();
