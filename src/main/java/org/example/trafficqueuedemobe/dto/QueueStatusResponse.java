package org.example.trafficqueuedemobe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueStatusResponse {
    private long position;
    private String status;
    private long totalWaiting;
    private long estimatedWaitSeconds;
}
