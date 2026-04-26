package com.nomendi6.orgsec.helper;

public class RsqlHelper {

    private RsqlHelper() {
        // Utility class
    }

    /**
     * Add parentheses around a filter expression.
     *
     * @param filter the filter expression
     * @return filter wrapped in parentheses, or empty string if filter is null/empty
     */
    public static String addParenthases(String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return "";
        }
        return "(" + filter + ")";
    }

    /**
     * Combine two filters with OR operator.
     *
     * @param filter1 first filter
     * @param filter2 second filter
     * @return combined filter with OR, or the non-empty filter if one is empty
     */
    public static String orRsql(String filter1, String filter2) {
        if (filter1 == null || filter1.trim().isEmpty()) {
            return filter2 != null ? filter2 : "";
        }
        if (filter2 == null || filter2.trim().isEmpty()) {
            return filter1;
        }
        return addParenthases(filter1) + "," + addParenthases(filter2);
    }

    /**
     * Combine two filters with AND operator.
     *
     * @param filter1 first filter
     * @param filter2 second filter
     * @return combined filter with AND, or the non-empty filter if one is empty
     */
    public static String andRsql(String filter1, String filter2) {
        if (filter1 == null || filter1.trim().isEmpty()) {
            return filter2 != null ? filter2 : "";
        }
        if (filter2 == null || filter2.trim().isEmpty()) {
            return filter1;
        }
        return addParenthases(filter1) + ";" + addParenthases(filter2);
    }
}
