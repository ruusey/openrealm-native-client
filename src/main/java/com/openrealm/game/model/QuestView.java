package com.openrealm.game.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Client-side view of one quest, parsed from the QuestStatePacket JSON payload. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestView {
    private int id;
    private String name;
    private String desc;
    private String cat;
    private boolean scoped;
    private String status;
    private boolean auto;
    private boolean repeatable;
    private int stars;
    private List<QuestViewObjective> objectives = new ArrayList<>();
    private List<QuestViewReward> rewards = new ArrayList<>();
}
