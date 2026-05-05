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

    private static final String[] SERVERS = { "useast", "local", "localhost" };
    private static final String[] CLASS_NAMES = {
            "Rogue","Archer","Wizard","Priest","Warrior","Knight","Paladin",
            "Assassin","Necromancer","Mystic","Trickster","Sorcerer","Huntress","Ninja"
    };
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

    private final LeaderboardPanel leaderboard = new LeaderboardPanel();

    /** Result fields from background threads; consumed on the GL thread. */
    private final AtomicReference<PlayerAccountDto> refreshResult = new AtomicReference<>();
    private final AtomicReference<String> errorResult = new AtomicReference<>();
    /** When non-null, transition to PlayState using this character. */
    private CharacterDto pendingPlay = null;
    /** When true, transition back to LoginState (logout). */
    private boolean pendingLogout = false;

    private boolean prevMouseDown = false;
    private float scrollOffset = 0f;

    public CharacterSelectState(GameStateManager gsm, PlayerAccountDto account) {
        super(gsm);
        this.account = account;
        this.currentPw.setPassword(true);
        this.newPw.setPassword(true);
        this.confirmPw.setPassword(true);
        this.currentPw.setPlaceholder("Current password");
        this.newPw.setPlaceholder("New password");
        this.confirmPw.setPlaceholder("Confirm new password");
        SessionStore store = SessionStore.get();
        if (store.getLastServer() != null) {
            for (int i = 0; i < SERVERS.length; i++) {
                if (SERVERS[i].equals(store.getLastServer())) { this.serverIdx = i; break; }
            }
        }
        // Hook up our typed-char sink so password fields receive keystrokes.
        KeyHandler.textSink = this::onChar;
    }

    private void onChar(char c) {
        if (this.currentPw.isFocused()) this.currentPw.appendChar(c);
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

        // Field caret blink + text input
        this.currentPw.update();
        this.newPw.update();
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

        if (!justClicked) return;

        // Tabs
        if (hit(mx, my, L.tabCharsX, L.tabsY, L.tabW, L.tabH)) { this.tab = Tab.CHARACTERS; return; }
        if (hit(mx, my, L.tabGraveX, L.tabsY, L.tabW, L.tabH)) { this.tab = Tab.GRAVEYARD; return; }

        // Logout / change-pw / server / vault buttons in the right column
        if (hit(mx, my, L.logoutX, L.logoutY, L.btnW, L.btnH)) { this.doLogout(); return; }
        if (hit(mx, my, L.changePwX, L.changePwY, L.btnW, L.btnH)) {
            this.changePwOpen = !this.changePwOpen;
            return;
        }
        if (hit(mx, my, L.serverX, L.serverY, L.btnW, L.btnH)) {
            this.serverIdx = (this.serverIdx + 1) % SERVERS.length;
            SessionStore.get().setLastServer(SERVERS[this.serverIdx]);
            SessionStore.get().save();
            return;
        }
        if (hit(mx, my, L.addChestX, L.addChestY, L.btnW, L.btnH)) { this.doAddChest(); return; }

        if (this.changePwOpen) {
            this.currentPw.setBounds(L.pwFieldX, L.pwFieldY,         L.pwFieldW, 28);
            this.newPw    .setBounds(L.pwFieldX, L.pwFieldY + 36,    L.pwFieldW, 28);
            this.confirmPw.setBounds(L.pwFieldX, L.pwFieldY + 72,    L.pwFieldW, 28);
            boolean cur = this.currentPw.handleClick(mx, my);
            boolean n   = this.newPw.handleClick(mx, my);
            boolean conf= this.confirmPw.handleClick(mx, my);
            if (!cur && !n && !conf) {
                this.currentPw.setFocused(false);
                this.newPw.setFocused(false);
                this.confirmPw.setFocused(false);
            }
            if (hit(mx, my, L.pwSubmitX, L.pwSubmitY, L.btnW, L.btnH)) {
                this.doChangePassword();
                return;
            }
        }

        // Character list rows
        List<CharacterDto> alive = this.aliveChars();
        List<CharacterDto> chars = (this.tab == Tab.CHARACTERS) ? alive : this.deadChars();
        for (int i = 0; i < chars.size(); i++) {
            int rowY = L.listY + i * L.rowH;
            if (rowY + L.rowH > L.listY + L.listH) break;
            if (hit(mx, my, L.listX, rowY, L.listW, L.rowH)) {
                if (this.tab == Tab.CHARACTERS) {
                    this.selectedCharIdx = i;
                }
                return;
            }
        }

        // Class picker (4 cols x N rows)
        for (int i = 0; i < CLASS_NAMES.length; i++) {
            int col = i % 4;
            int row = i / 4;
            int x = L.pickerX + col * L.pickerCellW;
            int y = L.pickerY + row * L.pickerCellH;
            if (hit(mx, my, x, y, L.pickerCellW - 4, L.pickerCellH - 4)) {
                this.selectedClassId = i;
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
            for (int i = 0; i < chars.size(); i++) {
                int rowY = L.listY + i * L.rowH;
                if (rowY + L.rowH > L.listY + L.listH) break;
                this.renderCharRow(batch, shapes, font, chars.get(i),
                        L.listX, rowY, L.listW, L.rowH,
                        this.tab == Tab.CHARACTERS && i == this.selectedCharIdx,
                        this.tab == Tab.GRAVEYARD);
            }
        }

        // Class picker
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "CREATE CHARACTER", L.pickerX, L.pickerY - 8);
        for (int i = 0; i < CLASS_NAMES.length; i++) {
            int col = i % 4;
            int row = i / 4;
            int x = L.pickerX + col * L.pickerCellW;
            int y = L.pickerY + row * L.pickerCellH;
            this.renderClassOption(batch, shapes, font, i, x, y, L.pickerCellW - 4, L.pickerCellH - 4,
                    this.selectedClassId == i);
        }

        // Right column: account info, server, leaderboard, vault, logout, change password
        // Server cycler
        this.drawButton(batch, shapes, font, L.serverX, L.serverY, L.btnW, L.btnH,
                "Server: " + SERVERS[this.serverIdx], false, false);

        // Vault
        int vaultChests = (this.account.getPlayerVault() == null) ? 0 : this.account.getPlayerVault().size();
        font.setColor(0.78f, 0.66f, 0.43f, 1f);
        font.draw(batch, "Vault Chests: " + vaultChests + "/10", L.addChestX, L.addChestY - 8);
        this.drawButton(batch, shapes, font, L.addChestX, L.addChestY, L.btnW, L.btnH,
                "+ Add Chest", false, false);

        // Change password collapsible header
        this.drawButton(batch, shapes, font, L.changePwX, L.changePwY, L.btnW, L.btnH,
                this.changePwOpen ? "▼ Change Password" : "▶ Change Password", false, false);
        if (this.changePwOpen) {
            this.currentPw.setBounds(L.pwFieldX, L.pwFieldY,         L.pwFieldW, 28);
            this.newPw    .setBounds(L.pwFieldX, L.pwFieldY + 36,    L.pwFieldW, 28);
            this.confirmPw.setBounds(L.pwFieldX, L.pwFieldY + 72,    L.pwFieldW, 28);
            this.currentPw.render(batch, shapes, font);
            this.newPw    .render(batch, shapes, font);
            this.confirmPw.render(batch, shapes, font);
            this.drawButton(batch, shapes, font, L.pwSubmitX, L.pwSubmitY, L.btnW, L.btnH,
                    "Update Password", false, false);
            if (!this.pwStatus.isEmpty()) {
                font.setColor(this.pwStatus.startsWith("OK") ? Color.LIME : Color.SALMON);
                font.draw(batch, this.pwStatus, L.pwFieldX, L.pwSubmitY + L.btnH + 18);
            }
        }

        // Logout
        this.drawButton(batch, shapes, font, L.logoutX, L.logoutY, L.btnW, L.btnH, "Logout", false, false);

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

        // Class icon
        CharacterClass cc = CharacterClass.valueOf(c.getCharacterClass());
        if (cc != null) {
            try {
                SpriteSheet classImg = GameSpriteManager.loadClassSprites(cc);
                TextureRegion frame = classImg.getCurrentFrame();
                if (frame != null) {
                    batch.draw(frame, x + 8, y + 8, h - 16, h - 16);
                }
            } catch (Exception ignored) {
                // Class sprite missing — skip so the rest of the row still renders.
            }
        }

        int textX = x + h + 8;
        int lvl = (c.getStats() != null && c.getStats().getXp() != null
                && GameDataManager.EXPERIENCE_LVLS != null
                && GameDataManager.EXPERIENCE_LVLS.isMaxLvl(c.getStats().getXp()))
                ? 20
                : (c.getStats() != null && c.getStats().getXp() != null && GameDataManager.EXPERIENCE_LVLS != null
                        ? GameDataManager.EXPERIENCE_LVLS.getLevel(c.getStats().getXp())
                        : 0);
        String className = (cc != null) ? cc.name() : "Unknown";
        font.setColor(grayed ? new Color(0.55f, 0.55f, 0.55f, 1f) : Color.WHITE);
        font.draw(batch, className + "  Lv " + lvl + "  " + c.numStatsMaxed() + "/8 maxed",
                textX, y + 24);
        if (c.getStats() != null) {
            font.setColor(0.75f, 0.70f, 0.60f, 1f);
            font.draw(batch,
                    String.format("HP %d  MP %d  ATT %d  DEF %d  SPD %d  DEX %d  VIT %d  WIS %d",
                        nz(c.getStats().getHp()), nz(c.getStats().getMp()),
                        nz(c.getStats().getAtt()), nz(c.getStats().getDef()),
                        nz(c.getStats().getSpd()), nz(c.getStats().getDex()),
                        nz(c.getStats().getVit()), nz(c.getStats().getWis())),
                    textX, y + 46);
        }
        if (grayed && c.getDeleted() != null) {
            font.setColor(0.55f, 0.40f, 0.40f, 1f);
            font.draw(batch, "Died: " + DEATH_FMT.format(c.getDeleted()), textX, y + 66);
        } else {
            font.setColor(0.45f, 0.40f, 0.40f, 1f);
            font.draw(batch, c.getCharacterUuid() == null ? "" : c.getCharacterUuid(), textX, y + 66);
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
        String label = classId < CLASS_NAMES.length ? CLASS_NAMES[classId] : "Class " + classId;
        font.draw(batch, label, x + 40, y + 20);
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
        // Left column = char list + class picker. Right column = account/leaderboard/etc.
        int leftPad = 32;
        int leftW = width - 380;
        L.tabsY = 56;
        L.tabH = 32;
        L.tabW = 200;
        L.tabCharsX = leftPad;
        L.tabGraveX = leftPad + L.tabW + 8;

        L.listX = leftPad;
        L.listY = L.tabsY + L.tabH + 8;
        L.listW = leftW - leftPad;
        L.listH = 380;
        L.rowH = 84;

        L.pickerX = leftPad;
        L.pickerY = L.listY + L.listH + 40;
        L.pickerCellW = (L.listW) / 4;
        L.pickerCellH = 44;

        L.btnW = 160;
        L.btnH = 32;
        L.playX = leftPad;
        L.playY = height - 56;
        L.deleteX = leftPad + L.btnW + 8;
        L.deleteY = L.playY;
        L.createX = leftPad + 2 * (L.btnW + 8);
        L.createY = L.playY;

        // Right column
        int rx = width - 340;
        int ry = 56;
        L.serverX = rx;
        L.serverY = ry;
        ry += L.btnH + 16;
        L.addChestY = ry + 12; // leave room for label above
        L.addChestX = rx;
        ry = L.addChestY + L.btnH + 16;
        L.changePwX = rx;
        L.changePwY = ry;
        ry += L.btnH + 8;
        L.pwFieldX = rx;
        L.pwFieldY = ry;
        L.pwFieldW = 280;
        L.pwSubmitX = rx;
        L.pwSubmitY = ry + (this.changePwOpen ? 108 : 0);
        if (this.changePwOpen) ry = L.pwSubmitY + L.btnH + 24;
        else ry += L.btnH + 8;
        L.logoutX = rx;
        L.logoutY = ry;
        // Leaderboard sits at the bottom of the right column.
        L.lbW = 320;
        L.lbH = 280;
        L.lbX = rx;
        L.lbY = height - L.lbH - 16;
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
        LoginState.applyServerSelection(SERVERS[this.serverIdx]);
        SessionStore.get().setLastServer(SERVERS[this.serverIdx]);
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
