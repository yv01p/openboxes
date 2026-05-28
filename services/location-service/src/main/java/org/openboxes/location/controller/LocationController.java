package org.openboxes.location.controller;

import org.openboxes.location.dto.LocationDto;
import org.openboxes.location.entity.Location;
import org.openboxes.location.enums.LocationTypeCode;
import org.openboxes.location.enums.SupportedActivitiesEnum;
import org.openboxes.location.repository.LocationRepository;
import org.openboxes.location.service.LocationFilterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/location")
public class LocationController {
    private final LocationRepository repo;
    private final LocationFilterService filter;

    public LocationController(LocationRepository r, LocationFilterService f) {
        this.repo = r;
        this.filter = f;
    }

    @GetMapping("/{id}")
    public ResponseEntity<LocationDto> read(@PathVariable String id,
                                             @RequestParam(defaultValue = "false") boolean includeInternal) {
        var found = includeInternal
            ? repo.findById(id)
            : repo.findByIdExcludingInternal(id, filter.internalTypeCodes());
        return found.map(l -> ResponseEntity.ok(LocationDto.from(l)))
                    .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<LocationDto> list(@RequestParam(required = false) String type,
                                  @RequestParam(required = false) Boolean active,
                                  @RequestParam(required = false) String parentId,
                                  @RequestParam(defaultValue = "false") boolean includeInternal) {
        LocationTypeCode typeFilter = (type == null || type.isBlank()) ? null : LocationTypeCode.valueOf(type);
        return repo.findFiltered(typeFilter, parentId, active, includeInternal, filter.internalTypeCodes())
                   .stream().map(LocationDto::from).toList();
    }

    @GetMapping("/supportedActivities")
    public List<String> supportedActivities() {
        return SupportedActivitiesEnum.list();
    }
}
