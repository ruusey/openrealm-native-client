package com.openrealm.game.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.Player;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.net.realm.RealmManagerClient;
import com.openrealm.net.server.packet.ExchangeItemsPacket;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Exchange Market UI — swap N of one consumable for N-1 of another in the same
 * exchange group (shards, crystals, essences, or stackable stat potions). One
 * item is always lost as the exchange tax, so the minimum trade is 2 -> 1.
 *
 * Grouping mirrors ServerExchangeMarketHelper so the UI only ever offers legal
 * swaps; the server re-validates every ExchangeItemsPacket.
 */
@Slf4j
public class ExchangeMarketWindow {

    private static final int MIN_QUANTITY = 2;
    private static final int DIALOG_W = 560;
    private static final int HEADER_H = 32;
    private static final int ROW_H = 30;

    private boolean visible = false;
    private boolean mouseDownPrev = false;

    @Setter private RealmManagerClient realmManager;
    @Setter private PlayerUI playerUi;

    private int sourceItemId = -1;
    private int targetItemId = -1;
    private int quantity = MIN_QUANTITY;

    private String statusMsg = "";
    private boolean statusIsError = false;

    // Rebuilt each frame from the live inventory / catalog so counts track swaps.
    private final List<int[]> giveRows = new ArrayList<>();   // [itemId, owned]
    private final List<Integer> recvRows = new ArrayList<>(); // itemId

    public boolean isVisible() {
        return this.visible;
    }

    public void show() {
        this.visible = true;
        this.sourceItemId = -1;
        this.targetItemId = -1;
        this.quantity = MIN_QUANTITY;
        this.statusMsg = "";
    }

    public void hide() {
        this.visible = false;
    }

    /** Exchange group an item belongs to, or null if it can't be exchanged. */
    public static String exchangeGroupKey(final GameItem item) {
        if (item == null || item.getCategory() == null) return null;
        switch (item.getCategory()) {
            case "shard":
            case "crystal":
            case "essence":
                return item.getCategory();
            case "generic":
                if (item.isConsumable() && item.isStackable()) return "stat_potion";
                return null;
            default:
                return null;
        }
    }

    /** itemId -> total owned across the backpack, exchangeable items only. */
    private Map<Integer, Integer> ownedCounts() {
        final Map<Integer, Integer> owned = new TreeMap<>();
        if (this.playerUi == null || this.playerUi.getInventory() == null) return owned;
        final Slots[] inv = this.playerUi.getInventory();
        final int end = Math.min(Player.INVENTORY_SIZE, inv.length);
        for (int i = Player.EQUIPMENT_SLOT_COUNT; i < end; i++) {
            final Slots slot = inv[i];
            final GameItem it = slot == null ? null : slot.getItem();
            if (it == null || it.getItemId() < 0) continue;
            final GameItem def = GameDataManager.GAME_ITEMS == null ? null
                    : GameDataManager.GAME_ITEMS.get(it.getItemId());
            if (exchangeGroupKey(def) == null) continue;
            owned.merge(it.getItemId(), it.isStackable() ? it.getStackCount() : 1, Integer::sum);
        }
        return owned;
    }

    private List<GameItem> groupMembers(final String groupKey) {
        final List<GameItem> out = new ArrayList<>();
        if (groupKey == null || GameDataManager.GAME_ITEMS == null) return out;
        final List<GameItem> all = new ArrayList<>(GameDataManager.GAME_ITEMS.values());
        all.sort((a, b) -> Integer.compare(a.getItemId(), b.getItemId()));
        for (final GameItem def : all) {
            if (def.getItemId() == this.sourceItemId) continue;
            if (groupKey.equals(exchangeGroupKey(def))) out.add(def);
        }
        return out;
    }

    public void update() {
        if (!this.visible) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            this.hide();
            return;
        }
        final boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        if (down && !this.mouseDownPrev) {
            this.handleClick(Gdx.input.getX(), Gdx.input.getY());
        }
        this.mouseDownPrev = down;
    }

    private int dialogH() {
        return Math.min(460, OpenRealmGame.height - 80);
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        if (!this.visible) return;

        final Map<Integer, Integer> owned = this.ownedCounts();
        // Drop a stale source the player no longer owns.
        if (this.sourceItemId >= 0 && !owned.containsKey(this.sourceItemId)) {
            this.sourceItemId = -1;
            this.targetItemId = -1;
        }
        this.giveRows.clear();
        for (final Map.Entry<Integer, Integer> e : owned.entrySet()) {
            this.giveRows.add(new int[]{e.getKey(), e.getValue()});
        }
        this.recvRows.clear();
        if (this.sourceItemId >= 0) {
            final GameItem src = GameDataManager.GAME_ITEMS.get(this.sourceItemId);
            for (final GameItem def : this.groupMembers(exchangeGroupKey(src))) {
                this.recvRows.add(def.getItemId());
            }
        }
        final int maxQty = this.sourceItemId >= 0 ? owned.getOrDefault(this.sourceItemId, 0) : 0;
        this.quantity = Math.max(MIN_QUANTITY, Math.min(this.quantity, Math.max(MIN_QUANTITY, maxQty)));

        final int w = OpenRealmGame.width;
        final int h = OpenRealmGame.height;
        final int dialogH = this.dialogH();
        final int x = (w - DIALOG_W) / 2;
        final int y = (h - dialogH) / 2;
        final int colW = (DIALOG_W - 36) / 2;
        final int leftColX = x + 12;
        final int rightColX = x + 24 + colW;
        final int colTop = y + HEADER_H + 44;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, w, h);
        shapes.setColor(0.10f, 0.10f, 0.12f, 0.97f);
        shapes.rect(x, y, DIALOG_W, dialogH);
        shapes.setColor(0.06f, 0.06f, 0.08f, 1f);
        shapes.rect(x, y, DIALOG_W, HEADER_H);

        // Cancel button in the header (right side).
        final int closeBtnW = 60, closeBtnH = HEADER_H - 8;
        final int closeBtnX = x + DIALOG_W - closeBtnW - 6;
        final int closeBtnY = y + 4;
        shapes.setColor(0.40f, 0.20f, 0.20f, 1f);
        shapes.rect(closeBtnX, closeBtnY, closeBtnW, closeBtnH);

        // Give rows (left).
        for (int i = 0; i < this.giveRows.size(); i++) {
            final int rowY = colTop + i * ROW_H;
            final boolean sel = this.giveRows.get(i)[0] == this.sourceItemId;
            shapes.setColor(sel ? 0.35f : 0.16f, sel ? 0.28f : 0.16f, sel ? 0.14f : 0.20f, 1f);
            shapes.rect(leftColX, rowY, colW, ROW_H - 4);
        }
        // Receive rows (right).
        for (int j = 0; j < this.recvRows.size(); j++) {
            final int rowY = colTop + j * ROW_H;
            final boolean sel = this.recvRows.get(j) == this.targetItemId;
            shapes.setColor(sel ? 0.35f : 0.16f, sel ? 0.28f : 0.16f, sel ? 0.14f : 0.20f, 1f);
            shapes.rect(rightColX, rowY, colW, ROW_H - 4);
        }

        // Quantity stepper + Exchange button along the bottom.
        final int controlsY = y + dialogH - 40;
        final int stepW = 26;
        shapes.setColor(0.16f, 0.16f, 0.20f, 1f);
        shapes.rect(leftColX, controlsY, stepW, stepW);                 // minus
        shapes.rect(leftColX + stepW + 40, controlsY, stepW, stepW);    // plus
        shapes.rect(leftColX + stepW * 2 + 52, controlsY, 44, stepW);   // max
        final boolean ready = this.sourceItemId >= 0 && this.targetItemId >= 0 && maxQty >= MIN_QUANTITY;
        final int exBtnW = 120, exBtnH = 28;
        final int exBtnX = x + DIALOG_W - exBtnW - 12;
        shapes.setColor(ready ? 0.20f : 0.14f, ready ? 0.45f : 0.14f, ready ? 0.20f : 0.16f, 1f);
        shapes.rect(exBtnX, controlsY, exBtnW, exBtnH);

        shapes.end();
        batch.begin();

        font.setColor(Color.WHITE);
        font.draw(batch, "EXCHANGE MARKET", x + 16, y + 22);
        font.draw(batch, "Cancel", closeBtnX + 8, closeBtnY + closeBtnH - 6);
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "You give", leftColX + 4, y + HEADER_H + 28);
        font.draw(batch, "You receive", rightColX + 4, y + HEADER_H + 28);

        for (int i = 0; i < this.giveRows.size(); i++) {
            final int rowY = colTop + i * ROW_H;
            final int[] r = this.giveRows.get(i);
            final GameItem def = GameDataManager.GAME_ITEMS.get(r[0]);
            font.setColor(Color.WHITE);
            font.draw(batch, nameOf(def, r[0]), leftColX + 6, rowY + ROW_H - 12);
            font.setColor(Color.LIGHT_GRAY);
            font.draw(batch, "x" + r[1], leftColX + colW - 44, rowY + ROW_H - 12);
        }
        if (this.giveRows.isEmpty()) {
            font.setColor(Color.GRAY);
            font.draw(batch, "No exchangeable items.", leftColX + 6, colTop + ROW_H - 12);
        }
        for (int j = 0; j < this.recvRows.size(); j++) {
            final int rowY = colTop + j * ROW_H;
            final GameItem def = GameDataManager.GAME_ITEMS.get(this.recvRows.get(j));
            font.setColor(Color.WHITE);
            font.draw(batch, nameOf(def, this.recvRows.get(j)), rightColX + 6, rowY + ROW_H - 12);
        }
        if (this.sourceItemId < 0) {
            font.setColor(Color.GRAY);
            font.draw(batch, "Pick an item to give.", rightColX + 6, colTop + ROW_H - 12);
        }

        // Quantity + summary line (reuse the geometry declared above).
        font.setColor(Color.WHITE);
        font.draw(batch, "-", leftColX + 9, controlsY + 19);
        font.draw(batch, String.valueOf(this.quantity), leftColX + stepW + 14, controlsY + 19);
        font.draw(batch, "+", leftColX + stepW + 48, controlsY + 19);
        font.draw(batch, "Max", leftColX + stepW * 2 + 58, controlsY + 19);
        font.setColor(ready ? Color.WHITE : Color.GRAY);
        font.draw(batch, "Exchange", exBtnX + 26, controlsY + 19);

        if (ready) {
            final GameItem s = GameDataManager.GAME_ITEMS.get(this.sourceItemId);
            final GameItem t = GameDataManager.GAME_ITEMS.get(this.targetItemId);
            font.setColor(Color.GOLD);
            font.draw(batch, this.quantity + " " + nameOf(s, this.sourceItemId) + " -> "
                            + (this.quantity - 1) + " " + nameOf(t, this.targetItemId),
                    leftColX, controlsY - 8);
        }

        if (!this.statusMsg.isEmpty()) {
            font.setColor(this.statusIsError ? Color.SCARLET : Color.LIME);
            font.draw(batch, this.statusMsg, leftColX, y + dialogH - 8);
        }
    }

    private static String nameOf(final GameItem def, final int itemId) {
        return def != null && def.getName() != null ? def.getName() : ("Item " + itemId);
    }

    private void handleClick(int mx, int my) {
        final int w = OpenRealmGame.width;
        final int h = OpenRealmGame.height;
        final int dialogH = this.dialogH();
        final int x = (w - DIALOG_W) / 2;
        final int y = (h - dialogH) / 2;
        final int colW = (DIALOG_W - 36) / 2;
        final int leftColX = x + 12;
        final int rightColX = x + 24 + colW;
        final int colTop = y + HEADER_H + 44;

        // Cancel.
        final int closeBtnW = 60, closeBtnH = HEADER_H - 8;
        final int closeBtnX = x + DIALOG_W - closeBtnW - 6;
        final int closeBtnY = y + 4;
        if (hit(mx, my, closeBtnX, closeBtnY, closeBtnW, closeBtnH)) {
            this.hide();
            return;
        }

        // Give-row selection.
        for (int i = 0; i < this.giveRows.size(); i++) {
            final int rowY = colTop + i * ROW_H;
            if (hit(mx, my, leftColX, rowY, colW, ROW_H - 4)) {
                this.sourceItemId = this.giveRows.get(i)[0];
                this.targetItemId = -1;
                this.quantity = Math.min(Math.max(MIN_QUANTITY, this.quantity), this.giveRows.get(i)[1]);
                this.statusMsg = "";
                return;
            }
        }
        // Receive-row selection.
        for (int j = 0; j < this.recvRows.size(); j++) {
            final int rowY = colTop + j * ROW_H;
            if (hit(mx, my, rightColX, rowY, colW, ROW_H - 4)) {
                this.targetItemId = this.recvRows.get(j);
                this.statusMsg = "";
                return;
            }
        }

        // Quantity stepper + Exchange.
        final int controlsY = y + dialogH - 40;
        final int stepW = 26;
        final int maxQty = this.sourceItemId >= 0 ? this.ownedCounts().getOrDefault(this.sourceItemId, 0) : 0;
        if (hit(mx, my, leftColX, controlsY, stepW, stepW)) {
            this.quantity = Math.max(MIN_QUANTITY, this.quantity - 1);
            return;
        }
        if (hit(mx, my, leftColX + stepW + 40, controlsY, stepW, stepW)) {
            this.quantity = Math.min(Math.max(MIN_QUANTITY, maxQty), this.quantity + 1);
            return;
        }
        if (hit(mx, my, leftColX + stepW * 2 + 52, controlsY, 44, stepW)) {
            this.quantity = Math.max(MIN_QUANTITY, maxQty);
            return;
        }
        final int exBtnW = 120, exBtnH = 28;
        final int exBtnX = x + DIALOG_W - exBtnW - 12;
        if (hit(mx, my, exBtnX, controlsY, exBtnW, exBtnH)) {
            this.attemptExchange(maxQty);
        }
    }

    private static boolean hit(int mx, int my, int bx, int by, int bw, int bh) {
        return mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
    }

    private void attemptExchange(int maxQty) {
        if (this.sourceItemId < 0 || this.targetItemId < 0 || this.realmManager == null) return;
        if (maxQty < MIN_QUANTITY || this.quantity < MIN_QUANTITY || this.quantity > maxQty) {
            this.setStatus("Invalid quantity", true);
            return;
        }
        try {
            final ExchangeItemsPacket packet = new ExchangeItemsPacket();
            packet.setSourceItemId(this.sourceItemId);
            packet.setTargetItemId(this.targetItemId);
            packet.setQuantity(this.quantity);
            this.realmManager.getClient().getOutboundPacketQueue().add(packet);
            this.setStatus("Exchanging...", false);
        } catch (Exception e) {
            log.error("[EXCHANGE] Failed to send exchange packet: {}", e.getMessage());
            this.setStatus("Network error", true);
        }
    }

    public void setStatus(String msg, boolean isError) {
        this.statusMsg = msg == null ? "" : msg;
        this.statusIsError = isError;
    }
}
