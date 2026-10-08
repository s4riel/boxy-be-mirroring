package com.boxy.boxy.modules.dashboard.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PageParamsTest {

    private static Map<String, Object> report() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            rows.add(Map.of("name", "Producto " + (char) ('A' + 7 - i), "qty", (double) i));
        }
        return Map.of("rows", rows, "chart", "kept");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> names(Map<String, Object> paged) {
        return ((List<Map<String, Object>>) paged.get("rows")).stream().map(r -> r.get("name")).toList();
    }

    @Test
    void cutsTheRowsToThePageAndReportsTheTotal() {
        Map<String, Object> paged = new PageParams(2, 3, null, null).apply(report());

        assertThat(names(paged)).hasSize(3);
        assertThat(paged.get("total")).isEqualTo(7);
        assertThat(paged.get("page")).isEqualTo(2);
        assertThat(paged.get("pageSize")).isEqualTo(3);
        assertThat(paged.get("chart")).isEqualTo("kept");
    }

    @Test
    void theLastPageHoldsTheRemainder_andAPageBeyondTheEndIsEmpty() {
        assertThat(names(new PageParams(3, 3, null, null).apply(report()))).hasSize(1);
        assertThat(names(new PageParams(9, 3, null, null).apply(report()))).isEmpty();
    }

    @Test
    void ordersTheWholeSetBeforeCuttingThePage() {
        Map<String, Object> byNumberDesc = new PageParams(1, 2, "qty", "desc").apply(report());
        assertThat(((List<Map<String, Object>>) byNumberDesc.get("rows")).get(0).get("qty")).isEqualTo(7.0);

        Map<String, Object> byNameAsc = new PageParams(1, 2, "name", "asc").apply(report());
        assertThat(names(byNameAsc)).containsExactly("Producto A", "Producto B");
    }

    @Test
    void aSortColumnTheRowsDoNotHaveIsIgnored() {
        Map<String, Object> paged = new PageParams(1, 25, "'; DROP TABLE x", "asc").apply(report());

        assertThat(names(paged)).hasSize(7);
    }

    @Test
    void defaultsAndCeiling() {
        PageParams none = new PageParams(null, null, null, null);
        assertThat(none.pageNumber()).isEqualTo(1);
        assertThat(none.size()).isEqualTo(PageParams.DEFAULT_PAGE_SIZE);
        assertThat(new PageParams(0, 999999, null, null).size()).isEqualTo(PageParams.MAX_PAGE_SIZE);
    }
}
