// OrganizationDto.java
package org.openboxes.organization.dto;
import org.openboxes.organization.entity.Organization;
import java.time.Instant;
import java.util.List;
public record OrganizationDto(
    String id, String code, String name, String description,
    String partyTypeId, String partyTypeCode,
    String defaultLocationId,
    Boolean active, Instant dateCreated, Instant lastUpdated,
    List<String> roleTypes
) {
    public static OrganizationDto from(Organization o) {
        return new OrganizationDto(
            o.getId(), o.getCode(), o.getName(), o.getDescription(),
            o.getPartyType() == null ? null : o.getPartyType().getId(),
            o.getPartyType() == null || o.getPartyType().getPartyTypeCode() == null ? null : o.getPartyType().getPartyTypeCode().name(),
            o.getDefaultLocationId(),
            o.getActive(), o.getDateCreated(), o.getLastUpdated(),
            o.getRoles() == null ? List.of() : o.getRoles().stream().map(r -> r.getRoleType()).toList()
        );
    }
}
