package com.openrealm.account.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openrealm.account.dto.AccountDto;
import com.openrealm.account.dto.AccountProvision;
import com.openrealm.account.dto.LoginRequestDto;
import com.openrealm.account.dto.PlayerAccountDto;
import com.openrealm.account.dto.SessionTokenDto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.util.Collections;

@AllArgsConstructor
@Data
@Slf4j
public class OpenRealmClientDataService implements OpenRealmDataService{
    // TODO: make POST/GET methods private
    // and expose public routine specific methods.
    // eg. getPlayerAccount(String accountUuid)
    private static final transient ObjectMapper REQUEST_MAPPER = new ObjectMapper();
    private HttpClient httpClient;
    private String baseUrl;
    private String sessionToken;

    /**
     * Log the elapsed time for a single REST round-trip. Format chosen so log
     * lines can be grep'd downstream (e.g. <code>grep '\[DATA-CALL\]' app.log
     * | sort -k? -n</code>) and so the client-side and server-side numbers
     * line up — the data service uses the same prefix in its request filter.
     * Slow calls (&gt;250 ms) escalate to WARN so they stand out without
     * needing to scan INFO output.
     */
    private static void logTiming(String method, String path, int status, long startNanos) {
        final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMs >= 250) {
            log.warn("[DATA-CALL] {} {} -> {} in {} ms (slow)", method, path, status, elapsedMs);
        } else {
            log.info("[DATA-CALL] {} {} -> {} in {} ms", method, path, status, elapsedMs);
        }
    }

    public <T> T executeDelete(String path, Class<T> responseClass) throws Exception {
        final long t0 = System.nanoTime();
        final URI targetURI = new URI(this.baseUrl + path);
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).DELETE();
        this.setAuth(httpRequest);

        final HttpResponse<String> response = this.httpClient.send(httpRequest.build(),
                HttpResponse.BodyHandlers.ofString());
        logTiming("DELETE", path, response.statusCode(), t0);
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmClientDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public <T> T executePost(String path, Object object, Class<T> responseClass) throws Exception {
        final long t0 = System.nanoTime();
        final URI targetURI = new URI(this.baseUrl + path);
        final BodyPublisher body = HttpRequest.BodyPublishers
                .ofString(OpenRealmClientDataService.REQUEST_MAPPER.writeValueAsString(object));
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).POST(body);
        this.setAuth(httpRequest);

        HttpResponse<String> response = this.httpClient.send(httpRequest.build(), HttpResponse.BodyHandlers.ofString());
        logTiming("POST", path, response.statusCode(), t0);
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmClientDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public <T> T executePut(String path, Object object, Class<T> responseClass) throws Exception {
        final long t0 = System.nanoTime();
        final URI targetURI = new URI(this.baseUrl + path);
        final BodyPublisher body = HttpRequest.BodyPublishers
                .ofString(OpenRealmClientDataService.REQUEST_MAPPER.writeValueAsString(object));
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).PUT(body);
        this.setAuth(httpRequest);

        final HttpResponse<String> response = this.httpClient.send(httpRequest.build(),
                HttpResponse.BodyHandlers.ofString());
        logTiming("PUT", path, response.statusCode(), t0);
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmClientDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public String executeGet(String path, Map<String, String> queryParams) throws Exception {
        final long t0 = System.nanoTime();
        URI targetURI = new URI(this.baseUrl + path);
        HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).GET();
        HttpResponse<String> response = this.httpClient.send(httpRequest.build(), HttpResponse.BodyHandlers.ofString());
        this.setAuth(httpRequest);

        logTiming("GET", path, response.statusCode(), t0);
        // TODO: Add query params
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return response.body();
    }

    public <T> T executeGet(String path, Map<String, String> queryParams, Class<T> responseClass) throws Exception {
        final long t0 = System.nanoTime();
        final URI targetURI = new URI(this.baseUrl + path);
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).GET();
        this.setAuth(httpRequest);
        final HttpResponse<String> response = this.httpClient.send(httpRequest.build(),
                HttpResponse.BodyHandlers.ofString());
        logTiming("GET", path, response.statusCode(), t0);
        // TODO: Add query params
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmClientDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public <T> T executeGetWithToken(String path, String token, Class<T> responseClass) throws Exception {
        final long t0 = System.nanoTime();
        final URI targetURI = new URI(this.baseUrl + path);
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(targetURI)
                .GET()
                .header("Content-Type", "application/json")
                .header("Authorization", token)
                .build();
        final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        logTiming("GET", path, response.statusCode(), t0);
        if (response.statusCode() >= 400) {
            throw new IOException(response.body());
        }
        return OpenRealmClientDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public void setAuth(HttpRequest.Builder builder) {
        if (this.sessionToken != null) {
            builder.header("Authorization", this.sessionToken);
        }
    }

    // ----- High-level account API (mirrors webclient/js/api.js) -----
    // These wrappers exist so the UI layer never has to assemble path strings
    // or DTOs by hand. Keeping the contract here also makes it easy to spot
    // drift from the web client's API surface during regressions.

    /** POST /admin/account/login — sets sessionToken on success. */
    public SessionTokenDto login(String email, String password) throws Exception {
        SessionTokenDto resp = this.executePost("/admin/account/login",
                new LoginRequestDto(email, password), SessionTokenDto.class);
        this.sessionToken = resp.getToken();
        return resp;
    }

    /** GET /admin/account/token/resolve — returns the auth'd account. */
    public AccountDto getMyAccount() throws Exception {
        return this.executeGet("/admin/account/token/resolve", null, AccountDto.class);
    }

    /** GET /data/account/{accountUuid} — full PlayerAccountDto with characters + chests. */
    public PlayerAccountDto getAccount(String accountGuid) throws Exception {
        return this.executeGet("/data/account/" + accountGuid, null, PlayerAccountDto.class);
    }

    /** POST /admin/account/register — guest creates DEMO account; otherwise standard PLAYER. */
    public AccountDto register(String email, String password, String accountName, boolean guest) throws Exception {
        AccountDto body = AccountDto.builder()
                .email(email)
                .password(password)
                .accountName(accountName)
                .guest(guest)
                .accountProvisions(Collections.<AccountProvision>emptyList())
                .accountSubscriptions(Collections.emptyList())
                .build();
        return this.executePost("/admin/account/register", body, AccountDto.class);
    }

    /** POST /data/account/{accountUuid}/character?classId={classId} — server picks UUID. */
    public JsonNode createCharacter(String accountUuid, int classId) throws Exception {
        return this.executePost(
                "/data/account/" + accountUuid + "/character?classId=" + classId, null, JsonNode.class);
    }

    /** DELETE /data/account/character/{characterUuid} — soft-delete (sets `deleted` for graveyard). */
    public JsonNode deleteCharacter(String characterUuid) throws Exception {
        return this.executeDelete("/data/account/character/" + characterUuid, JsonNode.class);
    }

    /** POST /data/account/{accountUuid}/chest/new — appends a chest, capped server-side. */
    public JsonNode createChest(String accountUuid) throws Exception {
        return this.executePost("/data/account/" + accountUuid + "/chest/new", null, JsonNode.class);
    }

    /** POST /admin/account/password — server uses bearer-token caller id, body is just the passwords. */
    public JsonNode changePassword(String currentPassword, String newPassword) throws Exception {
        ObjectNode body = new ObjectNode(JsonNodeFactory.instance);
        body.put("currentPassword", currentPassword);
        body.put("newPassword", newPassword);
        return this.executePost("/admin/account/password", body, JsonNode.class);
    }

    public static void main(String[] args) {
        OpenRealmClientDataService service = new OpenRealmClientDataService(HttpClient.newHttpClient(), "http://localhost/", null);
        LoginRequestDto login = new LoginRequestDto("ru-admin@jrealm.com", "password");
        try {
            final SessionTokenDto response = service.executePost("/admin/account/login", login, SessionTokenDto.class);
            System.out.println(response.getToken());
        } catch (Exception e) {
            System.out.println("Failed to login. " + e.getMessage());
        }
    }
}
