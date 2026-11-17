package local.dawn.curseforge;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** Installs only into isolated Dawn profiles; never rewrites existing game files. */
public final class DawnInstaller {
    private static final Object INSTALL_LOCK = new Object();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAX_ARCHIVE = 512L << 20, MAX_EXPANDED = 1L << 30, MAX_ENTRY = 256L << 20;
    private final Path home;
    private final CurseForgeApi api;

    public DawnInstaller(Path dawnHome, CurseForgeApi api) {
        this.home = dawnHome.toAbsolutePath().normalize(); this.api = Objects.requireNonNull(api);
    }

    public List<CatalogPanel.Profile> profiles() {
        List<CatalogPanel.Profile> result = new ArrayList<>();
        Path root = home.resolve("profiles");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return result;
        try (Stream<Path> paths = Files.list(root)) {
            for (Path path : paths.limit(1000).toList()) {
                try {
                    Existing existing = readProfile(path.getFileName().toString());
                    JsonObject p = existing.profile;
                    result.add(new CatalogPanel.Profile(string(p,"id"), string(p,"name"),
                            string(p,"minecraftVersion"), string(p.getAsJsonObject("loader"),"kind")));
                } catch (Exception ignored) { /* Invalid or unsupported profiles are not install targets. */ }
            }
        } catch (IOException ignored) { return List.of(); }
        result.sort(Comparator.comparing(CatalogPanel.Profile::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public String install(CatalogPanel.Item item, CatalogPanel.FileItem selected,
            CatalogPanel.Profile profile, Consumer<String> progress) throws Exception {
        synchronized(INSTALL_LOCK) { return installLocked(item,selected,profile,progress); }
    }
    private String installLocked(CatalogPanel.Item item, CatalogPanel.FileItem selected,
            CatalogPanel.Profile profile, Consumer<String> progress) throws Exception {
        Objects.requireNonNull(item); Objects.requireNonNull(selected);
        if (item.id() <= 0 || selected.id() <= 0) throw new IOException("Choose a valid CurseForge project and file.");
        Consumer<String> report = progress == null ? message -> {} : progress;
        ensureNoLinks(home);
        Path stagingRoot = home.resolve(".curseforge-staging");
        Files.createDirectories(stagingRoot); ensureNoLinks(stagingRoot);
        Path lockPath=stagingRoot.resolve("install.lock");ensureNoLinks(lockPath);
        try(FileChannel lockChannel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=lockChannel.tryLock()) {
        if(lock==null)throw new IOException("Another CurseForge installation is in progress. Wait for it to finish.");
        Path stage = Files.createTempDirectory(stagingRoot, "install-");
        try {
            JsonObject project = checkedProject(item.id());
            JsonObject file = checkedFile(item.id(), selected.id());
            return item.modpack() ? installPack(item, project, file, stage, report)
                    : installMod(item, file, profile, stage, report);
        } finally { deleteStage(stage, stagingRoot); }
        } catch(OverlappingFileLockException error) { throw new IOException("Another CurseForge installation is in progress. Wait for it to finish.",error); }
    }

    private String installMod(CatalogPanel.Item item, JsonObject file, CatalogPanel.Profile target,
            Path stage, Consumer<String> progress) throws Exception {
        if (target == null) throw new IOException("Choose the Dawn profile that should receive this mod.");
        Existing existing = readProfile(target.id());
        String version = string(existing.profile,"minecraftVersion");
        String loader = string(existing.profile.getAsJsonObject("loader"),"kind");
        requireModLoader(loader);
        assertStopped(existing.game);
        if (!version.equals(target.version()) || !loader.equalsIgnoreCase(target.loader()))
            throw new IOException("This profile changed. Refresh the profile list and select it again.");
        Plan plan = new Plan(version, loader, stage.resolve("files"), progress);
        plan.add(item.id(), file, true);
        Path mods = existing.game.resolve("mods");
        ensureNoLinks(existing.game); ensureNoLinks(mods);
        plan.checkExisting(mods);
        if (!Arrays.equals(existing.original, Files.readAllBytes(existing.directory.resolve("profile.json"))))
            throw new IOException("Dawn changed this profile during installation. Retry after closing its editor.");
        assertStopped(existing.game);
        plan.verifyReused(mods);
        Files.createDirectories(mods); ensureNoLinks(mods);
        List<Path> moved = new ArrayList<>(); Map<Path, byte[]> hashes = new HashMap<>();
        try {
            for (Download entry : plan.downloads.values()) {
                if(plan.reused.contains(entry.path))continue;
                assertStopped(existing.game);
                Path destination = mods.resolve(fileName(entry.file));
                ensureNoLinks(destination);
                hashes.put(destination, digest(entry.path));
                Files.move(entry.path, destination); // No REPLACE_EXISTING: concurrent files are never overwritten.
                moved.add(destination);
            }
        } catch (Exception error) {
            List<String> retained = rollback(moved, hashes);
            if (!retained.isEmpty()) throw new IOException("Install failed. Some new files changed externally and were preserved: " + String.join(", ", retained), error);
            throw new IOException("Install failed; newly added files were rolled back. " + error.getMessage(), error);
        }
        return "Installed " + moved.size() + " new mod file(s) into " + target.name()
                + (plan.reused.isEmpty()?"":"; reused "+plan.reused.size()+" identical existing file(s)")
                + ". Existing files were not replaced. Restart Minecraft to load them.";
    }

    private String installPack(CatalogPanel.Item item, JsonObject project, JsonObject file, Path stage,
            Consumer<String> progress) throws Exception {
        progress.accept("Downloading modpack archive…");
        Path archive = stage.resolve("pack.zip"); api.download(file, archive);
        validateZip(archive);
        JsonObject manifest;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null) throw new IOException("This archive has no CurseForge manifest.json.");
            manifest = parseObject(readEntry(zip, entry, 1L << 20));
        }
        if (!"minecraftModpack".equals(string(manifest,"manifestType")) || number(manifest,"manifestVersion") != 1)
            throw new IOException("Only CurseForge minecraftModpack manifest version 1 is supported.");
        JsonObject minecraft = manifest.getAsJsonObject("minecraft");
        String version = checkedVersion(string(minecraft,"version"));
        Loader loader = packLoader(minecraft.getAsJsonArray("modLoaders"));
        String id = "cf-" + UUID.randomUUID();
        Path profileDir = stage.resolve("profile"), game = profileDir.resolve(".minecraft");
        Files.createDirectories(game);
        Plan plan = new Plan(version, loader.kind, game, progress);
        JsonArray files = manifest.getAsJsonArray("files");
        if (files == null || files.size() > 2000) throw new IOException("The modpack manifest has an invalid or oversized file list.");
        int optional = 0;
        for (JsonElement value : files) {
            JsonObject reference = value.getAsJsonObject();
            if (!reference.has("required") || !reference.get("required").getAsBoolean()) { optional++; continue; }
            long projectId = number(reference,"projectID"), fileId = number(reference,"fileID");
            plan.add(projectId, checkedFile(projectId,fileId), false);
        }
        // Every manifest file is pinned. Do not replace exact manifest dependencies with latest versions.
        for (Download entry : plan.downloads.values()) {
            String folder = contentFolder(entry.project);
            Path destination = game.resolve(folder).resolve(fileName(entry.file));
            Files.createDirectories(destination.getParent());
            if (!entry.path.equals(destination)) Files.move(entry.path, destination);
        }
        String overrides = manifest.has("overrides") ? string(manifest,"overrides") : "overrides";
        safeRelative(overrides);
        extractOverrides(archive, overrides, game);
        checkModIds(game.resolve("mods"), List.of());
        String name = manifest.has("name") ? string(manifest,"name") : item.name();
        if (name.isBlank() || name.length() > 160) name = item.name().substring(0, Math.min(160,item.name().length()));
        JsonObject profileJson = newProfile(id,name,version,loader);
        Files.writeString(profileDir.resolve("profile.json"), JSON.toJson(profileJson), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Path profilesRoot = home.resolve("profiles"); Files.createDirectories(profilesRoot); ensureNoLinks(profilesRoot);
        Path destination = profilesRoot.resolve(id);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("New profile ID already exists. Please retry.");
        try { Files.move(profileDir,destination,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { throw new IOException("The Dawn data filesystem cannot atomically publish this new profile. No profile was installed.",error); }
        return "Created Dawn profile “" + name + "” with " + plan.downloads.size() + " required file(s)"
                + (optional == 0 ? "" : "; skipped " + optional + " optional file(s)")
                + ". Refresh Profiles or restart Dawn if it does not appear. " + loader.kind + " " + loader.version
                + " is recorded; Dawn will prepare its runtime when you launch.";
    }

    private final class Plan {
        final String version, loader; final Path stage; final Consumer<String> progress;
        final Map<Long,Download> downloads = new LinkedHashMap<>(); final Set<Long> visiting = new HashSet<>();
        final Set<String> filenames = new HashSet<>();
        final Set<Path> reused = new HashSet<>();
        long totalDownloadBytes;
        Plan(String version,String loader,Path stage,Consumer<String> progress) { this.version=version;this.loader=loader;this.stage=stage;this.progress=progress; }
        void add(long projectId,JsonObject file,boolean recurse) throws Exception {
            long fileId = number(file,"id");
            if (visiting.contains(projectId)) throw new IOException("CurseForge required dependencies contain a cycle. Nothing was installed.");
            if (downloads.containsKey(projectId)) {
                if (number(downloads.get(projectId).file,"id") != fileId) throw new IOException("The dependency graph requests conflicting versions of project " + projectId + ".");
                return;
            }
            if (downloads.size() + visiting.size() >= 2000 || visiting.size() > 64) throw new IOException("Mod dependency limits exceeded.");
            JsonObject project = checkedProject(projectId);
            String folder = contentFolder(project);
            if (recurse && !folder.equals("mods")) throw new IOException("The selected project or required dependency is not a mod.");
            requireCompatible(file, version, folder.equals("mods") ? loader : null);
            String filename = fileName(file);
            if (!filenames.add((folder + "/" + filename).toLowerCase(Locale.ROOT))) throw new IOException("Two selected files have the same destination name: " + filename);
            visiting.add(projectId);
            try {
                if (recurse && file.has("dependencies")) {
                    for (JsonElement value : file.getAsJsonArray("dependencies")) {
                        JsonObject dependency = value.getAsJsonObject();
                        if (number(dependency,"relationType") != 3) continue;
                        long dependencyId = number(dependency,"modId");
                        JsonObject chosen = null;
                        for (JsonElement candidate : api.files(dependencyId,version,loader)) {
                            JsonObject candidateFile = candidate.getAsJsonObject();
                            if (compatible(candidateFile,version,loader)) { chosen = candidateFile; break; }
                        }
                        if (chosen == null) throw new IOException("No compatible required dependency was found for project " + dependencyId + " (" + version + ", " + loader + ").");
                        add(dependencyId,checkedFile(dependencyId,number(chosen,"id")),true);
                    }
                }
                Files.createDirectories(stage);
                long declaredBytes=number(file,"fileLength");
                if(declaredBytes<0 || declaredBytes>(1L<<30) || (totalDownloadBytes+=declaredBytes)>(8L<<30))throw new IOException("Selected files exceed the 8 GiB installation budget.");
                if(Files.getFileStore(stage).getUsableSpace()<declaredBytes+(256L<<20))throw new IOException("Not enough free disk space to safely stage this installation.");
                Path target = stage.resolve("cf-"+projectId+"-"+fileId+"-"+filename);
                progress.accept("Downloading " + string(project,"name") + " — " + filename);
                try { api.download(file,target); }
                catch(CurseForgeApi.DownloadUnavailableException error) { throw new IOException(error.getMessage()+" Open "+website(project,projectId),error); }
                downloads.put(projectId,new Download(project,file,target));
            } finally { visiting.remove(projectId); }
        }
        void checkExisting(Path mods) throws Exception {
            for (Download entry : downloads.values()) {
                Path existing=mods.resolve(fileName(entry.file));
                if (Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) {
                    ensureNoLinks(existing);
                    if(Files.isRegularFile(existing,LinkOption.NOFOLLOW_LINKS) && MessageDigest.isEqual(digest(existing),digest(entry.path)))reused.add(entry.path);
                    else throw new IOException("An existing mod already uses " + fileName(entry.file) + ". Remove or update it in Dawn first; it will not be overwritten.");
                }
                if (entry.file.has("dependencies")) for (JsonElement value : entry.file.getAsJsonArray("dependencies")) {
                    JsonObject dependency = value.getAsJsonObject();
                    if (number(dependency,"relationType") == 5 && downloads.containsKey(number(dependency,"modId")))
                        throw new IOException("Selected mods declare an incompatible dependency. Nothing was installed.");
                }
            }
            checkModIds(mods,downloads.values().stream().map(Download::path).filter(path->!reused.contains(path)).toList());
        }
        void verifyReused(Path mods) throws Exception {
            for(Download entry:downloads.values())if(reused.contains(entry.path)){
                Path existing=mods.resolve(fileName(entry.file));ensureNoLinks(existing);
                if(!Files.isRegularFile(existing,LinkOption.NOFOLLOW_LINKS) || !MessageDigest.isEqual(digest(existing),digest(entry.path)))throw new IOException("An existing dependency changed during installation. Retry after closing Minecraft and other mod managers.");
            }
        }
    }

    private JsonObject checkedProject(long id) throws Exception {
        if (id <= 0) throw new IOException("Invalid project ID.");
        JsonObject project = api.project(id);
        if (number(project,"id") != id) throw new IOException("CurseForge returned a different project.");
        if (project.has("allowModDistribution") && !project.get("allowModDistribution").isJsonNull() && !project.get("allowModDistribution").getAsBoolean())
            throw new IOException("The author disallows third-party downloads. Download manually from " + website(project,id));
        return project;
    }
    private JsonObject checkedFile(long project,long id) throws Exception {
        JsonObject file = api.file(project,id);
        if (number(file,"id") != id || number(file,"modId") != project) throw new IOException("CurseForge returned a mismatched file.");
        if (file.has("isAvailable") && !file.get("isAvailable").getAsBoolean()) throw new IOException("This CurseForge file is unavailable. Open its project website to choose another file.");
        return file;
    }
    private static String website(JsonObject project,long id) {
        try { String url=string(project.getAsJsonObject("links"),"websiteUrl"); if(url.startsWith("https://www.curseforge.com/") || url.startsWith("https://curseforge.com/")) return url; } catch(Exception ignored) {}
        return "https://www.curseforge.com/minecraft/search?search="+id;
    }
    private static String contentFolder(JsonObject project) throws IOException {
        return switch ((int)number(project,"classId")) { case 6 -> "mods"; case 12 -> "resourcepacks"; case 6552 -> "shaderpacks"; default -> throw new IOException("Unsupported CurseForge content type in modpack: " + number(project,"classId")); };
    }
    static boolean compatible(JsonObject file,String version,String loader) {
        try {
            JsonArray versions=file.getAsJsonArray("gameVersions"); if(versions==null)return false;
            boolean game=false,kind=loader==null;
            for(JsonElement value:versions){String v=value.getAsString();if(v.equals(version))game=true;if(loader!=null && v.equalsIgnoreCase(loader))kind=true;}
            return game && kind;
        } catch(Exception ignored){return false;}
    }
    static void requireCompatible(JsonObject file,String version,String loader) throws IOException {
        if(!compatible(file,version,loader))throw new IOException("File “"+string(file,"displayName")+"” does not explicitly support Minecraft "+version+(loader==null?"":" / "+loader)+".");
    }
    private static void requireModLoader(String loader) throws IOException {
        if(!List.of("Fabric","Forge","NeoForge").contains(loader))throw new IOException("Select a Fabric, Forge or NeoForge profile for mods. Vanilla and Quilt are not supported by this add-on.");
    }
    private static String fileName(JsonObject file) throws IOException {
        String filename=string(file,"fileName");safeRelative(filename);
        if(filename.contains("/") || filename.length()>200)throw new IOException("Unsafe CurseForge filename.");
        return filename;
    }
    private record Download(JsonObject project,JsonObject file,Path path) {}
    private record Existing(Path directory,Path game,JsonObject profile,byte[] original) {}
    private record Loader(String kind,String version) {}

    private Existing readProfile(String id) throws Exception {
        if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,159}"))throw new IOException("Invalid Dawn profile ID.");
        Path directory=home.resolve("profiles").resolve(id);ensureNoLinks(directory);
        Path metadata=directory.resolve("profile.json");ensureNoLinks(metadata);
        if(!Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS) || Files.size(metadata)>262144)throw new IOException("Invalid Dawn profile metadata.");
        byte[] bytes=Files.readAllBytes(metadata);JsonObject envelope=parseObject(bytes);
        if(number(envelope,"schemaVersion")!=4)throw new IOException("Unsupported Dawn profile schema.");
        JsonObject profile=envelope.getAsJsonObject("profile");
        if(!id.equals(string(profile,"id")))throw new IOException("Dawn profile ID does not match its directory.");
        checkedVersion(string(profile,"minecraftVersion"));
        Path game=directory.resolve(".minecraft");ensureNoLinks(game);
        return new Existing(directory,game,profile,bytes);
    }
    private static Loader packLoader(JsonArray loaders) throws IOException {
        if(loaders==null || loaders.isEmpty())throw new IOException("Modpack has no declared primary mod loader.");
        JsonObject primary=null;
        for(JsonElement value:loaders){JsonObject loader=value.getAsJsonObject();if(loader.has("primary") && loader.get("primary").getAsBoolean()){if(primary!=null)throw new IOException("Modpack has multiple primary loaders.");primary=loader;}}
        if(primary==null)throw new IOException("Modpack has no primary loader.");
        String id=string(primary,"id");
        for(String kind:List.of("neoforge","forge","fabric"))if(id.startsWith(kind+"-"))return new Loader(kind.equals("neoforge")?"NeoForge":kind.equals("forge")?"Forge":"Fabric",checkedVersion(id.substring(kind.length()+1)));
        throw new IOException("Unsupported modpack loader: "+id+". Quilt is not supported.");
    }
    private static JsonObject newProfile(String id,String name,String version,Loader loader) {
        JsonObject envelope=new JsonObject(),profile=new JsonObject(),kind=new JsonObject();
        envelope.addProperty("schemaVersion",4);envelope.add("profile",profile);
        profile.addProperty("id",id);profile.addProperty("kind","Custom");profile.addProperty("name",name);profile.addProperty("minecraftVersion",version);
        kind.addProperty("kind",loader.kind);kind.addProperty("version",loader.version);profile.add("loader",kind);
        JsonObject settings=JsonParser.parseString("{\"memoryDefaults\":{\"minimumMiB\":2048,\"recommendedMiB\":4096,\"maximumMiB\":8192},\"jvmOptions\":{\"extraArguments\":[]},\"javaOverrides\":{\"enabled\":false,\"javaInstallationPath\":{\"enabled\":false},\"memoryAllocationMiB\":{\"enabled\":false},\"javaArguments\":{\"enabled\":false},\"environmentVariables\":{\"enabled\":false},\"prelaunchHook\":{\"enabled\":false},\"wrapperHook\":{\"enabled\":false},\"postExitHook\":{\"enabled\":false}},\"tags\":[],\"autoSyncMembership\":{\"kind\":\"None\"}}").getAsJsonObject();
        profile.add("settings",settings);profile.add("runtimePolicy",JsonParser.parseString("{\"modAuthority\":\"UserManaged\",\"requiredHooks\":[]}"));return envelope;
    }

    static Path safeRelative(String name) throws IOException {
        if(name==null || name.isBlank() || name.indexOf('\\')>=0 || name.startsWith("/") || name.matches(".*[\\x00-\\x1f:].*"))throw new IOException("Unsafe ZIP path: "+name);
        String normalized=name.endsWith("/")?name.substring(0,name.length()-1):name;
        for(String part:normalized.split("/",-1))if(part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ") || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))throw new IOException("Unsafe ZIP path: "+name);
        Path result=Path.of(normalized);if(result.isAbsolute())throw new IOException("Absolute archive paths are not allowed.");return result;
    }
    static void validateZip(Path archive) throws IOException {
        if(Files.size(archive)>MAX_ARCHIVE)throw new IOException("Modpack ZIP exceeds 512 MiB.");
        validateCentralDirectory(archive);
        long total=0;int count=0;Set<String> names=new HashSet<>();
        try(ZipFile zip=new ZipFile(archive.toFile())){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                ZipEntry entry=entries.nextElement();safeRelative(entry.getName());
                if(++count>10000 || !names.add(entry.getName().replaceAll("/$","").toLowerCase(Locale.ROOT)))throw new IOException("ZIP contains too many entries or duplicate paths.");
                long size=entry.getSize();if(size<0 || size>MAX_ENTRY || (total+=size)>MAX_EXPANDED)throw new IOException("ZIP expands beyond safe installation limits.");
                if(size>1048576 && size/Math.max(1,entry.getCompressedSize())>1000)throw new IOException("Suspicious ZIP compression ratio.");
            }
        }
    }
    private static void validateCentralDirectory(Path archive) throws IOException {
        try(RandomAccessFile file=new RandomAccessFile(archive.toFile(),"r")){
            long length=file.length();int tailSize=(int)Math.min(length,65557);byte[] tail=new byte[tailSize];file.seek(length-tailSize);file.readFully(tail);
            int end=-1;for(int i=tailSize-22;i>=0;i--)if(u32(tail,i)==0x06054b50L && i+22+u16(tail,i+20)==tailSize){end=i;break;}
            if(end<0)throw new IOException("ZIP end record is missing.");
            int count=u16(tail,end+10);long start=u32(tail,end+16),size=u32(tail,end+12);
            if(u16(tail,end+4)!=0 || u16(tail,end+6)!=0 || count==65535 || start==0xffffffffL || size==0xffffffffL || count>10000 || start+size>length-tailSize+end)throw new IOException("Multi-volume, ZIP64 or invalid ZIP archives are unsupported.");
            file.seek(start);
            for(int i=0;i<count;i++){
                byte[] header=new byte[46];file.readFully(header);if(u32(header,0)!=0x02014b50L)throw new IOException("Invalid ZIP central directory.");
                long attributes=u32(header,38);int type=(int)(attributes>>>16)&0170000;
                if((u16(header,8)&1)!=0 || type==0120000 || (attributes&0x400)!=0)throw new IOException("Encrypted entries, symbolic links and reparse points are unsupported.");
                long skip=u16(header,28)+u16(header,30)+u16(header,32);file.seek(file.getFilePointer()+skip);
                if(file.getFilePointer()>start+size)throw new IOException("ZIP central directory exceeds its declared size.");
            }
        }
    }
    private static int u16(byte[] bytes,int p){return (bytes[p]&255)|((bytes[p+1]&255)<<8);}
    private static long u32(byte[] bytes,int p){return Integer.toUnsignedLong((bytes[p]&255)|((bytes[p+1]&255)<<8)|((bytes[p+2]&255)<<16)|((bytes[p+3]&255)<<24));}
    private static byte[] readEntry(ZipFile zip,ZipEntry entry,long maximum) throws IOException {
        if(entry.getSize()<0 || entry.getSize()>maximum)throw new IOException("Archive metadata is too large.");
        try(InputStream input=zip.getInputStream(entry);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];long total=0;int count;while((count=input.read(buffer))!=-1){if((total+=count)>maximum)throw new IOException("ZIP entry exceeded safe size.");output.write(buffer,0,count);}return output.toByteArray();
        }
    }
    private static void extractOverrides(Path archive,String overrides,Path game) throws IOException {
        String prefix=overrides.replaceAll("/$","")+"/";long total=0;
        try(ZipFile zip=new ZipFile(archive.toFile())){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                ZipEntry entry=entries.nextElement();if(!entry.getName().startsWith(prefix) || entry.getName().equals(prefix))continue;
                Path relative=safeRelative(entry.getName().substring(prefix.length())),target=game.resolve(relative).normalize();
                if(!target.startsWith(game))throw new IOException("Override escaped game folder.");
                if(entry.isDirectory()){Files.createDirectories(target);continue;}
                if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Modpack override conflicts with downloaded file: "+relative);
                Files.createDirectories(target.getParent());
                try(InputStream input=zip.getInputStream(entry);OutputStream output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){
                    byte[] buffer=new byte[65536];long written=0;int count;
                    while((count=input.read(buffer))!=-1){if((written+=count)>MAX_ENTRY || (total+=count)>MAX_EXPANDED)throw new IOException("Modpack overrides exceed safe size.");output.write(buffer,0,count);}
                    if(written!=entry.getSize())throw new IOException("Modpack override size mismatch.");
                }
            }
        }
    }
    private static void checkModIds(Path mods,List<Path> additions) throws Exception {
        Map<String,Path> owners=new HashMap<>();List<Path> files=new ArrayList<>();
        if(Files.exists(mods,LinkOption.NOFOLLOW_LINKS)){
            ensureNoLinks(mods);try(Stream<Path> list=Files.list(mods)){files.addAll(list.filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).limit(2001).toList());}
        }
        files.addAll(additions);if(files.size()>2000)throw new IOException("Too many mods to safely check compatibility.");
        for(Path jar:files){ensureNoLinks(jar);for(String id:modIds(jar)){Path previous=owners.putIfAbsent(id,jar);if(previous!=null && !previous.equals(jar))throw new IOException("Duplicate mod ID “"+id+"” in "+previous.getFileName()+" and "+jar.getFileName()+". Existing mods will not be replaced.");}}
    }
    private static Set<String> modIds(Path jar) throws IOException {
        Set<String> result=new HashSet<>();
        try(ZipFile zip=new ZipFile(jar.toFile())){
            ZipEntry fabric=zip.getEntry("fabric.mod.json");
            if(fabric!=null){JsonObject value=parseObject(readEntry(zip,fabric,1L<<20));result.add(string(value,"id"));if(value.has("provides"))for(JsonElement id:value.getAsJsonArray("provides"))result.add(id.getAsString());}
            for(String descriptor:List.of("META-INF/mods.toml","META-INF/neoforge.mods.toml")){
                ZipEntry entry=zip.getEntry(descriptor);if(entry==null)continue;
                String text=new String(readEntry(zip,entry,1L<<20),StandardCharsets.UTF_8);
                Matcher sections=Pattern.compile("(?ms)^\\s*\\[\\[mods\\]\\]\\s*(.*?)(?=^\\s*\\[|\\z)").matcher(text);
                while(sections.find()){Matcher id=Pattern.compile("(?m)^\\s*modId\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(sections.group(1));if(id.find())result.add(id.group(1));}
            }
        } catch(ZipException error){throw new IOException("Invalid mod JAR: "+jar.getFileName(),error);}
        return result;
    }
    static List<String> rollback(List<Path> moved,Map<Path,byte[]> expected) {
        List<String> retained=new ArrayList<>();List<Path> reversed=new ArrayList<>(moved);Collections.reverse(reversed);
        for(Path path:reversed)try{if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && MessageDigest.isEqual(expected.get(path),digest(path)))Files.delete(path);else if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))retained.add(path.getFileName().toString());}catch(Exception error){retained.add(path.getFileName().toString());}
        return retained;
    }
    private static byte[] digest(Path file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(file)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}return digest.digest();
    }
    private static void assertStopped(Path game) throws IOException {
        String target=game.toAbsolutePath().normalize().toString().replace('\\','/').toLowerCase(Locale.ROOT);
        boolean windows=System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows");
        long ownPid=ProcessHandle.current().pid();
        try(Stream<ProcessHandle> processes=ProcessHandle.allProcesses()){
            boolean running=processes.filter(process->process.pid()!=ownPid).anyMatch(process->{
                ProcessHandle.Info info=process.info();
                Optional<String> commandLine=info.commandLine();
                if(commandLine.isPresent()){
                    String normalized=commandLine.get().replace('\\','/').toLowerCase(Locale.ROOT);
                    return normalized.contains(target) && (normalized.contains("java") || normalized.contains("minecraft"));
                }
                // Java's Windows implementation often omits commandLine/arguments entirely.
                // Refuse an unidentified Java game rather than guessing it uses another profile.
                if(windows)return info.command().map(command->{
                    String executable=Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
                    return executable.equals("java.exe") || executable.equals("javaw.exe") || executable.equals("minecraft.exe");
                }).orElse(false);
                return false;
            });
            if(running)throw new IOException("Close Minecraft before installing mods. Another Java process may be using this profile; Windows does not always expose its game directory.");
        }
    }
    private static void ensureNoLinks(Path path) throws IOException {
        Path current=path.toAbsolutePath().normalize();
        while(current!=null){
            if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes=Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(attributes.isSymbolicLink() || attributes.isOther())throw new IOException("Symbolic-link and junction installation paths are unsupported.");
            }
            current=current.getParent();
        }
    }
    private static void deleteStage(Path stage,Path stagingRoot) {
        if(!stage.toAbsolutePath().normalize().getParent().equals(stagingRoot.toAbsolutePath().normalize()) || !stage.getFileName().toString().startsWith("install-"))return;
        try(Stream<Path> paths=Files.walk(stage)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}catch(IOException ignored){/* Only temporary staging files can remain after cleanup failure. */}
    }
    private static JsonObject parseObject(byte[] bytes) throws IOException {
        try{return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException error){throw new IOException("Invalid JSON metadata.",error);}
    }
    private static String checkedVersion(String value) throws IOException {
        if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,79}") || value.contains(".."))throw new IOException("Invalid Minecraft or loader version.");return value;
    }
    private static String string(JsonObject object,String key) throws IOException {
        try{String value=object.get(key).getAsString();if(value.length()>4096)throw new IOException("Oversized metadata value.");return value;}catch(RuntimeException error){throw new IOException("Missing or invalid metadata: "+key,error);}
    }
    private static long number(JsonObject object,String key) throws IOException {
        try{return object.get(key).getAsLong();}catch(RuntimeException error){throw new IOException("Missing or invalid metadata: "+key,error);}
    }
}
