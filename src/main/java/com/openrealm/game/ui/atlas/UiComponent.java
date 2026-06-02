package com.openrealm.game.ui.atlas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One rect on the UI atlas sheet. Optionally describes a grid of cells
 * (cols/rows/cellW/cellH/spacing) for components that hold repeated slots
 * such as {@code panel.hud.main.grid} (4×4 inventory). Coordinates are in
 * sheet pixels — the renderer multiplies by {@link UiAtlas#getDisplayScale()}
 * when drawing.
 *
 * Source of truth: {@code openrealm-data/src/main/resources/ui/ui-components.json},
 * authored via the editor's UI Atlas tab.
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
	/** Optional per-component atlas override. Null → the document's root
	 *  {@code sheet} (see {@link UiAtlasModel#getSheet()}). Lets a subset of
	 *  components (e.g. the {@code panel.hud.main}/{@code .inv} namespaces)
	 *  live on a different sheet from the rest. */
	private String sheet;
	/** Optional — defaults to {@link UiAtlas#getContentInset()} when null. */
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
