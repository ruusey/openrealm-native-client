package com.openrealm.game.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestViewReward {
    private String type;
    private long amount;
    private int targetId;
    private String stat;
    private int skillId;
}
