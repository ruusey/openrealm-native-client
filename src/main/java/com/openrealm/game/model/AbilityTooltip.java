package com.openrealm.game.model;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ability.Ability;
import com.openrealm.game.model.ability.AbilityScaling;
import com.openrealm.game.ui.TooltipRenderer;

public class AbilityTooltip {

    private final Ability ability;
    private final int cellIdx;            // 1..4 ("Key N" subtitle); 0 = hide
    private final int investedSp;
    private final Stats viewerStats;
    private final Vector2f pos;
    private final int width;
    /** When true, {@code pos} is the anchor's BOTTOM edge and the box draws above it. */
    private boolean anchorAbove;

    private static final int PADDING = TooltipRenderer.PADDING;
    private static final int LINE_HEIGHT = TooltipRenderer.LINE_HEIGHT;
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

    public AbilityTooltip anchorAbove() {
        this.anchorAbove = true;
        return this;
    }

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

        lines.add(new TooltipLine(this.ability.getName() != null ? this.ability.getName() : "Unknown", NAME_COLOR));

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
        lines.add(new TooltipLine(subtitle.toString(), SUB_COLOR));

        if (this.ability.getDescription() != null && !this.ability.getDescription().isEmpty()) {
            for (String l : TooltipRenderer.wrapLines(font, this.ability.getDescription(), maxTextW)) {
                lines.add(new TooltipLine(l, DESC_COLOR));
            }
        }

        // Damage shown whenever the ability deals damage from any source
        // (base, stat scaling, or both) so scaling-only abilities still surface a number.
        {
            long total = this.ability.getBaseDamage();
            final List<String> parts = new ArrayList<>();
            if (this.ability.getBaseDamage() > 0) {
                parts.add(Long.toString(this.ability.getBaseDamage()));
            }
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
                        parts.add("+" + contrib + " (" + label + ")");
                    }
                }
            }
            if (total > 0) {
                final boolean pierce = (this.ability.getTags() != null
                        && this.ability.getTags().contains("armor_pierce"));
                final Color dmgColor = pierce ? DMG_PIERCE : DMG_COLOR;
                lines.add(new TooltipLine((pierce ? "Armor-Pierce Damage: " : "Damage: ") + total, dmgColor));
                final String breakdown = parts.isEmpty() ? Long.toString(total) : String.join(" ", parts);
                for (String wl : TooltipRenderer.wrapLines(font, "= " + breakdown, maxTextW)) {
                    lines.add(new TooltipLine("  " + wl, GREEN_BONUS));
                }
            }
        }

        if (this.ability.getBaseCooldownMs() > 0) {
            final long base = this.ability.getBaseCooldownMs();
            final long red = (long) this.investedSp * this.ability.getCdReductionPerPointMs();
            final long eff = Math.max(500L, base - red);
            String cdText = String.format("Cooldown: %.1fs", eff / 1000f);
            if (eff != base) cdText += String.format(" (base %.1fs)", base / 1000f);
            lines.add(new TooltipLine(cdText, CD_COLOR));
        }

        if (this.ability.getMpCost() > 0) {
            lines.add(new TooltipLine("MP Cost: " + this.ability.getMpCost(), MP_COLOR));
        }

        // SP cast-time reduction is halved, matching webclient.
        long baseCast = this.ability.getBaseCastMs();
        if (baseCast > 0) {
            final long red = (long) this.investedSp * (this.ability.getCdReductionPerPointMs() / 2);
            final long eff = Math.max(150L, baseCast - red);
            String castText = String.format("Cast: %.1fs", eff / 1000f);
            if (eff != baseCast) castText += String.format(" (base %.1fs)", baseCast / 1000f);
            lines.add(new TooltipLine(castText, CAST_COLOR));
        } else {
            lines.add(new TooltipLine("Cast: Instant", CAST_COLOR));
        }

        final int maxSp = this.ability.getMaxSkillPoints() > 0 ? this.ability.getMaxSkillPoints() : 5;
        lines.add(new TooltipLine("Skill Points: " + this.investedSp + " / " + maxSp, SP_COLOR));

        return lines;
    }

    /** Linear-only damage contribution of one scaling; skill-point scalings use investedSp. */
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
                case 4: statVal = stats.getStr(); break;
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
}
