import re

from django.core.validators import validate_email
from django.core.exceptions import ValidationError as DjangoValidationError
from rest_framework.exceptions import NotFound, ValidationError
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.core.permissions import GatewayPermission, IsBrokerAdmin

from . import render
from .models import Contact, Notification, Role, Status, Template, TriggerSetting
from .triggers import REGISTRY

_NO_CTRL = re.compile(r"[\x00-\x1f\x7f<>]")


def _perms(view):
    return [GatewayPermission()] + ([IsBrokerAdmin()] if view.request.method not in ("GET", "HEAD", "OPTIONS") else [])


def _opt_int(d, key):
    v = d.get(key)
    if v is None:
        return None
    if isinstance(v, bool) or not isinstance(v, int):
        raise ValidationError({key: "must be an integer"})
    return v


def _contact(c):
    return {"id": c.id, "role": c.role, "name": c.name, "email": c.email, "employer_id": c.employer_id, "policy_id": c.policy_id, "is_active": c.is_active}


def _tpl(t):
    return {"id": t.id, "trigger_code": t.trigger_code, "subject": t.subject, "body_html": t.body_html, "employer_id": t.employer_id,
            "policy_id": t.policy_id, "is_active": t.is_active, "updated_at": t.updated_at.isoformat()}


def _note(n, full=False):
    d = {"id": str(n.id), "trigger_code": n.trigger_code, "to_email": n.to_email, "subject": n.subject, "status": n.status, "attempts": n.attempts,
         "policy_id": n.policy_id, "employer_id": n.employer_id, "last_error": n.last_error, "created_at": n.created_at.isoformat(),
         "sent_at": n.sent_at.isoformat() if n.sent_at else None}
    if full:
        d["body_html"] = n.body_html
    return d


class TriggerListView(APIView):
    def get(self, request):
        b = request.user.broker_id
        defaults = {s.trigger_code: s.email_enabled for s in TriggerSetting.objects.filter(broker_id=b, policy_id__isnull=True)}
        overrides = {}
        for s in TriggerSetting.objects.filter(broker_id=b, policy_id__isnull=False):
            overrides.setdefault(s.trigger_code, {})[s.policy_id] = s.email_enabled
        return Response([{"code": t.code, "label": t.label, "recipient_roles": list(t.roles), "placeholders": list(t.placeholders),
                          "built_in_default": t.default_enabled, "broker_default": defaults.get(t.code), "policy_overrides": overrides.get(t.code, {})}
                         for t in REGISTRY.values()])


class TriggerSettingView(APIView):
    def get_permissions(self):
        return _perms(self)

    def put(self, request):
        d = request.data
        code, enabled = d.get("trigger_code"), d.get("email_enabled")
        if code not in REGISTRY:
            raise ValidationError({"trigger_code": "unknown trigger"})
        if not isinstance(enabled, bool):
            raise ValidationError({"email_enabled": "must be true or false"})
        TriggerSetting.objects.update_or_create(broker_id=request.user.broker_id, policy_id=_opt_int(d, "policy_id"), trigger_code=code,
                                                defaults={"email_enabled": enabled})
        return Response({"trigger_code": code, "policy_id": d.get("policy_id"), "email_enabled": enabled})


class ContactListView(APIView):
    def get_permissions(self):
        return _perms(self)

    def get(self, request):
        return Response([_contact(c) for c in Contact.objects.filter(broker_id=request.user.broker_id).order_by("id")[:1000]])

    def post(self, request):
        d = request.data
        email = d.get("email")
        try:
            if not isinstance(email, str) or _NO_CTRL.search(email):
                raise DjangoValidationError("bad")
            validate_email(email.strip())
        except DjangoValidationError:
            raise ValidationError({"email": "a valid email address is required"})
        name = d.get("name", "")
        if not isinstance(name, str) or len(name) > 120 or _NO_CTRL.search(name):
            raise ValidationError({"name": "at most 120 characters, no control characters or angle brackets"})
        if d.get("role") not in Role.values:
            raise ValidationError({"role": f"one of {Role.values}"})
        c, created = Contact.objects.get_or_create(broker_id=request.user.broker_id, employer_id=_opt_int(d, "employer_id"), policy_id=_opt_int(d, "policy_id"),
                                                   role=d["role"], email=email.strip().lower(), defaults={"name": name})
        return Response(_contact(c), status=201 if created else 200)


class ContactDetailView(APIView):
    def get_permissions(self):
        return _perms(self)

    def delete(self, request, pk):
        n, _ = Contact.objects.filter(broker_id=request.user.broker_id, pk=pk).delete()
        if not n:
            raise NotFound()
        return Response(status=204)


def _validated_template_fields(d, partial_of=None):
    code = d.get("trigger_code", partial_of.trigger_code if partial_of else None)
    if code not in REGISTRY:
        raise ValidationError({"trigger_code": "unknown trigger"})
    subject, body = d.get("subject"), d.get("body_html")
    if not isinstance(subject, str) or not subject.strip() or len(subject) > 200:
        raise ValidationError({"subject": "required, at most 200 characters"})
    if not isinstance(body, str) or not body.strip() or len(body) > 100_000:
        raise ValidationError({"body_html": "required, at most 100000 characters"})
    try:
        render.validate(subject, body, REGISTRY[code].placeholders)
    except render.TemplateError as e:
        raise ValidationError({"template": str(e)})
    return code, subject, body


class TemplateListView(APIView):
    def get_permissions(self):
        return _perms(self)

    def get(self, request):
        return Response([_tpl(t) for t in Template.objects.filter(broker_id=request.user.broker_id).order_by("-id")[:500]])

    def post(self, request):
        code, subject, body = _validated_template_fields(request.data)
        t = Template.objects.create(broker_id=request.user.broker_id, employer_id=_opt_int(request.data, "employer_id"),
                                    policy_id=_opt_int(request.data, "policy_id"), trigger_code=code, subject=subject, body_html=body,
                                    created_by=request.user.user_id)
        return Response(_tpl(t), status=201)


class TemplateDetailView(APIView):
    def get_permissions(self):
        return _perms(self)

    def _get(self, request, pk):
        t = Template.objects.filter(broker_id=request.user.broker_id, pk=pk).first()
        if not t:
            raise NotFound()
        return t

    def put(self, request, pk):
        t = self._get(request, pk)
        _, t.subject, t.body_html = _validated_template_fields({**request.data, "trigger_code": t.trigger_code}, t)
        if isinstance(request.data.get("is_active"), bool):
            t.is_active = request.data["is_active"]
        t.save()
        return Response(_tpl(t))

    def delete(self, request, pk):
        self._get(request, pk).delete()
        return Response(status=204)


class TemplatePreviewView(APIView):
    """Render a draft with sample values, so a broker sees the result (and any placeholder error) before saving."""

    def post(self, request):
        code, subject, body = _validated_template_fields(request.data)
        sample = {p: f"<{p}>" for p in REGISTRY[code].placeholders}
        sample.update(policy_number="P-12345", brand="Your Broker", job_id="9a1f4a52", op_type="ADD", members_changed="3")
        return Response({"subject": render.render_subject(subject, sample), "body_html": render.render_body(body, sample)})


class LogListView(APIView):
    def get(self, request):
        qs = Notification.objects.filter(broker_id=request.user.broker_id)
        for field in ("status", "trigger_code"):
            if field in request.query_params:
                qs = qs.filter(**{field: request.query_params[field]})
        if "policy_id" in request.query_params:
            try:
                qs = qs.filter(policy_id=int(request.query_params["policy_id"]))
            except ValueError:
                raise ValidationError({"policy_id": "must be an integer"})
        try:
            size = min(max(int(request.query_params.get("page_size", 50)), 1), 200)
            page = max(int(request.query_params.get("page", 1)), 1)
        except ValueError:
            raise ValidationError("page and page_size must be integers")
        return Response({"count": qs.count(), "page": page, "page_size": size,
                         "results": [_note(n) for n in qs.order_by("-created_at")[(page - 1) * size: page * size]]})


class LogDetailView(APIView):
    def get(self, request, pk):
        n = Notification.objects.filter(broker_id=request.user.broker_id, pk=pk).first()
        if not n:
            raise NotFound()
        return Response(_note(n, full=True))


class LogRetryView(APIView):
    def get_permissions(self):
        return _perms(self)

    def post(self, request, pk):
        from django.utils import timezone
        n = Notification.objects.filter(broker_id=request.user.broker_id, pk=pk).first()
        if not n:
            raise NotFound()
        if n.status != Status.DEAD:
            from rest_framework.exceptions import APIException

            class Conflict(APIException):
                status_code, default_detail = 409, "Only dead-lettered notifications can be retried"
            raise Conflict()
        n.status, n.attempts, n.next_attempt_at, n.lease_until = Status.PENDING, 0, timezone.now(), None
        n.save(update_fields=["status", "attempts", "next_attempt_at", "lease_until"])
        return Response(_note(n))
