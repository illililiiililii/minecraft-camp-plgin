package local.campfire;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import java.util.*;
import java.util.function.BiPredicate;

/** Temporary encounters and bounded-use loot. No terrain edits or explosions. */
final class AdventureExtras implements Listener {
    private static final String[] THEMES = {"닭 부장의 야근 던전", "감자 왕국 세무조사", "우주 출장 해골"};
    private static final String[] BOSSES = {"닭 부장: 퇴근은 허락하지 않았다", "감자 국세청장: 다이아 신고하셨나요", "우주 해골: 지구 출장비 미지급"};
    private final CampfireRuntime plugin;
    private final NamespacedKey charges, tag, potato;
    private final String session = UUID.randomUUID().toString();
    private final Random random = new Random();
    private final BiPredicate<Long, Long> saveTimes;
    private final Set<UUID> party = new HashSet<>();
    private final Map<UUID, LivingEntity> enemies = new HashMap<>();
    private final List<Entity> props = new ArrayList<>();
    private final Set<Chunk> tickets = new HashSet<>();
    private final BukkitTask ticker;
    private final SupplyQueue supplies;
    private Location site;
    private int wave, theme, teamSize;
    private double waveMaxHealth;
    private long nextAdventure, deadline, nextWave, nextJoke, nextAutoAttempt;
    private boolean stopped;

    record Snapshot(boolean active, String name, int wave, int enemies, double health, double maxHealth,
        long seconds, UUID world, int x, int y, int z, long supplySeconds, long cooldownSeconds) { }
    Snapshot snapshot() {
        long now = System.currentTimeMillis();
        double health = 0;
        for (LivingEntity entity : enemies.values()) {
            if (!entity.isValid()) continue;
            health += Math.max(0, entity.getHealth());
        }
        return new Snapshot(site != null, site == null ? "원정 대기" : THEMES[theme], wave, enemies.size(), health, waveMaxHealth,
            Math.max(0, (deadline - now + 999) / 1000), site == null ? null : site.getWorld().getUID(),
            site == null ? 0 : site.getBlockX(), site == null ? 0 : site.getBlockY(), site == null ? 0 : site.getBlockZ(),
            supplies.secondsUntil(now), Math.max(0, (nextAdventure - now + 999) / 1000));
    }

    AdventureExtras(CampfireRuntime plugin, long supplyAt, long adventureAt, BiPredicate<Long, Long> saveTimes) throws Exception {
        this.plugin = plugin;
        this.saveTimes = saveTimes;
        charges = new NamespacedKey(plugin.owner(), "supply_charges");
        tag = new NamespacedKey(plugin.owner(), "temporary_adventure");
        potato = new NamespacedKey(plugin.owner(), "suspicious_potato");
        long now = System.currentTimeMillis();
        for(Player player:Bukkit.getOnlinePlayers()) for(ItemStack item:player.getInventory().getContents()) immortal(item);
        supplies = new SupplyQueue(plugin, supplyAt, this::supplyItem, (p, item, rare) -> {
            ItemStack delivered=item.getItemStack(); immortal(delivered); item.setItemStack(delivered);
            if (plugin.presentation() != null) plugin.presentation().supplied(p, item, rare);
        });
        nextAdventure = adventureAt == 0 ? now + 480_000 : adventureAt;
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) cleanOld(e);
        Bukkit.getPluginManager().registerEvents(this, plugin.owner());
        ticker = Bukkit.getScheduler().runTaskTimer(plugin.owner(), this::tick, 20, 20);
        saveTimes.test(supplies.nextAt(), nextAdventure);
    }
    SupplyQueue supplies() { return supplies; }

    private void tell(CommandSender to, String text) {
        to.sendMessage(Component.text("[수상한 원정] " + text, NamedTextColor.AQUA));
    }
    private void announce(String text) { Bukkit.broadcast(Component.text("[수상한 원정] " + text, NamedTextColor.AQUA)); }
    private List<Player> eligible() {
        return Bukkit.getOnlinePlayers().stream().filter(p -> p.getGameMode() == GameMode.SURVIVAL && !p.isDead()
            && p.getWorld().getEnvironment() == World.Environment.NORMAL).map(p -> (Player)p).toList();
    }
    private List<Player> nearby(Location l, double radius) {
        return eligible().stream().filter(p -> p.getWorld().equals(l.getWorld()) && p.getLocation().distanceSquared(l) <= radius * radius).toList();
    }
    private long seconds(String key, int def, int min, int max) {
        return Math.clamp(plugin.getConfig().getInt(key, def), min, max) * 1000L;
    }
    private void tick() {
        if (stopped) return;
        long now = System.currentTimeMillis();
        List<Player> online = eligible();
        supplies.tick(now, online, this::skyClear);
        if (site == null) {
            if (plugin.getConfig().getBoolean("adventure.enabled", true) && now >= nextAdventure && now >= nextAutoAttempt && !online.isEmpty()) {
                nextAutoAttempt = now + 60_000;
                Player origin = online.get(random.nextInt(online.size()));
                if (nearby(origin.getLocation(), 48).size() >= minimum()) start(origin, false);
            }
            return;
        }
        props.removeIf(e -> !e.isValid());
        if (now >= deadline || nearby(site, 80).isEmpty()) { finish(false); return; }
        long countdown = (nextWave - now + 999) / 1000;
        if (wave == 0 && countdown >= 1 && countdown <= 3 && plugin.presentation() != null) plugin.presentation().countdown(site, (int)countdown);
        // Keep the encounter inside its clearing; all chunks around it stay loaded.
        for (LivingEntity e : enemies.values()) {
            if (!e.isValid()) { finish(false); return; }
            if (!e.getWorld().equals(site.getWorld()) || e.getLocation().distanceSquared(site) > 32 * 32) e.teleport(site);
        }
        if (enemies.isEmpty() && now >= nextWave) spawnWave();
        if (site != null && plugin.getConfig().getBoolean("adventure.pranks", true) && now >= nextJoke) {
            joke(random.nextInt(3)); nextJoke = now + 25_000;
        }
    }
    private int minimum() { return Math.clamp(plugin.getConfig().getInt("adventure.min-players", 2), 1, 4); }
    private boolean skyClear(Location loc) {
        World w = loc.getWorld();
        if (loc.getY() + 7 >= w.getMaxHeight() || !w.getWorldBorder().isInside(loc)) return false;
        for (int dy = 1; dy <= 7; dy++) if (!loc.clone().add(0, dy, 0).getBlock().getType().isAir()) return false;
        return w.getHighestBlockYAt(loc) <= loc.getBlockY() + 1;
    }
    private String supplyTier() {
        int r=random.nextInt(1000);
        return r<250?"WOODEN":r<500?"STONE":r<750?"IRON":r<900?"GOLDEN":r<995?"DIAMOND":"NETHERITE";
    }
    ItemStack supplyItem(SupplyRules.Prize prize) {
        if(prize==SupplyRules.Prize.DIAMOND) {
            int r=random.nextInt(1000);
            Material m=r<350?Material.IRON_INGOT:r<600?Material.GOLD_INGOT:r<800?Material.LAPIS_LAZULI:r<995?Material.DIAMOND:Material.NETHERITE_INGOT;
            int count=m==Material.IRON_INGOT?3+random.nextInt(4):m==Material.GOLD_INGOT?2+random.nextInt(3):m==Material.LAPIS_LAZULI?4+random.nextInt(5):1;
            return new ItemStack(m,count);
        }
        String tier=supplyTier();
        if(prize==SupplyRules.Prize.TOOLS) {
            String[] tools={"PICKAXE","AXE","SHOVEL","HOE"};
            return efficiencyTool(random.nextInt(5)==0?Material.SHEARS:Material.valueOf(tier+"_"+tools[random.nextInt(tools.length)]));
        }
        boolean armor=prize==SupplyRules.Prize.ARMOR;
        if(armor) tier=tier.equals("WOODEN")?"LEATHER":tier.equals("STONE")?"CHAINMAIL":tier;
        String[] slots={"HELMET","CHESTPLATE","LEGGINGS","BOOTS"};
        ItemStack item=new ItemStack(Material.valueOf(tier+"_"+(armor?slots[random.nextInt(4)]:"SWORD")));
        item.addUnsafeEnchantment(armor?Enchantment.PROTECTION:Enchantment.SHARPNESS,armor?6:7);
        immortal(item);
        return item;
    }
    ItemStack efficiencyTool(Material material) {
        ItemStack item=new ItemStack(material);
        item.addUnsafeEnchantment(Enchantment.EFFICIENCY,6);
        immortal(item);
        return item;
    }
    private void immortal(ItemStack item) {
        if(item==null || !item.hasItemMeta() && item.getType().getMaxDurability()==0) return;
        if(item.getType().getMaxDurability()==0) return;
        item.editMeta(meta->{
            boolean legacy=meta.getPersistentDataContainer().has(charges,PersistentDataType.INTEGER);
            meta.getPersistentDataContainer().remove(charges);
            meta.setUnbreakable(true);
            if(meta instanceof org.bukkit.inventory.meta.Damageable damage) damage.setDamage(0);
            if(legacy) meta.lore(null);
        });
    }
    @EventHandler(ignoreCancelled=true) public void noDurability(org.bukkit.event.player.PlayerItemDamageEvent e) {
        e.setCancelled(true); immortal(e.getItem());
    }
    @EventHandler public void immortalJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        for(ItemStack item:e.getPlayer().getInventory().getContents()) immortal(item);
    }
    private void updateLore(ItemStack item, int count) {
        boolean tool = item.containsEnchantment(Enchantment.EFFICIENCY);
        int max = tool ? 96 : item.getType() == Material.WOODEN_SWORD ? 32 : item.getEnchantmentLevel(Enchantment.SHARPNESS) == 7 ? 12 : 24;
        item.editMeta(meta -> meta.lore(List.of(CampPresentation.text("LIMITED EDITION  /  하늘 보급", CampPresentation.VIOLET),
            CampPresentation.text(CampPresentation.meter(count, max) + "  " + count + "/" + max, CampPresentation.TEAL),
            CampPresentation.text(tool ? "채굴 96회 제한 · 기타 동작은 기본 내구도 소모" : "유효 사용 횟수 소진 시 사라집니다.", NamedTextColor.GRAY),
            CampPresentation.text("수리 · 합성 · 인챈트 이전 불가", NamedTextColor.DARK_GRAY))));
    }
    int remaining(ItemStack item) { immortal(item); return -1; }
    private void spend(ItemStack item, Runnable destroy, Player owner) {
        int left = remaining(item);
        if (left < 0) return;
        if (left <= 1) { destroy.run(); tell(owner, "하늘 보급 장비가 퇴근했습니다. 남은 장비를 확인하세요!"); }
        else {
            item.editMeta(meta -> meta.getPersistentDataContainer().set(charges, PersistentDataType.INTEGER, left - 1));
            updateLore(item, left - 1);
        }
    }
    void command(CommandSender sender, String[] args) {
        String sub = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("cancel")) {
            if (!sender.hasPermission("campfire.admin")) { tell(sender, "운영자만 원정을 취소할 수 있습니다."); return; }
            finish(false); return;
        }
        if (sub.equals("start")) {
            if (sender instanceof Player p) start(p, true); else tell(sender, "생존 플레이어가 게임 안에서 시작하세요.");
            return;
        }
        if (site == null) {
            tell(sender, "친구와 넓은 지상에 모여 /camp adventure start 로 원정 시작!");
            tell(sender, "다음 원정까지 " + Math.max(0, (nextAdventure - System.currentTimeMillis() + 999) / 1000) + "초. 첫 수동 원정은 즉시 시작할 수 있습니다.");
        } else tell(sender, THEMES[theme] + " / " + site.getWorld().getName() + " " + site.getBlockX() + ", " + site.getBlockY() + ", " + site.getBlockZ()
            + " / " + wave + "/3차 전투, 남은 적 " + enemies.size());
    }
    private boolean manualStarted;
    private void start(Player origin, boolean manual) {
        if (!plugin.getConfig().getBoolean("adventure.enabled", true)) { if (manual) tell(origin, "원정 기능이 꺼져 있습니다."); return; }
        if (site != null) { if (manual) command(origin, new String[]{"adventure"}); return; }
        long now = System.currentTimeMillis();
        // First launch is available immediately only for a fresh server; restarts cannot bypass a saved cooldown.
        if (now < nextAdventure && (!manual || manualStarted || plugin.hadAdventureCooldown())) {
            if (manual) tell(origin, "다음 원정까지 " + ((nextAdventure - now + 999) / 1000) + "초 남았습니다."); return;
        }
        if (origin.getGameMode() != GameMode.SURVIVAL || origin.isDead() || origin.getWorld().getEnvironment() != World.Environment.NORMAL) {
            if (manual) tell(origin, "일반 월드의 생존 모드에서 시작하세요."); return;
        }
        List<Player> ready = nearby(origin.getLocation(), 48);
        if (ready.size() < minimum()) { if (manual) tell(origin, minimum() + "명 이상이 48블록 안에 모여야 합니다."); return; }
        Location candidate = findClearing(origin.getLocation());
        if (candidate == null) { if (manual) tell(origin, "넓고 평평한 지상으로 이동하세요. 원정은 아직 소비되지 않았습니다."); return; }
        nextAdventure = now + seconds("adventure.cooldown-seconds", 1200, 300, 7200);
        if (!saveTimes.test(supplies.nextAt(), nextAdventure)) return;
        manualStarted = true;
        site = candidate; theme = random.nextInt(THEMES.length); teamSize = Math.min(4, ready.size()); wave = 0;
        party.clear(); ready.forEach(p -> party.add(p.getUniqueId()));
        deadline = now + seconds("adventure.lifetime-seconds", 600, 120, 1200);
        nextWave = now + 10_000; nextJoke = now + 20_000;
        int cx = site.getBlockX() >> 4, cz = site.getBlockZ() >> 4;
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) {
            Chunk chunk = site.getWorld().getChunkAt(x, z);
            chunk.addPluginChunkTicket(plugin.owner()); tickets.add(chunk);
        }
        announce(THEMES[theme] + " 등장! 좌표 " + site.getBlockX() + ", " + site.getBlockY() + ", " + site.getBlockZ() + ". 10초 뒤 전투 시작, 반경 80블록을 벗어나면 종료됩니다.");
        if (plugin.presentation() != null) plugin.presentation().ambient(site.clone().add(0, 2, 0), Particle.END_ROD, 30);
        if (plugin.presentation() != null) plugin.presentation().pulse(site, Color.fromRGB(98,234,210));
    }
    private Location findClearing(Location origin) {
        World world = origin.getWorld();
        for (int attempt = 0; attempt < 20; attempt++) {
            double a = random.nextDouble() * Math.PI * 2;
            int x = origin.getBlockX() + (int)(Math.cos(a) * 16), z = origin.getBlockZ() + (int)(Math.sin(a) * 16);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            Location c = new Location(world, x + .5, y, z + .5);
            boolean valid = Math.abs(y - origin.getY()) <= 5 && world.getWorldBorder().isInside(c);
            for (int dx = -3; dx <= 3 && valid; dx++) for (int dz = -3; dz <= 3 && valid; dz++) {
                Location p = c.clone().add(dx, 0, dz);
                valid = world.isChunkLoaded(p.getBlockX() >> 4, p.getBlockZ() >> 4)
                    && p.getBlock().getType().isAir() && p.clone().add(0, 1, 0).getBlock().getType().isAir()
                    && p.clone().add(0, -1, 0).getBlock().getType().isSolid()
                    && !Set.of(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.OAK_LEAVES, Material.SPRUCE_LEAVES).contains(p.clone().add(0, -1, 0).getBlock().getType());
            }
            if (valid) return c;
        }
        return null;
    }
    private void spawnWave() {
        wave++;
        if (wave > 3) { finish(true); return; }
        int count = SupplyRules.waveSize(wave, teamSize);
        waveMaxHealth = 0;
        announce(THEMES[theme] + " — " + wave + "/3차 전투! " + count + "마리 출근했습니다.");
        if (plugin.presentation() != null) plugin.presentation().wave(site, wave);
        for (int i = 0; i < count; i++) {
            Location at = site.clone().add((i % 3) - 1, 0, i / 3);
            LivingEntity mob = (LivingEntity)site.getWorld().spawnEntity(at,
                theme == 2 ? EntityType.SKELETON : theme == 1 ? EntityType.HUSK : EntityType.ZOMBIE);
            mark(mob); enemies.put(mob.getUniqueId(), mob);
            mob.setRemoveWhenFarAway(false); mob.setCanPickupItems(false); mob.setGlowing(true);
            boolean boss = wave == 3 && i == 0;
            mob.customName(Component.text(boss ? BOSSES[theme] : theme == 0 ? "야근 거부 사원" : theme == 1 ? "감자 세무 조사관" : "출장비 달라는 해골", boss ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.YELLOW));
            mob.setCustomNameVisible(true);
            if (mob instanceof Zombie z) { z.setBaby(false); z.setShouldBurnInDay(false); }
            if (mob instanceof Skeleton s) s.setShouldBurnInDay(false);
            double health = boss ? 40 + teamSize * 12 : 16 + teamSize * 2;
            waveMaxHealth += health;
            Objects.requireNonNull(mob.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(health); mob.setHealth(health);
            EntityEquipment eq = mob.getEquipment();
            if (eq != null) {
                eq.setHelmet(new ItemStack(boss ? Material.CARVED_PUMPKIN : Material.LEATHER_HELMET));
                eq.setHelmetDropChance(0); eq.setItemInMainHand(new ItemStack(theme == 2 ? Material.BOW : theme == 1 ? Material.POTATO : Material.WOODEN_SWORD));
                eq.setItemInMainHandDropChance(0);
            }
            List<Player> targets = nearby(site, 64);
            if (mob instanceof Mob m && !targets.isEmpty()) m.setTarget(targets.get(i % targets.size()));
        }
    }
    private void mark(Entity e) { e.getPersistentDataContainer().set(tag, PersistentDataType.STRING, session); e.setPersistent(false); }
    private boolean marked(Entity e) { return e != null && e.getPersistentDataContainer().has(tag, PersistentDataType.STRING); }
    private void cleanOld(Entity e) { if (marked(e) && !session.equals(e.getPersistentDataContainer().get(tag, PersistentDataType.STRING))) e.remove(); }
    @EventHandler public void onChunk(ChunkLoadEvent e) { for (Entity entity : e.getChunk().getEntities()) cleanOld(entity); }
    private void joke(int type) {
        if (site == null) return;
        // Expire previous spectators; keep a fixed entity bound throughout the encounter.
        for (Entity e : props) if (!(e instanceof Projectile)) e.remove();
        props.removeIf(e -> !(e instanceof Projectile));
        if (type == 0) {
            announce("닭 감사팀 3명이 현장에 도착했습니다. 보수는 씨앗으로 주세요.");
            for (int i = 0; i < 3; i++) {
                Chicken c = site.getWorld().spawn(site.clone().add(i - 1, 0, -2), Chicken.class);
                mark(c); c.setInvulnerable(true); c.setAgeLock(true); c.setBreed(false);
                c.customName(Component.text("닭 감사 " + (i + 1) + "호: 꼬끼오", NamedTextColor.GOLD)); c.setCustomNameVisible(true); props.add(c);
            }
        } else if (type == 1) {
            announce("중력 담당자가 퇴근했습니다. 몬스터들이 잠깐 떠오릅니다!");
            for (LivingEntity e : enemies.values()) {
                e.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 50, 0));
                e.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 160, 0));
            }
        } else {
            announce("감자 국세청 속보: 이번 전투의 세금은 감자 0개입니다.");
            if (plugin.presentation() != null) {
                plugin.presentation().ambient(site, Particle.HAPPY_VILLAGER, 20);
                plugin.presentation().ambientSound(site, Sound.ENTITY_VILLAGER_CELEBRATE);
            }
        }
    }
    private void finish(boolean success) {
        if (site == null) return;
        if (plugin.presentation() != null && !stopped) plugin.presentation().result(site, success);
        if (success) {
            int rewarded = 0;
            for (Player p : nearby(site, 64)) {
                if (!party.contains(p.getUniqueId())) continue;
                give(p, new ItemStack(Material.DIAMOND, 2), new ItemStack(Material.EMERALD, 4), suspiciousPotato());
                p.giveExp(150); p.saveData(); rewarded++;
            }
            announce("원정 완료! 현장에 남은 참여자 " + rewarded + "명에게 다이아 2개·에메랄드 4개·수상한 감자·경험치 150을 지급했습니다.");
        } else announce("원정이 종료되었습니다. 현장 몬스터와 닭 감사팀이 퇴근합니다.");
        for (Entity e : enemies.values()) e.remove();
        for (Entity e : props) e.remove();
        enemies.clear(); props.clear(); party.clear(); site = null;
        for (Chunk chunk : tickets) chunk.removePluginChunkTicket(plugin.owner());
        tickets.clear();
    }
    private void give(Player p, ItemStack... items) {
        p.getInventory().addItem(items).values().forEach(item -> {
            Item drop = p.getWorld().dropItem(p.getLocation(), item); drop.setOwner(p.getUniqueId());
        });
    }
    ItemStack suspiciousPotato() {
        ItemStack item = new ItemStack(Material.BAKED_POTATO);
        item.editMeta(meta -> { meta.displayName(Component.text("수상한 감자: 먹어도 출근은 안 함", NamedTextColor.LIGHT_PURPLE));
            meta.lore(List.of(Component.text("먹으면 20초 동안 신속·성급함·야간 투시 중 하나!")));
            meta.getPersistentDataContainer().set(potato, PersistentDataType.BYTE, (byte)1); });
        return item;
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void eat(PlayerItemConsumeEvent e) {
        if (!e.getItem().getItemMeta().getPersistentDataContainer().has(potato, PersistentDataType.BYTE)) return;
        PotionEffectType[] effects = {PotionEffectType.SPEED, PotionEffectType.HASTE, PotionEffectType.NIGHT_VISION};
        e.getPlayer().addPotionEffect(new PotionEffect(effects[random.nextInt(effects.length)], 400, 0));
        tell(e.getPlayer(), "감자가 당신의 노동 의욕을 일시적으로 조작했습니다. 20초간 효과 적용!");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mobDeath(EntityDeathEvent e) {
        if (!marked(e.getEntity())) return;
        e.getDrops().clear(); e.setDroppedExp(0);
        if (enemies.remove(e.getEntity().getUniqueId()) != null && site != null) {
            Player killer = e.getEntity().getKiller();
            if (killer != null && killer.getGameMode() == GameMode.SURVIVAL) party.add(killer.getUniqueId());
            if (enemies.isEmpty()) { if (wave == 3) finish(true); else nextWave = System.currentTimeMillis() + 4000; }
        }
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void damage(EntityDamageEvent e) {
        if (e.getFinalDamage() <= 0) return;
        if (e.getEntity() instanceof Player p) {
            ItemStack[] armor = p.getInventory().getArmorContents();
            for (int i = 0; i < armor.length; i++) {
                if (remaining(armor[i]) < 0) continue;
                final int slot = i;
                spend(armor[i], () -> armor[slot] = null, p);
            }
            p.getInventory().setArmorContents(armor);
        }
        if (e instanceof EntityDamageByEntityEvent hit && hit.getDamager() instanceof Player p && e.getEntity() instanceof LivingEntity && !(e.getEntity() instanceof Player)) {
            ItemStack item = p.getInventory().getItemInMainHand();
            if (item.getType() == Material.WOODEN_SWORD || item.getType() == Material.IRON_SWORD)
                spend(item, () -> p.getInventory().setItemInMainHand(null), p);
            if (site != null && enemies.containsKey(e.getEntity().getUniqueId()) && p.getGameMode() == GameMode.SURVIVAL) party.add(p.getUniqueId());
        }
    }
    @EventHandler(ignoreCancelled = true) public void projectile(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Entity shooter && marked(shooter)) { mark(e.getEntity()); props.add(e.getEntity()); }
    }
    @EventHandler(ignoreCancelled = true) public void egg(EntityDropItemEvent e) { if (marked(e.getEntity())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void exhaustedTool(BlockBreakEvent e) {
        if (remaining(e.getPlayer().getInventory().getItemInMainHand()) == 0) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void mine(BlockBreakEvent e) {
        Player p = e.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        if (p.getGameMode() != GameMode.SURVIVAL || !item.containsEnchantment(Enchantment.EFFICIENCY)) return;
        int left = remaining(item);
        if (left <= 0) return;
        item.editMeta(meta -> meta.getPersistentDataContainer().set(charges, PersistentDataType.INTEGER, left - 1));
        updateLore(item, left - 1);
        // Keep the final tool through vanilla's ore-drop calculation, then remove exhausted copies.
        if (left == 1) Bukkit.getScheduler().runTask(plugin.owner(), () -> {
            Inventory inventory = p.getInventory();
            for (int i = 0; i < inventory.getSize(); i++) if (remaining(inventory.getItem(i)) == 0) inventory.setItem(i, null);
            if (remaining(p.getItemOnCursor()) == 0) p.setItemOnCursor(null);
            tell(p, "급행 도구가 채굴 96회를 마치고 퇴근했습니다!");
        });
    }
    private boolean specialInputs(Inventory inv) {
        // Skip result slots: each workstation's first inputs are slots 0 and 1 (smithing also 2).
        int n = inv instanceof SmithingInventory ? 3 : 2;
        for (int i = 0; i < n; i++) if (remaining(inv.getItem(i)) >= 0) return true;
        return false;
    }
    @EventHandler public void anvil(PrepareAnvilEvent e) { if (specialInputs(e.getInventory())) e.setResult(null); }
    @EventHandler public void grind(PrepareGrindstoneEvent e) { if (specialInputs(e.getInventory())) e.setResult(null); }
    @EventHandler public void smith(PrepareSmithingEvent e) { if (specialInputs(e.getInventory())) e.setResult(null); }
    @EventHandler(ignoreCancelled = true) public void craft(CraftItemEvent e) {
        for (ItemStack item : e.getInventory().getMatrix()) if (remaining(item) >= 0) { e.setCancelled(true); return; }
    }
    @EventHandler(ignoreCancelled = true) public void enchanting(EnchantItemEvent e) { if (remaining(e.getItem()) >= 0) e.setCancelled(true); }
    void close() { stopped = true; ticker.cancel(); supplies.close(); finish(false); }
}
