// Opens every "Settings and tools" area as a broker and an HR user, runs every read-only card it can fill in from the dropdowns, and checks the employer dropdown narrows the policies.
//   NODE_PATH=/opt/node-tools/node_modules node qa/portal-manage-smoke.js        needs the stack and Playwright
const { chromium } = require('playwright');
const BASE = process.env.PORTAL || 'http://127.0.0.1:8090', PASSWORD = 'Str0ng-Pass-123!';
let failed = 0; const say = (ok, m) => { console.log((ok ? 'PASS ' : 'FAIL ') + m); if (!ok) failed++; };
async function signIn(browser, email) {
  const page = await browser.newPage(); const errors = []; page.on('pageerror', e => errors.push(e.message));
  for (let i = 0; i < 4; i++) { await page.goto(BASE + '/login'); await page.fill('input[name=email]', email); await page.fill('input[name=password]', PASSWORD); await page.click('button'); await page.waitForTimeout(1500); if (!page.url().includes('/login')) break; await page.waitForTimeout(15000); }
  page.errors = errors; return page;
}
async function areas(page, who) {
  await page.goto(BASE + '/app/manage');
  const links = await page.$$eval('a.card', as => as.map(a => a.getAttribute('href')));
  say(links.length > 0, who + ': tools index lists ' + links.length + ' areas');
  let cards = 0, ran = 0, bad = [];
  for (const href of links) {
    await page.goto(BASE + href); await page.waitForSelector('#manage article', { timeout: 8000 });
    const arts = await page.$$('#manage article'); cards += arts.length;
    for (const art of arts) {
      const id = await art.getAttribute('id'); const get = await art.$('button[type=submit]'); const label = (await get.textContent()).trim();
      if (label !== 'Show') continue;                                           // read-only cards only
      const needs = await art.$$('select[id*="-p-"], input[id*="-p-"]');
      let ok = true;
      for (const n of needs) { const tag = await n.evaluate(e => e.tagName); if (tag === 'SELECT') { const o = await n.$$('option'); if (o.length > 1) await n.selectOption({ index: 1 }); else ok = false; } else ok = false; }
      if (!ok) continue;
      await get.click(); await page.waitForTimeout(700); ran++;
      const alert = await art.$('[role=alert]'); if (alert) { const t = (await alert.textContent()).trim(); if (/went wrong|reference/.test(t)) bad.push(id + ': ' + t.slice(0, 80)); }
    }
  }
  say(bad.length === 0, who + ': ' + cards + ' cards across ' + links.length + ' areas, ' + ran + ' read-only cards run' + (bad.length ? ' — server errors: ' + bad.join(' | ') : ' with no server error'));
  say(page.errors.length === 0, who + ': no JavaScript errors' + (page.errors.length ? ' — ' + page.errors[0] : ''));
}
(async () => {
  const browser = await chromium.launch();
  const broker = await signIn(browser, 'meera.kapoor@northwind.example');
  await broker.goto(BASE + '/app/endorsements');
  const all = await broker.$$eval('#policy-pick option', o => o.length);
  await broker.selectOption('#employer-pick', { label: 'Acme Logistics' });
  const some = await broker.$$eval('#policy-pick option', o => o.map(x => x.textContent.trim()));
  say(some.length < all && some.every(t => /Acme Logistics/.test(t)), 'employer dropdown narrows the policy dropdown (' + all + ' → ' + some.length + ': ' + some.join(', ') + ')');
  await areas(broker, 'broker');
  const hr = await signIn(browser, 'priya.menon@acme.example'); await areas(hr, 'HR');
  await browser.close(); process.exit(failed ? 1 : 0);
})();
