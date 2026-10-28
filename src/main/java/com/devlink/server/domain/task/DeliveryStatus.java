package com.devlink.server.domain.task;

/** Broker delivery result, independent of the task lifecycle. */
public enum DeliveryStatus {
    DELIVERED, FAILED, UNKNOWN
}
