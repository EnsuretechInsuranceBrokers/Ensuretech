/* The "how many employees" form: says, as the number changes, which way it will go. The server decides; this only tells the person early. */
(function () {
  var n = document.getElementById("count"), out = document.getElementById("route"); if (!n || !out) return;
  var LIMIT = parseInt(n.getAttribute("data-limit"), 10) || 100;
  function say() { var v = parseInt(n.value, 10); if (!v || v < 1) { out.textContent = ""; return; }
    out.textContent = v <= LIMIT ? v + " employee" + (v === 1 ? "" : "s") + ": you will type them into a grid that checks as you go." : v.toLocaleString("en-IN") + " employees: you will get a spreadsheet made for this policy."; }
  n.addEventListener("input", say); say();
})();
