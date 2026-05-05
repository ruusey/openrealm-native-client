package com.openrealm.game.model;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.entity.item.AttributeModifier;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Rarity;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Vector2f;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemTooltip {
    private Vector2f pos;
    private int width;
    private int height;

    private String title;
    private String description;

    private int minDamage;
    private int maxDamage;

    private byte targetClass;
    private byte tier;
    private byte rarity;
    private String category;
    private byte gemEffectType;
    private byte gemParam1;
    private short gemMagnitude;
    private int gemDurationMs;

    private Stats stats;
    private List<Enchantment> enchantments;
    private List<AttributeModifier> attributeModifiers;
    private byte targetSlot;

    private static final int PADDING = 8;
    private static final int LINE_HEIGHT = 22;
    private static final Color BG_COLOR = new Color(0.12f, 0.12f, 0.15f, 0.95f);
    private static final Color BORDER_COLOR = new Color(0.4f, 0.4f, 0.5f, 1f);
    private static final Color TITLE_COLOR = new Color(1f, 0.85f, 0.2f, 1f);
    private static final Color DESC_COLOR = new Color(0.75f, 0.75f, 0.75f, 1f);
    private static final Color STAT_POS_COLOR = new Color(0.3f, 1f, 0.3f, 1f);
    private static final Color STAT_NEG_COLOR = new Color(1f, 0.3f, 0.3f, 1f);
    private static final Color INFO_COLOR = new Color(0.6f, 0.8f, 1f, 1f);
    private static final Color HEADER_COLOR = new Color(0.78f, 0.66f, 0.43f, 1f);
    private static final Color GEM_COLOR = new Color(0.94f, 0.75f, 0.38f, 1f);
    private static final Color AFFIX_COLOR = new Color(0.75f, 0.63f, 0.88f, 1f);

    private static final String[] STAT_LABELS = {"VIT","WIS","HP","MP","ATT","DEF","SPD","DEX"};
    private static final String[] STATUS_EFFECT_NAMES = {
        "Invisible","Healing","Paralyzed","Stunned","Speedy","Healed","Invincible","",
        "None","Teleported","","Dazed","","","Damaging","Stasis",
        "Cursed","Poisoned","Armored","Berserk","","Slowed","Armor Broken"
    };

    public ItemTooltip(GameItem item, Vector2f pos, int width, int height) {
        this.pos = pos;
        this.width = width;
        this.height = height;
        this.title = item.getName();
        this.description = item.getDescription();

        if (item.getDamage() != null) {
            this.minDamage = item.getDamage().getMin();
            this.maxDamage = item.getDamage().getMax();
        }

        this.targetClass = item.getTargetClass();
        this.tier = item.getTier();
        this.rarity = item.getRarity();
        this.category = item.getCategory();
        this.gemEffectType = item.getGemEffectType();
        this.gemParam1 = item.getGemParam1();
        this.gemMagnitude = item.getGemMagnitude();
        this.gemDurationMs = item.getGemDurationMs();
        this.targetSlot = item.getTargetSlot();
        this.stats = item.getStats();
        this.enchantments = item.getEnchantments();
        this.attributeModifiers = item.getAttributeModifiers();
    }

    private String getClassName() {
        CharacterClass cls = CharacterClass.valueOf((int) this.targetClass);
        if (cls == null) return "Unknown";
        return cls.name();
    }

    /** Convert ARGB int to a libGDX Color. */
    private static Color argbToColor(int argb) {
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        return new Color(r, g, b, a == 0f ? 1f : a);
    }

    /** Plain-text effect description for an enchantment row. Mirrors the
     *  describeEnchantment() helper on the web client. */
    private static String describeEnchantment(Enchantment e) {
        if (e == null) return "";
        final byte eff = e.getEffectType();
        switch (eff) {
            case 0: { // STAT_DELTA
                final int sid = (e.getParam1() != 0 || e.getMagnitude() != 0) ? e.getParam1() : e.getStatId();
                final int mag = (e.getMagnitude() != 0) ? e.getMagnitude() : e.getDeltaValue();
                final String sign = mag > 0 ? "+" : "";
                return sign + mag + " " + safeStat(sid);
            }
            case 1: // STAT_SCALE
                return (e.getMagnitude() > 0 ? "+" : "") + e.getMagnitude() + "% " + safeStat(e.getParam1()) + " Scaling";
            case 2: return "+" + e.getMagnitude() + " Projectile" + (e.getMagnitude() == 1 ? "" : "s");
            case 3: return "+" + e.getMagnitude() + "% Projectile Damage";
            case 4: {
                final String name = (e.getParam1() >= 0 && e.getParam1() < STATUS_EFFECT_NAMES.length)
                        ? STATUS_EFFECT_NAMES[e.getParam1()] : "Effect " + e.getParam1();
                return name + " on hit (" + (e.getDurationMs() / 1000.0f) + "s)";
            }
            case 5: return e.getMagnitude() + "% Lifesteal";
            case 6: return e.getMagnitude() + "% Crit Chance";
            default: return "Unknown effect " + eff;
        }
    }

    private static String safeStat(int statId) {
        if (statId < 0 || statId >= STAT_LABELS.length) return "?";
        return STAT_LABELS[statId];
    }

    /** Color the dot/text by effect type — stat color for STAT_DELTA/SCALE,
     *  fixed effect color for the others. Mirrors web client gem palette. */
    private static Color enchColor(Enchantment e) {
        if (e == null) return Color.WHITE;
        final byte eff = e.getEffectType();
        if (eff == 0 || eff == 1) {
            return argbToColor(e.getPixelColor() == 0 ? 0xFFFFFFFF : e.getPixelColor());
        }
        switch (eff) {
            case 2: return argbToColor(0xFFFFD700); // PROJECTILE_COUNT — gold
            case 3: return argbToColor(0xFFFF4040); // PROJECTILE_DAMAGE — red
            case 4: return argbToColor(0xFFA040FF); // ON_HIT_EFFECT — purple
            case 5: return argbToColor(0xFF40FF80); // LIFESTEAL — green
            case 6: return argbToColor(0xFFFFA000); // CRIT_CHANCE — orange
            default: return Color.WHITE;
        }
    }

    private List<TooltipLine> buildLines() {
        List<TooltipLine> lines = new ArrayList<>();

        // Title — colored by rarity so the player's eye lands on it first.
        if (this.title != null && !this.title.isEmpty()) {
            final Color titleColor = (this.rarity > 0)
                    ? argbToColor(Rarity.fromOrdinal(this.rarity).color)
                    : TITLE_COLOR;
            lines.add(new TooltipLine(this.title, titleColor));
        }

        // Subtitle: rarity · tier · class · consumable
        final List<String> subtitleBits = new ArrayList<>();
        subtitleBits.add(Rarity.fromOrdinal(this.rarity).displayName);
        if (this.tier >= 0) subtitleBits.add("Tier " + this.tier);
        if (this.targetClass >= 0) subtitleBits.add(getClassName());
        if (!subtitleBits.isEmpty()) {
            final Color rarityColor = argbToColor(Rarity.fromOrdinal(this.rarity).color);
            lines.add(new TooltipLine(String.join(" · ", subtitleBits), rarityColor));
        }

        // Description - wrap long text to fit tooltip width
        if (this.description != null && !this.description.isEmpty()) {
            int charWidth = 7;
            int maxCharsPerLine = Math.max(8, (this.width - PADDING * 2) / charWidth);
            String[] words = this.description.split(" ");
            StringBuilder currentLine = new StringBuilder();
            for (String word : words) {
                if (currentLine.length() == 0) {
                    currentLine.append(word);
                } else if (currentLine.length() + 1 + word.length() <= maxCharsPerLine) {
                    currentLine.append(" ").append(word);
                } else {
                    lines.add(new TooltipLine(currentLine.toString(), DESC_COLOR));
                    currentLine = new StringBuilder(word);
                }
            }
            if (currentLine.length() > 0) {
                lines.add(new TooltipLine(currentLine.toString(), DESC_COLOR));
            }
        }

        lines.add(new TooltipLine("", null));

        if (this.maxDamage > 0) {
            lines.add(new TooltipLine("Damage: " + this.minDamage + " - " + this.maxDamage, INFO_COLOR));
        }

        // Stats
        if (this.stats != null) {
            List<String> statParts = new ArrayList<>();
            this.addStat(statParts, "HP", this.stats.getHp());
            this.addStat(statParts, "MP", this.stats.getMp());
            this.addStat(statParts, "ATT", this.stats.getAtt());
            this.addStat(statParts, "DEF", this.stats.getDef());
            this.addStat(statParts, "SPD", this.stats.getSpd());
            this.addStat(statParts, "DEX", this.stats.getDex());
            this.addStat(statParts, "VIT", this.stats.getVit());
            this.addStat(statParts, "WIS", this.stats.getWis());

            if (!statParts.isEmpty()) {
                for (String part : statParts) {
                    boolean positive = part.contains("+");
                    lines.add(new TooltipLine(part, positive ? STAT_POS_COLOR : STAT_NEG_COLOR));
                }
            }
        }

        // Random attribute-modifier affixes ("of the Bear: +2 VIT")
        if (this.attributeModifiers != null && !this.attributeModifiers.isEmpty()) {
            lines.add(new TooltipLine("", null));
            lines.add(new TooltipLine("Affix:", HEADER_COLOR));
            for (AttributeModifier m : this.attributeModifiers) {
                final String sign = m.getDeltaValue() > 0 ? "+" : "";
                lines.add(new TooltipLine("  " + sign + m.getDeltaValue() + " " + safeStat(m.getStatId()),
                        m.getDeltaValue() >= 0 ? STAT_POS_COLOR : STAT_NEG_COLOR));
            }
        }

        // Gem template description (gem items in inventory show their pending effect).
        if ("gem".equals(this.category) && this.gemEffectType >= 0) {
            lines.add(new TooltipLine("", null));
            final Enchantment preview = new Enchantment((byte) 0, (byte) 0, (byte) 0, (byte) 0, 0,
                    this.gemEffectType, this.gemParam1, this.gemMagnitude, this.gemDurationMs);
            lines.add(new TooltipLine("Gem Effect: " + describeEnchantment(preview), GEM_COLOR));
        }

        // Forged enchantments — one row per gem with its effect description.
        // Empty equipment shows the available slot count so the rarity ceiling is visible.
        if (this.targetSlot >= 0 && this.targetSlot <= 3) {
            final int slotCap = Rarity.slotsFor(this.rarity);
            final int filled = (this.enchantments == null) ? 0 : this.enchantments.size();
            lines.add(new TooltipLine("", null));
            lines.add(new TooltipLine("Forged (" + filled + "/" + slotCap + ")", HEADER_COLOR));
            if (this.enchantments != null) {
                for (Enchantment e : this.enchantments) {
                    lines.add(new TooltipLine("  " + describeEnchantment(e), enchColor(e)));
                }
            }
        }

        return lines;
    }

    private void addStat(List<String> parts, String name, int value) {
        if (value != 0) {
            String sign = value > 0 ? "+" : "";
            parts.add(name + " " + sign + value);
        }
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        List<TooltipLine> lines = this.buildLines();

        int contentHeight = PADDING * 2 + (lines.size() * LINE_HEIGHT);
        int tooltipWidth = this.width;
        int tooltipHeight = Math.max(contentHeight, LINE_HEIGHT * 3);

        float drawX = this.pos.x;
        float drawY = this.pos.y;

        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Border tinted by rarity so the whole tooltip reflects the item's tier.
        final Color border = (this.rarity > 0) ? argbToColor(Rarity.fromOrdinal(this.rarity).color) : BORDER_COLOR;
        shapes.setColor(border);
        shapes.rect(drawX - 2, drawY - 2, tooltipWidth + 4, tooltipHeight + 4);
        shapes.setColor(BG_COLOR);
        shapes.rect(drawX, drawY, tooltipWidth, tooltipHeight);
        shapes.end();
        batch.begin();

        float textX = drawX + PADDING;
        float textY = drawY + PADDING + LINE_HEIGHT;

        for (int i = 0; i < lines.size(); i++) {
            TooltipLine line = lines.get(i);
            if (line.color == null || line.text.isEmpty()) {
                textY += LINE_HEIGHT / 2f;
                continue;
            }
            font.setColor(line.color);
            font.draw(batch, line.text, textX, textY);
            textY += LINE_HEIGHT;
        }

        font.setColor(Color.WHITE);
    }

    private static class TooltipLine {
        String text;
        Color color;

        TooltipLine(String text, Color color) {
            this.text = text;
            this.color = color;
        }
    }
}
