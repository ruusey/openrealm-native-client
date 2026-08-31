package com.openrealm.game;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.graphics.ShaderManager;
import com.openrealm.game.state.GameStateManager;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;

@Slf4j
public class OpenRealmGame implements ApplicationListener {
    public static int width = 1920;
    public static int height = 1080;
    /**
     * World-to-screen pixel scale. The web client renders at 2x on desktop
     * (renderer.js SCALE constant). Matched here by feeding the world camera
     * a half-size ortho viewport — one world pixel becomes two screen pixels.
     */
    public static final float WORLD_SCALE = 2f;

    private SpriteBatch batch;
    private ShapeRenderer shapes;
    /**
     * World camera — used for tiles and entities. Ortho viewport is sized
     * width/WORLD_SCALE × height/WORLD_SCALE so the rendered world appears
     * 2× zoomed on a 1920×1080 window (matching the web client's SCALE=2).
     */
    private OrthographicCamera camera;
    /**
     * UI / HUD camera — fixed at full window size so HUD layout uses
     * screen-space pixels regardless of the world zoom. Switched onto the
     * batch around HUD draws.
     */
    private OrthographicCamera uiCamera;
    private BitmapFont defaultFont;
    private GameStateManager gsm;
    private KeyHandler keyHandler;
    private MouseHandler mouseHandler;

    public OrthographicCamera getUiCamera() { return this.uiCamera; }
    public OrthographicCamera getWorldCamera() { return this.camera; }

    /**
     * Render the project's bundled oryx-simplex.ttf at 18 px through
     * FreeType so HUD / login text uses the chunky pixel typeface the web
     * client uses, instead of LibGDX's tiny default Arial.
     *
     * Returns a {@link BitmapFont} flipped for the Y-down ortho camera the
     * rest of the game expects. The flip is set via FreeTypeBitmapFontData
     * before the font is built so glyphs draw correctly without per-call
     * inversion.
     */
    private static BitmapFont loadOryxFont() {
        try {
            FreeTypeFontGenerator gen =
                    new FreeTypeFontGenerator(
                            Gdx.files.classpath("oryx-simplex.ttf"));
            FreeTypeFontGenerator.FreeTypeFontParameter p =
                    new FreeTypeFontGenerator.FreeTypeFontParameter();
            p.size = 18;
            p.flip = true;            // y-down camera
            p.minFilter = Texture.TextureFilter.Nearest;
            p.magFilter = Texture.TextureFilter.Nearest;
            BitmapFont f = gen.generateFont(p);
            gen.dispose();
            return f;
        } catch (Exception e) {
            log.warn("[FONT] failed to load oryx-simplex.ttf, falling back to default: {}", e.getMessage());
            BitmapFont fallback = new BitmapFont(true);
            fallback.getData().setScale(1.8f);
            return fallback;
        }
    }

    @Override
    public void create() {
        OpenRealmGame.log.info("Initializing LibGDX client...");

        this.batch = new SpriteBatch();
        this.shapes = new ShapeRenderer();

        // Y-down camera to match existing game math (0,0 at top-left).
        // Smaller ortho viewport = zoomed-in world rendering; web client
        // ships at 2× on desktop so we mirror that.
        this.camera = new OrthographicCamera();
        this.camera.setToOrtho(true, width / WORLD_SCALE, height / WORLD_SCALE);
        // HUD camera: full window size so PlayerUI / OptionsWindow / etc.
        // can keep using OpenRealmGame.width/height as their layout space.
        this.uiCamera = new OrthographicCamera();
        this.uiCamera.setToOrtho(true, width, height);

        // Load the oryx-simplex pixel font via FreeType. Falls back to the
        // built-in BitmapFont if the .ttf is missing or FreeType fails — we
        // never want a font issue to crash the launcher.
        this.defaultFont = loadOryxFont();
        this.defaultFont.getData().setScale(1.0f);

        // Load sprite sheets as LibGDX Textures
        GameSpriteManager.loadSpriteImages(true);
        GameSpriteManager.loadTileSprites();
        GameSpriteManager.loadItemSprites();
        GameSpriteManager.loadAbilitySprites();
        // One-time bake of seam-feather TextureRegions for tile blending.
        // Must run AFTER loadTileSprites so TILE_SPRITES is populated.
        // Uses PIXMAP_CACHE for source pixels — those were stashed by
        // loadSpriteImages a few lines up.
        GameSpriteManager.bakeTileFeathers();
        // Recolor pipeline sanity check — the dye pipeline silently
        // no-ops at draw time when any of these caches is empty, so
        // surfacing the sizes at boot saves a debugging round-trip
        // when "still no dye" reports come in.
        com.openrealm.game.graphics.SpriteRecolorCache.logBootstrap();

        // Initialize shaders for sprite effects
        ShaderManager.init();

        // Set up input handlers
        this.keyHandler = new KeyHandler();
        Gdx.input.setInputProcessor(this.keyHandler);
        this.mouseHandler = new MouseHandler();

        // Initialize the game state manager and enter PlayState
        this.gsm = new GameStateManager(this.batch, this.shapes, this.defaultFont, this.camera);

        // Register JVM shutdown hook for clean disconnect on crash/kill
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            OpenRealmGame.log.info("JVM shutdown hook triggered, cleaning up...");
            this.shutdownNetwork();
        }));

        OpenRealmGame.log.info("LibGDX client initialized.");
    }

    @Override
    public void render() {
        try {
            this.renderFrame();
        } catch (Throwable t) {
            // jpackage --win-console swallows stack traces when the JVM
            // exits, and Lwjgl3Application.loop()'s wrapper bubbles a
            // render exception straight into JVM exit. Log to crash.log
            // ourselves and keep the process alive — recovers from
            // transient render-state issues (e.g. mid-realm-transition
            // tile arrays, half-built Pixmaps) without taking the game
            // down. If the same frame keeps throwing every render call,
            // the user sees one slow stutter while we log on a budget,
            // not a hard crash.
            GameLauncher.writeCrashLog("Render failure (recovering)", t);
            // Best-effort batch cleanup so the next frame starts in a
            // sane GL/batch state.
            try { if (this.batch.isDrawing()) this.batch.end(); } catch (Throwable ignored) {}
        }
    }

    private void renderFrame() {
        float delta = Gdx.graphics.getDeltaTime();

        // Clear screen with dark background
        Gdx.gl.glClearColor(33f / 255f, 30f / 255f, 39f / 255f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // Update input state
        this.keyHandler.update();
        this.mouseHandler.update();

        // Update game state
        this.gsm.update(delta);

        // Process input
        this.gsm.input(this.mouseHandler, this.keyHandler);

        // Default to the UI / screen-space camera so login, char-select,
        // pause, options, and any HUD-only state get drawn at 1:1 pixels.
        // PlayState's world rendering opts into the zoomed world camera
        // for tiles/entities and flips back to the UI camera before
        // drawing PlayerUI.
        this.uiCamera.update();
        this.camera.update();
        this.batch.setProjectionMatrix(this.uiCamera.combined);
        this.shapes.setProjectionMatrix(this.uiCamera.combined);

        this.batch.begin();
        ShaderManager.applyVibrance(this.batch, 1.3f, 1.1f);
        this.gsm.render(this.batch, this.shapes, this.defaultFont);
        this.batch.end();
    }

    @Override
    public void resize(int width, int height) {
        OpenRealmGame.width = width;
        OpenRealmGame.height = height;
        this.camera.setToOrtho(true, width / WORLD_SCALE, height / WORLD_SCALE);
        if (this.uiCamera != null) this.uiCamera.setToOrtho(true, width, height);
    }

    @Override
    public void pause() {
    }

    @Override
    public void resume() {
    }

    private void shutdownNetwork() {
        try {
            if (this.gsm != null && this.gsm.getPlayState() != null) {
                this.gsm.getPlayState().getRealmManager().shutdownClient();
            }
        } catch (Exception e) {
            OpenRealmGame.log.error("Failed to shutdown network. Reason: {}", e.getMessage());
        }
    }

    @Override
    public void dispose() {
        this.shutdownNetwork();
        if (this.batch != null) this.batch.dispose();
        if (this.shapes != null) this.shapes.dispose();
        if (this.defaultFont != null) this.defaultFont.dispose();
        ShaderManager.dispose();
        GameSpriteManager.disposeAll();
    }
}
