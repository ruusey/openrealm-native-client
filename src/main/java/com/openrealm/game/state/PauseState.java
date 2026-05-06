package com.openrealm.game.state;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

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
        // Layout: rows drawn in render() at the same coordinates we use for
        // hit-testing here. LibGDX mouse Y is screen-space (Y=0 at top), and
        // the UI camera in OpenRealmGame is Y-up, so we flip Y to match the
        // row positions used by render().
        final int rowHeight = 100;
        final int rowWidth = 500;
        // "Return to Character Select" button — drawn bottom-right in render().
        final int btnW = 280;
        final int btnH = 44;
        final int btnX = OpenRealmGame.width - btnW - 16;
        final int btnY = 56; // Y-up world coords
        final int mx = mouse.getX();
        final int myWorld = OpenRealmGame.height - mouse.getY();

        if (mouse.isPressed(1)) {
            if (mx >= btnX && mx <= btnX + btnW && myWorld >= btnY && myWorld <= btnY + btnH) {
                if (!this.characterSwitchRequested) {
                    this.characterSwitchRequested = true;
                    this.returnToCharSelect();
                }
                return;
            }
            // Character row click: rows are stacked from y=0 upward, row i
            // occupies [i*rowHeight, (i+1)*rowHeight) in world Y. Constrain
            // to the row's actual horizontal width too — without this, any
            // click on the screen at a matching Y row triggered a swap.
            if (mx >= 0 && mx <= rowWidth) {
                int idx = myWorld / rowHeight;
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
        ClientGameLogic.GAME_OVER = false;
        SessionStore store = SessionStore.get();
        OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        try {
            PlayState play = this.gsm.getPlayState();
            if (play != null && play.getRealmManager() != null) {
                play.getRealmManager().shutdownClient();
            }
        } catch (Exception e) {
            log.warn("Failed to shut down realm manager on pause exit: {}", e.getMessage());
        }
        this.gsm.pop(GameStateManager.PLAY);
        this.gsm.pop(GameStateManager.PAUSE);

        if (store.hasSession() && svc != null && svc.getSessionToken() != null) {
            try {
                PlayerAccountDto acct = svc.getAccount(store.getAccountGuid());
                this.gsm.add(GameStateManager.CHARSELECT, new CharacterSelectState(this.gsm, acct));
                return;
            } catch (Exception e) {
                log.warn("Failed to refresh account on pause exit, falling back to login: {}", e.getMessage());
            }
        }
        this.gsm.add(GameStateManager.LOGIN, new LoginState(this.gsm));
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

        int i = 0;
        int rowHeight = 100;
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

                // Draw character row background
                batch.end();
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                shapes.setColor(Color.GRAY);
                shapes.rect(0, i * rowHeight, 500, rowHeight);
                shapes.end();
                batch.begin();

                // Draw class sprite
                final SpriteSheet classImg = GameSpriteManager.loadClassSprites(characterClass);
                TextureRegion frame = classImg.getCurrentFrame();
                if (frame != null) {
                    batch.draw(frame, 0, i * rowHeight, 64, 64);
                }

                font.setColor(Color.WHITE);
                font.draw(batch, characterStr, 100, 32 + (rowHeight * i));
                i++;
            }
        }

        // Top-right leaderboard
        int lbW = 280;
        int lbH = 250;
        int lbX = OpenRealmGame.width - lbW - 16;
        int lbY = OpenRealmGame.height - lbH - 16;
        this.leaderboard.render(batch, shapes, font, lbX, lbY, lbW, lbH);

        // "Return to Character Select" button (bottom-right). Coordinates
        // must match the hit-test in input().
        int btnW = 280;
        int btnH = 44;
        int btnX = OpenRealmGame.width - btnW - 16;
        int btnY = 56;
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

        // Bottom hint
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "Press V for Vault", OpenRealmGame.width - 180, 24);

        // Vault overlay (centered modal) — drawn last so it sits on top
        this.vault.render(batch, shapes, font);
    }
}
