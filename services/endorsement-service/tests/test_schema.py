from apps.intake.schema import is_auto_confirmable, levenshtein, missing_required, suggest_mapping


def fields(headers):
    return {s.header: s.field for s in suggest_mapping(headers)}


def test_levenshtein():
    assert levenshtein("kitten", "sitting") == 3 and levenshtein("", "abc") == 3 and levenshtein("same", "same") == 0


def test_common_headers_map_with_full_confidence():
    s = suggest_mapping(["Emp Code", "Relationship", "Member Name", "D.O.B", "Sum Insured", "Date of Joining"])
    assert {x.header: x.field for x in s} == {"Emp Code": "employee_code", "Relationship": "relation", "Member Name": "full_name",
                                              "D.O.B": "dob", "Sum Insured": "sum_insured", "Date of Joining": "effective_date"}
    assert all(x.confidence == 1.0 for x in s)


def test_typos_map_with_reduced_confidence_and_need_confirmation():
    s = {x.header: x for x in suggest_mapping(["Emplyee Code", "Relatonship", "Sum Insurd", "DOB", "Name"])}
    assert s["Emplyee Code"].field == "employee_code" and s["Emplyee Code"].confidence < 1.0
    assert s["Sum Insurd"].field == "sum_insured"
    assert not is_auto_confirmable(list(s.values()), "REMOVE") or s["Emplyee Code"].confidence >= 0.9


def test_junk_headers_stay_unmapped_and_a_field_is_claimed_once():
    f = fields(["Remarks", "Emp Code", "Employee Code"])
    assert f["Remarks"] is None
    assert list(f.values()).count("employee_code") == 1


def test_short_strings_need_an_exact_match():
    assert fields(["si"])["si"] == "sum_insured"        # exact synonym
    assert fields(["sx"])["sx"] is None                 # one edit away from "si" is not enough for 2 letters


def test_required_fields_per_operation():
    assert missing_required({"employee_code", "relation"}, "REMOVE") == []
    assert set(missing_required({"employee_code"}, "REMOVE")) == {"relation"}
    assert "first_name" in missing_required({"employee_code", "relation", "dob", "sum_insured"}, "ADD")
    assert missing_required({"employee_code", "relation", "dob", "sum_insured", "full_name"}, "ADD") == []


def test_auto_confirm_requires_all_required_fields_and_high_confidence():
    good = suggest_mapping(["Emp Code", "Relationship", "Name", "DOB", "Sum Insured"])
    assert is_auto_confirmable(good, "ADD")
    assert not is_auto_confirmable(suggest_mapping(["Emp Code", "Name"]), "ADD")
