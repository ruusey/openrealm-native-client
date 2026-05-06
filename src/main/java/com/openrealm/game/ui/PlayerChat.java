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
    private boolean collapsed = true;
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
                && Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.GRAVE);
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
        // Use unscaled font for chat text
        float originalScale = font.getData().scaleX;
        font.getData().setScale(1.0f);

        float lineHeight = 22f;
        final float chatWidth = OpenRealmGame.width / 5f;

        // How many rows we'll actually draw: 3 when collapsed, full log
        // (up to CHAT_SIZE) when expanded. The on-screen ROWS reserved
        // mirror this so the black background only covers visible text.
        final int totalMessages = this.playerChat.size();
        final int visibleRows = this.collapsed
                ? Math.min(COLLAPSED_VISIBLE, totalMessages)
                : Math.min(PlayerChat.CHAT_SIZE, totalMessages);

        // Slot indices: bottommost row is "1", next up is "2", etc.
        // The latest message goes in slot 1 so chat reads top-down with
        // the most recent at the bottom near the input line.
        // y for slot k = height - k*lineHeight - 100.

        // Black panel sized to the visible rows + a hint label above
        // when collapsed.
        if (visibleRows > 0) {
            float bgTop    = OpenRealmGame.height - (visibleRows * lineHeight) - 100 - 4;
            float bgBottom = OpenRealmGame.height - 100 + lineHeight - 2;
            float bgH = bgBottom - bgTop;
            batch.end();
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA,
                    GL20.GL_ONE_MINUS_SRC_ALPHA);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0f, 0f, 0f, 0.7f);
            shapes.rect(4, bgTop, chatWidth, bgH);
            shapes.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);
            batch.begin();
        }

        // Toggle hint, drawn above the chat panel (always visible).
        font.setColor(new Color(0.65f, 0.65f, 0.65f, 0.85f));
        String hint = this.collapsed
                ? "[`] expand chat (" + totalMessages + ")"
                : "[`] collapse chat";
        font.draw(batch, hint, 8, OpenRealmGame.height - (visibleRows * lineHeight) - 100 - 6);

        font.setColor(Color.WHITE);

        // Iterate in insertion order (oldest → newest); skip everything
        // before the trailing window we'll display.
        final int skip = Math.max(0, totalMessages - visibleRows);
        int seen = 0;
        // Slot counts down so newest message lands at slot 1 (bottom row).
        int slot = visibleRows;
        for (Map.Entry<String, TextPacket> packet : this.playerChat.entrySet()) {
            if (seen++ < skip) continue;
            final TextPacket pkt = packet.getValue();
            final String fromName = pkt != null && pkt.getFrom() != null ? pkt.getFrom() : "";
            final String body = pkt != null && pkt.getMessage() != null ? pkt.getMessage() : "";
            float y = OpenRealmGame.height - (slot * lineHeight) - 100;
            slot--;

            // Sender name colored by chatRole (sysadmin red, admin blue,
            // mod green, editor purple, demo gray, default off-white).
            // Looks up the player by name in the realm; if not found
            // (e.g. system messages, players who left) falls back to the
            // default off-white color so the row still renders.
            final Color nameColor = roleColorByName(fromName);
            font.setColor(nameColor);
            font.draw(batch, "[" + fromName + "]: ", 8, y);
            // Approx pixel width of the prefix so the message body draws
            // immediately after the colored name; default BitmapFont at
            // 1.0 scale is ~6 px per char.
            float prefixWidth = ("[" + fromName + "]: ").length() * 6f;
            font.setColor(Color.WHITE);
            // Truncate the body to fit the 1/5 screen width — overflow
            // would either wrap (LibGDX BitmapFont doesn't auto-wrap with
            // draw()) or crash into the right HUD.
            final int maxBodyChars = Math.max(0, (int) ((chatWidth - prefixWidth - 16) / 6f));
            String shownBody = body.length() > maxBodyChars
                    ? body.substring(0, Math.max(0, maxBodyChars - 1)) + "…"
                    : body;
            font.draw(batch, shownBody, 8 + prefixWidth, y);
        }

        if (this.chatOpen) {
            // Draw dark semi-transparent background behind chat input
            float inputY = OpenRealmGame.height - lineHeight - 4;
            float inputHeight = lineHeight + 8;
            float inputWidth = chatWidth;
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(new Color(0f, 0f, 0f, 0.6f));
            shapes.rect(4, inputY, inputWidth, inputHeight);
            shapes.end();
            batch.begin();

            font.setColor(Color.WHITE);
            font.draw(batch, "> " + this.currentMessage + "_", 8, OpenRealmGame.height - lineHeight);
        }

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
