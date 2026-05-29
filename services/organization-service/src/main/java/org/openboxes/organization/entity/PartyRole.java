package org.openboxes.organization.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "party_role")
public class PartyRole {

    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Version @Column(nullable = false) private Long version;

    @ManyToOne
    @JoinColumn(name = "party_id", nullable = false)
    private Party party;

    @Column(name = "role_type", nullable = false, length = 255)
    private String roleType;

    @Column(name = "start_date") private Instant startDate;
    @Column(name = "end_date") private Instant endDate;

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public Party getParty() { return party; }
    public void setParty(Party party) { this.party = party; }
    public String getRoleType() { return roleType; }
    public void setRoleType(String roleType) { this.roleType = roleType; }
    public Instant getStartDate() { return startDate; }
    public void setEndDate(Instant endDate) { this.endDate = endDate; }
    public Instant getEndDate() { return endDate; }
}
