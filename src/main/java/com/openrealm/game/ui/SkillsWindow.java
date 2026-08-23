package com.openrealm.game.ui;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import com.openrealm.game.OpenRealmGame;

/**
 * Account-wide skills panel (toggled with M). Renders the 9 skills as a 3x3
 * grid; each cell shows the skill name, its derived level (0-99), and a small
 * blue progress bar toward the next level. Hovering the bar shows current XP
 * and the XP remaining until the next level. Read-only.
 */
public class SkillsWindow {

    // Mirrors the server curve (PlayerSkillHelper): totalXp(L) = CURVE_K * L^2.
    private static final long CURVE_K = 510L;
    private static final int MAX_LEVEL = 99;

    private static final String[] SKILL_NAMES = {
        "Ranged Combat", "Melee Combat", "Magic Combat",
        "Heavy Armor", "Light Armor", "Cloak Armor",
        "Support Caster", "Impairment Caster", "DPS Caster"
    };
    // Description, passive effect, and XP rule per skill (mirrors server PlayerSkillHelper).
    private static final String[] SKILL_DESC = {
        "Damage with ranged (light) weapons.",
        "Damage with melee (heavy) weapons.",
        "Damage with magic weapons.",
        "Toughness while wearing heavy armor.",
        "Toughness while wearing light armor.",
        "Toughness while wearing cloak armor.",
        "Buffing allies with abilities.",
        "Debuffing enemies with abilities.",
        "Dealing ability damage."
    };
    private static final String[] SKILL_EFFECT = {
        "+0.1% ranged damage / level",
        "+0.1% melee damage / level",
        "+0.1% magic damage / level",
        "+0.1% damage reduction / level",
        "+0.1% damage reduction / level",
        "+0.1% damage reduction / level",
        "+0.15% ally buff duration / level",
        "+0.15% enemy debuff duration / level",
        "+0.1% ability damage / level"
    };
    private static final String[] SKILL_XP_RULE = {
        "0.5 XP per ranged damage dealt",
        "0.5 XP per melee damage dealt",
        "0.5 XP per magic damage dealt",
        "1 XP per damage taken (heavy armor)",
        "1 XP per damage taken (light armor)",
        "1 XP per damage taken (cloak armor)",
        "25 XP per buff applied to an ally",
        "15 XP per debuff applied to an enemy",
        "0.5 XP per ability damage dealt"
    };

    private final GlyphLayout layout = new GlyphLayout();
    private boolean visible = false;

    public boolean isVisible() {
        return this.visible;
    }

    public void show() {
        this.visible = true;
    }

    public void hide() {
        this.visible = false;
    }

    public void toggle() {
        this.visible = !this.visible;
    }

    public void update() {
        if (!this.visible) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) this.hide();
    }

    public static long totalXpForLevel(final int level) {
        if (level <= 0) return 0L;
        final int capped = Math.min(level, MAX_LEVEL);
        return CURVE_K * (long) capped * (long) capped;
    }

    public static int levelForXp(final long xp) {
        if (xp <= 0) return 0;
        int level = (int) Math.sqrt((double) xp / (double) CURVE_K);
        if (level < 0) level = 0;
        while (level < MAX_LEVEL && totalXpForLevel(level + 1) <= xp) level++;
        while (level > 0 && totalXpForLevel(level) > xp) level--;
        return Math.min(level, MAX_LEVEL);
    }

    public void render(final SpriteBatch batch, final ShapeRenderer shapes, final BitmapFont font, final long[] skillXp) {
        if (!this.visible) return;

        final int w = OpenRealmGame.width;
        final int h = OpenRealmGame.height;
        final int cols = 3, rows = 3;
        final int cellW = 168, cellH = 92, pad = 10;
        final int headerH = 32;
        final int dialogW = cols * cellW + (cols + 1) * pad;
        final int dialogH = headerH + rows * cellH + (rows + 1) * pad;
        final int x = (w - dialogW) / 2;
        final int y = (h - dialogH) / 2;

        final int mouseX = Gdx.input.getX();
        final int mouseY = Gdx.input.getY();
        int hoverIdx = -1;
        int hoverBarX = 0, hoverBarY = 0;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, w, h);
        shapes.setColor(0.10f, 0.07f, 0.09f, 0.97f);
        shapes.rect(x, y, dialogW, dialogH);
        shapes.setColor(0.06f, 0.06f, 0.08f, 1f);
        shapes.rect(x, y, dialogW, headerH);

        for (int i = 0; i < SKILL_NAMES.length; i++) {
            final int c = i % cols, r = i / cols;
            final int cx = x + pad + c * (cellW + pad);
            final int cy = y + headerH + pad + r * (cellH + pad);
            shapes.setColor(0.16f, 0.14f, 0.18f, 1f);
            shapes.rect(cx, cy, cellW, cellH);

            // Progress bar at the bottom of the cell.
            final long xp = skillXp != null && i < skillXp.length ? skillXp[i] : 0L;
            final int level = levelForXp(xp);
            final long base = totalXpForLevel(level);
            final long next = totalXpForLevel(level + 1);
            final long span = Math.max(1L, next - base);
            final float progress = level >= MAX_LEVEL ? 1f : Math.max(0f, Math.min(1f, (xp - base) / (float) span));
            final int barX = cx + 10, barY = cy + cellH - 18, barW = cellW - 20, barH = 8;
            shapes.setColor(0.08f, 0.09f, 0.12f, 1f);
            shapes.rect(barX, barY, barW, barH);
            shapes.setColor(0.32f, 0.60f, 0.98f, 1f);
            shapes.rect(barX, barY, barW * progress, barH);

            if (mouseX >= barX && mouseX <= barX + barW && mouseY >= barY && mouseY <= barY + barH) {
                hoverIdx = i;
                hoverBarX = barX;
                hoverBarY = barY;
            }
        }

        shapes.end();
        batch.begin();

        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "SKILLS", x + 14, y + 22);
        font.setColor(0.53f, 0.47f, 0.41f, 1f);
        font.draw(batch, "Press M or ESC to close", x + dialogW - 190, y + 22);

        for (int i = 0; i < SKILL_NAMES.length; i++) {
            final int c = i % cols, r = i / cols;
            final int cx = x + pad + c * (cellW + pad);
            final int cy = y + headerH + pad + r * (cellH + pad);
            final long xp = skillXp != null && i < skillXp.length ? skillXp[i] : 0L;
            final int level = levelForXp(xp);
            font.setColor(Color.WHITE);
            font.draw(batch, SKILL_NAMES[i], cx + 10, cy + 22);
            font.setColor(0.55f, 0.85f, 1f, 1f);
            font.draw(batch, "Level " + level + " / " + MAX_LEVEL, cx + 10, cy + 44);
        }

        if (hoverIdx >= 0) {
            final long xp = skillXp != null && hoverIdx < skillXp.length ? skillXp[hoverIdx] : 0L;
            final int level = levelForXp(xp);
            final long next = totalXpForLevel(level + 1);
            final List<String> lineList = new ArrayList<>();
            lineList.add(SKILL_NAMES[hoverIdx]);
            lineList.add(SKILL_DESC[hoverIdx]);
            lineList.add("Effect: " + SKILL_EFFECT[hoverIdx]);
            lineList.add("XP: " + SKILL_XP_RULE[hoverIdx]);
            lineList.add("Current XP: " + String.format("%,d", xp));
            if (level >= MAX_LEVEL) {
                lineList.add("Max level reached");
            } else {
                lineList.add("Next level: " + String.format("%,d", next) + " XP");
                lineList.add("Remaining: " + String.format("%,d", next - xp) + " XP");
            }
            final String[] lines = lineList.toArray(new String[0]);

            final float lineH = font.getLineHeight();
            float maxLineW = 0f;
            for (final String l : lines) {
                this.layout.setText(font, l);
                if (this.layout.width > maxLineW) maxLineW = this.layout.width;
            }
            final float tipW = maxLineW + 16;
            final float tipH = lineH * lines.length + 12;
            final float tipX = hoverBarX;
            final float tipY = hoverBarY - tipH - 4;

            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.04f, 0.04f, 0.06f, 0.98f);
            shapes.rect(tipX, tipY, tipW, tipH);
            shapes.end();
            batch.begin();
            font.setColor(Color.WHITE);
            for (int li = 0; li < lines.length; li++) {
                font.draw(batch, lines[li], tipX + 8, tipY + tipH - 8 - li * lineH);
            }
        }
    }
}
