package com.broker.sheetgen.gen;

import java.text.Normalizer;
import java.util.*;

/** One sheet column. {@code id} is what code refers to, {@code header} is the legacy label written to the file. */
public record Column(String id, String header) {

    public static final String EMP_CODE = "emp_code", EMP_FIRST = "emp_first", EMP_LAST = "emp_last", EMP_GENDER = "emp_gender", EMP_DOB = "emp_dob",
            EMP_DOJ = "emp_doj", EMP_EMAIL = "emp_email", EMP_MOBILE = "emp_mobile", RELATION = "relation",
            MEM_FIRST = "mem_first", MEM_LAST = "mem_last", MEM_GENDER = "mem_gender", MEM_DOB = "mem_dob", DOM = "dom", EFFECTIVE = "eff",
            SUM_INSURED = "si", GRADE = "grade", DESIGNATION = "designation", SALARY = "salary", FLEX = "flex", STATE = "state", CITY = "city",
            EMP_PREMIUM = "emp_prem", ER_PREMIUM = "er_prem";

    /** Every column this service can write, in file order. */
    public static final List<Column> ALL = List.of(
            new Column(EMP_CODE, "Employee Code"), new Column(EMP_FIRST, "Employee First Name"), new Column(EMP_LAST, "Employee Last Name"),
            new Column(EMP_GENDER, "Employee Gender"), new Column(EMP_DOB, "Employee DOB"), new Column(EMP_DOJ, "Employee Date of Joining"),
            new Column(EMP_EMAIL, "Employee Email"), new Column(EMP_MOBILE, "Employee Mobile Number"),
            new Column(RELATION, "Relationship with Employee"),
            new Column(MEM_FIRST, "Insured Member First Name"), new Column(MEM_LAST, "Insured Member Last Name"),
            new Column(MEM_GENDER, "Insured Member Gender"), new Column(MEM_DOB, "Insured Member DOB"),
            new Column(DOM, "Date of Marriage"), new Column(EFFECTIVE, "Effective Date"), new Column(SUM_INSURED, "Sum Insured"),
            new Column(GRADE, "Employee Grade"), new Column(DESIGNATION, "Employee Designation"), new Column(SALARY, "Employee Annual Salary"),
            new Column(FLEX, "Flex Plan"), new Column(STATE, "Employee State"), new Column(CITY, "Employee City"),
            new Column(EMP_PREMIUM, "Employee Premium"), new Column(ER_PREMIUM, "Employer Premium"));

    private static final Map<String, String> BY_HEADER = byHeader();

    /** Header spellings (normalised) accepted when a sheet is read back for validation or derivation. */
    private static Map<String, String> byHeader() {
        Map<String, String> m = new HashMap<>();
        for (Column c : ALL) m.put(norm(c.header()), c.id());
        for (String[] a : new String[][]{
                {"empcode", EMP_CODE}, {"employeecode", EMP_CODE}, {"employeeid", EMP_CODE}, {"relation", RELATION}, {"relationship", RELATION},
                {"employeedateofbirth", EMP_DOB}, {"dateofbirth", EMP_DOB}, {"insuredmemberdateofbirth", MEM_DOB},
                {"dateofjoining", EMP_DOJ}, {"doj", EMP_DOJ}, {"dom", DOM}, {"suminsured", SUM_INSURED}, {"grade", GRADE},
                {"designation", DESIGNATION}, {"annualsalary", SALARY}, {"employeemobile", EMP_MOBILE}, {"employeeemailid", EMP_EMAIL}}) {
            m.putIfAbsent(a[0], a[1]);
        }
        return m;
    }

    public static String norm(String header) {
        String n = Normalizer.normalize(header == null ? "" : header, Normalizer.Form.NFKC);
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The column id for a header as found in a sheet, or null when it is not one of ours. */
    public static String idForHeader(String header) { return BY_HEADER.get(norm(header)); }

    public static String headerOf(String id) {
        return ALL.stream().filter(c -> c.id().equals(id)).map(Column::header).findFirst().orElseThrow();
    }
}
