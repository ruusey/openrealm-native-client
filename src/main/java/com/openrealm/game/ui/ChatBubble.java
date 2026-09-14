package com.openrealm.game.ui;

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

    public float alpha(long nowMs) {
        final long remaining = this.expiresAtMs - nowMs;
        if (remaining <= 0L) return 0f;
        if (remaining >= FADE_MS) return 1f;
        return remaining / (float) FADE_MS;
    }
}
