package local.dawn.curseforge;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Session-only official CurseForge client. Never persists or logs the caller's API key. */
public class CurseForgeApi {
    private static final String BASE = "https://api.curseforge.com/v1";
    private static final Set<String> HOSTS = Set.of("api.curseforge.com", "edge.forgecdn.net", "mediafilez.forgecdn.net");
    private static final int JSON_LIMIT = 10 * 1024 * 1024;
    private static final long FILE_LIMIT = 1024L * 1024 * 1024;
    private volatile String apiKey = "";
    private final ConnectionFactory connections;

    @FunctionalInterface interface ConnectionFactory { HttpURLConnection open(URI uri) throws IOException; }
    public CurseForgeApi() { this(uri -> (HttpURLConnection) uri.toURL().openConnection()); }
    CurseForgeApi(ConnectionFactory connections) { this.connections = Objects.requireNonNull(connections); }
    public void setApiKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.length() > 2048 || key.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IllegalArgumentException("The API key contains invalid characters.");
        apiKey = key;
    }
    public boolean available() { return !apiKey.isEmpty(); }

    /** Validate Minecraft catalog access before replacing the active session key. */
    public void verifyAndSetApiKey(String value) throws IOException {
        CurseForgeApi candidate = new CurseForgeApi(connections);
        candidate.setApiKey(value);
        try {
            JsonArray data = arrayData(candidate.get("/mods/search?gameId=432&classId=6&pageSize=1&index=0"), 1);
            for (JsonElement element : data) validateProject(object(element), 0);
            apiKey = candidate.apiKey;
        } finally { candidate.apiKey = ""; }
    }

    public JsonArray search(String query, boolean packs, String mcVersion, String loader, int index) throws IOException {
        if (index < 0 || index > 9950) throw new ApiException("Search index must be between 0 and 9950.");
        String term = query == null ? "" : query.trim();
        if (term.length() > 256) throw new ApiException("Search text is too long.");
        String route = "/mods/search?gameId=432&classId=" + (packs ? 4471 : 6) + "&pageSize=50&sortField=6&sortOrder=desc&index=" + index + "&searchFilter=" + encode(term) + filters(mcVersion, loader);
        JsonArray data = arrayData(get(route), 50);
        for (JsonElement element : data) validateProject(object(element), 0);
        return data;
    }
    public JsonArray files(long projectId, String mcVersion, String loader) throws IOException {
        id(projectId); JsonArray result = new JsonArray(); Set<Long> seen = new HashSet<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        for (int index = 0; index <= 9950; index += 50) {
            checkDeadline(deadline);
            JsonObject response = get("/mods/" + projectId + "/files?pageSize=50&index=" + index + filters(mcVersion, loader));
            JsonArray data = arrayData(response, 50);
            for (JsonElement element : data) {
                JsonObject file = object(element); validateFile(file, projectId, 0);
                if (!seen.add(number(file, "id"))) throw new ApiException("CurseForge's file list changed while loading. Please retry.");
                result.add(file);
            }
            if (data.size() < 50) return result;
            if (response.has("pagination")) {
                JsonObject pagination = object(response.get("pagination"));
                long total = number(pagination, "totalCount");
                if (total > 10000) throw new ApiException("Too many files. Choose a Minecraft version and loader to narrow the results.");
                if (index + data.size() >= total) return result;
            }
        }
        throw new ApiException("The file list exceeds the API limit. Choose a Minecraft version and loader.");
    }
    public JsonObject file(long projectId, long fileId) throws IOException {
        id(projectId); id(fileId); JsonObject result = object(get("/mods/" + projectId + "/files/" + fileId).get("data"));
        validateFile(result, projectId, fileId); return result;
    }
    public JsonObject project(long projectId) throws IOException {
        id(projectId); JsonObject result = object(get("/mods/" + projectId).get("data"));
        validateProject(result, projectId); return result;
    }
    public Path download(JsonObject file, Path target) throws IOException {
        validateFile(file, 0, 0);
        if (file.has("isAvailable") && !bool(file, "isAvailable")) throw new DownloadUnavailableException("This file is no longer available on CurseForge.");
        if (!file.has("downloadUrl") || file.get("downloadUrl").isJsonNull())
            throw new DownloadUnavailableException("The author has disabled third-party downloads for this file. Open its official CurseForge page to download it manually.");
        URI uri = safeUri(string(file, "downloadUrl", 8192), false);
        long length = number(file, "fileLength");
        if (length < 0 || length > FILE_LIMIT) throw new ApiException("The file exceeds the 1 GiB download limit.");
        Map<String, String> expected = hashes(file); Map<String, MessageDigest> digests = new LinkedHashMap<>();
        try { for (String name : expected.keySet()) digests.put(name, MessageDigest.getInstance(name)); }
        catch (NoSuchAlgorithmException e) { throw new ApiException("A required integrity algorithm is unavailable."); }
        Objects.requireNonNull(target, "target"); Path destination = target.toAbsolutePath().normalize();
        safeBasename(destination.getFileName().toString()); safeBasename(string(file, "fileName", 240));
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new ApiException("The destination already exists; no file was replaced.");
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) throw new ApiException("The download destination is not a regular directory.");
        Path temporary = parent.resolve(".curseforge-" + UUID.randomUUID() + ".part");
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        HttpURLConnection connection = null; boolean published = false;
        try {
            connection = openDownload(uri, deadline);
            long advertised = connection.getContentLengthLong();
            if (advertised >= 0 && advertised != length) throw new ApiException("The download length does not match CurseForge metadata.");
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024]; long received = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    checkDeadline(deadline); received += count;
                    if (received > length || received > FILE_LIMIT) throw new ApiException("The download exceeded its declared size.");
                    for (MessageDigest digest : digests.values()) digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                if (received != length) throw new ApiException("The download was incomplete. Please try again.");
            }
            for (String name : expected.keySet()) if (!MessageDigest.isEqual(HexFormat.of().parseHex(expected.get(name)), digests.get(name).digest()))
                throw new ApiException("The download failed its integrity check. Nothing was installed.");
            // Atomic no-replace publication: unlike ATOMIC_MOVE, a hard link cannot replace a destination.
            try { Files.createLink(destination, temporary); published = true; }
            catch (FileAlreadyExistsException e) { throw new ApiException("The destination already exists; no file was replaced."); }
            catch (UnsupportedOperationException e) { throw new ApiException("This filesystem does not support safe atomic download publication. Use a local NTFS directory."); }
            return destination;
        } catch (SocketTimeoutException e) { throw new ApiException("CurseForge timed out. Check your connection and try again."); }
        catch (ApiException | DownloadUnavailableException e) { throw e; }
        catch (IOException e) { throw new ApiException("The CurseForge download could not be completed. Check your connection, free disk space and destination permissions."); }
        finally {
            if (connection != null) connection.disconnect();
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { if (!published) throw new ApiException("The failed download could not be cleaned up. Remove the .curseforge partial file before retrying."); }
        }
    }
    public static class DownloadUnavailableException extends IOException { public DownloadUnavailableException(String message) { super(message); } }
    private static class ApiException extends IOException { ApiException(String message) { super(message); } }

    private JsonObject get(String route) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = connect(safeUri(BASE + route, true));
            int code = connection.getResponseCode();
            if (code == 403 && route.startsWith("/mods/search?"))
                throw new ApiException("CurseForge denied access to the Minecraft catalog (HTTP 403). This requires an approved third-party developer API key; an author upload token is a different credential. If your key was approved, check the owning account or contact CurseForge support. Changing Dawn or Windows permissions will not grant API access.");
            status(code);
            if (connection.getContentLengthLong() > JSON_LIMIT) throw new ApiException("CurseForge returned oversized metadata.");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    checkDeadline(deadline); if (bytes.size() + count > JSON_LIMIT) throw new ApiException("CurseForge returned oversized metadata."); bytes.write(buffer, 0, count);
                }
            }
            String json = bytes.toString(StandardCharsets.UTF_8); limitDepth(json);
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                reader.setStrictness(Strictness.STRICT); JsonElement parsed = JsonParser.parseReader(reader);
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new ApiException("CurseForge returned invalid JSON.");
                return object(parsed);
            } catch (JsonParseException | IllegalStateException e) { throw new ApiException("CurseForge returned invalid JSON."); }
        } catch (SocketTimeoutException e) { throw new ApiException("CurseForge timed out. Check your connection and retry."); }
        catch (UnknownHostException | ConnectException e) { throw new ApiException("Cannot reach CurseForge. Check your connection and retry."); }
        catch (ApiException e) { throw e; }
        catch (IOException e) { throw new ApiException("Cannot read CurseForge's response. Check your connection and retry."); }
        finally { if (connection != null) connection.disconnect(); }
    }
    private HttpURLConnection openDownload(URI uri, long deadline) throws IOException {
        for (int redirects = 0; redirects <= 4; redirects++) {
            checkDeadline(deadline); HttpURLConnection connection = connect(uri); int status;
            try { status = connection.getResponseCode(); } catch (IOException e) { connection.disconnect(); throw new ApiException("Cannot reach the CurseForge download service. Please retry."); }
            if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null || redirects == 4) throw new ApiException("CurseForge returned an invalid download redirect.");
                try { uri = safeUri(uri.resolve(location).toString(), false); } catch (IllegalArgumentException e) { throw new ApiException("CurseForge returned an invalid download redirect."); }
            } else { try { status(status); return connection; } catch (IOException e) { connection.disconnect(); throw e; } }
        }
        throw new ApiException("CurseForge returned too many redirects.");
    }
    private HttpURLConnection connect(URI uri) throws IOException {
        String key = apiKey; if (key.isEmpty()) throw new ApiException("Enter your CurseForge API key to search or download content.");
        HttpURLConnection connection = connections.open(uri);
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        connection.setRequestMethod("GET"); connection.setRequestProperty("Accept", "application/json, application/octet-stream");
        connection.setRequestProperty("Accept-Encoding", "identity"); connection.setRequestProperty("User-Agent", "DawnCurseForgeAddon/1.0"); connection.setRequestProperty("x-api-key", key);
        return connection;
    }
    static URI safeUri(String value, boolean api) throws IOException {
        try {
            URI uri = URI.create(value); String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT)) || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getFragment() != null || (api && (!"api.curseforge.com".equalsIgnoreCase(host) || !uri.getPath().startsWith("/v1/"))))
                throw new ApiException("CurseForge supplied an unsupported download URL. No API key was sent.");
            return uri;
        } catch (IllegalArgumentException e) { throw new ApiException("CurseForge supplied an invalid URL. No API key was sent."); }
    }
    private static void status(int code) throws IOException {
        if (code == 200) return;
        throw new ApiException(switch (code) { case 401 -> "CurseForge rejected the API key. Enter a valid key with download access."; case 403 -> "CurseForge denied access. Check key permissions and the author's third-party distribution setting."; case 404 -> "This CurseForge project or file is no longer available."; case 429 -> "CurseForge's rate limit was reached. Wait a minute and try again."; default -> "CurseForge request failed (HTTP " + code + "). Please retry."; });
    }
    private static String filters(String version, String loader) throws IOException {
        String result = "";
        if (version != null && !version.isBlank() && !version.equalsIgnoreCase("all")) { if (!version.matches("[A-Za-z0-9_.-]{1,60}")) throw new ApiException("Choose a valid Minecraft version."); result += "&gameVersion=" + encode(version); }
        String normalized = loader == null ? "all" : loader.trim().toLowerCase(Locale.ROOT);
        int type = switch (normalized) { case "", "all", "any", "vanilla" -> 0; case "forge" -> 1; case "fabric" -> 4; case "quilt" -> 5; case "neoforge" -> 6; default -> throw new ApiException("Choose a supported mod loader."); };
        return result + (type == 0 ? "" : "&modLoaderType=" + type);
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static void id(long value) throws IOException { if (value <= 0 || value > 0xffffffffL) throw new ApiException("Invalid CurseForge identifier."); }
    private static JsonObject object(JsonElement value) throws IOException { if (value == null || !value.isJsonObject()) throw new ApiException("CurseForge returned malformed metadata."); return value.getAsJsonObject(); }
    private static JsonArray arrayData(JsonObject value, int max) throws IOException { JsonElement data = value.get("data"); if (data == null || !data.isJsonArray() || data.getAsJsonArray().size() > max) throw new ApiException("CurseForge returned an invalid result list."); return data.getAsJsonArray(); }
    private static String string(JsonObject value, String key, int max) throws IOException { JsonElement item = value.get(key); if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().length() > max) throw new ApiException("CurseForge returned an invalid " + key + " field."); return item.getAsString(); }
    private static long number(JsonObject value, String key) throws IOException { try { JsonElement item = value.get(key); if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) throw new ArithmeticException(); return item.getAsBigDecimal().longValueExact(); } catch (ArithmeticException | NumberFormatException e) { throw new ApiException("CurseForge returned an invalid " + key + " field."); } }
    private static boolean bool(JsonObject value, String key) throws IOException { JsonElement item = value.get(key); if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean()) throw new ApiException("CurseForge returned an invalid " + key + " field."); return item.getAsBoolean(); }
    private static void validateProject(JsonObject value, long expectedId) throws IOException { long found = number(value, "id"); id(found); if (number(value, "gameId") != 432 || (expectedId != 0 && expectedId != found)) throw new ApiException("CurseForge returned a different project than requested."); string(value, "name", 500); if (value.has("allowModDistribution") && !value.get("allowModDistribution").isJsonNull()) bool(value, "allowModDistribution"); }
    private static void validateFile(JsonObject value, long project, long file) throws IOException { long found = number(value, "id"), owner = number(value, "modId"); id(found); id(owner); if (number(value, "gameId") != 432 || (project != 0 && owner != project) || (file != 0 && found != file)) throw new ApiException("CurseForge returned a different file than requested."); safeBasename(string(value, "fileName", 240)); long length = number(value, "fileLength"); if (length < 0) throw new ApiException("CurseForge returned an invalid file length."); }
    static void safeBasename(String name) throws IOException { if (name.isBlank() || name.length() > 240 || name.matches(".*[\\\\/:<>\"|?*\\p{Cntrl}].*") || name.endsWith(".") || name.endsWith(" ") || name.equals(".") || name.equals("..") || name.matches("(?i)^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?$")) throw new ApiException("CurseForge supplied an unsafe filename."); }
    private static Map<String, String> hashes(JsonObject file) throws IOException {
        JsonElement values = file.get("hashes"); if (values == null || !values.isJsonArray() || values.getAsJsonArray().size() > 8) throw new ApiException("CurseForge did not provide valid integrity hashes."); Map<String, String> result = new LinkedHashMap<>();
        for (JsonElement value : values.getAsJsonArray()) { JsonObject hash = object(value); long algorithm = number(hash, "algo"); if (algorithm != 1 && algorithm != 2) continue; String name = algorithm == 1 ? "SHA-1" : "MD5"; String hex = string(hash, "value", 128).toLowerCase(Locale.ROOT); if (!hex.matches(algorithm == 1 ? "[0-9a-f]{40}" : "[0-9a-f]{32}") || (result.containsKey(name) && !result.get(name).equals(hex))) throw new ApiException("CurseForge supplied an invalid integrity hash."); result.put(name, hex); }
        if (result.isEmpty()) throw new ApiException("CurseForge did not provide a SHA1 or MD5 integrity hash."); return result;
    }
    private static void checkDeadline(long deadline) throws IOException { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CurseForge operation cancelled."); if (System.nanoTime() > deadline) throw new ApiException("CurseForge operation timed out. Please try again."); }
    private static void limitDepth(String json) throws IOException { int depth = 0; boolean quoted = false, escaped = false; for (int i = 0; i < json.length(); i++) { char c = json.charAt(i); if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; } else if (c == '"') quoted = true; else if ((c == '{' || c == '[') && ++depth > 64) throw new ApiException("CurseForge metadata is nested too deeply."); else if (c == '}' || c == ']') depth--; } }
}
