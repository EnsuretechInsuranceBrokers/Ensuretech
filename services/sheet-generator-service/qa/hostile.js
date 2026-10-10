const { chromium } = require('/opt/node22/lib/node_modules/playwright');
const U = 'file:///home/user/Ensuretech/services/sheet-generator-service/src/main/resources/static/index.html', D = __dirname + '/fixtures/';
(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
  const ctx = await b.newContext({ acceptDownloads: true, offline: true });
  const p = await ctx.newPage(); const errs = [], dialogs = [];
  p.on('pageerror', e => errs.push('pageerror: ' + e.message));
  p.on('dialog', d => { dialogs.push(d.message()); d.dismiss(); });
  p.on('console', m => { if (m.type() === 'error' && !/ERR_INTERNET|Failed to load resource|ERR_NAME|ERR_FAILED/.test(m.text())) errs.push('console: ' + m.text()); });
  await p.goto(U); await p.waitForTimeout(400);
  const files = ['junk.xlsx', 'empty.csv', 'fake.xlsx', 'wide.csv', 'xss.csv', 'quote.csv', 'ragged.csv', 'bom.csv'];
  for (const id of ['rm-siFile', 'rm-premFile', 'ch-file', 'pm-srcFile']) for (const f of files) {
    const before = errs.length;
    try { await p.setInputFiles('#' + id, D + f); await p.waitForTimeout(250); } catch (e) { errs.push('driver ' + id + ' ' + f + ' ' + e.message.slice(0, 80)); }
    const sid = {'rm-siFile':'rm-siStatus','rm-premFile':'rm-premStatus','ch-file':'ch-status','pm-srcFile':'pm-specMsg'}[id]; const msg = await p.textContent('#'+sid).catch(()=>'');
    console.log(id.padEnd(11), f.padEnd(11), errs.length === before ? 'ok' : 'ERRORS ' + errs.slice(before).join(' | ').slice(0, 200), '|', msg.trim().slice(0,110));
  }
  for (const f of ['spec_bad.json', 'spec_wrong.json', 'junk.xlsx', 'empty.csv']) {
    await p.setInputFiles('#pm-specFile', D + f); await p.waitForTimeout(250);
    console.log('pm-specFile', f.padEnd(15), ((await p.textContent('#pm-specMsg').catch(() => '')) || '').trim().slice(0, 100));
  }
  // extreme / blank / negative numerics must not hang or crash the original generator
  await p.check('input[name=tmplMode][value=builtin]'); await p.selectOption('#outputFormat','csv');
  const odd = ['', '0', '-5', '1e9', '99999999999'];
  for (const v of odd) {
    await p.fill('#numEmployees', v); await p.fill('#suminsuredValue', v); await p.fill('#codeLength', v); await p.fill('#dobMinAge', v);
    const t0 = Date.now(); await p.click('#generateBtn');
    await p.waitForTimeout(1500);
    const stat = await p.evaluate(() => ({ rows: document.getElementById('statRows')?.textContent, busy: document.getElementById('generateBtn').disabled, msg: [...document.querySelectorAll('#s-generate .note.err,#s-generate .error,.toast,#genError')].map(e=>e.textContent.trim()).join('/').slice(0,100) }));
    console.log('numeric', JSON.stringify(v).padEnd(14), JSON.stringify(stat).slice(0, 160), 'dialogs', JSON.stringify(dialogs.splice(0)).slice(0,160));
    if (stat.busy) { await p.waitForTimeout(8000); console.log('   still busy after 8s:', await p.evaluate(() => document.getElementById('generateBtn').disabled)); }
  }
  // double click while running
  await p.fill('#numEmployees', '20000'); await p.fill('#suminsuredValue', '500000'); await p.fill('#codeLength', '6'); await p.fill('#dobMinAge', '21');
  await p.click('#generateBtn'); await p.click('#generateBtn', { force: true }).catch(() => {}); await p.waitForSelector('#resultsWrap:not([hidden])', { timeout: 60000 });
  console.log('double-click rows:', await p.textContent('#statRows'));
  console.log('xss flag:', await p.evaluate(() => window.__xss || null), '| dialogs:', JSON.stringify(dialogs));
  console.log('ERRORS', JSON.stringify(errs));
  await b.close();
})();
