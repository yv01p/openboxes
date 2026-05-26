package org.openboxes.document.repository;

import org.openboxes.document.entity.Document;
import org.openboxes.document.entity.DocumentCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, String> {

    /** Mirrors Grails {@code Document.findAllByDocumentCode(DocumentCode)} via the join through DocumentType. */
    List<Document> findByDocumentType_DocumentCode(DocumentCode code);

    List<Document> findByName(String name);

    List<Document> findByDocumentType_IdIn(List<String> typeIds);
}
