package com.openrealm.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Lightweight Swing-style text field for raw SpriteBatch UIs (LoginState,
 * CharacterSelectState). Keeps its own buffer + focus flag and pulls input
 * directly from {@link Gdx#input} via {@code isKeyJustPressed} polling, so
 * multiple fields can coexist without fighting over a shared InputProcessor.
 *
 * Caller owns layout and click-to-focus — instantiate, position via
 * {@link #setBounds(int, int, int, int)}, then in the parent's input() call
 * {@link #handleClick(int, int)} on every left-mouse-down to capture/release
 * focus, and {@link #update()} every frame to consume keystrokes.
 *
 * For password fields set {@link #setPassword(boolean)} to render the buffer
 * as masked dots; the underlying {@link #getText()} still returns the real
 * value for use in network calls.
 */
public class TextField {
    private int x, y, w, h;
    private final StringBuilder buf = new StringBuilder();
    private boolean focused = false;
    private boolean password = false;
    private String placeholder = "";
    private int maxLen = 64;
    private float caretBlinkAccum = 0f;
    private boolean caretVisible = true;

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
    }

    public String getText() { return this.buf.toString(); }
    public boolean isFocused() { return this.focused; }
    public void setFocused(boolean focused) {
        this.focused = focused;
        this.caretVisible = true;
        this.caretBlinkAccum = 0f;
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
        }
        return inside;
    }

    /**
     * Consume keyboard input while focused. Reads via Gdx.input.isKeyJustPressed
     * so a held key only inserts once per press (matching Swing/HTML inputs).
     * Returns true if Enter was pressed this frame — caller can use that to
     * advance focus or submit the form.
     */
    public boolean update() {
        this.caretBlinkAccum += Gdx.graphics.getDeltaTime();
        if (this.caretBlinkAccum >= 0.5f) {
            this.caretVisible = !this.caretVisible;
            this.caretBlinkAccum = 0f;
        }
        if (!this.focused) return false;

        boolean submitted = false;
        if (Gdx.input.isKeyJustPressed(Input.Keys.BACKSPACE) && this.buf.length() > 0) {
            this.buf.deleteCharAt(this.buf.length() - 1);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
            submitted = true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
            // Caller decides what to do with TAB (focus advance) — we just
            // surface it as a submit-like signal via separate path. For now
            // treat TAB as defocus so caller can pick it up.
            this.focused = false;
        }
        // Read printable input via the keyTyped queue. We don't have a hook
        // here; fallback to scanning common keys. Better path is the
        // keyTyped char in the parent's InputProcessor — handled separately.

        // V/Ctrl-V paste support: poll system clipboard if the user pressed it.
        if (Gdx.input.isKeyJustPressed(Input.Keys.V)
                && (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT))) {
            String clip = Gdx.app.getClipboard().getContents();
            if (clip != null) {
                for (char c : clip.toCharArray()) this.appendChar(c);
            }
        }
        return submitted;
    }

    /**
     * Forwarded from the parent state's InputProcessor.keyTyped(c) — adds the
     * printable character to the buffer if focused. Filters control codes.
     */
    public void appendChar(char c) {
        if (!this.focused) return;
        if (c < 32 || c == 127) return; // skip control / delete
        if (this.buf.length() >= this.maxLen) return;
        this.buf.append(c);
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        // Background + border
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.07f, 0.10f, 0.95f);
        shapes.rect(this.x, this.y, this.w, this.h);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        if (this.focused) shapes.setColor(0.78f, 0.66f, 0.43f, 1f);
        else shapes.setColor(0.30f, 0.25f, 0.30f, 1f);
        shapes.rect(this.x, this.y, this.w, this.h);
        shapes.end();
        batch.begin();

        String shown;
        boolean placeholderActive = this.buf.length() == 0;
        if (placeholderActive) {
            shown = this.placeholder == null ? "" : this.placeholder;
            font.setColor(0.45f, 0.42f, 0.42f, 1f);
        } else if (this.password) {
            char[] dots = new char[this.buf.length()];
            for (int i = 0; i < dots.length; i++) dots[i] = '*';
            shown = new String(dots);
            font.setColor(Color.WHITE);
        } else {
            shown = this.buf.toString();
            font.setColor(Color.WHITE);
        }
        // Draw text using the y-down (flipped) font that's the project default.
        // Vertical center: baseline ~ top + h*0.65f.
        float textY = this.y + this.h * 0.65f;
        float textX = this.x + 8;
        font.draw(batch, shown, textX, textY);
        if (this.focused && this.caretVisible && !placeholderActive) {
            float caretX = textX + font.getRegion().getRegionWidth(); // approximate
            // Simpler: place caret at end of drawn text using BitmapFont layout
            com.badlogic.gdx.graphics.g2d.GlyphLayout layout =
                    new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, shown);
            caretX = textX + layout.width + 1;
            font.draw(batch, "_", caretX, textY);
        } else if (this.focused && this.caretVisible) {
            font.draw(batch, "_", textX, textY);
        }
        font.setColor(Color.WHITE);
    }
}
