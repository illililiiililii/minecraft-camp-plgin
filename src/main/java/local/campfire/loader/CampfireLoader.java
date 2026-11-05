package local.campfire.loader;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.jar.JarFile;

/** Stable Bukkit host. Feature code and all its listeners/tasks live in one replaceable child loader. */
public class CampfireLoader extends JavaPlugin {
    private Object runtime;
    private ModuleLoader moduleLoader;
    private String version="not loaded",hash="";
    private boolean swapping;
    private Path root,current,updates;
    private static final List<String> DATA=List.of("config.yml","state.yml","graves.yml","supplies.yml","fun.yml");
    static final class ModuleLoader extends URLClassLoader {
        ModuleLoader(Path jar,ClassLoader parent)throws Exception{super(new URL[]{jar.toUri().toURL()},parent);}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
            if(name.startsWith("local.campfire.")&&!name.startsWith("local.campfire.loader."))synchronized(getClassLoadingLock(name)){
                Class<?> c=findLoadedClass(name);if(c==null)c=findClass(name);if(resolve)resolveClass(c);return c;
            }
            return super.loadClass(name,resolve);
        }
        @Override public URL getResource(String name){URL local=findResource(name);return local==null?super.getResource(name):local;}
    }
    record Candidate(Object runtime,ModuleLoader loader,String version,String hash,Path cached) { }
    @Override public void onEnable(){
        root=getDataFolder().toPath();current=root.resolve("runtime.jar");updates=root.resolve("updates");
        getCommand("camp").setExecutor(this);getCommand("camp").setTabCompleter(this);
        try{Files.createDirectories(updates);activateInitial();watch();}
        catch(Exception ex){getLogger().log(java.util.logging.Level.SEVERE,"Campfire runtime could not start. Keep your data folder; see the error below.",ex);Bukkit.getPluginManager().disablePlugin(this);}
    }
    private void watch(){
        if(!isEnabled())return;
        Bukkit.getScheduler().runTaskTimer(this,()->{
            if(swapping)return;
            Path jar=updates.resolve("update.jar"),checksum=updates.resolve("update.sha256");
            if(!Files.isRegularFile(jar)||!Files.isRegularFile(checksum))return;
            try{
                String expected=Files.readString(checksum,StandardCharsets.US_ASCII).trim();
                if(!expected.matches("[a-fA-F0-9]{64}")||!expected.equalsIgnoreCase(sha(jar)))return;
                if(expected.equalsIgnoreCase(hash)){Files.deleteIfExists(checksum);Files.deleteIfExists(jar);return;}
                apply(jar);
            }catch(Exception ex){getLogger().log(java.util.logging.Level.SEVERE,"Hot update rejected; previous runtime retained.",ex);result("rejected",ex.toString());try{Files.move(checksum,updates.resolve("rejected-"+System.currentTimeMillis()+".sha256"));}catch(Exception ignored){}}
        },100,100);
    }
    static String sha(Path file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))>0)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest());
    }
    private Candidate prepare(Path jar)throws Exception{
        String digest=sha(jar),nextVersion;
        try(JarFile zip=new JarFile(jar.toFile())){
            if(zip.getJarEntry("local/campfire/CampfireRuntime.class")==null||zip.getJarEntry("module.properties")==null)throw new IOException("Not a Campfire runtime module");
            Properties properties=new Properties();try(InputStream in=zip.getInputStream(zip.getJarEntry("module.properties"))){properties.load(in);}
            if(!"1".equals(properties.getProperty("loader-api")))throw new IOException("Incompatible loader API");
            nextVersion=properties.getProperty("version","");if(!nextVersion.matches("[A-Za-z0-9._-]{1,64}"))throw new IOException("Invalid runtime version");
        }
        Path cache=root.resolve("runtime-cache");Files.createDirectories(cache);
        Path cached=cache.resolve(digest+"-"+System.nanoTime()+".jar");Files.copy(jar,cached);
        ModuleLoader child=new ModuleLoader(cached,getClass().getClassLoader());
        try{
            Object next=child.loadClass("local.campfire.CampfireRuntime").getConstructor(JavaPlugin.class).newInstance(this);
            if(!(next instanceof TabExecutor))throw new IOException("Runtime does not implement Bukkit command API");
            return new Candidate(next,child,nextVersion,digest,cached);
        }catch(Exception ex){child.close();throw ex;}
    }
    private void start(Candidate candidate)throws Exception{
        runtime=candidate.runtime;moduleLoader=candidate.loader;version=candidate.version;hash=candidate.hash;
        runtime.getClass().getMethod("onEnable").invoke(runtime);
        if(!isEnabled())throw new IOException("Runtime disabled its plugin host");
        getLogger().info("CAMPFIRE RUNTIME ACTIVE version="+version+" sha256="+hash);
        result("active","Runtime enabled");
    }
    private void activateInitial()throws Exception{start(prepare(current));}
    private void stopRuntime(){
        // Unregister first so closing a menu cannot schedule an old callback during a hot swap.
        HandlerList.unregisterAll(this);Bukkit.getScheduler().cancelTasks(this);
        if(runtime!=null)try{runtime.getClass().getMethod("onDisable").invoke(runtime);}catch(Exception ex){getLogger().log(java.util.logging.Level.WARNING,"Runtime cleanup error",ex);}
        Bukkit.getScheduler().cancelTasks(this);runtime=null;
        if(moduleLoader!=null)try{moduleLoader.close();}catch(Exception ex){getLogger().warning(ex.toString());}
        moduleLoader=null;
    }
    private Path snapshot()throws Exception{
        Path backup=updates.resolve("backups").resolve(System.currentTimeMillis()+"-"+UUID.randomUUID());Files.createDirectories(backup);
        for(String file:DATA)if(Files.isRegularFile(root.resolve(file)))Files.copy(root.resolve(file),backup.resolve(file));
        return backup;
    }
    private void restore(Path backup)throws Exception{
        for(String file:DATA){Path saved=backup.resolve(file);if(Files.isRegularFile(saved))Files.copy(saved,root.resolve(file),StandardCopyOption.REPLACE_EXISTING);else Files.deleteIfExists(root.resolve(file));}
    }
    private void apply(Path source)throws Exception{
        if(swapping)throw new IOException("Another update is active");
        Candidate candidate=prepare(source); // Validate before touching a running module.
        Path previous=updates.resolve("previous-runtime.jar");Files.copy(current,previous,StandardCopyOption.REPLACE_EXISTING);
        swapping=true;Path backup=null;
        try{
            Bukkit.broadcast(net.kyori.adventure.text.Component.text("[모닥불] 기능 패치 적용 중입니다. 접속은 유지되며 진행 중 원정은 종료됩니다."));
            backup=snapshot();stopRuntime();
            try{
                start(candidate);
                Files.copy(source,current.resolveSibling("runtime.jar.tmp"),StandardCopyOption.REPLACE_EXISTING);
                Files.move(current.resolveSibling("runtime.jar.tmp"),current,StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(updates.resolve("update.sha256"));
                if(source.equals(updates.resolve("update.jar")))Files.deleteIfExists(source);
                Bukkit.broadcast(net.kyori.adventure.text.Component.text("[모닥불] 무중단 패치 완료 · "+version));
                result("updated","Hot swap completed without a server restart");
            }catch(Exception ex){
                stopRuntime();if(backup!=null)restore(backup);
                Files.copy(previous,current,StandardCopyOption.REPLACE_EXISTING);
                start(prepare(current));result("rolled-back",ex.toString());
                throw ex;
            }
        }finally{swapping=false;watch();}
    }
    private void result(String status,String message){
        if(updates==null)return;
        try{
            Properties p=new Properties();p.setProperty("status",status);p.setProperty("version",version);p.setProperty("sha256",hash);p.setProperty("time",Instant.now().toString());p.setProperty("message",message);
            try(Writer out=Files.newBufferedWriter(updates.resolve("result.properties"),StandardCharsets.UTF_8)){p.store(out,"Campfire runtime update status");}
        }catch(Exception ex){getLogger().warning("Could not write update status: "+ex);}
    }
    @Override public void onDisable(){stopRuntime();}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length>0&&Set.of("update","reload","version").contains(args[0].toLowerCase(Locale.ROOT))){
            if(args[0].equalsIgnoreCase("version")){sender.sendMessage("Campfire runtime "+version);return true;}
            if(!sender.hasPermission("campfire.admin")){sender.sendMessage("운영자만 패치를 적용할 수 있습니다.");return true;}
            if(swapping){sender.sendMessage("이미 패치 중입니다.");return true;}
            // Change module/menus only after the current command stack has returned.
            Bukkit.getScheduler().runTask(this,()->{
                try{
                    if(args[0].equalsIgnoreCase("reload"))apply(current);
                    else{
                        Path jar=updates.resolve("update.jar"),check=updates.resolve("update.sha256");
                        if(!Files.isRegularFile(jar)||!Files.isRegularFile(check)||!sha(jar).equalsIgnoreCase(Files.readString(check).trim())){sender.sendMessage("준비된 업데이트가 없습니다. update.jar와 SHA-256 파일을 확인하세요.");return;}
                        apply(jar);
                    }
                    sender.sendMessage("적용 완료: "+version);
                }catch(Exception ex){getLogger().log(java.util.logging.Level.SEVERE,"Patch failed",ex);sender.sendMessage("패치 실패. 이전 모듈과 콘솔을 확인하세요.");}
            });return true;
        }
        if(runtime==null||swapping){sender.sendMessage("모닥불 기능을 준비 중입니다.");return true;}
        return ((TabExecutor)runtime).onCommand(sender,command,label,args);
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        List<String> options=new ArrayList<>();
        if(runtime instanceof TabExecutor executor){List<String> result=executor.onTabComplete(sender,command,alias,args);if(result!=null)options.addAll(result);}
        if(args.length==1){for(String name:sender.hasPermission("campfire.admin")?List.of("update","reload","version"):List.of("version"))if(name.startsWith(args[0].toLowerCase(Locale.ROOT)))options.add(name);}
        return options;
    }
}
