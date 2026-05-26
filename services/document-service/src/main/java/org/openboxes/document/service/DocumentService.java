package org.openboxes.document.service;

import org.openboxes.document.entity.Document;
import org.openboxes.document.entity.DocumentCode;
import org.openboxes.document.entity.DocumentType;
import org.openboxes.document.repository.DocumentRepository;
import org.openboxes.document.repository.DocumentTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port of the entity-facing surface of Grails {@code DocumentService} (the parts that read
 * or write {@code Document} / {@code DocumentType} rows). File-utility methods (scaleImage,
 * docx/xlsx generation, etc.) stay Grails-side; Order-rooted queries (getAllDocumentsBy
 * SupplierOrganization) stay Grails-side until Order is extracted post-Phase 1.
 */
@Service
public class DocumentService {

    private final DocumentRepository docRepo;
    private final DocumentTypeRepository typeRepo;

    public DocumentService(DocumentRepository docRepo, DocumentTypeRepository typeRepo) {
        this.docRepo = docRepo;
        this.typeRepo = typeRepo;
    }

    public Optional<Document> findById(String id) {
        return docRepo.findById(id);
    }

    /** Mirrors Grails {@code Document.findAllByDocumentCode(DocumentCode)}. */
    public List<Document> findByCode(DocumentCode code) {
        return docRepo.findByDocumentType_DocumentCode(code);
    }

    /**
     * First-match-or-empty lookup mirroring Grails {@code Document.findByName(String)} (singular
     * dynamic finder). The {@code name} column has no unique constraint, but every observed
     * Grails caller (OrderController:943) expects scalar semantics.
     */
    public Optional<Document> findByName(String name) {
        return docRepo.findFirstByName(name);
    }

    /**
     * Returns documents whose document_type_id is in the given list. Guards against null/empty
     * input (per T3-M2): some DB / Hibernate combinations balk on {@code IN ()}, and an empty
     * list always yields an empty result anyway — cheap to short-circuit.
     */
    public List<Document> findByTypeIds(List<String> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) {
            return List.of();
        }
        return docRepo.findByDocumentType_IdIn(typeIds);
    }

    /**
     * Mirrors Grails {@code DocumentService.getNonTemplateDocumentTypes()}: returns types whose
     * {@code documentCode} is NULL or is not one of {@link DocumentCode#TEMPLATE_CODES}, sorted
     * by name (Grails sorted in-memory; we replicate that here so callers see equivalent ordering).
     */
    public List<DocumentType> getNonTemplateDocumentTypes() {
        List<DocumentType> types = typeRepo.findByDocumentCodeIsNullOrDocumentCodeNotIn(DocumentCode.TEMPLATE_CODES);
        return types.stream()
                .sorted(Comparator.comparing(DocumentType::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public List<DocumentType> getAllDocumentTypes() {
        return typeRepo.findAll();
    }

    /**
     * Creates a new Document row. {@code dateCreated} and {@code lastUpdated} are set explicitly
     * because the columns are NOT NULL in the schema and we deliberately do not use Hibernate
     * {@code @CreationTimestamp} / {@code @UpdateTimestamp} (would conflict with Grails-side
     * GORM auto-stamps during coexistence).
     */
    @Transactional
    public Document create(String name, String filename, String contentType, byte[] fileContents, String documentTypeId) {
        Document d = new Document();
        d.setId(UUID.randomUUID().toString().replace("-", ""));
        d.setName(name);
        d.setFilename(filename);
        d.setContentType(contentType);
        d.setFileContents(fileContents);
        Instant now = Instant.now();
        d.setDateCreated(now);
        d.setLastUpdated(now);
        if (documentTypeId != null) {
            typeRepo.findById(documentTypeId).ifPresent(d::setDocumentType);
        }
        return docRepo.save(d);
    }

    @Transactional
    public void delete(String id) {
        docRepo.deleteById(id);
    }
}
