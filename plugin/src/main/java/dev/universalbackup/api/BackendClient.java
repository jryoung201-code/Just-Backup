package dev.universalbackup.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.universalbackup.model.PlayerSnapshot;
import dev.universalbackup.model.Registration;
import dev.universalbackup.security.RegistrationStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

public final class BackendClient {
    private final RegistrationStore registration;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json = new ObjectMapper();

    public BackendClient(RegistrationStore registration) { this.registration = registration; }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(ServiceConstants.API_BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (registration.isRegistered()) b.header("Authorization", "Bearer " + registration.get().token());
        return b;
    }

    private JsonNode send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Backup API " + response.statusCode() + ": " + response.body());
        }
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }

    public Registration register(String password, String serverName) throws Exception {
        String body = json.createObjectNode().put("pass", password).put("name", serverName).toString();
        JsonNode n = send(request("/v1/register").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        return new Registration(n.get("serverId").asText(), n.get("accountId").asText(), n.get("token").asText(), n.get("registeredIp").asText());
    }

    public Registration join(String password, String serverName) throws Exception {
        String body = json.createObjectNode().put("pass", password).put("name", serverName).toString();
        JsonNode n = send(request("/v1/join").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        return new Registration(n.get("serverId").asText(), n.get("accountId").asText(), n.get("token").asText(), n.get("registeredIp").asText());
    }

    public void resetPassword(String oldPass, String newPass) throws Exception {
        String body = json.createObjectNode().put("oldPass", oldPass).put("newPass", newPass).toString();
        send(request("/v1/password/reset").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public void save(PlayerSnapshot snapshot) throws Exception {
        String body = json.createObjectNode()
                .put("uuid", snapshot.uuid().toString())
                .put("name", snapshot.playerName())
                .put("createdAt", snapshot.createdAt())
                .put("checksum", snapshot.checksum())
                .put("data", Base64.getEncoder().encodeToString(snapshot.compressedYaml()))
                .toString();
        send(request("/v1/player/save").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public PlayerSnapshot latest(UUID uuid) throws Exception {
        JsonNode n = send(request("/v1/player/latest/" + uuid).GET().build());
        byte[] data = Base64.getDecoder().decode(n.get("data").asText());
        return new PlayerSnapshot(uuid, n.path("name").asText(""), n.get("createdAt").asLong(), data, n.get("checksum").asText());
    }

    public JsonNode listPlayers() throws Exception {
        return send(request("/v1/player/list").GET().build());
    }

    public JsonNode startDestructive(String type, String password) throws Exception {
        String body = json.createObjectNode().put("type", type).put("pass", password).toString();
        return send(request("/v1/destructive/start").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public void verifyDestructive(String id, String code, String serverIp) throws Exception {
        String body = json.createObjectNode().put("id", id).put("verificationCode", code).put("serverIp", serverIp).toString();
        send(request("/v1/destructive/verify").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public void confirmDestructive(String id) throws Exception {
        String body = json.createObjectNode().put("id", id).toString();
        send(request("/v1/destructive/confirm").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }
}
