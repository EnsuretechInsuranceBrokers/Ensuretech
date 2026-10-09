/* The live endorsement grid.
 *
 * What the person sees: a sheet they can type or paste into, a mark on every row, red on the cell that is wrong with the reason on hover and below,
 * and a panel that fills in as they go (how many employees of how many, the mistakes grouped by kind, ages, sums insured, family sizes).
 * What happens behind it: on every pause in typing the WHOLE grid goes to the endorsement engine, which runs the same rules the million-row file
 * path runs, in one vectorised pass, and sends back a verdict per row and a pandas summary. This file only draws it; it holds no rules.
 * Nothing is saved until Submit.
 */
(function () {
  "use strict";
  var app = document.getElementById("grid-app"); if (!app) return;
  var ID = app.dataset.id, OP = app.dataset.op, COUNT = +app.dataset.count, CAP = +app.dataset.cap, EFF = app.dataset.eff || "";
  var schema = JSON.parse(document.getElementById("grid-schema").textContent);
  var byField = {}; schema.columns.forEach(function (c) { byField[c.field] = c; });
  var SHOW = { ADD: ["employee_code", "relation", "first_name", "last_name", "dob", "gender", "sum_insured", "pan", "mobile", "email", "grade", "designation", "annual_salary"].concat(EFF ? [] : ["effective_date"]),
    REMOVE: ["employee_code", "relation"].concat(EFF ? [] : ["effective_date"]),
    UPDATE: ["employee_code", "relation", "first_name", "last_name", "dob", "gender", "pan", "mobile", "email", "grade", "designation", "annual_salary", "sum_insured"] }[OP];
  var COLS = SHOW.filter(function (f) { return byField[f]; });
  var PLACEHOLDER = { dob: "dd/mm/yyyy", effective_date: "dd/mm/yyyy", sum_insured: "5,00,000", pan: "ABCDE1234F", mobile: "10 digits", gender: "M / F" };
  var rows = [], seq = 0, verdict = {}, pending = null, inflight = 0, dirty = false, lastSummary = null, lastChecks = null, valid = false;
  var thead = app.querySelector("thead"), tbody = app.querySelector("tbody"), side = document.getElementById("side"), verdictEl = document.getElementById("verdict"), submit = document.getElementById("submit"), saving = document.getElementById("saving");
  var KEY = "ensuretech-grid-" + ID;

  function el(tag, attrs) { var e = document.createElement(tag); Object.keys(attrs || {}).forEach(function (k) { if (k === "text") e.textContent = attrs[k]; else if (k === "class") e.className = attrs[k]; else e.setAttribute(k, attrs[k]); });
    for (var i = 2; i < arguments.length; i++) { var c = arguments[i]; if (c === null || c === undefined) continue; e.append(c.nodeType ? c : document.createTextNode(String(c))); } return e; }
  function inr(n) { var s = Math.round(n).toString(), last3 = s.slice(-3), rest = s.slice(0, -3); if (rest) rest = rest.replace(/\B(?=(\d{2})+(?!\d))/g, ","); return "₹" + (rest ? rest + "," : "") + last3; }
  function blankRow(rel) { var cells = {}; if (rel) cells.relation = rel; return { id: "r" + (++seq), cells: cells }; }
  /* a row with nothing but the pre-filled relation is still an empty row: it is not checked, not counted, not sent */
  function isBlank(r) { return COLS.every(function (f) { var v = r.cells[f]; return f === "relation" || v === undefined || String(v).trim() === ""; }); }

  /* ── drawing the table ─────────────────────────────────────────────────────────────────────────────────────────────────── */
  function head() {
    var tr = el("tr", null, el("th", { class: "idx", text: "#" }), el("th", { class: "state", text: "" }));
    COLS.forEach(function (f) { var c = byField[f], req = (c.required_for || []).indexOf(OP) >= 0; tr.append(el("th", { class: "c-" + f }, c.label, req ? el("span", { class: "req", title: "required", text: " *" }) : null)); });
    tr.append(el("th", { class: "why", text: "What is wrong" }), el("th", { class: "act", text: "" }));
    thead.textContent = ""; thead.append(tr);
  }
  function cell(r, f) {
    var c = byField[f], v = r.cells[f] === undefined ? "" : r.cells[f], input;
    if (f === "relation") { input = el("select", { "aria-label": "Relation" }); input.append(el("option", { value: "", text: "" })); schema.relations.forEach(function (x) { var o = el("option", { value: x, text: x.replace(/_/g, " ").toLowerCase() }); input.append(o); }); var cur = String(v).toUpperCase().replace(/[^A-Z0-9]+/g, "_");
      if (v && schema.relations.indexOf(cur) < 0) { input.append(el("option", { value: v, text: String(v) })); input.value = v; } else input.value = schema.relations.indexOf(cur) >= 0 ? cur : ""; }
    else input = el("input", { type: "text", value: v, autocomplete: "off", spellcheck: "false", placeholder: PLACEHOLDER[f] || "", "aria-label": c.label, inputmode: c.type === "money" ? "decimal" : null });
    input.dataset.f = f; input.dataset.r = r.id; return el("td", { class: "c-" + f }, input);
  }
  function drawRow(r, i) {
    var tr = el("tr", { "data-r": r.id }, el("td", { class: "idx", text: String(i + 1) }), el("td", { class: "state" }));
    COLS.forEach(function (f) { tr.append(cell(r, f)); });
    tr.append(el("td", { class: "why" }), el("td", { class: "act" },
      OP === "ADD" ? el("button", { type: "button", class: "mini", title: "Add a dependant under this employee", "data-do": "dep", text: "+ dependant" }) : null, el("button", { type: "button", class: "mini x", title: "Remove this row", "data-do": "del", "aria-label": "Remove row", text: "×" })));
    return tr;
  }
  function drawAll() { tbody.textContent = ""; rows.forEach(function (r, i) { tbody.append(drawRow(r, i)); }); paint(); }
  function number() { Array.prototype.forEach.call(tbody.children, function (tr, i) { tr.firstChild.textContent = i + 1; }); }

  /* ── painting a verdict ────────────────────────────────────────────────────────────────────────────────────────────────── */
  function paint() {
    Array.prototype.forEach.call(tbody.children, function (tr, i) {
      var r = rows[i], v = verdict[r.id] || null, issues = v ? v.issues : [];
      tr.classList.toggle("blank", !!(v && v.blank));
      Array.prototype.forEach.call(tr.querySelectorAll("td[class^=c-]"), function (td) { td.classList.remove("bad", "warn"); td.removeAttribute("title"); });
      var why = [];
      issues.forEach(function (x) {
        var td = tr.querySelector("td.c-" + (x.field === "relation_code" ? "relation" : x.field));
        if (td) { td.classList.add(x.severity === "ERROR" ? "bad" : "warn"); td.title = (td.title ? td.title + "\n" : "") + x.message; }
        why.push(x);
      });
      var st = tr.querySelector("td.state"), wy = tr.querySelector("td.why"); wy.textContent = ""; st.textContent = "";
      if (v && !v.blank) { var bad = issues.some(function (x) { return x.severity === "ERROR"; }); st.append(el("span", { class: "mark " + (bad ? "no" : issues.length ? "maybe" : "yes"), text: bad ? "✕" : issues.length ? "!" : "✓", title: bad ? "needs fixing" : issues.length ? "check this" : "good", role: "img", "aria-label": bad ? "needs fixing" : issues.length ? "check this" : "good" })); }
      else if (inflight && !isBlank(r)) st.append(el("span", { class: "mark wait", text: "…", "aria-label": "checking" }));
      why.slice(0, 3).forEach(function (x) { wy.append(el("div", { class: "w " + x.severity.toLowerCase() }, el("code", { text: x.rule }), " ", x.message)); });
      if (why.length > 3) wy.append(el("div", { class: "w more", text: "+" + (why.length - 3) + " more" }));
    });
  }

  var issuesEl = document.createElement("ul"); issuesEl.className = "issues"; document.querySelector(".gridwrap").after(issuesEl);
  function drawIssues() {
    issuesEl.textContent = ""; var n = 0, total = 0;
    rows.forEach(function (r, i) { var v = verdict[r.id]; if (!v || v.blank) return; v.issues.forEach(function (x) { if (x.severity !== "ERROR") return; total++; if (n >= 8) return; n++;
      var fld = x.field === "relation_code" ? "relation" : x.field, label = (byField[fld] || { label: fld || "Whole row" }).label;
      var a = el("a", { href: "#", text: "Row " + (i + 1) + " · " + label }); a.addEventListener("click", function (e) { e.preventDefault(); focusCell(i, byField[fld] ? fld : COLS[0]); });
      issuesEl.append(el("li", null, el("span", { class: "sev-error", text: "✕" }), a, el("span", { text: x.message }))); }); });
    if (total > n) issuesEl.append(el("li", { class: "more", text: "and " + (total - n) + " more" }));
  }

  /* ── the side panel (everything here is the engine's pandas summary) ──────────────────────────────────────────────────── */
  function bar(label, n, max, cls) { return el("div", { class: "gbar" }, el("span", { class: "bl", text: label }), el("span", { class: "bt" }, el("i", { class: cls || "", style: "width:" + (max ? Math.max(3, n / max * 100) : 0) + "%" })), el("span", { class: "bn num", text: String(n) })); }
  function drawSide() {
    var s = lastSummary; side.textContent = "";
    if (!s) { side.append(el("div", { class: "card" }, el("h2", { text: "Waiting for the first row" }), el("p", { class: "mut", text: "As you type, this fills in." }))); return; }
    var ee = s.employees_entered, ex = s.expected_employees || COUNT, pct = Math.min(100, Math.round(ee / ex * 100));
    side.append(el("div", { class: "card" }, el("h2", { text: "Progress" }), el("div", { class: "big num" }, String(ee), el("span", { class: "of", text: " of " + ex + " employees" })), el("div", { class: "meter" }, el("i", { style: "width:" + pct + "%" })),
      el("div", { class: "chips" }, el("span", { class: "pill ok", text: s.rows_clean + " good" }), el("span", { class: "pill warn", text: s.rows_with_warnings + " to check" }), el("span", { class: "pill err", text: s.rows_with_errors + " to fix" }), el("span", { class: "pill idle", text: s.rows_blank + " empty" }))));
    if (ee > ex) side.append(el("div", { class: "note warn", text: "You have entered " + ee + " employees but said " + ex + ". That is fine, but check you have not pasted extra rows." }));
    if (lastChecks && lastChecks.family_rules === false && OP === "ADD") side.append(el("div", { class: "note warn", text: "The family rules could not be checked just now. Everything else was. They run again when you submit." }));
    if (s.by_rule && s.by_rule.length) { var c = el("div", { class: "card" }, el("h2", { text: "Mistakes by kind" })); var max = s.by_rule[0].count; s.by_rule.slice(0, 6).forEach(function (x) { c.append(bar(x.rule.replace(/_/g, " ").toLowerCase(), x.count, max, x.severity === "ERROR" ? "e" : "w")); }); side.append(c); }
    if (s.completeness && Object.keys(s.completeness).length) { var cc = el("div", { class: "card" }, el("h2", { text: "Filled in" })); Object.keys(s.completeness).forEach(function (k) { cc.append(bar((byField[k === "relation_code" ? "relation" : k] || { label: k }).label, s.completeness[k], 100, s.completeness[k] === 100 ? "" : "w")); }); side.append(cc); }
    if (s.relations && Object.keys(s.relations).length > 1) { var rc = el("div", { class: "card" }, el("h2", { text: "Who is on the sheet" })), rm = Math.max.apply(null, Object.values(s.relations)); Object.keys(s.relations).forEach(function (k) { rc.append(bar(k.replace(/_/g, " ").toLowerCase(), s.relations[k], rm)); }); side.append(rc); }
    if (s.ages) { var ac = el("div", { class: "card" }, el("h2", { text: "Ages on the day cover starts" }), el("p", { class: "mut", text: "youngest " + s.ages.min + " · median " + s.ages.median + " · oldest " + s.ages.max })), am = Math.max.apply(null, Object.values(s.ages_by_band)); Object.keys(s.ages_by_band).forEach(function (k) { ac.append(bar(k, s.ages_by_band[k], am)); }); side.append(ac); }
    if (s.sum_insured) side.append(el("div", { class: "card" }, el("h2", { text: "Sum insured" }), el("div", { class: "big num", text: inr(s.sum_insured.total) }), el("p", { class: "mut", text: "across " + s.sum_insured.count + " people · average " + inr(s.sum_insured.mean) + " · " + inr(s.sum_insured.min) + " to " + inr(s.sum_insured.max) })));
    if (s.family_sizes && Object.keys(s.family_sizes).length > 1) { var fc = el("div", { class: "card" }, el("h2", { text: "Family sizes" })), fm = Math.max.apply(null, Object.values(s.family_sizes)); Object.keys(s.family_sizes).forEach(function (k) { fc.append(bar(k + (k === "1" ? " person" : " people"), s.family_sizes[k], fm)); }); side.append(fc); }
  }
  function setVerdict() {
    var s = lastSummary, typed = rows.filter(function (r) { return !isBlank(r); }).length;
    valid = !!s && !inflight && !dirty && s.rows_typed > 0 && s.rows_with_errors === 0 && typed === s.rows_typed;
    submit.disabled = !valid;
    verdictEl.className = valid ? "ok" : "";
    verdictEl.textContent = !typed ? "Nothing entered yet." : (dirty || inflight) ? "Checking…" : s && s.rows_with_errors ? s.rows_with_errors + " row" + (s.rows_with_errors === 1 ? "" : "s") + " to fix before you can submit." : s ? "All " + s.rows_typed + " rows pass. Ready to submit." : "";
  }

  /* ── talking to the engine ─────────────────────────────────────────────────────────────────────────────────────────────── */
  function payload() { return rows.map(function (r) { var o = { row_key: r.id }; if (isBlank(r)) return o; COLS.forEach(function (f) { var v = r.cells[f]; if (v !== undefined && String(v).trim() !== "") o[f] = String(v).trim(); }); return o; }); }
  function post(url, body) { return fetch(url, { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json", "Accept": "application/json", "X-CSRF-TOKEN": app.dataset.token }, body: JSON.stringify(body) }); }
  var seqCall = 0;
  function check() {
    var mine = ++seqCall; inflight++; dirty = false; setVerdict();
    post("/app/endorse/grid/" + ID + "/validate", { op_type: OP, rows: payload() }).then(function (res) { return res.json().then(function (j) { return [res.status, j]; }); }).then(function (a) {
      inflight--; if (mine !== seqCall) { setVerdict(); return; }                      // a newer check is on its way: this one is stale
      if (a[0] === 200) { verdict = {}; a[1].rows.forEach(function (x, i) { verdict[rows[i] ? rows[i].id : x.row_key] = x; }); lastSummary = a[1].summary; lastChecks = a[1].checks; saving.textContent = ""; }
      else saving.textContent = a[0] === 429 ? "Slow down a moment…" : (a[1] && a[1].detail) || "Could not check just now. Your typing is kept.";
      paint(); drawIssues(); drawSide(); setVerdict();
    }).catch(function () { inflight--; saving.textContent = "Offline? Your typing is kept here."; setVerdict(); });
  }
  function later() { dirty = true; setVerdict(); clearTimeout(pending); pending = setTimeout(check, 350); stash(); }
  function stash() { try { sessionStorage.setItem(KEY, JSON.stringify(rows)); } catch (e) {} }

  /* ── editing ───────────────────────────────────────────────────────────────────────────────────────────────────────────── */
  function rowOf(t) { var id = t.dataset.r; for (var i = 0; i < rows.length; i++) if (rows[i].id === id) return i; return -1; }
  tbody.addEventListener("input", function (e) { var t = e.target; if (!t.dataset.f) return; var i = rowOf(t); if (i < 0) return; rows[i].cells[t.dataset.f] = t.value; later(); });
  tbody.addEventListener("change", function (e) { var t = e.target; if (t.tagName === "SELECT" && t.dataset.f) { var i = rowOf(t); if (i >= 0) { rows[i].cells[t.dataset.f] = t.value; later(); } } });
  tbody.addEventListener("click", function (e) {
    var b = e.target.closest("button[data-do]"); if (!b) return; var tr = b.closest("tr"), i = Array.prototype.indexOf.call(tbody.children, tr);
    if (b.dataset.do === "del") { rows.splice(i, 1); if (!rows.length) rows.push(blankRow(OP === "ADD" ? "SELF" : "")); drawAll(); later(); }
    else if (b.dataset.do === "dep") { if (rows.length >= CAP) return flash("The grid holds " + CAP + " rows for " + COUNT + " employees."); var n = blankRow(""); n.cells.employee_code = rows[i].cells.employee_code || ""; rows.splice(i + 1, 0, n); drawAll(); focusCell(i + 1, "relation"); later(); }
  });
  function focusCell(i, f) { var tr = tbody.children[i]; if (!tr) return; var x = tr.querySelector("[data-f=" + f + "]"); if (x) x.focus(); }
  function flash(msg) { saving.textContent = msg; setTimeout(function () { if (saving.textContent === msg) saving.textContent = ""; }, 4000); }

  /* paste from Excel: tab-separated cells and newline-separated rows fill the grid from the cell you pasted into */
  tbody.addEventListener("paste", function (e) {
    var t = e.target; if (!t.dataset || !t.dataset.f) return; var text = (e.clipboardData || window.clipboardData).getData("text"); if (!/[\t\n]/.test(text.replace(/[\r\n]+$/, ""))) return;
    e.preventDefault(); var lines = text.replace(/\r/g, "").replace(/\n+$/, "").split("\n").map(function (l) { return l.split("\t"); }), i0 = rowOf(t), c0 = COLS.indexOf(t.dataset.f);
    if (i0 + lines.length > CAP) { flash("That is " + lines.length + " rows; the grid holds " + CAP + ". Use the spreadsheet for more."); lines = lines.slice(0, CAP - i0); }
    lines.forEach(function (cells, li) { var ri = i0 + li; while (rows.length <= ri) rows.push(blankRow(""));
      cells.forEach(function (v, ci) { var f = COLS[c0 + ci]; if (f) rows[ri].cells[f] = v.trim(); }); });
    drawAll(); later();
  });
  /* Enter / arrows move like a spreadsheet */
  tbody.addEventListener("keydown", function (e) {
    var t = e.target; if (!t.dataset || !t.dataset.f) return; var i = rowOf(t);
    if (e.key === "Enter" && t.tagName !== "SELECT") { e.preventDefault(); if (i === rows.length - 1 && rows.length < CAP) { rows.push(blankRow(OP === "ADD" ? "SELF" : "")); drawAll(); later(); } focusCell(Math.min(i + 1, rows.length - 1), t.dataset.f); }
    else if (e.key === "ArrowDown" && t.tagName !== "SELECT") { e.preventDefault(); focusCell(Math.min(i + 1, rows.length - 1), t.dataset.f); }
    else if (e.key === "ArrowUp" && t.tagName !== "SELECT") { e.preventDefault(); focusCell(Math.max(i - 1, 0), t.dataset.f); }
  });
  document.getElementById("add-row").addEventListener("click", function () { if (rows.length >= CAP) return flash("The grid holds " + CAP + " rows."); rows.push(blankRow(OP === "ADD" ? "SELF" : "")); drawAll(); focusCell(rows.length - 1, COLS[0]); later(); });
  document.getElementById("add-five").addEventListener("click", function () { for (var k = 0; k < 5 && rows.length < CAP; k++) rows.push(blankRow(OP === "ADD" ? "SELF" : "")); drawAll(); later(); });
  document.getElementById("clear-blank").addEventListener("click", function () { var keep = rows.filter(function (r) { return !isBlank(r); }); rows = keep.length ? keep : [blankRow(OP === "ADD" ? "SELF" : "")]; drawAll(); later(); });

  /* ── submit ───────────────────────────────────────────────────────────────────────────────────────────────────────────── */
  submit.addEventListener("click", function () {
    if (!valid) return; submit.disabled = true; verdictEl.textContent = "Submitting…";
    post("/app/endorse/grid/" + ID + "/submit", { op_type: OP, rows: payload() }).then(function (res) { return res.json().then(function (j) { return [res.status, j]; }); }).then(function (a) {
      if (a[0] === 200 && a[1].redirect) { try { sessionStorage.removeItem(KEY); } catch (e) {} location.href = a[1].redirect; return; }
      verdictEl.textContent = (a[1] && a[1].detail) || "Could not submit. Nothing was lost."; if (a[1] && a[1].result) { lastSummary = a[1].result.summary; a[1].result.rows.forEach(function (x, i) { verdict[rows[i].id] = x; }); paint(); drawIssues(); drawSide(); } submit.disabled = false;
    }).catch(function () { verdictEl.textContent = "Could not reach the server. Nothing was lost."; submit.disabled = false; });
  });
  window.addEventListener("beforeunload", function (e) { if (!submit.disabled && valid) { e.preventDefault(); e.returnValue = ""; } });

  /* ── start ─────────────────────────────────────────────────────────────────────────────────────────────────────────────── */
  var restored = null; try { restored = JSON.parse(sessionStorage.getItem(KEY) || "null"); } catch (e) {}
  if (restored && Array.isArray(restored) && restored.length) { rows = restored.map(function (r) { return { id: "r" + (++seq), cells: r.cells || {} }; }); }
  else for (var k = 0; k < Math.min(+app.dataset.initial || 1, CAP); k++) rows.push(blankRow(OP === "ADD" ? "SELF" : ""));
  head(); drawAll(); drawSide(); setVerdict(); if (restored) check();
})();
