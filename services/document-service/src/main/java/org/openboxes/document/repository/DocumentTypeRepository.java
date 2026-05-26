package org.openboxes.document.repository;

import org.openboxes.document.entity.DocumentCode;
import org.openboxes.document.entity.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, String> {

    /**
     * Backs {@code getNonTemplateDocumentTypes} in the upcoming service layer.
     * Mirrors Grails {@code DocumentService.getNonTemplateDocumentTypes()} semantics:
     * a type is "non-template" when its {@code documentCode} is null OR is not one of the
     * template codes. Pass {@link DocumentCode#TEMPLATE_CODES}.
     */
    List<DocumentType> findByDocumentCodeIsNullOrDocumentCodeNotIn(Set<DocumentCode> templates);

    /** Used to enumerate types that carry any document code. */
    List<DocumentType> findByDocumentCodeIsNotNull();
}
