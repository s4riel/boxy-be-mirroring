package com.boxy.boxy.modules.dashboard.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Resolves periods exactly like the frontend's resolvePeriodPreset, so the header and the numbers agree. */
class ReportFilterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private ReportFilter of(String preset) {
        return ReportFilter.of(preset, null, null, null, null, TODAY);
    }

    @Test
    void presetsResolveRelativeToToday() {
        assertThat(of("today").from()).isEqualTo(TODAY);
        assertThat(of("last-7-days").from()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(of("last-30-days").from()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(of("this-month").from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(of("last-month").from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(of("last-month").to()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(of("this-quarter").from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(of("this-year").from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(of("last-6-months").from()).isEqualTo(LocalDate.of(2026, 5, 1));
    }

    @Test
    void anUnknownOrMissingPresetFallsBackToTheLast30Days() {
        assertThat(of(null).from()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(of("nonsense").to()).isEqualTo(TODAY);
    }

    @Test
    void customRangeIsUsedAndSwappedWhenReversed() {
        ReportFilter filter = ReportFilter.of("custom", "2026-10-05", "2026-10-01", null, null, TODAY);

        assertThat(filter.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(filter.to()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void branchIdsAreParsedAndAnEmptyListMeansAllBranches() {
        assertThat(ReportFilter.of(null, null, null, "3,5, x", null, TODAY).branchIds()).containsExactly(3L, 5L);
        ReportFilter all = ReportFilter.of(null, null, null, "", null, TODAY);
        assertThat(all.allBranches()).isTrue();
        assertThat(all.branchIdsParam()).isNotEmpty();
    }

    @Test
    void weeksStartOnMonday() {
        ReportFilter weekly = ReportFilter.of(null, null, null, null, "week", TODAY);

        assertThat(weekly.bucketStart(LocalDate.of(2026, 10, 8))).isEqualTo(LocalDate.of(2026, 10, 5));
    }
}
