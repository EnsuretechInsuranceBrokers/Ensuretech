"""Chunk storage and tenant-scoped retrieval. Vectors live beside the text in `rag_chunks`; scoring happens in-process with numpy
over the caller's *tenant-filtered* candidates only. That is exact and simple but linear in the tenant's chunk count, so it is capped
(RAG_MAX_CANDIDATES) and reports when it truncated; at larger scale move the scoring into a vector index (Atlas Vector Search,
pgvector) keeping the same tenant filter as a pre-filter."""
from datetime import UTC, datetime

import numpy as np
from django.conf import settings
from django.utils.module_loading import import_string

from apps.core import mongo


def embedder():
    return import_string(settings.EMBEDDER)()


def upsert_chunks(db, chunks: list[dict], texts: list[str]):
    """chunks: dicts with _id and tenant fields; the vector is added here. Idempotent on _id."""
    if not chunks:
        return
    emb = embedder()
    name = getattr(emb, "name", "custom")
    for c, t, v in zip(chunks, texts, emb.embed(texts)):
        doc = {**c, "text": t, "vec": v.tolist(), "embedder": name, "created_at": datetime.now(UTC)}
        db[mongo.RAG_CHUNKS].replace_one({"_id": c["_id"]}, doc, upsert=True)


def retrieve(principal, question: str, *, policy_id: int | None = None, top_k: int | None = None) -> dict:
    top_k = top_k or settings.RAG_TOP_K
    q = embedder().embed([question])[0]
    coll = mongo.scoped(principal, mongo.RAG_CHUNKS)
    flt = {"policy_id": policy_id} if policy_id is not None else None
    cands = list(coll.find(flt, {"vec": 1, "text": 1, "title": 1, "source_type": 1, "source_id": 1, "policy_id": 1},
                           limit=settings.RAG_MAX_CANDIDATES + 1))
    truncated = len(cands) > settings.RAG_MAX_CANDIDATES
    cands = cands[: settings.RAG_MAX_CANDIDATES]
    if not cands:
        return {"hits": [], "truncated": False, "candidates": 0}
    scores = np.asarray([c["vec"] for c in cands], dtype=np.float32) @ q
    order = np.argsort(-scores)[:top_k]
    hits = [{"chunk_id": cands[i]["_id"], "score": round(float(scores[i]), 4), "title": cands[i].get("title"), "source_type": cands[i]["source_type"],
             "source_id": cands[i]["source_id"], "policy_id": cands[i].get("policy_id"), "text": cands[i]["text"]}
            for i in order if scores[i] >= settings.RAG_MIN_SCORE]
    return {"hits": hits, "truncated": truncated, "candidates": len(cands)}
