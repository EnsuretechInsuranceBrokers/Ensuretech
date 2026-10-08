package com.broker.policy;

import static org.junit.jupiter.api.Assertions.*;

import com.broker.policy.service.EnrollmentWindowEvaluator;
import com.broker.policy.service.EnrollmentWindowEvaluator.*;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The legacy rules, one case each. The policy runs 2030-04-01 .. 2031-03-31 and its enrolment window 2030-03-01 .. 2030-04-30. */
class EnrollmentWindowEvaluatorTest {
    static final LocalDate PS = LocalDate.of(2030, 4, 1), PE = LocalDate.of(2031, 3, 31);
    static final Settings WINDOW = new Settings(true, LocalDate.of(2030, 3, 1), LocalDate.of(2030, 4, 30), 30, false, 0);
    static final Settings NO_WINDOW = new Settings(false, null, null, 30, false, 0);

    static LocalDate d(String s) { return LocalDate.parse(s); }

    static Result eval(Settings s, String basis, String today, Employee e) {
        return new EnrollmentWindowEvaluator(s, PS, PE, basis, d(today)).evaluate(e);
    }

    static Employee emp(String doj, String dou) { return new Employee(doj == null ? null : d(doj), dou == null ? null : d(dou), true, false, null, null); }

    // ── the policy window ─────────────────────────────────────────────────────────────────────────────────────
    @Test void insideThePolicyWindowIsOpen() {
        Result r = eval(WINDOW, "DATE_OF_JOINING", "2030-04-10", emp("2030-04-02", "2030-04-02"));
        assertEquals(Status.OPEN, r.status()); assertEquals(d("2030-04-30"), r.end()); assertTrue(r.windowSet());
    }
    @Test void beforeTheWindowButAfterPolicyStartIsClosedNotOpen() {
        // today before the window opens, policy not yet started: the legacy "pre enrolment" rule keeps it closed
        assertEquals(Status.CLOSED, eval(WINDOW, "DATE_OF_JOINING", "2030-02-15", emp("2030-04-02", "2030-02-15")).status());
    }
    @Test void aCloseDateShutsTheWindowEvenInsideIt() {
        Employee e = new Employee(d("2030-04-02"), d("2030-04-02"), true, false, new CustomWindow(false, d("2030-04-01"), d("2030-04-30"), d("2030-04-05")), null);
        assertEquals(Status.CLOSED, eval(WINDOW, "NO_MIDTERM", "2030-04-10", e).status());
    }
    @Test void noMidtermClosesAfterTheWindow() {
        Result r = eval(WINDOW, "NO_MIDTERM", "2030-06-01", emp("2030-05-20", "2030-05-20"));
        assertEquals(Status.CLOSED, r.status()); assertFalse(r.midtermWindowSet());
    }

    // ── mid-term ──────────────────────────────────────────────────────────────────────────────────────────────
    @Test void midtermFromDateOfJoiningLastsTheConfiguredDays() {
        Employee e = emp("2030-06-01", "2030-06-01");
        Result open = eval(WINDOW, "DATE_OF_JOINING", "2030-06-30", e);               // 30 days: 06-01 .. 06-30 inclusive
        assertEquals(Status.OPEN, open.status()); assertEquals(d("2030-06-30"), open.end());
        assertEquals(Status.CLOSED, eval(WINDOW, "DATE_OF_JOINING", "2030-07-01", e).status());
    }
    @Test void midtermFromJoiningDoesNotOpenBeforeTheJoiningDate() {
        assertEquals(Status.CLOSED, eval(WINDOW, "DATE_OF_JOINING", "2030-05-20", emp("2030-06-01", "2030-05-20")).status());
    }
    @Test void midtermFromDateOfUploadCountsFromTheUpload() {
        Employee e = emp("2030-05-10", "2030-06-15");
        assertEquals(Status.OPEN, eval(WINDOW, "DATE_OF_UPLOAD", "2030-07-14", e).status());
        assertEquals(Status.CLOSED, eval(WINDOW, "DATE_OF_UPLOAD", "2030-07-15", e).status());
    }
    @Test void joiningBeforeThePolicyStartedNeverGetsAMidtermWindow() {
        Result r = eval(NO_WINDOW, "DATE_OF_JOINING", "2030-06-05", emp("2030-03-15", "2030-06-05"));
        assertEquals(Status.CLOSED, r.status()); assertFalse(r.midtermWindowSet());
    }
    @Test void inceptionMembersGetNoMidtermWindowWhenThereIsNoPolicyWindow() {
        Employee e = new Employee(d("2030-06-01"), d("2030-06-01"), true, true, null, null);
        assertFalse(eval(NO_WINDOW, "DATE_OF_JOINING", "2030-06-10", e).midtermWindowSet());
    }
    @Test void withoutAPolicyWindowMidtermIsTheOnlyWay() {
        Employee e = emp("2030-06-01", "2030-06-01");
        Result r = eval(NO_WINDOW, "DATE_OF_JOINING", "2030-06-10", e);
        assertEquals(Status.OPEN, r.status()); assertFalse(r.windowSet());
    }

    // ── employee-level (custom) windows ───────────────────────────────────────────────────────────────────────
    @Test void aLaterCustomWindowExtendsThePolicyWindow() {
        Employee e = new Employee(d("2030-04-02"), d("2030-04-02"), true, false, new CustomWindow(true, d("2030-05-01"), d("2030-05-20"), null), null);
        Result r = eval(WINDOW, "NO_MIDTERM", "2030-05-10", e);
        assertEquals(Status.OPEN, r.status()); assertEquals(d("2030-05-20"), r.end()); assertTrue(r.customWindowSet());
    }
    @Test void aCustomWindowCanNeverShortenThePolicyWindow() {
        Employee e = new Employee(d("2030-04-02"), d("2030-04-02"), true, false, new CustomWindow(true, d("2030-04-01"), d("2030-04-10"), null), null);
        Result r = eval(WINDOW, "NO_MIDTERM", "2030-04-20", e);                         // the policy window (to 04-30) still governs
        assertEquals(Status.OPEN, r.status()); assertEquals(d("2030-04-30"), r.end());
    }
    @Test void aCustomWindowsCloseDateWins() {
        Employee e = new Employee(d("2030-04-02"), d("2030-04-02"), true, false, new CustomWindow(true, d("2030-05-01"), d("2030-05-20"), d("2030-05-05")), null);
        assertEquals(Status.CLOSED, eval(WINDOW, "NO_MIDTERM", "2030-05-10", e).status());
    }

    // ── confirmation ──────────────────────────────────────────────────────────────────────────────────────────
    @Test void aConfirmedEmployeeIsFrozenAtTheirDates() {
        Confirmation c = new Confirmation(Instant.parse("2030-04-12T10:00:00Z"), d("2030-03-01"), d("2030-04-30"));
        Employee e = new Employee(d("2030-04-02"), d("2030-04-02"), true, false, null, c);
        Result r = eval(new Settings(true, d("2030-03-01"), d("2030-12-31"), 30, false, 0), "DATE_OF_JOINING", "2030-04-15", e);   // the policy window was widened later
        assertEquals(Status.CONFIRMED, r.status()); assertEquals(d("2030-04-30"), r.end()); assertEquals(c.at(), r.confirmedAt());
    }
    @Test void confirmedWithNoMembersIsConfirmedButNotOpted() {
        Confirmation c = new Confirmation(Instant.parse("2030-04-12T10:00:00Z"), d("2030-03-01"), d("2030-04-30"));
        assertEquals(Status.CONFIRMED_BUT_NOT_OPTED, eval(WINDOW, "NO_MIDTERM", "2030-04-15", new Employee(d("2030-04-02"), d("2030-04-02"), false, false, null, c)).status());
    }

    // ── a window that runs past the end of the policy ─────────────────────────────────────────────────────────
    @Test void aWindowPastThePolicyEndIsCutAtThePolicyEnd() {
        Settings late = new Settings(true, d("2030-03-01"), d("2031-06-30"), 30, false, 0);
        Result r = eval(late, "NO_MIDTERM", "2031-03-31", emp("2030-04-02", "2030-04-02"));
        assertEquals(PE, r.end()); assertEquals(Status.OPEN, r.status());
        assertEquals(Status.CLOSED, eval(late, "NO_MIDTERM", "2031-04-01", emp("2030-04-02", "2030-04-02")).status());
    }
    @Test void enrolmentAfterExpiryIsAllowedForTheConfiguredDays() {
        Settings after = new Settings(true, d("2030-03-01"), d("2031-06-30"), 30, true, 20);
        Employee e = emp("2030-04-02", "2030-04-02");
        Result open = eval(after, "DATE_OF_JOINING", "2031-04-15", e);                    // policy end + 20 days = 2031-04-20
        assertEquals(Status.OPEN, open.status()); assertEquals(d("2031-04-20"), open.end());
        assertEquals(Status.CLOSED, eval(after, "DATE_OF_JOINING", "2031-04-21", e).status());
    }
}
