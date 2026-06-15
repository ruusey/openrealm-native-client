package com.openrealm.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.Settings;

/**
 * In-game options menu modeled after the web client's settings drawer.
 *
 * Three tabs: Graphics / Controls / Audio. Settings are mutated against the
 * shared {@link Settings} singleton; closing the window persists to disk.
 *
 * The "Controls" tab supports keybind rebinding: click a binding row to enter
 * "listening" mode, press a key to assign it. ESC cancels the bind.
 *
 * This is a self-contained imperative UI — no Scene2D — to match the rest of
 * the native client's rendering style.
 */
public class OptionsWindow {
    public enum Tab { GRAPHICS, CONTROLS, AUDIO }

    private boolean visible = false;
    private Tab activeTab = Tab.GRAPHICS;

    /** When non-null, the next key press is captured and bound to this action. */
    private String pendingBindAction = null;

    /** Mouse coordinates from the last update, in screen-space (LibGDX origin = bottom-left). */
    private int mouseX, mouseY;
    private boolean mouseDown, mouseDownPrev;

    /** Action list shown in the Controls tab. Order matches display order. */
    private static final String[] BINDABLE_ACTIONS = {
        "moveUp", "moveDown", "moveLeft", "moveRight",
        "hpPotion", "mpPotion", "lootPickup",
        "rotateLeft", "rotateRight", "resetCamera",
        "autofire", "inventory", "chat", "menu"
    };

    public boolean isVisible() {
        return this.visible;
    }

    public void show() {
        this.visible = true;
        this.pendingBindAction = null;
    }

    public void hide() {
        this.visible = false;
        this.pendingBindAction = null;
        // Persist on close so a crash mid-session doesn't lose user changes.
        Settings.get().save();
    }

    public void toggle() {
        if (this.visible) this.hide();
        else this.show();
    }

    /**
     * Called every frame from the game loop. Captures keypresses while a
     * rebind is pending and updates mouse-derived state for the next render.
     */
    public void update() {
        if (!this.visible) return;

        this.mouseX = Gdx.input.getX();
        this.mouseY = Gdx.graphics.getHeight() - Gdx.input.getY(); // flip to GL space
        this.mouseDownPrev = this.mouseDown;
        this.mouseDown = Gdx.input.isButtonPressed(Input.Buttons.LEFT);

        if (this.pendingBindAction != null) {
            // ESC cancels the rebind without changing the binding.
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
                this.pendingBindAction = null;
                return;
            }
            // Capture the first non-modifier key that's just been pressed.
            for (int code = 0; code < 256; code++) {
                if (Gdx.input.isKeyJustPressed(code) && code != Input.Keys.ESCAPE) {
                    Settings.get().setKeybind(this.pendingBindAction, code);
                    this.pendingBindAction = null;
                    break;
                }
            }
        }
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!this.visible) return;

        int w = OpenRealmGame.width;
        int h = OpenRealmGame.height;
        int dialogW = Math.min(640, w - 80);
        int dialogH = Math.min(480, h - 80);
        int x = (w - dialogW) / 2;
        int y = (h - dialogH) / 2;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Dim backdrop
        shapes.setColor(0f, 0f, 0f, 0.7f);
        shapes.rect(0, 0, w, h);

        // Dialog body
        shapes.setColor(0.12f, 0.12f, 0.14f, 0.95f);
        shapes.rect(x, y, dialogW, dialogH);
        shapes.setColor(0.25f, 0.25f, 0.30f, 1f);
        shapes.rect(x, y + dialogH - 2, dialogW, 2);

        // Tab strip
        int tabW = dialogW / 3;
        int tabH = 30;
        int tabY = y + dialogH - tabH;
        for (int i = 0; i < 3; i++) {
            Tab t = Tab.values()[i];
            boolean active = t == this.activeTab;
            shapes.setColor(active ? 0.25f : 0.18f, active ? 0.25f : 0.18f, active ? 0.30f : 0.20f, 1f);
            shapes.rect(x + i * tabW, tabY, tabW, tabH);
        }

        shapes.end();
        batch.begin();

        font.setColor(Color.WHITE);
        for (int i = 0; i < 3; i++) {
            Tab t = Tab.values()[i];
            String label = t.name();
            font.draw(batch, label, x + i * tabW + 16, tabY + tabH - 10);
        }

        // Tab body
        int bodyX = x + 16;
        int bodyY = tabY - 24;
        int lineH = 22;
        switch (this.activeTab) {
            case GRAPHICS: this.renderGraphicsTab(batch, font, bodyX, bodyY, lineH); break;
            case CONTROLS: this.renderControlsTab(batch, font, bodyX, bodyY, lineH); break;
            case AUDIO:    this.renderAudioTab(batch, font, bodyX, bodyY, lineH); break;
        }

        // Footer
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "Press Esc to close", x + 16, y + 18);

        // Process clicks AFTER the layout is rendered so coordinates are current.
        if (this.mouseDown && !this.mouseDownPrev) {
            this.handleClick(x, y, dialogW, dialogH, tabW, tabH, tabY, bodyX, bodyY, lineH);
        }
    }

    private void renderGraphicsTab(SpriteBatch batch, BitmapFont font, int x, int y, int lineH) {
        Settings s = Settings.get();
        font.setColor(Color.WHITE);
        font.draw(batch, "[" + (s.isHideOtherPlayerBullets() ? "x" : " ") + "]  Hide other players' projectiles", x, y);
        font.draw(batch, "[" + (s.isShowDamageNumbers() ? "x" : " ") + "]  Show damage numbers",                x, y - lineH);
        font.draw(batch, "[" + (s.isShowPlayerNames() ? "x" : " ") + "]  Show player names",                   x, y - lineH * 2);
        font.draw(batch, "[" + (s.isShowRealmTransition() ? "x" : " ") + "]  Show realm transition screen",    x, y - lineH * 3);
        font.draw(batch, "Render quality: " + s.getRenderQuality(),                                            x, y - lineH * 4);
        font.draw(batch, "Max bullets on screen: " + (s.getMaxBulletsOnScreen() < 0 ? "Unlimited" : s.getMaxBulletsOnScreen()), x, y - lineH * 5);
        font.draw(batch, "Wall detail: " + s.getWallRenderMode() + "  (simple = faster)",                       x, y - lineH * 6);
    }

    private void renderControlsTab(SpriteBatch batch, BitmapFont font, int x, int y, int lineH) {
        Settings s = Settings.get();
        font.setColor(Color.WHITE);
        font.draw(batch, "Click a row to rebind. Esc cancels.", x, y);
        for (int i = 0; i < BINDABLE_ACTIONS.length; i++) {
            String action = BINDABLE_ACTIONS[i];
            int code = s.getKeybind(action);
            String label = action;
            String key = (this.pendingBindAction != null && this.pendingBindAction.equals(action))
                    ? "<press a key>"
                    : Input.Keys.toString(code);
            font.draw(batch, label + ":  " + key, x, y - lineH * (i + 1));
        }
    }

    private void renderAudioTab(SpriteBatch batch, BitmapFont font, int x, int y, int lineH) {
        Settings s = Settings.get();
        font.setColor(Color.WHITE);
        font.draw(batch, String.format("Master:  %.0f%%", s.getMasterVolume() * 100), x, y);
        font.draw(batch, String.format("SFX:     %.0f%%", s.getSfxVolume() * 100),    x, y - lineH);
        font.draw(batch, String.format("Music:   %.0f%%", s.getMusicVolume() * 100),  x, y - lineH * 2);
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "(audio engine not yet wired; values persist for future use)", x, y - lineH * 4);
    }

    private void handleClick(int x, int y, int dialogW, int dialogH, int tabW, int tabH, int tabY,
                             int bodyX, int bodyY, int lineH) {
        // Tab strip click
        if (this.mouseY >= tabY && this.mouseY <= tabY + tabH
                && this.mouseX >= x && this.mouseX <= x + dialogW) {
            int idx = (this.mouseX - x) / tabW;
            if (idx >= 0 && idx < 3) {
                this.activeTab = Tab.values()[idx];
                this.pendingBindAction = null;
                return;
            }
        }

        Settings s = Settings.get();
        if (this.activeTab == Tab.GRAPHICS) {
            int row = (bodyY - this.mouseY) / lineH;
            switch (row) {
                case 0: s.setHideOtherPlayerBullets(!s.isHideOtherPlayerBullets()); break;
                case 1: s.setShowDamageNumbers(!s.isShowDamageNumbers()); break;
                case 2: s.setShowPlayerNames(!s.isShowPlayerNames()); break;
                case 3: s.setShowRealmTransition(!s.isShowRealmTransition()); break;
                case 4: s.setRenderQuality(cycle(s.getRenderQuality(), "low", "med", "high")); break;
                case 5: s.setMaxBulletsOnScreen(cycleInt(s.getMaxBulletsOnScreen(), 50, 100, 200, -1)); break;
                case 6: s.setWallRenderMode(cycle(s.getWallRenderMode(), "simple", "fancy")); break;
                default: break;
            }
        } else if (this.activeTab == Tab.CONTROLS) {
            int row = ((bodyY - this.mouseY) / lineH) - 1; // first row is the hint text
            if (row >= 0 && row < BINDABLE_ACTIONS.length) {
                this.pendingBindAction = BINDABLE_ACTIONS[row];
            }
        } else if (this.activeTab == Tab.AUDIO) {
            int row = (bodyY - this.mouseY) / lineH;
            float delta = 0.1f;
            if (row == 0) s.setMasterVolume(clamp01(s.getMasterVolume() + delta));
            if (row == 1) s.setSfxVolume(clamp01(s.getSfxVolume() + delta));
            if (row == 2) s.setMusicVolume(clamp01(s.getMusicVolume() + delta));
        }
    }

    private static String cycle(String current, String... options) {
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(current)) return options[(i + 1) % options.length];
        }
        return options[0];
    }

    private static int cycleInt(int current, int... options) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) return options[(i + 1) % options.length];
        }
        return options[0];
    }

    private static float clamp01(float v) {
        // Wrap audio sliders so a click on a maxed slider drops to zero,
        // letting the user cycle through values without a separate slider UI.
        if (v > 1.001f) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }
}
