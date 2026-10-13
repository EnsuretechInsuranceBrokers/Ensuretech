(() => {
const $ = id => document.getElementById(id);
const screen = $('screen'), cap = $('cap'), fill = $('fill'), track = $('track'), playBtn = $('play'), timeEl = $('time'), urlEl = $('url'), chips = $('chips');
const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
const seg = (p, a, b) => clamp((p - a) / (b - a));           // progress inside [a,b]
const typed = (s, p) => s.slice(0, Math.round(s.length * p));
const fmt = ms => { const s = Math.floor(ms / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };

const NAMES = [['E10231','Anita Rao','Spouse','ADD'],['E10232','Rahul Menon','Child','ADD'],['E10233','Sana Qureshi','Employee','ADD'],['E10234','Vikram Shah','Parent','ADD'],['E10235','Divya Nair','Employee','ADD'],['E10236','Imran Khan','Spouse','ADD']];
const BAD = {1: 'DOB is after the policy start date', 3: 'MEMBER_ALREADY_EXISTS'};

const scenes = [
 { ms: 6000, url: 'portal.ensuretech.example/login', cap: 'A broker administrator signs in. The portal talks only to its own back end; every call is signed before it reaches the core.',
   html: `<div class="box" style="max-width:380px;margin:1.2rem auto"><p class="s-h">Sign in</p>
     <label class="field"><span>Email</span><div class="inp" data-k="email"></div></label>
     <label class="field"><span>Password</span><div class="inp" data-k="pw"></div></label>
     <div style="margin-top:.8rem"><span class="pbtn" data-k="btn">Sign in</span></div></div>`,
   draw(el, p) { const e = el.querySelector('[data-k=email]'), w = el.querySelector('[data-k=pw]');
     const pe = seg(p, .08, .5), pw = seg(p, .5, .8);
     e.textContent = typed('meera.kapoor@northwind.example', pe); w.textContent = '•'.repeat(Math.round(14 * pw));
     e.classList.toggle('cur', pe > 0 && pe < 1); w.classList.toggle('cur', pw > 0 && pw < 1);
     el.querySelector('[data-k=btn]').classList.toggle('press', p > .88 && p < .96); } },

 { ms: 6000, url: 'portal.ensuretech.example/app', cap: 'The dashboard shows employers, policies and endorsements in flight for this broker.',
   html: `<p class="s-h">Northwind Benefits Brokers</p><div class="row">
     <div class="box kpi"><b data-n="2">0</b><span>Employers</span></div><div class="box kpi"><b data-n="6">0</b><span>Policies</span></div>
     <div class="box kpi"><b data-n="1">0</b><span>Endorsements in flight</span></div><div class="box kpi"><b data-n="14820">0</b><span>Covered members</span></div></div>
     <div class="box" style="margin-top:1rem"><b>Recent activity</b><p style="color:var(--mut);margin:.4rem 0 0;font-size:.88rem">Add-members sheet for Orchid Textiles was endorsed yesterday. 2 announcements unread.</p></div>`,
   draw(el, p) { el.querySelectorAll('[data-n]').forEach(n => { n.textContent = Math.round(+n.dataset.n * seg(p, .1, .6)).toLocaleString('en-IN') }) } },

 { ms: 7000, url: 'portal.ensuretech.example/app/intake', cap: 'Pick the employer, then one of its policies. The policy decides the rules, the TPA and the premium basis.',
   html: `<p class="s-h">New endorsement</p>
     <label class="field"><span>Employer</span><div class="inp" data-k="emp"></div></label>
     <label class="field" style="margin-top:3.6rem"><span>Policy</span><div class="inp" data-k="pol"></div></label>
     <div class="box tag" data-k="info" style="max-width:420px;opacity:0">Group Mediclaim, 1 Apr 2026 to 31 Mar 2027, TPA 3, floater</div>`,
   draw(el, p) { const emp = el.querySelector('[data-k=emp]'), pol = el.querySelector('[data-k=pol]');
     const openE = p > .1 && p < .35, openP = p > .5 && p < .75;
     emp.textContent = p < .3 ? 'Select an employer' : 'Orchid Textiles Pvt Ltd';
     pol.textContent = p < .7 ? 'Select a policy' : 'GMC-2026-0042 (live)';
     emp.querySelector('.menu')?.remove(); pol.querySelector('.menu')?.remove();
     const menu = (items, hl) => { const m = document.createElement('div'); m.className = 'menu'; m.innerHTML = items.map((x, i) => `<div class="${i === hl ? 'hl' : ''}">${x}</div>`).join(''); return m };
     if (openE) emp.appendChild(menu(['Orchid Textiles Pvt Ltd', 'Harbor Logistics LLP'], p > .24 ? 0 : -1));
     if (openP) pol.appendChild(menu(['GMC-2026-0042 (live)', 'GMC-2026-0043 (pending)'], p > .64 ? 0 : -1));
     el.querySelector('[data-k=info]').style.opacity = seg(p, .78, .9) } },

 { ms: 8000, url: 'portal.ensuretech.example/app/intake/upload', cap: 'Drop the member sheet, CSV or Excel. One thousand rows.',
   html: `<p class="s-h">Upload member sheet</p><div class="drop" data-k="drop"><div style="font-size:1.1rem" data-k="t">Drop a CSV or Excel file here</div></div>
     <div class="box" style="margin-top:1rem;opacity:0" data-k="file"><b>add_members_1000.xlsx</b> <span class="tag">1,000 rows, 106 columns</span><div class="prog"><div data-k="pg"></div></div><span class="tag" data-k="pt">Uploading</span></div>`,
   draw(el, p) { const hot = p > .15 && p < .35; el.querySelector('[data-k=drop]').classList.toggle('hot', hot);
     el.querySelector('[data-k=t]').textContent = hot ? 'Release to upload' : 'Drop a CSV or Excel file here';
     const f = el.querySelector('[data-k=file]'); f.style.opacity = seg(p, .35, .45);
     const u = seg(p, .45, .85); el.querySelector('[data-k=pg]').style.width = u * 100 + '%';
     el.querySelector('[data-k=pt]').textContent = u < 1 ? 'Uploading ' + Math.round(u * 100) + '%' : 'Uploaded. Validation has started.' } },

 { ms: 10000, url: 'portal.ensuretech.example/app/jobs/812', cap: 'The engine validates every row in parallel: format rules, duplicates, dates, policy limits. Here, a mixed sheet.',
   html: `<p class="s-h">Endorsement job 812</p><div class="steps" data-k="steps"></div>
     <div class="row"><div class="box kpi"><div class="big ok" data-k="v">0</div><span>valid rows</span></div><div class="box kpi"><div class="big bad" data-k="i">0</div><span>rows to fix</span></div><div class="box kpi"><div class="big" data-k="t">0.0 s</div><span>since upload</span></div></div>
     <div class="box" style="margin-top:1rem"><div class="tag" style="margin:0 0 .4rem">Legacy system, same shape of sheet: more than 3 minutes</div><div class="prog"><div style="width:100%;background:#94a3b8"></div></div><div class="tag" style="margin:0">This system</div><div class="prog"><div data-k="pn"></div></div></div>`,
   draw(el, p) { const labels = ['Uploaded', 'Validating', 'Checked', 'Ready to proceed']; const q = seg(p, .1, .8);
     const stage = q < .02 ? 0 : q < .9 ? 1 : q < 1 ? 2 : 3; const done = p > .85 ? 4 : stage;
     el.querySelector('[data-k=steps]').innerHTML = labels.map((l, i) => `<span class="st ${i < done ? 'done' : i === done ? 'now' : ''}">${l}</span>`).join('');
     el.querySelector('[data-k=v]').textContent = Math.round(996 * q); el.querySelector('[data-k=i]').textContent = Math.round(4 * q);
     el.querySelector('[data-k=t]').textContent = (1.1 * q).toFixed(1) + ' s'; el.querySelector('[data-k=pn]').style.width = (0.6 * q) + '%'; } },

 { ms: 8000, url: 'portal.ensuretech.example/app/jobs/812/result', cap: 'The result sheet has the same columns as the legacy ADD sheet plus Status and Reason, one row per uploaded row, so it drops into existing workflows.',
   html: `<p class="s-h">Result sheet <span class="tag">Worksheet, 106 legacy columns + Status + Reason (6 of 1,000 rows shown)</span></p>
     <table class="t"><thead><tr><th>Employee code</th><th>Name</th><th>Relation</th><th>Action</th><th>Status</th><th>Reason</th></tr></thead><tbody data-k="rows"></tbody></table>
     <div style="margin-top:1rem"><span class="pbtn" data-k="dl">Download result sheet</span> <span class="tag" data-k="dd" style="margin-left:.6rem"></span></div>`,
   draw(el, p) { const n = Math.round(NAMES.length * seg(p, .05, .55));
     el.querySelector('[data-k=rows]').innerHTML = NAMES.slice(0, n).map((r, i) => `<tr class="${BAD[i] ? 'f' : ''}"><td>${r[0]}</td><td>${r[1]}</td><td>${r[2]}</td><td>${r[3]}</td><td>${BAD[i] ? 'Failed' : 'Passed'}</td><td>${BAD[i] || ''}</td></tr>`).join('');
     el.querySelector('[data-k=dl]').classList.toggle('press', p > .72 && p < .8);
     el.querySelector('[data-k=dd]').textContent = p > .8 ? 'Downloaded in 0.3 s' : '' } },

 { ms: 8000, url: 'portal.ensuretech.example/app/jobs/812', cap: 'Fix the four rows and re-upload, or proceed with the valid ones. Premium and balance are checked before anything reaches the insurer.',
   html: `<p class="s-h">Proceed with 996 valid rows</p><div class="row"><div class="box kpi"><b>₹ 4,82,310</b><span>pro-rata premium</span></div><div class="box kpi"><b>₹ 6,10,000</b><span>available balance</span></div><div class="box kpi"><b class="ok" data-k="chk">...</b><span>balance check</span></div></div>
     <div style="margin:1rem 0"><span class="pbtn" data-k="go">Proceed</span></div><div class="steps" data-k="steps"></div>`,
   draw(el, p) { el.querySelector('[data-k=chk]').textContent = p > .2 ? 'Sufficient' : '...';
     el.querySelector('[data-k=go]').classList.toggle('press', p > .38 && p < .46);
     const labels = ['Endorsement created', 'Sent to TPA', 'Insurer confirmed', 'Shared with employer']; const k = p < .46 ? 0 : 1 + Math.floor(seg(p, .46, .95) * 3.99);
     el.querySelector('[data-k=steps]').innerHTML = p < .46 ? '' : labels.map((l, i) => `<span class="st ${i < k ? 'done' : i === k ? 'now' : ''}">${l}</span>`).join(''); } },

 { ms: 9000, url: 'ensuretech.example/benchmarks', cap: 'Measured on a 4 vCPU build machine; the legacy figures are from a different 8 vCPU server, so treat the comparison as indicative, not exact.',
   html: `<p class="s-h">Same kind of sheet, two systems</p><div data-k="c"></div><p class="tag">Legacy: your production timings. This system: build-machine measurements, upload to sheet ready. Hardware differs; the like-for-like protocol is in the load-test notes.</p>`,
   draw(el, p) { const rows = [['1000 valid, legacy', 232, 232, 'lg', '3 min 52 s'], ['1000 valid, new', 1.0, 232, 'nw', '1.0 s'], ['996 + 4 failed, legacy', 190, 232, 'lg', '3 min+'], ['996 + 4 failed, new', 1.3, 232, 'nw', '1.3 s'], ['All invalid, legacy', 60, 232, 'lg', '~1 min'], ['All invalid, new', 1.0, 232, 'nw', '1.0 s']];
     const g = seg(p, .1, .7);
     el.querySelector('[data-k=c]').innerHTML = rows.map(r => `<div class="cmp"><span>${r[0]}</span><div><div class="b ${r[3]}" style="width:${Math.max(0.6, r[1] / r[2] * 100 * g)}%"></div></div><b>${g > .6 ? r[4] : ''}</b></div>`).join(''); } },
];

scenes.forEach((s, i) => { s.start = scenes.slice(0, i).reduce((a, x) => a + x.ms, 0); const d = document.createElement('div'); d.className = 'scene'; d.innerHTML = s.html; screen.appendChild(d); s.el = d;
  const b = document.createElement('button'); b.textContent = (i + 1) + '. ' + ['Sign in', 'Dashboard', 'Choose policy', 'Upload', 'Validate', 'Result sheet', 'Proceed', 'Compare'][i]; b.onclick = () => seek(s.start); chips.appendChild(b); s.chip = b; });
const TOTAL = scenes.reduce((a, s) => a + s.ms, 0);
let t = 0, playing = !matchMedia('(prefers-reduced-motion:reduce)').matches, last = null, cur = -1;

function render() {
  let i = scenes.findIndex(s => t < s.start + s.ms); if (i < 0) i = scenes.length - 1;
  if (i !== cur) { scenes.forEach((s, k) => { s.el.classList.toggle('on', k === i); s.chip.classList.toggle('on', k === i) }); urlEl.textContent = scenes[i].url; cap.textContent = scenes[i].cap; cur = i; }
  scenes[i].draw(scenes[i].el, clamp((t - scenes[i].start) / scenes[i].ms));
  fill.style.width = t / TOTAL * 100 + '%'; timeEl.textContent = fmt(t); playBtn.textContent = playing ? 'Pause' : (t >= TOTAL ? 'Replay' : 'Play');
}
function seek(ms) { t = clamp(ms, 0, TOTAL); render(); }
function tick(now) { if (playing) { if (last != null) t += now - last; if (t >= TOTAL) { t = TOTAL; playing = false } render(); } last = now; requestAnimationFrame(tick) }
playBtn.onclick = () => { if (t >= TOTAL) t = 0; playing = !playing; render() };
track.onclick = e => { const r = track.getBoundingClientRect(); seek((e.clientX - r.left) / r.width * TOTAL) };
addEventListener('keydown', e => { if (e.key === ' ') { e.preventDefault(); playBtn.click() } else if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') { const d = e.key === 'ArrowRight' ? 1 : -1; const i = Math.max(0, cur + d); seek(i >= scenes.length ? TOTAL : scenes[i].start) } });
render(); requestAnimationFrame(tick);
})();
