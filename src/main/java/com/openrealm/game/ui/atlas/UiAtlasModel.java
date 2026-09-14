package com.openrealm.game.ui.atlas;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UiAtlasModel {
	private String sheet = "Open_Realm_User_Interface_V1.png";
	private int displayScale = 2;
	private int iconScale = 2;
	private int contentInset = 4;
	private List<UiComponent> components = new ArrayList<>();
	private Object layouts;
}
