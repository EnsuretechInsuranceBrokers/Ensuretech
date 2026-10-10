// Drives the QA timing page in a real browser, the way a person does: signs in as a broker, picks a policy, clicks the run buttons and prints the results table.
//   node qa/qa-timing-page.js [rows] [rounds]           needs the stack with BFF_QA_TOOLS=true (the default in local.env) and Playwright
const { chromium } = require('playwright');
const BASE = process.env.PORTAL || 'http://127.0.0.1:8090';
const ROWS = process.argv[2] || '1000', ROUNDS = +(process.argv[3] || 1);
(async () => {
  const browser = await chromium.launch(), page = await browser.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => m.type() === 'error' && errors.push(m.text()));
  await page.goto(BASE + '/login');
  await page.fill('input[name=email]', 'meera.kapoor@northwind.example'); await page.fill('input[name=password]', 'Str0ng-Pass-123!');
  await page.click('button'); await page.waitForURL(u => !u.toString().includes('/login'), { timeout: 30000 });
  await page.goto(BASE + '/app/qa/timing');
  await page.selectOption('#policy', { index: 1 }).catch(() => {});                 // policy 2 (the first is the small sample policy)
  await page.fill('#rows', ROWS);
  const out = [];
  for (let i = 0; i < ROUNDS; i++) {
    await page.click('button[data-run=all]');
    await page.waitForFunction(() => !document.querySelector('button[data-run]').disabled, null, { timeout: 180000 });
    out.push(await page.$$eval('#results tr', trs => trs.map(tr => [...tr.children].slice(0, 7).map(td => td.textContent.trim()).join(' | '))));
  }
  await page.screenshot({ path: __dirname + '/out/qa-timing.png', fullPage: true });
  for (const [i, r] of out.entries()) { console.log('--- round ' + (i + 1)); r.reverse().forEach(l => console.log(l)); }
  console.log('policy:', await page.$eval('#policy', s => s.selectedOptions[0].textContent), '| page errors:', errors.length ? errors : 'none');
  await browser.close();
})();
