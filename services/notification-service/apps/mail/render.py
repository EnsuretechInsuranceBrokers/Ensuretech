"""Placeholder substitution only: `{{name}}` from a fixed list. No expressions, no logic, no template engine, so a broker-authored
template cannot execute anything. Values are HTML-escaped in bodies; subjects have control characters removed (header injection)."""
import html
import re

PLACEHOLDER = re.compile(r"\{\{\s*([a-z_]+)\s*\}\}")
_CTRL = re.compile(r"[\x00-\x1f\x7f]+")
_DANGEROUS = re.compile(r"<\s*(script|iframe|object|embed|form|meta|link|base)\b|javascript:|\bon[a-z]+\s*=", re.I)


class TemplateError(ValueError):
    pass


def placeholders_in(text: str) -> set[str]:
    return set(PLACEHOLDER.findall(text))


def validate(subject: str, body: str, allowed) -> None:
    unknown = (placeholders_in(subject) | placeholders_in(body)) - set(allowed)
    if unknown:
        raise TemplateError(f"unknown placeholder(s): {', '.join(sorted(unknown))}")
    leftover = PLACEHOLDER.sub("", subject + body)
    if "{{" in leftover or "{%" in leftover:
        raise TemplateError("malformed placeholder: use {{name}} with lower-case letters and underscores only; expressions are not supported")
    if _DANGEROUS.search(body) or _DANGEROUS.search(subject):
        raise TemplateError("scripts, forms, frames and event handlers are not allowed in mail templates")
    if re.search(r"[\r\n]", subject):
        raise TemplateError("subject must be a single line")


def _fill(text: str, ctx: dict, escape) -> str:
    return PLACEHOLDER.sub(lambda m: escape(str(ctx.get(m.group(1), ""))), text)


def render_subject(subject: str, ctx: dict) -> str:
    return _CTRL.sub(" ", _fill(subject, ctx, lambda v: v)).strip()[:300]


def render_body(body: str, ctx: dict) -> str:
    return _fill(body, ctx, html.escape)


def to_text(body_html: str) -> str:
    text = re.sub(r"<\s*(br|/p|/div|/li|/h\d)\s*/?>", "\n", body_html, flags=re.I)
    return html.unescape(re.sub(r"<[^>]+>", "", text)).strip()
