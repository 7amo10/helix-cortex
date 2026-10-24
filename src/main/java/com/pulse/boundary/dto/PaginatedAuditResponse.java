package com.pulse.boundary.dto;

import java.io.Serializable;
import java.util.List;

/**
 * Paginated response wrapper for audit trail records.
 *
 * @param <T>         record item type
 * @param records     list of records in current page
 * @param page        current page index (0-based)
 * @param limit       items per page
 * @param totalCount  total number of records matching query
 * @param totalPages  total calculated pages
 */
public record PaginatedAuditResponse<T>(
        List<T> records,
        int page,
        int limit,
        long totalCount,
        int totalPages
) implements Serializable {}
