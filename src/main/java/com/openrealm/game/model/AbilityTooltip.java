package com.openrealm.game.model;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.game.model.ability.AbilityScaling;

/**
 * Hover tooltip for an active ability cell — own hotbar or a party
 * member's cooldown strip. Port of the webclient's
 * {@code _buildAbilityTooltipHTML} (ui-widgets.js): name, subtitle,
 * description, damage breakdown with scalings, MP cost, effective
 * cooldown / cast time, SP invested.
 */
public class AbilityTooltip {

    private final Ability ability;
    private final int cellIdx;            // 1..4 ("Key N" subtitle); 0 = hide
    private final int investedSp;
    /** Optional viewer stats — used by stat-scaled DAMAGE breakdown. */
    private final Stats viewerStats;
    private final Vector2f pos;
    private final int width;

    private static final int PADDING = 8;
    private static final int LINE_HEIGHT = 18;
    private static final Color BG_COLOR     = new Color(0.12f, 0.12f, 0.15f, 0.95f);
    private static final Color BORDER_COLOR = new Color(0.4f,  0.4f,  0.5f,  1f);
    private static final Color NAME_COLOR   = new Color(1f,    0.85f, 0.2f,  1f);
    private static final Color SUB_COLOR    = new Color(0.78f, 0.66f, 0.43f, 1f);
    private static final Color DESC_COLOR   = new Color(0.85f, 0.85f, 0.85f, 1f);
    private static final Color CD_COLOR     = new Color(0.78f, 0.66f, 0.43f, 1f);
    private static final Color MP_COLOR     = new Color(0.63f, 0.44f, 0.85f, 1f);
    private static final Color CAST_COLOR   = new Color(0.72f, 0.69f, 0.63f, 1f);
    private static final Color DMG_COLOR    = new Color(0.88f, 0.63f, 0.38f, 1f);
    private static final Color DMG_PIERCE   = new Color(0.38f, 0.63f, 1f,    1f);
    private static final Color GREEN_BONUS  = new Color(0.25f, 0.75f, 0.25f, 1f);
    private static final Color SP_COLOR     = new Color(1f,    0.65f, 0.18f, 1f);

    public AbilityTooltip(Ability ability, int cellIdx, int investedSp,
                          Stats viewerStats,
                          Vector2f pos, int width) {
        this.ability = ability;
        this.cellIdx = cellIdx;
        this.investedSp = Math.max(0, investedSp);
        this.viewerStats = viewerStats;
        this.pos = pos;
        this.width = width;
    }

    /** Static helper: pick the viewer's invested SP for a given ability,
     *  or 0 when no entry exists. Mirrors webclient's
     *  {@code window.__game.hotbarInvested[cellIdx-1]}. */
    public static int investedFor(Player player, Ability ability) {
        if (player == null || ability == null) return 0;
        try {
            return player.getSkillLevel(ability.getId());
        } catch (Exception ignored) {
            return 0;
        }
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (this.ability == null) return;

        // Build text lines first so we can size the box before drawing chrome.
        final List<Line> lines = this.buildLines(font);

        // Compute box height from line count.
        final int contentH = lines.size() * LINE_HEIGHT;
        final int boxH = contentH + PADDING * 2;
        final int boxW = this.width;

        // Clamp pos to keep the tooltip on-screen.
        float bx = this.pos.x;
        float by = this.pos.y;
        if (bx + boxW > OpenRealmGame.width - 4) {
            bx = OpenRealmGame.width - 4 - boxW;
        }
        if (bx < 4) bx = 4;
        if (by + boxH > OpenRealmGame.height - 4) {
            by = OpenRealmGame.height - 4 - boxH;
        }
        if (by < 4) by = 4;

        // Background chrome — shapes pass.
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

        // Lines top-down. In the project's flipped ortho cam, font.draw
        // treats (x, y) as the top of the glyph box.
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

        // Header — name.
        lines.add(new Line(this.ability.getName() != null ? this.ability.getName() : "Unknown", NAME_COLOR));

        // Subtitle — "Active Ability — Key N • tag1 · tag2".
        StringBuilder subtitle = new StringBuilder("Active Ability");
        if (this.cellIdx >= 1) subtitle.append(" - Key ").append(this.cellIdx);
        if (this.ability.getTags() != null && !this.ability.getTags().isEmpty()) {
            int added = 0;
            StringBuilder tagBuf = new StringBuilder();
            for (String tag : this.ability.getTags()) {
                if (tag == null || tag.isEmpty() || tag.startsWith("visual_at_self")) continue;
                if (added > 0) tagBuf.append(" / ");
                tagBuf.append(tag);
                if (++added >= 3) break;
            }
            if (tagBuf.length() > 0) subtitle.append(" * ").append(tagBuf);
        }
        lines.add(new Line(subtitle.toString(), SUB_COLOR));

        // Description — word-wrapped.
        if (this.ability.getDescription() != null && !this.ability.getDescription().isEmpty()) {
            for (String l : wrapLines(font, this.ability.getDescription(), maxTextW)) {
                lines.add(new Line(l, DESC_COLOR));
            }
        }

        // Damage breakdown with stat scalings.
        if (this.ability.getBaseDamage() > 0) {
            long total = this.ability.getBaseDamage();
            StringBuilder breakdown = new StringBuilder(Long.toString(this.ability.getBaseDamage()));
            if (this.ability.getScalings() != null) {
                for (AbilityScaling sc : this.ability.getScalings()) {
                    if (sc == null) continue;
                    final String target = sc.getTarget() == null ? "" : sc.getTarget().toUpperCase();
                    if (!"DAMAGE".equals(target)) continue;
                    int contrib = scalingContribution(sc, this.viewerStats, this.investedSp);
                    if (contrib > 0) {
                        total += contrib;
                        final String label = sc.isSkillPointScaling() ? ("SP*" + this.investedSp)
                                : (sc.getStat() != null ? sc.getStat() : "");
                        breakdown.append(" +").append(contrib).append(" (").append(label).append(")");
                    }
                }
            }
            final boolean pierce = (this.ability.getTags() != null
                    && this.ability.getTags().contains("armor_pierce"));
            final Color dmgColor = pierce ? DMG_PIERCE : DMG_COLOR;
            lines.add(new Line((pierce ? "Armor-Pierce Damage: " : "Damage: ") + total, dmgColor));
            for (String wl : wrapLines(font, "= " + breakdown.toString(), maxTextW)) {
                lines.add(new Line("  " + wl, GREEN_BONUS));
            }
        }

        // Effective cooldown (with SP reduction).
        if (this.ability.getBaseCooldownMs() > 0) {
            final long base = this.ability.getBaseCooldownMs();
            final long red = (long) this.investedSp * this.ability.getCdReductionPerPointMs();
            final long eff = Math.max(500L, base - red);
            String cdText = String.format("Cooldown: %.1fs", eff / 1000f);
            if (eff != base) cdText += String.format(" (base %.1fs)", base / 1000f);
            lines.add(new Line(cdText, CD_COLOR));
        }

        // MP cost.
        if (this.ability.getMpCost() > 0) {
            lines.add(new Line("MP Cost: " + this.ability.getMpCost(), MP_COLOR));
        }

        // Cast time (with SP reduction halved, matching webclient).
        long baseCast = this.ability.getBaseCastMs();
        if (baseCast > 0) {
            final long red = (long) this.investedSp * (this.ability.getCdReductionPerPointMs() / 2);
            final long eff = Math.max(150L, baseCast - red);
            String castText = String.format("Cast: %.1fs", eff / 1000f);
            if (eff != baseCast) castText += String.format(" (base %.1fs)", baseCast / 1000f);
            lines.add(new Line(castText, CAST_COLOR));
        } else {
            lines.add(new Line("Cast: Instant", CAST_COLOR));
        }

        // SP invested / max.
        final int maxSp = this.ability.getMaxSkillPoints() > 0 ? this.ability.getMaxSkillPoints() : 5;
        lines.add(new Line("Skill Points: " + this.investedSp + " / " + maxSp, SP_COLOR));

        return lines;
    }

    /** Compute a single scaling's damage contribution against the given
     *  viewer stats and invested-SP level. Mirrors webclient
     *  {@code _scalingContribution} (ui-widgets.js): linear-only port. */
    public static int scalingContribution(AbilityScaling sc,
                                          Stats stats,
                                          int investedSp) {
        if (sc == null) return 0;
        int statVal;
        if (sc.isSkillPointScaling()) {
            statVal = investedSp;
        } else if (stats != null) {
            final int idx = sc.statIndex();
            switch (idx) {
                case 0: statVal = stats.getVit(); break;
                case 1: statVal = stats.getWis(); break;
                case 2: statVal = stats.getHp();  break;
                case 3: statVal = stats.getMp();  break;
                case 4: statVal = stats.getAtt(); break;
                case 5: statVal = stats.getDef(); break;
                case 6: statVal = stats.getSpd(); break;
                case 7: statVal = stats.getDex(); break;
                default: statVal = 0;
            }
        } else {
            statVal = 0;
        }
        float contrib = sc.getCoeff() * statVal;
        if (sc.getCap() > 0 && contrib > sc.getCap()) contrib = sc.getCap();
        return Math.max(0, (int) contrib);
    }

    /** Greedy word wrap so the description doesn't run off the chrome.
     *  Mirrors the webclient's CSS-driven wrap behavior at a fixed width. */
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
