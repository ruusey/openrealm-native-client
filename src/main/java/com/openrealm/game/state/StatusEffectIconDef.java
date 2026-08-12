package com.openrealm.game.state;

/**
 * Status icon palette mirrors webclient renderer.js STATUS_ICON_DEFS so
 * the same effect renders the same color on both clients (player can
 * identify "green DoT pip = poison" from any client they're using).
 */
final class StatusEffectIconDef {
    final short effectId;
    final String label;
    final float r, g, b;
    StatusEffectIconDef(short effectId, String label, int rgb) {
        this.effectId = effectId;
        this.label = label;
        this.r = ((rgb >> 16) & 0xFF) / 255f;
        this.g = ((rgb >>  8) & 0xFF) / 255f;
        this.b = ( rgb        & 0xFF) / 255f;
    }
}
