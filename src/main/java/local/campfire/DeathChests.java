package local.campfire;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** The physical chest is an anchor; all contents live in durable, owner-protected storage. */
final class DeathChests implements Listener {
    static final class Grave {
        final UUID id, owner, world;
        final String name;
        final int x, y, z;
        List<ItemStack> items;
        Grave(UUID id, UUID owner, String name, UUID world, int x, int y, int z, List<ItemStack> items) {
            this.id=id; this.owner=owner; this.name=name; this.world=world; this.x=x; this.y=y; this.z=z; this.items=items;
        }
        Location location() { World w=Bukkit.getWorld(world); return w==null ? null : new Location(w,x+.5,y,z+.5); }
        String coordinates() { World w=Bukkit.getWorld(world); return (w==null?world.toString():w.getName())+" "+x+", "+y+", "+z; }
    }
    final class Holder implements InventoryHolder {
        final Grave grave;
        final UUID viewer;
        final int page;
        final Inventory inv;
        Holder(Grave g, Player p, int page) {
            grave=g; viewer=p.getUniqueId(); this.page=Math.clamp(page,0,Math.max(0,(g.items.size()-1)/45));
            inv=Bukkit.createInventory(this,54,CampPresentation.heading("◆ "+g.name+"님의 회수 상자",CampPresentation.GOLD));
            for(int i=0;i<45 && this.page*45+i<g.items.size();i++) inv.setItem(i,g.items.get(this.page*45+i).clone());
            inv.setItem(45,button(Material.ARROW,"이전 페이지"));
            inv.setItem(48,button(Material.PAPER,(this.page+1)+" / "+Math.max(1,(g.items.size()+44)/45)+" 페이지"));
            inv.setItem(49,button(Material.CHEST_MINECART,"담을 수 있는 만큼 모두 회수"));
            inv.setItem(50,button(Material.CLOCK,"남은 아이템은 계속 보관됩니다"));
            inv.setItem(53,button(Material.ARROW,"다음 페이지"));
        }
        @Override public Inventory getInventory() { return inv; }
    }
    private final CampfireRuntime plugin;
    private final Map<UUID,Grave> graves=new LinkedHashMap<>();
    private final Map<String,Grave> anchors=new HashMap<>();
    private final Set<UUID> clicking=new HashSet<>();
    private boolean writable=true;
    DeathChests(CampfireRuntime plugin) throws Exception {
        this.plugin=plugin;
        File file=new File(plugin.getDataFolder(),"graves.yml");
        YamlConfiguration yaml=new YamlConfiguration();
        if(file.exists()) yaml.load(file);
        var section=yaml.getConfigurationSection("graves");
        if(section!=null) for(String id:section.getKeys(false)) {
            String key="graves."+id+".";
            List<ItemStack> items=new ArrayList<>();
            for(Object value:yaml.getList(key+"items",List.of())) {
                if(!(value instanceof ItemStack item) || item.getType().isAir() || item.getAmount()<=0) throw new IOException("Invalid death chest item");
                items.add(item.clone());
            }
            Grave g=new Grave(UUID.fromString(id),UUID.fromString(yaml.getString(key+"owner")),yaml.getString(key+"name","원정대원"),
                UUID.fromString(yaml.getString(key+"world")),yaml.getInt(key+"x"),yaml.getInt(key+"y"),yaml.getInt(key+"z"),items);
            if(items.isEmpty() || anchors.containsKey(key(g.world,g.x,g.y,g.z))) throw new IOException("Invalid or overlapping death chests");
            add(g);
        }
        Bukkit.getPluginManager().registerEvents(this,plugin.owner());
    }
    private static String key(UUID world,int x,int y,int z) { return world+":"+x+":"+y+":"+z; }
    private Grave at(Block block) { return anchors.get(key(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ())); }
    private void add(Grave g) { graves.put(g.id,g); anchors.put(key(g.world,g.x,g.y,g.z),g); }
    private void remove(Grave g) { graves.remove(g.id); anchors.remove(key(g.world,g.x,g.y,g.z)); }
    List<Grave> owned(UUID owner) { return graves.values().stream().filter(g->g.owner.equals(owner)).toList(); }
    private void tell(CommandSender p,String message) { p.sendMessage(CampPresentation.text("◆ 회수 상자  "+message,CampPresentation.GOLD)); }
    private ItemStack button(Material type,String name) {
        ItemStack item=new ItemStack(type); item.editMeta(meta->meta.displayName(CampPresentation.text(name,CampPresentation.TEAL))); return item;
    }
    private boolean save() {
        if(!writable) return false;
        YamlConfiguration yaml=new YamlConfiguration();
        for(Grave g:graves.values()) {
            String k="graves."+g.id+".";
            yaml.set(k+"owner",g.owner.toString()); yaml.set(k+"name",g.name); yaml.set(k+"world",g.world.toString());
            yaml.set(k+"x",g.x); yaml.set(k+"y",g.y); yaml.set(k+"z",g.z); yaml.set(k+"items",g.items);
        }
        Path file=new File(plugin.getDataFolder(),"graves.yml").toPath(), temp=file.resolveSibling("graves.yml.tmp");
        try {
            yaml.save(temp.toFile());
            if(Files.exists(file)) Files.copy(file,file.resolveSibling("graves.yml.bak"),StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ex) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch(Exception ex) {
            writable=false;
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"Death chest storage is read-only after a save failure. Existing data was not overwritten.",ex);
            return false;
        }
    }
    private boolean free(Block b) {
        if(b.getY()<=b.getWorld().getMinHeight() || b.getY()>=b.getWorld().getMaxHeight()-1 || !b.getType().isAir()
            || !b.getRelative(0,1,0).getType().isAir() || !b.getRelative(0,-1,0).getType().isSolid()
            || !b.getWorld().getWorldBorder().isInside(b.getLocation()) || at(b)!=null) return false;
        Material floor=b.getRelative(0,-1,0).getType();
        if(Set.of(Material.MAGMA_BLOCK,Material.CACTUS,Material.CAMPFIRE,Material.SOUL_CAMPFIRE).contains(floor)) return false;
        for(BlockFace face:List.of(BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST))
            if(b.getRelative(face).getType()==Material.CHEST || b.getRelative(face).getType()==Material.TRAPPED_CHEST) return false;
        return true;
    }
    private Block around(Location center) {
        if(center==null || center.getWorld()==null) return null;
        World w=center.getWorld();
        int y=Math.clamp(center.getBlockY(),w.getMinHeight()+1,w.getMaxHeight()-2);
        for(int radius=0;radius<=4;radius++) for(int dy=-2;dy<=4;dy++)
            for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
                if(Math.abs(dx)!=radius && Math.abs(dz)!=radius) continue;
                int by=y+dy; if(by<=w.getMinHeight() || by>=w.getMaxHeight()-1) continue;
                Block b=w.getBlockAt(center.getBlockX()+dx,by,center.getBlockZ()+dz); if(free(b)) return b;
            }
        return null;
    }
    private Block anchor(Player p) {
        Block b=around(p.getLocation()); if(b!=null) return b;
        b=around(p.getRespawnLocation()); if(b!=null) return b;
        for(World world:Bukkit.getWorlds()) if(world.getEnvironment()==World.Environment.NORMAL) {
            b=around(world.getSpawnLocation()); if(b!=null) return b;
        }
        return null;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void death(PlayerDeathEvent event) {
        if(!plugin.getConfig().getBoolean("convenience.death-chest",true) || event.getKeepInventory() || event.getDrops().isEmpty()) return;
        Player p=event.getEntity();
        Block block=anchor(p);
        if(block==null || !writable) { tell(p,"상자를 놓을 안전한 곳이 없거나 저장에 실패했습니다. 이번에는 기본 사망 드롭으로 처리됩니다."); return; }
        List<ItemStack> items=event.getDrops().stream().filter(i->i!=null && !i.getType().isAir() && i.getAmount()>0).map(ItemStack::clone).toList();
        if(items.isEmpty()) return;
        Grave g=new Grave(UUID.randomUUID(),p.getUniqueId(),p.getName(),block.getWorld().getUID(),block.getX(),block.getY(),block.getZ(),new ArrayList<>(items));
        block.setType(Material.CHEST,false);
        add(g);
        if(!save()) { remove(g); block.setType(Material.AIR,false); tell(p,"저장 실패로 기본 드롭을 유지했습니다. 서버 콘솔을 확인하세요."); return; }
        event.getDrops().clear();
        tell(p,"장비와 인벤토리를 한 상자에 보관했습니다: "+g.coordinates());
        tell(p,"우클릭으로 회수 · /camp graves 로 좌표 다시 보기 · 자동 삭제 없음");
    }
    private boolean allowed(Player p,Grave g) { return g.owner.equals(p.getUniqueId()) || p.hasPermission("campfire.admin"); }
    private boolean close(Player p,Grave g) {
        Location l=g.location(); return l!=null && p.getWorld().equals(l.getWorld()) && p.getLocation().distanceSquared(l)<=64;
    }
    void open(Player p,Grave g,int page) {
        if(!graves.containsKey(g.id) || !allowed(p,g) || !close(p,g)) { tell(p,"본인의 회수 상자에서 8블록 안으로 다가가세요."); return; }
        p.openInventory(new Holder(g,p,page).inv);
    }
    void claim(Player p,Grave g) {
        if(!graves.containsKey(g.id) || !allowed(p,g) || !close(p,g)) return;
        Inventory simulated=Bukkit.createInventory(null,36);
        ItemStack[] before=p.getInventory().getStorageContents();
        for(int i=0;i<before.length;i++) if(before[i]!=null) simulated.setItem(i,before[i].clone());
        List<ItemStack> left=new ArrayList<>();
        for(ItemStack item:g.items) left.addAll(simulated.addItem(item.clone()).values());
        List<ItemStack> old=g.items; g.items=left;
        if(left.isEmpty()) remove(g);
        if(!save()) { g.items=old; add(g); tell(p,"저장에 실패해 회수를 중단했습니다. 아이템은 계속 보관됩니다."); return; }
        p.getInventory().setStorageContents(simulated.getContents()); p.saveData();
        if(left.isEmpty()) {
            Block b=g.location().getBlock();
            if(b.getState() instanceof Chest chest && chest.getBlockInventory().isEmpty()) b.setType(Material.AIR,false);
            tell(p,"모든 아이템을 회수했습니다. 회수 상자를 정리했습니다.");
        } else tell(p,"남은 "+left.size()+"칸은 상자에 보관 중입니다. 인벤토리를 비우고 다시 받으세요.");
    }
    void command(Player p,String[] args) {
        if(args.length>1) {
            try { Grave g=graves.get(UUID.fromString(args[1])); if(g!=null) { open(p,g,0); return; } } catch(IllegalArgumentException ignored) { }
        }
        List<Grave> mine=owned(p.getUniqueId());
        if(mine.isEmpty()) { tell(p,"미회수 상자가 없습니다."); return; }
        for(Grave g:mine) p.sendMessage(CampPresentation.text("◆ "+g.coordinates()+"  [가까이 가서 열기]",CampPresentation.TEAL)
            .clickEvent(ClickEvent.runCommand("/camp grave "+g.id)));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interact(PlayerInteractEvent e) {
        if(e.getClickedBlock()==null || e.getAction()!=Action.RIGHT_CLICK_BLOCK) return;
        Grave g=at(e.getClickedBlock()); if(g==null) return; e.setCancelled(true);
        if(e.getHand()==EquipmentSlot.HAND) open(e.getPlayer(),g,0);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void protect(BlockBreakEvent e) { if(at(e.getBlock())!=null) { e.setCancelled(true); tell(e.getPlayer(),"우클릭으로 내용물을 회수하세요."); } }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void explosion(EntityExplodeEvent e) { e.blockList().removeIf(b->at(b)!=null); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void explosion(BlockExplodeEvent e) { e.blockList().removeIf(b->at(b)!=null); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void piston(BlockPistonExtendEvent e) { if(e.getBlocks().stream().anyMatch(b->at(b)!=null)) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void piston(BlockPistonRetractEvent e) { if(e.getBlocks().stream().anyMatch(b->at(b)!=null)) e.setCancelled(true); }
    private Holder holder(Inventory inv) { return inv!=null && inv.getHolder() instanceof DeathChests.Holder h ? h : null; }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void click(InventoryClickEvent e) {
        Holder h=holder(e.getView().getTopInventory()); if(h==null) return; e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p) || !h.viewer.equals(p.getUniqueId())
            || (e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT)) return;
        int slot=e.getRawSlot(); if(slot!=45 && slot!=49 && slot!=53) return;
        if(!clicking.add(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin.owner(),()->{
            clicking.remove(p.getUniqueId());
            if(!p.isOnline() || p.getOpenInventory().getTopInventory()!=h.inv) return;
            p.closeInventory();
            if(slot==49) claim(p,h.grave); else open(p,h.grave,h.page+(slot==45?-1:1));
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent e) { if(holder(e.getView().getTopInventory())!=null) e.setCancelled(true); }
    @EventHandler public void join(PlayerJoinEvent e) {
        if(!owned(e.getPlayer().getUniqueId()).isEmpty()) tell(e.getPlayer(),"미회수 상자가 있습니다. /camp graves");
    }
    void close() { for(Player p:Bukkit.getOnlinePlayers()) if(holder(p.getOpenInventory().getTopInventory())!=null) p.closeInventory(); }
}
