import re
import uuid
from datetime import UTC, datetime

from rest_framework.exceptions import NotFound, ValidationError
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView
from django.conf import settings

from apps.core import mongo
from apps.core.permissions import GatewayPermission, IsBrokerAdmin, gateway_for

from . import answer, store
from .chunking import chunk_text

KINDS = {"POLICY_DOC", "TPA_RULES", "NOTE"}
AUDIENCES = {"BROKER", "EMPLOYER"}


class AskView(APIView):
    """POST {question, policy_id?} -> grounded answer with citations. Advisory only."""

    throttle_scope = "ask"

    def post(self, request):
        q = request.data.get("question")
        if not isinstance(q, str) or not q.strip():
            raise ValidationError({"question": "required"})
        if len(q) > settings.RAG_MAX_QUESTION_CHARS:
            raise ValidationError({"question": f"at most {settings.RAG_MAX_QUESTION_CHARS} characters"})
        pid = request.data.get("policy_id")
        if pid is not None and (not isinstance(pid, int) or isinstance(pid, bool)):
            raise ValidationError({"policy_id": "must be an integer"})
        found = store.retrieve(request.user, q.strip(), policy_id=pid)
        text, mode = answer.ask(q.strip(), found["hits"])
        return Response({"answer": text, "mode": mode, "advisory": True, "truncated": found["truncated"],
                         "citations": [{"n": i, "chunk_id": h["chunk_id"], "title": h["title"], "source_type": h["source_type"],
                                        "source_id": h["source_id"], "policy_id": h["policy_id"], "score": h["score"]}
                                       for i, h in enumerate(found["hits"], 1)]})


class KnowledgeView(APIView):
    """Broker admins manage the documents the assistant may quote. Employers can only read what was shared with them."""

    def get_permissions(self):
        return [GatewayPermission()] + ([IsBrokerAdmin()] if self.request.method in ("POST", "DELETE") else [])

    def get(self, request):
        docs = mongo.scoped(request.user, mongo.RAG_DOCS).find(None, {"text": 0}, sort=[("created_at", -1)], limit=200)
        return Response([_doc_out(d) for d in docs])

    def post(self, request):
        if gateway_for(request.path) != "broker":
            raise NotFound()
        d = request.data
        title, text, kind, audience = d.get("title"), d.get("text"), d.get("kind", "NOTE"), d.get("audience", "BROKER")
        if not isinstance(title, str) or not title.strip() or len(title) > 200:
            raise ValidationError({"title": "required, at most 200 characters"})
        if not isinstance(text, str) or not text.strip() or len(text) > settings.RAG_MAX_DOC_CHARS:
            raise ValidationError({"text": f"required, at most {settings.RAG_MAX_DOC_CHARS} characters"})
        if kind not in KINDS or audience not in AUDIENCES:
            raise ValidationError("kind or audience is not valid")
        employer_id, policy_id = d.get("employer_id"), d.get("policy_id")
        for name, v in (("employer_id", employer_id), ("policy_id", policy_id)):
            if v is not None and (not isinstance(v, int) or isinstance(v, bool)):
                raise ValidationError({name: "must be an integer"})
        if audience == "EMPLOYER" and employer_id is None:
            raise ValidationError({"employer_id": "required when audience is EMPLOYER"})
        doc_id = uuid.uuid4().hex
        base = {"broker_id": request.user.broker_id, "employer_id": employer_id, "audience": audience, "policy_id": policy_id}
        mongo.scoped(request.user, mongo.RAG_DOCS).insert_one(
            {"_id": doc_id, **base, "title": title.strip(), "kind": kind, "chars": len(text), "created_by": request.user.user_id,
             "created_at": datetime.now(UTC)})
        chunks = chunk_text(text)
        store.upsert_chunks(mongo.system_db(), [{"_id": f"doc:{doc_id}:{i}", **base, "source_type": "DOC", "source_id": doc_id,
                                                 "title": title.strip()} for i in range(len(chunks))], chunks)
        return Response({"id": doc_id, "chunks": len(chunks)}, status=201)


class KnowledgeDetailView(APIView):
    def get_permissions(self):
        return [GatewayPermission()] + ([IsBrokerAdmin()] if self.request.method == "DELETE" else [])

    def get(self, request, doc_id):
        d = mongo.scoped(request.user, mongo.RAG_DOCS).find_one({"_id": doc_id}, {"text": 0})
        if not d:
            raise NotFound()
        return Response(_doc_out(d))

    def delete(self, request, doc_id):
        res = mongo.scoped(request.user, mongo.RAG_DOCS).delete_one({"_id": doc_id})
        if not res.deleted_count:
            raise NotFound()
        mongo.scoped(request.user, mongo.RAG_CHUNKS).delete_many({"source_type": "DOC", "source_id": doc_id})
        return Response(status=204)


def _doc_out(d):
    return {"id": d["_id"], "title": d["title"], "kind": d["kind"], "audience": d["audience"], "employer_id": d.get("employer_id"),
            "policy_id": d.get("policy_id"), "chars": d["chars"], "created_at": d["created_at"].isoformat()}
