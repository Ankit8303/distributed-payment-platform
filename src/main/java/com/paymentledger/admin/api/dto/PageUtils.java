package com.paymentledger.admin.api.dto;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Utility for bounding and normalizing pagination parameters.
 * Enforces maximum page size of 100 to prevent unbounded memory allocation and DoS.
 */
public final class PageUtils {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private PageUtils() {
    }

    public static Pageable clamp(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE);
        }
        int pageSize = Math.min(Math.max(1, pageable.getPageSize()), MAX_PAGE_SIZE);
        int pageNumber = Math.max(0, pageable.getPageNumber());
        Sort sort = pageable.getSort();
        return PageRequest.of(pageNumber, pageSize, sort);
    }
}
