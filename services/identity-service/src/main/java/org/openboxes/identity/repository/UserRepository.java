package org.openboxes.identity.repository;

import org.openboxes.identity.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    /**
     * Combined lookup mirroring Grails User.findByUsernameOrEmail.
     */
    @Query("SELECT u FROM User u WHERE u.username = :s OR u.email = :s")
    Optional<User> findByUsernameOrEmail(String s);
}
