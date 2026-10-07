"""Text -> vector. The default is a hashing bag-of-words embedder: deterministic, dependency-free, lexical only.
It finds passages that share words with the question ("why was job X blocked" ~ "moved to CD_INSUFFICIENT"), not passages that
merely mean the same thing. For semantic retrieval, point settings.EMBEDDER at a class with the same `embed()` that calls a real
embedding model. Vectors are stored with the chunk, so changing the embedder means re-embedding (`manage.py reembed`)."""
import hashlib
import math
import re
from collections import Counter

import numpy as np

_TOKEN = re.compile(r"[a-z0-9_]+")
_STOP = frozenset("a an and are as at be been by for from has have how i in is it its of on or that the this to was were what when where which who why will with do does did my our your".split())


def _stem(t: str) -> str:
    for suf in ("ing", "ed", "es", "s"):
        if len(t) > len(suf) + 3 and t.endswith(suf):
            return t[: -len(suf)]
    return t


def tokens(text: str) -> list[str]:
    return [_stem(t) for t in _TOKEN.findall(text.lower()) if t not in _STOP]


class HashingEmbedder:
    name = "hashing-v1"

    def __init__(self, dim: int = 384):
        self.dim = dim

    def _bucket(self, feature: str) -> tuple[int, float]:
        h = int.from_bytes(hashlib.blake2b(feature.encode(), digest_size=8).digest(), "big")
        return h % self.dim, 1.0 if (h >> 63) & 1 else -1.0           # signed hashing keeps collisions unbiased

    def embed(self, texts: list[str]) -> np.ndarray:
        out = np.zeros((len(texts), self.dim), dtype=np.float32)
        for i, text in enumerate(texts):
            toks = tokens(text)
            feats = Counter(toks)
            bigrams = Counter(f"{a} {b}" for a, b in zip(toks, toks[1:]))
            for feat, n in feats.items():
                j, s = self._bucket(feat)
                out[i, j] += s * (1 + math.log(n))
            for feat, n in bigrams.items():
                j, s = self._bucket(feat)
                out[i, j] += 0.5 * s * (1 + math.log(n))
            norm = float(np.linalg.norm(out[i]))
            if norm:
                out[i] /= norm
        return out
