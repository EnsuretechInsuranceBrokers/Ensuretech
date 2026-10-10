# Browser QA scripts for the standalone page

Playwright scripts (offline, headless Chromium) that exercise `static/index.html`:

- `hostile.js` / `hostile2.js` – garbage uploads (random bytes, empty, HTML renamed .xlsx, 3000 columns, ragged/unterminated CSV, markup in cells), extreme numbers, double-click Generate, and a Generate after bad uploads. Expect zero page errors, a message for every bad file, and no script execution from cell contents.
- `load.js` – 200k-family CSV, 30k-family XLSX, cancel mid-run, REMOVE/UPDATE; reports the worst frame gap.

Create `fixtures/junk.xlsx` first: `head -c 5000 /dev/urandom > qa/fixtures/junk.xlsx`. Run with `node qa/hostile.js` (Playwright must be resolvable; adjust the require path).
