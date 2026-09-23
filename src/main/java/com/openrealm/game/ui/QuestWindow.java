package com.openrealm.game.ui;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.entity.item.GameItem;
import com.openrealm.game.model.QuestView;
import com.openrealm.game.model.QuestViewObjective;
import com.openrealm.game.model.QuestViewReward;
import com.openrealm.game.state.PlayState;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.ServerCommandMessage;

/**
 * Quest Log (toggled with L). Scrollable list of the player's quests with status,
 * objective progress bars and rewards, plus Accept/Abandon buttons for non-autoStart
 * quests. Mirrors the webclient quest log. ASCII only ('*' for stars) to match the font.
 */
public class QuestWindow {

    private static final String[] SKILL_NAMES = {
        "Ranged", "Melee", "Magic", "Heavy Armor", "Light Armor",
        "Cloak Armor", "Support", "Impairment", "DPS"
    };

    private static final int DIALOG_W = 620;
    private static final int HEADER_H = 34;
    private static final int CARD_GAP = 8;
    private static final int SCROLL_STEP = 28;

    private boolean visible = false;
    private float scroll = 0f;
    private boolean mouseDownPrev = false;
    // [x, y, w, h, questId] in y-up shape space, captured each render for the next update().
    private final List<float[]> acceptButtons = new ArrayList<>();
    private final List<float[]> abandonButtons = new ArrayList<>();

    public boolean isVisible() {
        return this.visible;
    }

    public void toggle() {
        this.visible = !this.visible;
        if (this.visible) this.scroll = 0f;
    }

    public void hide() {
        this.visible = false;
    }

    public void update(final PlayState playState) {
        if (!this.visible) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            this.hide();
            return;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) this.scroll += SCROLL_STEP;
        if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) this.scroll = Math.max(0f, this.scroll - SCROLL_STEP);

        final boolean down = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        if (down && !this.mouseDownPrev) {
            final float mx = Gdx.input.getX();
            final float my = OpenRealmGame.height - Gdx.input.getY();
            for (final float[] b : this.acceptButtons) {
                if (hit(mx, my, b)) { sendQuestCommand(playState, "accept", (int) b[4]); break; }
            }
            for (final float[] b : this.abandonButtons) {
                if (hit(mx, my, b)) { sendQuestCommand(playState, "abandon", (int) b[4]); break; }
            }
        }
        this.mouseDownPrev = down;
    }

    private static boolean hit(final float mx, final float my, final float[] r) {
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    private void sendQuestCommand(final PlayState playState, final String sub, final int questId) {
        if (playState == null || playState.getPlayer() == null) return;
        try {
            final ServerCommandMessage msg = ServerCommandMessage.parseFromInput("/quest " + sub + " " + questId);
            final CommandPacket packet = CommandPacket.create(playState.getPlayer(), CommandType.SERVER_COMMAND, msg);
            playState.getRealmManager().getClient().sendRemote(packet);
        } catch (Exception ignored) {
        }
    }

    public void render(final SpriteBatch batch, final ShapeRenderer shapes, final BitmapFont font, final PlayState playState) {
        if (!this.visible) return;
        this.acceptButtons.clear();
        this.abandonButtons.clear();

        final int w = OpenRealmGame.width;
        final int h = OpenRealmGame.height;
        final int dialogH = h - 80;
        final int x = (w - DIALOG_W) / 2;
        final int y = 40;
        final float contentTop = y + dialogH - HEADER_H - 6;
        final float contentBottom = y + 8;
        final float contentH = contentTop - contentBottom;

        final List<QuestView> quests = playState != null ? playState.getQuests() : new ArrayList<>();
        final long stars = playState != null ? playState.getQuestStars() : 0L;

        // First pass: total height so we can clamp scroll.
        float total = 0f;
        for (final QuestView q : quests) total += cardHeight(q) + CARD_GAP;
        final float maxScroll = Math.max(0f, total - contentH);
        if (this.scroll > maxScroll) this.scroll = maxScroll;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, w, h);
        shapes.setColor(0.08f, 0.09f, 0.12f, 0.98f);
        shapes.rect(x, y, DIALOG_W, dialogH);
        shapes.setColor(0.05f, 0.06f, 0.08f, 1f);
        shapes.rect(x, y + dialogH - HEADER_H, DIALOG_W, HEADER_H);

        // Card backgrounds + bars + buttons (shapes pass). yTop walks downward.
        float yTop = contentTop + this.scroll;
        for (final QuestView q : quests) {
            final float ch = cardHeight(q);
            final float cardBottom = yTop - ch;
            final boolean vis = cardBottom >= contentBottom - 1 && yTop <= contentTop + 1;
            if (vis) drawCardShapes(shapes, q, x + 10, cardBottom, DIALOG_W - 20, ch);
            yTop = cardBottom - CARD_GAP;
        }
        shapes.end();
        batch.begin();

        font.setColor(0.85f, 0.78f, 0.5f, 1f);
        font.draw(batch, "QUEST LOG", x + 14, y + dialogH - 11);
        font.setColor(Color.GOLD);
        UiRender.drawRightAligned(batch, font, "* " + stars + " Stars", x + DIALOG_W - 150, y + dialogH - 11);
        font.setColor(0.5f, 0.45f, 0.4f, 1f);
        UiRender.drawRightAligned(batch, font, "L/ESC close  UP/DOWN scroll", x + DIALOG_W - 12, y + dialogH - 11);

        yTop = contentTop + this.scroll;
        for (final QuestView q : quests) {
            final float ch = cardHeight(q);
            final float cardBottom = yTop - ch;
            final boolean vis = cardBottom >= contentBottom - 1 && yTop <= contentTop + 1;
            if (vis) drawCardText(batch, font, q, x + 10, cardBottom, DIALOG_W - 20, ch);
            yTop = cardBottom - CARD_GAP;
        }

        if (quests.isEmpty()) {
            font.setColor(0.5f, 0.5f, 0.55f, 1f);
            font.draw(batch, "No quests available yet.", x + 20, contentTop - 20);
        }
        font.setColor(Color.WHITE);
    }

    // Card is: title/status (20) + category (16) + desc (18) + objectives*32 + rewards(18) + button(28) + pad.
    private float cardHeight(final QuestView q) {
        final int objs = q.getObjectives() == null ? 0 : q.getObjectives().size();
        float base = 20 + 16 + 18 + objs * 32 + 18 + 10;
        if (needsButton(q)) base += 28;
        return base;
    }

    private boolean needsButton(final QuestView q) {
        final String st = status(q);
        return st.equals("AVAILABLE") || (st.equals("ACTIVE") && !q.isAuto());
    }

    private String status(final QuestView q) {
        return q.getStatus() == null ? "AVAILABLE" : q.getStatus().toUpperCase();
    }

    private void drawCardShapes(final ShapeRenderer shapes, final QuestView q,
            final float cx, final float cy, final float cw, final float ch) {
        final String st = status(q);
        shapes.setColor(0.13f, 0.14f, 0.18f, 1f);
        shapes.rect(cx, cy, cw, ch);
        // Status stripe on the left edge.
        if (st.equals("COMPLETE")) shapes.setColor(0.30f, 0.80f, 0.48f, 1f);
        else if (st.equals("ACTIVE")) shapes.setColor(0.30f, 0.55f, 1f, 1f);
        else shapes.setColor(1f, 0.72f, 0.30f, 1f);
        shapes.rect(cx, cy, 4, ch);

        // Objective progress bars (top-down from just under the description).
        float rowY = cy + ch - 20 - 16 - 18;
        if (q.getObjectives() != null) {
            for (final QuestViewObjective o : q.getObjectives()) {
                final long target = Math.max(1, o.getTarget());
                final float pct = Math.max(0f, Math.min(1f, o.getProgress() / (float) target));
                final float barX = cx + 12, barW = cw - 24, barY = rowY - 14, barH = 6;
                shapes.setColor(0.06f, 0.07f, 0.10f, 1f);
                shapes.rect(barX, barY, barW, barH);
                shapes.setColor(0.30f, 0.55f, 1f, 1f);
                shapes.rect(barX, barY, barW * pct, barH);
                rowY -= 32;
            }
        }
        // Accept/Abandon button.
        if (needsButton(q)) {
            final boolean accept = st.equals("AVAILABLE");
            final float bw = 96, bh = 22, bx = cx + cw - bw - 10, by = cy + 8;
            if (accept) shapes.setColor(0.18f, 0.49f, 0.27f, 1f);
            else shapes.setColor(0.49f, 0.18f, 0.18f, 1f);
            shapes.rect(bx, by, bw, bh);
            (accept ? this.acceptButtons : this.abandonButtons).add(new float[]{bx, by, bw, bh, q.getId()});
        }
    }

    private void drawCardText(final SpriteBatch batch, final BitmapFont font, final QuestView q,
            final float cx, final float cy, final float cw, final float ch) {
        final String st = status(q);
        final float topY = cy + ch;
        font.setColor(Color.WHITE);
        String title = q.getName() == null ? "Quest" : q.getName();
        if (q.isScoped()) title += " [CHAR]";
        if (q.isRepeatable()) title += " [REPEAT]";
        font.draw(batch, title, cx + 12, topY - 6);

        final String badge = st.equals("COMPLETE") ? "COMPLETED" : st.equals("ACTIVE") ? "IN PROGRESS" : "AVAILABLE";
        if (st.equals("COMPLETE")) font.setColor(0.55f, 0.9f, 0.68f, 1f);
        else if (st.equals("ACTIVE")) font.setColor(0.6f, 0.75f, 1f, 1f);
        else font.setColor(1f, 0.8f, 0.5f, 1f);
        UiRender.drawRightAligned(batch, font, badge, cx + cw - 12, topY - 6);

        font.setColor(0.55f, 0.6f, 0.7f, 1f);
        String cat = (q.getCat() == null ? "" : q.getCat());
        if (q.getStars() > 0) cat += "   * " + q.getStars();
        font.draw(batch, cat, cx + 12, topY - 24);

        font.setColor(0.72f, 0.77f, 0.85f, 1f);
        font.draw(batch, truncate(font, q.getDesc(), cw - 24), cx + 12, topY - 42);

        float rowY = topY - 20 - 16 - 18;
        if (q.getObjectives() != null) {
            for (final QuestViewObjective o : q.getObjectives()) {
                final long target = Math.max(1, o.getTarget());
                font.setColor(0.82f, 0.86f, 0.94f, 1f);
                font.draw(batch, truncate(font, o.getLabel(), cw - 130), cx + 12, rowY);
                font.setColor(0.55f, 0.6f, 0.7f, 1f);
                UiRender.drawRightAligned(batch, font, Math.min(o.getProgress(), target) + " / " + target, cx + cw - 12, rowY);
                rowY -= 32;
            }
        }
        // Rewards line sits just above the button / card bottom.
        font.setColor(0.6f, 0.7f, 0.9f, 1f);
        final float rewY = cy + (needsButton(q) ? 36 : 12) + 6;
        font.draw(batch, truncate(font, "Rewards: " + rewardsText(q), cw - 24), cx + 12, rewY + 8);

        if (needsButton(q)) {
            font.setColor(Color.WHITE);
            final boolean accept = st.equals("AVAILABLE");
            font.draw(batch, accept ? "Accept" : "Abandon", cx + cw - 96 - 10 + 22, cy + 8 + 16);
        }
    }

    private String rewardsText(final QuestView q) {
        if (q.getRewards() == null || q.getRewards().isEmpty()) return "-";
        final StringBuilder sb = new StringBuilder();
        for (final QuestViewReward r : q.getRewards()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(rewardText(r));
        }
        return sb.toString();
    }

    private String rewardText(final QuestViewReward r) {
        final long amt = r.getAmount();
        final String t = r.getType() == null ? "" : r.getType().toUpperCase();
        switch (t) {
            case "FAME": return amt + " Fame";
            case "ITEM":
            case "POTION": return itemName(r.getTargetId()) + (amt > 1 ? " x" + amt : "");
            case "SKILL_XP": return amt + " " + skillName(r.getSkillId()) + " XP";
            case "STAT_POINT": return "+" + amt + " " + (r.getStat() != null && !r.getStat().isEmpty() ? r.getStat() : "Stat");
            case "UNLOCK_VAULT_CHEST": return amt + " Vault Chest" + (amt > 1 ? "s" : "");
            case "UNLOCK_CHARACTER_SLOT": return amt + " Char Slot" + (amt > 1 ? "s" : "");
            default: return r.getType() == null ? "?" : r.getType();
        }
    }

    private String itemName(final int id) {
        if (GameDataManager.GAME_ITEMS != null) {
            final GameItem def = GameDataManager.GAME_ITEMS.get(id);
            if (def != null && def.getName() != null) return def.getName();
        }
        return "Item #" + id;
    }

    private String skillName(final int id) {
        return id >= 0 && id < SKILL_NAMES.length ? SKILL_NAMES[id] : ("Skill " + id);
    }

    private String truncate(final BitmapFont font, final String text, final float maxW) {
        if (text == null) return "";
        if (UiRender.textWidth(font, text) <= maxW) return text;
        String cut = text;
        while (cut.length() > 1 && UiRender.textWidth(font, cut + "...") > maxW) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }
}
