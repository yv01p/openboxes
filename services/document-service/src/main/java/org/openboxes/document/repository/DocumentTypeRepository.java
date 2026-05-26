package org.openboxes.document.repository;

import org.openboxes.document.entity.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, String> {

    /** Used by {@code getNonTemplateDocumentTypes} in the upcoming service layer. */
    List<DocumentType> findByDocumentCodeIsNull();

    /** Used to enumerate template-bearing document types. */
    List<DocumentType> findByDocumentCodeIsNotNull();
}
