package com.openrealm.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Lightweight Swing-style text field for raw SpriteBatch UIs (LoginState,
 * CharacterSelectState). Keeps its own buffer + focus flag and pulls input
 * directly from {@link Gdx#input} via polling, so multiple fields can coexist
 * without fighting over a shared InputProcessor.
 *
 * Caller owns layout and click-to-focus — instantiate, position via
 * {@link #setBounds(int, int, int, int)}, then in the parent's input() call
 * {@link #handleClick(int, int)} on every left-mouse-down to capture/release
 * focus, drive {@link #handleDrag(int, int)} while the button is held and
 * {@link #handleRelease()} on release. Then call {@link #update()} every
 * frame to consume keystrokes.
 *
 * For password fields set {@link #setPassword(boolean)} to render the buffer
 * as masked dots; the underlying {@link #getText()} still returns the real
 * value for use in network calls.
 */
public class TextField {

    public enum UpdateResult { NONE, SUBMIT, TAB, SHIFT_TAB }

    // WHY: held-key auto-repeat constants tuned to OS feel (Win/macOS default ~500ms initial, ~30ms repeat).
    private static final float REPEAT_INITIAL_DELAY = 0.40f;
    private static final float REPEAT_INTERVAL = 0.030f;

    private int x, y, w, h;
    private final StringBuilder buf = new StringBuilder();
    private boolean focused = false;
    private boolean password = false;
    private String placeholder = "";
    private int maxLen = 64;
    private float caretBlinkAccum = 0f;
    private boolean caretVisible = true;

    private int caret = 0;
    // WHY: -1 means "no selection". Range is [min(caret,selAnchor), max(...)] when active.
    private int selAnchor = -1;

    // Per-key auto-repeat timers.
    private float backspaceHoldTime = -1f;
    private float backspaceRepeatAccum = 0f;
    private float deleteHoldTime = -1f;
    private float deleteRepeatAccum = 0f;
    private float leftHoldTime = -1f;
    private float leftRepeatAccum = 0f;
    private float rightHoldTime = -1f;
    private float rightRepeatAccum = 0f;

    private boolean dragging = false;

    public TextField(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    public void setPassword(boolean password) { this.password = password; }
    public void setPlaceholder(String placeholder) { this.placeholder = placeholder; }
    public void setMaxLen(int maxLen) { this.maxLen = maxLen; }

    public void setText(String text) {
        this.buf.setLength(0);
        if (text != null) this.buf.append(text);
        this.caret = this.buf.length();
        this.selAnchor = -1;
    }

    public String getText() { return this.buf.toString(); }
    public boolean isFocused() { return this.focused; }
    public void setFocused(boolean focused) {
        this.focused = focused;
        this.caretVisible = true;
        this.caretBlinkAccum = 0f;
        if (!focused) {
            this.selAnchor = -1;
            this.dragging = false;
        }
    }

    public boolean contains(int mx, int my) {
        return mx >= this.x && mx <= this.x + this.w && my >= this.y && my <= this.y + this.h;
    }

    /**
     * Call on every left-mouse-down. Returns true if this field captured the
     * click (caller should still loop through other fields to defocus them).
     */
    public boolean handleClick(int mx, int my) {
        boolean inside = this.contains(mx, my);
        this.focused = inside;
        if (inside) {
            this.caretVisible = true;
            this.caretBlinkAccum = 0f;
            this.caret = this.indexAtX(mx);
            this.selAnchor = this.caret;
            this.dragging = true;
        } else {
            this.dragging = false;
            this.selAnchor = -1;
        }
        return inside;
    }

    /** Drive while left mouse is held — extends the selection from anchor. */
    public void handleDrag(int mx, int my) {
        if (!this.focused || !this.dragging) return;
        this.caret = this.indexAtX(mx);
        this.caretVisible = true;
        this.caretBlinkAccum = 0f;
    }

    public void handleRelease() {
        this.dragging = false;
        if (this.selAnchor == this.caret) this.selAnchor = -1;
    }

    /**
     * Resolve a screen-x to a buffer index by measuring progressive substrings.
     * Buffers are tiny (<=64 chars) so the O(n^2) loop is fine.
     */
    private int indexAtX(int mx) {
        String shown = this.displayString();
        if (this.cachedFont == null || shown.isEmpty()) return shown.length();
        float baseX = this.x + 10f;
        float relative = mx - baseX;
        if (relative <= 0) return 0;
        GlyphLayout layout = new GlyphLayout();
        float prev = 0f;
        for (int i = 1; i <= shown.length(); i++) {
            layout.setText(this.cachedFont, shown.substring(0, i));
            float w = layout.width;
            if (relative < (prev + w) / 2f) return i - 1;
            prev = w;
        }
        return shown.length();
    }

    // WHY: handleClick can be called before render(), but indexAtX needs a font.
    // Cache the font on first render so click-resolution works for subsequent clicks.
    private BitmapFont cachedFont;

    /**
     * Consume keyboard input while focused. Returns a result indicating whether
     * Enter / Tab / Shift+Tab was pressed this frame so the caller can submit
     * the form or advance focus.
     */
    public UpdateResult update() {
        float dt = Gdx.graphics.getDeltaTime();
        this.caretBlinkAccum += dt;
        if (this.caretBlinkAccum >= 0.5f) {
            this.caretVisible = !this.caretVisible;
            this.caretBlinkAccum = 0f;
        }
        if (!this.focused) {
            this.backspaceHoldTime = -1f;
            this.deleteHoldTime = -1f;
            this.leftHoldTime = -1f;
            this.rightHoldTime = -1f;
            return UpdateResult.NONE;
        }

        boolean ctrl = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT);
        boolean shift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);

        UpdateResult result = UpdateResult.NONE;

        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            result = UpdateResult.SUBMIT;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
            result = shift ? UpdateResult.SHIFT_TAB : UpdateResult.TAB;
        }

        // Backspace with auto-repeat
        if (Gdx.input.isKeyPressed(Input.Keys.BACKSPACE)) {
            if (this.backspaceHoldTime < 0f) {
                this.backspaceHoldTime = 0f;
                this.backspaceRepeatAccum = 0f;
                this.doBackspace();
            } else {
                this.backspaceHoldTime += dt;
                if (this.backspaceHoldTime >= REPEAT_INITIAL_DELAY) {
                    this.backspaceRepeatAccum += dt;
                    while (this.backspaceRepeatAccum >= REPEAT_INTERVAL) {
                        this.backspaceRepeatAccum -= REPEAT_INTERVAL;
                        this.doBackspace();
                    }
                }
            }
        } else {
            this.backspaceHoldTime = -1f;
        }

        // Delete with auto-repeat
        if (Gdx.input.isKeyPressed(Input.Keys.FORWARD_DEL)) {
            if (this.deleteHoldTime < 0f) {
                this.deleteHoldTime = 0f;
                this.deleteRepeatAccum = 0f;
                this.doDelete();
            } else {
                this.deleteHoldTime += dt;
                if (this.deleteHoldTime >= REPEAT_INITIAL_DELAY) {
                    this.deleteRepeatAccum += dt;
                    while (this.deleteRepeatAccum >= REPEAT_INTERVAL) {
                        this.deleteRepeatAccum -= REPEAT_INTERVAL;
                        this.doDelete();
                    }
                }
            }
        } else {
            this.deleteHoldTime = -1f;
        }

        // Left arrow with auto-repeat
        if (Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            if (this.leftHoldTime < 0f) {
                this.leftHoldTime = 0f;
                this.leftRepeatAccum = 0f;
                this.moveCaretLeft(shift);
            } else {
                this.leftHoldTime += dt;
                if (this.leftHoldTime >= REPEAT_INITIAL_DELAY) {
                    this.leftRepeatAccum += dt;
                    while (this.leftRepeatAccum >= REPEAT_INTERVAL) {
                        this.leftRepeatAccum -= REPEAT_INTERVAL;
                        this.moveCaretLeft(shift);
                    }
                }
            }
        } else {
            this.leftHoldTime = -1f;
        }

        // Right arrow with auto-repeat
        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            if (this.rightHoldTime < 0f) {
                this.rightHoldTime = 0f;
                this.rightRepeatAccum = 0f;
                this.moveCaretRight(shift);
            } else {
                this.rightHoldTime += dt;
                if (this.rightHoldTime >= REPEAT_INITIAL_DELAY) {
                    this.rightRepeatAccum += dt;
                    while (this.rightRepeatAccum >= REPEAT_INTERVAL) {
                        this.rightRepeatAccum -= REPEAT_INTERVAL;
                        this.moveCaretRight(shift);
                    }
                }
            }
        } else {
            this.rightHoldTime = -1f;
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.HOME)) {
            if (shift) this.extendSelectionTo(0);
            else { this.caret = 0; this.selAnchor = -1; }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.END)) {
            if (shift) this.extendSelectionTo(this.buf.length());
            else { this.caret = this.buf.length(); this.selAnchor = -1; }
        }

        if (ctrl && Gdx.input.isKeyJustPressed(Input.Keys.A)) {
            this.selAnchor = 0;
            this.caret = this.buf.length();
        }
        if (ctrl && Gdx.input.isKeyJustPressed(Input.Keys.C)) {
            this.copySelection();
        }
        if (ctrl && Gdx.input.isKeyJustPressed(Input.Keys.X)) {
            if (this.copySelection()) this.deleteSelection();
        }
        if (ctrl && Gdx.input.isKeyJustPressed(Input.Keys.V)) {
            String clip = Gdx.app.getClipboard().getContents();
            if (clip != null) {
                this.deleteSelection();
                for (char c : clip.toCharArray()) {
                    if (c < 32 || c == 127) continue;
                    if (this.buf.length() >= this.maxLen) break;
                    this.buf.insert(this.caret, c);
                    this.caret++;
                }
            }
        }

        return result;
    }

    private void moveCaretLeft(boolean shift) {
        if (shift) {
            if (this.selAnchor < 0) this.selAnchor = this.caret;
            if (this.caret > 0) this.caret--;
        } else {
            if (this.selAnchor >= 0) {
                this.caret = Math.min(this.caret, this.selAnchor);
                this.selAnchor = -1;
            } else if (this.caret > 0) {
                this.caret--;
            }
        }
    }

    private void moveCaretRight(boolean shift) {
        if (shift) {
            if (this.selAnchor < 0) this.selAnchor = this.caret;
            if (this.caret < this.buf.length()) this.caret++;
        } else {
            if (this.selAnchor >= 0) {
                this.caret = Math.max(this.caret, this.selAnchor);
                this.selAnchor = -1;
            } else if (this.caret < this.buf.length()) {
                this.caret++;
            }
        }
    }

    private void extendSelectionTo(int newCaret) {
        if (this.selAnchor < 0) this.selAnchor = this.caret;
        this.caret = Math.max(0, Math.min(this.buf.length(), newCaret));
    }

    private void doBackspace() {
        if (this.hasSelection()) {
            this.deleteSelection();
        } else if (this.caret > 0) {
            this.buf.deleteCharAt(this.caret - 1);
            this.caret--;
        }
    }

    private void doDelete() {
        if (this.hasSelection()) {
            this.deleteSelection();
        } else if (this.caret < this.buf.length()) {
            this.buf.deleteCharAt(this.caret);
        }
    }

    private boolean hasSelection() {
        return this.selAnchor >= 0 && this.selAnchor != this.caret;
    }

    private int selStart() { return Math.min(this.caret, this.selAnchor); }
    private int selEnd() { return Math.max(this.caret, this.selAnchor); }

    private void deleteSelection() {
        if (!this.hasSelection()) return;
        int s = this.selStart();
        int e = this.selEnd();
        this.buf.delete(s, e);
        this.caret = s;
        this.selAnchor = -1;
    }

    private boolean copySelection() {
        if (!this.hasSelection()) return false;
        // WHY: never expose password text via the system clipboard.
        if (this.password) return true;
        String sel = this.buf.substring(this.selStart(), this.selEnd());
        Gdx.app.getClipboard().setContents(sel);
        return true;
    }

    /**
     * Forwarded from the parent state's InputProcessor.keyTyped(c) — adds the
     * printable character to the buffer at the caret. Filters control codes.
     */
    public void appendChar(char c) {
        if (!this.focused) return;
        if (c < 32 || c == 127) return;
        if (this.hasSelection()) this.deleteSelection();
        if (this.buf.length() >= this.maxLen) return;
        this.buf.insert(this.caret, c);
        this.caret++;
        this.caretVisible = true;
        this.caretBlinkAccum = 0f;
    }

    private String displayString() {
        if (this.password) {
            char[] dots = new char[this.buf.length()];
            for (int i = 0; i < dots.length; i++) dots[i] = '*';
            return new String(dots);
        }
        return this.buf.toString();
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        this.cachedFont = font;
        // WHY: clamp caret/anchor in case external setText shrank buffer.
        if (this.caret > this.buf.length()) this.caret = this.buf.length();
        if (this.selAnchor > this.buf.length()) this.selAnchor = this.buf.length();

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.93f, 0.93f, 0.96f, 1f);
        shapes.rect(this.x, this.y, this.w, this.h);
        shapes.end();

        boolean placeholderActive = this.buf.length() == 0;
        String shown = placeholderActive ? (this.placeholder == null ? "" : this.placeholder)
                                          : this.displayString();
        GlyphLayout fullLayout = new GlyphLayout(font, shown.isEmpty() ? "X" : shown);
        float textY = this.y + (this.h - fullLayout.height) / 2f;
        float textX = this.x + 10f;

        // Selection highlight
        if (!placeholderActive && this.hasSelection()) {
            int s = this.selStart();
            int e = this.selEnd();
            GlyphLayout pre = new GlyphLayout(font, shown.substring(0, s));
            GlyphLayout sel = new GlyphLayout(font, shown.substring(s, e));
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.40f, 0.60f, 0.95f, 0.35f);
            shapes.rect(textX + pre.width, this.y + 4, sel.width, this.h - 8);
            shapes.end();
        }

        shapes.begin(ShapeRenderer.ShapeType.Line);
        if (this.focused) shapes.setColor(0.78f, 0.66f, 0.43f, 1f);
        else shapes.setColor(0.30f, 0.25f, 0.30f, 1f);
        shapes.rect(this.x, this.y, this.w, this.h);
        shapes.end();
        batch.begin();

        if (placeholderActive) {
            font.setColor(0.45f, 0.45f, 0.50f, 1f);
        } else {
            font.setColor(0.10f, 0.10f, 0.15f, 1f);
        }
        font.draw(batch, shown, textX, textY);

        if (this.focused && this.caretVisible) {
            float caretX;
            if (placeholderActive) {
                caretX = textX;
            } else {
                GlyphLayout caretLayout = new GlyphLayout(font, shown.substring(0, this.caret));
                caretX = textX + caretLayout.width + 1;
            }
            font.draw(batch, "|", caretX, textY);
        }
        font.setColor(Color.WHITE);
    }
}
