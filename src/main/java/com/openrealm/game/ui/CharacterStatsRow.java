package com.openrealm.game.ui;

/** A rendered line: a section header (value == null) or a stat row. */
final class CharacterStatsRow {
    final String label;
    final String value;
    CharacterStatsRow(String label, String value) {
        this.label = label;
        this.value = value;
    }
}
