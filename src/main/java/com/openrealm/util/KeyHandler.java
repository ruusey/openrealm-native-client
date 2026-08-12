package com.openrealm.util;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;

import com.openrealm.game.Settings;

import lombok.Data;

@Data
public class KeyHandler implements InputProcessor {
    public boolean captureMode = false;
    public String content = "";
    public static List<Key> keys = new ArrayList<Key>();

    // WHY: caret + selection state for the chat capture buffer. selAnchor==-1 means "no selection".
    public int captureCaret = 0;
    public int captureSelAnchor = -1;

    // WHY: held-key auto-repeat constants tuned to OS feel (matches TextField).
    private static final float REPEAT_INITIAL_DELAY = 0.40f;
    private static final float REPEAT_INTERVAL = 0.030f;

    private float backspaceHoldTime = -1f;
    private float backspaceRepeatAccum = 0f;
    private float deleteHoldTime = -1f;
    private float deleteRepeatAccum = 0f;
    private float leftHoldTime = -1f;
    private float leftRepeatAccum = 0f;
    private float rightHoldTime = -1f;
    private float rightRepeatAccum = 0f;

    /**
     * Optional sink for typed characters used by ad-hoc text fields outside
     * the chat captureMode flow (login/register forms, char-create rename
     * dialogs, etc.). When non-null, every printable char from keyTyped is
     * forwarded so multiple {@link com.openrealm.game.ui.TextField} instances
     * can share the single LibGDX InputProcessor without trampling each
     * other's buffers.
     */
    public interface TextSink {
        void onChar(char c);
    }
    public static volatile TextSink textSink = null;

    public Key up = new Key();
    public Key down = new Key();
    public Key left = new Key();
    public Key right = new Key();
    public Key attack = new Key();
    public Key menu = new Key();
    public Key enter = new Key();
    public Key escape = new Key();
    public Key shift = new Key();
    public Key f1 = new Key();
    public Key f2 = new Key();

    public Key one = new Key();
    public Key two = new Key();
    public Key three = new Key();
    public Key four = new Key();
    public Key five = new Key();
    public Key six = new Key();
    public Key seven = new Key();
    public Key eight = new Key();
    public Key zero = new Key();

    public Key q = new Key();
    public Key e = new Key();
    public Key c = new Key();
    public Key t = new Key();
    public Key m = new Key();
    public Key plus = new Key();
    public Key minus = new Key();

    public KeyHandler() {
        // No listener registration needed - we poll Gdx.input
    }

    /** Resolve a rebindable action to its LibGDX key code, falling back to the
     *  default when unset. Read live so remaps take effect without a restart. */
    private static int kb(String action, int fallback) {
        int code = Settings.get().getKeybind(action);
        return code >= 0 ? code : fallback;
    }

    public void releaseAll() {
        for (int i = 0; i < KeyHandler.keys.size(); i++) {
            KeyHandler.keys.get(i).down = false;
        }
    }

    public void tick() {
        for (int i = 0; i < KeyHandler.keys.size(); i++) {
            KeyHandler.keys.get(i).tick();
        }
    }

    public void update() {
        if (this.captureMode) {
            this.tickCaptureEditKeys();
            this.up.toggle(false);
            this.down.toggle(false);
            this.left.toggle(false);
            this.right.toggle(false);
            this.attack.toggle(false);
            this.enter.toggle(Gdx.input.isKeyPressed(Input.Keys.ENTER));
            return;
        }
        // WHY: keep auto-repeat timers fresh when not capturing so a held key doesn't bleed across mode changes.
        this.backspaceHoldTime = -1f;
        this.deleteHoldTime = -1f;
        this.leftHoldTime = -1f;
        this.rightHoldTime = -1f;

        this.up.toggle(Gdx.input.isKeyPressed(kb("moveUp", Input.Keys.W)));
        this.down.toggle(Gdx.input.isKeyPressed(kb("moveDown", Input.Keys.S)));
        this.left.toggle(Gdx.input.isKeyPressed(kb("moveLeft", Input.Keys.A)));
        this.right.toggle(Gdx.input.isKeyPressed(kb("moveRight", Input.Keys.D)));
        this.attack.toggle(Gdx.input.isKeyPressed(kb("usePortal", Input.Keys.SPACE)));
        // The legacy `menu` key field still binds to E for compatibility,
        // but it has no consumers anywhere — the actual menu opens on
        // M / Escape. Camera rotation uses Q (left) / E (right) like the
        // webclient, tracked via the dedicated this.e Key below.
        this.menu.toggle(Gdx.input.isKeyPressed(Input.Keys.E));
        this.enter.toggle(Gdx.input.isKeyPressed(Input.Keys.ENTER));
        this.escape.toggle(Gdx.input.isKeyPressed(Input.Keys.ESCAPE));
        this.f1.toggle(Gdx.input.isKeyPressed(Input.Keys.F1));
        this.f2.toggle(Gdx.input.isKeyPressed(Input.Keys.F2));
        this.shift.toggle(Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT));

        this.one.toggle(Gdx.input.isKeyPressed(Input.Keys.NUM_1));
        this.two.toggle(Gdx.input.isKeyPressed(Input.Keys.NUM_2));
        this.three.toggle(Gdx.input.isKeyPressed(Input.Keys.NUM_3));
        this.four.toggle(Gdx.input.isKeyPressed(Input.Keys.NUM_4));
        this.five.toggle(Gdx.input.isKeyPressed(Input.Keys.NUM_5));
        this.six.toggle(Gdx.input.isKeyPressed(Input.Keys.NUM_6));
        this.seven.toggle(Gdx.input.isKeyPressed(Input.Keys.NUM_7));
        this.eight.toggle(Gdx.input.isKeyPressed(Input.Keys.NUM_8));
        this.zero.toggle(Gdx.input.isKeyPressed(Input.Keys.NUM_0));

        this.q.toggle(Gdx.input.isKeyPressed(kb("rotateLeft", Input.Keys.Q)));
        this.e.toggle(Gdx.input.isKeyPressed(kb("rotateRight", Input.Keys.E)));
        this.c.toggle(Gdx.input.isKeyPressed(kb("resetCamera", Input.Keys.C)));
        this.t.toggle(Gdx.input.isKeyPressed(Input.Keys.T));
        this.m.toggle(Gdx.input.isKeyPressed(Input.Keys.M));
        this.plus.toggle(Gdx.input.isKeyPressed(Input.Keys.PLUS) || Gdx.input.isKeyPressed(Input.Keys.EQUALS));
        this.minus.toggle(Gdx.input.isKeyPressed(Input.Keys.MINUS));
    }

    public void setCaptureMode(boolean captureMode) {
        if (captureMode && !this.captureMode) {
            // WHY: when chat opens, place caret at end of any pre-existing buffer.
            this.captureCaret = this.content.length();
            this.captureSelAnchor = -1;
        }
        this.captureMode = captureMode;
    }

    public void captureInput() {
        this.captureMode = true;
        this.captureCaret = this.content.length();
        this.captureSelAnchor = -1;
    }

    public String getCapturedInput() {
        String content = new String(this.content);
        this.content = "";
        this.captureMode = false;
        this.captureCaret = 0;
        this.captureSelAnchor = -1;
        return content;
    }

    /**
     * Called by LibGDX InputProcessor when in capture mode.
     */
    public void appendChar(char c) {
        if (!this.captureMode) return;
        if (c == '\n' || c == '\r' || c == '\b') return;
        if (c < 32 || c == 127) return;
        if (this.hasCaptureSelection()) this.deleteCaptureSelection();
        if (this.captureCaret < 0) this.captureCaret = 0;
        if (this.captureCaret > this.content.length()) this.captureCaret = this.content.length();
        this.content = this.content.substring(0, this.captureCaret) + c
                + this.content.substring(this.captureCaret);
        this.captureCaret++;
    }

    private boolean hasCaptureSelection() {
        return this.captureSelAnchor >= 0 && this.captureSelAnchor != this.captureCaret;
    }

    private int captureSelStart() { return Math.min(this.captureCaret, this.captureSelAnchor); }
    private int captureSelEnd() { return Math.max(this.captureCaret, this.captureSelAnchor); }

    private void deleteCaptureSelection() {
        if (!this.hasCaptureSelection()) return;
        int s = this.captureSelStart();
        int e = this.captureSelEnd();
        this.content = this.content.substring(0, s) + this.content.substring(e);
        this.captureCaret = s;
        this.captureSelAnchor = -1;
    }

    private void doCaptureBackspace() {
        if (this.hasCaptureSelection()) { this.deleteCaptureSelection(); return; }
        if (this.captureCaret > 0) {
            this.content = this.content.substring(0, this.captureCaret - 1)
                    + this.content.substring(this.captureCaret);
            this.captureCaret--;
        }
    }

    private void doCaptureDelete() {
        if (this.hasCaptureSelection()) { this.deleteCaptureSelection(); return; }
        if (this.captureCaret < this.content.length()) {
            this.content = this.content.substring(0, this.captureCaret)
                    + this.content.substring(this.captureCaret + 1);
        }
    }

    private void moveCaptureLeft(boolean shift) {
        if (shift) {
            if (this.captureSelAnchor < 0) this.captureSelAnchor = this.captureCaret;
            if (this.captureCaret > 0) this.captureCaret--;
        } else {
            if (this.hasCaptureSelection()) {
                this.captureCaret = this.captureSelStart();
                this.captureSelAnchor = -1;
            } else if (this.captureCaret > 0) {
                this.captureCaret--;
            }
        }
    }

    private void moveCaptureRight(boolean shift) {
        if (shift) {
            if (this.captureSelAnchor < 0) this.captureSelAnchor = this.captureCaret;
            if (this.captureCaret < this.content.length()) this.captureCaret++;
        } else {
            if (this.hasCaptureSelection()) {
                this.captureCaret = this.captureSelEnd();
                this.captureSelAnchor = -1;
            } else if (this.captureCaret < this.content.length()) {
                this.captureCaret++;
            }
        }
    }

    /** Drive auto-repeating edit keys + Ctrl+A/C/X/V while in capture mode. */
    private void tickCaptureEditKeys() {
        if (this.captureCaret > this.content.length()) this.captureCaret = this.content.length();
        if (this.captureCaret < 0) this.captureCaret = 0;
        float dt = Gdx.graphics.getDeltaTime();
        boolean ctrl = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT);
        boolean shift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);

        if (Gdx.input.isKeyPressed(Input.Keys.BACKSPACE)) {
            if (this.backspaceHoldTime < 0f) {
                this.backspaceHoldTime = 0f; this.backspaceRepeatAccum = 0f;
                this.doCaptureBackspace();
            } else {
                this.backspaceHoldTime += dt;
                if (this.backspaceHoldTime >= REPEAT_INITIAL_DELAY) {
                    this.backspaceRepeatAccum += dt;
                    while (this.backspaceRepeatAccum >= REPEAT_INTERVAL) {
                        this.backspaceRepeatAccum -= REPEAT_INTERVAL;
                        this.doCaptureBackspace();
                    }
                }
            }
        } else { this.backspaceHoldTime = -1f; }

        if (Gdx.input.isKeyPressed(Input.Keys.FORWARD_DEL)) {
            if (this.deleteHoldTime < 0f) {
                this.deleteHoldTime = 0f; this.deleteRepeatAccum = 0f;
                this.doCaptureDelete();
            } else {
                this.deleteHoldTime += dt;
                if (this.deleteHoldTime >= REPEAT_INITIAL_DELAY) {
                    this.deleteRepeatAccum += dt;
                    while (this.deleteRepeatAccum >= REPEAT_INTERVAL) {
                        this.deleteRepeatAccum -= REPEAT_INTERVAL;
                        this.doCaptureDelete();
                    }
                }
            }
        } else { this.deleteHoldTime = -1f; }

        if (Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            if (this.leftHoldTime < 0f) {
                this.leftHoldTime = 0f; this.leftRepeatAccum = 0f;
                this.moveCaptureLeft(shift);
            } else {
                this.leftHoldTime += dt;
                if (this.leftHoldTime >= REPEAT_INITIAL_DELAY) {
                    this.leftRepeatAccum += dt;
                    while (this.leftRepeatAccum >= REPEAT_INTERVAL) {
                        this.leftRepeatAccum -= REPEAT_INTERVAL;
                        this.moveCaptureLeft(shift);
                    }
                }
            }
        } else { this.leftHoldTime = -1f; }

        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            if (this.rightHoldTime < 0f) {
                this.rightHoldTime = 0f; this.rightRepeatAccum = 0f;
                this.moveCaptureRight(shift);
            } else {
                this.rightHoldTime += dt;
                if (this.rightHoldTime >= REPEAT_INITIAL_DELAY) {
                    this.rightRepeatAccum += dt;
                    while (this.rightRepeatAccum >= REPEAT_INTERVAL) {
                        this.rightRepeatAccum -= REPEAT_INTERVAL;
                        this.moveCaptureRight(shift);
                    }
                }
            }
        } else { this.rightHoldTime = -1f; }

        if (Gdx.input.isKeyJustPressed(Input.Keys.HOME)) {
            if (shift) {
                if (this.captureSelAnchor < 0) this.captureSelAnchor = this.captureCaret;
                this.captureCaret = 0;
            } else { this.captureCaret = 0; this.captureSelAnchor = -1; }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.END)) {
            if (shift) {
                if (this.captureSelAnchor < 0) this.captureSelAnchor = this.captureCaret;
                this.captureCaret = this.content.length();
            } else { this.captureCaret = this.content.length(); this.captureSelAnchor = -1; }
        }

        if (ctrl && Gdx.input.isKeyJustPressed(Input.Keys.A)) {
            this.captureSelAnchor = 0;
            this.captureCaret = this.content.length();
        }
        if (ctrl && Gdx.input.isKeyJustPressed(Input.Keys.C)) {
            if (this.hasCaptureSelection()) {
                Gdx.app.getClipboard().setContents(
                        this.content.substring(this.captureSelStart(), this.captureSelEnd()));
            }
        }
        if (ctrl && Gdx.input.isKeyJustPressed(Input.Keys.X)) {
            if (this.hasCaptureSelection()) {
                Gdx.app.getClipboard().setContents(
                        this.content.substring(this.captureSelStart(), this.captureSelEnd()));
                this.deleteCaptureSelection();
            }
        }
        if (ctrl && Gdx.input.isKeyJustPressed(Input.Keys.V)) {
            String clip = Gdx.app.getClipboard().getContents();
            if (clip != null) {
                this.deleteCaptureSelection();
                StringBuilder filtered = new StringBuilder();
                for (char c : clip.toCharArray()) {
                    if (c < 32 || c == 127) continue;
                    filtered.append(c);
                }
                this.content = this.content.substring(0, this.captureCaret)
                        + filtered + this.content.substring(this.captureCaret);
                this.captureCaret += filtered.length();
            }
        }
    }

    @Override
    public boolean keyTyped(char character) {
        // Sink takes priority over captureMode — when a login/register field
        // is focused we route chars there; chat captureMode is exclusive to
        // gameplay so the two never coincide in practice.
        TextSink sink = KeyHandler.textSink;
        if (sink != null) {
            sink.onChar(character);
            return true;
        }
        this.appendChar(character);
        return this.captureMode;
    }

    @Override
    public boolean keyDown(int keycode) {
        return false;
    }

    @Override
    public boolean keyUp(int keycode) {
        return false;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        return false;
    }

    @Override
    public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
        return false;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        return false;
    }

    /**
     * Mouse-wheel delta accumulated since the last consumer read it.
     * Positive = scrolled down. {@link #consumeScroll()} returns and clears.
     * Used by states (e.g. CharacterSelectState) to scroll their lists
     * without each having to register its own InputProcessor.
     */
    private static volatile float pendingScrollY = 0f;

    public static float consumeScroll() {
        float v = pendingScrollY;
        pendingScrollY = 0f;
        return v;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        pendingScrollY += amountY;
        return false;
    }
}
