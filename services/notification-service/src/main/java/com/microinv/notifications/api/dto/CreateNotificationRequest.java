package com.microinv.notifications.api.dto;

import com.microinv.notifications.domain.EntityType;
import com.microinv.notifications.domain.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code POST /internal/notifications} (§6.4). Los UUID llegan como texto canónico estricto. */
public record CreateNotificationRequest(
        @NotNull(message = "is required") NotificationType type,
        @NotBlank(message = "is required") @Size(max = 120, message = "must be at most 120 characters") String title,
        @NotBlank(message = "is required") @Size(max = 500, message = "must be at most 500 characters") String message,
        @Pattern(regexp = CreateNotificationRequest.UUID_REGEX, message = "must be a valid UUID") String targetUserId,
        @Pattern(regexp = CreateNotificationRequest.UUID_REGEX, message = "must be a valid UUID") String actorUserId,
        @NotNull(message = "is required") EntityType entityType,
        @NotNull(message = "is required")
        @Pattern(regexp = CreateNotificationRequest.UUID_REGEX, message = "must be a valid UUID") String entityId) {

    public static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
}
