const { chromium } = require('/opt/node22/lib/node_modules/playwright');
const U = 'file:///home/user/Ensuretech/services/sheet-generator-service/src/main/resources/static/index.html', D = __dirname + '/fixtures/';
(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
  const p = await (await b.newContext({ acceptDownloads: true, offline: true })).newPage(); const errs = [], dialogs = [];
  p.on('pageerror', e => errs.push(e.message)); p.on('dialog', d => { dialogs.push(d.message()); d.dismiss(); });
  await p.goto(U); await p.waitForTimeout(400);
  await p.check('input[name=tmplMode][value=builtin]'); await p.selectOption('#outputFormat', 'csv');
  await p.check('#rm-enabled').catch(() => {});
  const files = ['junk.xlsx', 'empty.csv', 'fake.xlsx', 'wide.csv', 'xss.csv', 'quote.csv', 'ragged.csv', 'bom.csv'];
  const SI = [2, 3, 6, 9, 17, 22, 30], PR = [2, 4, 5, 9, 13, 22, 31];
  for (const [sel, inp, st, ids] of [['#rm-si', '#rm-siFile', '#rm-siStatus', SI], ['#rm-prem', '#rm-premFile', '#rm-premStatus', PR]]) {
    for (const id of ids) {
      await p.selectOption(sel, String(id));
      for (const f of files) {
        const before = errs.length;
        await p.setInputFiles(inp, D + f); await p.waitForTimeout(150);
        const m = ((await p.textContent(st)) || '').trim();
        if (errs.length > before || !m) console.log('PROBLEM', sel, id, f, errs.slice(before), JSON.stringify(m));
      }
    }
  }
  // then generate with whatever state the garbage left behind
  await p.fill('#numEmployees', '200'); await p.click('#generateBtn');
  await p.waitForSelector('#resultsWrap:not([hidden])', { timeout: 60000 }).catch(() => console.log('generate after garbage: no results; dialogs', dialogs));
  console.log('rows', await p.textContent('#statRows'), 'dialogs', JSON.stringify(dialogs), 'errors', JSON.stringify(errs));
  await b.close();
})();
