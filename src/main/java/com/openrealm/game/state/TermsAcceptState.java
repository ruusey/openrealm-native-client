package com.openrealm.game.state;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack;
import com.badlogic.gdx.utils.Align;

import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * One-time Terms-of-Use acceptance gate. Inserted between LoginState and
 * CharacterSelectState: on entry it asks the data service whether the account
 * still needs to accept the current Terms version. If not, it transitions
 * straight through to character select; if so, it shows a must-read-and-agree
 * frame (the "I Agree" button unlocks only after the user scrolls the terms to
 * the bottom). Declining logs the user back out to LoginState.
 *
 * All GameStateManager transitions happen on the GL thread (update/input); the
 * network calls run on worker threads that only flip atomics.
 */
@Slf4j
public class TermsAcceptState extends GameState {

    // Font scale for the scrollable body text, relative to the UI font's base
    // scale (the title/buttons use the base scale; the body is a touch smaller
    // so more of the terms fit per line).
    private static final float BODY_SCALE = 0.7f;
    private static final float SCROLL_STEP = 56f;

    private static final String TERMS_TEXT =
        "OpenRealm  (c) 2024-2026 Robert Usey. All Rights Reserved.\n\n"
      + "LICENSE & PROHIBITED CONDUCT\n"
      + "OpenRealm and its client, artwork, data files, and the network protocol used to communicate with OpenRealm servers (the \"Software\") are the exclusive property of Robert Usey and are protected by copyright. The Software is licensed, not sold. You are granted a personal, limited, non-transferable, revocable license to use the official client solely to play OpenRealm. All rights not expressly granted are reserved.\n\n"
      + "You may NOT:\n"
      + "  - copy, modify, distribute, or create derivative works of the Software;\n"
      + "  - reverse engineer, decompile, or disassemble it -- this expressly includes intercepting, capturing, recording, injecting, replaying, or modifying network traffic between the client and our servers, or emulating or reimplementing the network protocol;\n"
      + "  - extract, rip, or redistribute the game's assets or data files;\n"
      + "  - use, create, or distribute bots, cheats, memory editors, packet tools, or modified/unofficial clients;\n"
      + "  - circumvent, disable, or interfere with any security, authentication, or access-control measure; or\n"
      + "  - remove or alter any proprietary notices.\n\n"
      + "Any unauthorized use is a material breach of these Terms and may result in civil and criminal penalties, and/or violate anti-circumvention and computer-misuse laws. We may suspend or terminate your account at any time for any violation. The Software is provided \"AS IS\", without warranty of any kind.\n\n"
      + "PRIVACY\n"
      + "The only personal information we collect is the email address you register with. We do NOT use cookies, analytics, advertising, or third-party trackers, and we never sell or share your information. Your email identifies your account and is used only for essential messages (such as password resets); your in-game progress and characters are saved to your account.\n\n"
      + "To run the game and protect it from abuse, our servers process your device's IP address during your session for connection, security, and anti-cheat. We do not use it to track you and retain it only briefly. You may request access to or deletion of your account and email at any time by contacting ruusey@gmail.com.\n\n"
      + "By clicking \"I Agree\" you confirm that you have read and accept these Terms of Use and the Privacy Policy.";

    private final transient PlayerAccountDto account;
    private final transient OpenRealmClientDataService svc;

    // null = still checking; TRUE = must accept (show gate); FALSE = already
    // accepted or the check failed (fail-open -> pass straight through).
    private final AtomicReference<Boolean> checkResult = new AtomicReference<>(null);
    private final AtomicBoolean acceptFinished = new AtomicBoolean(false);
    private boolean shown = false;          // gate visible (checkResult was TRUE)
    private boolean busy = false;           // an accept POST is in flight
    private boolean transitioned = false;   // guard so we only hand off once

    // Scroll + read-gate state.
    private float scrollOffset = 0f;
    private float maxScroll = 0f;
    private boolean scrolledToBottom = false;
    private boolean prevMouseDown = false;

    // Cached wrapped layout of the body text (computed once at BODY_SCALE).
    private GlyphLayout cachedBody = null;
    private float bodyTextHeight = 0f;
    private float cachedBodyWidth = -1f;

    // Geometry, recomputed each frame from the current window size so render()
    // and input() always agree on hit-boxes.
    private int cardX, cardY, cardW, cardH;
    private int bodyX, bodyY, bodyW, bodyH;
    private int btnW, btnH, btnY, declineX, agreeX;

    public TermsAcceptState(GameStateManager gsm, PlayerAccountDto account) {
        super(gsm);
        this.account = account;
        this.svc = ClientGameLogic.DATA_SERVICE;
        new Thread(() -> {
            try {
                this.checkResult.set(this.svc.needsTermsAcceptance());
            } catch (Exception e) {
                // Fail open: a transient status-check failure shouldn't lock the
                // player out. The gate still fires whenever the server reports it.
                log.warn("[TERMS] status check failed, passing through: {}", e.getMessage());
                this.checkResult.set(Boolean.FALSE);
            }
        }, "openrealm-terms-check").start();
    }

    private void recomputeLayout() {
        this.cardW = Math.min(640, OpenRealmGame.width - 40);
        this.cardH = Math.min(720, OpenRealmGame.height - 40);
        this.cardX = (OpenRealmGame.width - this.cardW) / 2;
        this.cardY = (OpenRealmGame.height - this.cardH) / 2;

        final int pad = 24;
        final int btnAreaH = 100;    // reserved at the bottom for hint + buttons
        this.bodyX = this.cardX + pad;
        this.bodyY = this.cardY + 78;
        this.bodyW = this.cardW - 2 * pad;
        this.bodyH = this.cardH - 78 - btnAreaH;

        this.btnW = 210;
        this.btnH = 46;
        final int gap = 24;
        final int totalBtn = 2 * this.btnW + gap;
        this.declineX = this.cardX + (this.cardW - totalBtn) / 2;
        this.agreeX = this.declineX + this.btnW + gap;
        this.btnY = this.cardY + this.cardH - 24 - this.btnH;
    }

    @Override
    public void update(double time) {
        if (this.transitioned) return;
        if (!this.shown) {
            final Boolean r = this.checkResult.get();
            if (r != null) {
                if (Boolean.TRUE.equals(r)) this.shown = true;
                else this.goToCharacterSelect();
            }
        }
        if (this.acceptFinished.get()) {
            this.goToCharacterSelect();
        }
    }

    @Override
    public void input(MouseHandler mouse, KeyHandler key) {
        if (this.transitioned || !this.shown || this.busy) return;
        this.recomputeLayout();

        // --- scroll (wheel + keyboard) ---
        final float wheel = KeyHandler.consumeScroll();
        if (wheel != 0f) this.scrollOffset += wheel * SCROLL_STEP;
        final float page = this.bodyH - 28;
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_DOWN)) this.scrollOffset += page;
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_UP))   this.scrollOffset -= page;
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN))      this.scrollOffset += 48f;
        if (Gdx.input.isKeyJustPressed(Input.Keys.UP))        this.scrollOffset -= 48f;
        this.scrollOffset = Math.max(0f, Math.min(this.maxScroll, this.scrollOffset));

        // --- buttons ---
        final boolean mouseDown = mouse.isPressed(1);
        final boolean justClicked = mouseDown && !this.prevMouseDown;
        this.prevMouseDown = mouseDown;
        final int mx = mouse.getX();
        final int my = mouse.getY();
        if (justClicked) {
            if (this.hit(mx, my, this.declineX, this.btnY, this.btnW, this.btnH)) {
                this.decline();
                return;
            }
            if (this.scrolledToBottom
                    && this.hit(mx, my, this.agreeX, this.btnY, this.btnW, this.btnH)) {
                this.agree();
            }
        }
    }

    @Override
    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        this.recomputeLayout();

        // Dark backdrop.
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.07f, 0.10f, 1f);
        shapes.rect(0, 0, OpenRealmGame.width, OpenRealmGame.height);
        shapes.end();
        batch.begin();

        if (!this.shown) {
            font.setColor(0.78f, 0.66f, 0.43f, 1f);
            this.drawCenteredText(batch, font, "Loading...",
                    OpenRealmGame.width / 2f, OpenRealmGame.height / 2f);
            font.setColor(Color.WHITE);
            return;
        }

        // Card + body panel.
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.16f, 0.13f, 0.16f, 1f);
        shapes.rect(this.cardX, this.cardY, this.cardW, this.cardH);
        shapes.setColor(0.12f, 0.09f, 0.13f, 1f);
        shapes.rect(this.bodyX, this.bodyY, this.bodyW, this.bodyH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.30f, 0.25f, 0.30f, 1f);
        shapes.rect(this.cardX, this.cardY, this.cardW, this.cardH);
        shapes.rect(this.bodyX, this.bodyY, this.bodyW, this.bodyH);
        shapes.end();
        batch.begin();

        // Title + subtitle (centered).
        final float cx = this.cardX + this.cardW / 2f;
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        this.drawCenteredText(batch, font, "Terms of Use & Privacy Policy", cx, this.cardY + 24);
        font.setColor(0.55f, 0.50f, 0.45f, 1f);
        this.drawCenteredText(batch, font, "Read to the bottom to enable \"I Agree\".", cx, this.cardY + 50);

        // --- scrollable body text (clipped + scrolled) ---
        final int textX = this.bodyX + 14;
        final int textY = this.bodyY + 14;
        final int textW = this.bodyW - 28;
        final int innerH = this.bodyH - 28;

        final float prevSx = font.getData().scaleX;
        final float prevSy = font.getData().scaleY;
        font.getData().setScale(prevSx * BODY_SCALE, prevSy * BODY_SCALE);
        font.setColor(0.82f, 0.78f, 0.72f, 1f);
        if (this.cachedBody == null || this.cachedBodyWidth != textW) {
            this.cachedBody = new GlyphLayout(font, TERMS_TEXT, new Color(0.82f, 0.78f, 0.72f, 1f),
                    textW, Align.left, true);
            this.bodyTextHeight = this.cachedBody.height;
            this.cachedBodyWidth = textW;
        }
        this.maxScroll = Math.max(0f, this.bodyTextHeight - innerH);
        this.scrolledToBottom = this.maxScroll <= 0f || this.scrollOffset >= this.maxScroll - 2f;

        batch.flush();
        final Rectangle scissor = new Rectangle(
                this.bodyX, OpenRealmGame.height - (this.bodyY + this.bodyH), this.bodyW, this.bodyH);
        ScissorStack.pushScissors(scissor);
        font.draw(batch, this.cachedBody, textX, textY - this.scrollOffset);
        batch.flush();
        ScissorStack.popScissors();
        font.getData().setScale(prevSx, prevSy);
        font.setColor(Color.WHITE);

        // Scroll thumb.
        if (this.maxScroll > 0f) {
            final float trackH = this.bodyH - 8f;
            final float thumbH = Math.max(24f, trackH * (innerH / this.bodyTextHeight));
            final float thumbY = this.bodyY + 4f + (this.scrollOffset / this.maxScroll) * (trackH - thumbH);
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.55f, 0.45f, 0.25f, 0.85f);
            shapes.rect(this.bodyX + this.bodyW - 6, thumbY, 4, thumbH);
            shapes.end();
            batch.begin();
        }

        // "Scroll down" hint until they've read to the end.
        if (!this.scrolledToBottom) {
            font.setColor(0.78f, 0.66f, 0.43f, 1f);
            this.drawCenteredText(batch, font, "Scroll down to read all terms", cx, this.btnY - 18);
            font.setColor(Color.WHITE);
        }

        // Buttons.
        this.drawButton(batch, shapes, font, this.declineX, this.btnY, this.btnW, this.btnH,
                "Decline & Log Out", false, false);
        this.drawButton(batch, shapes, font, this.agreeX, this.btnY, this.btnW, this.btnH,
                "I Agree", true, !this.scrolledToBottom);
    }

    private void agree() {
        if (this.busy) return;
        this.busy = true;
        new Thread(() -> {
            try {
                this.svc.acceptTerms();
            } catch (Exception e) {
                // If the stamp fails they'll simply be re-prompted next login;
                // don't block entering the game on an infra hiccup.
                log.warn("[TERMS] accept POST failed: {}", e.getMessage());
            } finally {
                this.acceptFinished.set(true);
            }
        }, "openrealm-terms-accept").start();
    }

    private void decline() {
        if (this.transitioned) return;
        this.transitioned = true;
        this.gsm.pop(GameStateManager.TERMS);
        this.gsm.add(GameStateManager.LOGIN, new LoginState(this.gsm));
    }

    private void goToCharacterSelect() {
        if (this.transitioned) return;
        this.transitioned = true;
        this.gsm.pop(GameStateManager.TERMS);
        this.gsm.add(GameStateManager.CHARSELECT, new CharacterSelectState(this.gsm, this.account));
    }

    // ---- Rendering helpers (kept identical to LoginState so centering matches) ----

    private void drawCenteredText(SpriteBatch batch, BitmapFont font, String s, float cx, float topY) {
        final GlyphLayout layout = new GlyphLayout(font, s);
        font.draw(batch, s, cx - layout.width / 2f, topY);
    }

    private void drawButton(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                            int x, int y, int w, int h, String label, boolean primary, boolean disabled) {
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (disabled) shapes.setColor(0.20f, 0.18f, 0.20f, 1f);
        else if (primary) shapes.setColor(0.55f, 0.40f, 0.18f, 1f);
        else shapes.setColor(0.20f, 0.18f, 0.22f, 1f);
        shapes.rect(x, y, w, h);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.78f, 0.66f, 0.43f, 1f);
        shapes.rect(x, y, w, h);
        shapes.end();
        batch.begin();
        font.setColor(disabled ? Color.LIGHT_GRAY : Color.WHITE);
        final GlyphLayout layout = new GlyphLayout(font, label);
        final float textX = x + (w - layout.width) / 2f;
        final float textY = y + (h - layout.height) / 2f;
        font.draw(batch, label, textX, textY);
        font.setColor(Color.WHITE);
    }

    private boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
