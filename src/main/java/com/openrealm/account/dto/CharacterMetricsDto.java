package com.openrealm.account.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-character lifetime metrics report, mirroring the data service's
 * {@code CharacterMetricsEntity}. Fetched read-only from
 * {@code GET /data/account/character/{uuid}/metrics} to render the
 * right-click stats window on the character-select screen.
 *
 * ignoreUnknown so the data service can add new counters without breaking
 * the native parse before a client release ships.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CharacterMetricsDto {
    private String characterUuid;

    // Combat
    private long projectilesFired;
    private long projectilesHit;
    private long projectilesMissed;
    private long damageDealtTotal;
    private long damageTakenTotal;
    private long deaths;
    private long killsTotal;
    private long bossKills;
    private Map<String, Long> killsByEnemyId;

    // Items
    private long hpPotionsDrank;
    private long mpPotionsDrank;
    private long itemsPickedUp;
    private long itemsEnchanted;
    private Map<String, Long> itemsConsumedByItemId;

    // Progression
    private long xpEarned;
    private long xpFromKills;
    private long skillPointsSpent;

    // Abilities
    private long abilityCastsTotal;
    private long abilityDamageDealt;
    private long abilityAlliesAffected;
    private long abilityEnemiesAffected;
    private long abilityBuffSecondsAlly;
    private long abilityDebuffSecondsEnemy;
    private long abilityDebuffSecondsFriendly;
    private Map<String, Long> castsStartedByAbility;
    private Map<String, Long> castsCompletedByAbility;

    // Social
    private long tradesCompleted;
    private long chatMessagesSent;
    private Map<String, Long> tradePartners;

    // Meta / PvP / dungeons
    private long playTimeSeconds;
    private long pvpMatches;
    private long pvpWins;
    private long pvpLosses;
    private Map<String, Long> dungeonCompletionsByDungeonId;
}
