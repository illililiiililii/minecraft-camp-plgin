package local.campfire;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.*;
import java.util.*;

final class Conveniences implements Listener {
    record Request(UUID sender,long expires) { }
    private final CampfireRuntime plugin;
    private final NamespacedKey placedKey;
    private final Set<UUID> chopping=new HashSet<>(), treeOff=new HashSet<>();
    private final Set<BukkitTask> jobs=new HashSet<>();
    private final Map<UUID,Request> requests=new HashMap<>();
    Conveniences(CampfireRuntime plugin) {
        this.plugin=plugin; placedKey=new NamespacedKey(plugin.owner(),"placed_logs");
        Bukkit.getPluginManager().registerEvents(this,plugin.owner());
    }
    private void tell(Player p,String s) { p.sendMessage(CampPresentation.text("◆ 편의 기능  "+s,CampPresentation.TEAL)); }
    private boolean log(Material m) { return m.name().endsWith("_LOG") && !m.name().startsWith("STRIPPED_"); }
    private String position(Block b) { return (b.getX()&15)+","+b.getY()+","+(b.getZ()&15); }
    private Set<String> placed(Chunk c) {
        String stored=c.getPersistentDataContainer().getOrDefault(placedKey,PersistentDataType.STRING,"");
        return stored.isEmpty()?new HashSet<>():new HashSet<>(Arrays.asList(stored.split("\\|")));
    }
    private boolean placed(Block b) { return placed(b.getChunk()).contains(position(b)); }
    private void mark(Block b,boolean value) {
        Chunk c=b.getChunk(); Set<String> set=placed(c);
        if(value) set.add(position(b)); else set.remove(position(b));
        if(set.isEmpty()) c.getPersistentDataContainer().remove(placedKey);
        else c.getPersistentDataContainer().set(placedKey,PersistentDataType.STRING,String.join("|",set));
    }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR)
    public void place(BlockPlaceEvent e) { if(log(e.getBlockPlaced().getType())) mark(e.getBlockPlaced(),true); }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR)
    public void unmark(BlockBreakEvent e) {
        if(log(e.getBlock().getType()) && placed(e.getBlock())) Bukkit.getScheduler().runTask(plugin.owner(),()->{
            if(!log(e.getBlock().getType())) mark(e.getBlock(),false);
        });
    }
    List<Block> tree(Block root) {
        if(!log(root.getType()) || placed(root)) return List.of();
        int cap=Math.clamp(plugin.getConfig().getInt("convenience.tree-max-logs",64),2,128);
        Material type=root.getType();
        Set<Block> visited=new HashSet<>(), leaves=new HashSet<>(); List<Block> result=new ArrayList<>();
        Deque<Block> queue=new ArrayDeque<>(); queue.add(root);
        boolean grounded=false;
        Set<Material> soil=Set.of(Material.DIRT,Material.GRASS_BLOCK,Material.PODZOL,Material.COARSE_DIRT,Material.ROOTED_DIRT,Material.MOSS_BLOCK,Material.MUD);
        while(!queue.isEmpty()) {
            Block b=queue.removeFirst(); if(!visited.add(b) || b.getType()!=type || placed(b)) continue;
            result.add(b); if(result.size()>cap) return List.of();
            if(soil.contains(b.getRelative(BlockFace.DOWN).getType())) grounded=true;
            for(int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=1;dy++) for(int dz=-1;dz<=1;dz++) {
                if(dx==0 && dy==0 && dz==0) continue;
                int x=b.getX()+dx,y=b.getY()+dy,z=b.getZ()+dz;
                if(Math.abs(x-root.getX())>6 || Math.abs(z-root.getZ())>6 || y<root.getY()-5 || y>root.getY()+32
                    || y<root.getWorld().getMinHeight() || y>=root.getWorld().getMaxHeight() || !root.getWorld().isChunkLoaded(x>>4,z>>4)) continue;
                Block n=root.getWorld().getBlockAt(x,y,z);
                if(n.getBlockData() instanceof Leaves l && !l.isPersistent()) leaves.add(n);
                if(n.getType()==type && !visited.contains(n)) queue.addLast(n);
            }
        }
        return grounded && leaves.size()>=4 ? result : List.of();
    }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR)
    public void chop(BlockBreakEvent e) {
        Player p=e.getPlayer();
        if(!plugin.getConfig().getBoolean("convenience.tree-chop",true) || p.getGameMode()!=GameMode.SURVIVAL || !p.isSneaking()
            || treeOff.contains(p.getUniqueId()) || chopping.contains(p.getUniqueId()) || !log(e.getBlock().getType()) || placed(e.getBlock())) return;
        Material axe=p.getInventory().getItemInMainHand().getType();
        if(!axe.name().endsWith("_AXE")) return;
        List<Block> found=tree(e.getBlock()); if(found.size()<2) return;
        Deque<Block> rest=new ArrayDeque<>(found); rest.remove(e.getBlock());
        Material logs=e.getBlock().getType(); Location origin=e.getBlock().getLocation();
        chopping.add(p.getUniqueId());
        new BukkitRunnable() {
            BukkitTask task;
            int cut;
            void begin() { task=runTaskTimer(plugin.owner(),1,1); jobs.add(task); }
            void done() { jobs.remove(task); chopping.remove(p.getUniqueId()); cancel(); if(cut>0 && p.isOnline()) tell(p,"찹트리: 추가 원목 "+cut+"개 벌목 완료"); }
            @Override public void run() {
                if(!p.isOnline() || p.isDead() || !p.isSneaking() || !p.getWorld().equals(origin.getWorld()) || p.getLocation().distanceSquared(origin)>64
                    || p.getInventory().getItemInMainHand().getType()!=axe) { done(); return; }
                for(int i=0;i<4;i++) {
                    if(rest.isEmpty()) { done(); return; }
                    if(p.getInventory().getItemInMainHand().getType()!=axe || plugin.extras().remaining(p.getInventory().getItemInMainHand())==0) { done(); return; }
                    Block b=rest.removeFirst();
                    if(!b.getWorld().isChunkLoaded(b.getX()>>4,b.getZ()>>4)) { done(); return; }
                    if(b.getType()!=logs || placed(b)) continue;
                    // Uses native player breaking: protection events, normal drops, durability and sky-tool charges apply per log.
                    if(!p.breakBlock(b)) { done(); return; }
                    cut++;
                }
            }
        }.begin();
    }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGH)
    public void replant(PlayerInteractEvent e) {
        if(!plugin.getConfig().getBoolean("convenience.replant",true) || e.getHand()!=EquipmentSlot.HAND || e.getAction()!=Action.RIGHT_CLICK_BLOCK
            || !e.getPlayer().isSneaking() || e.getPlayer().getGameMode()!=GameMode.SURVIVAL || e.getClickedBlock()==null) return;
        Block block=e.getClickedBlock();
        Material seed=switch(block.getType()) {
            case WHEAT->Material.WHEAT_SEEDS; case CARROTS->Material.CARROT; case POTATOES->Material.POTATO; case BEETROOTS->Material.BEETROOT_SEEDS; default->null;
        };
        if(seed==null || !(block.getBlockData() instanceof Ageable age) || age.getAge()!=age.getMaximumAge()) return;
        Player p=e.getPlayer(); BlockBreakEvent permission=new BlockBreakEvent(block,p);
        // BlockDropItemEvent is not fired here. Other plugins can veto harvesting through BlockBreakEvent.
        Bukkit.getPluginManager().callEvent(permission); if(permission.isCancelled() || !permission.isDropItems()) return;
        List<ItemStack> drops=block.getDrops(p.getInventory().getItemInMainHand(),p).stream().map(ItemStack::clone).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        boolean paid=false;
        for(ItemStack drop:drops) if(drop.getType()==seed && drop.getAmount()>0) { drop.setAmount(drop.getAmount()-1); paid=true; break; }
        if(!paid) return;
        e.setCancelled(true); age.setAge(0); block.setBlockData(age);
        for(ItemStack drop:drops) if(drop.getAmount()>0) block.getWorld().dropItemNaturally(block.getLocation().add(.5,.5,.5),drop);
    }
    void sort(Player p) {
        if(p.getOpenInventory().getTopInventory()!=null && p.getOpenInventory().getTopInventory().getType()!=org.bukkit.event.inventory.InventoryType.CRAFTING) {
            tell(p,"열려 있는 창을 닫고 /camp sort 를 사용하세요."); return;
        }
        ItemStack[] before=p.getInventory().getStorageContents();
        List<ItemStack> stacks=new ArrayList<>();
        // Keep hotbar, armor and offhand untouched. Merge only exactly similar item metadata.
        for(int i=9;i<36;i++) if(before[i]!=null && !before[i].getType().isAir()) stacks.add(before[i].clone());
        stacks.sort(java.util.Comparator.comparing(item->item.getType().name()));
        Inventory sorted=Bukkit.createInventory(null,27);
        for(ItemStack item:stacks) if(!sorted.addItem(item).isEmpty()) { tell(p,"정리 공간 계산 실패로 원래 인벤토리를 유지했습니다."); return; }
        ItemStack[] result=before.clone(); for(int i=9;i<36;i++) result[i]=sorted.getItem(i-9);
        p.getInventory().setStorageContents(result); p.saveData(); tell(p,"인벤토리를 정리했습니다. 핫바와 장비는 그대로입니다.");
    }
    void request(Player from,String name) {
        Player to=Bukkit.getPlayerExact(name);
        if(to==null || to.equals(from) || from.getGameMode()!=GameMode.SURVIVAL || to.getGameMode()!=GameMode.SURVIVAL || from.isDead() || to.isDead()) {
            tell(from,"접속 중인 다른 생존 플레이어에게 요청하세요."); return;
        }
        requests.entrySet().removeIf(entry->entry.getValue().sender.equals(from.getUniqueId()) || entry.getValue().expires<System.currentTimeMillis());
        Request existing=requests.get(to.getUniqueId());
        if(existing!=null) { tell(from,"상대가 다른 요청을 확인 중입니다. 잠시 뒤 다시 시도하세요."); return; }
        requests.put(to.getUniqueId(),new Request(from.getUniqueId(),System.currentTimeMillis()+30_000));
        tell(to,from.getName()+"님이 이곳으로 오기를 요청했습니다. 30초 안에 /camp tpaccept 또는 /camp tpdeny");
        tell(from,"요청을 보냈습니다. 상대가 수락하면 귀환 대기 후 이동합니다.");
    }
    void accept(Player to,boolean accept) {
        Request request=requests.remove(to.getUniqueId());
        if(request==null || request.expires<System.currentTimeMillis()) { tell(to,"유효한 텔레포트 요청이 없습니다."); return; }
        Player from=Bukkit.getPlayer(request.sender);
        if(from==null || from.isDead() || to.isDead() || from.getGameMode()!=GameMode.SURVIVAL || to.getGameMode()!=GameMode.SURVIVAL) { tell(to,"상대가 현재 이동할 수 없습니다."); return; }
        if(!accept) { tell(from,"텔레포트 요청이 거절되었습니다."); tell(to,"요청을 거절했습니다."); return; }
        plugin.warpTo(from,to.getLocation().clone(),to.getName()+"님의 수락 위치");
        tell(to,"요청을 수락했습니다. 현재 위치가 도착 지점으로 지정되었습니다.");
    }
    void command(Player p,String sub,String[] args) {
        switch(sub) {
            case "tree"->{ if(!treeOff.add(p.getUniqueId())) treeOff.remove(p.getUniqueId()); tell(p,"찹트리 "+(treeOff.contains(p.getUniqueId())?"끔":"켬")+" (이번 접속 동안)"); }
            case "sort"->sort(p);
            case "craft"->p.openWorkbench(null,true);
            case "ender"->{ if(!p.getInventory().contains(Material.ENDER_CHEST)) tell(p,"엔더 상자 아이템을 인벤토리에 지니고 있어야 합니다."); else p.openInventory(p.getEnderChest()); }
            case "tpa"->{ if(args.length<2) tell(p,"/camp tpa 친구닉네임"); else request(p,args[1]); }
            case "tpaccept"->accept(p,true);
            case "tpdeny"->accept(p,false);
            default->tell(p,"/camp sort · /camp craft · /camp ender · /camp tree · /camp tpa 닉네임 · /camp tpaccept · /camp tpdeny");
        }
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id=e.getPlayer().getUniqueId(); requests.remove(id); requests.entrySet().removeIf(entry->entry.getValue().sender.equals(id)); treeOff.remove(id);
    }
    void close() { for(BukkitTask job:jobs) job.cancel(); jobs.clear(); chopping.clear(); requests.clear(); }
}
