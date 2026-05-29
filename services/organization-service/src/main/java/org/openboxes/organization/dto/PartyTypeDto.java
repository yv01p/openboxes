// PartyTypeDto.java
package org.openboxes.organization.dto;
import org.openboxes.organization.entity.PartyType;
public record PartyTypeDto(
    String id, String code, String name, String description, String partyTypeCode
) {
    public static PartyTypeDto from(PartyType pt) {
        return new PartyTypeDto(
            pt.getId(), pt.getCode(), pt.getName(), pt.getDescription(),
            pt.getPartyTypeCode() == null ? null : pt.getPartyTypeCode().name()
        );
    }
}
