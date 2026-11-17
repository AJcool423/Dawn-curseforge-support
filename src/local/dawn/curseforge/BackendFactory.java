package local.dawn.curseforge;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class BackendFactory {
    private BackendFactory() {}
    public static CatalogPanel.CatalogBackend create() {
        return new Backend();
    }
    private static final class Backend implements CatalogPanel.CatalogBackend {
        private final CurseForgeApi api = new CurseForgeApi();
        private final DawnInstaller installer = new DawnInstaller(
                Path.of(System.getenv("APPDATA"), ".dawn"), api);
        public boolean available() { return api.available(); }
        public void setApiKey(String key) throws Exception { api.verifyAndSetApiKey(key); }
        public List<CatalogPanel.Item> search(String query,String type,String version,String loader) throws Exception {
            boolean packs = "modpack".equals(type);
            List<CatalogPanel.Item> result = new ArrayList<>();
            for (JsonElement value : api.search(query,packs,version,loader,0)) {
                JsonObject p = value.getAsJsonObject();
                result.add(new CatalogPanel.Item(p.get("id").getAsLong(),str(p,"name"),str(p,"summary"),packs));
            }
            return result;
        }
        public List<CatalogPanel.FileItem> files(CatalogPanel.Item item,String version,String loader) throws Exception {
            List<CatalogPanel.FileItem> result = new ArrayList<>();
            for(JsonElement value : api.files(item.id(),version,loader)) {
                JsonObject f=value.getAsJsonObject();
                if(f.has("isServerPack") && f.get("isServerPack").getAsBoolean()) continue;
                result.add(new CatalogPanel.FileItem(f.get("id").getAsLong(),str(f,"displayName")));
            }
            return result;
        }
        public List<CatalogPanel.Profile> profiles() throws Exception { return installer.profiles(); }
        public String install(CatalogPanel.Item item,CatalogPanel.FileItem file,CatalogPanel.Profile profile,Consumer<String> progress) throws Exception {
            return installer.install(item,file,profile,progress);
        }
        private static String str(JsonObject o,String key) { return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():""; }
    }
}
