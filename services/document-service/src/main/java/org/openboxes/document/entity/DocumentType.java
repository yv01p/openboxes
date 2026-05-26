package org.openboxes.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * JPA entity mirroring the Grails {@code DocumentType} domain class.
 * Schema is owned by the Grails-side Liquibase changelog; this entity
 * is validated (not generated) against the {@code document_type} table.
 */
@Entity
@Table(name = "document_type")
public class DocumentType {

    @Id
    @Column(name = "id", columnDefinition = "char(38)")
    private String id;  // Grails 'uuid' generator emits a 38-char UUID

    /**
     * Optimistic-locking column maintained by both Grails (Hibernate 5) and
     * this service (Hibernate 6). NOT NULL in the schema; must be mapped or
     * Hibernate 6 will throw StaleObjectStateException on the first write.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @NotNull
    @Size(max = 255)
    @Column(name = "name", nullable = false)
    private String name;

    @Size(max = 255)
    @Column(name = "description")
    private String description;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "date_created", nullable = false)
    private Instant dateCreated;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_code")
    private DocumentCode documentCode;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
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

    public DocumentCode getDocumentCode() {
        return documentCode;
    }

    public void setDocumentCode(DocumentCode documentCode) {
        this.documentCode = documentCode;
    }
}
