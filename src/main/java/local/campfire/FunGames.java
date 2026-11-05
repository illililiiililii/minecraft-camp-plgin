package local.campfire;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class FunGames implements Listener {
    private static final String[] NAMES={"퇴근 거부 돌격대장","초고속 감자 배달원","닭 협회 방패 기사"};
    private static final Set<EntityType> TYPES=EnumSet.of(EntityType.ZOMBIE,EntityType.HUSK,EntityType.SKELETON,EntityType.STRAY);
    private final CampfireRuntime plugin;
    private final NamespacedKey natural,elite,expiry,hp,speed,attack;
    private final Random random=new Random();
    private final PartyCombo combo=new PartyCombo();
    private final Set<UUID> contributors=new HashSet<>();
    private final Map<UUID,Long> fishingAt=new HashMap<>();
    private UUID target;
    private Location lastSeen;
    private String targetName="";
    private long targetUntil,nextElite,nextCombo;
    private boolean writable=true;
    private final BukkitTask ticker;

    FunGames(CampfireRuntime plugin) throws Exception {
        this.plugin=plugin;
        natural=new NamespacedKey(plugin.owner(),"fun_natural");elite=new NamespacedKey(plugin.owner(),"bounty_elite");expiry=new NamespacedKey(plugin.owner(),"bounty_expiry");
        hp=new NamespacedKey(plugin.owner(),"bounty_old_hp");speed=new NamespacedKey(plugin.owner(),"bounty_old_speed");attack=new NamespacedKey(plugin.owner(),"bounty_old_attack");
        File file=new File(plugin.getDataFolder(),"fun.yml");YamlConfiguration data=new YamlConfiguration();if(file.exists())data.load(file);
        nextElite=data.getLong("next-elite",0);nextCombo=data.getLong("next-combo",0);
        if(data.isString("target")) { target=UUID.fromString(data.getString("target"));targetUntil=data.getLong("target-until");lastSeen=data.getLocation("last-seen");targetName=data.getString("target-name",NAMES[0]); }
        var section=data.getConfigurationSection("fishing");if(section!=null)for(String id:section.getKeys(false))fishingAt.put(UUID.fromString(id),data.getLong("fishing."+id));
        if(data.isList("contributors"))for(String id:data.getStringList("contributors"))contributors.add(UUID.fromString(id));
        for(World w:Bukkit.getWorlds())for(Entity e:w.getEntities())if(e instanceof LivingEntity l)cleanupElite(l,System.currentTimeMillis());
        Bukkit.getPluginManager().registerEvents(this,plugin.owner());
        ticker=Bukkit.getScheduler().runTaskTimer(plugin.owner(),()->maintain(System.currentTimeMillis()),20,20);
    }
    private boolean save() {
        if(!writable)return false;
        YamlConfiguration data=new YamlConfiguration();data.set("next-elite",nextElite);data.set("next-combo",nextCombo);
        if(target!=null){data.set("target",target.toString());data.set("target-until",targetUntil);data.set("last-seen",lastSeen);data.set("target-name",targetName);data.set("contributors",contributors.stream().map(UUID::toString).toList());}
        fishingAt.forEach((id,t)->data.set("fishing."+id,t));
        Path file=new File(plugin.getDataFolder(),"fun.yml").toPath(),tmp=file.resolveSibling("fun.yml.tmp");
        try {
            data.save(tmp.toFile());if(Files.exists(file))Files.copy(file,file.resolveSibling("fun.yml.bak"),StandardCopyOption.REPLACE_EXISTING);
            try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
            return true;
        }catch(Exception ex){writable=false;plugin.getLogger().log(java.util.logging.Level.SEVERE,"Fun feature rewards paused after save failure.",ex);return false;}
    }
    private void tell(Player p,String text){p.sendMessage(CampPresentation.text("◆ 수상한 놀이  "+text,CampPresentation.VIOLET));}
    private boolean survivor(Player p){return p!=null && p.isOnline() && !p.isDead() && p.getGameMode()==GameMode.SURVIVAL;}
    private List<Player> nearby(Location l){return Bukkit.getOnlinePlayers().stream().filter(p->survivor(p)&&p.getWorld().equals(l.getWorld())&&p.getLocation().distanceSquared(l)<=48*48).map(p->(Player)p).toList();}
    private boolean bonus(Player p,List<ItemStack> items){boolean ok=plugin.extras().supplies().reward(p.getUniqueId(),items);tell(p,ok?"보상이 택배함에 적립됐습니다. 지상에서 받아 가세요!":"보상 저장 실패. 서버 콘솔을 확인하세요.");return ok;}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR)
    public void spawn(CreatureSpawnEvent e){considerSpawn(e,random.nextInt(100),System.currentTimeMillis());}
    void considerSpawn(CreatureSpawnEvent e,int roll,long now){
        if(e.getSpawnReason()!=CreatureSpawnEvent.SpawnReason.NATURAL || !(e.getEntity() instanceof Monster))return;
        LivingEntity mob=e.getEntity();mob.getPersistentDataContainer().set(natural,PersistentDataType.BYTE,(byte)1);
        if(!writable || !plugin.getConfig().getBoolean("fun.bounty",true) || target!=null || now<nextElite || roll>=3
            || !TYPES.contains(mob.getType()) || mob.customName()!=null || mob.getWorld().getEnvironment()!=World.Environment.NORMAL || nearby(mob.getLocation()).isEmpty())return;
        int type=random.nextInt(3);
        var health=mob.getAttribute(Attribute.MAX_HEALTH);var movement=mob.getAttribute(Attribute.MOVEMENT_SPEED);var damage=mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if(health==null || movement==null)return;
        var pdc=mob.getPersistentDataContainer();
        pdc.set(hp,PersistentDataType.DOUBLE,health.getBaseValue());pdc.set(speed,PersistentDataType.DOUBLE,movement.getBaseValue());
        if(damage!=null)pdc.set(attack,PersistentDataType.DOUBLE,damage.getBaseValue());
        target=mob.getUniqueId();targetName=NAMES[type];lastSeen=mob.getLocation();targetUntil=now+300_000;nextElite=now+180_000;contributors.clear();
        pdc.set(elite,PersistentDataType.BYTE,(byte)1);pdc.set(expiry,PersistentDataType.LONG,targetUntil);
        health.setBaseValue(Math.min(100,health.getBaseValue()*(type==2?2.8:2.0)));mob.setHealth(health.getValue());
        movement.setBaseValue(Math.min(.36,movement.getBaseValue()*(type==1?1.35:type==2?.85:1.15)));
        if(damage!=null)damage.setBaseValue(Math.min(6,damage.getBaseValue()*1.2));
        mob.customName(CampPresentation.heading("현상수배 · "+targetName,CampPresentation.VIOLET));mob.setCustomNameVisible(true);mob.setGlowing(true);
        if(!save()){restore(mob);target=null;return;}
        for(Player p:nearby(mob.getLocation()))tell(p,"현상수배: "+targetName+" 등장! /camp fun 으로 목격 좌표 확인. 제한 5분!");
        if(plugin.presentation()!=null)plugin.presentation().pulse(mob.getLocation(),Color.fromRGB(201,156,255));
    }
    private boolean marked(LivingEntity mob){return mob.getPersistentDataContainer().has(elite,PersistentDataType.BYTE);}
    private void restore(LivingEntity mob){
        if(!marked(mob))return;var p=mob.getPersistentDataContainer();
        var health=mob.getAttribute(Attribute.MAX_HEALTH);var movement=mob.getAttribute(Attribute.MOVEMENT_SPEED);var damage=mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if(health!=null){health.setBaseValue(p.getOrDefault(hp,PersistentDataType.DOUBLE,20d));if(!mob.isDead())mob.setHealth(Math.min(mob.getHealth(),health.getValue()));}
        if(movement!=null)movement.setBaseValue(p.getOrDefault(speed,PersistentDataType.DOUBLE,.23d));
        if(damage!=null&&p.has(attack,PersistentDataType.DOUBLE))damage.setBaseValue(p.get(attack,PersistentDataType.DOUBLE));
        for(NamespacedKey key:List.of(elite,expiry,hp,speed,attack))p.remove(key);
        mob.customName(null);mob.setCustomNameVisible(false);mob.setGlowing(false);
    }
    private void cleanupElite(LivingEntity mob,long now){if(marked(mob)&&(!mob.getUniqueId().equals(target)||mob.getPersistentDataContainer().getOrDefault(expiry,PersistentDataType.LONG,0L)<=now))restore(mob);}
    void maintain(long now){
        if(target==null)return;Entity e=Bukkit.getEntity(target);
        if(now>=targetUntil||!plugin.getConfig().getBoolean("fun.bounty",true)){if(e instanceof LivingEntity l)restore(l);target=null;contributors.clear();save();}
        else if(e!=null && !e.isValid()){target=null;contributors.clear();save();}
        else if(e!=null)lastSeen=e.getLocation();
    }
    @EventHandler public void chunk(ChunkLoadEvent e){for(Entity entity:e.getChunk().getEntities())if(entity instanceof LivingEntity l)cleanupElite(l,System.currentTimeMillis());}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR)
    public void damage(EntityDamageByEntityEvent e){
        if(target==null||!target.equals(e.getEntity().getUniqueId())||e.getFinalDamage()<=0)return;
        Player p=e.getDamager() instanceof Player direct?direct:e.getDamager() instanceof Projectile shot&&shot.getShooter() instanceof Player shooter?shooter:null;
        if(survivor(p)&&contributors.add(p.getUniqueId()))save();
    }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST)
    public void death(EntityDeathEvent e){
        LivingEntity mob=e.getEntity();Player killer=mob.getKiller();long now=System.currentTimeMillis();
        if(marked(mob)){
            boolean paid=target!=null&&target.equals(mob.getUniqueId())&&now<targetUntil&&survivor(killer)&&writable&&plugin.getConfig().getBoolean("fun.bounty",true);
            if(paid)contributors.add(killer.getUniqueId());
            if(target!=null&&target.equals(mob.getUniqueId())){target=null;save();}
            restore(mob);
            if(paid)for(Player p:nearby(mob.getLocation()))if(contributors.contains(p.getUniqueId())){
                List<ItemStack> loot=new ArrayList<>(List.of(new ItemStack(Material.EMERALD)));
                if(p.equals(killer))loot.add(new ItemStack(Material.DIAMOND));
                if(bonus(p,loot))p.giveExp(50);
            }
            contributors.clear();
        }
        if(!survivor(killer)||!plugin.getConfig().getBoolean("fun.combo",true)||!mob.getPersistentDataContainer().has(natural,PersistentDataType.BYTE))return;
        // Each death can advance the chain once, even if another plugin replays the event.
        mob.getPersistentDataContainer().remove(natural);
        Location at=mob.getLocation();int chain=combo.hit(killer.getUniqueId(),at.getWorld().getUID(),at.getX(),at.getY(),at.getZ(),now);
        if(chain>=5&&chain%5==0&&now>=nextCombo&&writable){
            nextCombo=now+60_000;if(!save())return;
            for(Player p:nearby(at))if(combo.participants().contains(p.getUniqueId())){
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,200,0));p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,120,0));p.giveExp(25);
                tell(p,"파티 콤보 "+chain+"! 신속 10초·재생 6초·경험치 25. 보상 재사용 60초.");
            }
            if(plugin.presentation()!=null)plugin.presentation().pulse(at,Color.fromRGB(98,234,210));
        }
    }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGH)
    public void fish(PlayerFishEvent e){fishing(e,random.nextInt(100),random.nextInt(100),System.currentTimeMillis());}
    void fishing(PlayerFishEvent e,int chance,int lootRoll,long now){
        Player p=e.getPlayer();if(!writable||!survivor(p)||!plugin.getConfig().getBoolean("fun.fishing",true)
            ||e.getState()!=PlayerFishEvent.State.CAUGHT_FISH||!(e.getCaught() instanceof Item)||chance>=20||now<fishingAt.getOrDefault(p.getUniqueId(),0L))return;
        ItemStack prize;String joke;
        if(lootRoll<20){prize=new ItemStack(Material.EMERALD,2);joke="물고기가 연체 이자를 갚았습니다. 에메랄드 2개!";}
        else if(lootRoll<40){prize=plugin.extras().suspiciousPotato();joke="바다가 수상한 감자를 반납했습니다.";}
        else if(lootRoll<75){prize=new ItemStack(Material.PAPER);prize.editMeta(meta->{meta.displayName(CampPresentation.text("닭 부장의 사직서",CampPresentation.GOLD));meta.lore(List.of(Component.text("반려 사유: 닭이 먼저 퇴근했습니다.")));});joke="꽝! 닭 부장의 사직서. 물고기는 그대로 받아 가세요.";}
        else if(lootRoll<95){prize=new ItemStack(Material.ENDER_PEARL);joke="물고기 대신 바다의 분실물 센터가 응답했습니다. 진주 1개!";}
        else{prize=new ItemStack(Material.DIAMOND);joke="낚싯줄에 다이아몬드 1개가 걸렸습니다!";}
        fishingAt.put(p.getUniqueId(),now+90_000);if(!save())return;
        if(bonus(p,List.of(prize))){tell(p,joke);if(plugin.presentation()!=null)plugin.presentation().ambient(p.getLocation(),Particle.HAPPY_VILLAGER,12);}
    }
    Component hud(Player p,long now){
        if(!plugin.getConfig().getBoolean("fun.combo",true)||!combo.nearby(p.getWorld().getUID(),p.getLocation().getX(),p.getLocation().getY(),p.getLocation().getZ(),now))return null;
        return CampPresentation.heading("◆ PARTY COMBO ×"+combo.count(now),CampPresentation.TEAL).append(CampPresentation.text("  15초 안에 친구가 마무리!",net.kyori.adventure.text.format.NamedTextColor.WHITE));
    }
    void status(Player p){
        if(target!=null&&lastSeen!=null)tell(p,"현상수배: "+targetName+" / 마지막 목격 "+lastSeen.getWorld().getName()+" "+lastSeen.getBlockX()+", "+lastSeen.getBlockY()+", "+lastSeen.getBlockZ()+" / "+Math.max(0,(targetUntil-System.currentTimeMillis()+999)/1000)+"초 남음");
        else tell(p,"현재 현상수배 몹이 없습니다. 자연 생성 몬스터 중 드물게 등장합니다.");
        tell(p,"친구와 15초 안에 번갈아 자연 몬스터 5마리 처치 → 파티 콤보!");
        tell(p,"낚시 성공 시 20%로 수상한 보너스. 개인 재사용 90초, 기본 낚시 보상 유지.");
    }
    boolean isElite(LivingEntity mob){return marked(mob);}
    void close(){ticker.cancel();save();}
}
