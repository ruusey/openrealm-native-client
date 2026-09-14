package com.openrealm.game.ui.atlas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One rect on the UI atlas sheet. Coordinates are in sheet pixels — the
 * renderer multiplies by {@link UiAtlas#getDisplayScale()} when drawing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UiComponent {
	private String id;
	private int x;
	private int y;
	private int w;
	private int h;
	/** Null falls back to the document root {@link UiAtlasModel#getSheet()}. */
	private String sheet;
	/** Null defaults to {@link UiAtlas#getContentInset()}. */
	private Integer contentInset;
	/** Grid metadata. Null on non-grid components. */
	private Integer cols;
	private Integer rows;
	private Integer cellW;
	private Integer cellH;
	private Integer spacing;

	public boolean isGrid() {
		return this.cols != null && this.rows != null && this.cellW != null && this.cellH != null;
	}
}
