package com.openrealm.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

public final class PerfMetrics {

    private static final PerfMetrics INSTANCE = new PerfMetrics();
    private static final Color COLOR_GOOD = new Color(0.4f, 1f, 0.4f, 1f);
    private static final Color COLOR_WARN = new Color(1f, 1f, 0.4f, 1f);
    private static final Color COLOR_BAD  = new Color(1f, 0.4f, 0.4f, 1f);

    private int fps = 0;
    private int frameCount = 0;
    private long lastFpsSampleMs = 0L;
    private int memoryMB = 0;

    private String fpsLabel = "FPS: 0";
    private String memLabel = "MEM: 0MB";
    private String pingLabel = "PING: 0ms";
    private String jitLabel = "JIT: 0ms";

    private final long[] rttSamples = new long[16];
    private int rttCount = 0;
    private int rttHead = 0;
    private long lastHeartbeatSendMs = 0L;
    private int ping = 0;
    private int jitter = 0;

    private boolean devVisible = false;
    private final GlyphLayout devLayout = new GlyphLayout();

    private PerfMetrics() {}

    public static PerfMetrics get() { return INSTANCE; }

    public boolean isDevVisible() { return this.devVisible; }
    public boolean toggleDev() { this.devVisible = !this.devVisible; return this.devVisible; }

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
            this.fpsLabel = "FPS: " + this.fps;
            this.memLabel = "MEM: " + this.memoryMB + "MB";
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
        this.pingLabel = "PING: " + this.ping + "ms";
        this.jitLabel = "JIT: " + this.jitter + "ms";
    }

    public int getFps() { return this.fps; }
    public int getMemoryMB() { return this.memoryMB; }
    public int getPing() { return this.ping; }
    public int getJitter() { return this.jitter; }

    /**
     * Draw the FPS / MEM / PING / JITTER overlay. Caller passes the panel's
     * top-right corner and manages batch state; we draw font runs only.
     */
    public void render(SpriteBatch batch, BitmapFont font, float rightX, float topY) {
        final float lineH = font.getLineHeight();
        final float labelW = 70f;
        font.setColor(this.fps >= 55 ? COLOR_GOOD
                : this.fps >= 30 ? COLOR_WARN : COLOR_BAD);
        font.draw(batch, this.fpsLabel, rightX - labelW, topY + lineH);

        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, this.memLabel, rightX - labelW, topY + lineH * 2);

        font.setColor(this.ping < 50 ? COLOR_GOOD
                : this.ping < 120 ? COLOR_WARN : COLOR_BAD);
        font.draw(batch, this.pingLabel, rightX - labelW, topY + lineH * 3);

        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, this.jitLabel, rightX - labelW, topY + lineH * 4);

        font.setColor(Color.WHITE);
    }

    /**
     * Single-line /dev bar right-aligned to the minimap's right edge, sitting
     * just above it. Batch is already active; y is the TOP of the glyphs.
     */
    public void renderDevBar(SpriteBatch batch, BitmapFont font,
            float minimapX, float minimapY, float minimapSize) {
        final int w = Gdx.graphics != null ? Gdx.graphics.getWidth() : 0;
        final int h = Gdx.graphics != null ? Gdx.graphics.getHeight() : 0;
        final String fpsStr  = "FPS " + this.fps;
        final String pingStr = "PING " + this.ping + "ms";
        final String jitStr  = "JITTER " + this.jitter + "ms";
        final String resStr  = "RES " + w + "x" + h;
        final String sep     = "   |   ";
        final float origScale = font.getData().scaleX;
        font.getData().setScale(0.7f);
        this.devLayout.setText(font, fpsStr + sep + pingStr + sep + jitStr + sep + resStr);
        float x = (minimapX + minimapSize) - this.devLayout.width;
        if (x < 4f) x = 4f;
        final float y = Math.max(2f, minimapY - this.devLayout.height - 2f);
        x = this.drawDevSeg(batch, font, fpsStr, x, y,
                this.fps >= 55 ? COLOR_GOOD : this.fps >= 30 ? COLOR_WARN : COLOR_BAD);
        x = this.drawDevSeg(batch, font, sep, x, y, Color.GRAY);
        x = this.drawDevSeg(batch, font, pingStr, x, y,
                this.ping < 50 ? COLOR_GOOD : this.ping < 120 ? COLOR_WARN : COLOR_BAD);
        x = this.drawDevSeg(batch, font, sep, x, y, Color.GRAY);
        x = this.drawDevSeg(batch, font, jitStr, x, y, Color.LIGHT_GRAY);
        x = this.drawDevSeg(batch, font, sep, x, y, Color.GRAY);
        this.drawDevSeg(batch, font, resStr, x, y, Color.LIGHT_GRAY);
        font.getData().setScale(origScale);
        font.setColor(Color.WHITE);
    }

    /** Draw one colored run at (x, y); returns the x advanced past it. */
    private float drawDevSeg(SpriteBatch batch, BitmapFont font, String s, float x, float y, Color c) {
        font.setColor(c);
        final GlyphLayout gl = font.draw(batch, s, x, y);
        return x + gl.width;
    }

    /** Convenience accessor used by tests that want raw Gdx FPS too. */
    public int getGdxFps() {
        return Gdx.graphics != null ? Gdx.graphics.getFramesPerSecond() : 0;
    }
}
