package com.openrealm.game;

import java.io.File;
import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Persisted login session for the native client. Mirrors the webclient's
 * {@code or_session}, {@code or_guest_email}, and {@code or_guest_password}
 * localStorage keys so closing the launcher and reopening it goes straight
 * back into character select instead of re-prompting for credentials.
 *
 * Lives at {@code ~/.openrealm/session.json}. The settings file is kept
 * separate (graphics/keybinds/etc.) so tweaking display options can't wipe
 * a saved session and vice versa.
 */
@Data
@Slf4j
public class SessionStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static SessionStore INSTANCE;

    public static SessionStore get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    /** Bearer token from the last successful login. May be expired — caller verifies via /token/resolve. */
    private String token;
    /** Account GUID corresponding to {@link #token}. Cached so the launcher doesn't have to resolve it. */
    private String accountGuid;
    /** Last server selection from the dropdown (`useast`, `local`, `localhost`, or a literal host:port). */
    private String lastServer;
    /** Last email typed into the login form. Used to pre-fill the field, never auto-submit. */
    private String rememberEmail;
    /** Guest account credentials, persisted so the "Play as Guest" button rejoins the same account. */
    private String guestEmail;
    private String guestPassword;

    public boolean hasSession() {
        return this.token != null && this.accountGuid != null;
    }

    public boolean hasGuest() {
        return this.guestEmail != null && this.guestPassword != null;
    }

    public void clearSession() {
        this.token = null;
        this.accountGuid = null;
        this.save();
    }

    public void clearGuest() {
        this.guestEmail = null;
        this.guestPassword = null;
        this.save();
    }

    public void setSession(String token, String accountGuid) {
        this.token = token;
        this.accountGuid = accountGuid;
        this.save();
    }

    public void setGuest(String email, String password) {
        this.guestEmail = email;
        this.guestPassword = password;
        this.save();
    }

    private static File sessionFile() {
        String home = System.getProperty("user.home", ".");
        File dir = new File(home, ".openrealm");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "session.json");
    }

    private static SessionStore load() {
        File f = sessionFile();
        if (!f.exists()) {
            return new SessionStore();
        }
        try {
            return MAPPER.readValue(f, SessionStore.class);
        } catch (IOException e) {
            log.warn("Failed to read session from {}: {} - starting fresh", f.getAbsolutePath(), e.getMessage());
            return new SessionStore();
        }
    }

    public void save() {
        File f = sessionFile();
        try {
            MAPPER.writeValue(f, this);
        } catch (IOException e) {
            log.error("Failed to save session to {}: {}", f.getAbsolutePath(), e.getMessage());
        }
    }
}
