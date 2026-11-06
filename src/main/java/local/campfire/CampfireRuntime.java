package local.campfire;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.file.*;
import java.util.*;

public class CampfireRuntime implements Listener, TabExecutor {
    private final JavaPlugin host;
    private org.bukkit.configuration.file.FileConfiguration runtimeConfig;
    public CampfireRuntime(JavaPlugin host) { this.host=host; }
    public JavaPlugin owner() { return host; }
    public boolean isEnabled() { return host.isEnabled(); }
    public File getDataFolder() { return host.getDataFolder(); }
    public org.bukkit.Server getServer() { return host.getServer(); }
    public java.util.logging.Logger getLogger() { return host.getLogger(); }
    public org.bukkit.command.PluginCommand getCommand(String command) { return host.getCommand(command); }
    public org.bukkit.configuration.file.FileConfiguration getConfig() { if(runtimeConfig==null) reloadConfig();return runtimeConfig; }
    public void reloadConfig() {
        try {
            YamlConfiguration loaded=new YamlConfiguration();
            File file=new File(getDataFolder(),"config.yml");if(file.exists()) loaded.load(file);
            try(var input=CampfireRuntime.class.getResourceAsStream("/config.yml")) {
                if(input!=null) loaded.setDefaults(YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)));
            }
            runtimeConfig=loaded;
        } catch(Exception ex) { throw new IllegalStateException("Could not load config.yml",ex); }
    }
    public void saveConfig() { try { getConfig().save(new File(getDataFolder(),"config.yml")); } catch(Exception ex) { throw new IllegalStateException("Could not save config.yml",ex); } }
    public void saveDefaultConfig() {
        try {
            java.nio.file.Files.createDirectories(getDataFolder().toPath());File file=new File(getDataFolder(),"config.yml");
            if(!file.exists()) try(var input=CampfireRuntime.class.getResourceAsStream("/config.yml")) { if(input==null) throw new java.io.IOException("Missing module config");java.nio.file.Files.copy(input,file.toPath()); }
            reloadConfig();
        } catch(Exception ex) { throw new IllegalStateException("Could not initialize config.yml",ex); }
    }
    private static final String[] TITLES = {"우리의 첫 기지", "철기 시대", "밤의 수호자", "풍성한 식탁", "네더 원정", "반짝이는 약속", "위더 토벌", "엔더 드래곤 토벌"};
    private static final String[] GOALS = {"일반 원목 합계 128개 납품", "철 주괴 64개 납품", "적대 몬스터 40마리 처치", "밀·당근·감자 합계 128개 납품", "블레이즈 막대 12개 납품", "다이아몬드 16개 납품", "위더 1마리 처치", "엔더 드래곤 1마리 처치"};
    private static final Material[] REWARD_ITEMS = {Material.BREAD, Material.COOKED_BEEF, Material.GOLDEN_APPLE, Material.EMERALD, Material.ENDER_PEARL, Material.DIAMOND, Material.NETHERITE_SCRAP, Material.NETHER_STAR};
    private static final int[] REWARD_COUNTS = {16, 16, 3, 8, 4, 4, 2, 1};
    private static final String[] REWARD_NAMES = {"빵 16개", "스테이크 16개", "황금 사과 3개", "에메랄드 8개", "엔더 진주 4개", "다이아몬드 4개", "네더라이트 파편 2개", "네더의 별 1개"};
    private static final Set<Material> LOGS = EnumSet.of(Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG, Material.PALE_OAK_LOG);
    private static final Set<Material> CROPS = EnumSet.of(Material.WHEAT, Material.CARROT, Material.POTATO);
    private static final Set<Material> UNSAFE = EnumSet.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.WITHER_ROSE);
    private Progress progress;
    // Members retain pending rewards while offline. New members start with no past rewards.
    private final Map<UUID, Integer> pending = new LinkedHashMap<>();
    private final Map<UUID, BukkitTask> warps = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> warpEnds = new HashMap<>();
    private final Map<UUID, Long> damagedAt = new HashMap<>();
    private final Map<UUID, String> deathLocations = new HashMap<>();
    private BossBar bar;
    private Location home;
    private boolean loaded;
    private AdventureExtras extras;
    private CampPresentation presentation;
    private DeathChests deathChests;
    private Conveniences conveniences;
    private GuideBook guideBook;
    private FunGames funGames;
    private long nextSupply, nextAdventure;
    private boolean adventureCooldownLoaded;

    boolean hadAdventureCooldown() { return adventureCooldownLoaded; }
    AdventureExtras extras() { return extras; }
    CampPresentation presentation() { return presentation; }
    DeathChests deathChests() { return deathChests; }
    Conveniences conveniences() { return conveniences; }
    GuideBook guideBook() { return guideBook; }
    FunGames funGames() { return funGames; }
    long warpSeconds(Player p) { return Math.max(0, (warpEnds.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis() + 999) / 1000); }
    record MenuState(String mission, String goal, int amount, int target, String reward, int rewards) { }
    MenuState menuState(Player p) {
        int c = progress.chapter();
        return progress.finished() ? new MenuState("모든 목표 완수", "건축과 모험을 자유롭게 이어가세요.", 8, 8, "모든 단계 완료", Integer.bitCount(pending.getOrDefault(p.getUniqueId(), 0)))
            : new MenuState((c + 1) + "/8 " + TITLES[c], GOALS[c], progress.amount(), Progress.TARGETS[c], REWARD_NAMES[c], Integer.bitCount(pending.getOrDefault(p.getUniqueId(), 0)));
    }

    public void onEnable() {
        saveDefaultConfig();
        // One-time migration to the faster supply cadence requested in 1.2.
        YamlConfiguration rawSettings = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
        if (rawSettings.getInt("settings-version", 0) < 2) {
            getConfig().set("sky-supply.interval-seconds", 120);
            getConfig().set("settings-version", 2);
            saveConfig();
        }
        try {
            File file = new File(getDataFolder(), "state.yml");
            YamlConfiguration data = new YamlConfiguration();
            if (file.exists()) data.load(file);
            progress = new Progress(data.getInt("chapter", 0), data.getInt("amount", 0));
            if (data.isConfigurationSection("rewards")) {
                for (String key : data.getConfigurationSection("rewards").getKeys(false)) {
                    int mask = data.getInt("rewards." + key);
                    if (mask < 0 || mask > 255 || (mask >>> progress.chapter()) != 0)
                        throw new IllegalArgumentException("Invalid reward mask");
                    pending.put(UUID.fromString(key), mask);
                }
            }
            home = data.getLocation("home");
            nextSupply = data.getLong("next-supply", 0);
            nextAdventure = data.getLong("next-adventure", 0);
            adventureCooldownLoaded = nextAdventure > 0;
            loaded = true;
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "state.yml could not be loaded. Restore state.yml.bak with the server stopped; the original was NOT overwritten.", ex);
            getServer().getPluginManager().disablePlugin(owner());
            return;
        }
        bar = Bukkit.createBossBar("모닥불 협동 생존", BarColor.YELLOW, BarStyle.SEGMENTED_10);
        getServer().getPluginManager().registerEvents(this, owner());
        if (getConfig().getBoolean("one-player-sleep", true)) {
            for (World w : Bukkit.getWorlds())
                if (w.getEnvironment() == World.Environment.NORMAL)
                    w.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, 1);
        }
        try { deathChests = new DeathChests(this); }
        catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not load graves.yml; original chest data was not overwritten.", ex);
            getServer().getPluginManager().disablePlugin(owner()); return;
        }
        new EnchantExtraction(this);
        conveniences = new Conveniences(this);
        guideBook = new GuideBook(this);
        presentation = new CampPresentation(this);
        for (Player p : Bukkit.getOnlinePlayers()) register(p);
        updateBar();
        try {
            extras = new AdventureExtras(this, nextSupply, nextAdventure, (supply, adventure) -> {
                nextSupply = supply; nextAdventure = adventure; return persist();
            });
            funGames = new FunGames(this);
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not load supplies.yml. Original supply data was not overwritten.", ex);
            getServer().getPluginManager().disablePlugin(owner()); return;
        }
        getLogger().info("CampfireCoop ready: chapter=" + (progress.chapter() + 1));
    }

    public void onDisable() {
        if (guideBook != null) { guideBook.close(); guideBook = null; }
        if (funGames != null) { funGames.close(); funGames = null; }
        if (extras != null) { extras.close(); extras = null; }
        if (deathChests != null) { deathChests.close(); deathChests = null; }
        if (conveniences != null) { conveniences.close(); conveniences = null; }
        if (presentation != null) { presentation.close(); presentation = null; }
        for (BukkitTask task : warps.values()) task.cancel();
        warps.clear();
        warpEnds.clear();
        if (bar != null) bar.removeAll();
        // Every mutation is saved immediately. Never overwrite data after a failed load.
    }

    private boolean persist() {
        if (!loaded) return false;
        YamlConfiguration data = new YamlConfiguration();
        data.set("chapter", progress.chapter());
        data.set("amount", progress.amount());
        data.set("home", home);
        data.set("next-supply", nextSupply);
        data.set("next-adventure", nextAdventure);
        pending.forEach((id, mask) -> data.set("rewards." + id, mask));
        Path target = new File(getDataFolder(), "state.yml").toPath();
        Path temp = target.resolveSibling("state.yml.tmp");
        try {
            data.save(temp.toFile());
            if (Files.exists(target)) Files.copy(target, target.resolveSibling("state.yml.bak"), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (Exception ex) {
            loaded = false;
            getLogger().log(java.util.logging.Level.SEVERE, "Saving failed. CampfireCoop disabled to protect mission data.", ex);
            Bukkit.broadcast(Component.text("[모닥불] 저장 오류로 협동 기능이 중지되었습니다. 서버 콘솔을 확인하세요.", NamedTextColor.RED));
            getServer().getPluginManager().disablePlugin(owner());
            return false;
        }
    }

    private void msg(CommandSender to, String text) {
        to.sendMessage(Component.text("[모닥불] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.WHITE)));
    }

    private void register(Player p) {
        if (pending.putIfAbsent(p.getUniqueId(), 0) == null && !persist()) return;
        bar.addPlayer(p);
        if (guideBook != null) guideBook.ensure(p, false);
        if (presentation != null) presentation.welcome(p);
        msg(p, "친구들과 목표를 완수하세요! /camp help · 안내서 재지급 /camp book");
        status(p);
        if (pending.getOrDefault(p.getUniqueId(), 0) != 0) msg(p, "받지 않은 보상이 있어요. /camp reward");
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { register(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancelWarp(event.getPlayer(), false);
        deathLocations.remove(id);
        damagedAt.remove(id);
        bar.removePlayer(event.getPlayer());
    }

    private void status(CommandSender to) {
        if (progress.finished()) { msg(to, "8개 목표를 모두 완수했습니다! 자유롭게 건축과 모험을 이어가세요."); return; }
        int c = progress.chapter();
        msg(to, "[" + (c + 1) + "/8] " + TITLES[c] + " — " + GOALS[c]);
        msg(to, "진행 " + progress.amount() + "/" + Progress.TARGETS[c] + " | 보상: 각자 " + REWARD_NAMES[c] + " + 경험치 100");
    }

    private void updateBar() {
        if (progress.finished()) {
            bar.setTitle("§6◆ §fCAMPFIRE §8| §a모든 목표 완수!"); bar.setProgress(1); bar.setColor(BarColor.GREEN);
        } else {
            int c = progress.chapter();
            bar.setTitle("§6◆ §f" + TITLES[c] + " §8| §e" + (c + 1) + "/8 §8| §b" + progress.amount() + "/" + Progress.TARGETS[c] + " §8| §f/camp");
            bar.setProgress((double) progress.amount() / Progress.TARGETS[c]);
        }
    }

    private boolean advance(int amount, String contributor) {
        int before = progress.chapter();
        progress.add(amount);
        if (progress.chapter() != before) pending.replaceAll((id, mask) -> mask | (1 << before));
        if (!persist()) return false;
        if (progress.chapter() != before) {
            Bukkit.broadcast(Component.text("[모닥불] " + TITLES[before] + " 완료! 등록된 원정대원은 /camp reward 로 보상을 받으세요.", NamedTextColor.GREEN));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.1f);
                if (presentation != null) presentation.chapter(p, TITLES[before]);
            }
            status(Bukkit.getConsoleSender());
        } else if (contributor != null) {
            Bukkit.broadcast(Component.text("[모닥불] " + contributor + "님이 자원 " + amount + "개를 납품했습니다.", NamedTextColor.YELLOW));
        }
        updateBar();
        return true;
    }

    private boolean accepts(Material type) {
        return switch (progress.chapter()) {
            case 0 -> LOGS.contains(type);
            case 1 -> type == Material.IRON_INGOT;
            case 3 -> CROPS.contains(type);
            case 4 -> type == Material.BLAZE_ROD;
            case 5 -> type == Material.DIAMOND;
            default -> false;
        };
    }

    private void donate(Player p) {
        if (p.getGameMode() != GameMode.SURVIVAL) { msg(p, "생존 모드에서만 납품할 수 있습니다."); return; }
        if (progress.finished()) { status(p); return; }
        ItemStack[] items = p.getInventory().getStorageContents();
        int needed = progress.remaining();
        int taken = 0;
        // Clone before changing inventory; custom named/enchanted/plugin items are never consumed.
        for (int i = 0; i < items.length && taken < needed; i++) {
            ItemStack item = items[i];
            if (item == null || !accepts(item.getType()) || !item.isSimilar(new ItemStack(item.getType()))) continue;
            int n = Math.min(item.getAmount(), needed - taken);
            taken += n;
            items[i] = item.getAmount() == n ? null : item.clone();
            if (items[i] != null) items[i].setAmount(item.getAmount() - n);
        }
        if (taken == 0) { msg(p, "현재 목표에 맞는 일반 아이템이 없어요. /camp status (이름·인챈트가 있는 아이템은 제외)"); return; }
        if (advance(taken, p.getName())) {
            p.getInventory().setStorageContents(items);
            p.saveData();
            if (presentation != null) presentation.pulse(p.getLocation(), Color.fromRGB(98,234,210));
            msg(p, taken + "개 납품 완료! 초과 자원은 그대로 보관됩니다.");
        }
    }

    private void reward(Player p) {
        int mask = pending.getOrDefault(p.getUniqueId(), 0);
        if (mask == 0) { msg(p, "아직 받을 보상이 없어요."); return; }
        int c = Integer.numberOfTrailingZeros(mask);
        Inventory simulated = Bukkit.createInventory(null, 36);
        ItemStack[] original = p.getInventory().getStorageContents();
        for (int i = 0; i < original.length; i++) if (original[i] != null) simulated.setItem(i, original[i].clone());
        if (!simulated.addItem(new ItemStack(REWARD_ITEMS[c], REWARD_COUNTS[c])).isEmpty()) {
            msg(p, "인벤토리 공간이 부족해요. 공간을 비운 뒤 다시 받으세요."); return;
        }
        pending.put(p.getUniqueId(), mask & ~(1 << c));
        if (!persist()) return;
        p.getInventory().setStorageContents(simulated.getContents());
        p.giveExp(100);
        p.saveData();
        msg(p, (c + 1) + "단계 보상 수령: " + REWARD_NAMES[c] + " + 경험치 100");
        if (pending.get(p.getUniqueId()) != 0) msg(p, "다음 보상도 /camp reward 로 받으세요.");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        if (progress.finished()) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.getGameMode() != GameMode.SURVIVAL || !pending.containsKey(killer.getUniqueId())) return;
        int c = progress.chapter();
        boolean counts = (c == 2 && event.getEntity() instanceof Monster && !(event.getEntity() instanceof Wither))
            || (c == 6 && event.getEntityType() == EntityType.WITHER)
            || (c == 7 && event.getEntityType() == EntityType.ENDER_DRAGON);
        if (counts) advance(1, null);
    }

    private boolean safe(Location loc) {
        if (loc.getWorld() == null || loc.getY() <= loc.getWorld().getMinHeight() || loc.getY() + 1 >= loc.getWorld().getMaxHeight()) return false;
        var feet = loc.getBlock();
        var head = feet.getRelative(0, 1, 0);
        var floor = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid()
            && floor.getType().isSolid() && !UNSAFE.contains(feet.getType()) && !UNSAFE.contains(head.getType()) && !UNSAFE.contains(floor.getType());
    }

    private void startWarp(Player p) {
        if (home == null) { msg(p, "기지가 아직 없어요. 운영자가 안전한 곳에서 /camp sethome 을 입력하세요."); return; }
        warpTo(p, home.clone(), "기지");
    }
    void warpTo(Player p, Location target, String label) {
        UUID id = p.getUniqueId();
        if (warps.containsKey(id)) { msg(p, "이미 귀환 준비 중입니다."); return; }
        if (p.isDead() || p.isInsideVehicle() || !p.isOnGround() || p.isGliding()) { msg(p, "땅 위에 서서 귀환하세요."); return; }
        long now = System.currentTimeMillis();
        long remaining = cooldowns.getOrDefault(id, 0L) - now;
        if (remaining > 0) { msg(p, ((remaining + 999) / 1000) + "초 뒤에 다시 귀환할 수 있어요."); return; }
        if (now - damagedAt.getOrDefault(id, 0L) < 10_000) { msg(p, "피해를 받은 뒤 10초 동안은 귀환할 수 없어요."); return; }
        int seconds = Math.clamp(getConfig().getInt("home-warmup-seconds", 8), 1, 60);
        msg(p, seconds + "초 뒤 " + label + "로 이동합니다. 이동하거나 피해를 받으면 취소됩니다.");
        BukkitTask task = Bukkit.getScheduler().runTaskLater(owner(), () -> {
            warps.remove(id);
            warpEnds.remove(id);
            if (!p.isOnline() || p.isDead()) return;
            if (!safe(target)) { msg(p, "도착 위치가 막혔거나 위험해 이동을 취소했습니다."); return; }
            if (p.teleport(target)) {
                cooldowns.put(id, System.currentTimeMillis() + Math.clamp(getConfig().getInt("home-cooldown-seconds", 60), 0, 3600) * 1000L);
                msg(p, label + "에 도착했습니다!");
                if (presentation != null) presentation.home(p);
            }
        }, seconds * 20L);
        warps.put(id, task);
        warpEnds.put(id, System.currentTimeMillis() + seconds * 1000L);
    }

    private void cancelWarp(Player p, boolean tell) {
        BukkitTask task = warps.remove(p.getUniqueId());
        warpEnds.remove(p.getUniqueId());
        if (task != null) { task.cancel(); if (tell) msg(p, "귀환이 취소되었습니다."); }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() != null && (!e.getFrom().getWorld().equals(e.getTo().getWorld()) || e.getFrom().distanceSquared(e.getTo()) > 0.000001)) cancelWarp(e.getPlayer(), true);
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) { cancelWarp(e.getPlayer(), true); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && e.getFinalDamage() > 0) {
            damagedAt.put(p.getUniqueId(), System.currentTimeMillis()); cancelWarp(p, true);
        }
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        cancelWarp(e.getEntity(), false);
        if (getConfig().getBoolean("death-coordinates", true)) {
            Location l = e.getEntity().getLocation();
            String text = "사망 위치: " + l.getWorld().getName() + " / " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
            deathLocations.put(e.getEntity().getUniqueId(), text); msg(e.getEntity(), text);
        }
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        String text = deathLocations.remove(e.getPlayer().getUniqueId());
        if (text != null) Bukkit.getScheduler().runTaskLater(owner(), () -> { if (e.getPlayer().isOnline()) msg(e.getPlayer(), text); }, 20);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? (sender instanceof Player ? "menu" : "status") : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("menu") && sender instanceof Player p) { presentation.open(p); return true; }
        if (sub.equals("utils") && sender instanceof Player p) { presentation.openUtility(p); return true; }
        if (sub.equals("effects") && sender instanceof Player p) { presentation.toggle(p); return true; }
        if (sub.equals("status")) { status(sender); return true; }
        if (sub.equals("adventure")) { extras.command(sender, args); return true; }
        if (sub.equals("help")) {
            msg(sender, "/camp status 목표 보기 | /camp donate 현재 목표 자원 납품");
            msg(sender, "/camp menu 클릭 메뉴 | /camp effects 본인의 이펙트 켜기/끄기");
            msg(sender, "/camp graves 회수 상자 좌표 | /camp utils 편의 기능 메뉴");
            msg(sender, "/camp delivery pause 수령 정지 | /camp delivery resume 재개 (정지 중 적립 유지)");
            msg(sender, "/camp book 모든 기능을 설명하는 안내서 재지급");
            msg(sender, "/camp fun 현상수배·파티 콤보·수상한 낚시 안내");
            msg(sender, "/camp reward 보상 1개 수령 | /camp home 공용 기지 귀환");
            msg(sender, "/camp adventure 원정 정보 | /camp adventure start 친구와 3차 전투 시작");
            msg(sender, "기본 2분마다 접속한 일반 월드 생존자 1명에게 택배 적립! 지상으로 나오면 밀린 택배가 1초 간격으로 떨어집니다.");
            msg(sender, "목표는 순서대로 진행됩니다. 처치 목표는 해당 단계에서 생존 플레이어가 직접 잡아야 인정됩니다.");
            if (sender.hasPermission("campfire.admin")) msg(sender, "운영자: /camp sethome 현재 위치를 공용 기지로 지정");
            return true;
        }
        if (!(sender instanceof Player p)) { msg(sender, "게임 안에서 사용하는 명령어입니다. /camp status, /camp help 가능"); return true; }
        switch (sub) {
            case "delivery", "택배" -> extras.supplies().command(p,args);
            case "fun" -> funGames.status(p);
            case "book" -> guideBook.ensure(p, true);
            case "graves", "grave" -> deathChests.command(p, args);
            case "sort", "craft", "ender", "tree", "tpa", "tpaccept", "tpdeny" -> conveniences.command(p, sub, args);
            case "donate" -> donate(p);
            case "reward" -> reward(p);
            case "home" -> startWarp(p);
            case "sethome" -> {
                if (!p.hasPermission("campfire.admin")) { msg(p, "운영자만 기지를 지정할 수 있어요."); break; }
                Location target = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
                target.setYaw(p.getLocation().getYaw());
                if (!safe(target)) { msg(p, "단단한 바닥 위의 안전하고 비어 있는 곳에 서 주세요."); break; }
                home = target;
                if (persist()) msg(p, "공용 기지를 지정했습니다. 모두 /camp home 으로 돌아올 수 있어요.");
            }
            default -> msg(p, "알 수 없는 명령어입니다. /camp help");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("tpa")) return Bukkit.getOnlinePlayers().stream().map(Player::getName)
            .filter(n -> !n.equals(sender.getName()) && n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("adventure")) {
            List<String> subs = new ArrayList<>(List.of("status", "start"));
            if (sender.hasPermission("campfire.admin")) subs.add("cancel");
            return subs.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delivery")) return List.of("pause", "resume", "status").stream().filter(v->v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>(List.of("delivery", "menu", "book", "fun", "status", "donate", "reward", "home", "adventure", "effects", "graves", "utils", "sort", "craft", "ender", "tree", "tpa", "tpaccept", "tpdeny", "help"));
        if (sender.hasPermission("campfire.admin")) options.add("sethome");
        return options.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
