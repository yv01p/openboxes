package org.openboxes.location.controller;

import org.openboxes.location.dto.LocationGroupDto;
import org.openboxes.location.repository.LocationGroupRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/location/group")
public class LocationGroupController {
    private final LocationGroupRepository repo;

    public LocationGroupController(LocationGroupRepository r) { this.repo = r; }

    @GetMapping("/{id}")
    public ResponseEntity<LocationGroupDto> read(@PathVariable String id) {
        return repo.findById(id)
            .map(lg -> ResponseEntity.ok(LocationGroupDto.from(lg)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<LocationGroupDto> list() {
        return repo.findAll().stream().map(LocationGroupDto::from).toList();
    }
}
