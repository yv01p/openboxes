package org.openboxes.document.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * JPA entity mirroring the Grails {@code Document} domain class.
 * Schema is owned by the Grails-side Liquibase changelog; this entity
 * is validated (not generated) against the {@code document} table.
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    @Column(name = "id", columnDefinition = "char(38)")
    private String id;  // UUID; generated app-side at create (mirrors Grails 'uuid' generator)

    /**
     * Optimistic-locking column maintained by both Grails (Hibernate 5) and
     * this service (Hibernate 6). NOT NULL in the schema; must be mapped or
     * Hibernate 6 will fail to write rows. {@code @JsonIgnore} keeps internal
     * lock state off the wire — callers shouldn't couple to it.
     */
    @JsonIgnore
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Size(max = 255)
    @Column(name = "name")
    private String name;

    @Size(max = 255)
    @Column(name = "filename")
    private String filename;

    @Size(max = 255)
    @Column(name = "extension")
    private String extension;

    @Size(max = 255)
    @Column(name = "content_type")
    private String contentType;

    /**
     * Schema stores file bytes as MEDIUMBLOB (capped at 10 MB per Grails constraint).
     * {@code columnDefinition} is supplied so Hibernate's schema validator does not
     * complain about LONGBLOB vs MEDIUMBLOB mismatch. {@code @JsonIgnore} suppresses
     * the field from JSON responses — callers fetch bytes via
     * {@code GET /api/documents/{id}/content}; serializing them inline base64-bloats
     * list responses by 33% per row.
     */
    @JsonIgnore
    @Lob
    @Column(name = "file_contents", columnDefinition = "mediumblob")
    private byte[] fileContents;

    // dateCreated is set explicitly in DocumentService.create() rather than via
    // @CreationTimestamp to avoid double-stamping during Grails coexistence
    // (Grails-side GORM also auto-stamps via the dateCreated convention).
    // updatable=false defends against accidental UPDATEs wiping the creation timestamp.
    @Column(name = "date_created", nullable = false, updatable = false)
    private Instant dateCreated;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    /**
     * Schema stores the URI as LONGTEXT (no length cap). {@code columnDefinition}
     * is supplied so Hibernate's schema validator does not complain about
     * VARCHAR(255) vs LONGTEXT mismatch.
     */
    @Column(name = "file_uri", columnDefinition = "longtext")
    private String fileUri;

    @Size(max = 255)
    @Column(name = "document_number")
    private String documentNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_type_id", columnDefinition = "char(38)")
    private DocumentType documentType;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getExtension() {
        return extension;
    }

    public void setExtension(String extension) {
        this.extension = extension;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public byte[] getFileContents() {
        return fileContents;
    }

    public void setFileContents(byte[] fileContents) {
        this.fileContents = fileContents;
    }

    public Instant getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Instant dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getFileUri() {
        return fileUri;
    }

    public void setFileUri(String fileUri) {
        this.fileUri = fileUri;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public void setDocumentType(DocumentType documentType) {
        this.documentType = documentType;
    }
}
