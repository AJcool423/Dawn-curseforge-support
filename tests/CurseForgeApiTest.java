package local.dawn.curseforge;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Offline transport fixtures: no real API key or network access. Run with -ea. */
public class CurseForgeApiTest {
    private static int assertions;
    private static final String KEY = "fixture-secret-not-for-network";
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static IOException fails(Checked task, String expected) throws Exception {
        try { task.run(); throw new AssertionError("Expected failure: " + expected); }
        catch (IOException error) { check(error.getMessage().contains(expected), "Unexpected error: " + error.getMessage()); check(!error.getMessage().contains(KEY), "Key leaked"); return error; }
    }
    private static JsonObject file(long id, byte[] bytes) throws Exception {
        JsonObject value = new JsonObject(); value.addProperty("id", id); value.addProperty("modId", 123); value.addProperty("gameId", 432);
        value.addProperty("fileName", "fixture.jar"); value.addProperty("fileLength", bytes.length); value.addProperty("isAvailable", true);
        value.addProperty("downloadUrl", "https://edge.forgecdn.net/files/123/456/fixture.jar");
        JsonArray hashes = new JsonArray();
        for (String algorithm : List.of("SHA-1", "MD5")) { JsonObject hash = new JsonObject(); hash.addProperty("algo", algorithm.equals("SHA-1") ? 1 : 2); hash.addProperty("value", HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes))); hashes.add(hash); }
        value.add("hashes", hashes); return value;
    }
    private static class Response extends HttpURLConnection {
        final int status; final byte[] body; final Map<String,String> headers = new HashMap<>(); Runnable onResponse;
        Response(URI uri, int status, byte[] body) throws Exception { super(uri.toURL()); this.status = status; this.body = body; }
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { if (onResponse != null) onResponse.run(); return status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public long getContentLengthLong() { return body.length; }
    }
    private static Response response(URI uri, int status, String body) throws IOException {
        try { return new Response(uri, status, body.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new IOException("fixture"); }
    }
    private static CurseForgeApi api(CurseForgeApi.ConnectionFactory factory) { CurseForgeApi api = new CurseForgeApi(factory); api.setApiKey(KEY); return api; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("dawn-cf-api-test-");
        try { urls(); verifiedKey(); searchAndPaging(); metadata(); downloadTests(root); errors(); }
        finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
        System.out.println("CurseForgeApiTest: " + assertions + " assertions passed (offline fixtures; no live API key).");
    }
    private static void urls() throws Exception {
        for (String url : List.of("http://edge.forgecdn.net/a", "https://edge.forgecdn.net.evil.test/a", "https://localhost/a", "https://a:b@edge.forgecdn.net/a", "https://edge.forgecdn.net:444/a", "https://edge.forgecdn.net/a#fragment", "https://127.0.0.1/a")) fails(() -> CurseForgeApi.safeUri(url, false), "URL");
        check(CurseForgeApi.safeUri("https://mediafilez.forgecdn.net/files/a", false).getHost().equals("mediafilez.forgecdn.net"), "Official CDN rejected");
        for (String name : List.of("../a.jar", "C:\\a.jar", "a.jar:stream", "CON.jar", "a.", "a ", "a/b.jar", "a\u0000.jar")) fails(() -> CurseForgeApi.safeBasename(name), "unsafe filename");
        CurseForgeApi noKey = new CurseForgeApi(uri -> { throw new AssertionError("No-key request reached transport"); });
        check(!noKey.available(), "Key should be empty"); fails(() -> noKey.search("", false, "", "", 0), "API key"); noKey.setApiKey(KEY); check(noKey.available(), "Key unavailable"); noKey.setApiKey(null); check(!noKey.available(), "Cleared key remained");
    }
    private static void verifiedKey() throws Exception {
        List<Response> replies = new ArrayList<>();
        CurseForgeApi denied = new CurseForgeApi(uri -> response(uri,403,""));
        fails(() -> denied.verifyAndSetApiKey(KEY), "approved third-party");
        check(!denied.available(), "Rejected key enabled catalog");
        CurseForgeApi valid = new CurseForgeApi(uri -> {
            if(replies.size()<2) check(uri.getRawQuery().contains("gameId=432") && uri.getRawQuery().contains("pageSize=1"), "Key check did not target Minecraft catalog");
            Response r=response(uri,replies.size()==1?403:200,"{\"data\":[]}");replies.add(r);return r;
        });
        valid.verifyAndSetApiKey(KEY);
        check(valid.available(),"Verified key not committed");
        fails(() -> valid.verifyAndSetApiKey("rejected-replacement"),"approved third-party");
        valid.search("",false,"","",0);
        check(KEY.equals(replies.get(2).getRequestProperty("x-api-key")),"Rejected replacement displaced working key");
    }
    private static void searchAndPaging() throws Exception {
        List<URI> seen = new ArrayList<>(); List<Response> replies = new ArrayList<>();
        CurseForgeApi api = api(uri -> { seen.add(uri); Response response = response(uri, 200, "{\"data\":[{\"id\":123,\"gameId\":432,\"name\":\"Example\"}]}"); replies.add(response); return response; });
        check(api.search("a&b", true, "1.21.1", "NeoForge", 50).size() == 1, "Search failed");
        String query = seen.get(0).getRawQuery(); check(query.contains("classId=4471") && query.contains("searchFilter=a%26b") && query.contains("modLoaderType=6") && query.contains("index=50") && query.contains("pageSize=50"), "Search filters wrong");
        check(KEY.equals(replies.get(0).getRequestProperty("x-api-key")), "API auth missing"); check(!replies.get(0).getInstanceFollowRedirects(), "Automatic redirects enabled");
        int[] pages = {0};
        CurseForgeApi paged = api(uri -> {
            try { JsonArray data = new JsonArray(); int count = pages[0]++ == 0 ? 50 : 1; long offset = count == 50 ? 0 : 50;
                for (int i = 0; i < count; i++) data.add(file(i + offset + 1, new byte[]{1}));
                JsonObject result = new JsonObject(); result.add("data", data); return response(uri, 200, result.toString());
            } catch (Exception e) { throw new IOException("fixture"); }
        });
        check(paged.files(123, "1.21.1", "Fabric").size() == 51 && pages[0] == 2, "File pagination failed");
        fails(() -> api.search("", false, "", "bad-loader", 0), "supported mod loader"); fails(() -> api.search("", false, "", "all", 9951), "index");
    }
    private static void metadata() throws Exception {
        CurseForgeApi api = api(uri -> response(uri, 200, "{\"data\":{\"id\":999,\"gameId\":432,\"name\":\"Wrong\"}}"));
        fails(() -> api.project(123), "different project");
        CurseForgeApi badNumber = api(uri -> response(uri, 200, "{\"data\":{\"id\":123.5,\"gameId\":432,\"name\":\"Bad\"}}"));
        fails(() -> badNumber.project(123), "invalid id");
        CurseForgeApi deep = api(uri -> response(uri, 200, "[".repeat(65) + "0" + "]".repeat(65))); fails(() -> deep.project(123), "deeply");
        CurseForgeApi oversized = api(uri -> response(uri, 200, " ".repeat(10 * 1024 * 1024 + 1))); fails(() -> oversized.project(123), "oversized");
    }
    private static void downloadTests(Path root) throws Exception {
        byte[] bytes = "verified-fixture-content".getBytes(StandardCharsets.UTF_8); JsonObject metadata = file(456, bytes); List<Response> replies = new ArrayList<>();
        CurseForgeApi valid = api(uri -> { Response response = response(uri, 200, new String(bytes, StandardCharsets.UTF_8)); replies.add(response); return response; });
        Path target = root.resolve("valid.jar"); check(valid.download(metadata, target).equals(target), "Wrong download result"); check(Arrays.equals(Files.readAllBytes(target), bytes), "File bytes differ"); check(KEY.equals(replies.get(0).getRequestProperty("x-api-key")), "CDN key missing");
        fails(() -> valid.download(metadata, target), "already exists"); check(Arrays.equals(Files.readAllBytes(target), bytes), "Existing file overwritten");
        JsonObject unavailable = metadata.deepCopy(); unavailable.add("downloadUrl", JsonNull.INSTANCE); check(fails(() -> valid.download(unavailable, root.resolve("blocked.jar")), "disabled third-party") instanceof CurseForgeApi.DownloadUnavailableException, "Blocked download type missing");
        JsonObject incorrect = metadata.deepCopy(); incorrect.getAsJsonArray("hashes").get(0).getAsJsonObject().addProperty("value", "0".repeat(40)); fails(() -> valid.download(incorrect, root.resolve("corrupt.jar")), "integrity"); check(!Files.exists(root.resolve("corrupt.jar")), "Corrupt file published");
        JsonObject length = metadata.deepCopy(); length.addProperty("fileLength", bytes.length + 1); fails(() -> valid.download(length, root.resolve("length.jar")), "length");
        int[] connections = {0}; CurseForgeApi redirect = api(uri -> { connections[0]++; Response response = response(uri, 302, ""); response.headers.put("Location", "https://evil.test/steal"); return response; });
        fails(() -> redirect.download(metadata, root.resolve("redirect.jar")), "unsupported download URL"); check(connections[0] == 1, "Unknown redirect received a request");
        List<Response> redirectResponses = new ArrayList<>(); CurseForgeApi officialRedirect = api(uri -> { Response response = response(uri, redirectResponses.isEmpty() ? 302 : 200, redirectResponses.isEmpty() ? "" : new String(bytes, StandardCharsets.UTF_8)); if (redirectResponses.isEmpty()) response.headers.put("Location", "https://mediafilez.forgecdn.net/files/fixture.jar"); redirectResponses.add(response); return response; });
        officialRedirect.download(metadata, root.resolve("redirect-ok.jar")); check(redirectResponses.size() == 2 && KEY.equals(redirectResponses.get(1).getRequestProperty("x-api-key")), "Validated CDN redirect auth missing");
        Path race = root.resolve("race.jar"); CurseForgeApi racing = api(uri -> { Response response = response(uri, 200, new String(bytes, StandardCharsets.UTF_8)); response.onResponse = () -> { try { Files.writeString(race, "preserve"); } catch (IOException e) { throw new UncheckedIOException(e); } }; return response; });
        fails(() -> racing.download(metadata, race), "already exists"); check(Files.readString(race).equals("preserve"), "Concurrent target overwritten");
        try (var files = Files.list(root)) { check(files.noneMatch(path -> path.toString().endsWith(".part")), "Partial files leaked"); }
    }
    private static void errors() throws Exception {
        for (int code : List.of(401, 403, 429)) { CurseForgeApi api = api(uri -> response(uri, code, KEY)); fails(() -> api.search("", false, "", "", 0), code == 401 ? "API key" : code == 403 ? "denied access" : "rate limit"); }
        CurseForgeApi leakedTransport = api(uri -> { throw new IOException("internal URL contains " + KEY); }); fails(() -> leakedTransport.project(123), "Cannot read");
    }
}
