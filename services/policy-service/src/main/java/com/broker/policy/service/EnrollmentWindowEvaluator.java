package com.broker.policy.service;

import java.time.LocalDate;

/**
 * Decides whether an employee may enrol in a policy today. A port of the legacy EnrollmentWindowService for base policies (parent/top-up policies are not
 * modelled). Pure: no database, no clock of its own. The numbered comments match the legacy log codes so a result can be traced back to its rule.
 */
public final class EnrollmentWindowEvaluator {
    public enum Status { CLOSED, OPEN, CONFIRMED, CONFIRMED_BUT_NOT_OPTED }

    public record Settings(boolean enabled, LocalDate start, LocalDate end, int midtermDays, boolean allowAfterExpiry, int daysAfterExpiry) {}
    /** An employee-level (custom) window. closeDate, when set, shuts the window for good after that day. */
    public record CustomWindow(boolean enabled, LocalDate start, LocalDate end, LocalDate closeDate) {}
    /** Dates frozen when the employee confirmed. */
    public record Confirmation(java.time.Instant at, LocalDate start, LocalDate end) {}
    public record Employee(LocalDate dateOfJoining, LocalDate dateOfUpload, boolean hasMembers, boolean inception, CustomWindow override, Confirmation confirmation) {}

    public record Result(Status status, LocalDate start, LocalDate end, java.time.Instant confirmedAt, boolean windowSet, boolean midtermWindowSet,
                         boolean endtermWindowSet, boolean customWindowSet, LocalDate closeDate) {}

    /** mutable working copy, as in the legacy DTO */
    private static final class W {
        Status status = Status.CLOSED; LocalDate start, end, closeDate; java.time.Instant confirmedAt;
        boolean window = true, midterm = true, endterm = false, custom = false;
    }

    private final Settings s; private final LocalDate policyStart, policyEnd; private final String basis; private final LocalDate today;

    public EnrollmentWindowEvaluator(Settings s, LocalDate policyStart, LocalDate policyEnd, String midtermBasis, LocalDate today) {
        this.s = s; this.policyStart = policyStart; this.policyEnd = policyEnd; this.basis = midtermBasis; this.today = today;
    }

    public Result evaluate(Employee e) {
        W w = new W();
        if (e.override() != null) w.closeDate = e.override().closeDate();
        Confirmation c = e.confirmation();

        if (c != null && !e.hasMembers()) {                                       // 100: confirmed, but never opted in to anything
            w.status = Status.CONFIRMED_BUT_NOT_OPTED;
            return result(w);
        }
        if (c != null) {                                                          // 302: confirmed -> frozen dates
            w.status = Status.CONFIRMED; w.confirmedAt = c.at(); w.start = c.start(); w.end = c.end();
        } else {
            policyLevel(w, e);                                                    // 311
        }

        CustomWindow o = e.override();
        if (o != null && o.enabled()) {                                           // 321: custom window; the LATER end date wins, so a custom window can extend but never shorten
            w.custom = true;
            LocalDate policyWideEnd = w.end != null ? w.end : LocalDate.of(1900, 1, 1);
            if (o.end() != null && !o.end().isBefore(policyWideEnd)) {            // 322
                if (c != null) {
                    w.status = Status.CONFIRMED; w.confirmedAt = c.at(); w.start = c.start(); w.end = c.end();   // 323
                } else {
                    w.start = o.start(); w.end = o.end();
                    if (o.closeDate() != null && today.isAfter(o.closeDate())) w.status = Status.CLOSED;
                    else if (!today.isBefore(o.start()) && !today.isAfter(o.end())) w.status = Status.OPEN;    // 331
                    else w.status = Status.CLOSED;                                                              // 332
                }
            }
        }
        endTerm(w, e);                                                            // 401
        return result(w);
    }

    private void policyLevel(W w, Employee e) {
        w.status = Status.CLOSED; w.window = true; w.midterm = true;
        boolean needMidterm = false;
        boolean midtermAllowed = !"NO_MIDTERM".equals(basis);

        if (s.enabled() && s.start() != null && s.end() != null) {                 // 501
            boolean within = !today.isBefore(s.start()) && !today.isAfter(s.end());
            if (w.closeDate != null && today.isAfter(w.closeDate)) {
                w.status = Status.CLOSED; w.start = s.start(); w.end = s.end();
            } else if (today.isBefore(policyStart) || within) {                    // 511
                w.status = Status.OPEN; w.start = s.start(); w.end = s.end();
                if (today.isBefore(policyStart) && !within) w.status = Status.CLOSED;     // 521: pre-enrolment, window not yet open
            } else {                                                               // 531
                w.status = Status.CLOSED; w.start = s.start(); w.end = s.end();
                if ("DATE_OF_JOINING".equals(basis) && e.dateOfJoining() != null) {        // 541
                    if (e.dateOfJoining().isAfter(policyStart) && e.dateOfJoining().isAfter(s.end())) needMidterm = true;
                } else if ("DATE_OF_UPLOAD".equals(basis) && e.dateOfUpload() != null) {   // 551
                    if (e.dateOfUpload().isAfter(policyStart) && e.dateOfUpload().isAfter(s.end())) needMidterm = true;
                }
            }
        } else {
            w.window = false;                                                      // 599: no policy window given
        }
        if (!midtermAllowed) { w.midterm = false; needMidterm = false; }           // 599: NO_MIDTERM
        if (!s.enabled() || s.start() == null || s.end() == null) {
            if ("DATE_OF_JOINING".equals(basis) && !e.inception() && e.dateOfJoining() != null) needMidterm = true;   // 601
            else if ("DATE_OF_UPLOAD".equals(basis) && !e.inception()) needMidterm = true;                              // 611
            if (e.inception()) w.midterm = false;                                  // 621: inception members never get a mid-term window
        }
        if (e.dateOfJoining() != null && e.dateOfJoining().isBefore(policyStart)) { needMidterm = false; w.midterm = false; }   // 651: joined before the policy began
        if (needMidterm) midterm(w, e);                                            // 701
    }

    private void midterm(W w, Employee e) {
        LocalDate from;
        if ("DATE_OF_JOINING".equals(basis)) from = e.dateOfJoining();             // 711
        else if ("DATE_OF_UPLOAD".equals(basis)) from = e.dateOfUpload();          // 751
        else return;
        if (from == null) return;
        LocalDate until = from.plusDays(s.midtermDays() - 1L);
        w.start = from; w.end = until;
        if (w.closeDate != null && today.isAfter(w.closeDate)) w.status = Status.CLOSED;
        else if ("DATE_OF_JOINING".equals(basis)) w.status = !until.isBefore(today) && !from.isAfter(today) ? Status.OPEN : Status.CLOSED;   // 721 / 731
        else w.status = !until.isBefore(today) ? Status.OPEN : Status.CLOSED;                                                                   // 761 / 771
    }

    private void endTerm(W w, Employee e) {
        if (w.end == null || !w.end.isAfter(policyEnd)) return;                    // 401/411: only a window that runs past the policy end
        if (w.midterm && !w.custom && !s.allowAfterExpiry()) {                     // 419
            if (w.status == Status.OPEN) {
                if (today.isAfter(policyEnd)) w.status = Status.CLOSED; else w.endterm = true;
            }
        } else if (w.midterm && s.allowAfterExpiry() && w.status != Status.CONFIRMED) {   // 431: enrolment allowed for N days after the policy expires
            LocalDate upload = e.dateOfUpload() != null ? e.dateOfUpload() : policyEnd;
            LocalDate base = upload.isAfter(policyEnd) ? upload : policyEnd;
            LocalDate validity = base.plusDays(s.daysAfterExpiry());
            if (w.end.isAfter(validity)) { w.status = !today.isAfter(validity) ? Status.OPEN : Status.CLOSED; w.end = validity; }
            else { w.status = !today.isAfter(w.end) ? Status.OPEN : Status.CLOSED; }
        } else {                                                                   // 421: cut the window at the policy end
            w.end = policyEnd;
            if (w.status == Status.OPEN && today.isAfter(policyEnd)) w.status = Status.CLOSED;
        }
    }

    private static Result result(W w) {
        return new Result(w.status, w.start, w.end, w.confirmedAt, w.window, w.midterm, w.endterm, w.custom, w.closeDate);
    }
}
