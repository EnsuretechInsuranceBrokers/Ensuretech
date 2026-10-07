"""CSV header negotiation: map arbitrary third-party column names onto canonical fields, with a confidence score."""
import re
from dataclasses import dataclass

# canonical field -> accepted header spellings (normalised: lower-case alphanumerics separated by single spaces)
SYNONYMS: dict[str, list[str]] = {
    "employee_code": ["employee code", "emp code", "employee id", "emp id", "empcode", "employee no", "emp no",
                      "employee number", "staff id", "personnel number", "code"],
    "relation": ["relation", "relationship", "relation with employee", "relationship with employee", "relation to employee", "rel", "member relation"],
    "first_name": ["first name", "firstname", "given name", "fname"],
    "last_name": ["last name", "lastname", "surname", "family name", "lname"],
    "full_name": ["name", "full name", "member name", "employee name", "name of member"],
    "dob": ["dob", "date of birth", "birth date", "birthdate", "d o b"],
    "gender": ["gender", "sex"],
    "pan": ["pan", "pan no", "pan number", "pan card"],
    "mobile": ["mobile", "mobile no", "mobile number", "phone", "phone number", "contact number", "contact no"],
    "email": ["email", "e mail", "email id", "email address", "mail"],
    "grade": ["grade", "band", "level", "employee grade"],
    "designation": ["designation", "title", "job title", "position"],
    "annual_salary": ["salary", "annual salary", "ctc", "annual ctc", "gross salary", "annual income"],
    "sum_insured": ["sum insured", "si", "suminsured", "cover", "coverage", "sum assured", "cover amount"],
    "effective_date": ["effective date", "eff date", "start date", "coverage start", "date of leaving", "dol", "leaving date", "removal date"],
    # Date of joining is only a fallback for the effective date (see parser): an explicit Effective Date column always wins.
    "date_of_joining": ["date of joining", "employee date of joining", "doj", "joining date"],
    "date_of_marriage": ["date of marriage", "marriage date", "dom"],
    # The legacy add-sheet has one block of columns for the employee and another for the insured member (dependant).
    # Self rows are read from the emp_* block, every other relation from the mem_* block (see parser._merge_dual).
    "emp_first_name": ["employee first name", "emp first name"],
    "emp_last_name": ["employee last name", "emp last name"],
    "emp_gender": ["employee gender"],
    "emp_dob": ["employee dob", "employee date of birth"],
    "emp_email": ["employee email", "employee email id"],
    "emp_mobile": ["employee mobile number", "employee mobile"],
    "mem_first_name": ["insured member first name", "member first name", "dependent first name"],
    "mem_last_name": ["insured member last name", "member last name", "dependent last name"],
    "mem_gender": ["insured member gender", "member gender", "dependent gender"],
    "mem_dob": ["insured member dob", "member dob", "dependent dob", "insured member date of birth"],
    "mem_email": ["insured member email", "member email"],
    "mem_mobile": ["insured member mobile number", "member mobile number"],
}
DUAL_FIELDS = {          # canonical -> (employee-side field, member-side field)
    "first_name": ("emp_first_name", "mem_first_name"), "last_name": ("emp_last_name", "mem_last_name"),
    "gender": ("emp_gender", "mem_gender"), "dob": ("emp_dob", "mem_dob"), "email": ("emp_email", "mem_email"),
    "mobile": ("emp_mobile", "mem_mobile"),
}
GENERIC_PREFIX = frozenset({"employee", "emp", "insured", "member", "dependent", "dependant"})

REQUIRED_BY_OP: dict[str, list[str]] = {
    "ADD": ["employee_code", "relation", "dob", "sum_insured"],      # a name (first_name or full_name) is checked too
    "REMOVE": ["employee_code", "relation"],
    "UPDATE": ["employee_code", "relation"],
}
AUTO_CONFIRM = 0.9
MIN_CONFIDENCE = 0.6


def norm_header(h: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ", str(h).lower())).strip()


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def similarity(a: str, b: str) -> float:
    return 1.0 - levenshtein(a, b) / max(len(a), len(b), 1)


@dataclass
class Suggestion:
    header: str
    field: str | None
    confidence: float


def _core(h: str) -> str:
    """The header without the words that only say whose value it is ("employee dob" -> "dob")."""
    return " ".join(t for t in h.split() if t not in GENERIC_PREFIX)


def _score(h: str, n: str) -> float:
    if h == n:
        return 1.0
    ch = _core(h)
    if ch != h:
        # The header names whose value it is ("employee dob"). A shared prefix is not evidence of a match ("employee dob" is not
        # "employee code"): compare the distinguishing words, and never auto-confirm on that alone.
        cn = _core(n)
        return min(similarity(ch, cn), 0.85) if ch and cn else 0.0
    return similarity(h, n)                # no prefix word: plain fuzzy match, so typos like "Emplyee Code" still map


def _best(header: str) -> tuple[str | None, float]:
    h = norm_header(header)
    best_field, best = None, 0.0
    for field, names in SYNONYMS.items():
        for n in names:
            s = _score(h, n)
            if len(h) <= 3 and h != n:      # tiny strings ("si", "rel", "dob") are only trusted on exact match
                s = min(s, 0.5)
            if s > best:
                best_field, best = field, s
    return (best_field, best) if best >= MIN_CONFIDENCE else (None, best)


def suggest_mapping(headers: list[str]) -> list[Suggestion]:
    """Each canonical field is claimed by at most one header (the most confident); the rest stay unmapped."""
    raw = [(h, *_best(h)) for h in headers]
    winners: dict[str, tuple[int, float]] = {}
    for i, (_, field, conf) in enumerate(raw):
        if field and (field not in winners or conf > winners[field][1]):
            winners[field] = (i, conf)
    return [Suggestion(h, field if field and winners[field][0] == i else None, round(conf, 3))
            for i, (h, field, conf) in enumerate(raw)]


def missing_required(mapped_fields: set[str], op_type: str) -> list[str]:
    covered = set(mapped_fields)
    for canon, sides in DUAL_FIELDS.items():
        if covered & set(sides):                   # a legacy two-block sheet supplies the canonical field per row
            covered.add(canon)
    missing = [f for f in REQUIRED_BY_OP.get(op_type, []) if f not in covered]
    if op_type == "ADD" and not ({"first_name", "full_name"} & covered):
        missing.append("first_name")
    return missing


def is_auto_confirmable(suggestions: list[Suggestion], op_type: str) -> bool:
    mapped = {s.field for s in suggestions if s.field}
    required_ok = not missing_required(mapped, op_type)
    return required_ok and all(s.confidence >= AUTO_CONFIRM for s in suggestions if s.field)
