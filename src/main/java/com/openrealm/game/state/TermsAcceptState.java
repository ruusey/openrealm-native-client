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
import com.openrealm.game.ui.UiRender;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * One-time Terms-of-Use gate between LoginState and CharacterSelectState. Fails
 * CLOSED: the player only passes through when the data service confirms the
 * current Terms version is accepted (or records a fresh acceptance). "I Agree"
 * unlocks only after scrolling to the bottom; declining returns to LoginState.
 * Transitions run on the GL thread; network calls flip atomics from workers.
 */
@Slf4j
public class TermsAcceptState extends GameState {

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

    // null = still checking; TRUE = must accept; FALSE = already accepted.
    private final AtomicReference<Boolean> checkResult = new AtomicReference<>(null);
    // Fail CLOSED: a failed status check blocks with a Back-to-Login button.
    private final AtomicBoolean checkFailed = new AtomicBoolean(false);
    private final AtomicBoolean acceptFinished = new AtomicBoolean(false);
    private volatile boolean acceptOk = false;
    private String error = null;
    private boolean shown = false;
    private boolean busy = false;
    private boolean transitioned = false;

    private float scrollOffset = 0f;
    private float maxScroll = 0f;
    private boolean scrolledToBottom = false;
    private boolean prevMouseDown = false;

    private GlyphLayout cachedBody = null;
    private float bodyTextHeight = 0f;
    private float cachedBodyWidth = -1f;

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
                log.warn("[TERMS] status check failed, blocking: {}", e.getMessage());
                this.checkFailed.set(true); // fail closed
            }
        }, "openrealm-terms-check").start();
    }

    private void recomputeLayout() {
        this.cardW = Math.min(640, OpenRealmGame.width - 40);
        this.cardH = Math.min(720, OpenRealmGame.height - 40);
        this.cardX = (OpenRealmGame.width - this.cardW) / 2;
        this.cardY = (OpenRealmGame.height - this.cardH) / 2;

        final int pad = 24;
        final int btnAreaH = 100; // bottom hint + buttons
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
        if (!this.shown && !this.checkFailed.get()) {
            final Boolean r = this.checkResult.get();
            if (r != null) {
                if (Boolean.TRUE.equals(r)) this.shown = true;
                else this.goToCharacterSelect();
            }
        }
        if (this.acceptFinished.compareAndSet(true, false)) {
            this.busy = false;
            if (this.acceptOk) this.goToCharacterSelect();
            else this.error = "Could not record your acceptance. Please try again.";
        }
    }

    @Override
    public void input(MouseHandler mouse, KeyHandler key) {
        if (this.transitioned) return;
        if (this.checkFailed.get()) {
            final boolean md = mouse.isPressed(1);
            final boolean jc = md && !this.prevMouseDown;
            this.prevMouseDown = md;
            final int bw = 220, bh = 46;
            final int bx = (OpenRealmGame.width - bw) / 2;
            final int by = OpenRealmGame.height / 2 + 24;
            if (jc && this.hit(mouse.getX(), mouse.getY(), bx, by, bw, bh)) this.decline();
            return;
        }
        if (!this.shown || this.busy) {
            this.prevMouseDown = mouse.isPressed(1);
            return;
        }
        this.recomputeLayout();

        final float wheel = KeyHandler.consumeScroll();
        if (wheel != 0f) this.scrollOffset += wheel * SCROLL_STEP;
        final float page = this.bodyH - 28;
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_DOWN)) this.scrollOffset += page;
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_UP))   this.scrollOffset -= page;
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN))      this.scrollOffset += 48f;
        if (Gdx.input.isKeyJustPressed(Input.Keys.UP))        this.scrollOffset -= 48f;
        this.scrollOffset = Math.max(0f, Math.min(this.maxScroll, this.scrollOffset));

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

        UiRender.fillRect(batch, shapes, 0, 0, OpenRealmGame.width, OpenRealmGame.height,
                new Color(0.10f, 0.07f, 0.10f, 1f));

        if (this.checkFailed.get()) {
            final float mcx = OpenRealmGame.width / 2f;
            font.setColor(0.95f, 0.55f, 0.45f, 1f);
            UiRender.drawCentered(batch, font, "Could not verify Terms of Use.", mcx, OpenRealmGame.height / 2f - 40);
            font.setColor(0.70f, 0.66f, 0.60f, 1f);
            UiRender.drawCentered(batch, font, "Please return to login and try again.", mcx, OpenRealmGame.height / 2f - 14);
            font.setColor(Color.WHITE);
            final int bw = 220, bh = 46;
            this.drawButton(batch, shapes, font, (OpenRealmGame.width - bw) / 2,
                    OpenRealmGame.height / 2 + 24, bw, bh, "Back to Login", true, false);
            return;
        }
        if (!this.shown) {
            font.setColor(0.78f, 0.66f, 0.43f, 1f);
            UiRender.drawCentered(batch, font, "Loading...",
                    OpenRealmGame.width / 2f, OpenRealmGame.height / 2f);
            font.setColor(Color.WHITE);
            return;
        }

        UiRender.panel(batch, shapes, this.cardX, this.cardY, this.cardW, this.cardH,
                new Color(0.16f, 0.13f, 0.16f, 1f), new Color(0.30f, 0.25f, 0.30f, 1f));
        UiRender.panel(batch, shapes, this.bodyX, this.bodyY, this.bodyW, this.bodyH,
                new Color(0.12f, 0.09f, 0.13f, 1f), new Color(0.30f, 0.25f, 0.30f, 1f));

        final float cx = this.cardX + this.cardW / 2f;
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        UiRender.drawCentered(batch, font, "Terms of Use & Privacy Policy", cx, this.cardY + 24);
        font.setColor(0.55f, 0.50f, 0.45f, 1f);
        UiRender.drawCentered(batch, font, "Read to the bottom to enable \"I Agree\".", cx, this.cardY + 50);

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

        if (this.maxScroll > 0f) {
            final float trackH = this.bodyH - 8f;
            final float thumbH = Math.max(24f, trackH * (innerH / this.bodyTextHeight));
            final float thumbY = this.bodyY + 4f + (this.scrollOffset / this.maxScroll) * (trackH - thumbH);
            UiRender.fillRect(batch, shapes, this.bodyX + this.bodyW - 6, thumbY, 4, thumbH,
                    new Color(0.55f, 0.45f, 0.25f, 0.85f));
        }

        if (!this.scrolledToBottom) {
            font.setColor(0.78f, 0.66f, 0.43f, 1f);
            UiRender.drawCentered(batch, font, "Scroll down to read all terms", cx, this.btnY - 18);
            font.setColor(Color.WHITE);
        }

        if (this.error != null) {
            font.setColor(0.95f, 0.45f, 0.45f, 1f);
            UiRender.drawCentered(batch, font, this.error, cx, this.btnY - 18);
            font.setColor(Color.WHITE);
        }

        this.drawButton(batch, shapes, font, this.declineX, this.btnY, this.btnW, this.btnH,
                "Decline & Log Out", false, false);
        this.drawButton(batch, shapes, font, this.agreeX, this.btnY, this.btnW, this.btnH,
                "I Agree", true, !this.scrolledToBottom);
    }

    private void agree() {
        if (this.busy) return;
        this.busy = true;
        this.error = null;
        new Thread(() -> {
            try {
                this.svc.acceptTerms();
                this.acceptOk = true;
            } catch (Exception e) {
                log.warn("[TERMS] accept POST failed, blocking: {}", e.getMessage());
                this.acceptOk = false; // fail closed
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

    private void drawButton(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                            int x, int y, int w, int h, String label, boolean primary, boolean disabled) {
        Color fill = disabled ? new Color(0.20f, 0.18f, 0.20f, 1f)
                : primary ? new Color(0.55f, 0.40f, 0.18f, 1f)
                : new Color(0.20f, 0.18f, 0.22f, 1f);
        UiRender.panel(batch, shapes, x, y, w, h, fill, new Color(0.78f, 0.66f, 0.43f, 1f));
        font.setColor(disabled ? Color.LIGHT_GRAY : Color.WHITE);
        UiRender.drawCenteredIn(batch, font, label, x, y, w, h);
        font.setColor(Color.WHITE);
    }

    private boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
