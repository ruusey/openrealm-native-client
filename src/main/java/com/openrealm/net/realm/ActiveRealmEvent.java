package com.openrealm.net.realm;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

class ActiveRealmEvent {
    final int eventId;
    final long bossEnemyId;
    final long spawnTime;
    final long durationMs;
    final int tileX, tileY;
    final int[][] savedBase;
    final int[][] savedCollision;
    final Set<Long> minionIds = new HashSet<>();
    final boolean[] wavesTriggered;
    boolean completed;

    ActiveRealmEvent(int eventId, long bossEnemyId, int tileX, int tileY,
                     int[][] savedBase, int[][] savedCollision, int waveCount, long durationMs) {
        this.eventId = eventId;
        this.bossEnemyId = bossEnemyId;
        this.spawnTime = Instant.now().toEpochMilli();
        this.durationMs = durationMs;
        this.tileX = tileX;
        this.tileY = tileY;
        this.savedBase = savedBase;
        this.savedCollision = savedCollision;
        this.wavesTriggered = new boolean[waveCount];
        this.completed = false;
    }

    boolean isExpired() {
        return Instant.now().toEpochMilli() - spawnTime >= durationMs;
    }
}
