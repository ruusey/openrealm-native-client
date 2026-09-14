package com.openrealm.util;

import java.time.Instant;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Data
@NoArgsConstructor
@Slf4j
@EqualsAndHashCode(callSuper = false)
public class TimedWorkerThread implements Runnable {
    private boolean shutdown = false;
    private Runnable runnable;
    private double targetFps;

    private long lastUpdateTime;
    private long now;
    private long lastRenderTime;
    private long lastSecondTime;

    private int oldFrameCount;
    private int oldTickCount;
    private int tickCount;

    public TimedWorkerThread(Runnable runnable, double targetFps) {
        this.runnable = runnable;
        this.targetFps = targetFps;
    }

    @SuppressWarnings("unused")
    @Override
    public void run() {
        log.info("TimedThread processing start...");

        final long nsPerTick = (long) (1_000_000_000.0 / this.targetFps);
        long nextTickTime = System.nanoTime();

        while (!this.shutdown) {
            try {
                final long now = System.nanoTime();
                if (now >= nextTickTime) {
                    try {
                        this.runnable.run();
                    } catch (Exception tickEx) {
                        log.error("TimedThread tick threw - continuing. Runnable={}. Reason: {}",
                                this.runnable, tickEx.getMessage(), tickEx);
                    }
                    nextTickTime += nsPerTick;
                    // If we fell behind, catch up but don't spiral.
                    if (now - nextTickTime > nsPerTick * 3) {
                        nextTickTime = now + nsPerTick;
                    }
                } else {
                    long sleepMs = (nextTickTime - now) / 1_000_000;
                    if (sleepMs > 0) {
                        Thread.sleep(sleepMs);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                this.shutdown = true;
                log.info("TimedThread interrupted, shutting down. Runnable={}", this.runnable);
            } catch (Throwable t) {
                // Rethrow Errors (OOM etc.) after logging; the JVM is in a bad state.
                log.error("TimedThread FATAL - thread dying. Runnable={}. Reason: {}",
                        this.runnable, t.getMessage(), t);
                throw t;
            }
        }
        log.info("Timed worker thread SHUTDOWN. Runnable = {}", this.runnable);
    }
}
