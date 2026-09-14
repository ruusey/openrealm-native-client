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
import com.openrealm.game.graphics.SpriteRecolorCache;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.model.AnimationModel;
import com.openrealm.game.ui.LeaderboardPanel;
import com.openrealm.game.ui.UiRender;
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

    private static final float SCROLL_STEP = 60f;
    private static final int CHAR_ROW_HEIGHT = 100;
    private static final int CHAR_LIST_W = 500;

    private PlayerAccountDto account;
    private boolean characterSwitchRequested = false;
    private final LeaderboardPanel leaderboard = new LeaderboardPanel();
    private final VaultWindow vault = new VaultWindow();

    // Staged teardown: the GL thread must not block on the untimed
    // getAccount() HTTP call, so a worker fills these and update() applies the
    // transition next frame.
    private boolean returnPending = false;
    private final AtomicReference<PlayerAccountDto> returnAcctResult = new AtomicReference<>();
    private final AtomicReference<String> returnError = new AtomicReference<>();
    private volatile boolean returnWorkerDone = false;

    private float charScrollOffset = 0f;

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
        if (this.returnPending && this.returnWorkerDone) {
            this.returnPending = false;
            this.returnWorkerDone = false;
            PlayerAccountDto acct = this.returnAcctResult.getAndSet(null);
            String err = this.returnError.getAndSet(null);
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
        if (this.returnPending) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.V)) {
            if (this.vault.isVisible()) this.vault.hide(); else this.vault.show();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.B)) {
            this.returnToCharSelect();
            return;
        }
        // Vault open: swallow clicks so its buttons don't also swap characters.
        if (this.vault.isVisible()) return;

        final List<CharacterDto> alive = this.aliveChars();
        final int rowHeight = CHAR_ROW_HEIGHT;
        final int rowWidth = CHAR_LIST_W;
        // Coordinates must match render() exactly.
        final int btnW = 280;
        final int btnH = 44;
        final int btnX = OpenRealmGame.width - btnW - 16;
        final int btnY = 16;
        final int mx = mouse.getX();
        final int my = mouse.getY();

        // Route the wheel to whichever panel the cursor is over; consume
        // either way so a notch doesn't leak into the next state.
        float wheel = KeyHandler.consumeScroll();
        if (wheel != 0f && this.leaderboard.containsPoint(mx, my)) {
            this.leaderboard.scrollBy(wheel > 0 ? 1 : -1);
        } else if (wheel != 0f && mx >= 0 && mx <= rowWidth) {
            int totalH = alive.size() * rowHeight;
            float maxScroll = Math.max(0f, totalH - OpenRealmGame.height);
            this.charScrollOffset = Math.max(0f,
                    Math.min(maxScroll, this.charScrollOffset + wheel * SCROLL_STEP));
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_DOWN)) {
            this.charScrollOffset += OpenRealmGame.height * 0.5f;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_UP)) {
            this.charScrollOffset -= OpenRealmGame.height * 0.5f;
        }
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
        try {
            PlayState play = this.gsm.getPlayState();
            if (play != null && play.getRealmManager() != null) {
                play.getRealmManager().shutdownClient();
            }
        } catch (Exception e) {
            log.warn("Failed to shut down realm manager on pause exit: {}", e.getMessage());
        }

        if (!(store.hasSession() && svc != null && svc.getSessionToken() != null)) {
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
        UiRender.fillRect(batch, shapes, 0, 0, OpenRealmGame.width, OpenRealmGame.height,
                new Color(0, 0, 0, 0.6f));

        font.setColor(Color.WHITE);
        UiRender.drawCentered(batch, font, "PAUSED - Press ESC to resume",
                OpenRealmGame.width / 2f, OpenRealmGame.height / 2f - 48);
        if (this.returnPending) {
            font.setColor(0.78f, 0.66f, 0.43f, 1f);
            UiRender.drawCentered(batch, font, "Returning to character select...",
                    OpenRealmGame.width / 2f, OpenRealmGame.height / 2f - 16);
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
                if (rowY + rowHeight < 0 || rowY > OpenRealmGame.height) {
                    i++;
                    continue;
                }
                UiRender.fillRect(batch, shapes, 0, rowY, CHAR_LIST_W, rowHeight, Color.GRAY);

                // Apply the character's saved dye so the thumbnail matches in-game.
                final SpriteSheet classImg = GameSpriteManager.loadClassSprites(characterClass);
                TextureRegion frame = classImg.getCurrentFrame();
                if (frame != null) {
                    TextureRegion drawFrame = frame;
                    final Integer dyeIdBoxed = cls.getStats() != null ? cls.getStats().getDyeId() : null;
                    final int dyeId = dyeIdBoxed != null ? dyeIdBoxed : 0;
                    if (dyeId > 0) {
                        final AnimationModel anim =
                                GameDataManager.getAnimation("player", characterClass.classId);
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
                            TextureRegion dyed = SpriteRecolorCache
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

        // Coordinates must match the hit-test in input().
        int btnW = 280;
        int btnH = 44;
        int btnX = OpenRealmGame.width - btnW - 16;
        int btnY = 16;
        UiRender.panel(batch, shapes, btnX, btnY, btnW, btnH,
                new Color(0.55f, 0.40f, 0.18f, 1f), new Color(0.78f, 0.66f, 0.43f, 1f));
        font.setColor(Color.WHITE);
        UiRender.drawCenteredIn(batch, font, "Return to Character Select (B)", btnX, btnY, btnW, btnH);

        int lbW = 360;
        int lbY = btnY + btnH + 12;
        int lbH = Math.max(250, OpenRealmGame.height - lbY - 56);
        int lbX = OpenRealmGame.width - lbW - 16;
        this.leaderboard.render(batch, shapes, font, lbX, lbY, lbW, lbH);

        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "Press V for Vault", lbX + 60, lbY + lbH + 24);

        this.vault.render(batch, shapes, font);
    }
}
