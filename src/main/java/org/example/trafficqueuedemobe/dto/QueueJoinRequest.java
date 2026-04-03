package org.example.trafficqueuedemobe.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueueJoinRequest {
    private String userId;
    private int priority;
}
