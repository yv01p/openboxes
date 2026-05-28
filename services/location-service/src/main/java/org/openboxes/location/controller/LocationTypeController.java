package org.openboxes.location.controller;

import org.openboxes.location.dto.LocationTypeDto;
import org.openboxes.location.service.LocationTypeCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/location/type")
public class LocationTypeController {
    private final LocationTypeCache cache;

    public LocationTypeController(LocationTypeCache c) { this.cache = c; }

    @GetMapping
    public List<LocationTypeDto> list() {
        return cache.getAll().stream().map(LocationTypeDto::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<LocationTypeDto> read(@PathVariable String id) {
        return cache.getById(id)
            .map(lt -> ResponseEntity.ok(LocationTypeDto.from(lt)))
            .orElse(ResponseEntity.notFound().build());
    }
}
