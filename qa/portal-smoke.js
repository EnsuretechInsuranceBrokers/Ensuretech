// Browser sanity run of the portal, the way a person uses it. Needs the stack from scripts/dev-up.sh and Playwright:
//     npm i -D playwright && npx playwright install chromium        (once)
//     node qa/portal-smoke.js            screenshots go to qa/out/
// Fails (exit 1) on a missing heading, a JavaScript error, or a page that answers an error. Add a check by adding a line to STEPS.
const { chromium } = require('playwright');
const fs = require('fs');
const BASE = process.env.PORTAL || 'http://127.0.0.1:8090';
const PASSWORD = 'Str0ng-Pass-123!';
fs.mkdirSync(__dirname + '/out', { recursive: true });

const STEPS = {
  broker: ['meera.kapoor@northwind.example', [
    ['/app', 'Dashboard'], ['/app/policies', 'Policies'], ['/app/employers', 'Employers'], ['/app/endorse', 'Add or remove people'], ['/app/endorsements', 'Endorsements'],
    ['/app/cd', 'CD balance'], ['/app/member-ids', 'Member ids and e-cards'], ['/app/member-ids/1', 'GMC-ACME-2627'],
  ]],
  hr: ['priya.menon@acme.example', [['/app', 'Dashboard'], ['/app/policies', 'Policies'], ['/app/endorse', 'Add or remove people']]],
};

(async () => {
  const browser = await chromium.launch();
  let failed = 0;
  for (const [who, [email, pages]] of Object.entries(STEPS)) {
    const ctx = await browser.newContext({ viewport: { width: 1300, height: 900 } });
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push('script error: ' + e));
    // the seeded demo announcement shows an image from example.com, which cannot load offline: that is seed data, not a portal fault. Any other failed request is.
    page.on('console', m => { if (m.type() === 'error' && !/favicon|Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
    page.on('requestfailed', r => { if (!/^https:\/\/example\.com\//.test(r.url()) && !/favicon/.test(r.url())) errors.push('request failed: ' + r.url()); });
    // sign-in is rate limited (the lock working): if it answers "too many attempts", wait and try again
    for (let attempt = 1; attempt <= 4; attempt++) {
      await page.goto(BASE + '/login');
      await page.fill('input[name=email]', email); await page.fill('input[name=password]', PASSWORD); await page.click('button');
      await page.waitForLoadState('networkidle');
      if (!page.url().includes('/login')) break;
      console.log(`  (sign-in for ${who} refused, probably rate limited; waiting 20 s, attempt ${attempt})`);
      await page.waitForTimeout(20000);
    }
    for (const [path, heading] of pages) {
      const res = await page.goto(BASE + path);
      await page.waitForLoadState('networkidle');
      const text = await page.locator('main').innerText();
      const ok = res.status() < 400 && text.includes(heading);
      console.log((ok ? 'PASS' : 'FAIL') + ` ${who} ${path} ${ok ? '' : `(status ${res.status()}, no "${heading}")`}`);
      if (!ok) { failed++; await page.screenshot({ path: `${__dirname}/out/${who}${path.replace(/\W+/g, '_')}.png`, fullPage: true }); }
    }
    for (const e of errors) { console.log('FAIL ' + who + ' ' + e); failed++; }
    await ctx.close();
  }
  await browser.close();
  console.log(failed ? `\n${failed} problem(s)` : '\nall pages rendered, no script errors');
  process.exit(failed ? 1 : 0);
})();
