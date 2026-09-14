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
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import java.util.concurrent.atomic.AtomicBoolean;
import com.openrealm.game.ui.UiRender;

/**
 * Account login / registration / guest screen. Network calls run on a worker
 * thread and land in atomic refs applied on the next frame. On success, swaps
 * itself out for {@link CharacterSelectState}.
 */
@Slf4j
public class LoginState extends GameState {

    private enum Mode { LOGIN, REGISTER }

    private static final String[] SERVERS = { "useast", "local", "localhost" };

    private static final String[] GUEST_NAMES = {
        "Utanu","Gharr","Yimi","Idrae","Odaru","Scheev","Zhiar","Itani",
        "Serl","Oeti","Tiar","Issz","Oshyu","Deyst","Oalei","Vorv",
        "Iatho","Uoro","Urake","Eashy","Queq","Rayr","Tal","Drac",
        "Yangu","Eango","Rilr","Ehoni","Risrr","Sek","Eati","Laen"
    };

    private Mode mode = Mode.LOGIN;
    private final TextField emailField;
    private final TextField passwordField;
    private final TextField nameField; // register-only
    private int serverIdx = 0;
    private String error = "";
    private boolean busy = false;
    private boolean autoLoginAttempted = false;
    private final AtomicReference<PlayerAccountDto> loginResult = new AtomicReference<>();
    private final AtomicReference<String> loginError = new AtomicReference<>();
    private PlayerAccountDto pendingHandoff = null;

    private boolean prevMouseDown = false;

    // Flipped once at load: the y-down ortho camera would draw a raw texture
    // upside-down.
    private TextureRegion logoRegion;

    // Set when auto-login finds no usable session (distinct from loginError so
    // update() can drop the spinner without surfacing a red message).
    private final AtomicBoolean autoLoginCleared = new AtomicBoolean(false);

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

    private TextureRegion getLogo() {
        if (this.logoRegion == null) {
            try {
                Texture tex = new Texture(Gdx.files.classpath("icon_min.png"));
                this.logoRegion = new TextureRegion(tex);
                this.logoRegion.flip(false, true);
            } catch (Exception e) {
                log.debug("[LOGIN] no logo texture: {}", e.getMessage());
            }
        }
        return this.logoRegion;
    }

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
                // Clear the saved token ONLY on an actual auth rejection; keep
                // it on transient failures so a connectivity blip doesn't force
                // a retype.
                final String msg = e.getMessage() == null ? "" : e.getMessage();
                final boolean tokenRejected = msg.contains("401")
                        || msg.contains("403")
                        || msg.toLowerCase().contains("unauthor")
                        || msg.toLowerCase().contains("invalid token");
                if (tokenRejected) {
                    log.info("[LOGIN] persisted token rejected by server ({}) - clearing", msg);
                    store.clearSession();
                    svc.setSessionToken(null);
                    this.loginError.set("Session expired - please sign in again.");
                } else {
                    log.warn("[LOGIN] auto-login transient failure ({}) - keeping saved token, falling back to manual login", msg);
                    this.loginError.set("Couldn't reach the server - sign in manually or retry.");
                }
                this.autoLoginCleared.set(true);
            }
        }, "openrealm-autologin").start();
    }

    @Override
    public void update(double time) {
        if (!this.autoLoginAttempted) {
            this.tryAutoLogin();
        }
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
        if (this.autoLoginCleared.compareAndSet(true, false)) {
            this.busy = false;
        }
        if (this.pendingHandoff != null) {
            PlayerAccountDto acct = this.pendingHandoff;
            this.pendingHandoff = null;
            KeyHandler.textSink = null;
            applyServerSelection(SERVERS[this.serverIdx]);
            this.gsm.pop(GameStateManager.LOGIN);
            this.gsm.add(GameStateManager.TERMS, new TermsAcceptState(this.gsm, acct));
            return;
        }

        TextField.UpdateResult emailRes = this.emailField.update();
        TextField.UpdateResult passRes = this.passwordField.update();
        TextField.UpdateResult nameRes = this.mode == Mode.REGISTER
                ? this.nameField.update() : TextField.UpdateResult.NONE;
        if (emailRes == TextField.UpdateResult.SUBMIT
                || passRes == TextField.UpdateResult.SUBMIT
                || nameRes == TextField.UpdateResult.SUBMIT) {
            this.submit();
            return;
        }
        TextField.UpdateResult tabSignal = TextField.UpdateResult.NONE;
        if (emailRes == TextField.UpdateResult.TAB || passRes == TextField.UpdateResult.TAB
                || nameRes == TextField.UpdateResult.TAB) {
            tabSignal = TextField.UpdateResult.TAB;
        } else if (emailRes == TextField.UpdateResult.SHIFT_TAB || passRes == TextField.UpdateResult.SHIFT_TAB
                || nameRes == TextField.UpdateResult.SHIFT_TAB) {
            tabSignal = TextField.UpdateResult.SHIFT_TAB;
        }
        if (tabSignal != TextField.UpdateResult.NONE) {
            this.advanceFocus(tabSignal == TextField.UpdateResult.SHIFT_TAB);
        }
    }

    private void advanceFocus(boolean reverse) {
        TextField[] ring = this.mode == Mode.REGISTER
                ? new TextField[] { this.nameField, this.emailField, this.passwordField }
                : new TextField[] { this.emailField, this.passwordField };
        int idx = -1;
        for (int i = 0; i < ring.length; i++) {
            if (ring[i].isFocused()) { idx = i; break; }
        }
        // Nothing focused yet -> tab lands on first, shift+tab on last.
        int next = idx < 0
                ? (reverse ? ring.length - 1 : 0)
                : (reverse ? (idx - 1 + ring.length) % ring.length : (idx + 1) % ring.length);
        for (int i = 0; i < ring.length; i++) ring[i].setFocused(i == next);
    }

    @Override
    public void input(MouseHandler mouse, KeyHandler key) {
        if (this.busy) return;

        // Layout must match render().
        int cardW = 575;
        int cardH = this.mode == Mode.REGISTER ? 720 : 660;
        int cardX = (OpenRealmGame.width - cardW) / 2;
        int cardY = (OpenRealmGame.height - cardH) / 2;
        int padX = cardX + 48;
        int fieldW = cardW - 96;

        int curY = cardY + 96 + 110;

        int rowH = 40;
        int labelGap = 28;
        int fieldGap = 18;

        // Username field (register mode only)
        if (this.mode == Mode.REGISTER) {
            int nameFieldY = curY + labelGap;
            this.nameField.setBounds(padX, nameFieldY, fieldW, rowH);
            curY = nameFieldY + rowH + fieldGap;
        }
        int emailFieldY = curY + labelGap;
        this.emailField.setBounds(padX, emailFieldY, fieldW, rowH);
        curY = emailFieldY + rowH + fieldGap;
        int passFieldY = curY + labelGap;
        this.passwordField.setBounds(padX, passFieldY, fieldW, rowH);
        curY = passFieldY + rowH + fieldGap + 4;

        // Submit button
        int submitH = 48;
        int submitY = curY;
        curY += submitH + 12;

        // Guest button (login mode only)
        int guestH = 44;
        int guestY = curY;
        if (this.mode == Mode.LOGIN) curY += guestH + 16;

        // "No account? Register" text link
        int linkH = 24;
        int linkY = curY;
        curY += linkH + 16;

        // Server cycler — small, below the link
        int serverH = 28;
        int serverY = curY;

        boolean mouseDown = mouse.isPressed(1);
        boolean justClicked = mouseDown && !this.prevMouseDown;
        boolean justReleased = !mouseDown && this.prevMouseDown;
        this.prevMouseDown = mouseDown;
        int mx = mouse.getX();
        int my = mouse.getY();

        if (mouseDown && !justClicked) {
            if (this.emailField.isFocused()) this.emailField.handleDrag(mx, my);
            else if (this.passwordField.isFocused()) this.passwordField.handleDrag(mx, my);
            else if (this.mode == Mode.REGISTER && this.nameField.isFocused()) this.nameField.handleDrag(mx, my);
        }
        if (justReleased) {
            this.emailField.handleRelease();
            this.passwordField.handleRelease();
            this.nameField.handleRelease();
        }

        if (justClicked) {
            if (this.hit(mx, my, padX, submitY, fieldW, submitH)) {
                this.submit();
                return;
            }
            if (this.mode == Mode.LOGIN && this.hit(mx, my, padX, guestY, fieldW, guestH)) {
                this.guestLogin();
                return;
            }
            if (this.hit(mx, my, padX, linkY, fieldW, linkH)) {
                this.mode = (this.mode == Mode.LOGIN) ? Mode.REGISTER : Mode.LOGIN;
                this.error = "";
                return;
            }
            if (this.hit(mx, my, padX, serverY, fieldW, serverH)) {
                this.serverIdx = (this.serverIdx + 1) % SERVERS.length;
                return;
            }

            // Field focus
            this.emailField.handleClick(mx, my);
            this.passwordField.handleClick(mx, my);
            if (this.mode == Mode.REGISTER) this.nameField.handleClick(mx, my);
            else this.nameField.setFocused(false);

            boolean any = this.emailField.isFocused() || this.passwordField.isFocused()
                    || (this.mode == Mode.REGISTER && this.nameField.isFocused());
            if (!any) {
                this.emailField.setFocused(false);
                this.passwordField.setFocused(false);
                this.nameField.setFocused(false);
            }
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
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

    private static String pickGuestName() {
        return GUEST_NAMES[(int)(Math.random() * GUEST_NAMES.length)];
    }

    /**
     * "useast" is a label meaning "keep the launcher's authoritative CLI host";
     * only the literal local options rewrite SERVER_ADDR.
     */
    static void applyServerSelection(String selection) {
        if ("local".equals(selection)) {
            SocketClient.SERVER_ADDR = "127.0.0.1";
        } else if ("localhost".equals(selection)) {
            SocketClient.SERVER_ADDR = "localhost";
        }
    }

    private String summarize(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        if (msg.length() > 200) return msg.substring(0, 200) + "...";
        return msg;
    }

    @Override
    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        UiRender.fillRect(batch, shapes, 0, 0, OpenRealmGame.width, OpenRealmGame.height,
                new Color(0.10f, 0.07f, 0.10f, 1f));

        // Must match input() exactly.
        int cardW = 575;
        int cardH = this.mode == Mode.REGISTER ? 720 : 660;
        int cardX = (OpenRealmGame.width - cardW) / 2;
        int cardY = (OpenRealmGame.height - cardH) / 2;
        int padX = cardX + 48;
        int fieldW = cardW - 96;

        UiRender.panel(batch, shapes, cardX, cardY, cardW, cardH,
                new Color(0.16f, 0.13f, 0.16f, 1f), new Color(0.30f, 0.25f, 0.30f, 1f));

        TextureRegion logo = this.getLogo();
        int logoSize = 96;
        if (logo != null) {
            float logoX = cardX + (cardW - logoSize) / 2f;
            float logoY = cardY + 16;
            batch.draw(logo, logoX, logoY, logoSize, logoSize);
        }
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        UiRender.drawCentered(batch, font, "OpenRealm", cardX + cardW / 2f,
                cardY + (logo != null ? logoSize + 28 : 36));
        font.setColor(0.55f, 0.50f, 0.45f, 1f);
        UiRender.drawCentered(batch, font, "Native Client", cardX + cardW / 2f,
                cardY + (logo != null ? logoSize + 64 : 80));

        int curY = cardY + (logo != null ? logoSize + 110 : 130);
        int rowH = 40;
        int labelGap = 28;
        int fieldGap = 18;

        if (this.mode == Mode.REGISTER) {
            curY = drawLabeledField(batch, shapes, font, "Username", this.nameField,
                    padX, curY, fieldW, rowH, labelGap, fieldGap);
        }
        curY = drawLabeledField(batch, shapes, font, "Email", this.emailField,
                padX, curY, fieldW, rowH, labelGap, fieldGap);
        curY = drawLabeledField(batch, shapes, font, "Password", this.passwordField,
                padX, curY, fieldW, rowH, labelGap, fieldGap);
        curY += 4;

        // Submit (primary)
        int submitH = 48;
        String submitLabel = this.mode == Mode.REGISTER
                ? (this.busy ? "Registering..." : "Register")
                : (this.busy ? "Logging in..."  : "Login");
        this.drawButton(batch, shapes, font, padX, curY, fieldW, submitH, submitLabel, true, this.busy);
        curY += submitH + 12;

        if (this.mode == Mode.LOGIN) {
            int guestH = 44;
            this.drawButton(batch, shapes, font, padX, curY, fieldW, guestH, "Play as Guest", false, this.busy);
            curY += guestH + 16;
        }

        int linkH = 24;
        String linkText = this.mode == Mode.LOGIN
                ? "No account? Register"
                : "Already registered? Sign in";
        font.setColor(0.55f, 0.50f, 0.45f, 1f);
        UiRender.drawCentered(batch, font, linkText, cardX + cardW / 2f, curY + 4);
        curY += linkH + 16;

        int serverH = 28;
        font.setColor(0.55f, 0.50f, 0.45f, 1f);
        UiRender.drawCentered(batch, font, "Server: " + SERVERS[this.serverIdx] + "  (click to change)",
                cardX + cardW / 2f, curY + 4);
        curY += serverH + 8;

        if (!this.error.isEmpty()) {
            font.setColor(0.95f, 0.45f, 0.45f, 1f);
            font.draw(batch, this.error, padX, curY + 16);
        }

        font.setColor(0.40f, 0.45f, 0.85f, 1f);
        UiRender.drawCentered(batch, font, "Join our Discord Community!",
                cardX + cardW / 2f, cardY + cardH - 36);

        final float legalCx = OpenRealmGame.width / 2f;
        font.setColor(0.45f, 0.42f, 0.40f, 1f);
        UiRender.drawCentered(batch, font, "(c) 2024-2026 Robert Usey - All Rights Reserved. Proprietary client.",
                legalCx, OpenRealmGame.height - 52);
        UiRender.drawCentered(batch, font, "No reverse engineering, network interception/tampering, asset ripping, or modified clients/bots.",
                legalCx, OpenRealmGame.height - 36);
        UiRender.drawCentered(batch, font, "Full Terms of Use & Privacy Policy are available on the OpenRealm web client.",
                legalCx, OpenRealmGame.height - 20);

        font.setColor(Color.WHITE);
    }

    /** Draws "Label" + input field, returning curY advanced past the field. */
    private int drawLabeledField(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                                 String label, TextField field,
                                 int padX, int curY, int fieldW, int rowH,
                                 int labelGap, int fieldGap) {
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, label, padX, curY);
        int fieldY = curY + labelGap;
        field.setBounds(padX, fieldY, fieldW, rowH);
        field.render(batch, shapes, font);
        return fieldY + rowH + fieldGap;
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
}
