package com.openrealm.game.model;

import com.openrealm.game.OpenRealmGame;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.AttributeModifier;
import com.openrealm.game.entity.item.Enchantment;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.entity.item.Rarity;
import com.openrealm.game.entity.item.Stats;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.ui.UiRender;

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
    /** Which stat the weapon's damage scales off of (0..7). Default 4 = STR. */
    private byte scalingStat = 4;

    private byte targetClass;
    private byte tier;
    private byte rarity;
    private String category;
    private byte gemstoneType;
    private List<Integer> socketSlots;

    private Stats stats;
    private List<Enchantment> enchantments;
    private List<AttributeModifier> attributeModifiers;
    private byte targetSlot;

    /** Weapon archetype + projectile group, captured for the DPS estimate. */
    private byte archetypeId;
    private int projectileGroupId;

    /** Local player's classId; -1 suppresses the compatibility row. */
    private int viewerClassId = -1;

    /** Local player's computed stats; null hides the DPS line. */
    private Stats viewerStats;

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

    private static final String[] STAT_LABELS = {"VIT","WIS","HP","MP","STR","DEF","SPD","DEX"};
    private static final String[] STATUS_EFFECT_NAMES = {
        "Hidden","Healing","Paralyzed","Stunned","Speedy","Healed","Invincible","",
        "None","Teleported","","Dazed","","","Damaging","Stasis",
        "Cursed","Poisoned","Armored","Berserk","","Slowed","Armor Broken"
    };

    public ItemTooltip(GameItem item, Vector2f pos, int width, int height, int viewerClassId) {
        this(item, pos, width, height);
        this.viewerClassId = viewerClassId;
    }

    public ItemTooltip(GameItem item, Vector2f pos, int width, int height) {
        this.pos = pos;
        this.width = width;
        this.height = height;
        this.title = item.getName();
        this.description = item.getDescription();

        if (item.getDamage() != null) {
            this.minDamage = item.getDamage().getMin();
            this.maxDamage = item.getDamage().getMax();
            this.projectileGroupId = item.getDamage().getProjectileGroupId();
        }
        this.scalingStat = item.getScalingStat();
        this.archetypeId = item.getArchetypeId();

        this.targetClass = item.getTargetClass();
        this.tier = item.getTier();
        this.rarity = item.getRarity();
        this.category = item.getCategory();
        this.gemstoneType = item.getGemstoneType();
        this.socketSlots = item.getSocketSlots();
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

    /** Label for an item's class requirement: specific classes or role/weapon-family buckets. */
    private static String compatibilityLabel(byte targetClass) {
        switch ((int) targetClass) {
            case -1: return "Robe classes";
            case -2: return "Leather classes";
            case -3: return "Heavy classes";
            case -4: return "All classes";
            case -5: return "Staff users";
            case -6: return "Wand users";
            case -7: return "Dagger users";
            case -8: return "Bow users";
            default: {
                CharacterClass c = CharacterClass.valueOf((int) targetClass);
                if (c == null) return "Unknown class";
                final String n = c.name();
                if (n.isEmpty()) return n;
                return n.charAt(0) + n.substring(1).toLowerCase().replace('_', ' ');
            }
        }
    }

    /** Client-side hint only; the server authoritatively rejects bad equips via canEquip(). */
    private static boolean isCompatible(int viewerClassId, byte targetClass) {
        if (targetClass < 0) return true;
        return targetClass == (byte) viewerClassId;
    }

    private static Color argbToColor(int argb) {
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        return new Color(r, g, b, a == 0f ? 1f : a);
    }

    private static String describeEnchantment(Enchantment e) {
        if (e == null) return "";
        final int mag = e.getDeltaValue();
        final String sign = mag > 0 ? "+" : "";
        return sign + mag + " " + safeStat(e.getStatId());
    }

    private static String safeStat(int statId) {
        if (statId < 0 || statId >= STAT_LABELS.length) return "?";
        return STAT_LABELS[statId];
    }

    private static Color enchColor(Enchantment e) {
        if (e == null) return Color.WHITE;
        return argbToColor(e.getPixelColor() == 0 ? 0xFFFFFFFF : e.getPixelColor());
    }

    /** Keep in sync with the server's GemstoneRegistry. */
    private static String gemstoneName(byte typeId) {
        switch (typeId) {
            case 1: return "Vampiric Gem";
            case 2: return "Crit Gem";
            case 3: return "Multishot Gem";
            case 4: return "Venom Gem";
            case 5: return "Frost Gem";
            case 6: return "Thorns Gem";
            case 7: return "Crushing Gem";
            case 8: return "Wisdom Scaling Gem";
            case 9: return "Swift Scaling Gem";
            case 10: return "Attack Scaling Gem";
            case 11: return "Defense Scaling Gem";
            case 12: return "Dexterity Scaling Gem";
            case 13: return "Vitality Scaling Gem";
            case 14: return "Health Scaling Gem";
            case 15: return "Mana Scaling Gem";
            default: return "Gem " + typeId;
        }
    }

    /** Prefers the item's data-driven socketSlots; falls back to the
     *  per-type default mirroring Gemstone.canSocketInto on the server. */
    private String gemAllowedSlotNames() {
        final String[] names = {"Weapon", "Armor", "Gauntlet", "Boots", "Ring"};
        if (this.socketSlots != null && !this.socketSlots.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < this.socketSlots.size(); i++) {
                final int s = this.socketSlots.get(i);
                if (i > 0) sb.append(", ");
                sb.append(s >= 0 && s < names.length ? names[s] : ("slot " + s));
            }
            return sb.toString();
        }
        switch (this.gemstoneType) {
            case 1: case 2: case 3: case 4: case 5: case 7: return "Weapon";
            case 6: return "Armor, Gauntlet, Boots";
            case 8: case 9: case 10: case 11: case 12: case 13: case 14: case 15:
                return "Weapon, Armor, Gauntlet, Boots, Ring";
            default: return "?";
        }
    }

    private List<TooltipLine> buildLines() {
        List<TooltipLine> lines = new ArrayList<>();

        if (this.title != null && !this.title.isEmpty()) {
            final Color titleColor = (this.rarity > 0)
                    ? argbToColor(Rarity.fromOrdinal(this.rarity).color)
                    : TITLE_COLOR;
            lines.add(new TooltipLine(this.title, titleColor));
        }

        final List<String> subtitleBits = new ArrayList<>();
        subtitleBits.add(Rarity.fromOrdinal(this.rarity).displayName);
        if (this.tier >= 0) subtitleBits.add("Tier " + this.tier);
        if (this.targetClass >= 0) subtitleBits.add(getClassName());
        if (!subtitleBits.isEmpty()) {
            final Color rarityColor = argbToColor(Rarity.fromOrdinal(this.rarity).color);
            lines.add(new TooltipLine(String.join(" - ", subtitleBits), rarityColor));
        }

        if (this.viewerClassId >= 0) {
            final boolean ok = isCompatible(this.viewerClassId, this.targetClass);
            final String label = compatibilityLabel(this.targetClass);
            // -4 ALL is always usable, so show a single info line rather than a green/red call-out.
            if (this.targetClass == (byte) -4) {
                lines.add(new TooltipLine("Usable by: Any class", INFO_COLOR));
            } else {
                final String prefix = ok ? "Compatible: " : "Cannot equip: requires ";
                lines.add(new TooltipLine(prefix + label, ok ? STAT_POS_COLOR : STAT_NEG_COLOR));
            }
        }

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
            final String scalesWith = (this.scalingStat >= 0 && this.scalingStat < STAT_LABELS.length)
                    ? STAT_LABELS[this.scalingStat] : "?";
            lines.add(new TooltipLine(
                    "Damage: " + this.minDamage + " - " + this.maxDamage + "  (scales with " + scalesWith + ")",
                    INFO_COLOR));
            final int[] dps = this.computeDps();
            if (dps != null) {
                final String shotLabel = dps[1] == 1 ? "1 shot" : dps[1] + " shots";
                lines.add(new TooltipLine(
                        "DPS: " + dps[0] + "  (" + shotLabel + " - " + (dps[2] / 100f) + "/s)",
                        GEM_COLOR));
            }
        }

        if (this.stats != null) {
            List<String> statParts = new ArrayList<>();
            this.addStat(statParts, "HP", this.stats.getHp());
            this.addStat(statParts, "MP", this.stats.getMp());
            this.addStat(statParts, "STR", this.stats.getStr());
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

        if (this.attributeModifiers != null && !this.attributeModifiers.isEmpty()) {
            lines.add(new TooltipLine("", null));
            lines.add(new TooltipLine("Affix:", HEADER_COLOR));
            for (AttributeModifier m : this.attributeModifiers) {
                final String sign = m.getDeltaValue() > 0 ? "+" : "";
                lines.add(new TooltipLine("  " + sign + m.getDeltaValue() + " " + safeStat(m.getStatId()),
                        m.getDeltaValue() >= 0 ? STAT_POS_COLOR : STAT_NEG_COLOR));
            }
        }

        if ("gem".equals(this.category) && this.gemstoneType != 0) {
            lines.add(new TooltipLine("", null));
            lines.add(new TooltipLine("Gem: " + gemstoneName(this.gemstoneType), GEM_COLOR));
            lines.add(new TooltipLine("Sockets into: " + gemAllowedSlotNames(), GEM_COLOR));
        } else if (this.gemstoneType != 0 && this.targetSlot >= 0 && this.targetSlot <= 4) {
            lines.add(new TooltipLine("", null));
            lines.add(new TooltipLine("Socketed: " + gemstoneName(this.gemstoneType), GEM_COLOR));
        }

        // Empty equipment still shows the slot count so the rarity ceiling is visible.
        if (this.targetSlot >= 0 && this.targetSlot <= 4) {
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

    /** DPS estimate as {dps, bulletsPerAttack, attacksPerSecond*100}, or null when
     *  the item isn't a weapon or no viewer stats are set. Mirrors the server
     *  basic-attack pipeline:
     *    aps     = floor((6.5*(DEX+17.3))/75) * archetype.attackSpeedMul
     *    perShot = (avgRoll + scalingStatValue) * archetype.damageMul * gem mods
     *    bullets = projectilesInGroup * (archetype.projectileCount + gemExtraProjectiles)
     *  Scaling-gem stat bonuses already live in the passed-in stats, so only the
     *  per-shot gems (crit/multishot/crushing) are applied here. */
    private int[] computeDps() {
        if (this.maxDamage <= 0 || this.viewerStats == null || this.targetSlot != 0) return null;
        final double avg = (this.minDamage + this.maxDamage) / 2.0;

        final WeaponArchetypeModel arch = (GameDataManager.WEAPON_ARCHETYPES != null)
                ? GameDataManager.WEAPON_ARCHETYPES.get(this.archetypeId) : null;
        final double damageMul = (arch != null && arch.getDamageMul() > 0f) ? arch.getDamageMul() : 1.0;
        final double attackSpeedMul = (arch != null && arch.getAttackSpeedMul() > 0f) ? arch.getAttackSpeedMul() : 1.0;
        final int archProjectiles = (arch != null && arch.getProjectileCount() > 0) ? arch.getProjectileCount() : 1;

        double perShot = (avg + statByIndex(this.viewerStats, this.scalingStat)) * damageMul;
        if (this.gemstoneType == 7 || this.gemstoneType == 2) perShot *= 1.15; // Crushing +15% / Crit +15% expected
        final int extraProjectiles = (this.gemstoneType == 3) ? 1 : 0;         // Multishot +1

        final ProjectileGroup group = (GameDataManager.PROJECTILE_GROUPS != null)
                ? GameDataManager.PROJECTILE_GROUPS.get(this.projectileGroupId) : null;
        final int groupShots = (group != null && group.getProjectiles() != null && !group.getProjectiles().isEmpty())
                ? group.getProjectiles().size() : 1;
        final int bullets = groupShots * Math.max(1, archProjectiles + extraProjectiles);

        final int dex = this.viewerStats.getDex();
        final double attacksPerSecond = Math.floor((6.5 * (dex + 17.3)) / 75.0) * attackSpeedMul;

        final int dps = (int) Math.round(perShot * bullets * attacksPerSecond);
        return new int[] { dps, bullets, (int) Math.round(attacksPerSecond * 100) };
    }

    /** Stat lookup: 0=VIT 1=WIS 2=HP 3=MP 4=STR 5=DEF 6=SPD 7=DEX. */
    private static int statByIndex(Stats s, int idx) {
        if (s == null) return 0;
        switch (idx) {
            case 0: return s.getVit();
            case 1: return s.getWis();
            case 2: return s.getHp();
            case 3: return s.getMp();
            case 4: return s.getStr();
            case 5: return s.getDef();
            case 6: return s.getSpd();
            case 7: return s.getDex();
            default: return s.getStr();
        }
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        List<TooltipLine> lines = this.buildLines();

        int contentHeight = PADDING * 2 + (lines.size() * LINE_HEIGHT);
        int tooltipWidth = this.width;
        int tooltipHeight = Math.max(contentHeight, LINE_HEIGHT * 3);

        float drawX = this.pos.x;
        float drawY = this.pos.y;
        if (drawX + tooltipWidth > OpenRealmGame.width - 4) drawX = OpenRealmGame.width - 4 - tooltipWidth;
        if (drawX < 4) drawX = 4;
        if (drawY + tooltipHeight > OpenRealmGame.height - 4) drawY = OpenRealmGame.height - 4 - tooltipHeight;
        if (drawY < 4) drawY = 4;

        // Border tinted by rarity so the whole tooltip reflects the item's tier.
        final Color border = (this.rarity > 0) ? argbToColor(Rarity.fromOrdinal(this.rarity).color) : BORDER_COLOR;
        UiRender.fillRect(batch, shapes, drawX - 2, drawY - 2, tooltipWidth + 4, tooltipHeight + 4, border);
        UiRender.fillRect(batch, shapes, drawX, drawY, tooltipWidth, tooltipHeight, BG_COLOR);

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
}
