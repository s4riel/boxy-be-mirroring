package com.boxy.boxy.modules.dashboard.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;

/**
 * What a report screen asks for: a period, optionally limited to some branches, bucketed by day /
 * week / month. The screen sends a preset ("last-30-days") or an explicit custom range; this resolves
 * it to concrete dates the same way the frontend's {@code resolvePeriodPreset} does, so the number a
 * user sees and the period the header says always agree.
 */
public record ReportFilter(LocalDate from, LocalDate to, List<Long> branchIds, String granularity) {

    /** Company-local calendar (no DST). Used for "today" and to turn dates into instants. */
    public static final ZoneId ZONE = ZoneId.of("America/La_Paz");

    /** A branch id that matches nothing — lets JPQL keep one {@code IN :ids} clause when no branch is chosen. */
    private static final List<Long> NO_BRANCH = List.of(-1L);

    public static ReportFilter of(String preset, String from, String to, String branch, String granularity) {
        return of(preset, from, to, branch, granularity, LocalDate.now(ZONE));
    }

    static ReportFilter of(String preset, String from, String to, String branch, String granularity, LocalDate today) {
        LocalDate start;
        LocalDate end;
        String p = preset == null ? "last-30-days" : preset;
        switch (p) {
            case "today" -> { start = today; end = today; }
            case "yesterday" -> { start = today.minusDays(1); end = start; }
            case "last-7-days" -> { start = today.minusDays(6); end = today; }
            case "last-6-months" -> { start = today.minusMonths(5).withDayOfMonth(1); end = today; }
            case "this-month" -> { start = today.withDayOfMonth(1); end = today; }
            case "last-month" -> {
                LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);
                start = lastMonthEnd.withDayOfMonth(1);
                end = lastMonthEnd;
            }
            case "this-quarter" -> {
                start = today.withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
                end = today;
            }
            case "this-year" -> { start = today.withDayOfYear(1); end = today; }
            case "custom" -> {
                start = parse(from, today.minusDays(29));
                end = parse(to, today);
            }
            default -> { start = today.minusDays(29); end = today; }
        }
        if (end.isBefore(start)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        List<Long> ids = branch == null || branch.isBlank() ? List.of() : Arrays.stream(branch.split(","))
                .map(String::trim)
                .map(ReportFilter::parseId)
                .filter(id -> id != null)
                .toList();
        String g = "week".equals(granularity) || "month".equals(granularity) ? granularity : "day";
        return new ReportFilter(start, end, ids, g);
    }

    public boolean allBranches() {
        return branchIds.isEmpty();
    }

    /** Bound for a JPQL {@code IN :branchIds}; never empty. */
    public List<Long> branchIdsParam() {
        return allBranches() ? NO_BRANCH : branchIds;
    }

    public java.time.Instant fromInstant() {
        return from.atStartOfDay(ZONE).toInstant();
    }

    /** Exclusive upper bound: the start of the day after {@code to}. */
    public java.time.Instant toInstantExclusive() {
        return to.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    /** The first day of the bucket {@code date} falls in under this filter's granularity. */
    public LocalDate bucketStart(LocalDate date) {
        return switch (granularity) {
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    private static LocalDate parse(String value, LocalDate fallback) {
        try {
            return value == null || value.isBlank() ? fallback : LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return fallback;
        }
    }

    private static Long parseId(String raw) {
        try {
            return Long.parseLong(raw.replace("branch-", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
