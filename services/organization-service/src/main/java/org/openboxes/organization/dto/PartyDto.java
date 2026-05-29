// PartyDto.java
package org.openboxes.organization.dto;
import org.openboxes.organization.entity.Party;
import java.util.List;
public record PartyDto(
    String id, String partyTypeId, String partyTypeCode, List<String> roleTypes
) {
    public static PartyDto from(Party p) {
        return new PartyDto(
            p.getId(),
            p.getPartyType() == null ? null : p.getPartyType().getId(),
            p.getPartyType() == null || p.getPartyType().getPartyTypeCode() == null ? null : p.getPartyType().getPartyTypeCode().name(),
            p.getRoles() == null ? List.of() : p.getRoles().stream().map(r -> r.getRoleType()).toList()
        );
    }
}
