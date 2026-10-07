import datetime as dt
import logging

from django.db import IntegrityError, transaction
from rest_framework import status
from rest_framework.exceptions import NotFound, ParseError, ValidationError
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.computation.policy_client import PolicyAccessError, PolicyNotFound, PolicyUnavailable, get_policy_client
from apps.core.exceptions import Conflict, Unprocessable, UpstreamUnavailable
from apps.core.outbox import run_after_commit
from apps.core.permissions import CAN_SUBMIT, GatewayPermission
from apps.core.tenancy import scope_filter
from apps.jobs.models import EndorsementJob
from apps.jobs.serializers import job_to_dict
from apps.jobs.machine import transition  # noqa: F401  (re-exported for tests)

from .models import SchemaMapping
from .parser import FileProblem, read_header
from .schema import SYNONYMS, is_auto_confirmable, missing_required, norm_header, suggest_mapping
from .store import FileTooLarge, get_store
from .tasks import ingest_csv

log = logging.getLogger(__name__)
OPS = {"ADD", "REMOVE", "UPDATE"}


def _op(request) -> str:
    op = str(request.data.get("op_type", "")).upper()
    if op not in OPS:
        raise ValidationError({"op_type": f"must be one of {sorted(OPS)}"})
    return op


def _file(request):
    f = request.FILES.get("file")
    if f is None:
        raise ValidationError({"file": "a CSV file is required (multipart field 'file')"})
    if not f.name.lower().endswith((".csv", ".xlsx")):
        raise ValidationError({"file": "only .csv and .xlsx files are accepted"})
    head = f.read(4)
    f.seek(0)
    is_zip = head == b"PK\x03\x04"
    if f.name.lower().endswith(".xlsx") != is_zip:         # the extension must match the content: no CSV renamed .xlsx, no zip renamed .csv
        raise ValidationError({"file": "the file content does not match its extension"})
    return f


def _policy(request, policy_id: int):
    """Fetch policy facts, enforcing the caller's tenant. Cross-tenant and missing policies are both 404."""
    scope_filter(request.user)                                    # rejects tokens without tenant scope
    try:
        info = get_policy_client().get_policy(request.user.broker_id, policy_id)
    except PolicyNotFound as e:
        raise NotFound("Policy not found") from e
    except (PolicyUnavailable, PolicyAccessError) as e:
        log.error("policy lookup failed: %s", e)
        raise UpstreamUnavailable("policy service unavailable") from e
    if request.user.is_employer and info.employer_id != request.user.employer_id:
        raise NotFound("Policy not found")
    return info


def _mappings_for(principal):
    qs = SchemaMapping.objects.filter(broker_id=principal.broker_id)
    return qs.filter(employer_id=principal.employer_id) if principal.is_employer else qs


def _mapping_to_dict(m: SchemaMapping) -> dict:
    return {"id": str(m.id), "source_name": m.source_name, "source_key": m.source_key, "column_map": m.column_map,
            "options": m.options, "version": m.version, "is_confirmed": m.is_confirmed, "employer_id": m.employer_id,
            "created_at": m.created_at}


def _validate_column_map(cm) -> dict:
    if not isinstance(cm, dict) or not cm:
        raise ValidationError({"column_map": "must be a non-empty object {header: field}"})
    bad = {h: f for h, f in cm.items() if f not in SYNONYMS}
    if bad:
        raise ValidationError({"column_map": f"unknown canonical fields: {sorted(set(bad.values()))}"})
    targets = list(cm.values())
    dup = {t for t in targets if targets.count(t) > 1}
    if dup:
        raise ValidationError({"column_map": f"several headers map to the same field: {sorted(dup)}"})
    return cm


class CsvPreviewView(APIView):
    """Header-only parse: suggests a mapping and says what is missing. Writes nothing."""
    permission_classes = [GatewayPermission, CAN_SUBMIT]

    def post(self, request):
        op, f = _op(request), _file(request)
        try:
            headers = read_header(f.file)
        except FileProblem as e:
            raise ParseError(str(e)) from e
        sug = suggest_mapping(headers)
        mapped = {s.field for s in sug if s.field}
        return Response({
            "headers": headers,
            "suggestions": [{"header": s.header, "field": s.field, "confidence": s.confidence} for s in sug],
            "unmapped_headers": [s.header for s in sug if not s.field],
            "missing_required": missing_required(mapped, op),
            "auto_confirmable": is_auto_confirmable(sug, op),
        })


class MappingListCreateView(APIView):
    permission_classes = [GatewayPermission, CAN_SUBMIT]

    def get(self, request):
        return Response([_mapping_to_dict(m) for m in _mappings_for(request.user).order_by("-created_at")[:200]])

    def post(self, request):
        d = request.data
        if not d.get("source_name"):
            raise ValidationError({"source_name": "required"})
        employer_id = request.user.employer_id if request.user.is_employer else d.get("employer_id")
        m = SchemaMapping.objects.create(
            broker_id=request.user.broker_id, employer_id=employer_id, source_name=str(d["source_name"])[:120],
            source_key=str(d.get("source_key", ""))[:40], column_map=_validate_column_map(d.get("column_map")),
            options=d.get("options") or {}, is_confirmed=True)
        return Response(_mapping_to_dict(m), status=status.HTTP_201_CREATED)


class MappingDetailView(APIView):
    permission_classes = [GatewayPermission, CAN_SUBMIT]

    def put(self, request, mapping_id):
        m = _mappings_for(request.user).filter(id=mapping_id).first()
        if m is None:
            raise NotFound()
        m.column_map = _validate_column_map(request.data.get("column_map"))
        m.options = request.data.get("options", m.options) or {}
        m.version += 1
        m.save()
        return Response(_mapping_to_dict(m))


class CsvUploadView(APIView):
    permission_classes = [GatewayPermission, CAN_SUBMIT]

    def post(self, request):
        op, f = _op(request), _file(request)
        try:
            policy_id = int(request.data.get("policy_id"))
        except (TypeError, ValueError):
            raise ValidationError({"policy_id": "required integer"}) from None
        eff = request.data.get("effective_date")
        try:
            effective = dt.date.fromisoformat(eff) if eff else None
        except ValueError:
            raise ValidationError({"effective_date": "must be YYYY-MM-DD"}) from None

        policy = _policy(request, policy_id)
        if policy.status != "APPROVED_AND_LIVE":
            raise Unprocessable(f"policy is {policy.status}; endorsements need an APPROVED_AND_LIVE policy")

        idem = (request.headers.get("Idempotency-Key") or "").strip() or None
        store = get_store()
        try:
            stored = store.save(f.file, max_bytes=_max_bytes())
        except FileTooLarge as e:
            return Response({"detail": str(e)}, status=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE)
        if stored.size == 0:
            store.delete(stored.key)
            raise ValidationError({"file": "file is empty"})

        if idem:
            existing = EndorsementJob.objects.filter(broker_id=request.user.broker_id, idempotency_key=idem).first()
            if existing:
                store.delete(stored.key)
                if (existing.policy_id, existing.op_type, existing.file_sha256) != (policy_id, op, stored.sha256):
                    raise Conflict("Idempotency-Key was already used for a different request")
                return Response({**self._body(request, existing), "idempotent_replay": True}, status=status.HTTP_200_OK)

        try:
            with store.open(stored.key) as fh:
                headers = read_header(fh)
            mapping = self._resolve_mapping(request, headers, op, policy.employer_id)
        except FileProblem as e:
            store.delete(stored.key)
            raise ParseError(str(e)) from e
        except Exception:
            store.delete(stored.key)
            raise

        try:
            with transaction.atomic():
                job = EndorsementJob.objects.create(
                    broker_id=request.user.broker_id, employer_id=policy.employer_id, policy_id=policy_id,
                    source_type="CSV_UPLOAD", op_type=op, idempotency_key=idem, schema_mapping_id=mapping.id,
                    source_filename=f.name[:255], file_sha256=stored.sha256, effective_date=effective,
                    actor_user_id=request.user.user_id, actor_ip=request.META.get("REMOTE_ADDR"),
                    metadata={"file_key": stored.key, "policy": _policy_facts(policy)})
                run_after_commit(lambda: ingest_csv.apply_async(args=[str(job.id)]))
        except IntegrityError:                                    # lost an idempotency race: return the winner
            store.delete(stored.key)
            winner = EndorsementJob.objects.get(broker_id=request.user.broker_id, idempotency_key=idem)
            return Response({**self._body(request, winner), "idempotent_replay": True}, status=status.HTTP_200_OK)
        job.refresh_from_db()
        return Response(self._body(request, job), status=status.HTTP_202_ACCEPTED)

    @staticmethod
    def _body(request, job):
        base = request.path.split("csv/upload/")[0]
        return {**job_to_dict(job), "job_id": str(job.id), "progress_url": f"{base}{job.id}/progress/"}

    def _resolve_mapping(self, request, headers, op, employer_id):
        mid = request.data.get("mapping_id")
        if mid:
            m = _mappings_for(request.user).filter(id=mid).first()
            if m is None:
                raise NotFound("Mapping not found")
            mapped = set(m.column_map.values())
            known = {norm_header(h) for h in m.column_map}
            if not known & {norm_header(h) for h in headers}:
                raise Unprocessable("none of the mapping's columns appear in this file")
            absent = [h for h in m.column_map if norm_header(h) not in {norm_header(x) for x in headers}]
            problems = missing_required(mapped, op)
            if problems or absent:
                raise Unprocessable({"missing_required": problems, "mapped_columns_absent_from_file": absent})
            m.last_used_at = dt.datetime.now(dt.UTC)
            m.save(update_fields=["last_used_at"])
            return m
        sug = suggest_mapping(headers)
        if not is_auto_confirmable(sug, op):
            mapped = {s.field for s in sug if s.field}
            raise Unprocessable({"detail": "headers could not be mapped with confidence; confirm a mapping first",
                                 "missing_required": missing_required(mapped, op),
                                 "suggestions": [{"header": s.header, "field": s.field, "confidence": s.confidence}
                                                 for s in sug]})
        return SchemaMapping.objects.create(
            broker_id=request.user.broker_id, employer_id=request.user.employer_id if request.user.is_employer else employer_id,
            source_name=f"auto:{request.data.get('source_key', 'csv')}"[:120], source_key=str(request.data.get("source_key", ""))[:40],
            column_map={s.header: s.field for s in sug if s.field}, confidence={s.header: s.confidence for s in sug},
            is_confirmed=False)


def _max_bytes() -> int:
    from django.conf import settings
    return settings.MAX_UPLOAD_BYTES


def _policy_facts(p) -> dict:
    return {"policy_type": p.policy_type, "requires_family_construct": p.requires_family_construct,
            "start_date": p.start_date.isoformat(), "end_date": p.end_date.isoformat(),
            "pro_rata_type": p.pro_rata_type, "is_inception": p.is_inception, "policy_number": p.policy_number}
