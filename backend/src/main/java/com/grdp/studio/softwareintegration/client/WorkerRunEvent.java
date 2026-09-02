package com.grdp.studio.softwareintegration.client;

import java.time.Instant;

public record WorkerRunEvent(
        long sequence,
        String state,
        Instant occurredAtUtc,
        String message
) {}
