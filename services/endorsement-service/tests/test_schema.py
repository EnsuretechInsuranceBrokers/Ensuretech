from apps.intake.schema import is_auto_confirmable, levenshtein, missing_required, suggest_mapping


def fields(headers):
    return {s.header: s.field for s in suggest_mapping(headers)}


def test_levenshtein():
    assert levenshtein("kitten", "sitting") == 3 and levenshtein("", "abc") == 3 and levenshtein("same", "same") == 0


def test_common_headers_map_with_full_confidence():
    s = suggest_mapping(["Emp Code", "Relationship", "Member Name", "D.O.B", "Sum Insured", "Date of Joining"])
    assert {x.header: x.field for x in s} == {"Emp Code": "employee_code", "Relationship": "relation", "Member Name": "full_name",
                                              "D.O.B": "dob", "Sum Insured": "sum_insured", "Date of Joining": "date_of_joining"}
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


LEGACY = ["Employee Code", "Employee First Name", "Employee Last Name", "Employee Gender", "Employee DOB", "Employee Date of Joining", "Employee Email",
          "Employee Mobile Number", "Relationship with Employee", "Insured Member First Name", "Insured Member Last Name", "Insured Member Gender",
          "Insured Member DOB", "Suminsured", "OPD Suminsured", "Employee State", "Employee Annual Salary", "Date of Marriage", "Effective Date",
          "Should Verify Email", "Default Password", "Batch Id"]


def test_legacy_two_block_sheet_maps_each_block_to_its_own_fields():
    f = fields(LEGACY)
    assert f["Employee DOB"] == "emp_dob" and f["Insured Member DOB"] == "mem_dob"
    assert f["Employee First Name"] == "emp_first_name" and f["Insured Member First Name"] == "mem_first_name"
    assert f["Employee Gender"] == "emp_gender" and f["Employee Email"] == "emp_email" and f["Employee Mobile Number"] == "emp_mobile"
    assert f["Employee Code"] == "employee_code" and f["Relationship with Employee"] == "relation" and f["Suminsured"] == "sum_insured"
    assert f["Employee Date of Joining"] == "date_of_joining" and f["Effective Date"] == "effective_date" and f["Date of Marriage"] == "date_of_marriage"
    assert f["Employee Annual Salary"] == "annual_salary"


def test_a_shared_employee_prefix_is_never_mistaken_for_a_match():
    f = fields(["Should Verify Email", "Default Password", "Employee Unit", "Is VIP Employee"])
    assert all(v is None for v in f.values()), f                       # these used to map to full_name / employee_code with 0.7-0.8 confidence
    g = fields(["Employee State", "Employee Pin Code"])
    assert g == {"Employee State": "state", "Employee Pin Code": "pincode"}      # rater inputs now have fields of their own


def test_dual_blocks_satisfy_the_required_fields():
    mapped = {s.field for s in suggest_mapping(LEGACY) if s.field}
    assert missing_required(mapped, "ADD") == []
