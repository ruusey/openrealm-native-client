package com.openrealm.game.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Root of the QuestStatePacket JSON payload: public star total + the quest list. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestSnapshot {
    private long stars;
    private List<QuestView> quests = new ArrayList<>();
}
