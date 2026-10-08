package com.boxy.boxy.modules.dashboard.service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paging and ordering of a report's table, as the screen asks for it ({@code page}, {@code pageSize},
 * {@code sort}, {@code direction} — bound straight from the query string). Applied to the report's
 * {@code rows} <em>after</em> everything that must cover all rows (chart, totals) was computed, so a
 * page never changes a figure — it only decides which rows travel to the browser.
 */
public record PageParams(Integer page, Integer pageSize, String sort, String direction) {

    public static final int DEFAULT_PAGE_SIZE = 25;
    /** Ceiling for one response; an export asks for this to get every row in one go. */
    public static final int MAX_PAGE_SIZE = 5000;

    private static final Collator COLLATOR = Collator.getInstance(Locale.forLanguageTag("es"));

    public int pageNumber() {
        return page == null || page < 1 ? 1 : page;
    }

    public int size() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * Returns {@code report} with its {@code rows} ordered by {@link #sort} (when that is a column
     * the rows really have — anything else is ignored) and cut to the requested page, plus
     * {@code total}, {@code page} and {@code pageSize}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(Map<String, Object> report) {
        List<Map<String, Object>> rows = new ArrayList<>((List<Map<String, Object>>) report.get("rows"));

        if (sort != null && !rows.isEmpty() && rows.get(0).containsKey(sort)) {
            Comparator<Map<String, Object>> byColumn = Comparator.comparing(r -> r.get(sort), PageParams::compareValues);
            rows.sort("desc".equalsIgnoreCase(direction) ? byColumn.reversed() : byColumn);
        }

        int from = (int) Math.min((long) (pageNumber() - 1) * size(), rows.size());
        int to = Math.min(from + size(), rows.size());

        Map<String, Object> paged = new LinkedHashMap<>(report);
        paged.put("rows", new ArrayList<>(rows.subList(from, to)));
        paged.put("total", rows.size());
        paged.put("page", pageNumber());
        paged.put("pageSize", size());
        return paged;
    }

    /** Numbers numerically, text without caring about case or accents, empty values last. */
    private static int compareValues(Object a, Object b) {
        if (a == null || b == null) {
            return a == b ? 0 : a == null ? 1 : -1;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        return COLLATOR.compare(String.valueOf(a), String.valueOf(b));
    }
}
