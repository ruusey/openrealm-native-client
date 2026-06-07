package com.openrealm.game.ui;

/**
 * A short-lived chat line floated above a player's head. Rendered in the
 * world-camera pass by PlayState (alongside nameplates); the same message also
 * lands in the chat bar via PlayerChat. Keyed by sender name so a new line
 * replaces the previous bubble for that player.
 */
public class ChatBubble {

    private static final long FADE_MS = 500L;

    private final String message;
    private final long expiresAtMs;

    public ChatBubble(String message, long nowMs, long lifeMs) {
        this.message = message;
        this.expiresAtMs = nowMs + lifeMs;
    }

    public String getMessage() {
        return this.message;
    }

    public boolean isExpired(long nowMs) {
        return nowMs >= this.expiresAtMs;
    }

    /** Full opacity for most of the lifetime, ramping to 0 over the final
     *  {@link #FADE_MS} milliseconds. */
    public float alpha(long nowMs) {
        final long remaining = this.expiresAtMs - nowMs;
        if (remaining <= 0L) return 0f;
        if (remaining >= FADE_MS) return 1f;
        return remaining / (float) FADE_MS;
    }
}
