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
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.math.Vector2f;
import com.openrealm.game.ui.LeaderboardPanel;
import com.openrealm.game.ui.VaultWindow;
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

    @Override
    public void input(MouseHandler mouse, KeyHandler key) {
        // V opens the vault overlay (web-parity char-select feature).
        if (Gdx.input.isKeyJustPressed(Input.Keys.V)) {
            if (this.vault.isVisible()) this.vault.hide(); else this.vault.show();
        }
        // While the vault overlay has focus, suppress the click-to-switch
        // character handling so a click on the vault's "Add Chest" button
        // doesn't accidentally swap characters.
        if (this.vault.isVisible()) return;
        // Character selection via mouse click on character rows
        if (this.account != null && mouse.isPressed(1)) {
            int rowHeight = 100;
            int mouseY = mouse.getY();
            int idx = mouseY / rowHeight;
            if (idx >= 0 && idx < this.account.getCharacters().size()) {
                if (!this.characterSwitchRequested) {
                    CharacterDto cls = this.account.getCharacters().get(idx);
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
            for (CharacterDto cls : this.account.getCharacters()) {
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

        // Bottom hint
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "Press V for Vault", OpenRealmGame.width - 180, 24);

        // Vault overlay (centered modal) — drawn last so it sits on top
        this.vault.render(batch, shapes, font);
    }
}
