package org.openboxes.location.enums;

public enum LocationTypeCode {
    DEFAULT,
    DEPOT, ZONE, BIN_LOCATION, INTERNAL,
    DISPENSARY, WARD,
    SUPPLIER, DONOR,
    CONSUMER,
    DISTRIBUTOR, DISPOSAL, VIRTUAL;

    public static java.util.Set<LocationTypeCode> listInternalTypeCodes() {
        return java.util.Set.of(BIN_LOCATION, INTERNAL);
    }
}
