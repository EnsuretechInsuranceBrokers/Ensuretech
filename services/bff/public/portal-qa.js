(function () {
  "use strict";
  var app = document.getElementById("qa"), token = app.dataset.token, rows = document.getElementById("results"), note = document.getElementById("note");
  var NAMES = { valid: "All rows valid", mixed: "Some rows invalid", invalid: "All rows invalid" };
  var TERMINAL = /^(ENDORSED|VALIDATION_FAILED|CANCELLED.*|REJECTED.*|.*_FAILED|CD_INSUFFICIENT|CD_PARTIAL_SUFFICIENT)$/, HELD_AFTER_MS = 3000;
  function $(id) { return document.getElementById(id); }
  function say(t) { note.textContent = t; }
  function sec(ms) { return (ms / 1000).toFixed(2) + " s"; }
  function post(url, body) {
    return fetch(url, { method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json", "Accept": "application/json", "X-CSRF-TOKEN": token }, body: JSON.stringify(body) })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, status: r.status, body: j }; }); });
  }
  function get(url) { return fetch(url, { credentials: "same-origin", headers: { "Accept": "application/json" } }).then(function (r) { return r.json(); }); }
  function params(scenario) { return { scenario: scenario, rows: +$("rows").value || 1000, invalid_rows: +$("invalid").value || 4 }; }
  function cell(tr, text) { var td = document.createElement("td"); td.textContent = text; tr.appendChild(td); return td; }
  function newRow(scenario, n, inv) {
    if (rows.querySelector(".empty")) { rows.textContent = ""; }
    var tr = document.createElement("tr");
    var label = NAMES[scenario] + (scenario === "mixed" ? " (" + inv + " of " + n + ")" : "");
    cell(tr, label); cell(tr, String(n)); for (var i = 0; i < 5; i++) { cell(tr, "…"); } cell(tr, "");
    rows.insertBefore(tr, rows.firstChild);
    return tr;
  }
  function link(td, text, href, attrs) { var a = document.createElement("a"); a.textContent = text; a.href = href; for (var k in (attrs || {})) { a.setAttribute(k, attrs[k]); } td.appendChild(a); }

  function run(scenario) {
    var p = params(scenario), inv = scenario === "invalid" ? p.rows : scenario === "valid" ? 0 : Math.min(p.invalid_rows, p.rows);
    var tr = newRow(scenario, p.rows, inv), c = tr.children, t0 = performance.now();
    say("Uploading " + p.rows + " rows (" + NAMES[scenario].toLowerCase() + ")…");
    return post("/app/qa/timing/run", Object.assign({ policy_id: +$("policy-pick").value, effective_date: $("eff").value }, p)).then(function (r) {
      if (!r.ok) { c[2].textContent = "refused"; c[3].textContent = (r.body && (r.body.error || r.body.message)) || ("HTTP " + r.status); say("The upload was refused."); return; }
      c[2].textContent = r.body.upload_ms + " ms";
      var id = r.body.job_id;
      var held = 0;
      return new Promise(function (done) {
        (function poll() {
          get("/app/qa/timing/job/" + id).then(function (j) {
            c[3].textContent = (j.status || "…").replace(/_/g, " ");
            // VALIDATION_PARTIAL is only a place to wait when the engine is set to hold; by default it is passed through in a moment, so only call it held if it stays
            if (j.status === "VALIDATION_PARTIAL") { held = held || performance.now(); } else { held = 0; }
            if (j.status && (TERMINAL.test(j.status) || (held && performance.now() - held > HELD_AFTER_MS))) {
              c[4].textContent = sec(performance.now() - t0);
              var actions = c[7];
              link(actions, "Open", "/app/endorsements/" + id);
              if (j.status === "VALIDATION_PARTIAL") { var b = document.createElement("button"); b.textContent = "Continue with valid rows"; b.className = "alt"; b.onclick = function () { fetch("/app/endorsements/" + id + "/proceed", { method: "POST", credentials: "same-origin", headers: { "X-CSRF-TOKEN": token } }).then(function () { say("Continuing…"); }); }; actions.appendChild(document.createTextNode(" ")); actions.appendChild(b); }
              if (j.failed_rows > 0) {
                c[3].textContent += " · " + j.failed_rows + " failed";
                var s0 = performance.now();
                fetch("/app/endorsements/" + id + "/errors.xlsx", { credentials: "same-origin" }).then(function (x) { return x.blob(); }).then(function (blob) {
                  c[5].textContent = (blob.size / 1024).toFixed(1) + " KB"; c[6].textContent = sec(performance.now() - t0) + " (+" + sec(performance.now() - s0) + " to build it)";
                  var u = URL.createObjectURL(blob); actions.appendChild(document.createTextNode(" ")); link(actions, "Error sheet", u, { download: "errors-" + id.slice(0, 8) + ".xlsx" }); done();
                });
              } else { c[5].textContent = "none needed"; c[6].textContent = c[4].textContent; done(); }
              return;
            }
            setTimeout(poll, 250);
          });
        })();
      });
    }).then(function () { say("Done."); });
  }
  document.addEventListener("click", function (e) {
    var t = e.target;
    if (t.dataset.run) {
      var list = t.dataset.run === "all" ? ["valid", "mixed", "invalid"] : [t.dataset.run];
      document.querySelectorAll("button[data-run]").forEach(function (b) { b.disabled = true; });
      list.reduce(function (p, s) { return p.then(function () { return run(s); }); }, Promise.resolve()).then(function () { document.querySelectorAll("button[data-run]").forEach(function (b) { b.disabled = false; }); });
    } else if (t.dataset.sheet) {
      var p = params(t.dataset.sheet); window.location.href = "/app/qa/timing/sheet?" + new URLSearchParams(p).toString();
    } else if (t.id === "topup") {
      post("/app/qa/timing/deposit", { policy_id: +$("policy-pick").value, amount: +$("amount").value }).then(function (r) { say(r.ok ? "Deposit added." : "The deposit was not added (HTTP " + r.status + ")."); });
    }
  });
})();
