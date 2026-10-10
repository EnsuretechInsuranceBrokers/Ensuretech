import re

_SENT = re.compile(r"(?<=[.!?])\s+")


def chunk_text(text: str, target: int = 800, overlap: int = 120) -> list[str]:
    """Paragraph-first packing into ~`target` characters, with a small tail carried into the next chunk so a sentence cut at a
    boundary is still retrievable. Overlong sentences are hard-split."""
    pieces: list[str] = []
    for para in re.split(r"\n\s*\n", text.strip()):
        para = " ".join(para.split())
        if not para:
            continue
        for sent in _SENT.split(para):
            while len(sent) > target:
                pieces.append(sent[:target])
                sent = sent[target:]
            if sent:
                pieces.append(sent)
    chunks, cur = [], ""
    for p in pieces:
        if cur and len(cur) + 1 + len(p) > target:
            chunks.append(cur)
            cur = (cur[-overlap:] + " " + p) if overlap else p
        else:
            cur = f"{cur} {p}".strip()
    if cur:
        chunks.append(cur)
    return chunks
