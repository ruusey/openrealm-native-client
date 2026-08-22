package com.openrealm.game.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ProjectileGroup;
import com.openrealm.game.model.WeaponArchetypeModel;
import com.openrealm.game.ui.ActiveVisualEffect;
import com.openrealm.net.client.packet.CreateEffectPacket;

/**
 * Pure ShapeRenderer geometry for ability / status visual effects, extracted
 * from PlayState so the state class carries game + net logic rather than draw
 * routines. Every method is static; the only state touched is the passed-in
 * ShapeRenderer and GL blend state. Coordinates are world-camera space.
 */
public final class AbilityEffectRenderer {

    private AbilityEffectRenderer() {}
    /** Procedural per-archetype melee swing (no art required). Sword: clean
     *  far-reaching crescent + tip gleam. Axe: fat short red cleave + chunk burst.
     *  Hammer: overhead smash then shockwave ring + ground cracks. Dagger: quick
     *  lunging thrust. tier = weapon archetype (1/2/3/10). Coords are world-camera
     *  space (matches the other ShapeRenderer effects); manages its own begin/end. */
    public static void drawMeleeSwing(ShapeRenderer shapes, ActiveVisualEffect vfx, float wx, float wy, float t) {
        final float ox = vfx.getTargetPosX() - wx;   // origin (player)
        final float oy = vfx.getTargetPosY() - wy;
        final float cx = vfx.getPosX() - wx;          // center (aim)
        final float cy = vfx.getPosY() - wy;
        final float ang = (float) Math.atan2(cy - oy, cx - ox);
        final float reach = Math.max((float) Math.hypot(cx - ox, cy - oy), 22f);
        final float a = Math.max(0f, 1f - t);
        final short tier = vfx.getTier();
        final float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);

        if (tier == 3) {
            // HAMMER — overhead drive, then radial shockwave ring + cracks.
            if (t < 0.5f) {
                final float p = t / 0.5f;
                final float headR = reach * (0.45f + 0.55f * p);
                final float hx = ox + cos * headR, hy = oy + sin * headR;
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                shapes.setColor(0.85f, 0.71f, 0.54f, a);            // heavy haft
                shapes.rectLine(ox + cos * reach * 0.18f, oy + sin * reach * 0.18f, hx, hy, 7f);
                shapes.setColor(0.54f, 0.35f, 0.17f, a);            // hammer head
                shapes.circle(hx, hy, 9f);
                shapes.end();
            } else {
                final float p = (t - 0.5f) / 0.5f;
                final float ix = ox + cos * reach, iy = oy + sin * reach;
                final float ringR = reach * 0.2f + p * reach * 0.85f;
                shapes.begin(ShapeRenderer.ShapeType.Line);
                Gdx.gl.glLineWidth(3f);
                shapes.setColor(1f, 0.88f, 0.63f, a);               // shockwave ring
                shapes.circle(ix, iy, ringR, 40);
                shapes.setColor(0.73f, 0.54f, 0.31f, a);            // ground cracks
                for (int k = 0; k < 6; k++) {
                    final float ca = ang + (k - 2.5f) * 0.52f;
                    shapes.line(ix, iy, ix + (float) Math.cos(ca) * ringR * 0.7f,
                            iy + (float) Math.sin(ca) * ringR * 0.7f);
                }
                shapes.end();
                Gdx.gl.glLineWidth(1f);
            }
        } else if (tier == 10) {
            // DAGGER — quick lunging thrust that extends then retracts.
            final float ext = t < 0.45f ? t / 0.45f : 1f - (t - 0.45f) / 0.55f;
            final float tipR = reach * (0.35f + 0.8f * ext);
            final float tx = ox + cos * tipR, ty = oy + sin * tipR;
            final float perp = ang + (float) (Math.PI / 2), w = 3f;
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.85f, 0.98f, 1f, a);                   // sharp spike
            shapes.triangle(tx, ty,
                    ox + (float) Math.cos(perp) * w, oy + (float) Math.sin(perp) * w,
                    ox - (float) Math.cos(perp) * w, oy - (float) Math.sin(perp) * w);
            if (ext > 0.85f) { shapes.setColor(1f, 1f, 1f, a); shapes.circle(tx, ty, 2.5f); }
            shapes.end();
        } else {
            // SWORD (1) / AXE (2) — sweeping crescent (triangle-fan smear + edge).
            final boolean isAxe = tier == 2;
            final float half = isAxe ? 1.35f : 1.1f;
            final float reachA = isAxe ? reach * 0.9f : reach;
            final float blade = ang - half + t * (2f * half);
            final int SEG = 10;
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            if (isAxe) shapes.setColor(0.84f, 0.35f, 0.12f, 0.22f * a);
            else       shapes.setColor(0.75f, 0.91f, 1f, 0.16f * a);
            for (int k = 0; k < SEG; k++) {
                final float a0 = ang - half + (blade - (ang - half)) * (k / (float) SEG);
                final float a1 = ang - half + (blade - (ang - half)) * ((k + 1) / (float) SEG);
                shapes.triangle(ox, oy,
                        ox + (float) Math.cos(a0) * reachA, oy + (float) Math.sin(a0) * reachA,
                        ox + (float) Math.cos(a1) * reachA, oy + (float) Math.sin(a1) * reachA);
            }
            if (isAxe) shapes.setColor(1f, 0.5f, 0.19f, a);         // bright leading edge
            else       shapes.setColor(0.85f, 0.94f, 1f, a);
            shapes.rectLine(ox + (float) Math.cos(blade) * reachA * 0.3f,
                    oy + (float) Math.sin(blade) * reachA * 0.3f,
                    ox + (float) Math.cos(blade) * reachA,
                    oy + (float) Math.sin(blade) * reachA, isAxe ? 5f : 3f);
            if (!isAxe) {
                shapes.setColor(1f, 1f, 1f, a);                     // tip gleam
                shapes.circle(ox + (float) Math.cos(blade) * reachA,
                        oy + (float) Math.sin(blade) * reachA, 2.5f);
            }
            shapes.end();
            if (isAxe && t > 0.75f) {                                // chunk burst
                final float bx = ox + (float) Math.cos(blade) * reachA;
                final float by = oy + (float) Math.sin(blade) * reachA;
                shapes.begin(ShapeRenderer.ShapeType.Line);
                Gdx.gl.glLineWidth(2f);
                shapes.setColor(1f, 0.69f, 0.44f, a);
                for (int k = 0; k < 3; k++) {
                    final float ca = blade + (k - 1) * 0.4f;
                    shapes.line(bx, by, bx + (float) Math.cos(ca) * 10f, by + (float) Math.sin(ca) * 10f);
                }
                shapes.end();
                Gdx.gl.glLineWidth(1f);
            }
        }
    }

    public static void renderAoeEffect(ShapeRenderer shapes, ActiveVisualEffect vfx, short type, float t, float wx, float wy,
                                       boolean swingSpriteAvailable) {
        final float cx = vfx.getPosX() - wx;
        final float cy = vfx.getPosY() - wy;
        final float maxRadius = vfx.getRadius();

        // Per-archetype melee swing (tier = weapon: 1 Sword, 2 Axe, 3 Hammer,
        // 10 Dagger). Procedural here; the sprite override (if authored) is drawn
        // in renderMeleeSwings() during the batch pass, and this skips those.
        if (type == CreateEffectPacket.EFFECT_MELEE_SWING) {
            if (!swingSpriteAvailable) drawMeleeSwing(shapes, vfx, wx, wy, t);
            return;
        }

        // Water fountain has its own procedural renderer (parabolic-arc
        // droplets + splash ripples), not the standard ring/particle setup.
        if (type == CreateEffectPacket.EFFECT_WATER_FOUNTAIN) {
            renderWaterFountain(shapes, vfx, cx, cy, maxRadius);
            return;
        }

        // Spawn-protection purify circle — white/gold expanding ring, cleansing
        // core flash, and gold sparkle motes orbiting the rim.
        if (type == CreateEffectPacket.EFFECT_PURIFY_CIRCLE) {
            renderPurifyCircle(shapes, cx, cy, maxRadius, t);
            return;
        }

        // Boss-grenade warning / impact (Enemy 26). Tier >= 10 is the
        // sentinel the boss script uses to ask for a *much* more visible
        // ring than the default CURSE_RADIUS — the standard renderer's
        // 35% fill reads as faint over the spiral arms + the ground tiles.
        // This path snaps to full radius, paints a 55%-opacity red disc,
        // and pulses the outline so the player can read the danger zone
        // through bullet clutter.
        if (vfx.getTier() >= 10) {
            final float bossAlpha = t < 0.7f ? 1.0f : 1.0f - (t - 0.7f) * 3.33f;
            // Grenade colour by tier: 10 = red, 11 = green, 12 = blue (webclient parity).
            final int gtier = vfx.getTier();
            final float fillR, fillG, fillB, edgeR, edgeG, edgeB, edg2R, edg2G, edg2B;
            if (gtier == 11) {
                fillR = 0.078f; fillG = 0.722f; fillB = 0.235f;
                edgeR = 0.200f; edgeG = 0.878f; edgeB = 0.333f;
                edg2R = 0.451f; edg2G = 1.000f; edg2B = 0.584f;
            } else if (gtier == 12) {
                fillR = 0.078f; fillG = 0.471f; fillB = 1.000f;
                edgeR = 0.200f; edgeG = 0.600f; edgeB = 1.000f;
                edg2R = 0.451f; edg2G = 0.753f; edg2B = 1.000f;
            } else {
                fillR = 1.000f; fillG = 0.051f; fillB = 0.051f;
                edgeR = 1.000f; edgeG = 0.200f; edgeB = 0.200f;
                edg2R = 1.000f; edg2G = 0.451f; edg2B = 0.333f;
            }

            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(fillR, fillG, fillB, bossAlpha * 0.95f);
            drawCircle(shapes, cx, cy, maxRadius, 36);
            shapes.end();

            shapes.begin(ShapeRenderer.ShapeType.Line);
            Gdx.gl.glLineWidth(8f);
            final float urgency = 0.85f + 0.15f * (float) Math.sin(t * Math.PI * 12);
            shapes.setColor(edgeR, edgeG, edgeB, bossAlpha * urgency);
            drawCircleOutline(shapes, cx, cy, maxRadius, 48);
            drawCircleOutline(shapes, cx, cy, maxRadius * 0.97f, 48);
            drawCircleOutline(shapes, cx, cy, maxRadius * 1.03f, 48);
            shapes.setColor(edg2R, edg2G, edg2B, bossAlpha * 0.85f);
            drawCircleOutline(shapes, cx, cy, maxRadius * 0.9f, 48);
            drawCircleOutline(shapes, cx, cy, maxRadius * 1.1f, 48);
            shapes.end();
            return;
        }

        // Ring expands fast then holds
        final float currentRadius = maxRadius * Math.min(t * 3.0f, 1.0f);
        // Stay fully visible for 70% of duration, then fade
        final float alpha = t < 0.7f ? 1.0f : 1.0f - (t - 0.7f) * 3.33f;

        // MELEE_SWING is handled at the top of this method (procedural per-archetype
        // swing, or the sprite override in renderMeleeSwings()).

        // SOUL_VORTEX (45) is a persistent vortex with bespoke art — render
        // it specially so it doesn't get drawn as a generic ring on top of
        // its actual visual. Falls through to the dedicated branch below.
        // Phase 4 bespoke effects — each dispatches to a self-contained
        // renderer that manages its own shape begin/end. Mirrors the
        // procedural rendering done in the webclient renderer.js.
        if (type == CreateEffectPacket.EFFECT_SANCTUARY_DOME) {
            renderSanctuaryDome(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_VAMPIRIC_LATCH) {
            renderVampiricLatch(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_RAPIER_STAB) {
            renderRapierStab(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_LOW_SWING) {
            renderLowSwing(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_DISARM_FLOURISH) {
            renderDisarmFlourish(shapes, vfx, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_DIVINE_BEAM) {
            renderDivineBeam(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_FORTIFY_AURA) {
            renderFortifyAura(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_GROUND_POUND) {
            renderGroundPound(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_DRUID_ROOTS) {
            renderDruidRoots(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_DRUID_MOONLIGHT) {
            renderDruidMoonlight(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_DRUID_WILD_SURGE) {
            renderDruidWildSurge(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_SOUL_VORTEX) {
            renderSoulVortex(shapes, vfx, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_SMOKE_POOF) {
            renderSmokePoof(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_FROST_NOVA) {
            renderFrostNova(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_POISON_CLOUD) {
            renderPoisonCloud(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_LIGHTNING_STRIKE) {
            renderLightningStrike(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_SMITE_FLASH) {
            renderSmiteFlash(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BONE_SPIKES) {
            renderBoneSpikes(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_MANA_BOLT) {
            renderManaBolt(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_TIME_STOP) {
            renderTimeStop(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BEAST_CLAWS) {
            renderBeastClaws(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_DEATH_BLOSSOM) {
            renderDeathBlossom(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_INSPIRE_BLOOM) {
            renderInspireBloom(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_RECKLESS_SLASH) {
            renderRecklessSlash(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_STAR_SHURIKEN) {
            renderStarShuriken(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BLINK_GLYPH) {
            renderBlinkGlyph(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_LIFE_DRAIN) {
            renderLifeDrain(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_SNARE_GEAR) {
            renderSnareGear(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_COMBUSTION_TRAP) {
            renderCombustionTrap(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_WAR_CRY_WAVE) {
            renderWarCryWave(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_CALTROPS) {
            renderCaltrops(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_ARCANE_AURA) {
            renderArcaneAura(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_HASTE_WIND) {
            renderHasteWind(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BANNER_RAISE) {
            renderBannerRaise(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_RAMPAGE_AURA) {
            renderRampageAura(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_STORM_AURA) {
            renderStormAura(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_DEATH_PACT_AURA) {
            renderDeathPactAura(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BLADE_STORM) {
            renderBladeStorm(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_TAUNT_ROAR) {
            renderTauntRoar(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_BRACE_STANCE) {
            renderBraceStance(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_SHIELD_DOME) {
            renderShieldDome(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_WIZARD_BURST) {
            renderWizardBurst(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_PALADIN_SEAL) {
            renderPaladinSeal(shapes, cx, cy, maxRadius, t);
            return;
        }
        if (type == CreateEffectPacket.EFFECT_WARRIOR_BUFF) {
            renderWarriorBuff(shapes, cx, cy, maxRadius, t);
            return;
        }
        // Necromancer Wither / curse cast (non-boss; tier>=10 boss-grenade
        // handled above) — dark-magic vortex instead of the plain ring.
        if (type == CreateEffectPacket.EFFECT_CURSE_RADIUS) {
            renderCurseVortex(shapes, cx, cy, maxRadius, t);
            return;
        }
        // BLADE_ORBIT (46) and BLADE_BLENDER (47) are drawn separately in
        // renderShurikenEffects() using real shuriken sprites + SpriteBatch.
        // We early-return so the procedural ring path doesn't paint a
        // generic disc behind them. BLADE_BLENDER still gets a faint ground
        // halo though, drawn here for hazard-zone readability.
        if (type == CreateEffectPacket.EFFECT_BLADE_ORBIT) return;
        if (type == CreateEffectPacket.EFFECT_BLADE_BLENDER) {
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.06f, 0.03f, 0.03f, alpha * 0.45f);
            drawCircle(shapes, cx, cy, maxRadius, 48);
            shapes.end();
            shapes.begin(ShapeRenderer.ShapeType.Line);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(0.75f, 0.12f, 0.18f, alpha * 0.7f);
            drawCircleOutline(shapes, cx, cy, maxRadius, 64);
            shapes.end();
            Gdx.gl.glLineWidth(1f);
            return;
        }
        // Per-effect color palette. Mirrors the webclient renderer.js cases
        // for parity at-a-glance — same hue as the webclient even if the
        // shape detail is simplified to ring+particles here.
        float r, g, b;
        switch (type) {
        case CreateEffectPacket.EFFECT_HEAL_RADIUS:       r = 0.10f; g = 1.00f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_VAMPIRISM:         r = 0.90f; g = 0.00f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_STASIS_FIELD:      r = 0.30f; g = 0.60f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_CURSE_RADIUS:      r = 0.80f; g = 0.00f; b = 0.15f; break;
        case CreateEffectPacket.EFFECT_POISON_SPLASH:     r = 0.20f; g = 0.80f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_TRAP_PLACED:       r = 0.85f; g = 0.55f; b = 0.10f; break;
        case CreateEffectPacket.EFFECT_TRAP_TRIGGER:      r = 1.00f; g = 0.45f; b = 0.10f; break;
        case CreateEffectPacket.EFFECT_SMOKE_POOF:        r = 0.55f; g = 0.55f; b = 0.60f; break;
        case CreateEffectPacket.EFFECT_WIZARD_BURST:      r = 1.00f; g = 0.55f; b = 0.10f; break;
        case CreateEffectPacket.EFFECT_KNIGHT_SHOCKWAVE:  r = 0.95f; g = 0.85f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_WARRIOR_BUFF:      r = 1.00f; g = 0.65f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_NINJA_DASH:        r = 0.40f; g = 0.85f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_PALADIN_SEAL:      r = 1.00f; g = 0.85f; b = 0.35f; break;
        case CreateEffectPacket.EFFECT_SHIELD_DOME:       r = 0.50f; g = 0.80f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_TAUNT_ROAR:        r = 1.00f; g = 0.20f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_BRACE_STANCE:      r = 0.70f; g = 0.85f; b = 0.95f; break;
        case CreateEffectPacket.EFFECT_FROST_NOVA:        r = 0.60f; g = 0.90f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_BLINK_GLYPH:       r = 0.75f; g = 0.45f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_POISON_CLOUD:      r = 0.38f; g = 0.78f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_LIFE_DRAIN:        r = 0.85f; g = 0.10f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_BONE_SPIKES:       r = 0.92f; g = 0.90f; b = 0.78f; break;
        case CreateEffectPacket.EFFECT_LIGHTNING_STRIKE:  r = 1.00f; g = 0.95f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_MANA_BOLT:         r = 0.55f; g = 0.30f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_TIME_STOP:         r = 0.70f; g = 0.80f; b = 0.95f; break;
        case CreateEffectPacket.EFFECT_BEAST_CLAWS:       r = 0.85f; g = 0.45f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_SMITE_FLASH:       r = 1.00f; g = 0.90f; b = 0.40f; break;
        case CreateEffectPacket.EFFECT_DEATH_BLOSSOM:     r = 0.60f; g = 0.10f; b = 0.70f; break;
        case CreateEffectPacket.EFFECT_INSPIRE_BLOOM:     r = 1.00f; g = 0.80f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_RECKLESS_SLASH:    r = 0.95f; g = 0.20f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_STAR_SHURIKEN:     r = 0.85f; g = 0.85f; b = 0.90f; break;
        case CreateEffectPacket.EFFECT_SNARE_GEAR:        r = 0.75f; g = 0.65f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_COMBUSTION_TRAP:   r = 1.00f; g = 0.45f; b = 0.10f; break;
        case CreateEffectPacket.EFFECT_WAR_CRY_WAVE:      r = 0.95f; g = 0.30f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_CALTROPS:          r = 0.70f; g = 0.70f; b = 0.75f; break;
        case CreateEffectPacket.EFFECT_ARCANE_AURA:       r = 0.65f; g = 0.40f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_HASTE_WIND:        r = 0.60f; g = 0.95f; b = 0.80f; break;
        case CreateEffectPacket.EFFECT_BANNER_RAISE:      r = 0.90f; g = 0.50f; b = 0.20f; break;
        case CreateEffectPacket.EFFECT_RAMPAGE_AURA:      r = 1.00f; g = 0.25f; b = 0.10f; break;
        case CreateEffectPacket.EFFECT_STORM_AURA:        r = 0.40f; g = 0.65f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_DEATH_PACT_AURA:   r = 0.55f; g = 0.10f; b = 0.50f; break;
        case CreateEffectPacket.EFFECT_BLADE_STORM:       r = 0.90f; g = 0.85f; b = 0.85f; break;
        // Phase 3 (post-rework) bespoke effects — until the native renderer
        // ports the procedural shape for each, paint a distinctive ring.
        case CreateEffectPacket.EFFECT_SANCTUARY_DOME:    r = 1.00f; g = 0.85f; b = 0.35f; break;
        case CreateEffectPacket.EFFECT_VAMPIRIC_LATCH:    r = 0.85f; g = 0.10f; b = 0.30f; break;
        // Heavy class kit FX — Debuffer (silver/red), Buffer (gold), DPS (dust).
        case CreateEffectPacket.EFFECT_RAPIER_STAB:       r = 0.88f; g = 0.90f; b = 0.93f; break;
        case CreateEffectPacket.EFFECT_LOW_SWING:         r = 0.75f; g = 0.16f; b = 0.19f; break;
        case CreateEffectPacket.EFFECT_DISARM_FLOURISH:   r = 1.00f; g = 0.82f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_DIVINE_BEAM:       r = 1.00f; g = 0.83f; b = 0.30f; break;
        case CreateEffectPacket.EFFECT_FORTIFY_AURA:      r = 0.25f; g = 0.66f; b = 1.00f; break;
        case CreateEffectPacket.EFFECT_GROUND_POUND:      r = 0.72f; g = 0.56f; b = 0.38f; break;
        default:                                          r = 1.00f; g = 1.00f; b = 1.00f; break;
        }

        // Filled translucent disc - much more visible
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(r, g, b, alpha * 0.35f);
        drawCircle(shapes, cx, cy, currentRadius, 48);
        shapes.end();

        // Thick bright outer ring (draw multiple concentric rings for thickness)
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(r, g, b, alpha);
        drawCircleOutline(shapes, cx, cy, currentRadius, 64);
        shapes.setColor(r, g, b, alpha * 0.7f);
        drawCircleOutline(shapes, cx, cy, currentRadius * 0.97f, 64);
        drawCircleOutline(shapes, cx, cy, currentRadius * 1.03f, 64);
        shapes.end();

        // Second inner ring, pulsing
        float pulse = 0.7f + 0.3f * (float) Math.sin(t * Math.PI * 8);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(r, g, b, alpha * 0.8f * pulse);
        drawCircleOutline(shapes, cx, cy, currentRadius * 0.6f, 48);
        shapes.end();

        // Large orbiting particles on the ring edge
        int particleCount = 16;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < particleCount; i++) {
            float angle = (float) (i * Math.PI * 2 / particleCount) + t * (float) Math.PI * 4;
            float px = cx + (float) Math.cos(angle) * currentRadius;
            float py = cy + (float) Math.sin(angle) * currentRadius;
            float pAlpha = alpha * (0.6f + 0.4f * (float) Math.sin(angle * 3 + t * Math.PI * 10));
            shapes.setColor(Math.min(r + 0.3f, 1f), Math.min(g + 0.3f, 1f), Math.min(b + 0.3f, 1f), pAlpha);
            shapes.rect(px - 3, py - 3, 6, 6);
        }

        // Inner scattered particles (moving outward or inward)
        int innerParticles = 12;
        for (int i = 0; i < innerParticles; i++) {
            float angle = (float) (i * Math.PI * 2 / innerParticles) - t * (float) Math.PI * 3;
            float dist;
            if (type == CreateEffectPacket.EFFECT_VAMPIRISM) {
                dist = currentRadius * (1.0f - t);
            } else {
                dist = currentRadius * 0.2f + currentRadius * 0.6f * t;
            }
            float px = cx + (float) Math.cos(angle) * dist;
            float py = cy + (float) Math.sin(angle) * dist;
            float pAlpha = alpha * 0.9f;
            shapes.setColor(Math.min(r + 0.2f, 1f), Math.min(g + 0.2f, 1f), Math.min(b + 0.2f, 1f), pAlpha);
            shapes.rect(px - 2.5f, py - 2.5f, 5, 5);
        }

        // Bright center flash at start
        if (t < 0.3f) {
            float flashAlpha = (0.3f - t) * 3.0f;
            shapes.setColor(1f, 1f, 1f, flashAlpha * 0.5f);
            drawCircle(shapes, cx, cy, currentRadius * 0.3f * (1.0f - t * 2), 24);
        }
        shapes.end();

        Gdx.gl.glLineWidth(1f);
    }

    public static void renderLineEffect(ShapeRenderer shapes, ActiveVisualEffect vfx, float t, float wx, float wy) {
        if (vfx.getEffectType() == CreateEffectPacket.EFFECT_POISON_SPLASH) {
            renderPoisonThrow(shapes, vfx, t, wx, wy);
            return;
        }
        if (vfx.getEffectType() == CreateEffectPacket.EFFECT_KNIGHT_SHOCKWAVE) {
            renderKnightShockwave(shapes, vfx, t, wx, wy);
            return;
        }
        if (vfx.getEffectType() == CreateEffectPacket.EFFECT_NINJA_DASH) {
            renderNinjaDash(shapes, vfx, t, wx, wy);
            return;
        }
        final float x1 = vfx.getPosX() - wx;
        final float y1 = vfx.getPosY() - wy;
        final float x2 = vfx.getTargetPosX() - wx;
        final float y2 = vfx.getTargetPosY() - wy;
        // Stay fully visible for 80% of duration, then fade
        final float alpha = t < 0.8f ? 1.0f : 1.0f - (t - 0.8f) * 5.0f;

        final float dx = x2 - x1;
        final float dy = y2 - y1;
        final float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1f) return;

        int segments = Math.max(8, (int) (length / 10));
        float perpX = -dy / length;
        float perpY = dx / length;

        // Pre-compute jitter offsets for main bolt (reused by glow)
        float[] jitters = new float[segments + 1];
        jitters[0] = 0;
        jitters[segments] = 0;
        for (int i = 1; i < segments; i++) {
            float frac = (float) i / segments;
            jitters[i] = (float) (Math.sin(frac * Math.PI * 5 + t * Math.PI * 14) * 12.0f
                    + Math.cos(frac * Math.PI * 9 + t * Math.PI * 8) * 5.0f);
        }

        // Outer glow (thick, dim blue-purple)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < segments; i++) {
            float frac0 = (float) i / segments;
            float frac1 = (float) (i + 1) / segments;
            float px0 = x1 + dx * frac0 + perpX * jitters[i];
            float py0 = y1 + dy * frac0 + perpY * jitters[i];
            float px1 = x1 + dx * frac1 + perpX * jitters[i + 1];
            float py1 = y1 + dy * frac1 + perpY * jitters[i + 1];
            // Draw thick quads along the bolt as glow
            float glowSize = 6f;
            shapes.setColor(0.3f, 0.4f, 1.0f, alpha * 0.3f);
            shapes.rectLine(px0, py0, px1, py1, glowSize);
        }
        shapes.end();

        // Main bright bolt - thick electric blue
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < segments; i++) {
            float frac0 = (float) i / segments;
            float frac1 = (float) (i + 1) / segments;
            float px0 = x1 + dx * frac0 + perpX * jitters[i];
            float py0 = y1 + dy * frac0 + perpY * jitters[i];
            float px1 = x1 + dx * frac1 + perpX * jitters[i + 1];
            float py1 = y1 + dy * frac1 + perpY * jitters[i + 1];
            shapes.setColor(0.4f, 0.7f, 1.0f, alpha);
            shapes.rectLine(px0, py0, px1, py1, 3f);
        }
        shapes.end();

        // Inner white-hot core
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < segments; i++) {
            float frac0 = (float) i / segments;
            float frac1 = (float) (i + 1) / segments;
            float px0 = x1 + dx * frac0 + perpX * jitters[i];
            float py0 = y1 + dy * frac0 + perpY * jitters[i];
            float px1 = x1 + dx * frac1 + perpX * jitters[i + 1];
            float py1 = y1 + dy * frac1 + perpY * jitters[i + 1];
            shapes.setColor(0.8f, 0.9f, 1.0f, alpha * 0.9f);
            shapes.rectLine(px0, py0, px1, py1, 1.5f);
        }
        shapes.end();

        // Secondary fork bolt (different jitter pattern)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < segments; i++) {
            float frac0 = (float) i / segments;
            float frac1 = (float) (i + 1) / segments;
            float j0 = (float) (Math.cos(frac0 * Math.PI * 7 + t * Math.PI * 18) * 8.0f);
            float j1 = (float) (Math.cos(frac1 * Math.PI * 7 + t * Math.PI * 18) * 8.0f);
            if (i == 0) j0 = 0;
            if (i == segments - 1) j1 = 0;
            float px0 = x1 + dx * frac0 + perpX * j0;
            float py0 = y1 + dy * frac0 + perpY * j0;
            float px1 = x1 + dx * frac1 + perpX * j1;
            float py1 = y1 + dy * frac1 + perpY * j1;
            shapes.setColor(0.5f, 0.6f, 1.0f, alpha * 0.5f);
            shapes.rectLine(px0, py0, px1, py1, 2f);
        }
        shapes.end();

        // Bright glow particles along the bolt
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        int particleCount = Math.max(6, segments / 2);
        for (int i = 0; i < particleCount; i++) {
            float frac = (float) i / particleCount;
            int segIdx = Math.min((int) (frac * segments), segments - 1);
            float px = x1 + dx * frac + perpX * jitters[segIdx];
            float py = y1 + dy * frac + perpY * jitters[segIdx];
            float pAlpha = alpha * (0.5f + 0.5f * (float) Math.sin(frac * Math.PI));
            shapes.setColor(0.6f, 0.8f, 1.0f, pAlpha);
            shapes.rect(px - 3, py - 3, 6, 6);
        }

        // Bright impact circles at endpoints
        float endSize = 8f + 4f * (float) Math.sin(t * Math.PI * 10);
        shapes.setColor(0.5f, 0.7f, 1.0f, alpha * 0.8f);
        drawCircle(shapes, x1, y1, endSize, 12);
        drawCircle(shapes, x2, y2, endSize, 12);
        shapes.setColor(1.0f, 1.0f, 1.0f, alpha);
        drawCircle(shapes, x1, y1, endSize * 0.4f, 8);
        drawCircle(shapes, x2, y2, endSize * 0.4f, 8);
        shapes.end();
    }

    /**
     * Knight Phalanx Shockwave (shield-bash thrust) — directional shield
     * bash with windup/thrust/slam phases. Ground-shadow streak along the
     * dash axis, 6 force chevrons sweeping forward, slam burst at the
     * forward endpoint, two staggered aftermath shockwaves, flash, debris
     * particles, and forward-radiating ground cracks. Procedural port of
     * renderer.js case 11 — directional via vfx.posX/Y → targetPosX/Y.
     */
    public static void renderKnightShockwave(ShapeRenderer shapes, ActiveVisualEffect vfx, float t, float wx, float wy) {
        final float sx = vfx.getPosX() - wx;
        final float sy = vfx.getPosY() - wy;
        final float tx = vfx.getTargetPosX() - wx;
        final float ty = vfx.getTargetPosY() - wy;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;

        float kdx = tx - sx, kdy = ty - sy;
        final float kdist = (float) Math.sqrt(kdx * kdx + kdy * kdy);
        final float dirX = kdist > 0.5f ? kdx / kdist : 1f;
        final float dirY = kdist > 0.5f ? kdy / kdist : 0f;
        final float perpX = -dirY, perpY = dirX;

        final float REACH = Math.max(60f, Math.min(280f, kdist));
        final float WINDUP_END = 0.12f;
        final float THRUST_END = 0.50f;
        final float SLAM_END = 0.70f;

        // Use the gold palette from EFFECT_KNIGHT_SHOCKWAVE
        final float tcR = 0.95f, tcG = 0.85f, tcB = 0.30f;

        // ── Ground-shadow streak along the thrust axis ───────────────
        final float streakStart = -40f;
        final float streakEnd = REACH * Math.min(1.2f, t * 1.4f);
        final float startX = sx + dirX * streakStart, startY = sy + dirY * streakStart;
        final float endX = sx + dirX * streakEnd, endY = sy + dirY * streakEnd;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(tcR, tcG, tcB, alpha * 0.10f);
        shapes.rectLine(startX, startY, endX, endY, 28f);
        shapes.setColor(tcR, tcG, tcB, alpha * 0.22f);
        shapes.rectLine(startX, startY, endX, endY, 16f);
        shapes.setColor(0f, 0f, 0f, alpha * 0.45f);
        shapes.rectLine(startX, startY, endX, endY, 6f);
        shapes.end();

        // ── Force chevrons sweeping forward ──────────────────────────
        final int chevCount = 6;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < chevCount; i++) {
            final float phaseOff = i * 0.045f;
            float lt;
            if (t < WINDUP_END) {
                final float tt = t / WINDUP_END;
                lt = -0.55f - 0.10f * tt - i * 0.08f;
            } else if (t < THRUST_END) {
                final float tt = Math.max(0f, Math.min(1f,
                        (t - WINDUP_END - phaseOff) / (THRUST_END - WINDUP_END - phaseOff)));
                final float eased = tt * tt * (3f - 2f * tt);
                final float startLT = -0.55f - 0.10f - i * 0.08f;
                final float endLT = 1.20f - i * 0.05f;
                lt = startLT + (endLT - startLT) * eased;
            } else {
                lt = 1.20f - i * 0.05f;
            }
            final float cx = sx + dirX * REACH * lt;
            final float cy = sy + dirY * REACH * lt;
            final float ltClamped = Math.max(-0.7f, Math.min(1.3f, lt));
            final float distFromCore = Math.max(0f, ltClamped - 1.0f);
            final float aheadFade = 1f - distFromCore * 1.8f;
            final float fade = alpha * Math.max(0.2f, aheadFade) * (1f - i * 0.06f);

            final float arm = 22f - i * 2.2f;
            final float tipFwd = arm * 0.55f;
            final float tipX = cx + dirX * tipFwd;
            final float tipY = cy + dirY * tipFwd;
            final float back1X = cx + perpX * arm - dirX * arm * 0.4f;
            final float back1Y = cy + perpY * arm - dirY * arm * 0.4f;
            final float back2X = cx - perpX * arm - dirX * arm * 0.4f;
            final float back2Y = cy - perpY * arm - dirY * arm * 0.4f;

            Gdx.gl.glLineWidth(7f);
            shapes.setColor(tcR, tcG, tcB, fade * 0.85f);
            shapes.line(back1X, back1Y, tipX, tipY);
            shapes.line(tipX, tipY, back2X, back2Y);
            Gdx.gl.glLineWidth(3f);
            shapes.setColor(0f, 0f, 0f, fade * 0.6f);
            shapes.line(back1X, back1Y, tipX, tipY);
            shapes.line(tipX, tipY, back2X, back2Y);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, fade * 0.95f);
            shapes.line(back1X, back1Y, tipX, tipY);
            shapes.line(tipX, tipY, back2X, back2Y);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // ── Brace flash behind knight during wind-up ─────────────────
        if (t < WINDUP_END) {
            final float tt = t / WINDUP_END;
            final float brakeA = alpha * (1f - tt) * 0.7f;
            final float braceX = sx - dirX * 18f;
            final float braceY = sy - dirY * 18f;
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.33f, 0.20f, 0.13f, brakeA * 0.5f);
            drawCircle(shapes, braceX, braceY, 14f + tt * 8f, 18);
            shapes.setColor(tcR, tcG, tcB, brakeA * 0.4f);
            drawCircle(shapes, braceX, braceY, 10f + tt * 6f, 16);
            shapes.end();
        }

        // ── Slam impact + radial spokes + forward crack lines ────────
        if (t >= WINDUP_END) {
            final float slamProg = Math.max(0f, Math.min(1f, (t - WINDUP_END) / (SLAM_END - WINDUP_END)));
            final float slamPeak = (THRUST_END - WINDUP_END) / (SLAM_END - WINDUP_END);
            float slamA = slamProg <= slamPeak
                    ? slamProg / slamPeak
                    : Math.max(0f, 1f - (slamProg - slamPeak) / (1f - slamPeak));
            slamA *= alpha;
            if (slamA > 0.02f) {
                final float slamX = sx + dirX * REACH;
                final float slamY = sy + dirY * REACH;
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                shapes.setColor(tcR, tcG, tcB, slamA * 0.55f);
                drawCircle(shapes, slamX, slamY, 38f + slamA * 18f, 32);
                shapes.setColor(1f, 1f, 1f, slamA * 0.95f);
                drawCircle(shapes, slamX, slamY, 18f + slamA * 10f, 24);
                shapes.setColor(tcR, tcG, tcB, slamA);
                drawCircle(shapes, slamX, slamY, 8f, 14);
                shapes.end();
                // 12 radial spokes around the slam
                shapes.begin(ShapeRenderer.ShapeType.Line);
                Gdx.gl.glLineWidth(3f);
                shapes.setColor(1f, 1f, 1f, slamA * 0.9f);
                final int spokes = 12;
                for (int i = 0; i < spokes; i++) {
                    final float a = (i / (float) spokes) * (float) Math.PI * 2f;
                    final float inner = 12f;
                    final float outer = 28f + slamA * 22f;
                    shapes.line(slamX + (float) Math.cos(a) * inner, slamY + (float) Math.sin(a) * inner,
                                slamX + (float) Math.cos(a) * outer, slamY + (float) Math.sin(a) * outer);
                }
                // Forward-only crack lines
                Gdx.gl.glLineWidth(4f);
                shapes.setColor(tcR, tcG, tcB, slamA * 0.85f);
                for (int i = -1; i <= 1; i++) {
                    final float tilt = i * 0.45f;
                    final float cTilt = (float) Math.cos(tilt), sTilt = (float) Math.sin(tilt);
                    final float fX = dirX * cTilt - dirY * sTilt;
                    final float fY = dirY * cTilt + dirX * sTilt;
                    shapes.line(slamX, slamY,
                                slamX + fX * (40f + slamA * 30f),
                                slamY + fY * (40f + slamA * 30f));
                }
                Gdx.gl.glLineWidth(1f);
                shapes.end();
            }
        }

        // ── Aftermath shockwaves (two staggered rings) ───────────────
        if (t >= THRUST_END) {
            final float aftT = (t - THRUST_END) / (1.0f - THRUST_END);
            final float slamX = sx + dirX * REACH;
            final float slamY = sy + dirY * REACH;
            shapes.begin(ShapeRenderer.ShapeType.Line);
            final float r1 = 30f + aftT * 100f;
            final float r1A = alpha * (1.0f - aftT) * 0.95f;
            Gdx.gl.glLineWidth(7f);
            shapes.setColor(tcR, tcG, tcB, r1A);
            drawCircleOutline(shapes, slamX, slamY, r1, 48);
            Gdx.gl.glLineWidth(3f);
            shapes.setColor(1f, 1f, 1f, r1A);
            drawCircleOutline(shapes, slamX, slamY, r1 * 0.93f, 48);
            if (aftT > 0.30f) {
                final float aft2 = (aftT - 0.30f) / 0.70f;
                final float r2 = 24f + aft2 * 78f;
                final float r2A = alpha * (1.0f - aft2) * 0.70f;
                Gdx.gl.glLineWidth(4f);
                shapes.setColor(tcR, tcG, tcB, r2A);
                drawCircleOutline(shapes, slamX, slamY, r2, 48);
            }
            Gdx.gl.glLineWidth(1f);
            shapes.end();
        }

        // ── Slam-moment flash ────────────────────────────────────────
        final float flashWindow = 0.20f;
        final float flashCenter = THRUST_END;
        final float fdist = Math.abs(t - flashCenter);
        if (fdist < flashWindow) {
            final float flashA = (1f - fdist / flashWindow) * alpha * 0.75f;
            final float flashX = sx + dirX * REACH;
            final float flashY = sy + dirY * REACH;
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(1f, 1f, 1f, flashA);
            drawCircle(shapes, flashX, flashY, 56f + (1f - fdist / flashWindow) * 24f, 36);
            shapes.setColor(tcR, tcG, tcB, flashA * 0.55f);
            drawCircle(shapes, flashX, flashY, 92f, 36);
            shapes.end();
        }

        // ── Debris particles ─────────────────────────────────────────
        if (t >= THRUST_END) {
            final float debT = (t - THRUST_END) / (1.0f - THRUST_END);
            final float slamX = sx + dirX * REACH;
            final float slamY = sy + dirY * REACH;
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            final int PARTICLES = 14;
            for (int i = 0; i < PARTICLES; i++) {
                final float baseAng = (float) Math.atan2(dirY, dirX);
                final float spread = (i / (float) PARTICLES - 0.5f) * (float) Math.PI * 1.5f;
                final float ang = baseAng + spread + (i * 1.3f) * 0.02f;
                final float vScale = 0.7f + ((i * 0.193f) % 1f) * 0.6f;
                final float reach = 70f + vScale * 60f;
                final float tt = Math.min(1f, debT * 1.3f);
                final float eased = 1f - (float) Math.pow(1f - tt, 3);
                final float pdx = (float) Math.cos(ang) * reach * eased;
                final float pdy = (float) Math.sin(ang) * reach * eased + eased * eased * 14f;
                final float px = slamX + pdx;
                final float py = slamY + pdy;
                final float partA = alpha * (1f - eased) * 0.9f;
                final float partR = 2.5f + (i % 3) * 1.5f;
                if ((i & 1) == 0) {
                    shapes.setColor(tcR, tcG, tcB, partA);
                } else {
                    shapes.setColor(0.42f, 0.27f, 0.14f, partA);
                }
                drawCircle(shapes, px, py, partR, 10);
                if ((i & 1) == 0) {
                    shapes.setColor(1f, 1f, 1f, partA * 0.6f);
                    drawCircle(shapes, px - partR * 0.3f, py - partR * 0.3f, partR * 0.4f, 8);
                }
            }
            shapes.end();
        }
    }

    /**
     * Ninja Dash — directional vortex of slicing blades along the dash path:
     * dash spine (tier aura + black outline + white core), orbiting blade
     * diamonds at varying perpendicular offsets, vanish puff at start,
     * arrival flash + radial spokes at endpoint. Procedural port of
     * renderer.js case 13. Directional via vfx.posX/Y → targetPosX/Y.
     */
    public static void renderNinjaDash(ShapeRenderer shapes, ActiveVisualEffect vfx, float t, float wx, float wy) {
        final float sx = vfx.getPosX() - wx;
        final float sy = vfx.getPosY() - wy;
        final float tx = vfx.getTargetPosX() - wx;
        final float ty = vfx.getTargetPosY() - wy;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();

        final float dx = tx - sx, dy = ty - sy;
        final float dist = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
        final float dirX = dx / dist, dirY = dy / dist;
        final float perpX = -dirY, perpY = dirX;

        // Cyan tier color from EFFECT_NINJA_DASH palette
        final float tcR = 0.40f, tcG = 0.85f, tcB = 1.00f;

        // ── 1. Dash spine ────────────────────────────────────────────
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(tcR, tcG, tcB, alpha * 0.10f);
        shapes.rectLine(sx, sy, tx, ty, 20f);
        shapes.setColor(tcR, tcG, tcB, alpha * 0.25f);
        shapes.rectLine(sx, sy, tx, ty, 10f);
        shapes.setColor(0f, 0f, 0f, alpha * 0.55f);
        shapes.rectLine(sx, sy, tx, ty, 5f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.75f);
        shapes.rectLine(sx, sy, tx, ty, 3f);
        shapes.end();

        // ── 2. Vortex of orbiting blades ─────────────────────────────
        final int bladeCount = Math.max(14, (int) (dist / 14f));
        final float ORBIT_AMP = 44f;
        final float ORBIT_SPEED = 0.011f;
        final float SPIN_SPEED = 0.016f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < bladeCount; i++) {
            final float frac = (i + 0.5f) / (float) bladeCount;
            final float appear = frac * 0.45f;
            if (t < appear) continue;
            final float local = (t - appear) / Math.max(0.001f, 1f - appear);
            float bScale = 1.0f;
            if (local < 0.15f) bScale = local / 0.15f;
            else if (local > 0.75f) bScale = Math.max(0f, (1f - local) / 0.25f);
            if (bScale <= 0f) continue;

            final float cx = sx + dx * frac;
            final float cy = sy + dy * frac;
            final float sign = (i & 1) != 0 ? 1f : -1f;
            final float orbitPhase = sign * (now * ORBIT_SPEED + i * 0.55f);
            final float orbit = (float) Math.sin(orbitPhase) * ORBIT_AMP * bScale;
            final float bx = cx + perpX * orbit;
            final float by = cy + perpY * orbit;

            final float spin = now * SPIN_SPEED + i * 0.4f;
            final float cs = (float) Math.cos(spin), sn = (float) Math.sin(spin);

            // Outer tier-coloured glow blade (diamond as two triangles)
            final float gLen = 22f * bScale, gWid = 7f * bScale;
            final float gx0 = bx + gLen * cs, gy0 = by + gLen * sn;
            final float gx1 = bx - gWid * sn, gy1 = by + gWid * cs;
            final float gx2 = bx - gLen * cs, gy2 = by - gLen * sn;
            final float gx3 = bx + gWid * sn, gy3 = by - gWid * cs;
            shapes.setColor(tcR, tcG, tcB, alpha * 0.32f * bScale);
            shapes.triangle(gx0, gy0, gx1, gy1, gx2, gy2);
            shapes.triangle(gx0, gy0, gx2, gy2, gx3, gy3);
            // Steel core
            final float cLen = 16f * bScale, cWid = 4f * bScale;
            final float cx0 = bx + cLen * cs, cy0 = by + cLen * sn;
            final float cx1 = bx - cWid * sn, cy1 = by + cWid * cs;
            final float cx2 = bx - cLen * cs, cy2 = by - cLen * sn;
            final float cx3 = bx + cWid * sn, cy3 = by - cWid * cs;
            shapes.setColor(1f, 1f, 1f, alpha * 0.85f * bScale);
            shapes.triangle(cx0, cy0, cx1, cy1, cx2, cy2);
            shapes.triangle(cx0, cy0, cx2, cy2, cx3, cy3);
        }
        shapes.end();
        // Motion trail per blade
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        for (int i = 0; i < bladeCount; i++) {
            final float frac = (i + 0.5f) / (float) bladeCount;
            final float appear = frac * 0.45f;
            if (t < appear) continue;
            final float local = (t - appear) / Math.max(0.001f, 1f - appear);
            float bScale = 1.0f;
            if (local < 0.15f) bScale = local / 0.15f;
            else if (local > 0.75f) bScale = Math.max(0f, (1f - local) / 0.25f);
            if (bScale <= 0f) continue;
            final float cx = sx + dx * frac;
            final float cy = sy + dy * frac;
            final float sign = (i & 1) != 0 ? 1f : -1f;
            final float orbitPhase = sign * (now * ORBIT_SPEED + i * 0.55f);
            final float orbit = (float) Math.sin(orbitPhase) * ORBIT_AMP * bScale;
            final float bx = cx + perpX * orbit;
            final float by = cy + perpY * orbit;
            final float spin = now * SPIN_SPEED + i * 0.4f;
            final float cs = (float) Math.cos(spin), sn = (float) Math.sin(spin);
            final float cLen = 16f * bScale;
            final float trailLen = 14f * bScale;
            shapes.setColor(tcR, tcG, tcB, alpha * 0.45f * bScale);
            shapes.line(bx + cLen * cs, by + cLen * sn,
                        bx + cLen * cs - dirX * trailLen,
                        by + cLen * sn - dirY * trailLen);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // ── 3. Vanish puff at start ──────────────────────────────────
        final float startPuffA = Math.max(0f, 1.0f - t * 1.6f);
        if (startPuffA > 0f) {
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.5f, 0.5f, 0.5f, startPuffA * 0.6f);
            drawCircle(shapes, sx, sy, 16f, 20);
            shapes.setColor(tcR, tcG, tcB, startPuffA * 0.4f);
            drawCircle(shapes, sx, sy, 26f, 24);
            shapes.end();
        }

        // ── 4. Arrival flash + radial sparks at endpoint ─────────────
        final float arriveA = t < 0.5f ? (1.0f - t / 0.5f) : 0f;
        if (arriveA > 0f) {
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(1f, 1f, 1f, arriveA * 0.9f);
            drawCircle(shapes, tx, ty, 12f + arriveA * 10f, 22);
            shapes.setColor(tcR, tcG, tcB, arriveA * 0.6f);
            drawCircle(shapes, tx, ty, 26f + arriveA * 14f, 28);
            shapes.end();
            shapes.begin(ShapeRenderer.ShapeType.Line);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, arriveA * 0.9f);
            final int spokes = 10;
            for (int i = 0; i < spokes; i++) {
                final float a = (i / (float) spokes) * (float) Math.PI * 2f + now * 0.005f;
                final float inner = 8f;
                final float outer = 22f + arriveA * 18f;
                shapes.line(tx + (float) Math.cos(a) * inner, ty + (float) Math.sin(a) * inner,
                            tx + (float) Math.cos(a) * outer, ty + (float) Math.sin(a) * outer);
            }
            Gdx.gl.glLineWidth(1f);
            shapes.end();
        }
    }

    /** Render a chunky vial/grenade arc from caster to target position.
     *  Default palette is green (assassin poison vial, tiers 0-6). When the
     *  packet's tier is >= 10 we draw red — used by the Inferno Demon grenade
     *  so we can re-use the same parabolic-lob renderer without inventing a
     *  parallel effect type. */
    public static void renderPoisonThrow(ShapeRenderer shapes, ActiveVisualEffect vfx, float t, float wx, float wy) {
        final float x1 = vfx.getPosX() - wx;
        final float y1 = vfx.getPosY() - wy;
        final float x2 = vfx.getTargetPosX() - wx;
        final float y2 = vfx.getTargetPosY() - wy;

        final float dx = x2 - x1;
        final float dy = y2 - y1;
        final float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        // Grenade colour by tier sentinel: 10 = red, 12 = blue, else green (11 /
        // untiered assassin vial). Matches the webclient renderPoisonThrow palette
        // so both clients look identical; keeps assassin tiers 0-6 on the green look.
        final int gtier = vfx.getTier();
        final boolean gRed = gtier == 10, gBlue = gtier == 12;

        final float trailR = gRed ? 0.902f : gBlue ? 0.118f : 0.200f;
        final float trailG = gRed ? 0.157f : gBlue ? 0.435f : 0.600f;
        final float trailB = gRed ? 0.059f : gBlue ? 0.902f : 0.125f;
        final float dripR  = gRed ? 0.753f : gBlue ? 0.082f : 0.165f;
        final float dripG  = gRed ? 0.082f : gBlue ? 0.314f : 0.533f;
        final float dripB  = gRed ? 0.020f : gBlue ? 0.753f : 0.094f;
        final float glowR  = gRed ? 1.000f : gBlue ? 0.200f : 0.188f;
        final float glowG  = gRed ? 0.200f : gBlue ? 0.627f : 0.467f;
        final float glowB  = gRed ? 0.082f : gBlue ? 1.000f : 0.102f;
        final float bodyR  = gRed ? 1.000f : gBlue ? 0.176f : 0.251f;
        final float bodyG  = gRed ? 0.302f : gBlue ? 0.490f : 0.800f;
        final float bodyB  = gRed ? 0.051f : gBlue ? 1.000f : 0.188f;
        final float coreR  = gRed ? 1.000f : gBlue ? 0.565f : 0.565f;
        final float coreG  = gRed ? 0.851f : gBlue ? 0.816f : 1.000f;
        final float coreB  = gRed ? 0.333f : gBlue ? 1.000f : 0.439f;

        // Tall parabolic arc — 50% of throw distance as peak height
        int steps = 24;
        float arcHeight = dist * 0.5f;

        // Vial position along arc (t goes 0->1 over the duration)
        float vialFrac = Math.min(t, 1.0f);

        // Compute arc positions
        float[] arcX = new float[steps + 1];
        float[] arcY = new float[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float f = (float) i / steps;
            arcX[i] = x1 + dx * f;
            arcY[i] = y1 + dy * f - 4.0f * arcHeight * f * (1.0f - f);
        }

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Thick trail behind the vial / grenade
        for (int i = 0; i < steps; i++) {
            float f = (float) (i + 1) / steps;
            if (f > vialFrac) break;
            // Trail fades from thin at start to thick near vial
            float thickness = 3.0f + 5.0f * (f / Math.max(vialFrac, 0.01f));
            float trailAlpha = 0.15f + 0.4f * (f / Math.max(vialFrac, 0.01f));
            shapes.setColor(trailR, trailG, trailB, trailAlpha);
            shapes.rectLine(arcX[i], arcY[i], arcX[i + 1], arcY[i + 1], thickness);
        }

        // Dripping / sparking particles along the trail
        for (int i = 0; i < 6; i++) {
            float pf = vialFrac * (0.3f + 0.7f * i / 6.0f);
            int idx = Math.min((int) (pf * steps), steps);
            float dripY = arcY[idx] + (t * 30.0f * (i + 1) / 6.0f);  // drip downward over time
            float dripAlpha = Math.max(0, 0.5f - t * 0.6f);
            if (dripAlpha > 0) {
                shapes.setColor(dripR, dripG, dripB, dripAlpha);
                shapes.rect(arcX[idx] - 2, dripY - 1, 4, 3 + i);
            }
        }

        // Fat vial / grenade blob
        if (vialFrac < 1.0f) {
            int vialIdx = Math.min((int) (vialFrac * steps), steps);
            float vx = arcX[vialIdx];
            float vy = arcY[vialIdx];

            // Outer glow
            shapes.setColor(glowR, glowG, glowB, 0.4f);
            drawCircle(shapes, vx, vy, 12f, 10);
            // Main body
            shapes.setColor(bodyR, bodyG, bodyB, 0.9f);
            drawCircle(shapes, vx, vy, 8f, 10);
            // Bright core / highlight
            shapes.setColor(coreR, coreG, coreB, 0.8f);
            drawCircle(shapes, vx - 2, vy - 2, 3.5f, 8);
        }

        shapes.end();
    }

    /**
     * Sorcerer Reality Tear — pitch-black void disc, violet inner glow,
     * 6 jagged radial cracks rotating outward, 10 orbiting void shards.
     * Procedural port of renderer.js case 48 for native LibGDX.
     */
    public static void renderSanctuaryDome(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.90f ? 1.0f : 1.0f - (t - 0.90f) * 10f;
        final long now = System.currentTimeMillis();
        // Translucent dome
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.18f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.95f);
        drawCircleOutline(shapes, cx, cy, radius, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.8f);
        drawCircleOutline(shapes, cx, cy, radius * 0.97f, 64);
        drawCircleOutline(shapes, cx, cy, radius * 1.03f, 64);
        shapes.end();
        // 8 rising light pillars
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int pillars = 8;
        for (int i = 0; i < pillars; i++) {
            final float a = (i / (float) pillars) * (float) Math.PI * 2f + now * 0.0008f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float baseR = radius * 0.92f;
            final float px = cx + cosA * baseR;
            final float py = cy + sinA * baseR;
            final float pHeight = radius * 0.42f * (0.7f + 0.3f * (float) Math.sin(now * 0.005f + i));
            shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.55f);
            drawCircle(shapes, px, py - pHeight * 0.5f, 5f, 12);
        }
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(3f);
        for (int i = 0; i < pillars; i++) {
            final float a = (i / (float) pillars) * (float) Math.PI * 2f + now * 0.0008f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float baseR = radius * 0.92f;
            final float px = cx + cosA * baseR;
            final float py = cy + sinA * baseR;
            final float pHeight = radius * 0.42f * (0.7f + 0.3f * (float) Math.sin(now * 0.005f + i));
            shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.75f);
            shapes.line(px, py, px, py - pHeight);
        }
        // Holy cross center
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha);
        final float crossLen = radius * 0.35f;
        shapes.line(cx - crossLen, cy, cx + crossLen, cy);
        shapes.line(cx, cy - crossLen, cx, cy + crossLen);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.85f);
        shapes.line(cx - crossLen * 0.7f, cy - crossLen * 0.7f,
                    cx + crossLen * 0.7f, cy + crossLen * 0.7f);
        shapes.line(cx + crossLen * 0.7f, cy - crossLen * 0.7f,
                    cx - crossLen * 0.7f, cy + crossLen * 0.7f);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Bright center pulse
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final float pulse = 0.55f + 0.35f * (float) Math.sin(now * 0.008f);
        shapes.setColor(1f, 1f, 1f, alpha * pulse);
        drawCircle(shapes, cx, cy, 6f, 18);
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha);
        drawCircle(shapes, cx, cy, 3f, 12);
        shapes.end();
    }

    /**
     * Necromancer Vampiric Latch — dark blood-red ground halo, 8 snaking
     * tendrils that oscillate perpendicular to outward axis with pulsing
     * mouth caps at the rim, central heart pulsing.
     */
    public static void renderVampiricLatch(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        // Ground halo
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.19f, 0.03f, 0.06f, alpha * 0.5f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.75f, 0.13f, 0.25f, alpha * 0.9f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(1f);
        // 8 snaking tendrils
        final int tendrils = 8;
        final int segs = 6;
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.13f, 0.25f, alpha * 0.95f);
        for (int i = 0; i < tendrils; i++) {
            final float a = (i / (float) tendrils) * (float) Math.PI * 2f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float perpX = (float) Math.cos(a + (float) Math.PI / 2f);
            final float perpY = (float) Math.sin(a + (float) Math.PI / 2f);
            final float phase = now * 0.004f + i;
            float prevX = cx, prevY = cy;
            for (int s = 1; s <= segs; s++) {
                final float sT = s / (float) segs;
                final float sR = radius * sT;
                final float wave = (float) Math.sin(phase + sT * (float) Math.PI * 3f) * 6f * sT;
                final float px = cx + cosA * sR + perpX * wave;
                final float py = cy + sinA * sR + perpY * wave;
                shapes.line(prevX, prevY, px, py);
                prevX = px; prevY = py;
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Mouth caps + center heart
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < tendrils; i++) {
            final float a = (i / (float) tendrils) * (float) Math.PI * 2f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float perpX = (float) Math.cos(a + (float) Math.PI / 2f);
            final float perpY = (float) Math.sin(a + (float) Math.PI / 2f);
            final float phase = now * 0.004f + i;
            // End of last segment (s = segs)
            final float wave = (float) Math.sin(phase + (float) Math.PI * 3f) * 6f;
            final float ex = cx + cosA * radius + perpX * wave;
            final float ey = cy + sinA * radius + perpY * wave;
            shapes.setColor(1f, 0.5f, 0.63f, alpha);
            drawCircle(shapes, ex, ey, 3.5f, 12);
        }
        // Central heart pulse
        final float pulse = 0.7f + 0.3f * (float) Math.sin(now * 0.009f);
        shapes.setColor(0.75f, 0.13f, 0.25f, alpha * pulse);
        drawCircle(shapes, cx, cy, 8f, 18);
        shapes.setColor(1f, 0.5f, 0.63f, alpha);
        drawCircle(shapes, cx, cy, 4f, 12);
        shapes.end();
    }

    /**
     * Heavy Debuffer Sidearm — quick silver rapier stab. 4 cardinal sparkle
     * arms shoot outward, white core flash, sparkle stars at the tips.
     * Procedural port of renderer.js case 53.
     */
    public static void renderRapierStab(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float corePulse = 1.0f - t;
        final float armReach = radius * (0.4f + 0.7f * t);

        // 4-axis sparkle lines (N/S/E/W) — outward dashes
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.54f, 0.60f, 0.66f, alpha * 0.85f);
        for (int i = 0; i < 4; i++) {
            final float a = (i / 4f) * (float) Math.PI * 2f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float fx = cx + cosA * (armReach * 0.35f);
            final float fy = cy + sinA * (armReach * 0.35f);
            final float tx = cx + cosA * armReach;
            final float ty = cy + sinA * armReach;
            shapes.line(fx, fy, tx, ty);
        }
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha);
        for (int i = 0; i < 4; i++) {
            final float a = (i / 4f) * (float) Math.PI * 2f;
            final float cosA = (float) Math.cos(a), sinA = (float) Math.sin(a);
            final float fx = cx + cosA * (armReach * 0.35f);
            final float fy = cy + sinA * (armReach * 0.35f);
            final float tx = cx + cosA * armReach;
            final float ty = cy + sinA * armReach;
            shapes.line(fx, fy, tx, ty);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Sparkle stars at tips + bright core flash
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 4; i++) {
            final float a = (i / 4f) * (float) Math.PI * 2f;
            final float tx = cx + (float) Math.cos(a) * armReach;
            final float ty = cy + (float) Math.sin(a) * armReach;
            shapes.setColor(1f, 1f, 1f, alpha * (1.0f - t * 0.6f));
            drawCircle(shapes, tx, ty, 3f + 2f * (1f - t), 12);
        }
        shapes.setColor(1f, 1f, 1f, alpha * corePulse);
        drawCircle(shapes, cx, cy, 6f + 3f * corePulse, 18);
        shapes.setColor(0.88f, 0.90f, 0.93f, alpha * corePulse * 0.7f);
        drawCircle(shapes, cx, cy, 10f + 4f * corePulse, 18);
        shapes.end();
    }

    /**
     * Heavy Debuffer Ankle Strike — bottom-half horizontal arc sweep. Steel
     * underlay, red core, white highlight. Quick ankle-level glint at center.
     * Procedural port of renderer.js case 54.
     */
    public static void renderLowSwing(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float reach = radius * 1.05f;
        final int segs = 10;
        // Sweep across the lower half of the ring. LibGDX is Y-up; PIXI Y is
        // down. Negate sin so the arc reads "lower" on screen (below caster).
        final float a0 = (float) (Math.PI * 0.15);
        final float a1 = (float) (Math.PI * 0.85);

        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(8f);
        shapes.setColor(0.25f, 0.03f, 0.06f, alpha * 0.8f);
        for (int s = 0; s < segs; s++) {
            final float p0 = a0 + ((a1 - a0) * s) / segs;
            final float p1 = a0 + ((a1 - a0) * (s + 1)) / segs;
            shapes.line(cx + (float) Math.cos(p0) * reach, cy - (float) Math.sin(p0) * reach,
                        cx + (float) Math.cos(p1) * reach, cy - (float) Math.sin(p1) * reach);
        }
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.75f, 0.16f, 0.19f, alpha);
        for (int s = 0; s < segs; s++) {
            final float p0 = a0 + ((a1 - a0) * s) / segs;
            final float p1 = a0 + ((a1 - a0) * (s + 1)) / segs;
            shapes.line(cx + (float) Math.cos(p0) * reach, cy - (float) Math.sin(p0) * reach,
                        cx + (float) Math.cos(p1) * reach, cy - (float) Math.sin(p1) * reach);
        }
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.9f);
        for (int s = 0; s < segs; s++) {
            final float p0 = a0 + ((a1 - a0) * s) / segs;
            final float p1 = a0 + ((a1 - a0) * (s + 1)) / segs;
            shapes.line(cx + (float) Math.cos(p0) * reach, cy - (float) Math.sin(p0) * reach,
                        cx + (float) Math.cos(p1) * reach, cy - (float) Math.sin(p1) * reach);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Small ankle-level steel glint just below caster.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.63f, 0.66f, 0.69f, alpha * (1f - t));
        drawCircle(shapes, cx, cy - 4f, 4f, 12);
        shapes.end();
    }

    /**
     * Heavy Debuffer Disarm — ultimate flourish. Triple-ring expanding
     * outward, 8 sparkle stars at cardinal/diagonal points, central impact
     * burst. Procedural port of renderer.js case 55.
     */
    public static void renderDisarmFlourish(ShapeRenderer shapes, ActiveVisualEffect vfx,
                                       float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float elapsed = vfx != null ? vfx.getElapsed() : 0f;

        // Three pulsing rings, time-offset for a cascade outward.
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < 3; i++) {
            final float ringP = (t + i * 0.18f) % 1.0f;
            if (ringP > 0.85f) continue;
            final float ringR = radius * (0.2f + ringP * 1.0f);
            final float ringA = alpha * (1.0f - ringP) * 0.9f;
            Gdx.gl.glLineWidth(6f);
            shapes.setColor(0.50f, 0.28f, 0.03f, ringA * 0.6f);
            drawCircleOutline(shapes, cx, cy, ringR, 48);
            Gdx.gl.glLineWidth(3f);
            shapes.setColor(1.00f, 0.82f, 0.30f, ringA);
            drawCircleOutline(shapes, cx, cy, ringR, 48);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // 8 sparkle stars at cardinal+diagonal points.
        final float starR = radius * (0.7f + 0.25f * t);
        final float starPulse = 0.5f + 0.5f * (float) Math.sin(elapsed * 0.024);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 8; i++) {
            final float a = (i / 8f) * (float) Math.PI * 2f + elapsed * 0.001f;
            final float tx = cx + (float) Math.cos(a) * starR;
            final float ty = cy + (float) Math.sin(a) * starR;
            shapes.setColor(1.00f, 0.82f, 0.30f, alpha);
            drawCircle(shapes, tx, ty, 5f + 2f * starPulse, 8);
            shapes.setColor(1f, 1f, 1f, alpha);
            drawCircle(shapes, tx, ty, 2f, 8);
        }
        // Central impact burst — bright early, fades out.
        final float earlyA = Math.max(0f, 1f - t * 2.5f);
        if (earlyA > 0f) {
            shapes.setColor(1f, 1f, 1f, alpha * earlyA);
            drawCircle(shapes, cx, cy, 10f + 8f * earlyA, 24);
            shapes.setColor(1.00f, 0.82f, 0.30f, alpha * earlyA * 0.85f);
            drawCircle(shapes, cx, cy, 18f + 10f * earlyA, 24);
        }
        shapes.end();
    }

    /**
     * Heavy Buffer Divine Beam — vertical column of golden light rising from
     * the caster, ground halo, and rising heal sparkles. Procedural port of
     * renderer.js case 56. LibGDX is Y-up so the column rises +y.
     */
    public static void renderDivineBeam(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float beamH = radius * 2.2f;
        final float beamW = Math.max(12f, radius * 0.35f);
        final float colA = alpha * (1.0f - t * 0.4f);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Outer column glow
        shapes.setColor(1.00f, 0.94f, 0.63f, colA * 0.35f);
        shapes.rect(cx - beamW, cy, beamW * 2f, beamH);
        // Inner column — bright core
        shapes.setColor(1.00f, 0.83f, 0.30f, colA * 0.65f);
        shapes.rect(cx - beamW * 0.5f, cy, beamW, beamH);
        // Hot white spine
        shapes.setColor(1f, 1f, 1f, colA);
        shapes.rect(cx - 3f, cy, 6f, beamH);
        // Ground halo fill
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.45f);
        drawCircle(shapes, cx, cy, radius * 0.7f, 36);
        shapes.end();

        // Ground halo rings
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(1.00f, 0.83f, 0.30f, alpha * 0.9f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircleOutline(shapes, cx, cy, radius * 0.85f, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Rising sparkle particles (heal feel) — Y-up: rise in +y.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 8; i++) {
            final float seed = i * 0.713f;
            final float st = (t + seed) % 1.0f;
            final float angle = seed * (float) Math.PI * 2f;
            final float dist = radius * (0.25f + 0.6f * ((seed * 11f) % 1f));
            final float px = cx + (float) Math.cos(angle) * dist;
            final float py = cy + (float) Math.sin(angle) * dist + st * radius * 0.7f;
            shapes.setColor(1f, 1f, 1f, alpha * (1f - st));
            drawCircle(shapes, px, py, 3f, 10);
            shapes.setColor(1.00f, 0.83f, 0.30f, alpha * (1f - st) * 0.8f);
            drawCircle(shapes, px, py, 5f, 10);
        }
        shapes.end();
    }

    /**
     * Heavy Buffer Fortify Aura — persistent regen sigil. Outer ring,
     * hexagram (two interlocking triangles), pulsing rising sparkles.
     * Procedural port of renderer.js case 57.
     */
    public static void renderFortifyAura(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.90f ? 1.0f : 1.0f - (t - 0.90f) * 10f;
        final long now = System.currentTimeMillis();
        final float pulse = 0.65f + 0.35f * (float) Math.sin(now * 0.005);

        // Outer ring (dark base + blue overlay).
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.06f, 0.22f, 0.28f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.25f, 0.66f, 1.00f, alpha * 0.9f * pulse);
        drawCircleOutline(shapes, cx, cy, radius, 48);

        // Hexagram — two interlocking equilateral triangles.
        final float innerR = radius * 0.62f;
        Gdx.gl.glLineWidth(3f);
        // Triangle A — green, pointing up (rotation = -PI/2 in math convention).
        shapes.setColor(0.38f, 1.00f, 0.53f, alpha * pulse);
        {
            float[] tx = new float[3], ty = new float[3];
            for (int i = 0; i < 3; i++) {
                final float a = (-(float) Math.PI / 2f) + (i / 3f) * (float) Math.PI * 2f;
                tx[i] = cx + (float) Math.cos(a) * innerR;
                ty[i] = cy + (float) Math.sin(a) * innerR;
            }
            shapes.line(tx[0], ty[0], tx[1], ty[1]);
            shapes.line(tx[1], ty[1], tx[2], ty[2]);
            shapes.line(tx[2], ty[2], tx[0], ty[0]);
        }
        // Triangle B — blue, pointing down (rotation = PI/2).
        shapes.setColor(0.25f, 0.66f, 1.00f, alpha * pulse);
        {
            float[] tx = new float[3], ty = new float[3];
            for (int i = 0; i < 3; i++) {
                final float a = ((float) Math.PI / 2f) + (i / 3f) * (float) Math.PI * 2f;
                tx[i] = cx + (float) Math.cos(a) * innerR;
                ty[i] = cy + (float) Math.sin(a) * innerR;
            }
            shapes.line(tx[0], ty[0], tx[1], ty[1]);
            shapes.line(tx[1], ty[1], tx[2], ty[2]);
            shapes.line(tx[2], ty[2], tx[0], ty[0]);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Rising sparkles — alternating green/blue. Y-up: rise +y.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int sparkles = 14;
        for (int i = 0; i < sparkles; i++) {
            final float seed = i * 0.451f;
            final float cycle = ((now * 0.0006f) + seed) % 1.0f;
            final float angle = (i / (float) sparkles) * (float) Math.PI * 2f + now * 0.0005f;
            final float dist = radius * (0.3f + 0.55f * ((seed * 23f) % 1f));
            final float px = cx + (float) Math.cos(angle) * dist;
            final float py = cy + (float) Math.sin(angle) * dist + cycle * radius * 0.65f;
            final float sA = (1f - cycle) * 0.9f;
            if ((i & 1) == 0) shapes.setColor(0.25f, 0.66f, 1.00f, alpha * sA);
            else              shapes.setColor(0.38f, 1.00f, 0.53f, alpha * sA);
            drawCircle(shapes, px, py, 2.5f, 8);
            shapes.setColor(1f, 1f, 1f, alpha * sA * 0.7f);
            drawCircle(shapes, px, py, 1.2f, 6);
        }
        // Central glint.
        shapes.setColor(1f, 1f, 1f, alpha * pulse * 0.7f);
        drawCircle(shapes, cx, cy, 4f, 12);
        shapes.end();
    }

    /**
     * Heavy DPS Ground Pound — expanding dust ring, 6 radial ground cracks,
     * lingering dust puffs, central impact flash. Procedural port of
     * renderer.js case 58.
     */
    public static void renderGroundPound(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;

        // 1. Expanding dust ring — fast outward in first 40% of life.
        final float ringP = Math.min(1f, t / 0.4f);
        final float ringR = radius * (0.2f + 0.8f * ringP);
        final float ringA = alpha * (1f - ringP * 0.5f);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(8f);
        shapes.setColor(0.25f, 0.16f, 0.06f, ringA * 0.85f);
        drawCircleOutline(shapes, cx, cy, ringR, 48);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.72f, 0.56f, 0.38f, ringA);
        drawCircleOutline(shapes, cx, cy, ringR, 48);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.88f, 0.78f, 0.56f, ringA * 0.9f);
        drawCircleOutline(shapes, cx, cy, ringR, 48);

        // 2. 6 radial crack lines with a midpoint kink for texture.
        final float crackR = radius * (0.5f + 0.55f * t);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.25f, 0.16f, 0.06f, alpha * (1f - t * 0.4f));
        for (int i = 0; i < 6; i++) {
            final float a = (i / 6f) * (float) Math.PI * 2f + 0.3f;
            final float midR = crackR * 0.55f;
            final float midX = cx + (float) Math.cos(a) * midR;
            final float midY = cy + (float) Math.sin(a) * midR;
            final float jitterA = a + ((i % 2 == 0) ? 0.15f : -0.15f);
            final float endX = cx + (float) Math.cos(jitterA) * crackR;
            final float endY = cy + (float) Math.sin(jitterA) * crackR;
            shapes.line(cx, cy, midX, midY);
            shapes.line(midX, midY, endX, endY);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // 3. Lingering dust puffs — slowly drift up (Y-up: +y) in second half.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int puffs = 8;
        for (int i = 0; i < puffs; i++) {
            final float seed = i * 0.617f;
            final float angle = (i / (float) puffs) * (float) Math.PI * 2f + seed * 0.4f;
            final float dist = radius * (0.3f + 0.5f * ((seed * 13f) % 1f));
            final float px = cx + (float) Math.cos(angle) * dist;
            final float py = cy + (float) Math.sin(angle) * dist + t * 8f;
            final float puffR = 4f + 4f * t;
            shapes.setColor(0.72f, 0.56f, 0.38f, alpha * (1f - t) * 0.75f);
            drawCircle(shapes, px, py, puffR, 12);
            shapes.setColor(0.88f, 0.78f, 0.56f, alpha * (1f - t) * 0.55f);
            drawCircle(shapes, px, py, puffR * 0.5f, 10);
        }
        // 4. Central impact flash — first beat only.
        final float earlyA = Math.max(0f, 1f - t * 4f);
        if (earlyA > 0f) {
            shapes.setColor(1f, 1f, 1f, alpha * earlyA);
            drawCircle(shapes, cx, cy, 12f * earlyA + 6f, 18);
            shapes.setColor(0.88f, 0.78f, 0.56f, alpha * earlyA * 0.8f);
            drawCircle(shapes, cx, cy, 18f * earlyA + 8f, 18);
        }
        shapes.end();
    }

    /** Druid Root Growth — gnarled roots writhe outward from the caster with a green
     *  ensnaring pulse, marking the DoT zone. Y-up coordinate space. */
    public static void renderDruidRoots(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.8f ? 1.0f : 1.0f - (t - 0.8f) * 5.0f;
        final float tt = System.currentTimeMillis() * 0.001f;
        final float grow = Math.min(1f, t * 2.2f);

        // Earthen ground disc
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.18f, 0.07f, alpha * 0.30f);
        drawCircle(shapes, cx, cy, radius, 40);
        shapes.end();

        // Writhing bark-brown root tendrils
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        final int tendrils = 11;
        final int segs = 9;
        for (int i = 0; i < tendrils; i++) {
            final float baseA = (i / (float) tendrils) * (float) Math.PI * 2f + i * 0.37f;
            final float cosA = (float) Math.cos(baseA), sinA = (float) Math.sin(baseA);
            final float perpX = -sinA, perpY = cosA;
            final float phase = tt * 1.6f + i;
            float prevX = cx, prevY = cy;
            shapes.setColor(0.32f, 0.20f, 0.09f, alpha * 0.9f);
            for (int s = 1; s <= segs; s++) {
                final float sT = s / (float) segs;
                final float sR = radius * sT * grow;
                final float wave = (float) Math.sin(phase + sT * Math.PI * 2.4f) * 10f * sT;
                final float px = cx + cosA * sR + perpX * wave;
                final float py = cy + sinA * sR + perpY * wave;
                shapes.line(prevX, prevY, px, py);
                prevX = px; prevY = py;
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Green ensnaring pulse ring
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(3f);
        final float pulse = 0.55f + 0.45f * (float) Math.sin(tt * 4f);
        shapes.setColor(0.30f, 0.85f, 0.25f, alpha * 0.7f * pulse);
        drawCircleOutline(shapes, cx, cy, radius * (0.6f + 0.4f * grow), 40);
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Sprouting leaf tips + central bulb
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < tendrils; i++) {
            final float baseA = (i / (float) tendrils) * (float) Math.PI * 2f + i * 0.37f;
            final float cosA = (float) Math.cos(baseA), sinA = (float) Math.sin(baseA);
            final float perpX = -sinA, perpY = cosA;
            final float phase = tt * 1.6f + i;
            final float sR = radius * grow;
            final float wave = (float) Math.sin(phase + (float) Math.PI * 2.4f) * 10f;
            final float px = cx + cosA * sR + perpX * wave;
            final float py = cy + sinA * sR + perpY * wave;
            shapes.setColor(0.20f, 0.55f, 0.16f, alpha * 0.9f);
            drawCircle(shapes, px, py, 5f, 8);
            shapes.setColor(0.45f, 0.95f, 0.35f, alpha);
            drawCircle(shapes, px, py, 2.5f, 6);
        }
        shapes.setColor(0.20f, 0.45f, 0.14f, alpha * 0.8f);
        drawCircle(shapes, cx, cy, 11f, 16);
        shapes.setColor(0.55f, 0.95f, 0.40f, alpha);
        drawCircle(shapes, cx, cy, 6f, 14);
        shapes.end();
    }

    /** Druid Moonlight — night-blue healing aura with a silver crescent moon,
     *  descending moonbeams and rising healing motes. Y-up. */
    public static void renderDruidMoonlight(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.75f ? 1.0f : 1.0f - (t - 0.75f) * 4.0f;
        final float tt = System.currentTimeMillis() * 0.001f;
        final float ringR = radius * (0.35f + 0.65f * Math.min(1f, t * 1.8f));

        // Night-blue base
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.12f, 0.30f, alpha * 0.30f);
        drawCircle(shapes, cx, cy, radius, 44);
        shapes.setColor(0.18f, 0.22f, 0.45f, alpha * 0.18f);
        drawCircle(shapes, cx, cy, ringR * 0.9f, 40);
        shapes.end();

        // Silver moon-glow rings
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.85f, 0.90f, 1.00f, alpha * 0.9f);
        drawCircleOutline(shapes, cx, cy, ringR, 44);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.70f, 0.80f, 1.00f, alpha * 0.7f);
        drawCircleOutline(shapes, cx, cy, ringR * 0.9f, 44);
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Slanted moonbeams
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(3f);
        final int beams = 6;
        for (int i = 0; i < beams; i++) {
            final float a = (i / (float) beams) * (float) Math.PI * 2f + tt * 0.3f;
            final float beamA = alpha * (0.25f + 0.35f * (0.5f + 0.5f * (float) Math.sin(tt * 2f + i)));
            shapes.setColor(0.80f, 0.88f, 1.00f, beamA);
            final float ox = (float) Math.cos(a) * radius * 0.9f;
            final float oy = (float) Math.sin(a) * radius * 0.9f;
            shapes.line(cx + ox, cy + oy + radius * 0.5f, cx + ox * 0.4f, cy + oy * 0.4f);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Rising healing motes (+y) + crescent moon
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int motes = 14;
        for (int i = 0; i < motes; i++) {
            final float seed = i * 0.453f;
            final float ph = (t * 1.1f + seed) % 1f;
            final float mA = (float) Math.sin(ph * Math.PI) * alpha;
            if (mA <= 0.05f) continue;
            final float a = seed * (float) Math.PI * 2f + tt * 0.6f;
            final float dist = radius * (0.2f + 0.7f * ((seed * 13f) % 1f));
            final float mx = cx + (float) Math.cos(a) * dist;
            final float my = cy + (float) Math.sin(a) * dist * 0.5f + ph * radius * 0.5f;
            shapes.setColor(0.70f, 0.85f, 1.00f, mA * 0.8f);
            drawCircle(shapes, mx, my, 4f, 8);
            shapes.setColor(1f, 1f, 1f, mA);
            drawCircle(shapes, mx, my, 1.8f, 6);
        }
        // Crescent: silver disc carved by an offset night-blue disc.
        final float moonR = 14f + 2f * (float) Math.sin(tt * 2f);
        shapes.setColor(0.92f, 0.95f, 1.00f, alpha * 0.95f);
        drawCircle(shapes, cx, cy, moonR, 24);
        shapes.setColor(0.10f, 0.12f, 0.30f, alpha);
        drawCircle(shapes, cx + moonR * 0.5f, cy + moonR * 0.18f, moonR * 0.92f, 24);
        shapes.end();
    }

    /** Druid Wild Surge (ultimate) — spiraling vine arms, bursting leaves and a
     *  radiant verdant core. Y-up. */
    public static void renderDruidWildSurge(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.8f ? 1.0f : 1.0f - (t - 0.8f) * 5.0f;
        final float tt = System.currentTimeMillis() * 0.001f;

        // Verdant ground halo
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.40f, 0.10f, alpha * 0.25f);
        drawCircle(shapes, cx, cy, radius * (0.6f + 0.4f * Math.min(1f, t * 2f)), 44);
        shapes.end();

        // Spiraling vine arms
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        final int arms = 3;
        final int segs = 26;
        for (int arm = 0; arm < arms; arm++) {
            final float armOff = (arm / (float) arms) * (float) Math.PI * 2f;
            shapes.setColor(0.25f, 0.80f, 0.25f, alpha * 0.9f);
            float prevX = cx, prevY = cy;
            boolean has = false;
            for (int s = 0; s <= segs; s++) {
                final float sT = s / (float) segs;
                final float rr = radius * sT;
                final float a = armOff + sT * (float) Math.PI * 3f + tt * 1.4f;
                final float px = cx + (float) Math.cos(a) * rr;
                final float py = cy + (float) Math.sin(a) * rr;
                if (has) shapes.line(prevX, prevY, px, py);
                prevX = px; prevY = py; has = true;
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Bursting leaves + radiant core
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int leaves = 18;
        for (int i = 0; i < leaves; i++) {
            final float seed = i * 0.371f;
            final float ph = (t * 1.3f + seed) % 1f;
            final float lA = (float) Math.sin(ph * Math.PI) * alpha;
            if (lA <= 0.05f) continue;
            final float a = seed * (float) Math.PI * 2f + tt * 0.5f;
            final float orbR = radius * (0.2f + 0.8f * ph);
            final float lx = cx + (float) Math.cos(a) * orbR;
            final float ly = cy + (float) Math.sin(a) * orbR;
            shapes.setColor(0.20f, 0.60f, 0.18f, lA * 0.85f);
            drawCircle(shapes, lx, ly, 5f, 8);
            shapes.setColor(0.80f, 1.00f, 0.30f, lA);
            drawCircle(shapes, lx, ly, 2.5f, 6);
        }
        final float surge = 0.55f + 0.45f * (float) Math.sin(tt * 6f);
        shapes.setColor(0.50f, 0.95f, 0.25f, alpha * 0.7f * surge);
        drawCircle(shapes, cx, cy, 14f, 18);
        shapes.setColor(0.90f, 1.00f, 0.55f, alpha * surge);
        drawCircle(shapes, cx, cy, 7f, 14);
        shapes.end();
    }

    /**
     * Rogue Smoke Poof — billowy three-tone puff cluster + brief dagger
     * silhouettes during the first 35% of life + tier-tinted POP flash for
     * the first 30% + warm ember flecks drifting outward and upward.
     * Procedural port of renderer.js case 9.
     */
    public static void renderSmokePoof(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float puffR = radius * (0.6f + t * 1.4f);
        // 12 overlapping puff circles, rotating slowly
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 12; i++) {
            final float a = (i / 12f) * (float) Math.PI * 2f + now * 0.002f;
            final float dist = puffR * (0.30f + 0.20f * (i % 2));
            final float px = cx + (float) Math.cos(a) * dist;
            final float py = cy + (float) Math.sin(a) * dist;
            final float pr = puffR * (0.55f + 0.12f * (float) Math.sin(now * 0.01f + i));
            shapes.setColor(0.55f, 0.55f, 0.60f, alpha * 0.18f);
            drawCircle(shapes, px, py, pr, 18);
            shapes.setColor(0.50f, 0.50f, 0.50f, alpha * 0.32f);
            drawCircle(shapes, px, py, pr * 0.78f, 16);
            shapes.setColor(0.25f, 0.25f, 0.25f, alpha * 0.4f);
            drawCircle(shapes, px, py, pr * 0.45f, 14);
        }
        shapes.end();
        // Dagger silhouettes (first 35%)
        if (t < 0.35f) {
            final float dagA = (1f - t / 0.35f) * alpha;
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            final int dCount = 4;
            for (int i = 0; i < dCount; i++) {
                final float a = (i / (float) dCount) * (float) Math.PI * 2f + (float) Math.PI / 4f;
                final float reach = puffR * (0.55f + t * 0.6f);
                final float dx = cx + (float) Math.cos(a) * reach;
                final float dy = cy + (float) Math.sin(a) * reach;
                final float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                shapes.setColor(0.69f, 0.69f, 0.75f, dagA * 0.85f);
                shapes.triangle(dx + cs * 8f, dy + sn * 8f,
                                dx + sn * 2.5f, dy - cs * 2.5f,
                                dx - cs * 4f,  dy - sn * 4f);
                shapes.triangle(dx + cs * 8f, dy + sn * 8f,
                                dx - cs * 4f, dy - sn * 4f,
                                dx - sn * 2.5f, dy + cs * 2.5f);
                shapes.setColor(1f, 1f, 1f, dagA * 0.9f);
                shapes.triangle(dx + cs * 7f, dy + sn * 7f,
                                dx + sn * 1f, dy - cs * 1f,
                                dx - cs * 2f, dy - sn * 2f);
                shapes.triangle(dx + cs * 7f, dy + sn * 7f,
                                dx - cs * 2f, dy - sn * 2f,
                                dx - sn * 1f, dy + cs * 1f);
            }
            shapes.end();
        }
        // POP flash (first 30%)
        if (t < 0.30f) {
            final float flashA = 1f - t / 0.30f;
            shapes.begin(ShapeRenderer.ShapeType.Line);
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(0.55f, 0.55f, 0.60f, flashA * 0.75f);
            drawCircleOutline(shapes, cx, cy, puffR * 0.7f * (1f + t * 1.2f), 48);
            Gdx.gl.glLineWidth(1f);
            shapes.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.55f, 0.55f, 0.60f, flashA * 0.55f);
            drawCircle(shapes, cx, cy, puffR * 0.55f * (1f + t), 32);
            shapes.setColor(1f, 1f, 1f, flashA * 0.85f);
            drawCircle(shapes, cx, cy, puffR * 0.35f * (1f + t), 24);
            shapes.end();
        }
        // Ember flecks
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 10; i++) {
            final float seed = i * 0.439f;
            final float phase = (t * 1.6f + seed) % 1.0f;
            final float a = (seed * (float) Math.PI * 2f + now * 0.001f) % ((float) Math.PI * 2f);
            final float dist = puffR * 0.4f + phase * puffR * 0.7f;
            final float lift = phase * 28f;
            final float ex = cx + (float) Math.cos(a) * dist;
            final float ey = cy + (float) Math.sin(a) * dist + lift;  // +lift: native Y-up flips relative to web Y-down
            final float eA = alpha * (1f - phase) * 0.95f;
            if (eA <= 0.05f) continue;
            shapes.setColor(1.00f, 0.55f, 0.15f, eA * 0.4f);
            drawCircle(shapes, ex, ey, 3f, 10);
            shapes.setColor(1.00f, 0.78f, 0.30f, eA);
            drawCircle(shapes, ex, ey, 1.5f, 8);
        }
        shapes.end();
    }

    /**
     * Wizard / Mystic Frost Nova — 12 diamond ice spikes radiating outward
     * from a cold halo, with a tiny white central frost burst.
     * Procedural port of renderer.js case 19.
     */
    public static void renderFrostNova(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        // Halo
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.50f, 0.82f, 1.00f, alpha * 0.18f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.50f, 0.82f, 1.00f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Crystal diamond spikes
        final int spikes = 12;
        final float spikeReach = radius * (0.55f + 0.55f * t);
        final float baseW = 9f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < spikes; i++) {
            final float a = (i / (float) spikes) * (float) Math.PI * 2f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            final float tipX = cx + ca * spikeReach;
            final float tipY = cy + sa * spikeReach;
            final float perpX = -sa * baseW, perpY = ca * baseW;
            final float innerX = cx + ca * (spikeReach * 0.35f);
            final float innerY = cy + sa * (spikeReach * 0.35f);
            final float tailX = cx + ca * (spikeReach * 0.05f);
            final float tailY = cy + sa * (spikeReach * 0.05f);
            // Diamond as two triangles
            shapes.setColor(0.50f, 0.82f, 1.00f, alpha * 0.6f);
            shapes.triangle(tipX, tipY, innerX + perpX, innerY + perpY, tailX, tailY);
            shapes.triangle(tipX, tipY, tailX, tailY, innerX - perpX, innerY - perpY);
        }
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        for (int i = 0; i < spikes; i++) {
            final float a = (i / (float) spikes) * (float) Math.PI * 2f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            final float tipX = cx + ca * spikeReach;
            final float tipY = cy + sa * spikeReach;
            final float perpX = -sa * baseW, perpY = ca * baseW;
            final float innerX = cx + ca * (spikeReach * 0.35f);
            final float innerY = cy + sa * (spikeReach * 0.35f);
            final float tailX = cx + ca * (spikeReach * 0.05f);
            final float tailY = cy + sa * (spikeReach * 0.05f);
            shapes.setColor(0.19f, 0.44f, 0.82f, alpha * 0.95f);
            shapes.line(tipX, tipY, innerX + perpX, innerY + perpY);
            shapes.line(innerX + perpX, innerY + perpY, tailX, tailY);
            shapes.line(tailX, tailY, innerX - perpX, innerY - perpY);
            shapes.line(innerX - perpX, innerY - perpY, tipX, tipY);
            // Inner core line
            shapes.setColor(1f, 1f, 1f, alpha);
            shapes.line(cx + ca * (spikeReach * 0.08f), cy + sa * (spikeReach * 0.08f), tipX, tipY);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Central frost burst
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, alpha * 0.55f);
        drawCircle(shapes, cx, cy, radius * 0.12f, 16);
        shapes.end();
    }

    /**
     * Hunter Reticle — red 4-corner crosshair sweeping inward toward the
     * target, with center cross-tick lock indicator.
     * Procedural port of renderer.js case 21.
     */
    public static void renderPoisonCloud(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        // Cloud body
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.19f, 0.31f, 0.06f, alpha * 0.35f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.setColor(0.38f, 0.75f, 0.13f, alpha * 0.40f);
        drawCircle(shapes, cx, cy, radius * 0.85f, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.67f, 1.00f, 0.50f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Bubbles
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int bubbles = 9;
        for (int i = 0; i < bubbles; i++) {
            final float seed = i * 0.591f;
            final float a = seed * (float) Math.PI * 2f + now * 0.001f;
            final float dist = radius * (0.2f + 0.55f * ((seed * 17f) % 1f));
            final float bx = cx + (float) Math.cos(a) * dist;
            final float by = cy + (float) Math.sin(a) * dist;
            final float br = 4f + 2f * (float) Math.sin(now * 0.008f + seed * 7f);
            shapes.setColor(0.38f, 0.75f, 0.13f, alpha * 0.75f);
            drawCircle(shapes, bx, by, br + 1f, 14);
            shapes.setColor(0.67f, 1.00f, 0.50f, alpha * 0.9f);
            drawCircle(shapes, bx, by, br * 0.55f, 12);
        }
        shapes.end();
    }

    /**
     * Wizard / Storm Lightning Strike — vertical zigzag bolt crashing down
     * with bright white core, ground impact ring expanding outward, and
     * yellow burst at impact point.
     * Procedural port of renderer.js case 25. Native Y-up means the bolt
     * descends from cy+r*2.2 to cy (web: from cy-r*2.2 downward to cy).
     */
    public static void renderLightningStrike(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final int segs = 6;
        // Outer dark zigzag
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(6f);
        shapes.setColor(0.50f, 0.38f, 0.06f, alpha * 0.7f);
        float px = cx + (float) Math.sin(now * 0.05f) * 8f;
        float py = cy + radius * 2.2f;
        for (int s = 1; s <= segs; s++) {
            final float frac = s / (float) segs;
            final float wob = ((float) Math.sin(now * 0.04f + s * 1.7f) * 14f) * (1f - frac);
            final float nx = cx + wob;
            final float ny = cy + radius * 2.2f * (1f - frac);
            shapes.line(px, py, nx, ny);
            px = nx; py = ny;
        }
        // Bright yellow bolt
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1.00f, 0.94f, 0.38f, alpha);
        px = cx + (float) Math.sin(now * 0.05f) * 8f;
        py = cy + radius * 2.2f;
        for (int s = 1; s <= segs; s++) {
            final float frac = s / (float) segs;
            final float wob = ((float) Math.sin(now * 0.04f + s * 1.7f) * 14f) * (1f - frac);
            final float nx = cx + wob;
            final float ny = cy + radius * 2.2f * (1f - frac);
            shapes.line(px, py, nx, ny);
            px = nx; py = ny;
        }
        // White core line straight down
        Gdx.gl.glLineWidth(1f);
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.line(cx, cy + radius * 2.2f, cx, cy);
        // Ground impact ring
        final float ringR = radius * (0.4f + t * 0.8f);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1.00f, 0.94f, 0.38f, (1f - t) * alpha);
        drawCircleOutline(shapes, cx, cy, ringR, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Burst at impact
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.94f, 0.38f, alpha * 0.6f);
        drawCircle(shapes, cx, cy, 14f, 20);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 6f, 14);
        shapes.end();
    }

    /**
     * Priest / Paladin Smite Flash — golden cross of light + central white
     * burst + 4 diagonal ground cracks radiating outward.
     * Procedural port of renderer.js case 29.
     */
    public static void renderSmiteFlash(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        // Gold cross
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(8f);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha);
        shapes.line(cx, cy - radius * 0.6f, cx, cy + radius * 0.6f);
        shapes.line(cx - radius * 0.45f, cy - radius * 0.1f,
                    cx + radius * 0.45f, cy - radius * 0.1f);
        // White inner highlight
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.line(cx, cy - radius * 0.6f, cx, cy + radius * 0.6f);
        shapes.line(cx - radius * 0.45f, cy - radius * 0.1f,
                    cx + radius * 0.45f, cy - radius * 0.1f);
        // Ground cracks (4 diagonals)
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.50f, 0.38f, 0.13f, alpha * 0.85f);
        for (int i = 0; i < 4; i++) {
            final float a = (i / 4f) * (float) Math.PI * 2f + (float) Math.PI / 4f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            shapes.line(cx + ca * radius * 0.6f, cy + sa * radius * 0.6f,
                        cx + ca * radius * 1.1f, cy + sa * radius * 1.1f);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Center burst
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.55f);
        drawCircle(shapes, cx, cy, radius * 0.55f, 32);
        shapes.setColor(1f, 1f, 1f, alpha * 0.75f);
        drawCircle(shapes, cx, cy, radius * 0.35f, 24);
        shapes.end();
    }

    /**
     * Necromancer Bone Spikes — 9 jagged white shards erupting from the
     * ground, each with a darker shadow base. Spikes grow in the first 45%
     * of life. Procedural port of renderer.js case 24.
     */
    public static void renderBoneSpikes(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, alpha * 0.15f);
        drawCircle(shapes, cx, cy, radius, 48);
        final int spikes = 9;
        final float grow = Math.min(t * 2.2f, 1f);
        for (int i = 0; i < spikes; i++) {
            final float seed = i * 0.683f;
            final float a = (i / (float) spikes) * (float) Math.PI * 2f + seed;
            final float dist = radius * (0.20f + 0.7f * ((seed * 13f) % 1f));
            final float bx = cx + (float) Math.cos(a) * dist;
            final float by = cy + (float) Math.sin(a) * dist;
            final float h = (14f + 10f * ((seed * 7f) % 1f)) * grow;
            final float w = 6f;
            // Shadow base triangle (point up in native Y-up: tip = by + h)
            shapes.setColor(0.31f, 0.28f, 0.19f, alpha * 0.7f);
            shapes.triangle(bx - w, by, bx + w, by, bx, by + h);
            // Bone face
            shapes.setColor(0.92f, 0.88f, 0.75f, alpha);
            shapes.triangle(bx - w * 0.7f, by + 1f, bx + w * 0.7f, by + 1f, bx, by + h * 0.92f);
        }
        shapes.end();
    }

    /**
     * Wizard Mana Bolt — 6 rotating arcane star arms with violet halo and
     * bright white core. Procedural port of renderer.js case 26.
     */
    public static void renderManaBolt(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.56f, 0.25f, 1.00f, alpha * 0.25f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        final int arms = 6;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.75f, 0.50f, 1.00f, alpha);
        for (int i = 0; i < arms; i++) {
            final float a = (i / (float) arms) * (float) Math.PI * 2f + now * 0.003f;
            shapes.line(cx, cy, cx + (float) Math.cos(a) * radius, cy + (float) Math.sin(a) * radius);
        }
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha);
        for (int i = 0; i < arms; i++) {
            final float a = (i / (float) arms) * (float) Math.PI * 2f + now * 0.003f;
            shapes.line(cx, cy,
                        cx + (float) Math.cos(a) * radius * 0.95f,
                        cy + (float) Math.sin(a) * radius * 0.95f);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.75f, 0.50f, 1.00f, alpha * 0.7f);
        drawCircle(shapes, cx, cy, 14f, 18);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 8f, 14);
        shapes.end();
    }

    /**
     * Mystic Time Stop — silver chronometer ring with 12 tick marks and
     * frozen hour/minute hands (no animation: time is stopped).
     * Procedural port of renderer.js case 27.
     */
    public static void renderTimeStop(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.25f, 0.28f, 0.35f, alpha * 0.20f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.75f, 0.82f, 0.88f, alpha * 0.95f);
        drawCircleOutline(shapes, cx, cy, radius, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircleOutline(shapes, cx, cy, radius - 3f, 64);
        // Tick marks
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.82f, 0.88f, alpha);
        for (int i = 0; i < 12; i++) {
            final float a = (i / 12f) * (float) Math.PI * 2f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            shapes.line(cx + ca * (radius - 6f), cy + sa * (radius - 6f),
                        cx + ca * (radius - 14f), cy + sa * (radius - 14f));
        }
        // Frozen hands (hour pointing up = +y native, minute toward upper-right)
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.line(cx, cy, cx, cy + radius * 0.55f);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.82f, 0.88f, alpha);
        shapes.line(cx, cy, cx + radius * 0.7f, cy - radius * 0.1f);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 5f, 14);
        shapes.end();
    }

    /**
     * Druid Beast Claws — 3 angled claw-slash arcs at the caster, each
     * with shadow + sharp claw + bright white highlight, rotating slowly.
     * Procedural port of renderer.js case 28.
     */
    public static void renderBeastClaws(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float reach = radius * 1.4f;
        final float sweep = 0.55f;
        final int slashes = 3;
        final int segs = 8;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < slashes; i++) {
            final float baseA = (i / (float) slashes) * (float) Math.PI * 2f + now * 0.001f;
            // Shadow
            Gdx.gl.glLineWidth(7f);
            shapes.setColor(0.25f, 0.13f, 0.06f, alpha * 0.85f);
            for (int s = 0; s < segs; s++) {
                final float a0 = baseA - sweep / 2f + (s / (float) segs) * sweep;
                final float a1 = baseA - sweep / 2f + ((s + 1) / (float) segs) * sweep;
                shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                            cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
            }
            // Sharp claw
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(0.82f, 0.63f, 0.38f, alpha);
            for (int s = 0; s < segs; s++) {
                final float a0 = baseA - sweep / 2f + (s / (float) segs) * sweep;
                final float a1 = baseA - sweep / 2f + ((s + 1) / (float) segs) * sweep;
                shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                            cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
            }
            // Bright highlight
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, alpha * 0.9f);
            for (int s = 0; s < segs; s++) {
                final float a0 = baseA - sweep / 2f + (s / (float) segs) * sweep;
                final float a1 = baseA - sweep / 2f + ((s + 1) / (float) segs) * sweep;
                shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                            cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
    }

    /**
     * Ninja Death Blossom ult — 8 radial slash arcs with dark outer trace
     * and bright blade core, rotating with progress + red center pip.
     * Procedural port of renderer.js case 30.
     */
    public static void renderDeathBlossom(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final int slashes = 8;
        final float reach = radius * 1.1f;
        final float sweep = 0.42f;
        final int segs = 6;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < slashes; i++) {
            final float baseA = (i / (float) slashes) * (float) Math.PI * 2f + t * (float) Math.PI * 0.5f;
            // Outer dark
            Gdx.gl.glLineWidth(6f);
            shapes.setColor(0.16f, 0.13f, 0.19f, alpha * 0.85f);
            for (int s = 0; s < segs; s++) {
                final float a0 = baseA - sweep / 2f + (s / (float) segs) * sweep;
                final float a1 = baseA - sweep / 2f + ((s + 1) / (float) segs) * sweep;
                shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                            cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
            }
            // Bright blade
            Gdx.gl.glLineWidth(3f);
            shapes.setColor(0.88f, 0.88f, 0.94f, alpha);
            for (int s = 0; s < segs; s++) {
                final float a0 = baseA - sweep / 2f + (s / (float) segs) * sweep;
                final float a1 = baseA - sweep / 2f + ((s + 1) / (float) segs) * sweep;
                shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                            cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.25f, 0.38f, alpha);
        drawCircle(shapes, cx, cy, 6f, 14);
        shapes.end();
    }

    /**
     * Bard Inspire Bloom — 6 golden flower petals expanding outward from
     * the center, with deep-gold base, gold body, and white core.
     * Procedural port of renderer.js case 31.
     */
    public static void renderInspireBloom(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final int petals = 6;
        final float reach = radius * (0.55f + 0.55f * t);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < petals; i++) {
            final float a = (i / (float) petals) * (float) Math.PI * 2f + t * (float) Math.PI * 0.25f;
            final float px = cx + (float) Math.cos(a) * reach * 0.5f;
            final float py = cy + (float) Math.sin(a) * reach * 0.5f;
            shapes.setColor(0.63f, 0.44f, 0.13f, alpha * 0.75f);
            drawCircle(shapes, px, py, reach * 0.32f, 18);
            shapes.setColor(1.00f, 0.82f, 0.38f, alpha * 0.95f);
            drawCircle(shapes, px, py, reach * 0.26f, 16);
            shapes.setColor(1f, 1f, 1f, alpha * 0.6f);
            drawCircle(shapes, px, py, reach * 0.12f, 12);
        }
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 6f, 14);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha);
        drawCircle(shapes, cx, cy, 11f, 18);
        shapes.end();
    }

    /**
     * Berserker Reckless Slash — wide sweeping red arc with dark outer
     * trace + bright red blade + white highlight along the sweep.
     * Procedural port of renderer.js case 32. (Web uses no rotation —
     * always sweeps right; we keep that for consistency.)
     */
    public static void renderRecklessSlash(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float reach = radius * 1.05f;
        final float sweep = 1.4f;
        final int segs = 14;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(10f);
        shapes.setColor(0.38f, 0.00f, 0.06f, alpha * 0.85f);
        for (int s = 0; s < segs; s++) {
            final float a0 = -sweep / 2f + (s / (float) segs) * sweep;
            final float a1 = -sweep / 2f + ((s + 1) / (float) segs) * sweep;
            shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                        cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
        }
        Gdx.gl.glLineWidth(6f);
        shapes.setColor(1.00f, 0.13f, 0.19f, alpha);
        for (int s = 0; s < segs; s++) {
            final float a0 = -sweep / 2f + (s / (float) segs) * sweep;
            final float a1 = -sweep / 2f + ((s + 1) / (float) segs) * sweep;
            shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                        cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
        }
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.9f);
        for (int s = 0; s < segs; s++) {
            final float a0 = -sweep / 2f + (s / (float) segs) * sweep;
            final float a1 = -sweep / 2f + ((s + 1) / (float) segs) * sweep;
            shapes.line(cx + (float) Math.cos(a0) * reach, cy + (float) Math.sin(a0) * reach,
                        cx + (float) Math.cos(a1) * reach, cy + (float) Math.sin(a1) * reach);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
    }

    /**
     * Ninja Star Shuriken — rotating 4-point throwing star drawn as two
     * crossed triangles + bright cross highlight + dark center stud.
     * Procedural port of renderer.js case 33.
     */
    public static void renderStarShuriken(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float rot = now * 0.018f;
        final float armR = radius * (0.6f + 0.4f * t);
        final float[] px = new float[4];
        final float[] py = new float[4];
        for (int i = 0; i < 4; i++) {
            final float a = rot + (i / 4f) * (float) Math.PI * 2f;
            px[i] = cx + (float) Math.cos(a) * armR;
            py[i] = cy + (float) Math.sin(a) * armR;
        }
        // Steel body as two triangles forming the diamond
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.75f, 0.78f, 0.82f, alpha * 0.85f);
        shapes.triangle(px[0], py[0], px[1], py[1], px[2], py[2]);
        shapes.triangle(px[0], py[0], px[2], py[2], px[3], py[3]);
        shapes.end();
        // Outer dark frame
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.25f, 0.28f, 0.31f, alpha);
        shapes.line(px[0], py[0], px[1], py[1]);
        shapes.line(px[1], py[1], px[2], py[2]);
        shapes.line(px[2], py[2], px[3], py[3]);
        shapes.line(px[3], py[3], px[0], py[0]);
        // Bright cross highlight
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.line(px[0], py[0], px[2], py[2]);
        shapes.line(px[1], py[1], px[3], py[3]);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.25f, 0.28f, 0.31f, alpha);
        drawCircle(shapes, cx, cy, 5f, 12);
        shapes.end();
    }

    /**
     * Sorcerer Blink Glyph — violet runic portal: outer rune ring,
     * translucent void interior, 6 runic tick-runes orbiting the rim, and
     * a central vertical rift line. Procedural port of renderer.js case 20.
     */
    public static void renderBlinkGlyph(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float phase = Math.min(t * 2f, 1f);
        final float ringR = radius * (0.4f + phase * 0.7f);
        // Void interior
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.04f, 0.19f, alpha * 0.55f);
        drawCircle(shapes, cx, cy, ringR, 48);
        shapes.end();
        // Outer rune ring
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(0.78f, 0.50f, 1.00f, alpha);
        drawCircleOutline(shapes, cx, cy, ringR, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.56f, 0.25f, 1.00f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, ringR - 4f, 64);
        // Vertical rift line
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.78f, 0.50f, 1.00f, alpha * 0.95f);
        shapes.line(cx, cy - ringR * 0.9f, cx, cy + ringR * 0.9f);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.8f);
        shapes.line(cx, cy - ringR * 0.85f, cx, cy + ringR * 0.85f);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // 6 rune ticks orbiting
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 6; i++) {
            final float a = (i / 6f) * (float) Math.PI * 2f + now * 0.004f;
            final float rx = cx + (float) Math.cos(a) * ringR;
            final float ry = cy + (float) Math.sin(a) * ringR;
            shapes.setColor(0.78f, 0.50f, 1.00f, alpha);
            drawCircle(shapes, rx, ry, 4f, 12);
            shapes.setColor(1f, 1f, 1f, alpha * 0.8f);
            drawCircle(shapes, rx, ry, 1.6f, 8);
        }
        shapes.end();
    }

    /**
     * Necromancer Life Drain — 3 spiraling red ribbon streams pulling
     * INWARD from the rim to the caster, with bright pulsing center.
     * Procedural port of renderer.js case 23.
     */
    public static void renderLifeDrain(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        // Halo
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.75f, 0.00f, 0.13f, alpha * 0.20f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.31f, 0.00f, 0.06f, alpha * 0.95f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        // 3 inward-spiraling streams
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.00f, 0.13f, alpha);
        final int arms = 3, segs = 20;
        for (int arm = 0; arm < arms; arm++) {
            final float armOff = (arm / (float) arms) * (float) Math.PI * 2f;
            for (int s = 0; s < segs - 1; s++) {
                final float t0 = s / (float) segs, t1 = (s + 1) / (float) segs;
                final float rr0 = radius * (1f - t0) + 4f;
                final float rr1 = radius * (1f - t1) + 4f;
                final float a0 = armOff + t0 * 4f + now * 0.004f;
                final float a1 = armOff + t1 * 4f + now * 0.004f;
                shapes.line(cx + (float) Math.cos(a0) * rr0, cy + (float) Math.sin(a0) * rr0,
                            cx + (float) Math.cos(a1) * rr1, cy + (float) Math.sin(a1) * rr1);
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.75f, 0.00f, 0.13f, alpha);
        drawCircle(shapes, cx, cy, 8f, 16);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 4f, 12);
        shapes.end();
    }

    /**
     * Engineer Snare Gear — tightening iron gear ring with 12 rectangular
     * teeth around the rim. Procedural port of renderer.js case 34.
     */
    public static void renderSnareGear(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float gearR = radius * (1f - t * 0.30f);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.19f, 0.22f, 0.25f, alpha * 0.95f);
        drawCircleOutline(shapes, cx, cy, gearR, 48);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.50f, 0.53f, 0.56f, alpha);
        drawCircleOutline(shapes, cx, cy, gearR - 3f, 48);
        // 12 teeth
        final int teeth = 12;
        for (int i = 0; i < teeth; i++) {
            final float a = (i / (float) teeth) * (float) Math.PI * 2f + now * 0.001f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            Gdx.gl.glLineWidth(5f);
            shapes.setColor(0.50f, 0.53f, 0.56f, alpha);
            shapes.line(cx + ca * gearR, cy + sa * gearR,
                        cx + ca * (gearR + 8f), cy + sa * (gearR + 8f));
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, alpha);
            shapes.line(cx + ca * gearR, cy + sa * gearR,
                        cx + ca * (gearR + 8f), cy + sa * (gearR + 8f));
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
    }

    /**
     * Pyromancer Combustion Trap — orange explosion ring with hot inner
     * core, ember sparks, and central flash. Ring expands with progress.
     * Procedural port of renderer.js case 35.
     */
    public static void renderCombustionTrap(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float ringR = radius * (0.4f + 0.7f * t);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.38f, 0.13f, alpha * 0.45f);
        drawCircle(shapes, cx, cy, ringR, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(8f);
        shapes.setColor(0.50f, 0.25f, 0.13f, alpha * 0.9f);
        drawCircleOutline(shapes, cx, cy, ringR, 48);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(1.00f, 0.38f, 0.13f, alpha);
        drawCircleOutline(shapes, cx, cy, ringR - 4f, 48);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1.00f, 0.88f, 0.25f, alpha);
        drawCircleOutline(shapes, cx, cy, ringR - 9f, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Embers
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int embers = 12;
        for (int i = 0; i < embers; i++) {
            final float seed = i * 0.491f;
            final float a = seed * (float) Math.PI * 2f + now * 0.002f;
            final float d = ringR * ((seed * 13f) % 1f);
            shapes.setColor(1.00f, 0.88f, 0.25f, alpha);
            drawCircle(shapes, cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, 3f, 10);
        }
        // Central flash
        shapes.setColor(1.00f, 0.88f, 0.25f, alpha * 0.85f);
        drawCircle(shapes, cx, cy, ringR * 0.2f, 18);
        shapes.end();
    }

    /**
     * Warrior War Cry Wave — 4 concentric red wave-rings at staggered
     * progress offsets to evoke a roaring shockwave. Procedural port of
     * renderer.js case 36.
     */
    public static void renderWarCryWave(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < 4; i++) {
            final float phase = (t * 1.5f + i * 0.18f) % 1.0f;
            final float ringR = radius * (0.2f + phase * 1.0f);
            final float ringA = (1f - phase) * alpha;
            if (ringA <= 0.02f) continue;
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(0.50f, 0.00f, 0.13f, ringA * 0.85f);
            drawCircleOutline(shapes, cx, cy, ringR, 48);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1.00f, 0.19f, 0.31f, ringA);
            drawCircleOutline(shapes, cx, cy, ringR - 3f, 48);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.19f, 0.31f, alpha * 0.45f);
        drawCircle(shapes, cx, cy, radius * 0.18f, 16);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, radius * 0.08f, 12);
        shapes.end();
    }

    /**
     * Trapper Caltrops — 10 scattered tiny 4-point metal spikes inside the
     * radius, each with a steel center stud. Procedural port of renderer.js
     * case 37.
     */
    public static void renderCaltrops(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.25f, 0.28f, 0.31f, alpha * 0.18f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.69f, 0.72f, 0.75f, alpha * 0.5f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        final int caltrops = 10;
        for (int i = 0; i < caltrops; i++) {
            final float seed = i * 0.421f;
            final float a = seed * (float) Math.PI * 2f;
            final float d = radius * ((seed * 11f) % 0.85f);
            final float kx = cx + (float) Math.cos(a) * d;
            final float ky = cy + (float) Math.sin(a) * d;
            final float arm = 6f;
            Gdx.gl.glLineWidth(3f);
            shapes.setColor(0.25f, 0.28f, 0.31f, alpha);
            shapes.line(kx - arm, ky, kx + arm, ky);
            shapes.line(kx, ky - arm, kx, ky + arm);
            shapes.line(kx - arm * 0.7f, ky - arm * 0.7f, kx + arm * 0.7f, ky + arm * 0.7f);
            shapes.line(kx - arm * 0.7f, ky + arm * 0.7f, kx + arm * 0.7f, ky - arm * 0.7f);
            Gdx.gl.glLineWidth(1f);
            shapes.setColor(1f, 1f, 1f, alpha);
            shapes.line(kx - arm, ky, kx + arm, ky);
            shapes.line(kx, ky - arm, kx, ky + arm);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < caltrops; i++) {
            final float seed = i * 0.421f;
            final float a = seed * (float) Math.PI * 2f;
            final float d = radius * ((seed * 11f) % 0.85f);
            shapes.setColor(0.69f, 0.72f, 0.75f, alpha);
            drawCircle(shapes, cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, 2f, 8);
        }
        shapes.end();
    }

    /**
     * Wizard Arcane Aura — purple swirling self-aura with 8 orbiting
     * sparks at varying radii. Procedural port of renderer.js case 38.
     */
    public static void renderArcaneAura(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.56f, 0.25f, 1.00f, alpha * 0.20f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.50f, 1.00f, alpha * 0.9f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Orbiting sparks
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int sparks = 8;
        for (int i = 0; i < sparks; i++) {
            final float a = (i / (float) sparks) * (float) Math.PI * 2f + now * 0.005f;
            final float wob = (float) Math.sin(now * 0.01f + i) * 0.15f;
            final float orbR = radius * (0.85f + wob);
            final float ex = cx + (float) Math.cos(a) * orbR;
            final float ey = cy + (float) Math.sin(a) * orbR;
            shapes.setColor(0.75f, 0.50f, 1.00f, alpha);
            drawCircle(shapes, ex, ey, 4f, 12);
            shapes.setColor(1f, 1f, 1f, alpha);
            drawCircle(shapes, ex, ey, 1.8f, 8);
        }
        shapes.end();
    }

    /** Necromancer Wither / curse cast — dark-magic vortex: translucent void
     *  zone, expanding shockwave, two counter-rotating rune rings, particles
     *  spiralling inward, and a pulsing core. Mirrors renderer.js CURSE_RADIUS. */
    public static void renderCurseVortex(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float tt = now * 0.001f;
        final float wave = Math.min(1f, t * 3f);

        // Translucent void zone so the AoE reads on the floor.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.165f, 0.04f, 0.227f, alpha * 0.28f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();

        // Expanding shockwave + two counter-rotating dashed rune rings.
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.61f, 0.19f, 1.00f, alpha * 0.5f * (1f - wave * 0.5f));
        drawCircleOutline(shapes, cx, cy, radius * (0.55f + 0.5f * wave), 48);
        Gdx.gl.glLineWidth(2f);
        for (int ring = 0; ring < 2; ring++) {
            final float rr = radius * (ring == 0 ? 0.78f : 0.52f);
            final float dir = ring == 0 ? 1f : -1f;
            final int seg = 12;
            if (ring == 0) shapes.setColor(0.50f, 0.25f, 0.75f, alpha * 0.8f);
            else           shapes.setColor(0.75f, 0.38f, 1.00f, alpha * 0.8f);
            for (int i = 0; i < seg; i++) {
                final float a0 = (i / (float) seg) * (float) Math.PI * 2f + dir * tt * 1.6f;
                final float a1 = a0 + (float) Math.PI / seg;
                shapes.line(cx + (float) Math.cos(a0) * rr, cy + (float) Math.sin(a0) * rr,
                            cx + (float) Math.cos(a1) * rr, cy + (float) Math.sin(a1) * rr);
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();

        // Particles spiralling inward + pulsing void core.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int n = 18;
        for (int i = 0; i < n; i++) {
            final float spin = (i / (float) n) * (float) Math.PI * 2f - tt * 2.2f;
            final float inward = ((i / (float) n) + t * 1.3f) % 1f;
            final float pr = radius * (1f - inward) * 0.95f;
            shapes.setColor(0.69f, 0.38f, 1.00f, alpha * (0.25f + 0.5f * (1f - inward)));
            drawCircle(shapes, cx + (float) Math.cos(spin) * pr, cy + (float) Math.sin(spin) * pr,
                    1f + 2.5f * (1f - inward), 8);
        }
        final float pulse = 0.6f + 0.4f * (float) Math.sin(tt * 6f);
        final float coreR = radius * 0.1f + radius * 0.08f * pulse;
        shapes.setColor(0.06f, 0.0f, 0.10f, alpha * 0.6f);
        drawCircle(shapes, cx, cy, coreR, 16);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.82f, 0.50f, 1.00f, alpha * 0.9f * pulse);
        drawCircleOutline(shapes, cx, cy, coreR, 24);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
    }

    /**
     * Ninja Haste Wind — 5 vertical cyan streamers at the caster's feet
     * sliding upward as progress advances. Procedural port of renderer.js
     * case 39. (Native Y-up: streamers travel up the screen with phase.)
     */
    public static void renderHasteWind(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final int streamers = 5;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < streamers; i++) {
            final float seed = i * 0.523f;
            final float phase = (t + seed) % 1.0f;
            final float xOff = (seed * 2f - 1f) * radius * 0.6f;
            // Web: yStart = sy + r*0.4 - phase * r*1.2; yEnd = yStart + 18 (downward in web Y-down).
            // In native Y-up, mirror: streamer rises up the screen.
            final float yStart = cy - radius * 0.4f + phase * radius * 1.2f;
            final float yEnd   = yStart - 18f;
            final float a = (1f - phase) * alpha;
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(0.25f, 0.88f, 1.00f, a);
            shapes.line(cx + xOff, yStart, cx + xOff, yEnd);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, a);
            shapes.line(cx + xOff, yStart, cx + xOff, yEnd);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
    }

    /**
     * Standard-bearer Banner Raise — vertical red banner with gold pole
     * above the caster + ground stomp shockwave. Procedural port of
     * renderer.js case 40. (Native Y-up: banner extends upward = +y.)
     */
    public static void renderBannerRaise(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float h = radius * 1.6f * Math.min(t * 1.6f, 1f);
        // Pole (gold) — extends upward
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.82f, 0.38f, alpha);
        shapes.rect(cx - 2f, cy, 4f, radius * 1.5f);
        // Banner cloth — dark backing
        shapes.setColor(0.31f, 0.00f, 0.06f, alpha * 0.95f);
        shapes.rect(cx + 2f, cy + radius * 1.4f - h, 30f, h);
        // Banner cloth — red face
        shapes.setColor(0.75f, 0.06f, 0.19f, alpha);
        shapes.rect(cx + 4f, cy + radius * 1.4f - 2f - (h - 4f), 26f, h - 4f);
        shapes.end();
        // Banner emblem (X) — drawn at the top of banner
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha);
        final float ey0 = cy + radius * 1.3f;
        shapes.line(cx + 8f,  ey0, cx + 26f, ey0 - 14f);
        shapes.line(cx + 26f, ey0, cx + 8f,  ey0 - 14f);
        // Stomp shockwave at feet
        final float ringR = radius * (0.3f + t * 0.8f);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.06f, 0.19f, (1f - t) * alpha);
        drawCircleOutline(shapes, cx, cy - 8f, ringR, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
    }

    /**
     * Berserker Rampage Aura — dark-red ground halo with 10 outer flame
     * tongues drawn as triangles + hot gold inner highlight triangles.
     * Procedural port of renderer.js case 41.
     */
    public static void renderRampageAura(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.38f, 0.00f, 0.06f, alpha * 0.35f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.setColor(1.00f, 0.25f, 0.13f, alpha * 0.45f);
        drawCircle(shapes, cx, cy, radius * 0.85f, 48);
        final int tongues = 10;
        for (int i = 0; i < tongues; i++) {
            final float a = (i / (float) tongues) * (float) Math.PI * 2f + now * 0.0035f;
            final float wob = 0.85f + 0.15f * (float) Math.sin(now * 0.015f + i);
            final float baseX = cx + (float) Math.cos(a) * radius * 0.85f;
            final float baseY = cy + (float) Math.sin(a) * radius * 0.85f;
            final float tipX = cx + (float) Math.cos(a) * radius * 1.15f * wob;
            final float tipY = cy + (float) Math.sin(a) * radius * 1.15f * wob;
            final float perpX = -(float) Math.sin(a) * 6f;
            final float perpY =  (float) Math.cos(a) * 6f;
            shapes.setColor(1.00f, 0.25f, 0.13f, alpha * 0.95f);
            shapes.triangle(baseX + perpX, baseY + perpY,
                            tipX, tipY,
                            baseX - perpX, baseY - perpY);
            shapes.setColor(1.00f, 0.82f, 0.25f, alpha);
            shapes.triangle(baseX + perpX * 0.6f, baseY + perpY * 0.6f,
                            tipX, tipY,
                            baseX - perpX * 0.6f, baseY - perpY * 0.6f);
        }
        shapes.end();
    }

    /**
     * Storm Druid Storm Aura — 5 zigzag yellow bolts emanating outward
     * with a deep-blue ground halo + bright white center pip.
     * Procedural port of renderer.js case 42.
     */
    public static void renderStormAura(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.13f, 0.16f, 0.28f, alpha * 0.25f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1.00f, 0.94f, 0.38f, alpha * 0.75f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        // 5 bolts
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1.00f, 0.94f, 0.38f, alpha);
        final int bolts = 5;
        final int segs = 5;
        for (int i = 0; i < bolts; i++) {
            final float baseA = (i / (float) bolts) * (float) Math.PI * 2f + now * 0.003f + (float) Math.sin(now * 0.01f + i);
            float px = cx, py = cy;
            for (int s = 1; s <= segs; s++) {
                final float tt = s / (float) segs;
                final float wob = (float) Math.sin(now * 0.03f + s + i) * 8f;
                final float tA = baseA + wob * 0.02f;
                final float nx = cx + (float) Math.cos(tA) * radius * tt;
                final float ny = cy + (float) Math.sin(tA) * radius * tt;
                shapes.line(px, py, nx, ny);
                px = nx; py = ny;
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 5f, 14);
        shapes.end();
    }

    /**
     * Necromancer Death Pact Aura — 10 dark-red mist wisps spiraling at
     * varying radii with deep ground halo. Procedural port of renderer.js
     * case 43.
     */
    public static void renderDeathPactAura(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.19f, 0.00f, 0.06f, alpha * 0.40f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.63f, 0.00f, 0.13f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Spiral wisps
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int wisps = 10;
        for (int i = 0; i < wisps; i++) {
            final float seed = i * 0.671f;
            final float a = seed * (float) Math.PI * 2f + now * 0.004f;
            final float orbR = radius * (0.4f + ((seed * 7f) % 0.6f));
            final float wx = cx + (float) Math.cos(a) * orbR;
            final float wy = cy + (float) Math.sin(a) * orbR;
            shapes.setColor(0.38f, 0.13f, 0.19f, alpha * 0.85f);
            drawCircle(shapes, wx, wy, 5f, 12);
            shapes.setColor(0.63f, 0.00f, 0.13f, alpha);
            drawCircle(shapes, wx, wy, 2.5f, 10);
        }
        shapes.end();
    }

    /**
     * Berserker Blade Storm — 2 dual rotating blades through the player,
     * drawn as long line segments crossing the center. Procedural port of
     * renderer.js case 44.
     */
    public static void renderBladeStorm(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float rot = now * 0.025f;
        final float orbR = radius * 0.85f;
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < 2; i++) {
            final float a = rot + i * (float) Math.PI;
            final float x0 = cx + (float) Math.cos(a) * orbR;
            final float y0 = cy + (float) Math.sin(a) * orbR;
            final float x1 = cx + (float) Math.cos(a + (float) Math.PI) * orbR;
            final float y1 = cy + (float) Math.sin(a + (float) Math.PI) * orbR;
            Gdx.gl.glLineWidth(7f);
            shapes.setColor(0.13f, 0.13f, 0.16f, alpha * 0.85f);
            shapes.line(x0, y0, x1, y1);
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(0.88f, 0.88f, 0.94f, alpha);
            shapes.line(x0, y0, x1, y1);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1f, 1f, 1f, alpha);
            shapes.line(x0, y0, x1, y1);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, alpha);
        drawCircle(shapes, cx, cy, 5f, 12);
        shapes.end();
    }

    /**
     * Knight Taunt Roar — translucent red disc + bright outline ring +
     * small bright center dot. Tightens slightly as it fades.
     * Procedural port of renderer.js case 17.
     */
    public static void renderTauntRoar(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float r2 = radius * (1f - 0.25f * t);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.13f, 0.19f, alpha * 0.55f);
        drawCircle(shapes, cx, cy, r2, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(4f);
        shapes.setColor(1.00f, 0.31f, 0.38f, alpha);
        drawCircleOutline(shapes, cx, cy, r2, 48);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.7f);
        drawCircleOutline(shapes, cx, cy, r2 - 3f, 48);
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, alpha * 0.6f);
        drawCircle(shapes, cx, cy, r2 * 0.18f, 14);
        shapes.end();
    }

    /**
     * Knight Brace Stance — black core with bright accent rim, 8 spoke
     * decorations, and 4 cardinal bright dots. Expands outward with
     * progress. Procedural port of renderer.js case 18.
     */
    public static void renderBraceStance(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float ringR = radius * (0.35f + 0.95f * t);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.06f, 0.06f, 0.07f, alpha * 0.70f);
        drawCircle(shapes, cx, cy, ringR, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.78f, 0.78f, 0.82f, alpha);
        drawCircleOutline(shapes, cx, cy, ringR, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.8f);
        drawCircleOutline(shapes, cx, cy, ringR - 4f, 64);
        // 8 spoke decorations
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.78f, 0.78f, 0.82f, alpha * 0.95f);
        final int spokes = 8;
        for (int i = 0; i < spokes; i++) {
            final float a = (i / (float) spokes) * (float) Math.PI * 2f + t * (float) Math.PI * 0.5f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            shapes.line(cx + ca * (ringR - 8f), cy + sa * (ringR - 8f),
                        cx + ca * (ringR + 6f), cy + sa * (ringR + 6f));
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // 4 cardinal dots
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < 4; i++) {
            final float a = (i / 4f) * (float) Math.PI * 2f + t * (float) Math.PI * 0.5f;
            final float dx = (float) Math.cos(a) * ringR;
            final float dy = (float) Math.sin(a) * ringR;
            shapes.setColor(1f, 1f, 1f, alpha);
            drawCircle(shapes, cx + dx, cy + dy, 3f, 10);
        }
        shapes.end();
    }

    /**
     * Knight Phalanx Shield Dome — HARDCODED BLUE protective bubble: dense
     * translucent blue interior, heavy multi-layer rim, 4 rotating energy
     * ripples, edge sparks, plus a bright cast-moment flash on the first
     * 15% of life. Procedural port of renderer.js case 16.
     */
    public static void renderShieldDome(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        // 1. Translucent BLUE interior
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.06f, 0.50f, 1.00f, alpha * 0.42f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.setColor(0.63f, 0.86f, 1.00f, alpha * 0.22f);
        drawCircle(shapes, cx, cy, radius * 0.85f, 48);
        shapes.setColor(1f, 1f, 1f, alpha * 0.10f);
        drawCircle(shapes, cx, cy, radius * 0.55f, 48);
        shapes.end();
        // 2. Heavy multi-layer rim
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(10f);
        shapes.setColor(0.23f, 0.66f, 1.00f, alpha);
        drawCircleOutline(shapes, cx, cy, radius, 64);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(0.63f, 0.86f, 1.00f, alpha);
        drawCircleOutline(shapes, cx, cy, radius - 7f, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius - 12f, 64);
        // 3. Rotating energy ripples — 4 short white arcs
        final int ripples = 4;
        final float ripPhase = now * 0.003f;
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.85f);
        for (int i = 0; i < ripples; i++) {
            final float a0 = ripPhase + (i / (float) ripples) * (float) Math.PI * 2f;
            final float a1 = a0 + 0.32f;
            final int seg = 8;
            for (int s = 0; s < seg; s++) {
                final float aa = a0 + (a1 - a0) * (s / (float) seg);
                final float ab = a0 + (a1 - a0) * ((s + 1) / (float) seg);
                shapes.line(cx + (float) Math.cos(aa) * radius, cy + (float) Math.sin(aa) * radius,
                            cx + (float) Math.cos(ab) * radius, cy + (float) Math.sin(ab) * radius);
            }
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // 4. Edge sparks — fixed seeded positions, flicker independently
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int sparks = 10;
        for (int i = 0; i < sparks; i++) {
            final float seed = i * 0.371f;
            final float angle = seed * (float) Math.PI * 2f + now * 0.0008f;
            final float flicker = 0.5f + 0.5f * (float) Math.sin(now * 0.012f + seed * 11f);
            if (flicker < 0.55f) continue;
            final float ex = cx + (float) Math.cos(angle) * radius;
            final float ey = cy + (float) Math.sin(angle) * radius;
            shapes.setColor(0.23f, 0.66f, 1.00f, alpha * 0.85f * flicker);
            drawCircle(shapes, ex, ey, 5f, 12);
            shapes.setColor(1f, 1f, 1f, alpha * flicker);
            drawCircle(shapes, ex, ey, 2f, 8);
        }
        shapes.end();
        // 5. Cast-moment punch
        if (t < 0.15f) {
            final float flashA = 1.0f - t / 0.15f;
            shapes.begin(ShapeRenderer.ShapeType.Line);
            Gdx.gl.glLineWidth(8f);
            shapes.setColor(1f, 1f, 1f, flashA * 0.9f);
            drawCircleOutline(shapes, cx, cy, radius, 64);
            Gdx.gl.glLineWidth(1f);
            shapes.end();
        }
    }

    /**
     * Wizard Burst — arcane release: filled magic-circle floor, two
     * expanding wave-rings beyond the burst, two main runic rings, glyph
     * hexagram (rotating Star of David), 6 orbiting rune diamonds, radial
     * spokes that fade, sparkle convergence (first 30%), bright cast
     * flash. Procedural port of renderer.js case 10.
     */
    public static void renderWizardBurst(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float burstR = radius * (0.5f + t * 0.6f);
        // Floor
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.18f);
        drawCircle(shapes, cx, cy, burstR, 48);
        shapes.end();
        // Expanding wave rings
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int w = 0; w < 2; w++) {
            final float waveDelay = w * 0.20f;
            final float wt = Math.max(0f, (t - waveDelay)) / Math.max(0.001f, 1f - waveDelay);
            if (wt <= 0f || wt >= 1f) continue;
            final float wR = burstR * (1.0f + wt * 0.9f);
            final float wA = alpha * (1f - wt) * 0.75f;
            Gdx.gl.glLineWidth(2.5f);
            shapes.setColor(1.00f, 0.55f, 0.10f, wA);
            drawCircleOutline(shapes, cx, cy, wR, 64);
            Gdx.gl.glLineWidth(1.5f);
            shapes.setColor(1f, 1f, 1f, wA * 0.6f);
            drawCircleOutline(shapes, cx, cy, wR * 0.97f, 64);
        }
        // Two main runic rings
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, burstR, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, burstR * 0.78f, 64);
        // Glyph hexagram — two interlocking triangles
        final float glyphR = burstR * 0.55f;
        final float rot = now * 0.003f;
        shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.75f);
        Gdx.gl.glLineWidth(2f);
        // Upright triangle
        final float u0x = cx + (float) Math.cos(rot - (float) Math.PI / 2f) * glyphR;
        final float u0y = cy + (float) Math.sin(rot - (float) Math.PI / 2f) * glyphR;
        final float u1x = cx + (float) Math.cos(rot + (float) Math.PI / 6f) * glyphR;
        final float u1y = cy + (float) Math.sin(rot + (float) Math.PI / 6f) * glyphR;
        final float u2x = cx + (float) Math.cos(rot + 5f * (float) Math.PI / 6f) * glyphR;
        final float u2y = cy + (float) Math.sin(rot + 5f * (float) Math.PI / 6f) * glyphR;
        shapes.line(u0x, u0y, u1x, u1y);
        shapes.line(u1x, u1y, u2x, u2y);
        shapes.line(u2x, u2y, u0x, u0y);
        // Inverted triangle
        final float i0x = cx + (float) Math.cos(rot + (float) Math.PI / 2f) * glyphR;
        final float i0y = cy + (float) Math.sin(rot + (float) Math.PI / 2f) * glyphR;
        final float i1x = cx + (float) Math.cos(rot - (float) Math.PI / 6f) * glyphR;
        final float i1y = cy + (float) Math.sin(rot - (float) Math.PI / 6f) * glyphR;
        final float i2x = cx + (float) Math.cos(rot + 7f * (float) Math.PI / 6f) * glyphR;
        final float i2y = cy + (float) Math.sin(rot + 7f * (float) Math.PI / 6f) * glyphR;
        shapes.line(i0x, i0y, i1x, i1y);
        shapes.line(i1x, i1y, i2x, i2y);
        shapes.line(i2x, i2y, i0x, i0y);
        // Radial spokes that fade
        final float spokeA = alpha * (1.0f - t * 0.7f);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, spokeA);
        for (int i = 0; i < 8; i++) {
            final float a = (i / 8f) * (float) Math.PI * 2f;
            final float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            shapes.line(cx + ca * burstR * 0.2f, cy + sa * burstR * 0.2f,
                        cx + ca * burstR * 0.95f, cy + sa * burstR * 0.95f);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // Six rune-points orbiting (filled diamonds)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int runes = 6;
        for (int i = 0; i < runes; i++) {
            final float a = (i / (float) runes) * (float) Math.PI * 2f + now * 0.006f;
            final float px = cx + (float) Math.cos(a) * burstR;
            final float py = cy + (float) Math.sin(a) * burstR;
            shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.55f);
            drawCircle(shapes, px, py, 8f, 14);
            shapes.setColor(1f, 1f, 1f, alpha * 0.95f);
            // Diamond as two triangles
            shapes.triangle(px, py - 5f, px + 4f, py, px, py + 5f);
            shapes.triangle(px, py - 5f, px, py + 5f, px - 4f, py);
        }
        // Sparkle convergence (first 30%)
        if (t < 0.30f) {
            final float gt = t / 0.30f;
            final float eased = 1f - (float) Math.pow(1f - gt, 2);
            for (int i = 0; i < 8; i++) {
                final float a = (i / 8f) * (float) Math.PI * 2f;
                final float dist = burstR * 1.4f * (1f - eased);
                final float px = cx + (float) Math.cos(a) * dist;
                final float py = cy + (float) Math.sin(a) * dist;
                shapes.setColor(1f, 1f, 1f, alpha * 0.9f);
                drawCircle(shapes, px, py, 2f + (1f - eased) * 2f, 10);
                shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.55f);
                drawCircle(shapes, px, py, 5f + (1f - eased) * 3f, 12);
            }
        }
        // Initial flash
        if (t < 0.25f) {
            final float flashA = 1.0f - t / 0.25f;
            shapes.setColor(1f, 1f, 1f, flashA * 0.85f);
            drawCircle(shapes, cx, cy, burstR * 0.5f, 32);
            shapes.setColor(1.00f, 0.55f, 0.10f, flashA * 0.65f);
            drawCircle(shapes, cx, cy, burstR * 0.75f, 32);
        }
        // Pulsing core
        shapes.setColor(1.00f, 0.55f, 0.10f, alpha * 0.75f);
        drawCircle(shapes, cx, cy, burstR * 0.18f, 14);
        shapes.end();
    }

    /**
     * Paladin Seal — vertical pillar of light + radiant gold cross at the
     * caster + rotating halo with 12 sun-rays + ascending divine motes +
     * cast-moment consecration flash. Procedural port of renderer.js
     * case 14. (Native Y-up: pillar extends +y above caster.)
     */
    public static void renderPaladinSeal(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        final float baseR = radius * (0.55f + 0.45f * t);
        final float pillarH = baseR * 2.4f;
        final float pillarW = baseR * 0.55f;
        // Pillar (Y-up: pillar rises upward = positive Y above caster)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1.00f, 0.85f, 0.35f, alpha * 0.18f);
        shapes.rect(cx - pillarW, cy, pillarW * 2f, pillarH);
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.30f);
        shapes.rect(cx - pillarW * 0.55f, cy, pillarW * 1.1f, pillarH * 0.95f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.45f);
        shapes.rect(cx - pillarW * 0.20f, cy, pillarW * 0.4f, pillarH * 0.92f);
        // Halo behind the cross (above caster)
        final float haloR = baseR * 0.78f;
        final float crossCy = cy + baseR * 0.15f;
        shapes.setColor(1.00f, 0.85f, 0.35f, alpha * 0.22f);
        drawCircle(shapes, cx, crossCy, haloR, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(1.00f, 0.88f, 0.44f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, crossCy, haloR, 48);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.6f);
        drawCircleOutline(shapes, cx, crossCy, haloR * 0.92f, 48);
        // 12 sun-rays
        final int spokes = 12;
        final float spokePulse = 0.8f + 0.2f * (float) Math.sin(now * 0.012f);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.7f * spokePulse);
        for (int i = 0; i < spokes; i++) {
            final float a = (i / (float) spokes) * (float) Math.PI * 2f + now * 0.0015f;
            final float inner = haloR * 0.95f;
            final float outer = haloR * (1.15f + 0.08f * (float) Math.sin(now * 0.008f + i));
            shapes.line(cx + (float) Math.cos(a) * inner, crossCy + (float) Math.sin(a) * inner,
                        cx + (float) Math.cos(a) * outer, crossCy + (float) Math.sin(a) * outer);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // The cross (vertical + horizontal beams as rects)
        final float vH = haloR * 1.55f;
        final float vW = haloR * 0.18f;
        final float hH = haloR * 0.18f;
        final float hW = haloR * 1.05f;
        final float hOff = vH * 0.12f;  // horizontal sits slightly above center (Y-up = +)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Outer glow
        shapes.setColor(1.00f, 0.85f, 0.35f, alpha * 0.55f);
        shapes.rect(cx - vW * 1.5f, crossCy - vH * 0.55f, vW * 3f, vH * 1.1f);
        shapes.rect(cx - hW, crossCy + hOff - hH * 1.5f, hW * 2f, hH * 3f);
        // Warm gold
        shapes.setColor(1.00f, 0.94f, 0.63f, alpha * 0.85f);
        shapes.rect(cx - vW, crossCy - vH * 0.5f, vW * 2f, vH);
        shapes.rect(cx - hW * 0.95f, crossCy + hOff - hH, hW * 1.9f, hH * 2f);
        // White core
        shapes.setColor(1f, 1f, 1f, Math.min(1f, alpha));
        shapes.rect(cx - vW * 0.45f, crossCy - vH * 0.5f, vW * 0.9f, vH);
        shapes.rect(cx - hW * 0.92f, crossCy + hOff - hH * 0.45f, hW * 1.84f, hH * 0.9f);
        // Cross-arm endpoint flares
        final float flareR = 4f + 2f * spokePulse;
        shapes.setColor(1f, 1f, 1f, alpha * 0.9f);
        drawCircle(shapes, cx, crossCy - vH * 0.5f, flareR, 12);
        drawCircle(shapes, cx, crossCy + vH * 0.5f, flareR, 12);
        drawCircle(shapes, cx - hW * 0.95f, crossCy + hOff, flareR, 12);
        drawCircle(shapes, cx + hW * 0.95f, crossCy + hOff, flareR, 12);
        shapes.setColor(1.00f, 0.85f, 0.35f, alpha * 0.5f);
        drawCircle(shapes, cx, crossCy - vH * 0.5f, flareR * 1.8f, 14);
        drawCircle(shapes, cx, crossCy + vH * 0.5f, flareR * 1.8f, 14);
        drawCircle(shapes, cx - hW * 0.95f, crossCy + hOff, flareR * 1.8f, 14);
        drawCircle(shapes, cx + hW * 0.95f, crossCy + hOff, flareR * 1.8f, 14);
        // Ascending motes (web: rises from ground upward; Y-up: same direction)
        final int motes = 14;
        for (int i = 0; i < motes; i++) {
            final float seed = i * 0.61f;
            final float phase = (t + seed) % 1.0f;
            final float moteA = (float) Math.sin(phase * (float) Math.PI) * alpha;
            if (moteA <= 0.05f) continue;
            final float dx = (float) Math.sin(seed * 7f + now * 0.001f) * baseR * 0.5f;
            // Y-up: motes rise upward as phase increases
            final float my = cy - baseR * 0.6f + phase * pillarH * 1.05f;
            final float mx = cx + dx;
            shapes.setColor(1.00f, 0.94f, 0.63f, moteA * 0.5f);
            drawCircle(shapes, mx, my, 5f, 12);
            shapes.setColor(1f, 1f, 1f, Math.min(1f, moteA));
            drawCircle(shapes, mx, my, 2.5f, 10);
        }
        // Initial consecration flash
        if (t < 0.18f) {
            final float flashA = 1.0f - t / 0.18f;
            shapes.setColor(1f, 1f, 1f, flashA * 0.95f);
            drawCircle(shapes, cx, cy, baseR * 0.55f, 32);
            shapes.setColor(1.00f, 0.94f, 0.63f, flashA * 0.7f);
            drawCircle(shapes, cx, cy, baseR * 0.85f, 32);
        }
        shapes.end();
    }

    /**
     * Warrior Buff — gritty battle rally: smoke haze + jagged 16-segment
     * shockwave ring + crossed war-blades raised high + 8 outward chevrons
     * + 18 ember motes + cast-moment roar flash + pulsing core.
     * Procedural port of renderer.js case 12.
     */
    public static void renderWarriorBuff(ShapeRenderer shapes, float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final float earlyA = t < 0.35f ? alpha : alpha * (1.0f - (t - 0.35f) / 0.65f);
        final long now = System.currentTimeMillis();
        final float buffR = radius * (0.5f + t * 0.55f);
        // 1. Smoke haze
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.33f, 0.20f, 0.13f, alpha * 0.22f);
        drawCircle(shapes, cx, cy, buffR * 1.1f, 48);
        shapes.end();
        // 2. Jagged 16-segment shockwave ring
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(1.00f, 0.65f, 0.20f, alpha * 0.85f);
        final int jagSegs = 16;
        for (int i = 0; i < jagSegs; i++) {
            final float a0 = (i / (float) jagSegs) * (float) Math.PI * 2f;
            final float a1 = ((i + 1) / (float) jagSegs) * (float) Math.PI * 2f;
            final float r0 = buffR * (0.92f + 0.08f * (float) Math.sin(i * 5.7f + now * 0.005f));
            final float r1 = buffR * (0.92f + 0.08f * (float) Math.sin((i + 1) * 5.7f + now * 0.005f));
            shapes.line(cx + (float) Math.cos(a0) * r0, cy + (float) Math.sin(a0) * r0,
                        cx + (float) Math.cos(a1) * r1, cy + (float) Math.sin(a1) * r1);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // 3. Crossed war-blades — two diagonal stretched diamonds
        final float bladeAngle1 = -(float) Math.PI / 4f + (float) Math.sin(now * 0.004f) * 0.06f;
        final float bladeAngle2 = -(float) Math.PI * 3f / 4f - (float) Math.sin(now * 0.004f) * 0.06f;
        final float bladeLen = buffR * 0.55f;
        final float bladeWid = 6f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int blade = 0; blade < 2; blade++) {
            final float ang = blade == 0 ? bladeAngle1 : bladeAngle2;
            final float cs = (float) Math.cos(ang), sn = (float) Math.sin(ang);
            // Outer warm glow (orange-red)
            shapes.setColor(1.00f, 0.50f, 0.19f, alpha * 0.55f);
            final float bw = bladeWid + 3f;
            // Diamond split into two triangles: (tip, side1, tail) + (tip, tail, side2)
            shapes.triangle(cx + bladeLen * 1.1f * cs, cy + bladeLen * 1.1f * sn,
                            cx - bw * sn,              cy + bw * cs,
                            cx - bladeLen * 0.55f * cs, cy - bladeLen * 0.55f * sn);
            shapes.triangle(cx + bladeLen * 1.1f * cs, cy + bladeLen * 1.1f * sn,
                            cx - bladeLen * 0.55f * cs, cy - bladeLen * 0.55f * sn,
                            cx + bw * sn,              cy - bw * cs);
            // Steel-bright core
            shapes.setColor(1f, 1f, 1f, alpha * 0.95f);
            shapes.triangle(cx + bladeLen * cs,        cy + bladeLen * sn,
                            cx - bladeWid * sn,        cy + bladeWid * cs,
                            cx - bladeLen * 0.5f * cs, cy - bladeLen * 0.5f * sn);
            shapes.triangle(cx + bladeLen * cs,        cy + bladeLen * sn,
                            cx - bladeLen * 0.5f * cs, cy - bladeLen * 0.5f * sn,
                            cx + bladeWid * sn,        cy - bladeWid * cs);
        }
        shapes.end();
        // 4. Outward war-cry chevrons
        shapes.begin(ShapeRenderer.ShapeType.Line);
        final int chevs = 8;
        for (int i = 0; i < chevs; i++) {
            final float a = (i / (float) chevs) * (float) Math.PI * 2f + now * 0.005f;
            final float kx = cx + (float) Math.cos(a) * buffR * 0.78f;
            final float ky = cy + (float) Math.sin(a) * buffR * 0.78f;
            final float ox = (float) Math.cos(a), oy = (float) Math.sin(a);
            final float tx = -oy, ty = ox;
            Gdx.gl.glLineWidth(4f);
            shapes.setColor(1.00f, 0.65f, 0.20f, alpha * 0.85f);
            shapes.line(kx - tx * 8f - ox * 4f, ky - ty * 8f - oy * 4f, kx + ox * 9f, ky + oy * 9f);
            shapes.line(kx + ox * 9f, ky + oy * 9f, kx + tx * 8f - ox * 4f, ky + ty * 8f - oy * 4f);
            Gdx.gl.glLineWidth(2f);
            shapes.setColor(1.00f, 0.88f, 0.75f, alpha * 0.95f);
            shapes.line(kx - tx * 8f - ox * 4f, ky - ty * 8f - oy * 4f, kx + ox * 9f, ky + oy * 9f);
            shapes.line(kx + ox * 9f, ky + oy * 9f, kx + tx * 8f - ox * 4f, ky + ty * 8f - oy * 4f);
        }
        Gdx.gl.glLineWidth(1f);
        shapes.end();
        // 5. Ember motes (18 little square dust particles)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int embers = 18;
        for (int i = 0; i < embers; i++) {
            final float seed = i * 0.91f;
            final float a = (seed * 6.28f) + now * 0.004f;
            final float dist = buffR * (0.85f + 0.20f * (float) Math.sin(now * 0.008f + seed));
            final float ex = cx + (float) Math.cos(a) * dist;
            final float ey = cy + (float) Math.sin(a) * dist;
            final float sz = (i & 1) != 0 ? 3f : 2f;
            shapes.setColor(1.00f, 0.38f, 0.13f, alpha * 0.85f);
            shapes.rect(ex - sz, ey - sz, sz * 2f, sz * 2f);
            shapes.setColor(0.53f, 0.27f, 0.00f, alpha * 0.55f);
            shapes.rect(ex - sz - 1f, ey - sz - 1f, sz * 2f + 2f, sz * 2f + 2f);
        }
        // 6. Initial roar flash
        if (t < 0.18f) {
            final float flashA = 1.0f - t / 0.18f;
            shapes.setColor(1.00f, 0.88f, 0.75f, flashA * 0.95f);
            drawCircle(shapes, cx, cy, buffR * 0.28f, 28);
            shapes.setColor(1.00f, 0.50f, 0.19f, flashA * 0.7f);
            drawCircle(shapes, cx, cy, buffR * 0.5f, 32);
        }
        // 7. Throbbing core
        final float corePulse = 0.6f + 0.4f * (float) Math.sin(now * 0.022f);
        shapes.setColor(1.00f, 0.65f, 0.20f, earlyA * 0.65f * corePulse);
        drawCircle(shapes, cx, cy, buffR * 0.22f, 24);
        shapes.end();
    }

    /**
     * Necromancer Soul Harvest visual — persistent crimson/violet vortex
     * with three inward-spiraling arms + drifting motes + bright core.
     * Driven by wall-clock so consecutive refresh packets stay phase-
     * continuous (no resetting on each server pulse).
     */
    public static void renderSoulVortex(ShapeRenderer shapes, ActiveVisualEffect vfx,
                                   float cx, float cy, float radius, float t) {
        if (radius <= 0) return;
        final float alpha = t < 0.85f ? 1.0f : 1.0f - (t - 0.85f) * 6.67f;
        final long now = System.currentTimeMillis();
        // Ground halo + outer boundary ring
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.13f, 0.03f, 0.10f, alpha * 0.55f);
        drawCircle(shapes, cx, cy, radius, 48);
        shapes.setColor(0.50f, 0.19f, 0.75f, alpha * 0.25f);
        drawCircle(shapes, cx, cy, radius * 0.92f, 48);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(3f);
        shapes.setColor(0.75f, 0.06f, 0.25f, alpha * 0.9f);
        drawCircleOutline(shapes, cx, cy, radius, 64);
        Gdx.gl.glLineWidth(2f);
        shapes.setColor(0.50f, 0.19f, 0.75f, alpha * 0.85f);
        drawCircleOutline(shapes, cx, cy, radius * 0.78f, 64);
        // Three inward spiraling arms — chained line segments rotated by
        // wall-clock so the whole vortex churns.
        final int arms = 3;
        final int segs = 24;
        final float rotSpeed = 0.006f;
        for (int arm = 0; arm < arms; arm++) {
            final float armOff = (arm / (float) arms) * (float) Math.PI * 2f;
            shapes.setColor(1.0f, 0.5f, 1.0f, alpha * 0.95f);
            float prevX = cx, prevY = cy;
            for (int s = 0; s <= segs; s++) {
                final float tt = s / (float) segs;
                final float rr = radius * (1f - tt * 0.95f) + 2f;
                final float a = armOff + tt * (float) Math.PI * 2.8f + now * rotSpeed;
                final float px = cx + (float) Math.cos(a) * rr;
                final float py = cy + (float) Math.sin(a) * rr;
                if (s > 0) shapes.line(prevX, prevY, px, py);
                prevX = px; prevY = py;
            }
        }
        shapes.end();
        // Drifting soul motes — orbiting wisps
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int motes = 14;
        for (int i = 0; i < motes; i++) {
            final float seed = i * 0.421f + 0.137f;
            final float phase = ((now * 0.0012f + seed) % 1.0f);
            final float moteA = (float) Math.sin(phase * Math.PI) * alpha;
            if (moteA <= 0.05f) continue;
            final float orbA = seed * (float) Math.PI * 2f + now * 0.004f + phase * 2f;
            final float orbR = radius * (0.25f + 0.65f * phase);
            final float mx = cx + (float) Math.cos(orbA) * orbR;
            final float my = cy + (float) Math.sin(orbA) * orbR;
            shapes.setColor(1.0f, 0.5f, 1.0f, moteA * 0.9f);
            shapes.rect(mx - 2f, my - 2f, 4f, 4f);
        }
        // Bright core — the sink everything spirals into
        shapes.setColor(0.75f, 0.06f, 0.25f, alpha * 0.8f);
        drawCircle(shapes, cx, cy, 9f, 18);
        shapes.setColor(1.0f, 0.5f, 1.0f, alpha);
        drawCircle(shapes, cx, cy, 4f, 16);
        shapes.end();
        Gdx.gl.glLineWidth(1f);
    }

    /**
     * Spawn-protection purify circle. White/gold themed: a soft golden fill, a
     * bright cleansing core flash on cast, concentric gold + white rings, an outer
     * shockwave that races ahead and fades, and gold sparkle motes orbiting the rim.
     * {@code t} is normalized effect progress [0..1].
     */
    public static void renderPurifyCircle(ShapeRenderer shapes, float cx, float cy, float maxRadius, float t) {
        if (maxRadius <= 0) return;
        // Expand quickly to full, then hold; fade over the final third.
        final float radius = maxRadius * Math.min(t * 2.2f, 1f);
        final float alpha = t < 0.65f ? 1f : Math.max(0f, 1f - (t - 0.65f) * 2.86f);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Soft golden fill.
        shapes.setColor(1.0f, 0.92f, 0.55f, alpha * 0.18f);
        drawCircle(shapes, cx, cy, radius, 56);
        // Cleansing white core — punchiest on cast, then eased.
        final float coreAlpha = alpha * (t < 0.3f ? (0.5f + (0.3f - t) * 1.5f) : 0.35f);
        shapes.setColor(1.0f, 1.0f, 0.9f, Math.max(0f, coreAlpha) * 0.5f);
        drawCircle(shapes, cx, cy, radius * 0.45f, 40);
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glLineWidth(5f);
        shapes.setColor(1.0f, 0.85f, 0.35f, alpha);          // gold rim
        drawCircleOutline(shapes, cx, cy, radius, 64);
        drawCircleOutline(shapes, cx, cy, radius * 0.97f, 64);
        Gdx.gl.glLineWidth(2.5f);
        shapes.setColor(1.0f, 1.0f, 0.85f, alpha * 0.9f);    // inner white-gold
        drawCircleOutline(shapes, cx, cy, radius * 0.88f, 64);
        // Outer shockwave ring that races ahead and fades.
        final float shock = maxRadius * Math.min(t * 1.6f, 1.15f);
        shapes.setColor(1.0f, 0.95f, 0.6f, alpha * 0.5f * (1f - Math.min(t * 1.4f, 1f)));
        drawCircleOutline(shapes, cx, cy, shock, 64);
        shapes.end();

        // Gold sparkle motes orbiting the rim.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        final int motes = 20;
        final float spin = t * 3.2f;
        for (int i = 0; i < motes; i++) {
            final float ang = (float) (i * Math.PI * 2 / motes) + spin;
            final float mr = radius * (0.98f + 0.05f * (float) Math.sin(t * 10f + i));
            final float mx = cx + (float) Math.cos(ang) * mr;
            final float my = cy + (float) Math.sin(ang) * mr;
            final float twinkle = 0.6f + 0.4f * (float) Math.sin(t * 14f + i * 1.7f);
            shapes.setColor(1.0f, 0.9f, 0.5f, alpha * twinkle);
            final float s = 2.4f;
            shapes.rect(mx - s * 0.5f, my - s * 0.5f, s, s);
        }
        shapes.end();
    }

    /**
     * Procedural water fountain — ring of streams continuously launching
     * droplets up and out from (cx, cy), each following the same parabolic
     * arc the assassin's poison throw uses, landing inside `radius` and
     * splashing on impact. Uses the effect's own elapsed-ms clock so the
     * animation stays smooth across heal-tick packet boundaries.
     */
    public static void renderWaterFountain(ShapeRenderer shapes, ActiveVisualEffect vfx,
                                     float cx, float cy, float radius) {
        if (radius <= 0) return;

        // Continuous timeline (seconds). Looping the fountain off elapsed —
        // not the normalized t — keeps adjacent packets phase-continuous so
        // overlapping heal-tick packets read as one stream rather than
        // resetting on each tick.
        final float elapsedSec = vfx.getElapsed() / 1000f;
        final float dropPeriod = 0.85f;     // seconds per droplet (launch -> land)
        final int streams = 14;             // number of staggered launchers around the ring

        // Overall fade so the visual eases out at the end of the packet's
        // lifetime instead of popping. Because consecutive heal ticks send
        // overlapping packets, the visible stream stays continuous.
        final float t = vfx.getProgress();
        final float globalAlpha = t < 0.85f ? 1.0f : Math.max(0f, 1.0f - (t - 0.85f) * 6.7f);

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Soft pool reflection at the base (gives the fountain a "wet" anchor
        // even if the mapper hasn't placed water tiles yet).
        shapes.setColor(0.20f, 0.45f, 0.75f, 0.18f * globalAlpha);
        drawCircle(shapes, cx, cy, radius, 32);
        shapes.setColor(0.35f, 0.65f, 0.95f, 0.10f * globalAlpha);
        drawCircle(shapes, cx, cy, radius * 0.82f, 28);

        for (int s = 0; s < streams; s++) {
            // Per-stream deterministic randomness so each launcher has its
            // own angle / landing distance / phase but the look stays stable.
            float r1 = pseudoRand(s * 73 + 11);
            float r2 = pseudoRand(s * 131 + 29);
            float r3 = pseudoRand(s * 197 + 53);

            // Each stream slowly orbits so the fountain doesn't read as
            // 14 fixed jets — gives a subtle organic motion.
            float baseAngle = (float) (s * Math.PI * 2 / streams)
                    + elapsedSec * 0.35f
                    + r1 * (float) Math.PI * 2;
            // Landing distance: 55–100% of radius so droplets fill the pool
            // without all bunching at the rim.
            float landDist = radius * (0.55f + 0.45f * r2);

            float landX = cx + (float) Math.cos(baseAngle) * landDist;
            float landY = cy + (float) Math.sin(baseAngle) * landDist;

            // Phase in [0,1) — stream s lags by s/streams of the period plus
            // its own random jitter so launches don't all line up.
            float phase = ((elapsedSec / dropPeriod) + (s + r3) / streams) % 1.0f;
            if (phase < 0) phase += 1.0f;

            if (phase < 0.78f) {
                // Droplet in flight: parabola from (cx,cy) to (landX,landY)
                // with peak height ≈ 60% of the ground distance, matching the
                // poison throw's lob feel.
                float f = phase / 0.78f;
                float arcHeight = landDist * 0.65f + radius * 0.10f;
                float dx = landX - cx;
                float dy = landY - cy;
                float px = cx + dx * f;
                float py = cy + dy * f - 4.0f * arcHeight * f * (1.0f - f);

                // Short trailing tail (3 segments behind the head)
                int tailSegs = 3;
                for (int k = 1; k <= tailSegs; k++) {
                    float fk = Math.max(0f, f - 0.06f * k);
                    float tx = cx + dx * fk;
                    float ty = cy + dy * fk - 4.0f * arcHeight * fk * (1.0f - fk);
                    float tailA = globalAlpha * 0.32f * (1.0f - (float) k / tailSegs);
                    shapes.setColor(0.55f, 0.78f, 1.0f, tailA);
                    shapes.rect(tx - 1.5f, ty - 1.5f, 3f, 3f);
                }

                // Droplet head — outer halo + bright core.
                shapes.setColor(0.40f, 0.70f, 1.0f, globalAlpha * 0.55f);
                drawCircle(shapes, px, py, 3.2f, 10);
                shapes.setColor(0.85f, 0.95f, 1.0f, globalAlpha * 0.95f);
                drawCircle(shapes, px, py, 1.6f, 8);
            } else {
                // Splash ripple at the landing point. Phase 0.78–1.0 covers
                // ~22% of the period (~190 ms), enough to read as an impact
                // without lingering past the next launch.
                float sf = (phase - 0.78f) / 0.22f;            // 0..1 splash progress
                float splashR = 2.0f + 7.5f * sf;
                float splashA = globalAlpha * (1.0f - sf) * 0.85f;
                // Soft outer halo (filled, low alpha) — staying in Filled
                // mode for the whole fountain pass keeps batches simple and
                // avoids per-droplet begin/end churn.
                shapes.setColor(0.40f, 0.70f, 1.0f, splashA * 0.40f);
                drawCircle(shapes, landX, landY, splashR, 14);
                // Bright center splat fading fast
                shapes.setColor(0.85f, 0.95f, 1.0f, splashA);
                drawCircle(shapes, landX, landY, Math.max(0.5f, 2.2f * (1.0f - sf)), 8);
                // Two small side flecks kicked outward by the impact
                float flAng = baseAngle + (r1 - 0.5f) * 1.2f;
                float flDist = splashR * 0.9f;
                float fx = landX + (float) Math.cos(flAng) * flDist;
                float fy = landY + (float) Math.sin(flAng) * flDist;
                shapes.setColor(0.70f, 0.88f, 1.0f, splashA * 0.7f);
                shapes.rect(fx - 1f, fy - 1f, 2f, 2f);
            }
        }

        // Bright core at the statue base — the "spout" the fountain emerges
        // from. Subtly pulses so the source itself looks alive.
        float pulse = 0.85f + 0.15f * (float) Math.sin(elapsedSec * Math.PI * 4);
        shapes.setColor(0.85f, 0.95f, 1.0f, globalAlpha * 0.55f * pulse);
        drawCircle(shapes, cx, cy, 4.0f * pulse, 12);
        shapes.setColor(1.0f, 1.0f, 1.0f, globalAlpha * 0.85f * pulse);
        drawCircle(shapes, cx, cy, 1.8f, 8);

        shapes.end();
    }

    /** Cheap deterministic [0,1) hash — no allocations, suitable per-frame. */
    public static float pseudoRand(int seed) {
        int x = seed;
        x = (x ^ 61) ^ (x >>> 16);
        x = x + (x << 3);
        x = x ^ (x >>> 4);
        x = x * 0x27d4eb2d;
        x = x ^ (x >>> 15);
        // Map to [0,1)
        return ((x & 0x7fffffff) % 1000003) / 1000003f;
    }

    /** Draw a filled circle using triangles (ShapeRenderer.Filled mode must be active) */
    public static void drawCircle(ShapeRenderer shapes, float cx, float cy, float radius, int segments) {
        for (int i = 0; i < segments; i++) {
            float a1 = (float) (i * Math.PI * 2 / segments);
            float a2 = (float) ((i + 1) * Math.PI * 2 / segments);
            shapes.triangle(cx, cy,
                    cx + (float) Math.cos(a1) * radius, cy + (float) Math.sin(a1) * radius,
                    cx + (float) Math.cos(a2) * radius, cy + (float) Math.sin(a2) * radius);
        }
    }

    /** Draw a circle outline (ShapeRenderer.Line mode must be active) */
    public static void drawCircleOutline(ShapeRenderer shapes, float cx, float cy, float radius, int segments) {
        for (int i = 0; i < segments; i++) {
            float a1 = (float) (i * Math.PI * 2 / segments);
            float a2 = (float) ((i + 1) * Math.PI * 2 / segments);
            shapes.line(
                    cx + (float) Math.cos(a1) * radius, cy + (float) Math.sin(a1) * radius,
                    cx + (float) Math.cos(a2) * radius, cy + (float) Math.sin(a2) * radius);
        }
    }
}
