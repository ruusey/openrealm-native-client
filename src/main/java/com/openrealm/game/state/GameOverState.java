package com.openrealm.game.state;

import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.SessionStore;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.Gdx;

/**
 * Death overlay. Mirrors the web client's {@code death-overlay} flow: the
 * player can return to character select to pick a different character or
 * quit the launcher. The legacy "ENTER restarts the same character" path
 * is gone — once a character is dead the server has soft-deleted them so
 * restarting them is meaningless.
 */
@Slf4j
public class GameOverState extends GameState {

    private boolean prevMouseDown = false;

    // WHY: returnToCharSelect used to do a synchronous svc.getAccount() on
    // the GL thread. HttpClient.newHttpClient() ships with NO request
    // timeout, so a slow/unreachable data service hung the renderer
    // indefinitely and the user was stuck on a frozen "GAME OVER" overlay.
    // Same staged-teardown approach as PauseState.
    private boolean returnPending = false;
    private final AtomicReference<PlayerAccountDto> returnAcctResult = new AtomicReference<>();
    private final AtomicReference<String> returnError = new AtomicReference<>();
    private volatile boolean returnWorkerDone = false;

    public GameOverState(GameStateManager gsm) {
        super(gsm);
    }

    @Override
    public void update(double time) {
        if (this.returnPending && this.returnWorkerDone) {
            this.returnPending = false;
            this.returnWorkerDone = false;
            PlayerAccountDto acct = this.returnAcctResult.getAndSet(null);
            String err = this.returnError.getAndSet(null);
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
        if (this.returnPending) return;
        key.escape.tick();
        key.enter.tick();

        // Layout matches render() — buttons centered horizontally, stacked.
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
        if (this.returnPending) return;
        this.returnPending = true;
        this.returnWorkerDone = false;
        ClientGameLogic.GAME_OVER = false;
        final SessionStore store = SessionStore.get();
        final OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        // Tear down the live socket immediately; the slot swap waits for
        // the account refresh worker so the GL thread doesn't block.
        try {
            PlayState play = this.gsm.getPlayState();
            if (play != null && play.getRealmManager() != null) {
                play.getRealmManager().shutdownClient();
            }
        } catch (Exception e) {
            log.warn("Failed to shut down realm manager on game-over: {}", e.getMessage());
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
        }, "openrealm-gameover-return").start();
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
        font.draw(batch, "GAME OVER", OpenRealmGame.width / 2f - 60, OpenRealmGame.height / 2f - 32);
        if (this.returnPending) {
            font.setColor(0.78f, 0.66f, 0.43f, 1f);
            font.draw(batch, "Returning to character select...",
                    OpenRealmGame.width / 2f - 150, OpenRealmGame.height / 2f - 8);
        }
        font.setColor(Color.WHITE);
        font.draw(batch, "Your character has fallen. Choose your next path.",
                OpenRealmGame.width / 2f - 220, OpenRealmGame.height / 2f);

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
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (primary) shapes.setColor(0.55f, 0.40f, 0.18f, 1f);
        else shapes.setColor(0.20f, 0.18f, 0.22f, 1f);
        shapes.rect(x, y, w, h);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.78f, 0.66f, 0.43f, 1f);
        shapes.rect(x, y, w, h);
        shapes.end();
        batch.begin();
        font.setColor(Color.WHITE);
        font.draw(batch, label, x + (w / 2f) - (label.length() * 4f), y + h * 0.65f);
    }
}
