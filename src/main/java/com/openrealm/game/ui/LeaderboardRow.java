package com.openrealm.game.ui;

public class LeaderboardRow {
    public int rank;
    public String accountName;
    public String className;
    public int classIdx;
    public int level;
    public long fame;
    /** Item id per slot index, in canonical left-to-right order:
     *  0=PRIMARY_WEAPON, 1=ARMOR, 2=GAUNTLETS, 3=BOOTS, 4=RING.
     *  -1 = empty. Layout matches the webclient leaderboard tooltip
     *  (loadLeaderboard / showEquipmentTooltip in main.js). */
    public int[] equipment = new int[]{-1, -1, -1, -1, -1};

    public LeaderboardRow(int rank, String accountName, String className, int classIdx,
               int level, long fame, int[] equipment) {
        this.rank = rank;
        this.accountName = accountName;
        this.className = className;
        this.classIdx = classIdx;
        this.level = level;
        this.fame = fame;
        if (equipment != null && equipment.length == 5) this.equipment = equipment;
    }
}
