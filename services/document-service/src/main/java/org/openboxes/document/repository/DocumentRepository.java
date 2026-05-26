package org.openboxes.document.repository;

import org.openboxes.document.entity.Document;
import org.openboxes.document.entity.DocumentCode;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {

    /**
     * Override the inherited {@code findById} so the {@code documentType} relation is fetched
     * in the same SELECT. Without this, Jackson serialization of {@code Document} via the REST
     * surface ships {@code documentType: null} for rows that actually have one (the LAZY proxy
     * is not initialized at serialization time even with open-in-view).
     */
    @EntityGraph(attributePaths = "documentType")
    @Override
    Optional<Document> findById(String id);

    /** Mirrors Grails {@code Document.findAllByDocumentCode(DocumentCode)} via the join through DocumentType. */
    @EntityGraph(attributePaths = "documentType")
    List<Document> findByDocumentType_DocumentCode(DocumentCode code);

    @EntityGraph(attributePaths = "documentType")
    List<Document> findByName(String name);

    /**
     * Mirrors Grails {@code Document.findByName(String)} (singular GORM dynamic finder) which returns
     * the first match or null. The {@code name} column has no unique constraint, so multiple rows can
     * share a name; callers that expect scalar semantics (e.g., OrderController:943 looking up a
     * single template by name) should use this method.
     */
    @EntityGraph(attributePaths = "documentType")
    Optional<Document> findFirstByName(String name);

    @EntityGraph(attributePaths = "documentType")
    List<Document> findByDocumentType_IdIn(List<String> typeIds);
}
