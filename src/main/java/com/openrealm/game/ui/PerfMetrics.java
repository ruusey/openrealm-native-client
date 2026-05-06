package com.openrealm.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Lightweight perf overlay mirroring the web client's {@code _perfEl}
 * (main.js {@code perfMetrics}). Tracks FPS, JVM heap usage, ping, and
 * jitter (stddev of one-way ping samples). Drawn in the corner of the
 * HUD by {@link PlayerUI#render}.
 *
 * Singleton because heartbeat record-points fire from network handler
 * code that doesn't have a cleaner way to reach the UI layer. The cost
 * is trivial — one tiny static object.
 */
public final class PerfMetrics {

    private static final PerfMetrics INSTANCE = new PerfMetrics();
    public static PerfMetrics get() { return INSTANCE; }

    private PerfMetrics() {}

    // FPS sampled every 500ms over the framecount; smoother than the
    // raw Gdx.graphics.getFramesPerSecond() reading on machines with
    // bursty frame deltas.
    private int fps = 0;
    private int frameCount = 0;
    private long lastFpsSampleMs = 0L;

    // Latest observed JVM heap, refreshed alongside FPS so we don't
    // call Runtime per frame.
    private int memoryMB = 0;

    // Ping = mean of recent RTT samples (ms). Jitter = stddev of those
    // samples. Capped at 16 samples — newest replaces oldest.
    private final long[] rttSamples = new long[16];
    private int rttCount = 0;
    private int rttHead = 0;
    private long lastHeartbeatSendMs = 0L;
    private int ping = 0;
    private int jitter = 0;

    /** Frame-tick — call once per render frame from PlayerUI. */
    public void onFrame() {
        this.frameCount++;
        final long now = System.currentTimeMillis();
        if (now - this.lastFpsSampleMs >= 500L) {
            final long elapsed = Math.max(1L, now - this.lastFpsSampleMs);
            this.fps = (int) Math.round(this.frameCount * 1000.0 / elapsed);
            this.frameCount = 0;
            this.lastFpsSampleMs = now;
            final Runtime rt = Runtime.getRuntime();
            this.memoryMB = (int) ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L));
        }
    }

    /** Called by RealmManagerClient.startHeartbeatThread right before the
     *  send. Recording the send timestamp lets us match the round-trip
     *  when the server echoes the same packet back. */
    public void recordHeartbeatSend(long timestamp) {
        this.lastHeartbeatSendMs = timestamp;
    }

    /** Called by the client-side HeartbeatPacket echo handler. The
     *  server returns the original client timestamp; current time minus
     *  that gives the true RTT (not first-random-packet latency).
     *  Maintains a small ring buffer for mean + stddev. */
    public void recordHeartbeatRtt(long clientSendTimestamp) {
        final long rtt = System.currentTimeMillis() - clientSendTimestamp;
        if (rtt < 0L || rtt > 10_000L) return; // bogus / clock skew
        this.rttSamples[this.rttHead] = rtt;
        this.rttHead = (this.rttHead + 1) % this.rttSamples.length;
        if (this.rttCount < this.rttSamples.length) this.rttCount++;

        long sum = 0L;
        for (int i = 0; i < this.rttCount; i++) sum += this.rttSamples[i];
        final double mean = sum / (double) this.rttCount;
        // Ping reported as one-way (RTT/2) to match the web client's
        // PerfMetrics. Jitter = stddev of one-way samples.
        this.ping = (int) Math.round(mean / 2.0);
        double varSum = 0.0;
        for (int i = 0; i < this.rttCount; i++) {
            final double diff = (this.rttSamples[i] / 2.0) - (mean / 2.0);
            varSum += diff * diff;
        }
        this.jitter = (int) Math.round(Math.sqrt(varSum / this.rttCount));
    }

    public int getFps() { return this.fps; }
    public int getMemoryMB() { return this.memoryMB; }
    public int getPing() { return this.ping; }
    public int getJitter() { return this.jitter; }

    /**
     * Draw the FPS / MEM / PING / JITTER overlay. Caller passes the
     * top-right corner of the panel; we lay out four lines downward.
     * Caller is responsible for managing batch state — we draw font
     * runs only, no shapes.
     */
    public void render(SpriteBatch batch, BitmapFont font, float rightX, float topY) {
        final float lineH = font.getLineHeight();
        final float labelW = 70f;
        // FPS — green ≥55, yellow ≥30, red below.
        font.setColor(this.fps >= 55 ? new Color(0.4f, 1f, 0.4f, 1f)
                : this.fps >= 30 ? new Color(1f, 1f, 0.4f, 1f)
                : new Color(1f, 0.4f, 0.4f, 1f));
        font.draw(batch, "FPS: " + this.fps, rightX - labelW, topY + lineH);

        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "MEM: " + this.memoryMB + "MB", rightX - labelW, topY + lineH * 2);

        // Ping — green <50, yellow <120, red above.
        font.setColor(this.ping < 50 ? new Color(0.4f, 1f, 0.4f, 1f)
                : this.ping < 120 ? new Color(1f, 1f, 0.4f, 1f)
                : new Color(1f, 0.4f, 0.4f, 1f));
        font.draw(batch, "PING: " + this.ping + "ms", rightX - labelW, topY + lineH * 3);

        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "JIT: " + this.jitter + "ms", rightX - labelW, topY + lineH * 4);

        font.setColor(Color.WHITE);
    }

    /** Convenience accessor used by tests that want raw Gdx FPS too. */
    public int getGdxFps() {
        return Gdx.graphics != null ? Gdx.graphics.getFramesPerSecond() : 0;
    }

    // Keep `ShapeRenderer` import live so future expansions (mini RTT
    // graph) don't need a re-import — small upfront cost, zero runtime.
    @SuppressWarnings("unused")
    private static void _unused(ShapeRenderer shapes) {}
}
