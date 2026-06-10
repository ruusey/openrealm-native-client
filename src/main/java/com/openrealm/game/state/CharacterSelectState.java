package com.openrealm.game.state;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.openrealm.account.dto.CharacterDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.SessionStore;
import com.openrealm.game.contants.CharacterClass;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.ui.LeaderboardPanel;
import com.openrealm.game.ui.TextField;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.net.client.SocketClient;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack;
import com.badlogic.gdx.utils.viewport.ScalingViewport;

/**
 * Visual character-select screen — replaces the JOptionPane dropdown that
 * the legacy launcher used. Mirrors the web client's {@code charselect-screen}:
 *
 *   - Tabs: Characters | Graveyard
 *   - Character list (class icon, level, class name, stats-maxed)
 *   - Class picker for creating a new character (14 classes)
 *   - Vault chest count + Add Chest button
 *   - Change password collapsible (toggle row + 3 fields)
 *   - Logout button (clears persisted session, returns to LoginState)
 *   - Server selector cycler
 *   - Leaderboard panel (top 10)
 *   - Play button (transitions to PlayState with selected character)
 *   - Delete Character button (soft-delete, refreshes list)
 *
 * Kept self-contained: HTTP calls run on background threads and results
 * land in atomic refs so the GL thread stays free.
 */
@Slf4j
public class CharacterSelectState extends GameState {

    /**
     * Preset host shortcuts the user can cycle through. Each entry is a
     * {label, host} pair — label shows in the cycler, host is what gets
     * written to the editable field (and ultimately to SocketClient).
     *
     * The legacy ["useast","local","localhost"] list was useless on the
     * native TCP client because those nginx-route names don't resolve
     * outside the web client's reverse proxy. Replace with real targets:
     *   USEast -> user's prod game-server IP
     *   Local  -> 127.0.0.1
     *   Localhost -> localhost
     * Add more here as deployments come online.
     */
    private static final String[][] SERVER_PRESETS = {
            { "USEast",    "100.55.103.226" },
            { "Local",     "127.0.0.1" },
            { "Localhost", "localhost" }
    };
    // Fallback name list — only consulted when GameDataManager hasn't loaded
    // character-classes.json yet (transient race during cold-start). Every
    // class that ships is sourced from JSON; adding a class is a data-only edit.
    private static final String[] CLASS_NAMES = {
            "Rogue","Archer","Wizard","Priest","Warrior","Knight","Paladin",
            "Assassin","Necromancer","Mystic","Trickster","Sorcerer","Huntress","Ninja",
            "Heavy Debuffer","Heavy Buffer","Heavy DPS","Heavy Oddball"
    };

    /**
     * Sorted class ids the create-character picker should render. Driven by
     * {@code GameDataManager.CHARACTER_CLASSES} so a new class in JSON shows
     * up automatically. Falls back to {@code CLASS_NAMES} indices only when
     * the data manager hasn't populated yet.
     */
    private int[] pickerClassIds() {
        if (GameDataManager.CHARACTER_CLASSES == null || GameDataManager.CHARACTER_CLASSES.isEmpty()) {
            int[] out = new int[CLASS_NAMES.length];
            for (int i = 0; i < out.length; i++) out[i] = i;
            return out;
        }
        return GameDataManager.CHARACTER_CLASSES.keySet().stream()
                .filter(id -> id != null && id >= 0)
                .sorted()
                .mapToInt(Integer::intValue)
                .toArray();
    }

    /** Resolve a class id to its display name, preferring the JSON definition. */
    private String classNameFor(int classId) {
        if (GameDataManager.CHARACTER_CLASSES != null) {
            com.openrealm.game.model.CharacterClassModel m = GameDataManager.CHARACTER_CLASSES.get(classId);
            if (m != null && m.getClassName() != null) return m.getClassName();
        }
        return classId >= 0 && classId < CLASS_NAMES.length ? CLASS_NAMES[classId] : ("Class " + classId);
    }
    private static final SimpleDateFormat DEATH_FMT = new SimpleDateFormat("yyyy-MM-dd");

    private enum Tab { CHARACTERS, GRAVEYARD }

    private PlayerAccountDto account;
    private Tab tab = Tab.CHARACTERS;
    private int selectedCharIdx = -1;
    private int selectedClassId = -1;
    private int serverIdx = 0;
    private String error = "";
    private String pwStatus = "";
    private boolean busy = false;
    private boolean changePwOpen = false;

    private final TextField currentPw = new TextField(0, 0, 200, 28);
    private final TextField newPw = new TextField(0, 0, 200, 28);
    private final TextField confirmPw = new TextField(0, 0, 200, 28);

    /**
     * Editable game-server host. Defaults to the data-service host (which
     * is what the launcher was started with) since most deployments run the
     * game server and data service on the same machine. Persisted between
     * sessions via SessionStore.
     */
    private final TextField serverHostField = new TextField(0, 0, 200, 32);

    private final LeaderboardPanel leaderboard = new LeaderboardPanel();

    /** Result fields from background threads; consumed on the GL thread. */
    private final AtomicReference<PlayerAccountDto> refreshResult = new AtomicReference<>();
    private final AtomicReference<String> errorResult = new AtomicReference<>();
    /** When non-null, transition to PlayState using this character. */
    private CharacterDto pendingPlay = null;
    /** When true, transition back to LoginState (logout). */
    private boolean pendingLogout = false;

    private boolean prevMouseDown = false;
    /** Vertical scroll offset for the character list (pixels; 0 = top). */
    private float scrollOffset = 0f;
    /** Pixels per scroll-wheel notch — matches typical OS feel. */
    private static final float SCROLL_STEP = 56f;
    /** Currently-selected preset index (used by the preset cycler button). */
    private int presetIdx = 0;

    public CharacterSelectState(GameStateManager gsm, PlayerAccountDto account) {
        super(gsm);
        this.account = account;
        this.currentPw.setPassword(true);
        this.newPw.setPassword(true);
        this.confirmPw.setPassword(true);
        this.currentPw.setPlaceholder("Current password");
        this.newPw.setPlaceholder("New password");
        this.confirmPw.setPlaceholder("Confirm new password");
        // Seed the editable game-server host field. The data-service host
        // (SocketClient.SERVER_ADDR / launcher arg) is intentionally NOT
        // used as a fallback here — the game server runs on a different
        // host in production, so seeding from the data-service IP would
        // mislead the user into a guaranteed-broken connection. Order:
        //   1. previously-saved server host (if it looks like a real host
        //      and not a stale nginx-route label)
        //   2. First preset's host (the known prod game-server IP)
        //   3. "127.0.0.1" as a last resort
        SessionStore store = SessionStore.get();
        String seed;
        String saved = store.getLastServer();
        if (saved != null && isUsableHost(saved)) {
            seed = saved;
        } else if (SERVER_PRESETS.length > 0) {
            seed = SERVER_PRESETS[0][1];
        } else {
            seed = "127.0.0.1";
        }
        this.serverHostField.setText(seed);
        this.serverHostField.setPlaceholder("e.g. 100.55.103.226");
        // Try to align the preset cycler index with the seeded host so the
        // displayed preset label matches what's in the field on entry.
        for (int i = 0; i < SERVER_PRESETS.length; i++) {
            if (SERVER_PRESETS[i][1].equalsIgnoreCase(seed)) { this.presetIdx = i; break; }
        }
        // Hook up our typed-char sink so the focused field receives keystrokes.
        KeyHandler.textSink = this::onChar;
    }

    /**
     * True if {@code host} is something a raw {@code Socket(host, 2222)} can
     * actually resolve. Filters out the legacy nginx-route labels
     * ("useast", "euwest", "local") that only worked through the web
     * client's reverse proxy.
     */
    private static boolean isUsableHost(String host) {
        if (host == null) return false;
        String h = host.trim();
        if (h.isEmpty()) return false;
        if ("useast".equalsIgnoreCase(h) || "euwest".equalsIgnoreCase(h)
                || "local".equalsIgnoreCase(h)) return false;
        // localhost, IPs (contain dots) and FQDNs (contain dots) are fine.
        return "localhost".equalsIgnoreCase(h) || h.contains(".") || h.contains(":");
    }

    private void onChar(char c) {
        if (this.serverHostField.isFocused()) this.serverHostField.appendChar(c);
        else if (this.currentPw.isFocused()) this.currentPw.appendChar(c);
        else if (this.newPw.isFocused()) this.newPw.appendChar(c);
        else if (this.confirmPw.isFocused()) this.confirmPw.appendChar(c);
    }

    private List<CharacterDto> aliveChars() {
        List<CharacterDto> out = new ArrayList<>();
        if (this.account == null || this.account.getCharacters() == null) return out;
        for (CharacterDto c : this.account.getCharacters()) {
            if (c != null && !c.isDeleted()) out.add(c);
        }
        return out;
    }

    private List<CharacterDto> deadChars() {
        List<CharacterDto> out = new ArrayList<>();
        if (this.account == null || this.account.getCharacters() == null) return out;
        for (CharacterDto c : this.account.getCharacters()) {
            if (c != null && c.isDeleted()) out.add(c);
        }
        return out;
    }

    @Override
    public void update(double time) {
        // Drain pending background results.
        PlayerAccountDto refreshed = this.refreshResult.getAndSet(null);
        if (refreshed != null) {
            this.account = refreshed;
            this.busy = false;
        }
        String err = this.errorResult.getAndSet(null);
        if (err != null) {
            this.error = err;
            this.busy = false;
        }
        if (this.pendingLogout) {
            this.pendingLogout = false;
            this.doLogoutNow();
            return;
        }
        if (this.pendingPlay != null) {
            CharacterDto c = this.pendingPlay;
            this.pendingPlay = null;
            this.startGame(c);
            return;
        }

        // If the just-launched PlayState failed to connect to the game
        // server, pop it and surface the error here. The user can then edit
        // the host field and try again.
        PlayState live = this.gsm.getPlayState();
        if (live != null && live.getConnectError() != null) {
            this.error = "Connect failed: " + live.getConnectError();
            this.gsm.pop(GameStateManager.PLAY);
            // Re-claim the typed-char sink — startGame() cleared it.
            KeyHandler.textSink = this::onChar;
        }

        // Field caret blink + text input
        this.currentPw.update();
        this.newPw.update();
        this.serverHostField.update();
        this.confirmPw.update();
    }

    @Override
    public void input(MouseHandler mouse, KeyHandler key) {
        if (this.busy) return;

        boolean mouseDown = mouse.isPressed(1);
        boolean justClicked = mouseDown && !this.prevMouseDown;
        this.prevMouseDown = mouseDown;
        int mx = mouse.getX();
        int my = mouse.getY();

        // Layout regions are computed in render(); we recompute here so click
        // hit-tests stay aligned. Keeping layout in one helper would be nicer
        // but the read-only render() path is hot, so duplicate the math here.
        Layout L = this.layout();

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            // ESC defocuses any password field, then nothing — there's no
            // safe action to bind ESC to here ('logout to login' would be
            // surprising). User must click Logout explicitly.
            this.currentPw.setFocused(false);
            this.newPw.setFocused(false);
            this.confirmPw.setFocused(false);
            this.changePwOpen = false;
        }

        // Mouse wheel scroll. The wheel buffer is shared and consume-once,
        // so we route to whichever panel the cursor is over: leaderboard
        // first (right column), then character list (left). Anywhere else
        // we still consume to drain the buffer (avoids carryover firing
        // on the next state transition).
        Layout LL = this.layout();
        float wheel = KeyHandler.consumeScroll();
        if (wheel != 0f && this.leaderboard.containsPoint(mx, my)) {
            this.leaderboard.scrollBy(wheel > 0 ? 1 : -1);
        } else if (wheel != 0f
                && mx >= LL.listX && mx <= LL.listX + LL.listW
                && my >= LL.listY && my <= LL.listY + LL.listH) {
            int rowCount = ((this.tab == Tab.CHARACTERS) ? this.aliveChars().size() : this.deadChars().size());
            float maxScroll = Math.max(0f, rowCount * LL.rowH - LL.listH);
            this.scrollOffset = Math.max(0f, Math.min(maxScroll, this.scrollOffset + wheel * SCROLL_STEP));
        }
        // Arrow-key scroll fallback for keyboards / accessibility.
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_DOWN)) this.scrollOffset += LL.listH;
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_UP))   this.scrollOffset -= LL.listH;
        // Clamp here too so PageUp/Down can't escape valid bounds.
        {
            int rc = ((this.tab == Tab.CHARACTERS) ? this.aliveChars().size() : this.deadChars().size());
            float maxScroll = Math.max(0f, rc * LL.rowH - LL.listH);
            this.scrollOffset = Math.max(0f, Math.min(maxScroll, this.scrollOffset));
        }

        if (!justClicked) return;

        // Tabs
        if (hit(mx, my, L.tabCharsX, L.tabsY, L.tabW, L.tabH)) { this.tab = Tab.CHARACTERS; return; }
        if (hit(mx, my, L.tabGraveX, L.tabsY, L.tabW, L.tabH)) { this.tab = Tab.GRAVEYARD; return; }

        // Logout / change-pw / server / vault buttons in the right column
        // (these use the wider rightBtnW so the click region matches what's
        // actually rendered).
        if (hit(mx, my, L.logoutX, L.logoutY, L.rightBtnW, L.btnH)) { this.doLogout(); return; }
        if (hit(mx, my, L.changePwX, L.changePwY, L.rightBtnW, L.btnH)) {
            this.changePwOpen = !this.changePwOpen;
            return;
        }
        // Preset cycler: cycles through SERVER_PRESETS and writes the host
        // into the editable field. Sits 8 px below the field box.
        int presetY = L.serverY + L.btnH + 8;
        if (hit(mx, my, L.serverX, presetY, L.rightBtnW, 36)) {
            this.presetIdx = (this.presetIdx + 1) % SERVER_PRESETS.length;
            this.serverHostField.setText(SERVER_PRESETS[this.presetIdx][1]);
            return;
        }
        // Click into the editable game-server field to focus it. Defocus
        // any currently-focused field if the click lands elsewhere on the
        // server row but outside the field bounds.
        if (this.serverHostField.handleClick(mx, my)) {
            // Field grabbed focus — also clear the password fields.
            this.currentPw.setFocused(false);
            this.newPw.setFocused(false);
            this.confirmPw.setFocused(false);
            return;
        }
        if (hit(mx, my, L.addChestX, L.addChestY, L.rightBtnW, L.btnH)) { this.doAddChest(); return; }

        if (this.changePwOpen) {
            this.currentPw.setBounds(L.pwFieldX, L.pwFieldY,         L.pwFieldW, 32);
            this.newPw    .setBounds(L.pwFieldX, L.pwFieldY + 40,    L.pwFieldW, 32);
            this.confirmPw.setBounds(L.pwFieldX, L.pwFieldY + 80,    L.pwFieldW, 32);
            boolean cur = this.currentPw.handleClick(mx, my);
            boolean n   = this.newPw.handleClick(mx, my);
            boolean conf= this.confirmPw.handleClick(mx, my);
            if (!cur && !n && !conf) {
                this.currentPw.setFocused(false);
                this.newPw.setFocused(false);
                this.confirmPw.setFocused(false);
            }
            if (hit(mx, my, L.pwSubmitX, L.pwSubmitY, L.rightBtnW, L.btnH)) {
                this.doChangePassword();
                return;
            }
        }

        // Character list rows — apply scroll offset so the click target moves
        // with the visible row. Rows whose visible band falls outside the list
        // viewport are not clickable.
        List<CharacterDto> alive = this.aliveChars();
        List<CharacterDto> chars = (this.tab == Tab.CHARACTERS) ? alive : this.deadChars();
        for (int i = 0; i < chars.size(); i++) {
            int rowY = L.listY + i * L.rowH - (int) this.scrollOffset;
            // Skip if entirely outside the list viewport.
            if (rowY + L.rowH <= L.listY || rowY >= L.listY + L.listH) continue;
            if (hit(mx, my, L.listX, rowY, L.listW, L.rowH)
                    && my >= L.listY && my <= L.listY + L.listH) {
                if (this.tab == Tab.CHARACTERS) {
                    this.selectedCharIdx = i;
                }
                return;
            }
        }

        // Class picker (4 cols x N rows)
        int[] pickerIdsHit = this.pickerClassIds();
        for (int i = 0; i < pickerIdsHit.length; i++) {
            int col = i % 4;
            int row = i / 4;
            int x = L.pickerX + col * L.pickerCellW;
            int y = L.pickerY + row * L.pickerCellH;
            if (hit(mx, my, x, y, L.pickerCellW - 4, L.pickerCellH - 4)) {
                this.selectedClassId = pickerIdsHit[i];
                return;
            }
        }

        // Bottom action bar
        if (this.tab == Tab.CHARACTERS && this.selectedCharIdx >= 0 && this.selectedCharIdx < alive.size()
                && hit(mx, my, L.playX, L.playY, L.btnW, L.btnH)) {
            this.pendingPlay = alive.get(this.selectedCharIdx);
            return;
        }
        if (this.tab == Tab.CHARACTERS && this.selectedCharIdx >= 0 && this.selectedCharIdx < alive.size()
                && hit(mx, my, L.deleteX, L.deleteY, L.btnW, L.btnH)) {
            this.doDelete(alive.get(this.selectedCharIdx));
            return;
        }
        if (this.selectedClassId >= 0 && hit(mx, my, L.createX, L.createY, L.btnW, L.btnH)) {
            this.doCreateChar();
            return;
        }
    }

    @Override
    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        // Backdrop
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.08f, 0.07f, 0.10f, 1f);
        shapes.rect(0, 0, OpenRealmGame.width, OpenRealmGame.height);
        shapes.end();
        batch.begin();

        Layout L = this.layout();

        // Header
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        String ident = (this.account.getAccountName() != null ? this.account.getAccountName() : "Player")
                + "  *" + (this.account.getAccountFame() == null ? 0 : this.account.getAccountFame())
                + "   <" + (this.account.getAccountEmail() == null ? "" : this.account.getAccountEmail()) + ">";
        font.draw(batch, "Logged in as: " + ident, 32, 32);

        // Tabs
        this.drawTab(batch, shapes, font, L.tabCharsX, L.tabsY, L.tabW, L.tabH, "CHARACTERS", this.tab == Tab.CHARACTERS);
        this.drawTab(batch, shapes, font, L.tabGraveX, L.tabsY, L.tabW, L.tabH, "GRAVEYARD",  this.tab == Tab.GRAVEYARD);

        // List frame
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.08f, 0.10f, 0.95f);
        shapes.rect(L.listX, L.listY, L.listW, L.listH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.30f, 0.25f, 0.30f, 1f);
        shapes.rect(L.listX, L.listY, L.listW, L.listH);
        shapes.end();
        batch.begin();

        List<CharacterDto> alive = this.aliveChars();
        List<CharacterDto> chars = (this.tab == Tab.CHARACTERS) ? alive : this.deadChars();
        if (chars.isEmpty()) {
            font.setColor(0.55f, 0.50f, 0.45f, 1f);
            String empty = (this.tab == Tab.CHARACTERS)
                    ? "No characters yet — pick a class below to create one."
                    : "No fallen characters.";
            font.draw(batch, empty, L.listX + 16, L.listY + 32);
        } else {
            // Scissor-clip rendering to the list viewport so rows that scroll
            // partially in/out are cropped at the edges instead of bleeding
            // over the picker / leaderboard below.
            batch.flush();
            Rectangle scissor = new Rectangle();
            Rectangle clipBounds = new Rectangle(
                    L.listX, L.listY, L.listW, L.listH);
            ScissorStack.calculateScissors(
                    ScalingViewport.class.cast(null) == null
                            ? new OrthographicCamera() : null,
                    batch.getTransformMatrix(), clipBounds, scissor);
            // Calculator above can be unreliable with our flipped Y-down camera;
            // fall back to a manual clip rect: just trust list bounds.
            scissor.set(L.listX, OpenRealmGame.height - (L.listY + L.listH), L.listW, L.listH);
            ScissorStack.pushScissors(scissor);
            for (int i = 0; i < chars.size(); i++) {
                int rowY = L.listY + i * L.rowH - (int) this.scrollOffset;
                if (rowY + L.rowH <= L.listY) continue;            // above viewport
                if (rowY >= L.listY + L.listH) break;               // below viewport
                this.renderCharRow(batch, shapes, font, chars.get(i),
                        L.listX, rowY, L.listW, L.rowH,
                        this.tab == Tab.CHARACTERS && i == this.selectedCharIdx,
                        this.tab == Tab.GRAVEYARD);
            }
            batch.flush();
            ScissorStack.popScissors();

            // Scroll indicator: tiny tan thumb on the right edge of the list
            // so the user knows there's more content offscreen.
            int rowCount = chars.size();
            float contentH = rowCount * L.rowH;
            if (contentH > L.listH) {
                float thumbH = Math.max(24f, L.listH * (L.listH / contentH));
                float thumbY = L.listY + (this.scrollOffset / Math.max(1f, contentH - L.listH)) * (L.listH - thumbH);
                batch.end();
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                shapes.setColor(0.55f, 0.45f, 0.25f, 0.85f);
                shapes.rect(L.listX + L.listW - 6, thumbY, 4, thumbH);
                shapes.end();
                batch.begin();
            }
        }

        // Class picker — header above the cells with enough vertical
        // clearance that the 1.8x font doesn't overlap the first row.
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "CREATE CHARACTER", L.pickerX, L.pickerY - 32);
        int[] pickerIdsRender = this.pickerClassIds();
        for (int i = 0; i < pickerIdsRender.length; i++) {
            int classId = pickerIdsRender[i];
            int col = i % 4;
            int row = i / 4;
            int x = L.pickerX + col * L.pickerCellW;
            int y = L.pickerY + row * L.pickerCellH;
            this.renderClassOption(batch, shapes, font, classId, x, y, L.pickerCellW - 4, L.pickerCellH - 4,
                    this.selectedClassId == classId);
        }

        // Right column: account info, server, leaderboard, vault, logout, change password
        // Game-server config block — wrapped in a bordered panel that visually
        // groups the server label, host field, and preset cycler so the section
        // doesn't read as three loose controls floating in the gutter.
        int presetY = L.serverY + L.btnH + 8;
        int gsPad = 10;
        // Reserve full glyph height (≈30 at our 1.8x font) above the field so
        // the "GAME SERVER" header isn't sliced by the panel border.
        int gsLabelH = 32;
        int gsBoxX = L.serverX - gsPad;
        int gsBoxY = L.serverY - gsLabelH - gsPad;
        int gsBoxW = L.rightBtnW + gsPad * 2;
        int gsBoxH = (presetY + 36) - gsBoxY + gsPad;
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.10f, 0.09f, 0.12f, 0.85f);
        shapes.rect(gsBoxX, gsBoxY, gsBoxW, gsBoxH);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.45f, 0.36f, 0.22f, 1f);
        shapes.rect(gsBoxX, gsBoxY, gsBoxW, gsBoxH);
        shapes.end();
        batch.begin();

        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        // Baseline 12 px above the field — leaves the full glyph height
        // safely inside the panel border.
        font.draw(batch, "GAME SERVER", L.serverX, L.serverY - 12);
        this.serverHostField.setBounds(L.serverX, L.serverY, L.rightBtnW, L.btnH);
        this.serverHostField.render(batch, shapes, font);
        // Preset cycler 8 px below the field box. Drop the IP from the label —
        // the field above already shows the resolved host, and including the
        // full IP made the button text overflow its border. Append " >" as a
        // cycle affordance.
        String[] preset = SERVER_PRESETS[this.presetIdx];
        this.drawButton(batch, shapes, font, L.serverX, presetY, L.rightBtnW, 36,
                "Preset: " + preset[0] + "  >", false, false);

        // Vault block: label, then "+ Add Chest" button below.
        int vaultChests = (this.account.getPlayerVault() == null) ? 0 : this.account.getPlayerVault().size();
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "Vault Chests: " + vaultChests + "/10", L.addChestX, L.addChestY - 36);
        this.drawButton(batch, shapes, font, L.addChestX, L.addChestY, L.rightBtnW, L.btnH,
                "+ Add Chest", false, false);

        // Change password collapsible header — caret arrow indicates state.
        this.drawButton(batch, shapes, font, L.changePwX, L.changePwY, L.rightBtnW, L.btnH,
                this.changePwOpen ? "v Change Password" : "> Change Password", false, false);
        if (this.changePwOpen) {
            // Three 32-px-tall password fields stacked with 8 px gaps —
            // matches the 40-px stride used in the layout calculation.
            this.currentPw.setBounds(L.pwFieldX, L.pwFieldY,         L.pwFieldW, 32);
            this.newPw    .setBounds(L.pwFieldX, L.pwFieldY + 40,    L.pwFieldW, 32);
            this.confirmPw.setBounds(L.pwFieldX, L.pwFieldY + 80,    L.pwFieldW, 32);
            this.currentPw.render(batch, shapes, font);
            this.newPw    .render(batch, shapes, font);
            this.confirmPw.render(batch, shapes, font);
            this.drawButton(batch, shapes, font, L.pwSubmitX, L.pwSubmitY, L.rightBtnW, L.btnH,
                    "Update Password", false, false);
            if (!this.pwStatus.isEmpty()) {
                font.setColor(this.pwStatus.startsWith("OK") ? Color.LIME : Color.SALMON);
                font.draw(batch, this.pwStatus, L.pwFieldX, L.pwSubmitY + L.btnH + 12);
            }
        }

        // Logout
        this.drawButton(batch, shapes, font, L.logoutX, L.logoutY, L.rightBtnW, L.btnH, "Logout", false, false);

        // Leaderboard
        this.leaderboard.render(batch, shapes, font, L.lbX, L.lbY, L.lbW, L.lbH);

        // Bottom action buttons
        if (this.tab == Tab.CHARACTERS) {
            boolean canPlay = this.selectedCharIdx >= 0 && this.selectedCharIdx < alive.size();
            this.drawButton(batch, shapes, font, L.playX, L.playY, L.btnW, L.btnH,
                    "Play", true, !canPlay);
            this.drawButton(batch, shapes, font, L.deleteX, L.deleteY, L.btnW, L.btnH,
                    "Delete", false, !canPlay);
        }
        boolean canCreate = this.selectedClassId >= 0;
        this.drawButton(batch, shapes, font, L.createX, L.createY, L.btnW, L.btnH,
                "Create", false, !canCreate);

        if (!this.error.isEmpty()) {
            font.setColor(0.95f, 0.45f, 0.45f, 1f);
            font.draw(batch, this.error, 32, OpenRealmGame.height - 12);
        }
        font.setColor(Color.WHITE);
    }

    private void renderCharRow(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                               CharacterDto c, int x, int y, int w, int h,
                               boolean selected, boolean grayed) {
        // Row background (selected = warm tan)
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (selected) shapes.setColor(0.30f, 0.22f, 0.15f, 1f);
        else shapes.setColor(0.13f, 0.10f, 0.13f, 1f);
        shapes.rect(x, y, w, h);
        shapes.end();
        if (selected) {
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(0.78f, 0.66f, 0.43f, 1f);
            shapes.rect(x, y, w, h);
            shapes.end();
        }
        batch.begin();

        // Class icon — apply the character's stored dye so the row preview
        // matches what the player will look like in-game (web-parity).
        CharacterClass cc = CharacterClass.valueOf(c.getCharacterClass());
        if (cc != null) {
            try {
                SpriteSheet classImg = GameSpriteManager.loadClassSprites(cc);
                TextureRegion frame = classImg.getCurrentFrame();
                if (frame != null) {
                    final Integer dyeIdBoxed = c.getStats() != null ? c.getStats().getDyeId() : null;
                    final int dyeId = dyeIdBoxed != null ? dyeIdBoxed : 0;
                    TextureRegion drawFrame = frame;
                    if (dyeId > 0) {
                        final com.openrealm.game.model.AnimationModel anim =
                                GameDataManager.getAnimation("player", cc.classId);
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
                                    .getDyedRegion(anim.getSpriteKey(), cc.classId,
                                            row, col, spW, dyeId);
                            if (dyed != null) drawFrame = dyed;
                        }
                    }
                    batch.draw(drawFrame, x + 8, y + 8, h - 16, h - 16);
                }
            } catch (Exception ignored) {
                // Class sprite missing — skip so the rest of the row still renders.
            }
        }

        int textX = x + h + 12;
        int lvl = (c.getStats() != null && c.getStats().getXp() != null
                && GameDataManager.EXPERIENCE_LVLS != null
                && GameDataManager.EXPERIENCE_LVLS.isMaxLvl(c.getStats().getXp()))
                ? 20
                : (c.getStats() != null && c.getStats().getXp() != null && GameDataManager.EXPERIENCE_LVLS != null
                        ? GameDataManager.EXPERIENCE_LVLS.getLevel(c.getStats().getXp())
                        : 0);
        String className = (cc != null) ? cc.name() : "Unknown";
        // Two lines per row with proper vertical spacing. Line 1 = class +
        // level + maxed-count summary. Line 2 = stat block. UUID dropped —
        // it's noise the user explicitly asked to remove.
        int line1Y = y + 24;
        int line2Y = y + 60;
        font.setColor(grayed ? new Color(0.55f, 0.55f, 0.55f, 1f) : Color.WHITE);
        font.draw(batch, className + "    Lv " + lvl + "    " + c.numStatsMaxed() + "/8 maxed",
                textX, line1Y);
        if (c.getStats() != null) {
            font.setColor(0.75f, 0.70f, 0.60f, 1f);
            font.draw(batch,
                    String.format("HP %d  MP %d  STR %d  DEF %d  SPD %d  DEX %d  VIT %d  WIS %d",
                        nz(c.getStats().getHp()), nz(c.getStats().getMp()),
                        nz(c.getStats().getStr()), nz(c.getStats().getDef()),
                        nz(c.getStats().getSpd()), nz(c.getStats().getDex()),
                        nz(c.getStats().getVit()), nz(c.getStats().getWis())),
                    textX, line2Y);
        }
        // Graveyard rows show the death date; live rows just leave the third
        // line blank for breathing room.
        if (grayed && c.getDeleted() != null) {
            font.setColor(0.55f, 0.40f, 0.40f, 1f);
            font.draw(batch, "Died: " + DEATH_FMT.format(c.getDeleted()), textX, y + 88);
        }
    }

    private static int nz(Integer i) { return i == null ? 0 : i; }

    private void renderClassOption(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                                   int classId, int x, int y, int w, int h, boolean selected) {
        batch.end();
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (selected) shapes.setColor(0.30f, 0.22f, 0.15f, 1f);
        else shapes.setColor(0.13f, 0.10f, 0.13f, 1f);
        shapes.rect(x, y, w, h);
        shapes.end();
        if (selected) {
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(0.78f, 0.66f, 0.43f, 1f);
            shapes.rect(x, y, w, h);
            shapes.end();
        }
        batch.begin();

        CharacterClass cc = CharacterClass.valueOf(classId);
        if (cc != null) {
            try {
                SpriteSheet classImg = GameSpriteManager.loadClassSprites(cc);
                TextureRegion frame = classImg.getCurrentFrame();
                if (frame != null) batch.draw(frame, x + 4, y + 4, 32, 32);
            } catch (Exception ignored) { }
        }
        font.setColor(selected ? Color.WHITE : new Color(0.85f, 0.80f, 0.70f, 1f));
        font.draw(batch, this.classNameFor(classId), x + 40, y + 20);
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
        drawCenteredInBox(batch, font, label, x, y, w, h);
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
        drawCenteredInBox(batch, font, label, x, y, w, h);
        font.setColor(Color.WHITE);
    }

    /**
     * Center text both axes inside (x, y, w, h). The previous heuristic
     * {@code y + h * 0.65f} put the baseline below the box for a
     * 1.8x-scaled flipped BitmapFont, which made every button look struck-
     * through by its own border. GlyphLayout gives us a real width/height
     * for the glyph run so centering is exact.
     */
    private static void drawCenteredInBox(SpriteBatch batch, BitmapFont font,
                                          String text, int x, int y, int w, int h) {
        GlyphLayout layout =
                new GlyphLayout(font, text);
        float tx = x + (w - layout.width) / 2f;
        float ty = y + (h - layout.height) / 2f;
        font.draw(batch, text, tx, ty);
    }

    private static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /** Pre-computed layout coordinates so input + render agree. */
    private static class Layout {
        int tabsY, tabH, tabW, tabCharsX, tabGraveX;
        int listX, listY, listW, listH, rowH;
        int pickerX, pickerY, pickerCellW, pickerCellH;
        int playX, playY, deleteX, deleteY, createX, createY;
        int btnW, btnH;
        /** Right-column buttons stretch wider than the bottom action ones. */
        int rightBtnW;
        int serverX, serverY;
        int addChestX, addChestY;
        int changePwX, changePwY;
        int pwFieldX, pwFieldY, pwFieldW;
        int pwSubmitX, pwSubmitY;
        int logoutX, logoutY;
        int lbX, lbY, lbW, lbH;
    }

    private Layout layout() {
        Layout L = new Layout();
        int width = OpenRealmGame.width;
        int height = OpenRealmGame.height;
        // Two columns: left = char list + class picker, right = account
        // panel + leaderboard. Constants tuned so labels above controls
        // don't overlap and bottom action buttons clear all chrome.
        int leftPad = 32;
        // Wider right column so the leaderboard rows can fit account name +
        // class + level on one line without colliding with the equipment row
        // beneath. 340 was too tight at 1.8x font; names truncated to a
        // single character.
        int rightW = 420;
        int leftW = width - rightW - leftPad - 24; // 24 = gap between cols
        L.tabsY = 64;
        L.tabH = 40;
        L.tabW = 220;
        L.tabCharsX = leftPad;
        L.tabGraveX = leftPad + L.tabW + 8;

        L.listX = leftPad;
        L.listY = L.tabsY + L.tabH + 12;
        L.listW = leftW;
        L.listH = 360;
        // Taller rows so the class title and stat line each get their own
        // breathing room — was 84, but the 1.8x font wants ~30/line and
        // we render two lines plus padding.
        L.rowH = 96;

        // Picker header sits ABOVE pickerY, so reserve space for the label
        // height (~28px for the 1.8x font) plus a gap.
        int pickerHeaderH = 32;
        L.pickerX = leftPad;
        L.pickerY = L.listY + L.listH + 24 + pickerHeaderH;
        L.pickerCellW = leftW / 4;
        L.pickerCellH = 48;

        L.btnW = 160;
        L.btnH = 40;
        L.rightBtnW = rightW - 20; // 20 keeps the right-side gutter clear
        L.playX = leftPad;
        L.playY = height - L.btnH - 24;
        L.deleteX = leftPad + L.btnW + 12;
        L.deleteY = L.playY;
        L.createX = leftPad + 2 * (L.btnW + 12);
        L.createY = L.playY;

        // Right column laid out top-down with generous, non-overlapping
        // spacing. Each block reserves space for its own label, field/
        // button, and a clear gap before the next block. Constants are
        // tuned so a 1.8x BitmapFont's actual glyph heights fit cleanly.
        int rx = width - rightW;
        int labelH = 28;          // 1.8x font line height ≈ 28
        int rowGap = 24;          // gap between blocks
        int presetH = 36;         // preset cycler height
        int fieldH = L.btnH;      // input fields share btnH
        int ry = 56;

        // -- Game Server block --
        // Label
        // [ Editable field ]
        // [ Preset cycler ]
        L.serverX = rx;
        L.serverY = ry + labelH + 8;                                    // field starts under label + 8 padding
        int presetY_local = L.serverY + fieldH + 8;                     // preset under field + 8
        ry = presetY_local + presetH + rowGap;                          // next block starts after preset + rowGap

        // -- Vault block --
        // Label
        // [ + Add Chest button ]
        L.addChestX = rx;
        L.addChestY = ry + labelH + 8;                                  // button under label + 8 padding
        ry = L.addChestY + L.btnH + rowGap;

        // -- Change Password collapsible block --
        L.changePwX = rx;
        L.changePwY = ry;
        ry = L.changePwY + L.btnH + 12;
        L.pwFieldX = rx;
        L.pwFieldY = ry;
        L.pwFieldW = rightW - 40;
        // 3 password fields stacked at 40 px each + 8 px gap between rows
        if (this.changePwOpen) {
            L.pwSubmitX = rx;
            L.pwSubmitY = ry + 3 * 40 + 16;                             // 3 fields * 40 + 16 padding
            ry = L.pwSubmitY + L.btnH + rowGap;
        } else {
            L.pwSubmitX = rx;
            L.pwSubmitY = ry;                                           // unused when closed
            ry += rowGap / 2;
        }

        // -- Logout --
        L.logoutX = rx;
        L.logoutY = ry;

        // Leaderboard fills the right column from just below the Logout
        // button down to the bottom margin. Previously it was a fixed 280 px
        // pinned to the corner, which left a big empty gap and crammed the
        // rows. Computing the height from logoutY makes it expand as far as
        // the column allows so each entry has real room to render the class
        // sprite + equipment row.
        L.lbW = rightW - 20;
        L.lbX = rx;
        L.lbY = L.logoutY + L.btnH + 16;
        L.lbH = Math.max(160, height - L.lbY - 24);
        return L;
    }

    // --- Actions ---

    private void doLogout() {
        this.pendingLogout = true;
    }

    private void doLogoutNow() {
        SessionStore store = SessionStore.get();
        store.clearSession();
        // Note: guest creds intentionally preserved — clicking Play as Guest
        // again on the login screen should rejoin the same guest account.
        ClientGameLogic.DATA_SERVICE.setSessionToken(null);
        KeyHandler.textSink = null;
        this.gsm.pop(GameStateManager.CHARSELECT);
        LoginState login = new LoginState(this.gsm);
        this.gsm.add(GameStateManager.LOGIN, login);
    }

    private void startGame(CharacterDto c) {
        SocketClient.CHARACTER_UUID = c.getCharacterUuid();
        // Take the user-typed server host as authoritative — overrides any
        // previously cycled label. Reject the legacy nginx-route labels
        // outright so a stale "useast" doesn't trigger another UnknownHost
        // crash; the user must enter a real IP or FQDN.
        String host = this.serverHostField.getText().trim();
        if (!isUsableHost(host)) {
            this.error = "Enter a real host (e.g. 127.0.0.1 or openrealm.net) — '"
                    + host + "' isn't a resolvable address.";
            return;
        }
        SocketClient.SERVER_ADDR = host;
        SessionStore.get().setLastServer(host);
        SessionStore.get().save();
        log.info("[CHARSELECT] starting PlayState with characterUuid={} server={}",
                c.getCharacterUuid(), SocketClient.SERVER_ADDR);
        KeyHandler.textSink = null;
        this.gsm.pop(GameStateManager.CHARSELECT);
        // PlayState fetches the account itself when needed (PauseState path),
        // but seed it with what we already have so Pause renders immediately.
        PlayState play = new PlayState(this.gsm, GameStateManager.cam);
        play.setAccount(this.account);
        this.gsm.add(GameStateManager.PLAY, play);
    }

    private void doDelete(CharacterDto c) {
        if (c == null || c.getCharacterUuid() == null) return;
        this.busy = true;
        OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        new Thread(() -> {
            try {
                svc.deleteCharacter(c.getCharacterUuid());
                PlayerAccountDto acct = svc.getAccount(this.account.getAccountUuid());
                this.refreshResult.set(acct);
                this.selectedCharIdx = -1;
            } catch (Exception e) {
                log.error("[DELETE] failed: {}", e.getMessage());
                this.errorResult.set("Delete failed: " + e.getMessage());
            }
        }, "openrealm-delete-char").start();
    }

    private void doCreateChar() {
        if (this.selectedClassId < 0) return;
        this.busy = true;
        int classId = this.selectedClassId;
        OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        new Thread(() -> {
            try {
                svc.createCharacter(this.account.getAccountUuid(), classId);
                PlayerAccountDto acct = svc.getAccount(this.account.getAccountUuid());
                this.refreshResult.set(acct);
                this.selectedClassId = -1;
            } catch (Exception e) {
                log.error("[CREATE] failed: {}", e.getMessage());
                this.errorResult.set("Create failed: " + e.getMessage());
            }
        }, "openrealm-create-char").start();
    }

    private void doAddChest() {
        this.busy = true;
        OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        new Thread(() -> {
            try {
                svc.createChest(this.account.getAccountUuid());
                PlayerAccountDto acct = svc.getAccount(this.account.getAccountUuid());
                this.refreshResult.set(acct);
            } catch (Exception e) {
                log.error("[CHEST] failed: {}", e.getMessage());
                this.errorResult.set("Add chest failed: " + e.getMessage());
            }
        }, "openrealm-add-chest").start();
    }

    private void doChangePassword() {
        String cur = this.currentPw.getText();
        String nw  = this.newPw.getText();
        String conf= this.confirmPw.getText();
        if (cur.isEmpty() || nw.isEmpty() || conf.isEmpty()) {
            this.pwStatus = "All fields required.";
            return;
        }
        if (!nw.equals(conf)) {
            this.pwStatus = "Passwords do not match.";
            return;
        }
        this.pwStatus = "Updating...";
        OpenRealmClientDataService svc = ClientGameLogic.DATA_SERVICE;
        new Thread(() -> {
            try {
                JsonNode resp = svc.changePassword(cur, nw);
                this.pwStatus = "OK: Password updated.";
                this.currentPw.setText("");
                this.newPw.setText("");
                this.confirmPw.setText("");
                if (resp != null) log.info("[CHANGE_PW] response: {}", resp);
            } catch (Exception e) {
                log.error("[CHANGE_PW] failed: {}", e.getMessage());
                this.pwStatus = "Failed: " + e.getMessage();
            }
        }, "openrealm-change-pw").start();
    }
}
