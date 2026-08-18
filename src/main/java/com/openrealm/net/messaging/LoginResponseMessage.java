package com.openrealm.net.messaging;

import com.openrealm.account.dto.PlayerAccountDto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginResponseMessage {
    private long playerId;
    private int classId;
    private boolean success;
    private float spawnX;
    private float spawnY;
    private String token;
    private String chatRole;
    // Authoritative game-server version from the server, shown in the welcome line.
    private String version;
    private PlayerAccountDto account;
}
