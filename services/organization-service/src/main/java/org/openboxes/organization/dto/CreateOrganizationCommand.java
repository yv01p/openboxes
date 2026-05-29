// CreateOrganizationCommand.java (POST request body)
package org.openboxes.organization.dto;
import jakarta.validation.constraints.NotBlank;
public record CreateOrganizationCommand(
    @NotBlank String name,
    String description,
    String code  // optional; auto-generated if absent
) {}
