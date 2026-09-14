package com.openrealm.game.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RealmEventModel {
    private int eventId;
    private String name;
    private String announceMessage;   // format args: event name, zone name
    private String defeatMessage;
    private String timeoutMessage;
    private int bossEnemyId;
    private int eventMultiplier;
    private int setPieceId;           // SetPieceModel arena, -1 = none
    private List<String> allowedZones;
    private int durationSeconds;
    private List<MinionWave> minionWaves;
}
