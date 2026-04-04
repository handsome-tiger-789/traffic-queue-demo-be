package org.example.trafficqueuedemobe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueJoinResponse {
    private String token;
    private long position;
    private String status;
}
