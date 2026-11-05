package local.campfire;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Parcels remain durable until owner pickup, including while a visual drop is in flight. */
final class SupplyQueue implements Listener {
    record Parcel(UUID id, UUID owner, ItemStack item) { }
    record Flight(Parcel parcel, Item entity, long since) { }
    @FunctionalInterface interface OnDrop { void accept(Player p, Item item, boolean rare); }
    private final CampfireRuntime plugin;
    private final Function<SupplyRules.Prize,ItemStack> factory;
    private final OnDrop onDrop;
    private final NamespacedKey parcelKey;
    private final Random random=new Random();
    private final LinkedHashMap<UUID,Parcel> parcels=new LinkedHashMap<>();
    private final Map<UUID,Flight> flying=new HashMap<>();
    private final Map<UUID,Long> deliveryAt=new HashMap<>();
    private long nextAt;
    private boolean writable=true;

    SupplyQueue(CampfireRuntime plugin,long legacyDeadline,Function<SupplyRules.Prize,ItemStack> factory,OnDrop onDrop) throws Exception {
        this.plugin=plugin;this.factory=factory;this.onDrop=onDrop;
        parcelKey=new NamespacedKey(plugin.owner(),"sky_parcel_id");
        File file=new File(plugin.getDataFolder(),"supplies.yml");
        long now=System.currentTimeMillis();
        if(file.exists()) {
            YamlConfiguration data=new YamlConfiguration();data.load(file);
            if(data.getInt("format",0)!=1) throw new IOException("Unsupported supplies.yml format");
            nextAt=data.getLong("next-supply",now+60_000);
            var section=data.getConfigurationSection("parcels");
            if(section!=null) for(String id:section.getKeys(false)) {
                String k="parcels."+id+".";
                ItemStack item=data.getItemStack(k+"item");
                if(item==null || item.getType().isAir() || item.getAmount()<1 || item.getAmount()>item.getMaxStackSize()) throw new IOException("Invalid supply parcel");
                Parcel p=new Parcel(UUID.fromString(id),UUID.fromString(data.getString(k+"owner")),item.clone());
                parcels.put(p.id,p);
            }
            // No offline back-pay. Already earned parcels above are retained in full.
            if(nextAt<now) nextAt=now+Math.min(60_000,interval());
        } else nextAt=legacyDeadline>now ? Math.min(legacyDeadline,now+interval()) : now+60_000;
        if(!save()) throw new IOException("Cannot save supply queue");
        Bukkit.getPluginManager().registerEvents(this,plugin.owner());
    }
    private long interval() { return Math.clamp(plugin.getConfig().getInt("sky-supply.interval-seconds",120),60,3600)*1000L; }
    long nextAt() { return nextAt; }
    long secondsUntil(long now) { return Math.max(0,(nextAt-now+999)/1000); }
    int pending(UUID owner) { return (int)parcels.values().stream().filter(p->p.owner.equals(owner)).count(); }
    int inFlight(UUID owner) { return (int)flying.values().stream().filter(f->f.parcel.owner.equals(owner)).count(); }
    List<Item> drops(UUID owner) { return flying.values().stream().filter(f->f.parcel.owner.equals(owner)).map(Flight::entity).toList(); }
    boolean reward(UUID owner,List<ItemStack> items) {
        List<UUID> added=new ArrayList<>();
        for(ItemStack item:items) {
            if(item==null || item.getType().isAir() || item.getAmount()<1 || item.getAmount()>item.getMaxStackSize()) throw new IllegalArgumentException("Invalid bonus parcel");
        }
        for(ItemStack item:items) { Parcel p=new Parcel(UUID.randomUUID(),owner,item.clone());parcels.put(p.id,p);added.add(p.id); }
        if(save()) return true;
        added.forEach(parcels::remove);return false;
    }
    private boolean save() {
        if(!writable) return false;
        YamlConfiguration data=new YamlConfiguration();data.set("format",1);data.set("next-supply",nextAt);
        for(Parcel p:parcels.values()) {
            String k="parcels."+p.id+".";data.set(k+"owner",p.owner.toString());data.set(k+"item",p.item);
        }
        Path file=new File(plugin.getDataFolder(),"supplies.yml").toPath(),tmp=file.resolveSibling("supplies.yml.tmp");
        try {
            data.save(tmp.toFile());
            if(Files.exists(file)) Files.copy(file,file.resolveSibling("supplies.yml.bak"),StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ex) { Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch(Exception ex) {
            writable=false;
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"Supply delivery stopped after a queue save failure. Existing parcels are retained.",ex);
            return false;
        }
    }
    void tick(long now,List<Player> eligible,Predicate<Location> skyClear) {
        if(!writable) { removeAllDrops();return; }
        if(!plugin.getConfig().getBoolean("sky-supply.enabled",true)) { removeAllDrops();return; }
        if(now>=nextAt) {
            long previous=nextAt;
            nextAt=now+interval();
            Parcel added=null;
            if(!eligible.isEmpty()) {
                Player chosen=eligible.get(random.nextInt(eligible.size()));
                // Roll exactly once at allocation. Re-delivery never rerolls the prize.
                added=new Parcel(UUID.randomUUID(),chosen.getUniqueId(),factory.apply(SupplyRules.roll(random.nextInt(100))));
                parcels.put(added.id,added);
            }
            if(!save()) { if(added!=null) parcels.remove(added.id);nextAt=previous;return; }
        }
        Set<UUID> online=new HashSet<>();eligible.forEach(p->online.add(p.getUniqueId()));
        for(Flight flight:new ArrayList<>(flying.values())) {
            Player owner=Bukkit.getPlayer(flight.parcel.owner);
            if(!flight.entity.isValid() || owner==null || !online.contains(owner.getUniqueId()) || !skyClear.test(owner.getLocation())
                || !owner.getWorld().equals(flight.entity.getWorld()) || owner.getLocation().distanceSquared(flight.entity.getLocation())>32*32
                || now-flight.since>=60_000) recover(flight.parcel.id);
        }
        int max=Math.clamp(plugin.getConfig().getInt("sky-supply.max-in-flight",3),1,8);
        long gap=Math.clamp(plugin.getConfig().getInt("sky-supply.catch-up-seconds",1),1,10)*1000L;
        for(Player owner:eligible) {
            UUID id=owner.getUniqueId();
            if(now<deliveryAt.getOrDefault(id,0L) || inFlight(id)>=max || !skyClear.test(owner.getLocation())) continue;
            Parcel next=parcels.values().stream().filter(p->p.owner.equals(id) && !flying.containsKey(p.id)).findFirst().orElse(null);
            if(next==null) continue;
            deliveryAt.put(id,now+gap);
            Location location=owner.getLocation().clone().add(0,6,0);
            Item entity=location.getWorld().dropItem(location,next.item.clone());
            entity.getPersistentDataContainer().set(parcelKey,PersistentDataType.STRING,next.id.toString());
            entity.setPersistent(false);entity.setOwner(id);entity.setPickupDelay(20);entity.setGlowing(true);
            entity.setVelocity(new Vector(0,-.08,0));
            flying.put(next.id,new Flight(next,entity,now));
            onDrop.accept(owner,entity,next.item.getType()==Material.IRON_CHESTPLATE || next.item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS)==7 && next.item.getType()==Material.IRON_SWORD);
        }
    }
    private UUID id(Item entity) {
        String value=entity.getPersistentDataContainer().get(parcelKey,PersistentDataType.STRING);
        if(value==null) return null;
        try { return UUID.fromString(value); } catch(IllegalArgumentException ignored) { return null; }
    }
    private void recover(UUID id) {
        Flight flight=flying.remove(id);
        if(flight!=null) flight.entity.remove(); // The durable parcel remains outstanding.
    }
    private void removeAllDrops() { for(UUID id:new ArrayList<>(flying.keySet())) recover(id); }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST)
    public void pickup(EntityPickupItemEvent e) {
        UUID id=id(e.getItem());if(id==null) return;
        Parcel parcel=parcels.get(id);Flight flight=flying.get(id);
        if(parcel==null || flight==null || !flight.entity.getUniqueId().equals(e.getItem().getUniqueId())) {
            e.setCancelled(true);e.getItem().remove();return;
        }
        if(!(e.getEntity() instanceof Player p) || !p.getUniqueId().equals(parcel.owner) || e.getRemaining()!=0 || !writable) { e.setCancelled(true);return; }
        parcels.remove(id);
        if(!save()) { parcels.put(id,parcel);e.setCancelled(true);return; }
        flying.remove(id);
        // The native pickup now moves exactly this one item into its owner's inventory.
    }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST)
    public void hopper(InventoryPickupItemEvent e) { if(id(e.getItem())!=null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST)
    public void merge(ItemMergeEvent e) { if(id(e.getEntity())!=null || id(e.getTarget())!=null) e.setCancelled(true); }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST)
    public void damage(EntityDamageEvent e) {
        if(!(e.getEntity() instanceof Item item)) return;UUID id=id(item);if(id==null) return;
        e.setCancelled(true);recover(id);
    }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST)
    public void despawn(ItemDespawnEvent e) { UUID id=id(e.getEntity());if(id!=null) { e.setCancelled(true);recover(id); } }
    @EventHandler public void quit(PlayerQuitEvent e) {
        for(Flight flight:new ArrayList<>(flying.values())) if(flight.parcel.owner.equals(e.getPlayer().getUniqueId())) recover(flight.parcel.id);
        deliveryAt.remove(e.getPlayer().getUniqueId());
    }
    void close() { removeAllDrops(); }
}
