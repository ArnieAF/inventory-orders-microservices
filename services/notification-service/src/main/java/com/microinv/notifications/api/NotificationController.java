package com.microinv.notifications.api;

import com.microinv.notifications.api.dto.NotificationResponse;
import com.microinv.notifications.api.dto.PageResponse;
import com.microinv.notifications.api.dto.UnreadCountResponse;
import com.microinv.notifications.security.CurrentUser;
import com.microinv.notifications.service.NotificationService;
import com.microinv.notifications.service.PageParams;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API de historial/lectura por usuario (§6.4). Exige JWT; el usuario sale de {@code sub}. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<NotificationResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + PageParams.DEFAULT_SIZE) int size,
            @RequestParam(name = "sort", required = false) String sort) {
        return service.list(CurrentUser.id(jwt), unreadOnly, PageParams.parse(page, size, sort));
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new UnreadCountResponse(service.countUnread(CurrentUser.id(jwt)));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        service.markRead(CurrentUser.id(jwt), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(CurrentUser.id(jwt));
    }
}
