package org.openboxes.location.service;

import org.openboxes.location.enums.LocationTypeCode;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service
public class LocationFilterService {
    public Set<LocationTypeCode> internalTypeCodes() {
        return LocationTypeCode.listInternalTypeCodes();  // [BIN_LOCATION, INTERNAL]
    }
}
