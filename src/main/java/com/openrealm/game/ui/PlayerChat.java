package com.openrealm.game.ui;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
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
    private static final int CHAT_SIZE = 15;
    /** When collapsed, only this many trailing messages are shown so the
     *  log doesn't take up half the screen. Toggle with the BACKTICK key
     *  (or the click target rendered above the chat panel). */
    private static final int COLLAPSED_VISIBLE = 3;
    private Map<String, TextPacket> playerChat;
    private String currentMessage;
    private boolean chatOpen;
    private boolean releasedEnter;
    private boolean pressedEnter;
    /** True = only show the last COLLAPSED_VISIBLE messages. False = show
     *  all CHAT_SIZE. Defaults to collapsed so chat is unobtrusive. */
    private boolean collapsed = false;
    private boolean lastTildeDown = false;
    private PlayState state;

    public PlayerChat(PlayState state) {
        this.currentMessage = "";
        this.chatOpen = false;
        this.releasedEnter = false;
        this.pressedEnter = false;
        this.state = state;
        this.playerChat = new LinkedHashMap<String, TextPacket>() {
            private static final long serialVersionUID = 4568387673008726309L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TextPacket> eldest) {
                return this.size() > PlayerChat.CHAT_SIZE;
            }
        };
    }

    /** Wipe the chat log. Called on realm transitions to mirror the web
     *  client's clean-slate-per-realm behavior so chat doesn't carry a
     *  history from a previous map / instance. */
    public void clearChat() {
        this.playerChat.clear();
    }

    public void addChatMessage(final TextPacket packet) {
        String message = "[{0}]: {1}";
        message = MessageFormat.format(message, packet.getFrom(), packet.getMessage());
        this.playerChat.put(message, packet);
    }

    public void input(MouseHandler mouse, KeyHandler key, SocketClient client) {
        // Backtick (`) toggles collapsed/expanded. Edge-detected so holding
        // the key doesn't flicker. Suppressed while typing a message so the
        // user can include backticks in chat.
        boolean tildeDown = !key.captureMode
                && Gdx.input.isKeyPressed(Input.Keys.GRAVE);
        if (tildeDown && !this.lastTildeDown) {
            this.collapsed = !this.collapsed;
        }
        this.lastTildeDown = tildeDown;

        if (key.captureMode) {
            this.currentMessage = key.getContent();
        }

        if (key.enter.down && !this.pressedEnter) {
            this.pressedEnter = true;
        }

        if (this.pressedEnter && this.releasedEnter) {
            this.chatOpen = !this.chatOpen;
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
                            this.playerChat = new LinkedHashMap<String, TextPacket>() {
                                private static final long serialVersionUID = 4568387673008726309L;

                                @Override
                                protected boolean removeEldestEntry(Map.Entry<String, TextPacket> eldest) {
                                    return this.size() > PlayerChat.CHAT_SIZE;
                                }
                            };
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
        // ============================================================
        // Direct port of webclient's #chat-panel layout (style.css ~825):
        //   width:  360 px, anchored bottom-left with 10 px margin.
        //   #chat-messages: 140 px tall, dark bg #1a1218aa, border #3a2a38,
        //                   font 12px @ line-height 1.5 (≈18 px per row),
        //                   padding 6 px / 8 px, latest msg at bottom.
        //   #chat-input:    appended directly below, 100 % width, 28 px tall.
        //   #chat-toggle:   18 px square at top-right of panel, collapses
        //                   the messages box (input stays visible).
        //
        // Y axis is flipped (setToOrtho true) so y=0 is screen top,
        // y=height is screen bottom. We compute box positions from the
        // bottom upward to match the CSS anchor.
        // ============================================================
        float originalScale = font.getData().scaleX;
        font.getData().setScale(1.0f);

        final int PANEL_X      = 10;
        final int PANEL_W      = 360;
        final int PANEL_BOTTOM_MARGIN = 10;
        final int INPUT_H      = 28;
        final int MSG_H        = 140;
        final int TOGGLE_W     = 22;
        final int TOGGLE_H     = 18;
        final float LINE_H     = font.getLineHeight();           // matches web's line-height:1.5
        final int TEXT_PAD_X   = 8;
        final int TEXT_PAD_Y   = 6;

        // Y of the BOTTOM edge of each box (in flipped-ortho coords).
        final float screenBottom = OpenRealmGame.height - PANEL_BOTTOM_MARGIN;
        final float inputBoxBottom = screenBottom;
        final float inputBoxTop    = inputBoxBottom - INPUT_H;
        final float msgBoxBottom   = inputBoxTop;                // boxes share an edge
        final float msgBoxTop      = msgBoxBottom - MSG_H;
        final float toggleBoxTop   = msgBoxTop - TOGGLE_H;

        // ---- Shapes pass: backgrounds + borders ----
        batch.end();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Messages box bg #1a1218aa  (only when expanded)
        if (!this.collapsed) {
            shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 0xaa / 255f);
            shapes.rect(PANEL_X, msgBoxTop, PANEL_W, MSG_H);
        }

        // Input box bg #1a1218 (always shown — web parity)
        shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 1f);
        shapes.rect(PANEL_X, inputBoxTop, PANEL_W, INPUT_H);

        // Toggle button bg #1a1218
        shapes.setColor(0x1a / 255f, 0x12 / 255f, 0x18 / 255f, 1f);
        shapes.rect(PANEL_X + PANEL_W - TOGGLE_W, toggleBoxTop, TOGGLE_W, TOGGLE_H);

        shapes.end();

        // 1 px border #3a2a38 around all three pieces
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0x3a / 255f, 0x2a / 255f, 0x38 / 255f, 1f);
        if (!this.collapsed) shapes.rect(PANEL_X, msgBoxTop, PANEL_W, MSG_H);
        shapes.rect(PANEL_X, inputBoxTop, PANEL_W, INPUT_H);
        shapes.rect(PANEL_X + PANEL_W - TOGGLE_W, toggleBoxTop, TOGGLE_W, TOGGLE_H);
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
        batch.begin();

        // ---- Toggle button glyph (▼ expanded, ▲ collapsed) ----
        font.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f); // hover-style accent
        String toggleGlyph = this.collapsed ? "^" : "v";
        font.draw(batch, toggleGlyph,
                PANEL_X + PANEL_W - TOGGLE_W + 8,
                toggleBoxTop + TOGGLE_H - 4);

        // ---- Messages, only rendered when expanded ----
        if (!this.collapsed) {
            // How many lines fit in 140 px? floor((MSG_H - 2*pad) / LINE_H).
            final int maxRows = Math.max(1, (int) ((MSG_H - 2 * TEXT_PAD_Y) / LINE_H));
            final int totalMessages = this.playerChat.size();
            final int visibleRows = Math.min(maxRows, totalMessages);
            final int skip = Math.max(0, totalMessages - visibleRows);

            // Latest message anchored at the BOTTOM of the box. Each older
            // message draws one LINE_H higher.
            int seen = 0;
            int slot = visibleRows;          // bottom row = slot 1
            for (Map.Entry<String, TextPacket> entry : this.playerChat.entrySet()) {
                if (seen++ < skip) continue;
                final TextPacket pkt = entry.getValue();
                final String fromName = pkt != null && pkt.getFrom() != null ? pkt.getFrom() : "";
                final String body     = pkt != null && pkt.getMessage() != null ? pkt.getMessage() : "";

                // Baseline: msgBoxBottom - pad - (slot - 1) * lineH
                float y = msgBoxBottom - TEXT_PAD_Y - (slot - 1) * LINE_H;
                slot--;

                // Sender prefix in role color (web's .msg-name styling).
                final Color nameColor = roleColorByName(fromName);
                font.setColor(nameColor);
                final String prefix = "[" + fromName + "]: ";
                font.draw(batch, prefix, PANEL_X + TEXT_PAD_X, y);

                // Approx prefix width — Oryx-simplex at scale 1 averages ~7 px
                // per glyph; the body just needs to start slightly after the
                // colored name and not run off the panel.
                float prefixWidth = prefix.length() * 7f;

                // Body in #e0d8c8 (web .msg-player), or #c8a86e (.msg-system)
                // when sender is "SYSTEM".
                if ("SYSTEM".equalsIgnoreCase(fromName)) {
                    font.setColor(0xc8 / 255f, 0xa8 / 255f, 0x6e / 255f, 1f);
                } else {
                    font.setColor(0xe0 / 255f, 0xd8 / 255f, 0xc8 / 255f, 1f);
                }
                final int maxBodyChars = Math.max(0,
                        (int) ((PANEL_W - prefixWidth - 2 * TEXT_PAD_X) / 7f));
                String shownBody = body.length() > maxBodyChars
                        ? body.substring(0, Math.max(0, maxBodyChars - 1)) + "..."
                        : body;
                font.draw(batch, shownBody, PANEL_X + TEXT_PAD_X + prefixWidth, y);
            }
        }

        // ---- Input field (always visible — web parity) ----
        if (this.chatOpen) {
            font.setColor(0xe0 / 255f, 0xd8 / 255f, 0xc8 / 255f, 1f);
            font.draw(batch, "> " + this.currentMessage + "_",
                    PANEL_X + TEXT_PAD_X + 2,
                    inputBoxBottom - 8);
        } else {
            // Placeholder text — web ships "Press Enter to chat..."
            font.setColor(0x88 / 255f, 0x78 / 255f, 0x68 / 255f, 1f);
            font.draw(batch, "Press Enter to chat...",
                    PANEL_X + TEXT_PAD_X + 2,
                    inputBoxBottom - 8);
        }
        font.setColor(Color.WHITE);

        // Restore original scale
        font.getData().setScale(originalScale);
    }

    /**
     * Look up the chatRole of a player by name and return the matching
     * Color. Mirrors the web client's GameRenderer.getNameColorHex.
     * Falls back to the default off-white for system messages or unknown
     * senders.
     */
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
