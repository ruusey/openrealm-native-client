package com.openrealm.game.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ability.PassiveAbility;

/**
 * Hover tooltip for the class-passive cell (slot 0 in the hotbar). Mirror
 * of the webclient's _buildAbilityTooltipHTML when kind === 'passive':
 * name, "Class Passive - always on" subtitle, description with live
 * {STAT}/{STAT/N}/{STAT*N}/{STAT+N}/{STAT-N} substitution against the
 * viewer's current stats.
 *
 * Kept structurally parallel to {@link AbilityTooltip} (same chrome,
 * same per-line rendering loop) so the two surfaces feel identical on
 * screen even though their content differs.
 */
public class PassiveTooltip {

    private final PassiveAbility passive;
    private final Stats viewerStats;
    private final Vector2f pos;
    private final int width;

    private static final int PADDING = 8;
    private static final int LINE_HEIGHT = 18;
    private static final Color BG_COLOR     = new Color(0.12f, 0.12f, 0.15f, 0.95f);
    private static final Color BORDER_COLOR = new Color(0.4f,  0.4f,  0.5f,  1f);
    private static final Color NAME_COLOR   = new Color(0.85f, 0.85f, 1f,    1f); // pale blue tint distinguishes passive from active
    private static final Color SUB_COLOR    = new Color(0.78f, 0.66f, 0.43f, 1f);
    private static final Color DESC_COLOR   = new Color(0.85f, 0.85f, 0.85f, 1f);

    // Same syntax as webclient ui-widgets._substituteStatTemplates:
    //   {STAT}, {STAT/N}, {STAT*N}, {STAT+N}, {STAT-N}.
    private static final Pattern STAT_TEMPLATE =
            Pattern.compile("\\{([A-Za-z]{2,3})\\s*(?:([/*+\\-])\\s*(\\d+))?\\}");

    public PassiveTooltip(PassiveAbility passive, Stats viewerStats,
                          Vector2f pos, int width) {
        this.passive = passive;
        this.viewerStats = viewerStats;
        this.pos = pos;
        this.width = width;
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.passive == null) return;

        final List<Line> lines = this.buildLines(font);

        final int contentH = lines.size() * LINE_HEIGHT;
        final int boxH = contentH + PADDING * 2;
        final int boxW = this.width;

        // Clamp on-screen — same logic as AbilityTooltip.
        float bx = this.pos.x;
        float by = this.pos.y;
        if (bx + boxW > OpenRealmGame.width  - 4) bx = OpenRealmGame.width  - 4 - boxW;
        if (bx < 4) bx = 4;
        if (by + boxH > OpenRealmGame.height - 4) by = OpenRealmGame.height - 4 - boxH;
        if (by < 4) by = 4;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(BG_COLOR);
        shapes.rect(bx, by, boxW, boxH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(BORDER_COLOR);
        shapes.rect(bx, by, boxW, boxH);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

        float ty = by + PADDING + LINE_HEIGHT - 4;
        for (Line line : lines) {
            font.setColor(line.color);
            font.draw(batch, line.text, bx + PADDING, ty);
            ty += LINE_HEIGHT;
        }
        font.setColor(Color.WHITE);
    }

    private List<Line> buildLines(BitmapFont font) {
        final List<Line> lines = new ArrayList<>();
        final int maxTextW = this.width - PADDING * 2;

        lines.add(new Line(this.passive.getName() != null ? this.passive.getName() : "Passive",
                NAME_COLOR));
        lines.add(new Line("Class Passive - always on", SUB_COLOR));

        if (this.passive.getDescription() != null && !this.passive.getDescription().isEmpty()) {
            final String resolved = substituteStatTemplates(
                    this.passive.getDescription(), this.viewerStats);
            for (String l : wrapLines(font, resolved, maxTextW)) {
                lines.add(new Line(l, DESC_COLOR));
            }
        }

        return lines;
    }

    /** Live-substitute {STAT} placeholders. Unknown stat names are left
     *  intact so designer typos remain visible. */
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
                // Leave the placeholder so the typo is visible.
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

    private static List<String> wrapLines(BitmapFont font, String text, int maxWidth) {
        final List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty() || maxWidth <= 0) return out;
        final String[] words = text.split("\\s+");
        final GlyphLayout layout = new GlyphLayout();
        StringBuilder current = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            final String trial = current.length() == 0 ? w : current + " " + w;
            layout.setText(font, trial);
            if (layout.width <= maxWidth) {
                current.setLength(0);
                current.append(trial);
            } else {
                if (current.length() > 0) out.add(current.toString());
                current.setLength(0);
                current.append(w);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    private static final class Line {
        final String text;
        final Color color;
        Line(String text, Color color) { this.text = text; this.color = color; }
    }
}
