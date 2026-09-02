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
        "rotateLeft", "rotateRight", "toggleChat",
        "lootPickup", "usePortal", "goNexus", "chat",
        "skillsMenu", "metricsMenu"
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

    /** Graphics checkbox rows, in display order. Each renders as a checkbox and
     *  toggles on click (see the row switch in handleClick). Wall detail is the
     *  one cycle row and sits last. */
    private static final String[] GRAPHICS_ROWS = {
        "renderOtherPlayers", "showPlayerNames", "showStatusBubbles", "showChatBubbles",
        "showDamageNumbers", "playAbilityAnimations", "spriteStroke", "lootBagPreview",
        "hideOtherPlayerBullets", "hideAllyEffects", "showRealmTransition"
    };

    private static String graphicsLabel(String key) {
        switch (key) {
            case "renderOtherPlayers":     return "Render other players";
            case "showPlayerNames":        return "Show player names";
            case "showStatusBubbles":      return "Show status effect icons";
            case "showChatBubbles":        return "Show chat/text bubbles";
            case "showDamageNumbers":      return "Show damage numbers";
            case "playAbilityAnimations":  return "Play ability animations";
            case "spriteStroke":           return "Sprite outlines";
            case "lootBagPreview":         return "Loot bag preview";
            case "hideOtherPlayerBullets": return "Hide other players' projectiles";
            case "hideAllyEffects":        return "Hide ally ability effects";
            case "showRealmTransition":    return "Show realm transition screen";
            default:                       return key;
        }
    }

    private static boolean graphicsValue(Settings s, String key) {
        switch (key) {
            case "renderOtherPlayers":     return s.isRenderOtherPlayers();
            case "showPlayerNames":        return s.isShowPlayerNames();
            case "showStatusBubbles":      return s.isShowStatusBubbles();
            case "showChatBubbles":        return s.isShowChatBubbles();
            case "showDamageNumbers":      return s.isShowDamageNumbers();
            case "playAbilityAnimations":  return s.isPlayAbilityAnimations();
            case "spriteStroke":           return s.isSpriteStroke();
            case "lootBagPreview":         return s.isLootBagPreview();
            case "hideOtherPlayerBullets": return s.isHideOtherPlayerBullets();
            case "hideAllyEffects":        return s.isHideAllyEffects();
            case "showRealmTransition":    return s.isShowRealmTransition();
            default:                       return false;
        }
    }

    private static void toggleGraphics(Settings s, String key) {
        switch (key) {
            case "renderOtherPlayers":     s.setRenderOtherPlayers(!s.isRenderOtherPlayers()); break;
            case "showPlayerNames":        s.setShowPlayerNames(!s.isShowPlayerNames()); break;
            case "showStatusBubbles":      s.setShowStatusBubbles(!s.isShowStatusBubbles()); break;
            case "showChatBubbles":        s.setShowChatBubbles(!s.isShowChatBubbles()); break;
            case "showDamageNumbers":      s.setShowDamageNumbers(!s.isShowDamageNumbers()); break;
            case "playAbilityAnimations":  s.setPlayAbilityAnimations(!s.isPlayAbilityAnimations()); break;
            case "spriteStroke":           s.setSpriteStroke(!s.isSpriteStroke()); break;
            case "lootBagPreview":         s.setLootBagPreview(!s.isLootBagPreview()); break;
            case "hideOtherPlayerBullets": s.setHideOtherPlayerBullets(!s.isHideOtherPlayerBullets()); break;
            case "hideAllyEffects":        s.setHideAllyEffects(!s.isHideAllyEffects()); break;
            case "showRealmTransition":    s.setShowRealmTransition(!s.isShowRealmTransition()); break;
            default: break;
        }
    }

    /** Friendly labels for the controls tab. */
    private static String controlLabel(String action) {
        switch (action) {
            case "moveUp":      return "Move Up";
            case "moveDown":    return "Move Down";
            case "moveLeft":    return "Move Left";
            case "moveRight":   return "Move Right";
            case "rotateLeft":  return "Rotate Camera Left";
            case "rotateRight": return "Rotate Camera Right";
            case "toggleChat":  return "Toggle Chat Window";
            case "lootPickup":  return "Pick Up / Interact";
            case "usePortal":   return "Use Nearest Portal";
            case "goNexus":     return "Return to Nexus";
            case "chat":        return "Open Chat";
            case "skillsMenu":  return "Skills Menu";
            case "metricsMenu": return "Character Stats";
            default:            return action;
        }
    }

    private void renderGraphicsTab(SpriteBatch batch, BitmapFont font, int x, int y, int lineH) {
        Settings s = Settings.get();
        font.setColor(Color.WHITE);
        for (int i = 0; i < GRAPHICS_ROWS.length; i++) {
            String key = GRAPHICS_ROWS[i];
            font.draw(batch, "[" + (graphicsValue(s, key) ? "x" : " ") + "]  " + graphicsLabel(key), x, y - lineH * i);
        }
        font.draw(batch, "Wall detail: " + s.getWallRenderMode(),
                x, y - lineH * GRAPHICS_ROWS.length);
    }

    private void renderControlsTab(SpriteBatch batch, BitmapFont font, int x, int y, int lineH) {
        Settings s = Settings.get();
        font.setColor(Color.WHITE);
        font.draw(batch, "Click a row to rebind. Esc cancels.", x, y);
        for (int i = 0; i < BINDABLE_ACTIONS.length; i++) {
            String action = BINDABLE_ACTIONS[i];
            int code = s.getKeybind(action);
            String key = (this.pendingBindAction != null && this.pendingBindAction.equals(action))
                    ? "<press a key>"
                    : Input.Keys.toString(code);
            font.draw(batch, controlLabel(action) + ":  " + key, x, y - lineH * (i + 1));
        }
    }

    private void renderAudioTab(SpriteBatch batch, BitmapFont font, int x, int y, int lineH) {
        Settings s = Settings.get();
        font.setColor(Color.WHITE);
        font.draw(batch, String.format("Master:  %.0f%%", s.getMasterVolume() * 100), x, y);
        font.draw(batch, String.format("SFX:     %.0f%%", s.getSfxVolume() * 100),    x, y - lineH);
        font.draw(batch, String.format("Music:   %.0f%%", s.getMusicVolume() * 100),  x, y - lineH * 2);
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
            if (row >= 0 && row < GRAPHICS_ROWS.length) {
                toggleGraphics(s, GRAPHICS_ROWS[row]);
            } else if (row == GRAPHICS_ROWS.length) {
                s.setWallRenderMode(cycle(s.getWallRenderMode(), "simple", "fancy"));
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

    private static float clamp01(float v) {
        // Wrap audio sliders so a click on a maxed slider drops to zero,
        // letting the user cycle through values without a separate slider UI.
        if (v > 1.001f) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }
}
