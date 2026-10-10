const { chromium } = require('/opt/node22/lib/node_modules/playwright');
const URL_ = 'file:///home/user/Ensuretech/services/sheet-generator-service/src/main/resources/static/index.html';
(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
  const ctx = await b.newContext({ acceptDownloads: true, offline: true });
  const p = await ctx.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push('pageerror: ' + e.message));
  p.on('console', m => { if (m.type() === 'error' && !/ERR_INTERNET|Failed to load resource/.test(m.text())) errs.push('console: ' + m.text()); });
  await p.goto(URL_);
  await p.waitForTimeout(500);
  console.log('offline banner:', await p.locator('#s-generate .note.warn').first().textContent().catch(() => 'none'));
  // original section still works offline (csv)
  await p.check('input[name=tmplMode][value=builtin]');
  await p.selectOption('#outputFormat', 'csv');
  await p.fill('#numEmployees', '50');
  await p.click('#generateBtn');
  await p.waitForSelector('#resultsWrap:not([hidden])', { timeout: 20000 });
  console.log('original rows:', await p.textContent('#statRows'));
  // policy mode
  await p.check('input[name=pm-src][value=sample]');
  await p.click('#pm-useSample');
  console.log('spec:', (await p.textContent('#pm-specMsg')).trim());
  // responsiveness probe
  await p.evaluate(() => { window.__gaps = []; let last = performance.now(); const f = () => { const n = performance.now(); window.__gaps.push(n - last); last = n; requestAnimationFrame(f); }; requestAnimationFrame(f); });
  for (const [fmtChoice, fam] of [['csv', 200000], ['xlsx', 30000]]) {
    await p.selectOption('#pm-format', fmtChoice);
    await p.fill('#pm-families', String(fam));
    await p.evaluate(() => { window.__gaps = []; });
    const t0 = Date.now();
    await p.click('#pm-go');
    await p.waitForSelector('#pm-results:not([hidden])', { timeout: 280000 });
    const secs = (Date.now() - t0) / 1000;
    const gaps = await p.evaluate(() => window.__gaps);
    console.log(fmtChoice, fam, 'families ->', await p.textContent('#pm-statRows'), 'rows in', secs.toFixed(1), 's; worst frame gap', Math.max(...gaps).toFixed(0), 'ms; check:', await p.textContent('#pm-statCheck'), '|', await p.textContent('#pm-fileName'));
    const [dl] = await Promise.all([p.waitForEvent('download'), p.click('#pm-download')]);
    await dl.saveAs('/tmp/claude-0/work/big.' + fmtChoice);
  }
  // cancel mid-run
  await p.selectOption('#pm-format', 'csv'); await p.fill('#pm-families', '2000000');
  await p.click('#pm-go'); await p.waitForTimeout(1500); await p.click('#pm-cancel');
  await p.waitForSelector('#pm-results:not([hidden])', { timeout: 60000 });
  console.log('stopped run:', await p.textContent('#pm-statOp'), await p.textContent('#pm-statRows'), 'rows; check', await p.textContent('#pm-statCheck'));
  // derive from last run
  for (const op of ['REMOVE', 'UPDATE']) {
    await p.check(`input[name=pm-op][value=${op}]`); await p.fill('#pm-count', '500'); await p.click('#pm-go');
    await p.waitForSelector('#pm-results:not([hidden])'); await p.waitForTimeout(300);
    console.log(op, await p.textContent('#pm-statRows'), 'rows; check', await p.textContent('#pm-statCheck'));
  }
  console.log('errors:', JSON.stringify(errs));
  await b.close();
})();
