package com.boxy.boxy.modules.dashboard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A report table to turn into a file: the screen already holds exactly what the user is looking at
 * (filters applied, totals computed), so it sends that and the server only lays it out as Excel or
 * PDF — one export path for every report instead of one per report.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportExportRequest {

    @NotBlank
    @Size(max = 150)
    private String title;

    /** Human description of the filters in effect ("Últimos 30 días · Sucursal Central"). */
    @Size(max = 300)
    private String subtitle;

    @NotEmpty
    @Size(max = 40)
    @Valid
    private List<Column> columns;

    /** Each row has one cell per column — a string or a number (numbers stay numeric in Excel). */
    @NotNull
    @Size(max = 20000)
    private List<List<Object>> rows;

    /** Optional totals row, same shape as a row. */
    private List<Object> footer;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Column {
        @NotBlank
        @Size(max = 100)
        private String header;
        /** {@code text} (default), {@code number}, {@code currency} or {@code percent} (a fraction 0–1). */
        private String kind;
    }
}
