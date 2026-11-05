package local.campfire;
import org.bukkit.plugin.java.JavaPlugin;
public class RuntimeTestHost extends JavaPlugin {
    public static RuntimeTestHost load(){
        try {
            String yaml="name: CampfireCoop\nversion: 1.7.0\nmain: local.campfire.RuntimeTestHost\napi-version: '1.21.11'\ncommands:\n  camp:\n    aliases: [coop]\npermissions:\n  campfire.admin:\n    default: op\n";
            var description=new org.bukkit.plugin.PluginDescriptionFile(new java.io.StringReader(yaml));
            return org.mockbukkit.mockbukkit.MockBukkit.loadWith(RuntimeTestHost.class,description);
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private CampfireRuntime runtime;
    public CampfireRuntime runtime(){return runtime;}
    @Override public void onEnable(){
        if(runtime==null)runtime=new CampfireRuntime(this);
        runtime.onEnable();getCommand("camp").setExecutor(runtime);getCommand("camp").setTabCompleter(runtime);
    }
    @Override public void onDisable(){if(runtime!=null)runtime.onDisable();}
}
