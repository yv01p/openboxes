package org.openboxes.document.repository;

import org.openboxes.document.entity.Document;
import org.openboxes.document.entity.DocumentCode;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {

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
