"""Downloadable reports (CSV / XLSX) built from the read models, through the same tenant-scoped collections as the API.
Row-capped (REPORT_MAX_ROWS) with an `X-Report-Truncated` header, so a report can never be an unbounded scan. No member PII exists in
these collections, so there is none in the files. Spreadsheet formula injection is neutralised in both formats."""
import csv
import io
from datetime import datetime, time, timezone

from django.conf import settings
from django.http import HttpResponse
from rest_framework.exceptions import NotFound, ValidationError
from rest_framework.views import APIView

from apps.core import mongo

_FORMULA_START = ("=", "+", "-", "@", "\t", "\r")


def safe_cell(v):
    """Values that a spreadsheet would execute are prefixed so they are shown as text (OWASP CSV injection guidance)."""
    if isinstance(v, str) and v.startswith(_FORMULA_START):
        return "'" + v
    if isinstance(v, datetime):
        return v.isoformat()
    return "" if v is None else v


REPORTS = {
    "endorsements": {"collection": mongo.JOBS, "sort": [("state_ts", -1), ("_id", 1)], "date_field": "state_ts",
                     "columns": [("job_id", "_id"), ("policy_id", "policy_id"), ("employer_id", "employer_id"), ("operation", "op_type"), ("source", "source_type"),
                                 ("state", "state"), ("phase", "phase"), ("category", "category"), ("terminal", "is_terminal"), ("last_change", "state_ts"),
                                 ("endorsed_at", "endorsed_at"), ("members_changed", "applied.members_changed"), ("premium_delta", "applied.premium_delta"),
                                 ("tax", "applied.tax_amount"), ("total", "applied.total")],
                     "filters": {"state": str, "policy_id": int, "op_type": str, "is_terminal": "bool"}},
    "policies": {"collection": mongo.POLICIES, "sort": [("_id", 1)], "date_field": None,
                 "columns": [("policy_id", "_id"), ("policy_number", "policy_number"), ("employer_id", "employer_id"), ("status", "status"), ("tpa_id", "tpa_id"),
                             ("premium_type", "premium_type")], "filters": {"status": str, "tpa_id": int}},
    "cd-balances": {"collection": mongo.CD_ACCOUNTS, "sort": [("_id", 1)], "date_field": "updated_at",
                    "columns": [("policy_id", "_id"), ("employer_id", "employer_id"), ("balance", "balance"), ("reserved", "reserved"), ("available", "available"),
                                ("threshold", "threshold"), ("low", "low"), ("updated_at", "updated_at")], "filters": {"low": "bool"}},
    "tpa-syncs": {"collection": mongo.TPA_SYNCS, "sort": [("updated_at", -1)], "date_field": "updated_at",
                  "columns": [("sync_id", "_id"), ("endorsement_job_id", "endorsement_job_id"), ("policy_id", "policy_id"), ("tpa_id", "tpa_id"), ("status", "status"),
                              ("expected", "members_expected"), ("synced", "synced"), ("rejected", "rejected"), ("attempts", "attempts"), ("error", "error"),
                              ("updated_at", "updated_at")], "filters": {"status": str, "policy_id": int}},
}


def _get(doc, path):
    for part in path.split("."):
        doc = doc.get(part) if isinstance(doc, dict) else None
    return doc


def _date(request, name, end=False):
    v = request.query_params.get(name)
    if v is None:
        return None
    try:
        d = datetime.fromisoformat(v).date()
    except ValueError:
        raise ValidationError({name: "use YYYY-MM-DD"})
    return datetime.combine(d, time.max if end else time.min, tzinfo=timezone.utc)


class ReportView(APIView):
    def get(self, request, report):
        spec = REPORTS.get(report)
        if spec is None:
            raise NotFound()
        fmt = request.query_params.get("file", "csv")
        if fmt not in ("csv", "xlsx"):
            raise ValidationError({"file": "csv or xlsx"})
        flt = {}
        for name, kind in spec["filters"].items():
            v = request.query_params.get(name)
            if v is None:
                continue
            if kind == "bool":
                if v not in ("true", "false"):
                    raise ValidationError({name: "true or false"})
                flt[name] = v == "true"
            elif kind is int:
                try:
                    flt[name] = int(v)
                except ValueError:
                    raise ValidationError({name: "must be an integer"})
            else:
                flt[name] = v
        lo, hi = _date(request, "from"), _date(request, "to", end=True)
        if (lo or hi) and spec["date_field"]:
            flt[spec["date_field"]] = {k: v for k, v in (("$gte", lo), ("$lte", hi)) if v}
        cap = settings.REPORT_MAX_ROWS
        rows = list(mongo.scoped(request.user, spec["collection"]).find(flt or None, sort=spec["sort"], limit=cap + 1))
        truncated = len(rows) > cap
        rows = rows[:cap]
        header = [c for c, _ in spec["columns"]]
        data = [[safe_cell(_get(r, path)) for _, path in spec["columns"]] for r in rows]
        if fmt == "csv":
            buf = io.StringIO()
            w = csv.writer(buf)
            w.writerow(header)
            w.writerows(data)
            resp = HttpResponse(buf.getvalue().encode("utf-8-sig"), content_type="text/csv; charset=utf-8")     # BOM so Excel reads UTF-8
        else:
            from openpyxl import Workbook
            wb = Workbook(write_only=True)
            ws = wb.create_sheet(report[:31])
            ws.append(header)
            for row in data:
                ws.append(row)
            out = io.BytesIO()
            wb.save(out)
            resp = HttpResponse(out.getvalue(), content_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        resp["Content-Disposition"] = f'attachment; filename="{report}.{fmt}"'
        resp["X-Report-Truncated"] = "true" if truncated else "false"
        resp["Cache-Control"] = "no-store"
        return resp
