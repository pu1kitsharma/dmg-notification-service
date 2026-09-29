package com.dmg.notify.notification;

import java.util.EnumSet;
import java.util.Set;

public enum NotificationStatus {
    PENDING, PROCESSING, SENT, DEAD, CANCELLED;

    public boolean canTransitionTo(NotificationStatus to) {
        return allowed().contains(to);
    }

    private Set<NotificationStatus> allowed() {
        return switch (this) {
            case PENDING -> EnumSet.of(PROCESSING, CANCELLED);
            case PROCESSING -> EnumSet.of(SENT, PENDING, DEAD);
            case SENT, DEAD, CANCELLED -> EnumSet.noneOf(NotificationStatus.class);
        };
    }
}
