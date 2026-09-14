package com.openrealm.game.state;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.SessionStore;
import com.openrealm.game.ui.UiRender;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.Gdx;

@Slf4j
public class GameOverState extends GameState {

    private boolean prevMouseDown = false;

    private final CharacterSelectReturn charSelectReturn = new CharacterSelectReturn();

    public GameOverState(GameStateManager gsm) {
        super(gsm);
    }

    @Override
    public void update(double time) {
        CharacterSelectReturnResult result = this.charSelectReturn.poll();
        if (result != null) {
            PlayerAccountDto acct = result.getAccount();
            String err = result.getError();
            this.gsm.pop(GameStateManager.PLAY);
            this.gsm.pop(GameStateManager.PAUSE);
            this.gsm.pop(GameStateManager.GAMEOVER);
            if (acct != null) {
                this.gsm.add(GameStateManager.CHARSELECT,
                        new CharacterSelectState(this.gsm, acct));
            } else {
                if (err != null) log.warn("Falling back to login after game-over refresh failed: {}", err);
                this.gsm.add(GameStateManager.LOGIN, new LoginState(this.gsm));
            }
        }
    }

    @Override
    public void input(MouseHandler mouse, KeyHandler key) {
        if (this.charSelectReturn.isPending()) return;
        key.escape.tick();
        key.enter.tick();

        // Layout must match render().
        int w = OpenRealmGame.width;
        int h = OpenRealmGame.height;
        int btnW = 280;
        int btnH = 44;
        int charBtnX = w / 2 - btnW / 2;
        int charBtnY = h / 2 + 24;
        int quitBtnX = w / 2 - btnW / 2;
        int quitBtnY = charBtnY + btnH + 12;

        boolean mouseDown = mouse.isPressed(1);
        boolean justClicked = mouseDown && !this.prevMouseDown;
        this.prevMouseDown = mouseDown;

        if (justClicked) {
            int mx = mouse.getX();
            int my = mouse.getY();
            if (mx >= charBtnX && mx <= charBtnX + btnW && my >= charBtnY && my <= charBtnY + btnH) {
                this.returnToCharSelect();
                return;
            }
            if (mx >= quitBtnX && mx <= quitBtnX + btnW && my >= quitBtnY && my <= quitBtnY + btnH) {
                System.exit(0);
            }
        }

        if (key.enter.clicked) {
            this.returnToCharSelect();
        }
        if (key.escape.clicked) {
            System.exit(0);
        }
    }

    private void returnToCharSelect() {
        if (this.charSelectReturn.isPending()) return;
        ClientGameLogic.GAME_OVER = false;
        final SessionStore store = SessionStore.get();
        final OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        this.charSelectReturn.begin(svc, store, "openrealm-gameover-return", () -> {
            try {
                PlayState play = this.gsm.getPlayState();
                if (play != null && play.getRealmManager() != null) {
                    play.getRealmManager().shutdownClient();
                }
            } catch (Exception e) {
                log.warn("Failed to shut down realm manager on game-over: {}", e.getMessage());
            }
        });
    }

    @Override
    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.85f);
        shapes.rect(0, 0, OpenRealmGame.width, OpenRealmGame.height);
        shapes.end();
        batch.begin();

        font.setColor(Color.RED);
        UiRender.drawCentered(batch, font, "GAME OVER", OpenRealmGame.width / 2f, OpenRealmGame.height / 2f - 32);
        if (this.charSelectReturn.isPending()) {
            font.setColor(0.78f, 0.66f, 0.43f, 1f);
            UiRender.drawCentered(batch, font, "Returning to character select...",
                    OpenRealmGame.width / 2f, OpenRealmGame.height / 2f - 8);
        }
        font.setColor(Color.WHITE);
        UiRender.drawCentered(batch, font, "Your character has fallen. Choose your next path.",
                OpenRealmGame.width / 2f, OpenRealmGame.height / 2f);

        int w = OpenRealmGame.width;
        int h = OpenRealmGame.height;
        int btnW = 280;
        int btnH = 44;
        int charBtnX = w / 2 - btnW / 2;
        int charBtnY = h / 2 + 24;
        int quitBtnX = w / 2 - btnW / 2;
        int quitBtnY = charBtnY + btnH + 12;

        this.drawButton(batch, shapes, font, charBtnX, charBtnY, btnW, btnH, "Select Character (Enter)", true);
        this.drawButton(batch, shapes, font, quitBtnX, quitBtnY, btnW, btnH, "Quit (Esc)", false);
    }

    private void drawButton(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                            int x, int y, int w, int h, String label, boolean primary) {
        Color fill = primary ? new Color(0.55f, 0.40f, 0.18f, 1f) : new Color(0.20f, 0.18f, 0.22f, 1f);
        UiRender.panel(batch, shapes, x, y, w, h, fill, new Color(0.78f, 0.66f, 0.43f, 1f));
        font.setColor(Color.WHITE);
        UiRender.drawCenteredIn(batch, font, label, x, y, w, h);
    }
}
