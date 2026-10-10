(function () {
  "use strict";
  var root = document.getElementById("manage"), token = root.dataset.token;
  var cards = JSON.parse(root.dataset.cards), employers = JSON.parse(root.dataset.employers || "[]"), policies = JSON.parse(root.dataset.policies || "[]");
  var byName = {}; cards.forEach(function (c) { byName[c.name] = c; });
  var empName = {}; employers.forEach(function (e) { empName[e.id] = e.name; });

  function el(tag, attrs, kids) {
    var n = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) { if (k === "text") { n.textContent = attrs[k]; } else if (k === "class") { n.className = attrs[k]; } else { n.setAttribute(k, attrs[k]); } });
    (kids || []).forEach(function (c) { n.appendChild(typeof c === "string" ? document.createTextNode(c) : c); });
    return n;
  }
  function nice(k) { return k.replace(/_/g, " ").replace(/^./, function (c) { return c.toUpperCase(); }); }

  function control(f, id) {
    var t = f.type, input;
    if (f.enum) {
      input = el("select", { id: id }, [el("option", { value: "", text: f.required ? "Choose…" : "Leave as it is" })].concat(f.enum.map(function (v) { return el("option", { value: v, text: String(v) }); })));
    } else if (t === "bool") {
      input = el("select", { id: id }, [el("option", { value: "", text: "Leave as it is" }), el("option", { value: "true", text: "Yes" }), el("option", { value: "false", text: "No" })]);
    } else if (t === "int" || t === "number") {
      input = el("input", { id: id, type: "number", step: t === "int" ? "1" : "any" }); if (f.min != null) { input.min = f.min; } if (f.max != null) { input.max = f.max; }
    } else if (t === "date") {
      input = el("input", { id: id, type: "date" });
    } else if (t === "list" || t === "map" || t === "raw") {
      input = el("textarea", { id: id, rows: "4", placeholder: "Advanced: paste the value (JSON)" });
    } else {
      input = el("input", { id: id, type: "text", autocomplete: "off" }); if (f.max) { input.maxLength = f.max; }
    }
    return input;
  }
  function value(f, input) {
    var v = input.value.trim(); if (v === "") { return undefined; }
    if (f.type === "int") { return parseInt(v, 10); } if (f.type === "number") { return parseFloat(v); } if (f.type === "bool") { return v === "true"; }
    if (f.type === "list" || f.type === "map" || f.type === "raw") { return JSON.parse(v); }
    return v;
  }
  function picker(p, id) {
    var sel = el("select", { id: id }, [el("option", { value: "", text: p.picker === "employer" ? "Choose an employer…" : "Choose a policy…" })]);
    (p.picker === "employer" ? employers.map(function (e) { return { id: e.id, label: e.name }; }) : policies.map(function (x) { return { id: x.id, label: x.number + (x.employer_id && empName[x.employer_id] ? " · " + empName[x.employer_id] : "") }; }))
      .forEach(function (o) { sel.appendChild(el("option", { value: o.id, text: o.label })); });
    return sel;
  }

  function friendly(status, body) {
    var msg = ({ 401: "Please sign in again.", 403: "You do not have permission to do this.", 404: "That could not be found.", 409: "That is not possible right now.", 429: "Too many requests. Wait a moment and try again." })[status];
    if (body && (body.detail || body.title) && status !== 500 && status !== 502) { msg = (body.detail || body.title); }
    if (!msg) { msg = "Something went wrong on our side. Try again, and if it keeps happening quote this reference: " + ((body && body.correlation_id) || "none"); }
    var wrap = el("div", { class: "msg err", role: "alert" }, [el("strong", { text: msg })]);
    if (body && Array.isArray(body.issues) && body.issues.length) { wrap.appendChild(el("ul", {}, body.issues.map(function (i) { return el("li", { text: (i.field ? nice(i.field) + ": " : "") + i.message }); }))); }
    return wrap;
  }
  function cellText(v) { if (v === null || v === undefined || v === "") { return "–"; } if (typeof v === "boolean") { return v ? "Yes" : "No"; } if (typeof v === "object") { return JSON.stringify(v); } return String(v); }
  function table(rows) {
    var cols = []; rows.forEach(function (r) { Object.keys(r).forEach(function (k) { if (cols.indexOf(k) < 0) { cols.push(k); } }); });
    return el("div", { class: "table-wrap" }, [el("table", {}, [el("thead", {}, [el("tr", {}, cols.map(function (c) { return el("th", { text: nice(c) }); }))]),
      el("tbody", {}, rows.map(function (r) { return el("tr", {}, cols.map(function (c) { return el("td", { text: cellText(r[c]) }); })); }))])]);
  }
  function show(data) {
    if (data === null || data === undefined || (typeof data === "object" && !Object.keys(data).length)) { return el("div", { class: "msg ok", text: "Done." }); }
    if (Array.isArray(data)) { return data.length && typeof data[0] === "object" ? table(data) : el("p", { text: data.length ? data.join(", ") : "Nothing to show." }); }
    var listKey = ["items", "results", "content"].filter(function (k) { return Array.isArray(data[k]); })[0];
    if (listKey) {
      var box = el("div", {}, [el("p", { class: "mut", text: (data.total != null ? data.total : data[listKey].length) + " found" })]);
      box.appendChild(data[listKey].length ? table(data[listKey]) : el("p", { text: "Nothing to show." })); return box;
    }
    var dl = el("dl", { class: "kv" });
    Object.keys(data).forEach(function (k) { dl.appendChild(el("dt", { text: nice(k) })); dl.appendChild(el("dd", { text: cellText(data[k]) })); });
    return dl;
  }

  function build(card) {
    var art = el("article", { class: "card", id: card.name }), form = el("form", { class: "form", novalidate: "novalidate" }), out = el("div", { role: "status", "aria-live": "polite" });
    art.appendChild(el("h2", { text: card.title, style: "margin-top:0" })); art.appendChild(el("p", { class: "mut", text: card.help }));
    var inputs = { params: {}, query: {}, body: {}, extra: {} };
    function row(label, input, required) { var l = el("label", {}, [el("span", { text: label + (required ? " *" : "") }), input]); form.appendChild(l); return input; }
    card.params.forEach(function (p) {
      inputs.params[p.name] = row(p.label, p.picker ? picker(p, card.name + "-p-" + p.name) : p.options ? el("select", { id: card.name + "-p-" + p.name }, p.options.map(function (o) { return el("option", { value: o, text: nice(o) }); })) : el("input", { id: card.name + "-p-" + p.name, type: "text", autocomplete: "off", placeholder: p.name === "id" ? "The number or code shown on its page" : "" }), true);
    });
    card.query.forEach(function (q) { inputs.query[q.name] = row(q.label + " (optional)", el("input", { id: card.name + "-q-" + q.name, type: /(^|_)(from|to)$/.test(q.name) ? "date" : "text", autocomplete: "off" }), false); });
    card.extra.forEach(function (x) { inputs.extra[x.name] = row(x.label, el("input", { id: card.name + "-x-" + x.name, type: "text", autocomplete: "off" }), false); });
    var file = null; if (card.multipart) { file = row("File", el("input", { id: card.name + "-file", type: "file" }), true); }
    card.fields.forEach(function (f) { inputs.body[f.name] = row(f.label, control(f, card.name + "-b-" + f.name), f.required); });
    var actions = el("div", { style: "display:flex;gap:.5rem;flex-wrap:wrap;margin-top:.6rem" });
    var go = el("button", { type: "submit", class: card.destructive ? "danger" : "", text: card.download ? "Download" : card.write ? (card.destructive ? "Do this" : "Save") : "Show" });
    actions.appendChild(go);
    if (card.load) {
      var ld = el("button", { type: "button", class: "alt", text: "Load what is set now" }); actions.appendChild(ld);
      ld.onclick = function () { run(byName[card.load], true).then(function (res) { if (res && res.ok && res.data) { card.fields.forEach(function (f) { var v = res.data[f.name]; if (v !== undefined && v !== null) { inputs.body[f.name].value = typeof v === "object" ? JSON.stringify(v) : String(v); } }); out.replaceChildren(el("div", { class: "msg ok", text: "Loaded. Change what you need and press Save." })); } }); };
    }
    form.appendChild(actions); art.appendChild(form); art.appendChild(out);

    function gather(c, ins) {
      var p = {}, q = {}, b = {};
      c.params.forEach(function (x) { p[x.name] = (ins.params[x.name] || {}).value || ""; });
      c.query.forEach(function (x) { if (ins.query[x.name] && ins.query[x.name].value) { q[x.name] = ins.query[x.name].value; } });
      c.fields.forEach(function (f) { if (ins.body[f.name]) { var v = value(f, ins.body[f.name]); if (v !== undefined) { b[f.name] = v; } } });
      return { p: p, q: q, b: b };
    }
    function run(c, quiet) {
      var g; try { g = gather(c, c === card ? inputs : { params: inputs.params, query: {}, body: {} }); } catch (e) { out.replaceChildren(el("div", { class: "msg err", role: "alert", text: "One of the advanced values is not valid JSON." })); return Promise.resolve(null); }
      var fd = new FormData(); fd.append("name", c.name); fd.append("params", JSON.stringify(g.p)); fd.append("query", JSON.stringify(g.q)); fd.append("body", JSON.stringify(Object.assign({}, g.b, c === card ? extra() : {})));
      if (c === card && file && file.files[0]) { fd.append("file", file.files[0]); }
      if (!quiet) { out.replaceChildren(el("p", { class: "mut", text: "Working…" })); }
      return fetch("/app/manage/call", { method: "POST", credentials: "same-origin", headers: { "Accept": "application/json", "X-CSRF-TOKEN": token }, body: fd })
        .then(function (r) { return r.json().then(function (j) { return j; }).catch(function () { return { status: r.status, ok: false, data: null }; }); })
        .then(function (res) { if (!quiet) { out.replaceChildren(res.ok ? show(res.data) : friendly(res.status, res.data)); } return res; });
    }
    function extra() { var e = {}; Object.keys(inputs.extra).forEach(function (k) { if (inputs.extra[k].value) { e[k] = inputs.extra[k].value; } }); return e; }
    form.addEventListener("submit", function (ev) {
      ev.preventDefault();
      if (card.download) {
        var g = gather(card, inputs), u = new URLSearchParams(); Object.keys(g.p).forEach(function (k) { u.append("p[" + k + "]", g.p[k]); }); Object.keys(g.q).forEach(function (k) { u.append("q[" + k + "]", g.q[k]); });
        window.location.href = "/app/manage/download/" + card.name + "?" + u.toString(); return;
      }
      if (card.destructive && !window.confirm("Are you sure? " + card.title + ".")) { return; }
      run(card, false);
    });
    return art;
  }

  cards.forEach(function (c) { root.appendChild(build(c)); });
  if (location.hash) { var t = document.getElementById(location.hash.slice(1)); if (t) { t.scrollIntoView(); } }
})();
