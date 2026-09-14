package com.openrealm.game.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ability.PassiveAbility;
import com.openrealm.game.ui.TooltipRenderer;

public class PassiveTooltip {

    private final PassiveAbility passive;
    private final Stats viewerStats;
    private final Vector2f pos;
    private final int width;
    /** When true, {@code pos} is the anchor's BOTTOM edge and the box floats above it. */
    private boolean anchorAbove;

    private static final int PADDING = TooltipRenderer.PADDING;
    private static final int LINE_HEIGHT = TooltipRenderer.LINE_HEIGHT;
    private static final Color NAME_COLOR   = new Color(0.85f, 0.85f, 1f,    1f);
    private static final Color SUB_COLOR    = new Color(0.78f, 0.66f, 0.43f, 1f);
    private static final Color DESC_COLOR   = new Color(0.85f, 0.85f, 0.85f, 1f);

    // Template syntax: {STAT}, {STAT/N}, {STAT*N}, {STAT+N}, {STAT-N}.
    private static final Pattern STAT_TEMPLATE =
            Pattern.compile("\\{([A-Za-z]{2,3})\\s*(?:([/*+\\-])\\s*(\\d+))?\\}");

    public PassiveTooltip(PassiveAbility passive, Stats viewerStats,
                          Vector2f pos, int width) {
        this.passive = passive;
        this.viewerStats = viewerStats;
        this.pos = pos;
        this.width = width;
    }

    public PassiveTooltip anchorAbove() {
        this.anchorAbove = true;
        return this;
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.passive == null) return;

        final List<TooltipLine> lines = this.buildLines(font);

        final int contentH = lines.size() * LINE_HEIGHT;
        final int boxH = contentH + PADDING * 2;
        final int boxW = this.width;

        final Vector2 origin = TooltipRenderer.clampBox(this.pos.x, this.pos.y, boxW, boxH, this.anchorAbove);
        final float bx = origin.x;
        final float by = origin.y;

        TooltipRenderer.drawFrame(batch, shapes, bx, by, boxW, boxH);

        float ty = by + PADDING + LINE_HEIGHT - 4;
        for (TooltipLine line : lines) {
            font.setColor(line.color);
            font.draw(batch, line.text, bx + PADDING, ty);
            ty += LINE_HEIGHT;
        }
        font.setColor(Color.WHITE);
    }

    private List<TooltipLine> buildLines(BitmapFont font) {
        final List<TooltipLine> lines = new ArrayList<>();
        final int maxTextW = this.width - PADDING * 2;

        lines.add(new TooltipLine(this.passive.getName() != null ? this.passive.getName() : "Passive",
                NAME_COLOR));
        lines.add(new TooltipLine("Class Passive - always on", SUB_COLOR));

        if (this.passive.getDescription() != null && !this.passive.getDescription().isEmpty()) {
            final String resolved = substituteStatTemplates(
                    this.passive.getDescription(), this.viewerStats);
            for (String l : TooltipRenderer.wrapLines(font, resolved, maxTextW)) {
                lines.add(new TooltipLine(l, DESC_COLOR));
            }
        }

        return lines;
    }

    /** Unknown stat names are left intact so designer typos stay visible. */
    public static String substituteStatTemplates(String desc, Stats stats) {
        if (desc == null || desc.isEmpty()) return desc;
        if (stats == null) return desc;
        final Matcher m = STAT_TEMPLATE.matcher(desc);
        final StringBuilder out = new StringBuilder();
        while (m.find()) {
            final String statName = m.group(1).toUpperCase();
            final String op = m.group(2);
            final String nStr = m.group(3);
            final Integer raw = lookupStat(stats, statName);
            final String replacement;
            if (raw == null) {
                replacement = Matcher.quoteReplacement(m.group(0));
            } else if (op == null) {
                replacement = Integer.toString(raw);
            } else {
                final int n = Integer.parseInt(nStr);
                final int v;
                switch (op) {
                    case "/": v = (n > 0) ? raw / n : 0; break;
                    case "*": v = raw * n; break;
                    case "+": v = raw + n; break;
                    case "-": v = Math.max(0, raw - n); break;
                    default:  v = raw; break;
                }
                replacement = Integer.toString(v);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Integer lookupStat(Stats stats, String name) {
        switch (name) {
            case "STR": return (int) stats.getStr();
            case "DEF": return (int) stats.getDef();
            case "SPD": return (int) stats.getSpd();
            case "DEX": return (int) stats.getDex();
            case "VIT": return (int) stats.getVit();
            case "WIS": return (int) stats.getWis();
            case "HP":  return stats.getHp();
            case "MP":  return (int) stats.getMp();
            default:    return null;
        }
    }
}
