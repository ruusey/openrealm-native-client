package com.openrealm.game.state;

import java.util.concurrent.atomic.AtomicReference;

import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.account.service.OpenRealmClientDataService;
import com.openrealm.game.SessionStore;

/**
 * Shared worker plumbing for the "return to character select" teardown used by
 * PauseState and GameOverState. The GL thread must not block on the untimed
 * getAccount() HTTP call, so a worker thread fills these refs and the owning
 * state polls next frame to run its own gsm transitions.
 */
public class CharacterSelectReturn {

    private boolean pending = false;
    private volatile boolean workerDone = false;
    private final AtomicReference<PlayerAccountDto> accountResult = new AtomicReference<>();
    private final AtomicReference<String> error = new AtomicReference<>();

    public boolean isPending() {
        return this.pending;
    }

    public void begin(OpenRealmClientDataService service, SessionStore store,
                      String threadName, Runnable socketShutdown) {
        if (this.pending) return;
        this.pending = true;
        this.workerDone = false;
        if (socketShutdown != null) socketShutdown.run();

        if (!(store != null && store.hasSession() && service != null && service.getSessionToken() != null)) {
            this.accountResult.set(null);
            this.error.set("no-session");
            this.workerDone = true;
            return;
        }

        new Thread(() -> {
            try {
                PlayerAccountDto account = service.getAccount(store.getAccountGuid());
                this.accountResult.set(account);
            } catch (Exception e) {
                this.error.set(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                this.workerDone = true;
            }
        }, threadName).start();
    }

    /**
     * Returns the finished result once the worker has completed, clearing the
     * pending/done flags so the state can run its transition; returns null while
     * still pending or not yet started.
     */
    public CharacterSelectReturnResult poll() {
        if (!(this.pending && this.workerDone)) return null;
        this.pending = false;
        this.workerDone = false;
        return new CharacterSelectReturnResult(this.accountResult.getAndSet(null), this.error.getAndSet(null));
    }
}
