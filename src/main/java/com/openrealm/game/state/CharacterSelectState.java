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
import com.openrealm.game.graphics.SpriteRecolorCache;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.model.AnimationModel;
import com.openrealm.game.model.CharacterClassModel;
import com.openrealm.game.ui.CharacterStatsWindow;
import com.openrealm.game.ui.LeaderboardPanel;
import com.openrealm.game.ui.TextField;
import com.openrealm.game.ui.UiRender;
import com.openrealm.net.client.ClientGameLogic;
import com.openrealm.net.client.SocketClient;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack;
import com.badlogic.gdx.utils.viewport.ScalingViewport;

/**
 * Visual character-select screen. HTTP calls run on background threads and land
 * in atomic refs consumed on the GL thread.
 */
@Slf4j
public class CharacterSelectState extends GameState {

    private enum Tab { CHARACTERS, GRAVEYARD }

    // label -> host shortcuts for the cycler. Legacy nginx-route names
    // ("useast" etc.) don't resolve on the raw-TCP native client, so these are
    // real hosts.
    private static final String[][] SERVER_PRESETS = {
            { "USEast",    "100.55.103.226" },
            { "Local",     "127.0.0.1" },
            { "Localhost", "localhost" }
    };
    // Fallback only while GameDataManager hasn't loaded character-classes.json.
    private static final String[] CLASS_NAMES = {
            "Rogue","Archer","Wizard","Priest","Warrior","Knight","Paladin",
            "Assassin","Necromancer","Mystic","Trickster","Sorcerer","Huntress","Ninja",
            "Heavy Debuffer","Heavy Buffer","Heavy DPS","Heavy Oddball"
    };
    private static final SimpleDateFormat DEATH_FMT = new SimpleDateFormat("yyyy-MM-dd");
    private static final float SCROLL_STEP = 56f;

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
    private final TextField serverHostField = new TextField(0, 0, 200, 32);

    private final LeaderboardPanel leaderboard = new LeaderboardPanel();
    private final CharacterStatsWindow statsWindow = new CharacterStatsWindow();

    // Result fields from background threads; consumed on the GL thread.
    private final AtomicReference<PlayerAccountDto> refreshResult = new AtomicReference<>();
    private final AtomicReference<String> errorResult = new AtomicReference<>();
    private CharacterDto pendingPlay = null;
    private boolean pendingLogout = false;

    private boolean prevMouseDown = false;
    private boolean prevRightDown = false;
    private float scrollOffset = 0f;
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
        // Seed order: saved host, then first preset, then loopback. The
        // data-service host is deliberately NOT a fallback (game server runs
        // elsewhere in prod).
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
        for (int i = 0; i < SERVER_PRESETS.length; i++) {
            if (SERVER_PRESETS[i][1].equalsIgnoreCase(seed)) { this.presetIdx = i; break; }
        }
        KeyHandler.textSink = this::onChar;
    }

    /** Sorted class ids for the picker, from JSON when loaded else CLASS_NAMES indices. */
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

    private String classNameFor(int classId) {
        if (GameDataManager.CHARACTER_CLASSES != null) {
            CharacterClassModel m = GameDataManager.CHARACTER_CLASSES.get(classId);
            if (m != null && m.getClassName() != null) return m.getClassName();
        }
        return classId >= 0 && classId < CLASS_NAMES.length ? CLASS_NAMES[classId] : ("Class " + classId);
    }

    /** Rejects the legacy nginx-route labels that a raw Socket can't resolve. */
    private static boolean isUsableHost(String host) {
        if (host == null) return false;
        String h = host.trim();
        if (h.isEmpty()) return false;
        if ("useast".equalsIgnoreCase(h) || "euwest".equalsIgnoreCase(h)
                || "local".equalsIgnoreCase(h)) return false;
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
        this.statsWindow.update();
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

        // Surface a failed connect from the just-launched PlayState.
        PlayState live = this.gsm.getPlayState();
        if (live != null && live.getConnectError() != null) {
            this.error = "Connect failed: " + live.getConnectError();
            this.gsm.pop(GameStateManager.PLAY);
            KeyHandler.textSink = this::onChar; // startGame() cleared it
        }

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

        // Recomputed here so hit-tests match render().
        CharacterSelectLayout L = this.layout();

        // Open stats report owns all input; route the wheel, swallow the rest.
        if (this.statsWindow.isVisible()) {
            float wheelStats = KeyHandler.consumeScroll();
            if (wheelStats != 0f) this.statsWindow.onWheel(wheelStats);
            this.prevRightDown = mouse.isPressed(3);
            return;
        }

        // Right-click a character row -> open its lifetime stats report.
        boolean rightDown = mouse.isPressed(3);
        boolean rightJustClicked = rightDown && !this.prevRightDown;
        this.prevRightDown = rightDown;
        if (rightJustClicked) {
            List<CharacterDto> rc = (this.tab == Tab.CHARACTERS) ? this.aliveChars() : this.deadChars();
            for (int i = 0; i < rc.size(); i++) {
                int rowY = L.listY + i * L.rowH - (int) this.scrollOffset;
                if (rowY + L.rowH <= L.listY || rowY >= L.listY + L.listH) continue;
                if (hit(mx, my, L.listX, rowY, L.listW, L.rowH)
                        && my >= L.listY && my <= L.listY + L.listH) {
                    CharacterDto rcChar = rc.get(i);
                    int rcClass = rcChar.getCharacterClass() == null ? -1 : rcChar.getCharacterClass();
                    this.statsWindow.show(rcChar, this.classNameFor(rcClass));
                    return;
                }
            }
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            this.currentPw.setFocused(false);
            this.newPw.setFocused(false);
            this.confirmPw.setFocused(false);
            this.changePwOpen = false;
        }

        // Route the wheel to whichever panel the cursor is over; consume
        // either way so a notch doesn't leak into the next state.
        CharacterSelectLayout LL = this.layout();
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
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_DOWN)) this.scrollOffset += LL.listH;
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_UP))   this.scrollOffset -= LL.listH;
        {
            int rc = ((this.tab == Tab.CHARACTERS) ? this.aliveChars().size() : this.deadChars().size());
            float maxScroll = Math.max(0f, rc * LL.rowH - LL.listH);
            this.scrollOffset = Math.max(0f, Math.min(maxScroll, this.scrollOffset));
        }

        if (!justClicked) return;

        // Tabs
        if (hit(mx, my, L.tabCharsX, L.tabsY, L.tabW, L.tabH)) { this.tab = Tab.CHARACTERS; return; }
        if (hit(mx, my, L.tabGraveX, L.tabsY, L.tabW, L.tabH)) { this.tab = Tab.GRAVEYARD; return; }

        if (hit(mx, my, L.logoutX, L.logoutY, L.rightBtnW, L.btnH)) { this.doLogout(); return; }
        if (hit(mx, my, L.changePwX, L.changePwY, L.rightBtnW, L.btnH)) {
            this.changePwOpen = !this.changePwOpen;
            return;
        }
        int presetY = L.serverY + L.btnH + 8;
        if (hit(mx, my, L.serverX, presetY, L.rightBtnW, 36)) {
            this.presetIdx = (this.presetIdx + 1) % SERVER_PRESETS.length;
            this.serverHostField.setText(SERVER_PRESETS[this.presetIdx][1]);
            return;
        }
        if (this.serverHostField.handleClick(mx, my)) {
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

        List<CharacterDto> alive = this.aliveChars();
        List<CharacterDto> chars = (this.tab == Tab.CHARACTERS) ? alive : this.deadChars();
        for (int i = 0; i < chars.size(); i++) {
            int rowY = L.listY + i * L.rowH - (int) this.scrollOffset;
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
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        UiRender.fillRect(batch, shapes, 0, 0, OpenRealmGame.width, OpenRealmGame.height,
                new Color(0.08f, 0.07f, 0.10f, 1f));

        CharacterSelectLayout L = this.layout();

        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        String ident = (this.account.getAccountName() != null ? this.account.getAccountName() : "Player")
                + "  *" + (this.account.getAccountFame() == null ? 0 : this.account.getAccountFame())
                + "   <" + (this.account.getAccountEmail() == null ? "" : this.account.getAccountEmail()) + ">";
        font.draw(batch, "Logged in as: " + ident, 32, 32);
        font.setColor(0.55f, 0.50f, 0.45f, 1f);
        font.draw(batch, "Tip: right-click a character for lifetime stats", 32, 48);

        this.drawTab(batch, shapes, font, L.tabCharsX, L.tabsY, L.tabW, L.tabH, "CHARACTERS", this.tab == Tab.CHARACTERS);
        this.drawTab(batch, shapes, font, L.tabGraveX, L.tabsY, L.tabW, L.tabH, "GRAVEYARD",  this.tab == Tab.GRAVEYARD);

        UiRender.panel(batch, shapes, L.listX, L.listY, L.listW, L.listH,
                new Color(0.10f, 0.08f, 0.10f, 0.95f), new Color(0.30f, 0.25f, 0.30f, 1f));

        List<CharacterDto> alive = this.aliveChars();
        List<CharacterDto> chars = (this.tab == Tab.CHARACTERS) ? alive : this.deadChars();
        if (chars.isEmpty()) {
            font.setColor(0.55f, 0.50f, 0.45f, 1f);
            String empty = (this.tab == Tab.CHARACTERS)
                    ? "No characters yet - pick a class below to create one."
                    : "No fallen characters.";
            font.draw(batch, empty, L.listX + 16, L.listY + 32);
        } else {
            // Clip rows to the list viewport. calculateScissors is unreliable
            // with the flipped Y-down camera, so trust the manual rect below.
            batch.flush();
            Rectangle scissor = new Rectangle();
            Rectangle clipBounds = new Rectangle(
                    L.listX, L.listY, L.listW, L.listH);
            ScissorStack.calculateScissors(
                    ScalingViewport.class.cast(null) == null
                            ? new OrthographicCamera() : null,
                    batch.getTransformMatrix(), clipBounds, scissor);
            scissor.set(L.listX, OpenRealmGame.height - (L.listY + L.listH), L.listW, L.listH);
            ScissorStack.pushScissors(scissor);
            for (int i = 0; i < chars.size(); i++) {
                int rowY = L.listY + i * L.rowH - (int) this.scrollOffset;
                if (rowY + L.rowH <= L.listY) continue;
                if (rowY >= L.listY + L.listH) break;
                this.renderCharRow(batch, shapes, font, chars.get(i),
                        L.listX, rowY, L.listW, L.rowH,
                        this.tab == Tab.CHARACTERS && i == this.selectedCharIdx,
                        this.tab == Tab.GRAVEYARD);
            }
            batch.flush();
            ScissorStack.popScissors();

            int rowCount = chars.size();
            float contentH = rowCount * L.rowH;
            if (contentH > L.listH) {
                float thumbH = Math.max(24f, L.listH * (L.listH / contentH));
                float thumbY = L.listY + (this.scrollOffset / Math.max(1f, contentH - L.listH)) * (L.listH - thumbH);
                UiRender.fillRect(batch, shapes, L.listX + L.listW - 6, thumbY, 4, thumbH,
                        new Color(0.55f, 0.45f, 0.25f, 0.85f));
            }
        }

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

        // Game-server block: panel grouping label + host field + preset cycler.
        int presetY = L.serverY + L.btnH + 8;
        int gsPad = 10;
        int gsLabelH = 32;
        int gsBoxX = L.serverX - gsPad;
        int gsBoxY = L.serverY - gsLabelH - gsPad;
        int gsBoxW = L.rightBtnW + gsPad * 2;
        int gsBoxH = (presetY + 36) - gsBoxY + gsPad;
        UiRender.panel(batch, shapes, gsBoxX, gsBoxY, gsBoxW, gsBoxH,
                new Color(0.10f, 0.09f, 0.12f, 0.85f), new Color(0.45f, 0.36f, 0.22f, 1f));

        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "GAME SERVER", L.serverX, L.serverY - 12);
        this.serverHostField.setBounds(L.serverX, L.serverY, L.rightBtnW, L.btnH);
        this.serverHostField.render(batch, shapes, font);
        String[] preset = SERVER_PRESETS[this.presetIdx];
        this.drawButton(batch, shapes, font, L.serverX, presetY, L.rightBtnW, 36,
                "Preset: " + preset[0] + "  >", false, false);

        int vaultChests = (this.account.getPlayerVault() == null) ? 0 : this.account.getPlayerVault().size();
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "Vault Chests: " + vaultChests + "/10", L.addChestX, L.addChestY - 36);
        this.drawButton(batch, shapes, font, L.addChestX, L.addChestY, L.rightBtnW, L.btnH,
                "+ Add Chest", false, false);

        this.drawButton(batch, shapes, font, L.changePwX, L.changePwY, L.rightBtnW, L.btnH,
                this.changePwOpen ? "v Change Password" : "> Change Password", false, false);
        if (this.changePwOpen) {
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

        this.drawButton(batch, shapes, font, L.logoutX, L.logoutY, L.rightBtnW, L.btnH, "Logout", false, false);

        this.leaderboard.render(batch, shapes, font, L.lbX, L.lbY, L.lbW, L.lbH);

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

        // Right-click stats report overlays everything else.
        this.statsWindow.render(batch, shapes, font);
    }

    private void renderCharRow(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font,
                               CharacterDto c, int x, int y, int w, int h,
                               boolean selected, boolean grayed) {
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

        // Apply the character's stored dye so the row preview matches in-game.
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
                        final AnimationModel anim =
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
                            TextureRegion dyed = SpriteRecolorCache
                                    .getDyedRegion(anim.getSpriteKey(), cc.classId,
                                            row, col, spW, dyeId);
                            if (dyed != null) drawFrame = dyed;
                        }
                    }
                    batch.draw(drawFrame, x + 8, y + 8, h - 16, h - 16);
                }
            } catch (Exception ignored) {
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
        UiRender.drawCenteredIn(batch, font, label, x, y, w, h);
        font.setColor(Color.WHITE);
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

    private static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private CharacterSelectLayout layout() {
        CharacterSelectLayout L = new CharacterSelectLayout();
        int width = OpenRealmGame.width;
        int height = OpenRealmGame.height;
        // Left column = char list + picker; right column = account + leaderboard.
        int leftPad = 32;
        int rightW = 525;
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
        L.rowH = 96;

        // Picker header sits ABOVE pickerY; reserve label height + gap.
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

        // Right column, top-down: each block reserves label + control + gap.
        int rx = width - rightW;
        int labelH = 28;
        int rowGap = 24;
        int presetH = 36;
        int fieldH = L.btnH;
        int ry = 56;

        // Game server block: label, field, preset cycler.
        L.serverX = rx;
        L.serverY = ry + labelH + 8;
        int presetY_local = L.serverY + fieldH + 8;
        ry = presetY_local + presetH + rowGap;

        // Vault block: label, Add Chest button.
        L.addChestX = rx;
        L.addChestY = ry + labelH + 8;
        ry = L.addChestY + L.btnH + rowGap;

        // Change Password collapsible block.
        L.changePwX = rx;
        L.changePwY = ry;
        ry = L.changePwY + L.btnH + 12;
        L.pwFieldX = rx;
        L.pwFieldY = ry;
        L.pwFieldW = rightW - 40;
        if (this.changePwOpen) {
            L.pwSubmitX = rx;
            L.pwSubmitY = ry + 3 * 40 + 16; // 3 fields * 40 + padding
            ry = L.pwSubmitY + L.btnH + rowGap;
        } else {
            L.pwSubmitX = rx;
            L.pwSubmitY = ry;
            ry += rowGap / 2;
        }

        L.logoutX = rx;
        L.logoutY = ry;

        // Leaderboard fills the column from below Logout to the bottom margin.
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
        // Guest creds deliberately preserved so Play as Guest rejoins the account.
        ClientGameLogic.DATA_SERVICE.setSessionToken(null);
        KeyHandler.textSink = null;
        this.gsm.pop(GameStateManager.CHARSELECT);
        LoginState login = new LoginState(this.gsm);
        this.gsm.add(GameStateManager.LOGIN, login);
    }

    private void startGame(CharacterDto c) {
        SocketClient.CHARACTER_UUID = c.getCharacterUuid();
        // User-typed host is authoritative; reject legacy nginx-route labels.
        String host = this.serverHostField.getText().trim();
        if (!isUsableHost(host)) {
            this.error = "Enter a real host (e.g. 127.0.0.1 or openrealm.net) - '"
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
        // Seed the account so Pause renders immediately.
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
