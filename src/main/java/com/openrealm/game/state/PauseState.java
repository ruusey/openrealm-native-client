package com.openrealm.game.state;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.account.dto.CharacterDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.SessionStore;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.ui.LeaderboardPanel;
import com.openrealm.game.ui.VaultWindow;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.net.client.SocketClient;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;

@Slf4j
public class PauseState extends GameState {

    private PlayerAccountDto account;
    private boolean characterSwitchRequested = false;
    // Web-parity additions: top-N leaderboard + account vault. Leaderboard
    // refreshes itself on render. Vault opens via "V" key.
    private final LeaderboardPanel leaderboard = new LeaderboardPanel();
    private final VaultWindow vault = new VaultWindow();

    // WHY: returnToCharSelect used to do a synchronous svc.getAccount() on
    // the GL thread. HttpClient.newHttpClient() ships with NO request
    // timeout, so a slow/unreachable data service would hang the renderer
    // indefinitely — user saw the dark pause overlay frozen forever and
    // described it as a "black screen". The teardown is now staged across
    // frames: input() flips returnPending and kicks off a worker, the
    // worker drops its result into one of these refs, and update() applies
    // the GL-thread state transition on a later frame.
    private boolean returnPending = false;
    private final AtomicReference<PlayerAccountDto> returnAcctResult = new AtomicReference<>();
    private final AtomicReference<String> returnError = new AtomicReference<>();
    /** True once the worker thread has reported back, regardless of outcome. */
    private volatile boolean returnWorkerDone = false;

    /** Pixel scroll offset for the character list on the left of the pause
     *  screen. Without this, accounts with many alive characters got their
     *  later rows clipped off the bottom of the screen with no way to
     *  reach them — the click handler still recognized them but the user
     *  could not see who they were selecting. */
    private float charScrollOffset = 0f;
    private static final float SCROLL_STEP = 60f;
    private static final int CHAR_LIST_HEADER = 0;
    private static final int CHAR_ROW_HEIGHT = 100;
    private static final int CHAR_LIST_W = 500;

    public PauseState(GameStateManager gsm, PlayerAccountDto account) {
        super(gsm);
        this.account = account;
        if (account != null) {
            this.vault.setAccountUuid(account.getAccountUuid());
        }
    }

    @Override
    public void update(double time) {
        this.vault.update();
        // Drain the in-flight char-select transition. We don't push the new
        // state from input() any more (see returnPending field) — the
        // worker thread sets one of these refs and the GL thread applies
        // the slot swap here on the next frame.
        if (this.returnPending && this.returnWorkerDone) {
            this.returnPending = false;
            this.returnWorkerDone = false;
            PlayerAccountDto acct = this.returnAcctResult.getAndSet(null);
            String err = this.returnError.getAndSet(null);
            // Tear down play/pause regardless of which branch we take so the
            // user isn't stuck staring at a stale pause overlay.
            this.gsm.pop(GameStateManager.PLAY);
            this.gsm.pop(GameStateManager.PAUSE);
            if (acct != null) {
                this.gsm.add(GameStateManager.CHARSELECT,
                        new CharacterSelectState(this.gsm, acct));
            } else {
                if (err != null) log.warn("Falling back to login after char-select refresh failed: {}", err);
                this.gsm.add(GameStateManager.LOGIN, new LoginState(this.gsm));
            }
        }
    }

    private List<CharacterDto> aliveChars() {
        List<CharacterDto> out = new ArrayList<>();
        if (this.account == null || this.account.getCharacters() == null) return out;
        for (CharacterDto c : this.account.getCharacters()) {
            if (c != null && !c.isDeleted()) out.add(c);
        }
        return out;
    }

    @Override
    public void input(MouseHandler mouse, KeyHandler key) {
        // Once the char-select transition is in flight, ignore input — every
        // click would otherwise queue another worker or attempt another
        // doLogin against a freshly-shut-down socket.
        if (this.returnPending) return;
        // V opens the vault overlay (web-parity char-select feature).
        if (Gdx.input.isKeyJustPressed(Input.Keys.V)) {
            if (this.vault.isVisible()) this.vault.hide(); else this.vault.show();
        }
        // B returns to the character select screen — same teardown
        // GameOverState uses, so we don't get stranded with a half-alive
        // session if the user wants out without dying.
        if (Gdx.input.isKeyJustPressed(Input.Keys.B)) {
            this.returnToCharSelect();
            return;
        }
        // While the vault overlay has focus, suppress the click-to-switch
        // character handling so a click on the vault's "Add Chest" button
        // doesn't accidentally swap characters.
        if (this.vault.isVisible()) return;

        final List<CharacterDto> alive = this.aliveChars();
        // The UI camera in OpenRealmGame is set up with Y-down (setToOrtho
        // true, ...), so screen-space mouse Y matches render Y directly —
        // no inversion. Earlier code flipped Y to "world" coords, which
        // broke every hit test in this state and made the Return button
        // unclickable.
        final int rowHeight = CHAR_ROW_HEIGHT;
        final int rowWidth = CHAR_LIST_W;
        // "Return to Character Select" button — drawn top-right in render(),
        // below the leaderboard. Coordinates must match render() exactly.
        final int btnW = 280;
        final int btnH = 44;
        final int btnX = OpenRealmGame.width - btnW - 16;
        final int btnY = 16;
        final int mx = mouse.getX();
        final int my = mouse.getY();

        // Scroll wheel routing: leaderboard panel first (right-column),
        // then the character list when the cursor is over the left strip.
        // Drains the buffer either way so a wheel notch doesn't leak into
        // the next state.
        float wheel = KeyHandler.consumeScroll();
        if (wheel != 0f && this.leaderboard.containsPoint(mx, my)) {
            this.leaderboard.scrollBy(wheel > 0 ? 1 : -1);
        } else if (wheel != 0f && mx >= 0 && mx <= rowWidth) {
            int totalH = alive.size() * rowHeight;
            float maxScroll = Math.max(0f, totalH - OpenRealmGame.height);
            this.charScrollOffset = Math.max(0f,
                    Math.min(maxScroll, this.charScrollOffset + wheel * SCROLL_STEP));
        }
        // PageUp/PageDown for keyboard accessibility.
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_DOWN)) {
            this.charScrollOffset += OpenRealmGame.height * 0.5f;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_UP)) {
            this.charScrollOffset -= OpenRealmGame.height * 0.5f;
        }
        // Clamp every frame so Page keys don't escape valid bounds.
        {
            int totalH = alive.size() * rowHeight;
            float maxScroll = Math.max(0f, totalH - OpenRealmGame.height);
            this.charScrollOffset = Math.max(0f, Math.min(maxScroll, this.charScrollOffset));
        }

        if (mouse.isPressed(1)) {
            if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                if (!this.characterSwitchRequested) {
                    this.characterSwitchRequested = true;
                    this.returnToCharSelect();
                }
                return;
            }
            // Character row click: rows stack from y=0 downward in Y-down,
            // row i occupies [i*rowHeight, (i+1)*rowHeight) BEFORE scroll.
            // Add the scroll offset to the click Y to map screen → list space.
            if (mx >= 0 && mx <= rowWidth) {
                int idx = (my + (int) this.charScrollOffset) / rowHeight;
                if (idx >= 0 && idx < alive.size() && !this.characterSwitchRequested) {
                    CharacterDto cls = alive.get(idx);
                    CharacterClass characterClass = CharacterClass.valueOf(cls.getCharacterClass());
                    log.info("Character button clicked for {} {}", characterClass, cls.getCharacterUuid());
                    SocketClient.CHARACTER_UUID = cls.getCharacterUuid();
                    try {
                        this.gsm.getPlayState().getRealmManager().getRealm().clearData();
                        this.gsm.getPlayState().doLogin();
                    } catch (Exception e) {
                        log.error("Failed to perform character switch login. Reason: {}", e);
                    }
                    this.gsm.pop(GameStateManager.PAUSE);
                    this.gsm.add(GameStateManager.PLAY);
                    this.characterSwitchRequested = true;
                }
            }
        }
    }

    private void returnToCharSelect() {
        if (this.returnPending) return;
        this.returnPending = true;
        this.returnWorkerDone = false;
        ClientGameLogic.GAME_OVER = false;
        final SessionStore store = SessionStore.get();
        final OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        // Tear the socket down immediately so the dying connection doesn't
        // outlive the user's intent — the actual slot swap happens in
        // update() once the account refresh worker reports back.
        try {
            PlayState play = this.gsm.getPlayState();
            if (play != null && play.getRealmManager() != null) {
                play.getRealmManager().shutdownClient();
            }
        } catch (Exception e) {
            log.warn("Failed to shut down realm manager on pause exit: {}", e.getMessage());
        }

        if (!(store.hasSession() && svc != null && svc.getSessionToken() != null)) {
            // No session to refresh — short-circuit to login on the next tick.
            this.returnAcctResult.set(null);
            this.returnError.set("no-session");
            this.returnWorkerDone = true;
            return;
        }

        new Thread(() -> {
            try {
                PlayerAccountDto acct = svc.getAccount(store.getAccountGuid());
                this.returnAcctResult.set(acct);
            } catch (Exception e) {
                this.returnError.set(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                this.returnWorkerDone = true;
            }
        }, "openrealm-pause-return").start();
    }

    @Override
    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        // Semi-transparent overlay
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0, 0, 0, 0.6f);
        shapes.rect(0, 0, OpenRealmGame.width, OpenRealmGame.height);
        shapes.end();
        batch.begin();

        font.setColor(Color.WHITE);
        font.draw(batch, "PAUSED - Press ESC to resume", OpenRealmGame.width / 2f - 150, OpenRealmGame.height / 2f - 48);
        if (this.returnPending) {
            font.setColor(0.78f, 0.66f, 0.43f, 1f);
            font.draw(batch, "Returning to character select...",
                    OpenRealmGame.width / 2f - 150, OpenRealmGame.height / 2f - 16);
            font.setColor(Color.WHITE);
        }

        int i = 0;
        int rowHeight = CHAR_ROW_HEIGHT;
        final int scrollPx = (int) this.charScrollOffset;
        if (this.account != null) {
            for (CharacterDto cls : this.aliveChars()) {
                final CharacterClass characterClass = CharacterClass.valueOf(cls.getCharacterClass());
                int lvl;
                if (GameDataManager.EXPERIENCE_LVLS.isMaxLvl(cls.getStats().getXp())) {
                    lvl = 20;
                } else {
                    lvl = GameDataManager.EXPERIENCE_LVLS.getLevel(cls.getStats().getXp());
                }

                String characterStr = "{0}, lv {1} {2} {3}/8";
                characterStr = MessageFormat.format(characterStr, this.account.getAccountName(), lvl, characterClass,
                        cls.numStatsMaxed());

                final int rowY = i * rowHeight - scrollPx;
                // Skip rows fully above or below the visible viewport — no
                // point allocating draws for off-screen entries on accounts
                // with many characters.
                if (rowY + rowHeight < 0 || rowY > OpenRealmGame.height) {
                    i++;
                    continue;
                }
                // Draw character row background
                batch.end();
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                shapes.setColor(Color.GRAY);
                shapes.rect(0, rowY, CHAR_LIST_W, rowHeight);
                shapes.end();
                batch.begin();

                // Draw class sprite — apply the character's saved dye so
                // the pause-menu thumbnail matches the in-game appearance
                // (web-parity).
                final SpriteSheet classImg = GameSpriteManager.loadClassSprites(characterClass);
                TextureRegion frame = classImg.getCurrentFrame();
                if (frame != null) {
                    TextureRegion drawFrame = frame;
                    final Integer dyeIdBoxed = cls.getStats() != null ? cls.getStats().getDyeId() : null;
                    final int dyeId = dyeIdBoxed != null ? dyeIdBoxed : 0;
                    if (dyeId > 0) {
                        final com.openrealm.game.model.AnimationModel anim =
                                GameDataManager.ANIMATIONS != null
                                        ? GameDataManager.ANIMATIONS.get(characterClass.classId) : null;
                        if (anim != null) {
                            final int spW = anim.getSpriteSize() > 0 ? anim.getSpriteSize() : 8;
                            final int spH = anim.getEffectiveSpriteHeight() > 0
                                    ? anim.getEffectiveSpriteHeight() : spW;
                            final int spX = frame.isFlipX()
                                    ? frame.getRegionX() - frame.getRegionWidth()
                                    : frame.getRegionX();
                            final int spY = frame.isFlipY()
                                    ? frame.getRegionY() - frame.getRegionHeight()
                                    : frame.getRegionY();
                            final int row = spY / spH;
                            final int col = spX / spW;
                            TextureRegion dyed = com.openrealm.game.graphics.SpriteRecolorCache
                                    .getDyedRegion(anim.getSpriteKey(), characterClass.classId,
                                            row, col, spW, dyeId);
                            if (dyed != null) drawFrame = dyed;
                        }
                    }
                    batch.draw(drawFrame, 0, rowY, 64, 64);
                }

                font.setColor(Color.WHITE);
                font.draw(batch, characterStr, 100, rowY + 32);
                i++;
            }
        }

        // "Return to Character Select" button — placed at top-right where
        // the user can always see it. Coordinates must exactly match the
        // hit-test in input(); the UI camera is Y-down so y=16 is near
        // the top of the screen.
        int btnW = 280;
        int btnH = 44;
        int btnX = OpenRealmGame.width - btnW - 16;
        int btnY = 16;
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.55f, 0.40f, 0.18f, 1f);
        shapes.rect(btnX, btnY, btnW, btnH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.78f, 0.66f, 0.43f, 1f);
        shapes.rect(btnX, btnY, btnW, btnH);
        shapes.end();
        batch.begin();
        font.setColor(Color.WHITE);
        String label = "Return to Character Select (B)";
        font.draw(batch, label, btnX + (btnW / 2f) - (label.length() * 4f), btnY + btnH * 0.65f);

        // Leaderboard — drawn under the button so it doesn't overlap.
        // Width bumped from 280 → 360 so account-name + class + level
        // doesn't ellipsize aggressively at common name lengths, and
        // height now scales with the screen so additional rows are
        // visible without scrolling on large displays. Mouse wheel
        // scrolls when more rows exist than fit.
        int lbW = 360;
        int lbY = btnY + btnH + 12;
        int lbH = Math.max(250, OpenRealmGame.height - lbY - 56);
        int lbX = OpenRealmGame.width - lbW - 16;
        this.leaderboard.render(batch, shapes, font, lbX, lbY, lbW, lbH);

        // "Press V for Vault" hint — drawn just below the leaderboard so
        // it sits in a clear area instead of being clipped at the corner.
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "Press V for Vault", lbX + 60, lbY + lbH + 24);

        // Vault overlay (centered modal) — drawn last so it sits on top
        this.vault.render(batch, shapes, font);
    }
}
