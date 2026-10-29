package com.seamware.consentmanager.notification;

/**
 * Delivery state of an outbox row. {@code PENDING} and {@code FAILED} are due; {@code DELIVERED}
 * and {@code ABANDONED} are terminal and carry no payload.
 */
public enum NotificationOutboxStatus {
    PENDING,
    FAILED,
    DELIVERED,
    ABANDONED
}
