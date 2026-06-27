package com.clele.parts.repository;

/** Spring Data projection for the native low-stock and all-thresholds queries. */
public interface StockThresholdView {
    Long getId();
    Long getPartId();
    Long getLocationId();
    Integer getMinimumQuantity();
    Long getTotalQuantity();
    String getPartNumber();
    String getPartName();
    String getLocationName();
}
