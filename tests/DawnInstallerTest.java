package local.dawn.curseforge;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Offline fixtures only. Never accesses installed Dawn profiles or the network. */
public final class DawnInstallerTest {
    private static int assertions;
    private interface Action { void run() throws Exception; }
    private static void check(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
    private static void fails(Action action,String expected)throws Exception{
        try{action.run();throw new AssertionError("Expected failure: "+expected);}catch(IOException error){check(error.getMessage().toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)),"Unexpected error: "+error.getMessage());}
    }
    private static byte[] zip(Map<String,byte[]> files)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream output=new ZipOutputStream(bytes)){for(var file:files.entrySet()){output.putNextEntry(new ZipEntry(file.getKey()));output.write(file.getValue());output.closeEntry();}}return bytes.toByteArray();
    }
    private static byte[] jar(String id)throws Exception{return zip(Map.of("fabric.mod.json",("{\"schemaVersion\":1,\"id\":\""+id+"\",\"version\":\"1.0\"}").getBytes(StandardCharsets.UTF_8)));}
    private static JsonObject metadata(long project,long id,String filename,byte[] bytes){
        JsonObject value=new JsonObject();value.addProperty("id",id);value.addProperty("modId",project);value.addProperty("fileName",filename);value.addProperty("displayName",filename);value.addProperty("fileLength",bytes.length);value.addProperty("isAvailable",true);
        value.add("gameVersions",JsonParser.parseString("[\"1.21.1\",\"Fabric\"]"));value.add("dependencies",new JsonArray());return value;
    }
    private static final class Api extends CurseForgeApi {
        final Map<Long,JsonObject> projects=new HashMap<>(),files=new HashMap<>();final Map<Long,byte[]> bytes=new HashMap<>();long failId=-1;
        void add(long project,long id,String filename,byte[] content,int kind){
            JsonObject p=new JsonObject();p.addProperty("id",project);p.addProperty("name","Project "+project);p.addProperty("classId",kind);p.addProperty("allowModDistribution",true);projects.put(project,p);files.put(id,metadata(project,id,filename,content));bytes.put(id,content);
        }
        @Override public JsonObject project(long id)throws IOException{if(!projects.containsKey(id))throw new IOException("Unknown fixture project");return projects.get(id).deepCopy();}
        @Override public JsonObject file(long project,long id)throws IOException{if(!files.containsKey(id))throw new IOException("Unknown fixture file");return files.get(id).deepCopy();}
        @Override public JsonArray files(long project,String version,String loader){JsonArray list=new JsonArray();for(JsonObject file:files.values())if(file.get("modId").getAsLong()==project)list.add(file.deepCopy());return list;}
        @Override public Path download(JsonObject file,Path target)throws IOException{long id=file.get("id").getAsLong();if(id==failId)throw new IOException("Fixture download failed");Files.write(target,bytes.get(id),StandardOpenOption.CREATE_NEW);return target;}
        void dependency(long file,long project){JsonObject d=new JsonObject();d.addProperty("modId",project);d.addProperty("relationType",3);files.get(file).getAsJsonArray("dependencies").add(d);}
    }
    private static CatalogPanel.Profile profile(Path home)throws Exception{
        Path dir=home.resolve("profiles/fixture");Files.createDirectories(dir.resolve(".minecraft/mods"));
        Files.writeString(dir.resolve("profile.json"),"{\"schemaVersion\":4,\"profile\":{\"id\":\"fixture\",\"name\":\"Fixture\",\"minecraftVersion\":\"1.21.1\",\"loader\":{\"kind\":\"Fabric\",\"version\":\"0.16.0\"}}}");
        return new CatalogPanel.Profile("fixture","Fixture","1.21.1","Fabric");
    }
    private static CatalogPanel.Item item(long id,boolean pack){return new CatalogPanel.Item(id,"Fixture","Offline fixture",pack);}
    private static CatalogPanel.FileItem file(long id){return new CatalogPanel.FileItem(id,"Fixture file");}
    private static Path fixture(Path root,String name)throws Exception{Path dir=root.resolve(name);Files.createDirectories(dir);return dir;}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("dawn-installer-fixture-");
        try{paths(root);compatibility();modInstall(root);failedDownload(root);cycle(root);packInstall(root);packTraversal(root);rollback(root);runningProfile(root);}
        finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("DawnInstallerTest: "+assertions+" assertions passed; offline fixtures only.");
    }
    private static void paths(Path root)throws Exception{
        for(String name:List.of("../outside","/absolute","a/../../evil","a\\evil","x:ads","CON.jar","a/NUL.txt","a/../b","trailing. "))fails(()->DawnInstaller.safeRelative(name),"unsafe");
        check(DawnInstaller.safeRelative("config/example.json").equals(Path.of("config/example.json")),"Safe path rejected");
        Path duplicate=root.resolve("duplicate.zip");Files.write(duplicate,zip(Map.of("overrides/A.txt",new byte[]{1},"overrides/a.txt",new byte[]{2})));fails(()->DawnInstaller.validateZip(duplicate),"duplicate");
        Path symlink=root.resolve("link.zip");byte[] bytes=zip(Map.of("overrides/link",new byte[]{1}));
        for(int p=0;p<bytes.length-46;p++)if(bytes[p]==0x50 && bytes[p+1]==0x4b && bytes[p+2]==1 && bytes[p+3]==2){int attributes=0120777<<16;for(int n=0;n<4;n++)bytes[p+38+n]=(byte)(attributes>>>(8*n));break;}
        Files.write(symlink,bytes);fails(()->DawnInstaller.validateZip(symlink),"symbolic");
    }
    private static void compatibility()throws Exception{
        JsonObject metadata=metadata(1,10,"fixture.jar",new byte[0]);
        check(DawnInstaller.compatible(metadata,"1.21.1","Fabric"),"Matching loader rejected");
        check(!DawnInstaller.compatible(metadata,"1.21.1","Forge"),"Wrong loader accepted");
        check(!DawnInstaller.compatible(metadata,"1.20.1","Fabric"),"Wrong version accepted");
    }
    private static void modInstall(Path root)throws Exception{
        Path home=fixture(root,"mods");CatalogPanel.Profile profile=profile(home);Api api=new Api();api.add(1,10,"one.jar",jar("one"),6);api.add(2,20,"two.jar",jar("two"),6);api.dependency(10,2);
        DawnInstaller installer=new DawnInstaller(home,api);check(installer.profiles().size()==1,"Profile discovery failed");
        installer.install(item(1,false),file(10),profile,null);
        Path mods=home.resolve("profiles/fixture/.minecraft/mods");check(Files.exists(mods.resolve("one.jar")),"Root mod missing");check(Files.exists(mods.resolve("two.jar")),"Required dependency missing");
        byte[] existing=Files.readAllBytes(mods.resolve("one.jar"));String repeat=installer.install(item(1,false),file(10),profile,null);check(repeat.contains("reused 2"),"Identical dependency reuse missing");check(Arrays.equals(existing,Files.readAllBytes(mods.resolve("one.jar"))),"Existing mod modified");
        api.add(4,40,"four.jar",jar("four"),6);api.dependency(40,2);String second=installer.install(item(4,false),file(40),profile,null);check(second.contains("reused 1"),"Shared dependency was not reused");check(Files.exists(mods.resolve("four.jar")),"Second mod missing");
        api.bytes.put(10L,jar("different"));fails(()->installer.install(item(1,false),file(10),profile,null),"existing");check(Arrays.equals(existing,Files.readAllBytes(mods.resolve("one.jar"))),"Conflicting install modified existing file");
        api.add(3,30,"different-name.jar",jar("one"),6);fails(()->installer.install(item(3,false),file(30),profile,null),"Duplicate mod ID");
    }
    private static void failedDownload(Path root)throws Exception{
        Path home=fixture(root,"failed");CatalogPanel.Profile profile=profile(home);Api api=new Api();api.add(1,10,"one.jar",jar("one"),6);api.add(2,20,"two.jar",jar("two"),6);api.dependency(10,2);api.failId=10;
        fails(()->new DawnInstaller(home,api).install(item(1,false),file(10),profile,null),"download failed");
        try(var files=Files.list(home.resolve("profiles/fixture/.minecraft/mods"))){check(files.count()==0,"Partial dependency published after failure");}
    }
    private static void cycle(Path root)throws Exception{
        Path home=fixture(root,"cycle");CatalogPanel.Profile profile=profile(home);Api api=new Api();api.add(1,10,"one.jar",jar("one"),6);api.add(2,20,"two.jar",jar("two"),6);api.dependency(10,2);api.dependency(20,1);
        fails(()->new DawnInstaller(home,api).install(item(1,false),file(10),profile,null),"cycle");
    }
    private static byte[] pack(String override)throws Exception{
        String manifest="{\"manifestType\":\"minecraftModpack\",\"manifestVersion\":1,\"name\":\"Fixture Pack\",\"minecraft\":{\"version\":\"1.21.1\",\"modLoaders\":[{\"id\":\"fabric-0.16.0\",\"primary\":true}]},\"files\":[{\"projectID\":1,\"fileID\":10,\"required\":true},{\"projectID\":999,\"fileID\":9999,\"required\":false}],\"overrides\":\"overrides\"}";
        return zip(Map.of("manifest.json",manifest.getBytes(StandardCharsets.UTF_8),override,"fixture".getBytes(StandardCharsets.UTF_8)));
    }
    private static void packInstall(Path root)throws Exception{
        Path home=fixture(root,"pack");Api api=new Api();api.add(1,10,"one.jar",jar("one"),6);api.add(100,1000,"pack.zip",pack("overrides/config/test.txt"),4471);
        String result=new DawnInstaller(home,api).install(item(100,true),file(1000),null,null);check(result.contains("skipped 1 optional"),"Optional summary missing");
        Path installed;try(var paths=Files.list(home.resolve("profiles"))){installed=paths.findFirst().orElseThrow();}
        JsonObject wrapper=JsonParser.parseString(Files.readString(installed.resolve("profile.json"))).getAsJsonObject();
        JsonObject p=wrapper.getAsJsonObject("profile");check(wrapper.get("schemaVersion").getAsInt()==4,"Dawn schema mismatch");check(p.get("id").getAsString().startsWith("cf-"),"Profile ID mismatch");check(p.getAsJsonObject("runtimePolicy").get("modAuthority").getAsString().equals("UserManaged"),"Dawn core hooks injected");check(p.getAsJsonObject("runtimePolicy").getAsJsonArray("requiredHooks").isEmpty(),"Hooks not empty");
        check(Files.exists(installed.resolve(".minecraft/mods/one.jar")),"Pack mod missing");check(Files.readString(installed.resolve(".minecraft/config/test.txt")).equals("fixture"),"Overrides missing");
    }
    private static void packTraversal(Path root)throws Exception{
        Path home=fixture(root,"pack-traversal");Api api=new Api();api.add(100,1000,"pack.zip",pack("overrides/../../escape"),4471);
        fails(()->new DawnInstaller(home,api).install(item(100,true),file(1000),null,null),"unsafe");check(!Files.exists(home.resolve("profiles")),"Unsafe pack profile published");
    }
    private static void rollback(Path root)throws Exception{
        Path dir=fixture(root,"rollback"),first=dir.resolve("first.jar"),changed=dir.resolve("changed.jar");Files.writeString(first,"original");Files.writeString(changed,"original");
        byte[] expected=MessageDigest.getInstance("SHA-256").digest("original".getBytes(StandardCharsets.UTF_8));Files.writeString(changed,"external change");
        List<String> retained=DawnInstaller.rollback(List.of(first,changed),Map.of(first,expected,changed,expected));check(!Files.exists(first),"Rollback did not remove own unchanged file");check(Files.readString(changed).equals("external change"),"Rollback erased concurrent user change");check(retained.equals(List.of("changed.jar")),"Retained file not reported");
    }
    private static void runningProfile(Path root)throws Exception{
        // A harmless child command only sleeps; its arguments emulate Minecraft's gameDir.
        Path home=fixture(root,"running");CatalogPanel.Profile profile=profile(home);Api api=new Api();api.add(1,10,"one.jar",jar("one"),6);
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"javaw.exe":"java").toString();
        Process process=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),Sleeper.class.getName(),"--gameDir",home.resolve("profiles/fixture/.minecraft").toString()).start();
        try{Thread.sleep(300);check(process.isAlive(),"Running-profile fixture did not start");check(process.info().command().isPresent(),"Process executable unavailable on host");fails(()->new DawnInstaller(home,api).install(item(1,false),file(10),profile,null),"Close Minecraft");}
        finally{process.destroyForcibly();process.waitFor();}
    }
    public static final class Sleeper { public static void main(String[] args)throws Exception{Thread.sleep(30000);} }
}
