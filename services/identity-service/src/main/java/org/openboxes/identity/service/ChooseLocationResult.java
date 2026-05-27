package org.openboxes.identity.service;

import org.openboxes.identity.entity.Location;
import org.openboxes.identity.entity.User;

import java.util.List;

public record ChooseLocationResult(User user, Location location, List<String> roleIds, String token) {}
