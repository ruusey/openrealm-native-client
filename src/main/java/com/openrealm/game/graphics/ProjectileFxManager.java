package com.openrealm.game.graphics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Bullet;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.ProjectileFx;
import com.openrealm.game.model.ProjectileGroup;

/**
 * Data-driven projectile FX particles (trail / muzzle / impact), the native
 * counterpart of the webclient FX system. Pooled Structure-of-Arrays state
 * (zero per-particle allocation) rendered with one shared soft-dot texture
 * tinted per particle, so thousands of particles cost ~one texture + a tight
 * loop. Positions are world coords; render converts to screen with the same
 * {@link Vector2f#worldX}/{@code worldY} offset the bullets use.
 */
public class ProjectileFxManager {

    private static final int CAP = 4096;
    private int n = 0;                              // active particle count (packed to front)
    private final float[] px = new float[CAP];
    private final float[] py = new float[CAP];
    private final float[] vx = new float[CAP];      // world units / sec
    private final float[] vy = new float[CAP];
    private final float[] life = new float[CAP];    // seconds remaining
    private final float[] maxLife = new float[CAP];
    private final float[] s0 = new float[CAP];      // start size
    private final float[] s1 = new float[CAP];      // end size
    private final float[] cr = new float[CAP];      // tint r/g/b 0..1
    private final float[] cg = new float[CAP];
    private final float[] cb = new float[CAP];

    private Texture softTex;
    // Muzzle/impact spawn-despawn tracking. value = [x, y, projectileGroupId].
    private Map<Long, float[]> seen = new HashMap<>();
    private Map<Long, float[]> seenNext = new HashMap<>();

    /** Emit trails for visible bullets, fire muzzle/impact bursts by diffing
     *  live bullets vs last frame, then advance every particle. */
    public void emitAndUpdate(final List<Bullet> visible, final Map<Long, Bullet> all, final float dtSec) {
        if (GameDataManager.PROJECTILE_GROUPS == null) return;
        for (int i = 0; i < visible.size(); i++) {
            final Bullet b = visible.get(i);
            final ProjectileGroup pg = GameDataManager.PROJECTILE_GROUPS.get((int) b.getProjectileId());
            if (pg == null || pg.getFx() == null) continue;
            for (final ProjectileFx fx : pg.getFx()) {
                if (fx != null && "trail".equals(fx.getType())) emitTrail(b, fx, dtSec);
            }
        }
        processBursts(all);
        update(dtSec);
    }

    public void render(final SpriteBatch batch) {
        if (n == 0) return;
        final Texture t = tex();
        final float wx = Vector2f.worldX, wy = Vector2f.worldY;
        for (int i = 0; i < n; i++) {
            final float f = life[i] / maxLife[i];             // 1 -> 0
            final float sz = s0[i] + (s1[i] - s0[i]) * (1f - f);
            final float sx = px[i] - wx, sy = py[i] - wy;
            batch.setColor(cr[i], cg[i], cb[i], f * f);        // ease-out fade
            batch.draw(t, sx - sz / 2f, sy - sz / 2f, sz, sz);
        }
        batch.setColor(1f, 1f, 1f, 1f);
    }

    // ── internals ──────────────────────────────────────────────────────────

    private void spawn(float x, float y, float ivx, float ivy, float lifeSec,
            float startSize, float endSize, float r, float g, float b) {
        if (n >= CAP) return;
        final int i = n++;
        px[i] = x; py[i] = y; vx[i] = ivx; vy[i] = ivy;
        life[i] = lifeSec; maxLife[i] = lifeSec; s0[i] = startSize; s1[i] = endSize;
        cr[i] = r; cg[i] = g; cb[i] = b;
    }

    private void emitTrail(final Bullet b, final ProjectileFx fx, final float dtSec) {
        if (n >= CAP) return;
        final float rate = fx.getRate() != null ? fx.getRate() : 24f;
        float acc = b.getFxTrailAcc() + rate * dtSec;
        int count = (int) acc;
        b.setFxTrailAcc(acc - count);
        if (count > 4) count = 4;
        if (count <= 0) return;
        final int tint = parseHex(fx.getColor(), 0x111111);
        final float r = ((tint >> 16) & 0xFF) / 255f, g = ((tint >> 8) & 0xFF) / 255f, bl = (tint & 0xFF) / 255f;
        final float lifeSec = (fx.getLifeMs() != null ? fx.getLifeMs() : 500) / 1000f;
        final float base = fx.getSize() != null ? fx.getSize() : 6f;
        final float spread = fx.getSpread() != null ? fx.getSpread() : 0.2f;
        final float cx = b.getPos().x + b.getSize() / 2f, cy = b.getPos().y + b.getSize() / 2f;
        for (int k = 0; k < count && n < CAP; k++) {
            final float a = (float) (Math.random() * Math.PI * 2);
            final float disp = spread * 22f * (0.4f + (float) Math.random() * 0.8f);
            spawn(cx + ((float) Math.random() - 0.5f) * base, cy + ((float) Math.random() - 0.5f) * base,
                    (float) Math.cos(a) * disp, (float) Math.sin(a) * disp,
                    lifeSec * (0.75f + (float) Math.random() * 0.5f), base * 0.55f, base * 1.5f, r, g, bl);
        }
    }

    private void burst(final float cx, final float cy, final ProjectileFx fx) {
        final int count = fx.getCount() != null ? fx.getCount() : 8;
        final int tint = parseHex(fx.getColor(), 0xFFFFFF);
        final float r = ((tint >> 16) & 0xFF) / 255f, g = ((tint >> 8) & 0xFF) / 255f, bl = (tint & 0xFF) / 255f;
        final float lifeSec = (fx.getLifeMs() != null ? fx.getLifeMs() : 300) / 1000f;
        final float base = fx.getSize() != null ? fx.getSize() : 5f;
        final float speed = fx.getSpeed() != null ? fx.getSpeed() : 50f;   // world units / sec
        for (int k = 0; k < count && n < CAP; k++) {
            final float a = (float) (Math.random() * Math.PI * 2);
            final float sp = speed * (0.4f + (float) Math.random() * 0.7f);
            spawn(cx, cy, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp,
                    lifeSec * (0.7f + (float) Math.random() * 0.6f), base * 1.3f, base * 0.25f, r, g, bl);
        }
    }

    private void processBursts(final Map<Long, Bullet> all) {
        if (all == null) { swapSeen(); return; }
        seenNext.clear();
        for (final Bullet b : all.values()) {
            final ProjectileGroup pg = GameDataManager.PROJECTILE_GROUPS.get((int) b.getProjectileId());
            if (pg == null || pg.getFx() == null) continue;
            ProjectileFx muzzle = null, impact = null;
            for (final ProjectileFx f : pg.getFx()) {
                if (f == null) continue;
                if ("muzzle".equals(f.getType())) muzzle = f;
                else if ("impact".equals(f.getType())) impact = f;
            }
            if (muzzle == null && impact == null) continue;
            final float cx = b.getPos().x + b.getSize() / 2f, cy = b.getPos().y + b.getSize() / 2f;
            final long id = b.getId();
            if (muzzle != null && !seen.containsKey(id)) burst(cx, cy, muzzle);
            if (impact != null) seenNext.put(id, new float[] { cx, cy, b.getProjectileId() });
        }
        for (final Map.Entry<Long, float[]> e : seen.entrySet()) {
            if (seenNext.containsKey(e.getKey())) continue;
            final float[] rec = e.getValue();
            final ProjectileGroup pg = GameDataManager.PROJECTILE_GROUPS.get((int) rec[2]);
            if (pg == null || pg.getFx() == null) continue;
            for (final ProjectileFx f : pg.getFx()) {
                if (f != null && "impact".equals(f.getType())) burst(rec[0], rec[1], f);
            }
        }
        swapSeen();
    }

    private void swapSeen() {
        final Map<Long, float[]> tmp = seen; seen = seenNext; seenNext = tmp;
    }

    private void update(final float dtSec) {
        if (n == 0) return;
        final float damp = (float) Math.exp(-3f * dtSec);   // velocity decay/sec (computed once)
        int a = 0;
        for (int i = 0; i < n; i++) {
            final float remaining = life[i] - dtSec;
            if (remaining <= 0f) continue;                  // dead — dropped by not copying
            px[a] = px[i] + vx[i] * dtSec;
            py[a] = py[i] + vy[i] * dtSec;
            vx[a] = vx[i] * damp; vy[a] = vy[i] * damp;
            life[a] = remaining; maxLife[a] = maxLife[i];
            s0[a] = s0[i]; s1[a] = s1[i];
            cr[a] = cr[i]; cg[a] = cg[i]; cb[a] = cb[i];
            a++;
        }
        n = a;
    }

    private Texture tex() {
        if (this.softTex != null) return this.softTex;
        final int S = 32;
        final Pixmap pm = new Pixmap(S, S, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        final float c = (S - 1) / 2f;
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                final float dx = (x - c) / c, dy = (y - c) / c;
                float alpha = 1f - (float) Math.sqrt(dx * dx + dy * dy);
                if (alpha < 0f) alpha = 0f;
                alpha *= alpha;                              // soft falloff
                pm.drawPixel(x, y, Color.rgba8888(1f, 1f, 1f, alpha));
            }
        }
        this.softTex = new Texture(pm);
        this.softTex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pm.dispose();
        return this.softTex;
    }

    private static int parseHex(final String s, final int dflt) {
        if (s == null) return dflt;
        try {
            String h = s.trim();
            if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
            else if (h.startsWith("#")) h = h.substring(1);
            return (int) (Long.parseLong(h, 16) & 0xFFFFFF);
        } catch (final NumberFormatException e) {
            return dflt;
        }
    }
}
