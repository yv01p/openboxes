package org.openboxes.organization.entity;

import jakarta.persistence.*;
import org.openboxes.organization.enums.PartyTypeCode;
import java.time.Instant;

@Entity
@Table(name = "party_type")
public class PartyType {

    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Version @Column(nullable = false) private Long version;
    @Column(nullable = false, length = 255) private String code;
    @Column(length = 255) private String name;
    @Column(length = 255) private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "party_type_code", nullable = false, length = 255)
    private PartyTypeCode partyTypeCode;

    @Column(name = "date_created", nullable = false) private Instant dateCreated;
    @Column(name = "last_updated", nullable = false) private Instant lastUpdated;

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public PartyTypeCode getPartyTypeCode() { return partyTypeCode; }
}
