package com.openrealm.game.graphics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.openrealm.game.contants.GlobalConstants;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.graphics.Sprite.EffectEnum;
import com.openrealm.game.model.SpriteModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.Gdx;

@Data
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class SpriteSheet {
    private Texture spriteSheetTexture;
    private int animationFrame = 0;
    // Wall-clock animation accumulator. Incremented in animate() by
    // dt * 60 so that animationFrames durations (originally tuned in
    // 60-FPS frame counts) advance at the same wall-clock rate
    // regardless of render fps. At 144 fps the per-frame increment
    // is ~0.417 instead of 1, so a "duration 6" frame still takes
    // ~100 ms instead of running 2.4× too fast.
    private float elapsedFrames = 0f;
    private TextureRegion[][] spriteSheetRegions;
    private List<Sprite> sprites;
    private List<Integer> animationFrames;
    private int spriteImageHeight;
    private int spriteImageWidth;

    // Current effect applied to all sprites in this sheet (used as a tag for shader selection)
    private EffectEnum currentEffect = EffectEnum.NORMAL;

    // Named animation sets for walk/idle/attack cycles
    private Map<String, List<Sprite>> animSets = new HashMap<>();
    private Map<String, List<Integer>> animSetDurations = new HashMap<>();
    private String currentAnimSetName;

    public SpriteSheet(Texture texture, int spriteWidth, int spriteHeight, int col, int row) {
        this.spriteImageWidth = spriteWidth;
        this.spriteImageHeight = spriteHeight;
        this.spriteSheetTexture = texture;
        this.sprites = new ArrayList<>();
        if (texture == null) {
            // Stay alive in a degraded state — the renderer skips frames that
            // come back null, so a missing sheet shows blank tiles instead of
            // crashing the whole client. Caller already logged the cause.
            this.spriteSheetRegions = new TextureRegion[0][0];
            return;
        }
        final int cols = texture.getWidth() / spriteImageWidth;
        final int rows = texture.getHeight() / spriteImageHeight;
        this.spriteSheetRegions = new TextureRegion[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                this.spriteSheetRegions[i][j] = new TextureRegion(texture,
                    j * spriteImageWidth, i * spriteImageHeight, spriteImageWidth, spriteImageHeight);
                this.spriteSheetRegions[i][j].flip(false, true);
            }
        }
        if (row < rows && col < cols) {
            this.sprites.add(new Sprite(this.spriteSheetRegions[row][col]));
        }
    }

    public SpriteSheet(String fileName, int spriteWidth, int spriteHeight, int col, int row) {
        this(GameSpriteManager.TEXTURE_CACHE == null ? null : GameSpriteManager.TEXTURE_CACHE.get(fileName),
                spriteWidth, spriteHeight, col, row);
        if (GameSpriteManager.TEXTURE_CACHE == null || GameSpriteManager.TEXTURE_CACHE.get(fileName) == null) {
            log.warn("[SPRITE] Missing texture '{}' — sheet will render blank", fileName);
        }
    }

    public SpriteSheet(String fileName, int spriteWidth, int spriteHeight) {
        this.spriteImageWidth = spriteWidth;
        this.spriteImageHeight = spriteHeight;
        this.sprites = new ArrayList<>();
        Texture texture = GameSpriteManager.TEXTURE_CACHE == null
                ? null
                : GameSpriteManager.TEXTURE_CACHE.get(fileName);
        this.spriteSheetTexture = texture;
        if (texture == null) {
            log.warn("[SPRITE] Missing texture '{}' — sheet will render blank", fileName);
            this.spriteSheetRegions = new TextureRegion[0][0];
            return;
        }
        final int cols = texture.getWidth() / spriteImageWidth;
        final int rows = texture.getHeight() / spriteImageHeight;
        this.spriteSheetRegions = new TextureRegion[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                this.spriteSheetRegions[i][j] = new TextureRegion(texture,
                    j * spriteImageWidth, i * spriteImageHeight, spriteImageWidth, spriteImageHeight);
                this.spriteSheetRegions[i][j].flip(false, true);
            }
        }
    }

    public SpriteSheet(Texture texture) {
        this(texture, GlobalConstants.BASE_SPRITE_SIZE, GlobalConstants.BASE_SPRITE_SIZE);
    }

    public SpriteSheet(Texture texture, int spriteWidth, int spriteHeight) {
        this.spriteImageWidth = spriteWidth;
        this.spriteImageHeight = spriteHeight;
        this.spriteSheetTexture = texture;
        this.sprites = new ArrayList<>();
        if (texture == null) {
            this.spriteSheetRegions = new TextureRegion[0][0];
            return;
        }
        final int cols = texture.getWidth() / spriteImageWidth;
        final int rows = texture.getHeight() / spriteImageHeight;
        this.spriteSheetRegions = new TextureRegion[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                this.spriteSheetRegions[i][j] = new TextureRegion(texture,
                    j * spriteImageWidth, i * spriteImageHeight, spriteImageWidth, spriteImageHeight);
                this.spriteSheetRegions[i][j].flip(false, true);
            }
        }
    }

    public SpriteSheet(Texture texture, int x, int y, boolean isPosition) {
        this(texture, GlobalConstants.BASE_SPRITE_SIZE, GlobalConstants.BASE_SPRITE_SIZE, x, y);
    }

    public SpriteSheet(Texture texture, SpriteModel model) {
        this(texture, model.getSpriteSize() == 0 ? GlobalConstants.BASE_SPRITE_SIZE : model.getSpriteSize(),
                model.getEffectiveSpriteHeight() == 0 ? GlobalConstants.BASE_SPRITE_SIZE : model.getEffectiveSpriteHeight(), model.getCol(),
                model.getRow());
    }

    public Sprite getSubSprite(int x, int y) {
        if (this.spriteSheetRegions == null
                || y >= this.spriteSheetRegions.length
                || x >= (this.spriteSheetRegions.length == 0 ? 0 : this.spriteSheetRegions[0].length)) {
            // Texture missing or out-of-range — return an empty Sprite so the
            // caller's null-check on getRegion() short-circuits cleanly.
            return new Sprite();
        }
        return new Sprite(this.spriteSheetRegions[y][x]);
    }

    /**
     * Pixel-precise sub-sprite for frames whose width/height differ from
     * the sheet's default cell. Bypasses the precomputed grid so an
     * attack frame can span N cells (or fractional cells) without
     * forcing the whole sheet to be re-tiled. Coordinates and dimensions
     * are in source-texture pixels. Returned region is Y-flipped to match
     * the rest of the sheet's regions.
     */
    public Sprite getSubSpritePx(int pxX, int pxY, int width, int height) {
        if (this.spriteSheetTexture == null || width <= 0 || height <= 0) {
            return new Sprite();
        }
        TextureRegion region = new TextureRegion(this.spriteSheetTexture, pxX, pxY, width, height);
        region.flip(false, true);
        return new Sprite(region);
    }

    public void resetAnimation() {
        if ((this.animationFrames != null) && (this.animationFrames.size() > 0)) {
            this.animationFrame = 0;
        }
    }

    public void animate() {
        if ((this.animationFrames != null) && (this.animationFrames.size() > 0)) {
            int currentAnimationFrames = this.animationFrames.get(this.animationFrame);
            if (this.elapsedFrames >= currentAnimationFrames) {
                this.elapsedFrames = 0f;
                if (this.animationFrame == (this.animationFrames.size() - 1)) {
                    this.animationFrame = 0;
                } else {
                    this.animationFrame = this.animationFrame + 1;
                }
            }
        }
        // Advance by wall-clock time scaled to a 60-FPS reference. dt here
        // is the LibGDX frame delta (capped at 1/30 to avoid huge jumps
        // after a paused window). At 60 FPS this contributes ~1.0 per frame
        // (matching legacy behavior); at 144 FPS it's ~0.417 per frame so
        // animations no longer play 2.4× too fast.
        float dt = Gdx.graphics != null
                ? Math.min(Gdx.graphics.getDeltaTime(), 1f / 30f)
                : 1f / 60f;
        this.elapsedFrames += dt * 60f;
    }

    public boolean hasEffect(final EffectEnum effect) {
        return this.currentEffect == effect;
    }

    public void setEffect(final EffectEnum effect) {
        this.currentEffect = effect;
        for (Sprite sprite : this.sprites) {
            sprite.setEffect(effect);
        }
    }

    public void resetEffects() {
        this.currentEffect = EffectEnum.NORMAL;
        for (Sprite sprite : this.sprites) {
            sprite.setEffect(EffectEnum.NORMAL);
        }
    }

    public void addAnimSet(String name, List<Sprite> frames, List<Integer> durations) {
        this.animSets.put(name, frames);
        this.animSetDurations.put(name, durations);
    }

    public void setAnimSet(String name) {
        if (name == null || name.equals(this.currentAnimSetName)) return;
        List<Sprite> frames = this.animSets.get(name);
        List<Integer> durations = this.animSetDurations.get(name);
        if (frames == null || durations == null) return;
        this.currentAnimSetName = name;
        this.sprites = frames;
        this.animationFrames = durations;
        this.animationFrame = 0;
        this.elapsedFrames = 0f;
        for (Sprite sprite : this.sprites) {
            sprite.setEffect(this.currentEffect);
        }
    }

    public boolean hasAnimSets() {
        return !this.animSets.isEmpty();
    }

    /**
     * Recalculate animation frame durations based on player stats.
     * Walk/idle animations scale with speed, attack animations scale with dexterity.
     * Higher stat = faster animation (lower frame duration).
     * @param speed player speed stat (typically 10-75)
     * @param dexterity player dexterity stat (typically 10-75)
     */
    public void updateDurationsFromStats(short speed, short dexterity) {
        for (Map.Entry<String, List<Integer>> entry : this.animSetDurations.entrySet()) {
            String name = entry.getKey();
            List<Integer> durations = entry.getValue();
            boolean isAttack = name.startsWith("attack_");
            boolean isIdle = name.startsWith("idle_");
            if (isIdle) continue; // idle durations stay at 999

            // Base duration at stat=0 is 12 frames, at stat=75 is 3 frames
            // Linear interpolation: duration = max(3, 12 - stat * 0.12)
            float stat = isAttack ? dexterity : speed;
            int dur = Math.max(3, Math.round(12.0f - stat * 0.12f));
            for (int i = 0; i < durations.size(); i++) {
                durations.set(i, dur);
            }
        }
    }

    public TextureRegion getCurrentFrame() {
        if (this.sprites == null || this.sprites.isEmpty()) return null;
        // Bounds-check the frame index — Entity.update now drives this
        // directly from animFrame % frameCount, but a setAnimSet() swap
        // can shrink the frame list mid-cycle, leaving the index past
        // the end for one render frame. Clamp instead of crashing.
        int idx = this.animationFrame;
        if (idx < 0 || idx >= this.sprites.size()) idx = 0;
        Sprite sprite = this.sprites.get(idx);
        if (sprite != null)
            return sprite.getRegion();
        return null;
    }

    /** Number of frames in the current animation set. Used by the web-
     *  parity walk cycle in Entity.update to mod animFrame correctly. */
    public int getFrameCount() {
        return this.sprites != null ? this.sprites.size() : 0;
    }

    public void loadImageArray(final int x, final int y) {
        Sprite newSprite = new Sprite(this.spriteSheetRegions[x][y]);
        this.sprites.add(newSprite);
    }

    public void loadImageArray() {
        final int cols = this.spriteSheetTexture.getWidth() / this.spriteImageWidth;
        final int rows = this.spriteSheetTexture.getHeight() / this.spriteImageHeight;

        for (int x = 0; x < rows; x++) {
            for (int y = 0; y < cols; y++) {
                final Sprite newSprite = new Sprite(this.spriteSheetRegions[x][y]);
                this.sprites.add(newSprite);
            }
        }
    }

    public TextureRegion cropImage(int x, int y, int width, int height) {
        TextureRegion region = new TextureRegion(this.spriteSheetTexture, x, y, width, height);
        region.flip(false, true);
        return region;
    }

    public TextureRegion getSubimage(int row, int col) {
        return this.spriteSheetRegions[row][col];
    }

    public static SpriteSheet fromSpriteModel(SpriteModel model) {
        Texture texture = GameSpriteManager.TEXTURE_CACHE.get(model.getSpriteKey());
        return new SpriteSheet(texture, model);
    }

    public static SpriteSheet x8SpriteSheet(final String fileName) {
        return new SpriteSheet(fileName, GlobalConstants.BASE_SPRITE_SIZE, GlobalConstants.BASE_SPRITE_SIZE);
    }

    public static SpriteSheet x16SpriteSheet(final String fileName) {
        return new SpriteSheet(fileName, GlobalConstants.MEDIUM_ART_SIZE, GlobalConstants.MEDIUM_ART_SIZE);
    }
}
