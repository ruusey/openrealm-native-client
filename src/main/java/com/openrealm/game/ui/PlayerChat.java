package com.openrealm.game.ui;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.Settings;
import com.openrealm.game.state.PlayState;
import com.openrealm.net.client.SocketClient;
import com.openrealm.net.messaging.CommandType;
import com.openrealm.net.messaging.ServerCommandMessage;
import com.openrealm.net.server.packet.CommandPacket;
import com.openrealm.net.server.packet.TextPacket;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.openrealm.game.entity.Player;

@Data
@Slf4j
public class PlayerChat {
    private static final int CHAT_SIZE = 50;
    private static final int MAX_INPUT_CHARS = 200;
    private static final int COLLAPSED_VISIBLE = 3;

    private final GlyphLayout layout = new GlyphLayout();
    // Per-render scratch, cleared each frame to avoid per-frame allocation.
    private final List<TextPacket> wrapPickedScratch = new ArrayList<>(CHAT_SIZE);
    private final List<Integer> wrapRowCountsScratch = new ArrayList<>(CHAT_SIZE);

    private List<TextPacket> playerChat;
    private String currentMessage;
    private boolean chatOpen;
    private boolean releasedEnter;
    private boolean pressedEnter;
    private boolean collapsed = false;
    private boolean lastTildeDown = false;
    private boolean lastChatToggleDown = false;
    // Edge-detect so a held left-mouse drag doesn't re-toggle every frame.
    private boolean lastChatMouseDown = false;
    private PlayState state;
    // Stashed from input() so render() can read caret/selection without a signature change.
    private KeyHandler lastKey;

    // When non-null the panel positions itself inside this rect (sprite HUD mount)
    // instead of the legacy bottom-left float. Keep width >= 200px to avoid bad wrap.
    private Integer overrideX = null;
    private Integer overrideY = null;
    private Integer overrideW = null;
    private Integer overrideH = null;

    public PlayerChat(PlayState state) {
        this.currentMessage = "";
        this.chatOpen = false;
        this.releasedEnter = false;
        this.pressedEnter = false;
        this.state = state;
        this.playerChat = new ArrayList<TextPacket>(CHAT_SIZE);
    }

    public void setLayout(int x, int y, int w, int h) {
        this.overrideX = x;
        this.overrideY = y;
        this.overrideW = w;
        this.overrideH = h;
    }

    /** Wipe the chat log. Called on realm transitions for a clean slate per realm. */
    public void clearChat() {
        this.playerChat.clear();
    }

    public void addChatMessage(final TextPacket packet) {
        if (packet == null) return;
        this.playerChat.add(packet);
        while (this.playerChat.size() > CHAT_SIZE) {
            this.playerChat.remove(0);
        }
    }

    public void input(MouseHandler mouse, KeyHandler key, SocketClient client) {
        this.lastKey = key;
        // Backtick toggles collapsed/expanded; suppressed in captureMode so it can be typed.
        boolean tildeDown = !key.captureMode
                && Gdx.input.isKeyPressed(Input.Keys.GRAVE);
        if (tildeDown && !this.lastTildeDown) {
            this.collapsed = !this.collapsed;
        }
        this.lastTildeDown = tildeDown;

        boolean chatToggleDown = !key.captureMode
                && Gdx.input.isKeyPressed(Settings.get().getKeybind("toggleChat"));
        if (chatToggleDown && !this.lastChatToggleDown) {
            this.collapsed = !this.collapsed;
        }
        this.lastChatToggleDown = chatToggleDown;

        // Toggle-button click. These bounds MUST mirror the rect drawn in render().
        // Mouse coords are already flipped-ortho (y=0 top), the same basis render() uses.
        boolean mouseDown = mouse != null && mouse.isPressed(1);
        if (mouseDown && !this.lastChatMouseDown) {
            final boolean override = this.overrideX != null;
            final int PANEL_X = override ? this.overrideX : 10;
            final int PANEL_W = override ? this.overrideW
                                         : (this.collapsed ? 360 : 600);
            final int PANEL_BOTTOM_MARGIN = override
                    ? (OpenRealmGame.height - (this.overrideY + this.overrideH))
                    : 10;
            final int INPUT_H  = 28;
            final int MSG_H    = override
                    ? Math.max(60, this.overrideH - INPUT_H)
                    : 220;
            final int TOGGLE_W = 22;
            final int TOGGLE_H = 18;
            final float screenBottom   = OpenRealmGame.height - PANEL_BOTTOM_MARGIN;
            final float inputBoxTop    = screenBottom - INPUT_H;
            final float msgBoxTop      = inputBoxTop - MSG_H;
            // Collapsed: pin the toggle to the screen bottom, not the hidden panel top.
            final float toggleBoxTop   = this.collapsed
                    ? (screenBottom - TOGGLE_H)
                    : (msgBoxTop - TOGGLE_H);
            final int toggleX = PANEL_X + PANEL_W - TOGGLE_W;
            final int mx = mouse.getX();
            final int my = mouse.getY();
            final boolean inToggleRow = my >= toggleBoxTop && my <= toggleBoxTop + TOGGLE_H;
            if (this.collapsed) {
                // Whole collapsed bar is the expand target (forgiving hit-box).
                if (inToggleRow && mx >= PANEL_X && mx <= PANEL_X + PANEL_W) {
                    this.collapsed = false;
                }
            } else if (inToggleRow && mx >= toggleX && mx <= toggleX + TOGGLE_W) {
                this.collapsed = true;
            }
        }
        this.lastChatMouseDown = mouseDown;

        if (key.captureMode) {
            String captured = key.getContent();
            if (captured != null && captured.length() > MAX_INPUT_CHARS) {
                captured = captured.substring(0, MAX_INPUT_CHARS);
                key.setContent(captured);
            }
            this.currentMessage = captured == null ? "" : captured;
        }

        if (key.enter.down && !this.pressedEnter) {
            this.pressedEnter = true;
        }

        if (this.pressedEnter && this.releasedEnter) {
            this.chatOpen = !this.chatOpen;
            // Opening chat force-expands the panel so input + history are visible.
            if (this.chatOpen) this.collapsed = false;
            key.setCaptureMode(this.chatOpen);
            this.pressedEnter = false;
            this.releasedEnter = false;
            if (!this.chatOpen && !key.getContent().isBlank()) {
                try {
                    String messageToSend = key.getCapturedInput();
                    messageToSend = messageToSend.replace("\n", "").replace("\r", "").trim();
                    if (messageToSend.startsWith("/")) {
                        if (messageToSend.equalsIgnoreCase("/debug")) {
                            this.state.setDebugMode(!this.state.isDebugMode());
                            String status = this.state.isDebugMode() ? "ON" : "OFF";
                            TextPacket debugMsg = TextPacket.create("SYSTEM", "SYSTEM", "Debug mode: " + status);
                            this.addChatMessage(debugMsg);
                        } else if (messageToSend.equalsIgnoreCase("/clear")) {
                            this.playerChat.clear();
                        } else if (messageToSend.equalsIgnoreCase("/dev")) {
                            boolean on = PerfMetrics.get().toggleDev();
                            TextPacket devMsg = TextPacket.create("SYSTEM", "SYSTEM",
                                    "Dev overlay " + (on ? "ON" : "OFF") + " (FPS / ping / jitter / res)");
                            this.addChatMessage(devMsg);
                        } else if (messageToSend.toLowerCase().startsWith("/walls")) {
                            // /walls toggles, /walls simple|fancy sets explicitly.
                            String arg = messageToSend.substring("/walls".length()).trim().toLowerCase();
                            Settings settings = Settings.get();
                            String next = arg.equals("simple") || arg.equals("fancy")
                                    ? arg
                                    : ("simple".equals(settings.getWallRenderMode()) ? "fancy" : "simple");
                            settings.setWallRenderMode(next);
                            settings.save();
                            TextPacket wallsMsg = TextPacket.create("SYSTEM", "SYSTEM",
                                    "Wall rendering: " + next.toUpperCase()
                                            + (next.equals("simple") ? " (flat stroke - faster)" : " (shaded)"));
                            this.addChatMessage(wallsMsg);
                        } else {
                            ServerCommandMessage serverCommand = ServerCommandMessage.parseFromInput(messageToSend);
                            CommandPacket packet = CommandPacket.create(this.state.getPlayer(), CommandType.SERVER_COMMAND,
                                    serverCommand);
                            client.sendRemote(packet);
                        }
                    } else {
                        TextPacket packet = TextPacket.create(this.state.getPlayer().getName(), "SYSTEM",
                                messageToSend);
                        client.sendRemote(packet);
                    }
                } catch (Exception e) {
                    PlayerChat.log.error("Failed to send PlayerChat to server. Reason: {}", e);
                }
            }
        }

        if (this.pressedEnter && !key.enter.down) {
            this.releasedEnter = true;
            return;
        }
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        // Y axis is flipped (setToOrtho true): y=0 is screen top; box positions
        // are computed from the bottom upward to match the webclient CSS anchor.
        float originalScale = font.getData().scaleX;
        font.getData().setScale(1.0f);

        final boolean override = this.overrideX != null;
        final int PANEL_X      = override ? this.overrideX : 10;
        final int PANEL_W      = override ? this.overrideW
                                          : (this.collapsed ? 360 : 600);
        final int PANEL_BOTTOM_MARGIN = override
                ? (OpenRealmGame.height - (this.overrideY + this.overrideH))
                : 10;
        final int INPUT_H      = 28;
        final int MSG_H        = override
                ? Math.max(60, this.overrideH - INPUT_H)
                : 220;
        final int TOGGLE_W     = 22;
        final int TOGGLE_H     = 18;
        final float LINE_H     = font.getLineHeight();
        final int TEXT_PAD_X   = 8;
        final int TEXT_PAD_Y   = 6;

        final float screenBottom = OpenRealmGame.height - PANEL_BOTTOM_MARGIN;
        final float inputBoxBottom = screenBottom;
        final float inputBoxTop    = inputBoxBottom - INPUT_H;
        final float msgBoxBottom   = inputBoxTop;
        final float msgBoxTop      = msgBoxBottom - MSG_H;
        // Collapsed: pin the toggle to the screen bottom, not the hidden panel top.
        final float toggleBoxTop   = this.collapsed
                ? (screenBottom - TOGGLE_H)
                : (msgBoxTop - TOGGLE_H);
        // Vertically centered top-Y for a single line of text inside the input box; the
        // placeholder and the typed input's bottom line both sit here.
        final float lineTextH      = UiRender.textHeight(font, "Ay");
        final float inputLineTopY  = inputBoxTop + (INPUT_H - lineTextH) / 2f;

        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        if (!this.collapsed) {
            shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0xaa / 255f);
            shapes.rect(PANEL_X, msgBoxTop, PANEL_W, MSG_H);
            shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 1f);
            shapes.rect(PANEL_X, inputBoxTop, PANEL_W, INPUT_H);
        }

        // Collapsed: the toggle spans the whole width as a large click target.
        final float barX = this.collapsed ? PANEL_X : (PANEL_X + PANEL_W - TOGGLE_W);
        final float barW = this.collapsed ? PANEL_W : TOGGLE_W;
        shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 1f);
        shapes.rect(barX, toggleBoxTop, barW, TOGGLE_H);

        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0x3a / 255f, 0x2a / 255f, 0x38 / 255f, 1f);
        if (!this.collapsed) {
            shapes.rect(PANEL_X, msgBoxTop, PANEL_W, MSG_H);
            shapes.rect(PANEL_X, inputBoxTop, PANEL_W, INPUT_H);
        }
        shapes.rect(barX, toggleBoxTop, barW, TOGGLE_H);
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

        font.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
        String toggleGlyph = this.collapsed ? "^" : "v";
        UiRender.drawCenteredIn(batch, font, toggleGlyph, barX, toggleBoxTop, barW, TOGGLE_H);

        if (!this.collapsed) {
            // Body wraps, so a message spans 1..N rows. Walk newest-first
            // accumulating rows until the box fills, then render forward.
            final int maxRows = Math.max(1, (int) ((MSG_H - 2 * TEXT_PAD_Y) / LINE_H));
            final int totalMessages = this.playerChat.size();

            final List<TextPacket> picked = this.wrapPickedScratch;
            final List<Integer> rowCounts = this.wrapRowCountsScratch;
            picked.clear();
            rowCounts.clear();
            int rowsAccum = 0;
            for (int i = totalMessages - 1; i >= 0; i--) {
                final TextPacket pkt = this.playerChat.get(i);
                final String fromName = pkt != null && pkt.getFrom() != null ? pkt.getFrom() : "";
                final String body     = pkt != null && pkt.getMessage() != null ? pkt.getMessage() : "";
                final String prefix   = "[" + fromName + "]: ";
                this.layout.setText(font, prefix);
                final float prefixWidth = this.layout.width;
                final float bodyMaxWidth = PANEL_W - prefixWidth - 2 * TEXT_PAD_X;
                this.layout.setText(font, body, 0, body.length(), font.getColor(),
                        Math.max(1f, bodyMaxWidth),
                        Align.left, true, null);
                final int wrapLines = Math.max(1, this.layout.runs.size);
                if (rowsAccum + wrapLines > maxRows && !picked.isEmpty()) break;
                picked.add(pkt);
                rowCounts.add(wrapLines);
                rowsAccum += wrapLines;
                if (rowsAccum >= maxRows) break;
            }

            // Anchor to the top of the box; blocks grow downward (y-flipped ortho).
            float topOfNextBlock = msgBoxTop + TEXT_PAD_Y + LINE_H;
            for (int idx = picked.size() - 1; idx >= 0; idx--) {
                final TextPacket pkt = picked.get(idx);
                final int rows = rowCounts.get(idx);
                final String fromName = pkt != null && pkt.getFrom() != null ? pkt.getFrom() : "";
                final String body     = pkt != null && pkt.getMessage() != null ? pkt.getMessage() : "";
                final String prefix   = "[" + fromName + "]: ";
                final float blockTopY = topOfNextBlock;

                final Color nameColor = roleColorByName(fromName);
                font.setColor(nameColor);
                this.layout.setText(font, prefix);
                final float prefixWidth = this.layout.width;
                font.draw(batch, this.layout, PANEL_X + TEXT_PAD_X, blockTopY);

                // Body wraps starting on the prefix line, growing downward.
                if ("SYSTEM".equalsIgnoreCase(fromName)) {
                    font.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
                } else {
                    font.setColor(0xe0 / 255f, 0xd8 / 255f, 0xc8 / 255f, 1f);
                }
                final float bodyMaxWidth = PANEL_W - prefixWidth - 2 * TEXT_PAD_X;
                this.layout.setText(font, body, 0, body.length(), font.getColor(),
                        Math.max(1f, bodyMaxWidth),
                        Align.left, true, null);
                font.draw(batch, this.layout,
                        PANEL_X + TEXT_PAD_X + prefixWidth, blockTopY);

                topOfNextBlock += rows * LINE_H;
            }
        }

        if (this.chatOpen) {
            font.setColor(0xe0 / 255f, 0xd8 / 255f, 0xc8 / 255f, 1f);
            final String prompt = "> ";
            final float textOriginX = PANEL_X + TEXT_PAD_X + 2;
            this.layout.setText(font, prompt);
            final float promptWidth = this.layout.width;

            KeyHandler kh = this.lastKey;
            int caret = kh != null ? kh.captureCaret : this.currentMessage.length();
            if (caret < 0) caret = 0;
            if (caret > this.currentMessage.length()) caret = this.currentMessage.length();

            // Typed message wraps; the box grows upward, bottom edge anchored to screen bottom.
            final float bodyMaxW = Math.max(1f, PANEL_W - 2 * TEXT_PAD_X - 4 - promptWidth);
            this.layout.setText(font, this.currentMessage, 0, this.currentMessage.length(),
                    font.getColor(), bodyMaxW,
                    Align.left, true, null);
            final int wrapLines = Math.max(1, this.layout.runs.size);
            final float inputWrapH = INPUT_H + (wrapLines - 1) * LINE_H;
            final float wrappedInputBoxTop = inputBoxBottom - inputWrapH;

            // Repaint the box + border at the grown height so multi-line text is framed.
            if (wrapLines > 1) {
                batch.end();
                Gdx.gl.glEnable(GL20.GL_BLEND);
                Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 1f);
                shapes.rect(PANEL_X, wrappedInputBoxTop, PANEL_W, inputWrapH);
                shapes.end();
                shapes.begin(ShapeRenderer.ShapeType.Line);
                shapes.setColor(0x3a / 255f, 0x2a / 255f, 0x38 / 255f, 1f);
                shapes.rect(PANEL_X, wrappedInputBoxTop, PANEL_W, inputWrapH);
                shapes.end();
                Gdx.gl.glDisable(GL20.GL_BLEND);
                batch.begin();
                font.setColor(0xe0 / 255f, 0xd8 / 255f, 0xc8 / 255f, 1f);
            }

            final float firstLineY = inputLineTopY - (wrapLines - 1) * LINE_H;

            // Caret column/row: re-layout the substring up to the caret with the same wrap.
            this.layout.setText(font, this.currentMessage.substring(0, caret),
                    0, caret, font.getColor(), bodyMaxW,
                    Align.left, true, null);
            final int caretLines = Math.max(1, this.layout.runs.size);
            final float caretXOnLine = this.layout.runs.size == 0
                    ? 0f : this.layout.runs.get(this.layout.runs.size - 1).width;

            this.layout.setText(font, this.currentMessage, 0, this.currentMessage.length(),
                    font.getColor(), bodyMaxW,
                    Align.left, true, null);

            font.draw(batch, prompt, textOriginX, firstLineY);
            font.draw(batch, this.layout, textOriginX + promptWidth, firstLineY);

            final float caretX = textOriginX + promptWidth + caretXOnLine;
            final float caretY = firstLineY + (caretLines - 1) * LINE_H;
            font.draw(batch, "|", caretX, caretY);
        } else if (!this.collapsed) {
            font.setColor(0x88 / 255f, 0x78 / 255f, 0x68 / 255f, 1f);
            font.draw(batch, "Press Enter to chat...", PANEL_X + TEXT_PAD_X + 2, inputLineTopY);
        }
        font.setColor(Color.WHITE);
        font.getData().setScale(originalScale);
    }

    /** Role color for a player by name; off-white for SYSTEM or unknown senders. */
    private Color roleColorByName(String name) {
        if (name == null || name.isEmpty()) return new Color(0.93f, 0.93f, 0.93f, 1f);
        try {
            for (Player p : this.state.getRealmManager().getRealm().getPlayers().values()) {
                if (p == null || p.getName() == null) continue;
                if (!name.equals(p.getName())) continue;
                final String role = p.getChatRole();
                if (role == null) break;
                switch (role) {
                    case "sysadmin": return new Color(1.00f, 0.25f, 0.25f, 1f);
                    case "admin":    return new Color(0.25f, 0.50f, 0.88f, 1f);
                    case "mod":      return new Color(0.25f, 0.75f, 0.25f, 1f);
                    case "editor":   return new Color(0.63f, 0.25f, 0.75f, 1f);
                    case "demo":     return new Color(0.80f, 0.80f, 0.80f, 1f);
                    default:         return new Color(0.93f, 0.93f, 0.93f, 1f);
                }
            }
        } catch (Exception ignored) { /* fall through to default */ }
        return new Color(0.93f, 0.93f, 0.93f, 1f);
    }
}
