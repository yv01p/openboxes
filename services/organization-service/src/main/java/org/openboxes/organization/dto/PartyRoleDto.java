// PartyRoleDto.java
package org.openboxes.organization.dto;
import org.openboxes.organization.entity.PartyRole;
import java.time.Instant;
public record PartyRoleDto(
    String id, String partyId, String roleType, Instant startDate, Instant endDate
) {
    public static PartyRoleDto from(PartyRole r) {
        return new PartyRoleDto(
            r.getId(),
            r.getParty() == null ? null : r.getParty().getId(),
            r.getRoleType(), r.getStartDate(), r.getEndDate()
        );
    }
}
