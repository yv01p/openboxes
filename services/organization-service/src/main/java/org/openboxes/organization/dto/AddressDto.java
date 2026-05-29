// AddressDto.java (defined for completeness per spec §5.6; not surfaced through endpoint in Phase 4)
package org.openboxes.organization.dto;
public record AddressDto(
    String id, String address, String address2, String city, String stateOrProvince,
    String postalCode, String country, String description
) {}
