package com.openrealm.game.state;

import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.openrealm.account.dto.AccountDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.account.dto.SessionTokenDto;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.GameLauncher;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.SessionStore;
import com.openrealm.game.ui.TextField;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.net.client.SocketClient;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * Account login / registration / guest screen — replaces the legacy
 * JOptionPane sequence in {@link GameLauncher}. Visually mirrors the web
 * client's {@code login-screen} (email + password, register link, guest
 * button, server selector, Discord link).
 *
 * Network calls are dispatched to a worker thread so the UI stays
 * responsive — the LibGDX render loop must not block on HTTP. Results are
 * surfaced through atomic refs and applied on the next frame.
 *
 * On success, swaps itself out for {@link CharacterSelectState}. The
 * caller (GameStateManager.add) handles state slot management.
 */
@Slf4j
public class LoginState extends GameState {

    private enum Mode { LOGIN, REGISTER }

    private static final String[] SERVERS = { "useast", "local", "localhost" };

    private Mode mode = Mode.LOGIN;
    private final TextField emailField;
    private final TextField passwordField;
    private final TextField nameField; // register-only
    private int serverIdx = 0;
    private String error = "";
    private boolean busy = false;
    private boolean autoLoginAttempted = false;
    /** Result of an in-flight network call; consumed on the next render frame. */
    private final AtomicReference<PlayerAccountDto> loginResult = new AtomicReference<>();
    private final AtomicReference<String> loginError = new AtomicReference<>();
    /** Once non-null, swap this LoginState out for the new CharacterSelectState. */
    private PlayerAccountDto pendingHandoff = null;

    private boolean prevMouseDown = false;

    public LoginState(GameStateManager gsm) {
        super(gsm);
        // Bind the typed-char sink so any focused TextField receives keys.
        KeyHandler.textSink = this::onChar;
        this.emailField = new TextField(0, 0, 360, 36);
        this.emailField.setPlaceholder("you@example.com");
        this.passwordField = new TextField(0, 0, 360, 36);
        this.passwordField.setPlaceholder("password");
        this.passwordField.setPassword(true);
        this.nameField = new TextField(0, 0, 360, 36);
        this.nameField.setPlaceholder("Username");

        SessionStore store = SessionStore.get();
        if (store.getRememberEmail() != null) this.emailField.setText(store.getRememberEmail());
        if (store.getLastServer() != null) {
            for (int i = 0; i < SERVERS.length; i++) {
                if (SERVERS[i].equals(store.getLastServer())) { this.serverIdx = i; break; }
            }
        }
    }

    private void onChar(char c) {
        if (this.emailField.isFocused()) this.emailField.appendChar(c);
        else if (this.passwordField.isFocused()) this.passwordField.appendChar(c);
        else if (this.mode == Mode.REGISTER && this.nameField.isFocused()) this.nameField.appendChar(c);
    }

    /** Try the persisted token before showing the login form. */
    private void tryAutoLogin() {
        this.autoLoginAttempted = true;
        SessionStore store = SessionStore.get();
        if (!store.hasSession()) return;
        OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        svc.setSessionToken(store.getToken());
        this.busy = true;
        new Thread(() -> {
            try {
                AccountDto authed = svc.getMyAccount();
                if (authed == null || authed.getAccountGuid() == null) throw new Exception("Token invalid");
                PlayerAccountDto acct = svc.getAccount(authed.getAccountGuid());
                this.loginResult.set(acct);
            } catch (Exception e) {
                log.info("[LOGIN] persisted token failed validation: {} — clearing", e.getMessage());
                store.clearSession();
                svc.setSessionToken(null);
                this.loginError.set(null); // silent — fall through to login form
            }
        }, "openrealm-autologin").start();
    }

    @Override
    public void update(double time) {
        if (!this.autoLoginAttempted) {
            this.tryAutoLogin();
        }
        // Drain any pending background result.
        PlayerAccountDto pending = this.loginResult.getAndSet(null);
        if (pending != null) {
            this.busy = false;
            this.pendingHandoff = pending;
        }
        String err = this.loginError.getAndSet(null);
        if (err != null) {
            this.busy = false;
            this.error = err;
        }
        if (this.pendingHandoff != null) {
            // Hand off to character select on the GL thread.
            PlayerAccountDto acct = this.pendingHandoff;
            this.pendingHandoff = null;
            KeyHandler.textSink = null;
            applyServerSelection(SERVERS[this.serverIdx]);
            this.gsm.pop(GameStateManager.LOGIN);
            CharacterSelectState charSelect = new CharacterSelectState(this.gsm, acct);
            this.gsm.add(GameStateManager.CHARSELECT, charSelect);
            return;
        }

        // Caret blink + keyboard-driven editing
        boolean enterEmail = this.emailField.update();
        boolean enterPassword = this.passwordField.update();
        boolean enterName = this.mode == Mode.REGISTER && this.nameField.update();
        if (enterEmail || enterPassword || enterName) {
            // Enter from any field submits the form.
            this.submit();
        }
    }

    @Override
    public void input(MouseHandler mouse, KeyHandler key) {
        if (this.busy) return;

        // Layout: card at center
        int cardW = 480;
        int cardH = this.mode == Mode.REGISTER ? 600 : 540;
        int cardX = (OpenRealmGame.width - cardW) / 2;
        int cardY = (OpenRealmGame.height - cardH) / 2;
        int padX = cardX + 60;
        int fieldW = cardW - 120;
        int curY = cardY + 96;

        // Tab bar
        int tabW = (cardW - 120) / 2;
        int loginTabX = padX;
        int registerTabX = padX + tabW;
        int tabY = curY;
        int tabH = 32;
        curY += tabH + 16;

        // Field rects
        int rowH = 36;
        int fieldGap = 16;
        int nameY = -1;
        if (this.mode == Mode.REGISTER) {
            nameY = curY;
            this.nameField.setBounds(padX, curY, fieldW, rowH);
            curY += rowH + fieldGap;
        }
        int emailY = curY; this.emailField.setBounds(padX, curY, fieldW, rowH);
        curY += rowH + fieldGap;
        int passY = curY; this.passwordField.setBounds(padX, curY, fieldW, rowH);
        curY += rowH + fieldGap + 4;

        // Server cycler button
        int serverX = padX;
        int serverY = curY;
        int serverW = fieldW;
        int serverH = 32;
        curY += serverH + fieldGap;

        // Submit button
        int submitX = padX;
        int submitY = curY;
        int submitW = fieldW;
        int submitH = 40;
        curY += submitH + 8;

        // Guest button (login mode only)
        int guestX = padX;
        int guestY = curY;
        int guestW = fieldW;
        int guestH = 36;

        boolean mouseDown = mouse.isPressed(1);
        boolean justClicked = mouseDown && !this.prevMouseDown;
        this.prevMouseDown = mouseDown;
        int mx = mouse.getX();
        int my = mouse.getY();

        // Tab clicks
        if (justClicked) {
            if (this.hit(mx, my, loginTabX, tabY, tabW, tabH)) {
                this.mode = Mode.LOGIN;
                this.error = "";
                return;
            }
            if (this.hit(mx, my, registerTabX, tabY, tabW, tabH)) {
                this.mode = Mode.REGISTER;
                this.error = "";
                return;
            }
            if (this.hit(mx, my, serverX, serverY, serverW, serverH)) {
                this.serverIdx = (this.serverIdx + 1) % SERVERS.length;
                return;
            }
            if (this.hit(mx, my, submitX, submitY, submitW, submitH)) {
                this.submit();
                return;
            }
            if (this.mode == Mode.LOGIN && this.hit(mx, my, guestX, guestY, guestW, guestH)) {
                this.guestLogin();
                return;
            }

            // Field focus
            this.emailField.handleClick(mx, my);
            this.passwordField.handleClick(mx, my);
            if (this.mode == Mode.REGISTER) this.nameField.handleClick(mx, my);
            else this.nameField.setFocused(false);

            // Defocus on click outside any field
            boolean any = this.emailField.isFocused() || this.passwordField.isFocused()
                    || (this.mode == Mode.REGISTER && this.nameField.isFocused());
            if (!any) {
                this.emailField.setFocused(false);
                this.passwordField.setFocused(false);
                this.nameField.setFocused(false);
            }
        }

        // ESC quits the launcher (matches web client closing the tab).
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            // No confirm — closing the launcher window before login is harmless.
            Gdx.app.exit();
        }
    }

    private boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void submit() {
        if (this.busy) return;
        String email = this.emailField.getText().trim();
        String password = this.passwordField.getText();
        if (email.isEmpty() || password.isEmpty()) {
            this.error = "Email and password required.";
            return;
        }
        if (this.mode == Mode.REGISTER) {
            String name = this.nameField.getText().trim();
            if (name.isEmpty()) {
                this.error = "Username required.";
                return;
            }
            this.doRegisterAndLogin(name, email, password);
        } else {
            this.doLogin(email, password, false);
        }
    }

    private void doLogin(String email, String password, boolean isGuest) {
        this.error = "";
        this.busy = true;
        OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        new Thread(() -> {
            try {
                SessionTokenDto resp = svc.login(email, password);
                SocketClient.PLAYER_EMAIL = email;
                SocketClient.PLAYER_PASSWORD = password;
                SessionStore store = SessionStore.get();
                store.setSession(resp.getToken(), resp.getAccountGuid());
                store.setRememberEmail(email);
                store.setLastServer(SERVERS[this.serverIdx]);
                if (isGuest) store.setGuest(email, password);
                store.save();
                PlayerAccountDto acct = svc.getAccount(resp.getAccountGuid());
                this.loginResult.set(acct);
            } catch (Exception e) {
                log.error("[LOGIN] failed: {}", e.getMessage());
                this.loginError.set(this.summarize(e));
            }
        }, "openrealm-login").start();
    }

    private void doRegisterAndLogin(String name, String email, String password) {
        this.error = "";
        this.busy = true;
        OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        new Thread(() -> {
            try {
                svc.register(email, password, name, false);
                // Auto-login after register, mirroring the web client.
                SessionTokenDto resp = svc.login(email, password);
                SocketClient.PLAYER_EMAIL = email;
                SocketClient.PLAYER_PASSWORD = password;
                SessionStore store = SessionStore.get();
                store.setSession(resp.getToken(), resp.getAccountGuid());
                store.setRememberEmail(email);
                store.setLastServer(SERVERS[this.serverIdx]);
                store.save();
                PlayerAccountDto acct = svc.getAccount(resp.getAccountGuid());
                this.loginResult.set(acct);
            } catch (Exception e) {
                log.error("[REGISTER] failed: {}", e.getMessage());
                this.loginError.set(this.summarize(e));
            }
        }, "openrealm-register").start();
    }

    private void guestLogin() {
        this.error = "";
        this.busy = true;
        OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        new Thread(() -> {
            try {
                SessionStore store = SessionStore.get();
                String email = store.getGuestEmail();
                String password = store.getGuestPassword();
                if (email != null && password != null) {
                    try {
                        SessionTokenDto resp = svc.login(email, password);
                        SocketClient.PLAYER_EMAIL = email;
                        SocketClient.PLAYER_PASSWORD = password;
                        store.setSession(resp.getToken(), resp.getAccountGuid());
                        store.setLastServer(SERVERS[this.serverIdx]);
                        store.save();
                        PlayerAccountDto acct = svc.getAccount(resp.getAccountGuid());
                        this.loginResult.set(acct);
                        return;
                    } catch (Exception loginErr) {
                        // Saved guest creds expired/invalid — fall through to register.
                        store.clearGuest();
                    }
                }
                // Generate fresh guest credentials matching the web client format.
                String guestId = randHex() + randHex();
                email = "guest_" + guestId.substring(0, 8) + "@openrealm.net";
                password = randHex() + randHex();
                String accountName = pickGuestName();
                svc.register(email, password, accountName, true);
                SessionTokenDto resp = svc.login(email, password);
                SocketClient.PLAYER_EMAIL = email;
                SocketClient.PLAYER_PASSWORD = password;
                store.setSession(resp.getToken(), resp.getAccountGuid());
                store.setGuest(email, password);
                store.setLastServer(SERVERS[this.serverIdx]);
                store.save();
                log.info("[GUEST] new account email={} (saved to ~/.openrealm/session.json)", email);
                PlayerAccountDto acct = svc.getAccount(resp.getAccountGuid());
                this.loginResult.set(acct);
            } catch (Exception e) {
                log.error("[GUEST] failed: {}", e.getMessage());
                this.loginError.set(this.summarize(e));
            }
        }, "openrealm-guest").start();
    }

    private static String randHex() {
        return Long.toHexString((long)(Math.random() * 0xffffffffL));
    }

    private static final String[] GUEST_NAMES = {
        "Utanu","Gharr","Yimi","Idrae","Odaru","Scheev","Zhiar","Itani",
        "Serl","Oeti","Tiar","Issz","Oshyu","Deyst","Oalei","Vorv",
        "Iatho","Uoro","Urake","Eashy","Queq","Rayr","Tal","Drac",
        "Yangu","Eango","Rilr","Ehoni","Risrr","Sek","Eati","Laen"
    };
    private static String pickGuestName() {
        return GUEST_NAMES[(int)(Math.random() * GUEST_NAMES.length)];
    }

    /**
     * Apply the user's server-cycler choice to SocketClient.SERVER_ADDR.
     * The native client uses raw TCP on port 2222 (not WebSockets) and the
     * launcher's CLI arg is the authoritative host, so "useast" is a label
     * meaning "use whatever the launcher was started with"; only the literal
     * local options actually rewrite the address.
     */
    static void applyServerSelection(String selection) {
        if ("local".equals(selection)) {
            SocketClient.SERVER_ADDR = "127.0.0.1";
        } else if ("localhost".equals(selection)) {
            SocketClient.SERVER_ADDR = "localhost";
        }
        // For "useast" (default) leave SERVER_ADDR alone — keeps the launcher
        // arg authoritative for prod/staging deployments.
    }

    private String summarize(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        // Trim verbose JSON error envelopes to the first 200 chars.
        if (msg.length() > 200) return msg.substring(0, 200) + "…";
        return msg;
    }

    @Override
    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        // Dark backdrop
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.08f, 0.07f, 0.10f, 1f);
        shapes.rect(0, 0, OpenRealmGame.width, OpenRealmGame.height);
        shapes.end();
        batch.begin();

        // Title
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "OpenRealm", OpenRealmGame.width / 2f - 80, 80);
        font.setColor(0.55f, 0.50f, 0.45f, 1f);
        font.draw(batch, "Native Launcher v" + GameLauncher.GAME_VERSION, OpenRealmGame.width / 2f - 110, 110);

        // Card
        int cardW = 480;
        int cardH = this.mode == Mode.REGISTER ? 600 : 540;
        int cardX = (OpenRealmGame.width - cardW) / 2;
        int cardY = (OpenRealmGame.height - cardH) / 2;
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.13f, 0.10f, 0.13f, 0.97f);
        shapes.rect(cardX, cardY, cardW, cardH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.30f, 0.25f, 0.30f, 1f);
        shapes.rect(cardX, cardY, cardW, cardH);
        shapes.end();
        batch.begin();

        int padX = cardX + 60;
        int fieldW = cardW - 120;
        int curY = cardY + 96;

        // Tab bar
        int tabW = fieldW / 2;
        int tabH = 32;
        this.drawTab(batch, shapes, font, padX, curY, tabW, tabH, "LOGIN", this.mode == Mode.LOGIN);
        this.drawTab(batch, shapes, font, padX + tabW, curY, tabW, tabH, "REGISTER", this.mode == Mode.REGISTER);
        curY += tabH + 16;

        int rowH = 36;
        int fieldGap = 16;
        if (this.mode == Mode.REGISTER) {
            font.setColor(0.78f, 0.66f, 0.43f, 1f);
            font.draw(batch, "Username", padX, curY - 4);
            this.nameField.setBounds(padX, curY, fieldW, rowH);
            this.nameField.render(batch, shapes, font);
            curY += rowH + fieldGap;
        }
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "Email", padX, curY - 4);
        this.emailField.setBounds(padX, curY, fieldW, rowH);
        this.emailField.render(batch, shapes, font);
        curY += rowH + fieldGap;

        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "Password", padX, curY - 4);
        this.passwordField.setBounds(padX, curY, fieldW, rowH);
        this.passwordField.render(batch, shapes, font);
        curY += rowH + fieldGap + 4;

        // Server selector (cycle on click)
        this.drawButton(batch, shapes, font, padX, curY, fieldW, 32,
                "Game Server: " + SERVERS[this.serverIdx] + "  (click to change)",
                false, false);
        curY += 32 + fieldGap;

        // Submit
        String submitLabel = this.mode == Mode.REGISTER
                ? (this.busy ? "Registering..." : "Register")
                : (this.busy ? "Logging in..."  : "Login");
        this.drawButton(batch, shapes, font, padX, curY, fieldW, 40, submitLabel, true, this.busy);
        curY += 40 + 8;

        if (this.mode == Mode.LOGIN) {
            this.drawButton(batch, shapes, font, padX, curY, fieldW, 36, "Play as Guest", false, this.busy);
            curY += 36 + 8;
        }

        if (!this.error.isEmpty()) {
            font.setColor(0.95f, 0.45f, 0.45f, 1f);
            font.draw(batch, this.error, padX, curY + 16);
        }

        // Discord link footer
        font.setColor(0.55f, 0.55f, 0.65f, 1f);
        font.draw(batch, "Discord: https://discord.gg/NQ2hZZGR3",
                cardX + 60, cardY + cardH - 16);

        font.setColor(Color.WHITE);
    }

    private void drawTab(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                         int x, int y, int w, int h, String label, boolean active) {
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (active) shapes.setColor(0.30f, 0.22f, 0.18f, 1f);
        else shapes.setColor(0.14f, 0.11f, 0.13f, 1f);
        shapes.rect(x, y, w, h);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(active ? 0.78f : 0.30f, active ? 0.66f : 0.25f, 0.30f, 1f);
        shapes.rect(x, y, w, h);
        shapes.end();
        batch.begin();
        font.setColor(active ? Color.WHITE : new Color(0.65f, 0.60f, 0.55f, 1f));
        font.draw(batch, label, x + (w / 2f) - (label.length() * 4f), y + h * 0.65f);
        font.setColor(Color.WHITE);
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
        font.draw(batch, label, x + (w / 2f) - (label.length() * 4f), y + h * 0.65f);
        font.setColor(Color.WHITE);
    }
}
