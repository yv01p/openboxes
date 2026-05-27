package org.openboxes.identity.repository;

import org.openboxes.identity.entity.Person;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRepository extends JpaRepository<Person, String> {
}
