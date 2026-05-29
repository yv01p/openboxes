package org.openboxes.organization.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "party")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "class", discriminatorType = DiscriminatorType.STRING, length = 255)
@DiscriminatorValue("org.pih.warehouse.core.Party")
public class Party {

    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "party_type_id", nullable = false)
    private PartyType partyType;

    @OneToMany(mappedBy = "party", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<PartyRole> roles = new HashSet<>();

    @Column(name = "date_created", nullable = false)
    private Instant dateCreated;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        if (dateCreated == null) dateCreated = now;
        lastUpdated = now;
    }
    @PreUpdate void preUpdate() { lastUpdated = Instant.now(); }

    // Getters + setters (id, version, partyType, roles, dateCreated, lastUpdated)
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getVersion() { return version; }
    public PartyType getPartyType() { return partyType; }
    public void setPartyType(PartyType partyType) { this.partyType = partyType; }
    public Set<PartyRole> getRoles() { return roles; }
    public void setRoles(Set<PartyRole> roles) { this.roles = roles; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
