package com.dmg.notify.notification;

import com.dmg.notify.notification.NotificationDtos.*;
import com.dmg.notify.notification.NotificationService.SubmitResult;
import com.dmg.notify.security.TenantContext;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    /** 202 Accepted for a new notification, 200 when an Idempotency-Key replays an earlier submit. */
    @PostMapping
    public ResponseEntity<NotificationResponse> submit(@Valid @RequestBody SubmitRequest req,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        SubmitResult r = service.submit(TenantContext.requireTenantId(), req, key);
        return ResponseEntity.status(r.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(NotificationResponse.from(r.notification()));
    }

    @GetMapping("/{id}")
    public NotificationDetail get(@PathVariable String id) {
        return service.detail(TenantContext.requireTenantId(), id);
    }

    @GetMapping
    public Page<NotificationResponse> list(@RequestParam(required = false) NotificationStatus status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return service.list(TenantContext.requireTenantId(), status, page, size).map(NotificationResponse::from);
    }

    @PostMapping("/{id}/cancel")
    public NotificationResponse cancel(@PathVariable String id) {
        return NotificationResponse.from(service.cancel(TenantContext.requireTenantId(), id));
    }
}
