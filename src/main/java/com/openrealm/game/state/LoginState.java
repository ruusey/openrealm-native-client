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

    /**
     * Set when the auto-login worker thread detects an invalid/expired
     * persisted token. The GL update() loop watches for this and clears
     * the busy spinner so the user can re-enter their password instead of
     * staring at a hung form.
     *
     * Separate flag from {@link #loginError} so we can distinguish a
     * silent "no valid session, just show the form" from an actual error
     * that needs surfacing in red.
     */
    private final java.util.concurrent.atomic.AtomicBoolean autoLoginCleared =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
                // Surface a soft, non-error status so the user knows why the
                // form just appeared, and signal the GL loop to drop the
                // spinner. The previous code wrote null into loginError,
                // which the update() consumer's null-check filtered out —
                // leaving busy=true forever and the form unusable.
                this.loginError.set("Session expired — please sign in again.");
                this.autoLoginCleared.set(true);
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
        // Auto-login worker may have decided "no usable session, drop the
        // spinner" without producing a user-visible error string. Honour
        // that signal independently so the form unblocks even when err
        // happened to be null.
        if (this.autoLoginCleared.compareAndSet(true, false)) {
            this.busy = false;
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

        // Layout matches render() — keep the two in sync. Web-client-style
        // single-form layout: title block at the top of the card, then the
        // form fields, then primary/secondary buttons, then a "Register"
        // text link, then the server cycler, then the Discord footer.
        int cardW = 460;
        int cardH = this.mode == Mode.REGISTER ? 620 : 560;
        int cardX = (OpenRealmGame.width - cardW) / 2;
        int cardY = (OpenRealmGame.height - cardH) / 2;
        int padX = cardX + 48;
        int fieldW = cardW - 96;

        // Title block (no input target) is fixed-height; layout pointer
        // starts BELOW it.
        int curY = cardY + 130;

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
        this.prevMouseDown = mouseDown;
        int mx = mouse.getX();
        int my = mouse.getY();

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
        shapes.setColor(0.10f, 0.07f, 0.10f, 1f);
        shapes.rect(0, 0, OpenRealmGame.width, OpenRealmGame.height);
        shapes.end();
        batch.begin();

        // Card geometry — must match input() exactly.
        int cardW = 460;
        int cardH = this.mode == Mode.REGISTER ? 620 : 560;
        int cardX = (OpenRealmGame.width - cardW) / 2;
        int cardY = (OpenRealmGame.height - cardH) / 2;
        int padX = cardX + 48;
        int fieldW = cardW - 96;

        // Card background + border
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.16f, 0.13f, 0.16f, 1f);
        shapes.rect(cardX, cardY, cardW, cardH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.30f, 0.25f, 0.30f, 1f);
        shapes.rect(cardX, cardY, cardW, cardH);
        shapes.end();
        batch.begin();

        // Title block inside the card — mirrors the web client. Big gold
        // "OpenRealm" and a small subtitle below it.
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        drawCenteredText(batch, font, "OpenRealm", cardX + cardW / 2f, cardY + 36);
        font.setColor(0.55f, 0.50f, 0.45f, 1f);
        drawCenteredText(batch, font, "Native Client", cardX + cardW / 2f, cardY + 80);

        int curY = cardY + 130;
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

        // Guest button (login mode only — registering inherently creates the
        // account so guest doesn't apply)
        if (this.mode == Mode.LOGIN) {
            int guestH = 44;
            this.drawButton(batch, shapes, font, padX, curY, fieldW, guestH, "Play as Guest", false, this.busy);
            curY += guestH + 16;
        }

        // "No account? Register" / "Already registered? Sign in" text link
        int linkH = 24;
        String linkText = this.mode == Mode.LOGIN
                ? "No account? Register"
                : "Already registered? Sign in";
        font.setColor(0.55f, 0.50f, 0.45f, 1f);
        drawCenteredText(batch, font, linkText, cardX + cardW / 2f, curY + 4);
        curY += linkH + 16;

        // Server selector (smaller, secondary). Kept since the native client
        // can target multiple deployments unlike the web client.
        int serverH = 28;
        font.setColor(0.55f, 0.50f, 0.45f, 1f);
        drawCenteredText(batch, font, "Server: " + SERVERS[this.serverIdx] + "  (click to change)",
                cardX + cardW / 2f, curY + 4);
        curY += serverH + 8;

        if (!this.error.isEmpty()) {
            font.setColor(0.95f, 0.45f, 0.45f, 1f);
            font.draw(batch, this.error, padX, curY + 16);
        }

        // Discord link footer
        font.setColor(0.40f, 0.45f, 0.85f, 1f);
        drawCenteredText(batch, font, "Join our Discord Community!",
                cardX + cardW / 2f, cardY + cardH - 36);

        font.setColor(Color.WHITE);
    }

    /**
     * Lay out a "Label\n[input field]" pair starting at curY (top of label).
     * Returns the new curY, advanced past the field plus the inter-row gap.
     */
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

    /** Centers a string horizontally around `cx`, drawing its top at `topY`. */
    private void drawCenteredText(SpriteBatch batch, BitmapFont font, String s, float cx, float topY) {
        com.badlogic.gdx.graphics.g2d.GlyphLayout layout = new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, s);
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
        drawTextCenteredInBox(batch, font, label, x, y, w, h);
        font.setColor(Color.WHITE);
    }

    /**
     * Center a string both horizontally AND vertically inside a box. Uses
     * GlyphLayout for an actual width measurement and font.getCapHeight() for
     * vertical anchoring — the previous "y + h * 0.65" heuristic had the text
     * baseline below the box bottom for a 1.8x-scaled font, which is what
     * made every button look like it was struck through.
     */
    private void drawTextCenteredInBox(SpriteBatch batch, BitmapFont font,
                                        String text, int x, int y, int w, int h) {
        com.badlogic.gdx.graphics.g2d.GlyphLayout layout = new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, text);
        float textX = x + (w - layout.width) / 2f;
        float textY = y + (h - layout.height) / 2f;
        font.draw(batch, text, textX, textY);
    }
}
