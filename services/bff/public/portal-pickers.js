// An employer dropdown narrows the policy dropdown next to it: only that employer's policies stay selectable. "All employers" shows every policy.
(function () {
  "use strict";
  document.querySelectorAll("select[data-narrows]").forEach(function (emp) {
    var pol = document.getElementById(emp.dataset.narrows); if (!pol) { return; }
    var all = Array.prototype.slice.call(pol.options);
    function apply() {
      var keep = pol.value;
      pol.replaceChildren.apply(pol, all.filter(function (o) { return !emp.value || o.dataset.employer === emp.value; }));
      if (!pol.querySelector("option[value='" + keep + "']")) { pol.selectedIndex = 0; } else { pol.value = keep; }
    }
    emp.addEventListener("change", apply);
  });
})();
