package local.campfire;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.scheduler.*;
import java.time.Duration;
import java.util.*;

/** Vanilla-client presentation. Every animation has a finite lifetime and local audience. */
final class CampPresentation implements Listener {
    static final TextColor GOLD = TextColor.color(0xffc66d), TEAL = TextColor.color(0x62ead2), VIOLET = TextColor.color(0xc99cff);
    private final CampfireRuntime plugin;
    private final BossBar expedition = Bukkit.createBossBar("원정 대기", BarColor.BLUE, BarStyle.SEGMENTED_10);
    private final Set<UUID> quiet = new HashSet<>(), clicked = new HashSet<>();
    private final Map<UUID,Long> supplyTitles = new HashMap<>();
    private final Set<BukkitTask> animations = new HashSet<>();
    private final BukkitTask ticker;

    CampPresentation(CampfireRuntime plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin.owner());
        ticker = Bukkit.getScheduler().runTaskTimer(plugin.owner(), this::tick, 20, 20);
    }
    static Component text(String value, TextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }
    static Component heading(String value, TextColor color) { return text(value, color).decorate(TextDecoration.BOLD); }
    static String clock(long seconds) {
        if (seconds <= 0) return "준비 완료";
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    static String meter(int amount, int target) {
        int filled = target <= 0 ? 10 : Math.clamp((int)((long)amount * 10 / target), 0, 10);
        return "▰".repeat(filled) + "▱".repeat(10 - filled);
    }
    final class MenuHolder implements InventoryHolder {
        final UUID owner;
        final boolean utility;
        final Inventory inventory;
        MenuHolder(Player p, boolean utility) {
            owner = p.getUniqueId();
            this.utility = utility;
            inventory = Bukkit.createInventory(this, 45, heading("◆ 모닥불 원정대", GOLD).append(text("  |  CAMPFIRE", TEAL)));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    void open(Player p) {
        MenuHolder holder = new MenuHolder(p, false);
        fill(holder.inventory, p);
        p.openInventory(holder.inventory);
        if (soundEnabled(p)) p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .45f, 1.3f);
    }
    void openUtility(Player p) {
        MenuHolder holder = new MenuHolder(p, true); fill(holder.inventory,p); p.openInventory(holder.inventory);
    }
    private ItemStack icon(Material material, String title, TextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(heading(title, color));
            meta.lore(Arrays.stream(lore).map(line -> text(line, NamedTextColor.GRAY)).toList());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        });
        return item;
    }
    private void fill(Inventory inv, Player p) {
        var s = plugin.menuState(p);
        var a = plugin.extras().snapshot();
        ItemStack pane = icon(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK);
        for (int i = 0; i < 45; i++) inv.setItem(i, pane);
        for (int i : new int[]{0,8,36,44}) inv.setItem(i, icon(Material.CYAN_STAINED_GLASS_PANE, " ", TEAL));
        inv.setItem(24,icon(Material.FISHING_ROD,"수상한 놀이",VIOLET,"현상수배 정예몹 · 파티 콤보 · 낚시 보너스","클릭하여 현재 현상수배와 규칙 확인"));
        if (inv.getHolder() instanceof MenuHolder h && h.utility) {
            inv.setItem(4,icon(Material.COMPASS,"원정대 편의 본부",GOLD,"기능을 선택하세요."));
            inv.setItem(10,icon(Material.CRAFTING_TABLE,"휴대용 제작대",TEAL,"어디서든 제작대 열기"));
            inv.setItem(12,icon(Material.HOPPER,"인벤토리 정리",TEAL,"핫바·갑옷·보조손은 보존","나머지 아이템을 합치고 종류별 정리"));
            inv.setItem(14,icon(Material.ENDER_CHEST,"엔더 창고",VIOLET,"엔더 상자 아이템을 지니고 있어야 합니다."));
            inv.setItem(16,icon(Material.IRON_AXE,"찹트리 켜기 / 끄기",GOLD,"웅크린 채 도끼로 자연 나무 벌목","기본 최대 64원목 · 내구도 정상 소모"));
            inv.setItem(22,icon(Material.WRITTEN_BOOK,"서버 안내서",GOLD,"모든 기능을 설명하는 책 한 권 지급","이미 있으면 추가 지급하지 않습니다."));
            inv.setItem(28,icon(Material.ENDER_PEARL,"친구에게 이동 요청",VIOLET,"/camp tpa 친구닉네임","상대 수락 후 대기 · 이동/피해 시 취소"));
            inv.setItem(30,icon(Material.LIME_DYE,"텔레포트 수락",TEAL,"/camp tpaccept"));
            inv.setItem(32,icon(Material.RED_DYE,"텔레포트 거절",GOLD,"/camp tpdeny"));
            inv.setItem(34,icon(Material.CHEST,"내 회수 상자",GOLD,"사망 장비와 인벤토리를 안전한 곳에 보관","클릭하면 좌표 목록 표시"));
            inv.setItem(40,icon(Material.ARROW,"원정 본부로",TEAL,"메인 메뉴로 돌아가기"));
            return;
        }
        inv.setItem(4, icon(Material.CAMPFIRE, "CAMPFIRE  /  원정 본부", GOLD,
            "친구와 목표를 깨고, 수상한 원정에 도전하세요.", "진행 정보는 1초마다 갱신됩니다."));
        inv.setItem(10, icon(Material.WRITABLE_BOOK, "01  /  " + s.mission(), GOLD,
            s.goal(), meter(s.amount(), s.target()) + "  " + s.amount() + "/" + s.target(), "클릭하여 자세히 보기"));
        inv.setItem(12, icon(Material.HOPPER, "02  /  자원 납품", TEAL,
            "현재 목표에 필요한 자원만 납품합니다.", "이름 붙은 아이템과 초과 수량은 보관됩니다.", "클릭하면 즉시 납품"));
        inv.setItem(14, icon(s.rewards() > 0 ? Material.CHEST_MINECART : Material.CHEST, "03  /  보상 " + s.rewards() + "개 대기", GOLD,
            "현재 단계 보상: " + s.reward(), "완료한 단계의 보상을 1개씩 받습니다.", "클릭하여 수령"));
        inv.setItem(16, icon(Material.ENDER_PEARL, "04  /  기지 귀환", VIOLET,
            "준비 중 이동하거나 피해를 받으면 취소됩니다.", "클릭하여 귀환 준비"));
        inv.setItem(28, icon(Material.IRON_SWORD, "원정 출발", VIOLET,
            "친구와 넓은 지상에 모여 3차 전투에 도전!", a.active() ? "진행 중: " + a.name() : "재사용: " + clock(a.cooldownSeconds()), "클릭하여 시작 요청"));
        inv.setItem(30, icon(Material.COMPASS, "원정 레이더", TEAL,
            a.active() ? a.name() : "진행 중인 원정이 없습니다.",
            a.active() ? "좌표 " + a.x() + ", " + a.y() + ", " + a.z() + "  |  " + a.wave() + "/3" : "출발 버튼을 눌러 도전하세요.",
            "클릭하여 현황 확인"));
        inv.setItem(32, icon(Material.ENDER_CHEST, "하늘 택배", GOLD,
            plugin.getConfig().getBoolean("sky-supply.enabled", true) ? "다음 보급: " + clock(a.supplySeconds()) : "하늘 보급이 꺼져 있습니다.",
            "내 미수령 택배 " + plugin.extras().supplies().pending(p.getUniqueId()) + "개 · 낙하 중 " + plugin.extras().supplies().inFlight(p.getUniqueId()) + "개",
            "지하에서도 적립 · 지상에서 1초마다 연속 배송", "클릭하여 보급 안내"));
        inv.setItem(34, icon(quiet.contains(p.getUniqueId()) ? Material.GRAY_DYE : Material.AMETHYST_SHARD, "이펙트 " + (quiet.contains(p.getUniqueId()) ? "조용히" : "표준"), TEAL,
            "클릭하여 본인의 파티클·알림 소리를 켜거나 끕니다.", "보스바와 전투 정보는 계속 표시됩니다."));
        inv.setItem(40, icon(Material.BARRIER, "메뉴 닫기", NamedTextColor.RED, "전투 준비 완료."));
        inv.setItem(38, icon(Material.CHEST,"내 회수 상자",GOLD,"사망 아이템 보관함 좌표 확인"));
        inv.setItem(42, icon(Material.CRAFTING_TABLE,"편의 기능",TEAL,"제작대 · 정리 · 엔더 창고 · 찹트리 · 친구 이동"));
        inv.setItem(22,icon(Material.WRITTEN_BOOK,"서버 안내서",GOLD,"목표·원정·보급·편의 기능 모두 설명","클릭하여 책 재지급"));
    }
    private boolean ours(Inventory inv) { return inv != null && inv.getHolder() instanceof CampPresentation.MenuHolder; }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent e) {
        if (!ours(e.getView().getTopInventory())) return;
        e.setCancelled(true); // Also blocks shift-click, hotbar swaps and collect-to-cursor from the bottom inventory.
        if (!(e.getWhoClicked() instanceof Player p) || !(e.getView().getTopInventory().getHolder() instanceof MenuHolder h)
            || !h.owner.equals(p.getUniqueId()) || e.getRawSlot() < 0 || e.getRawSlot() >= 45
            || !(e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT)) return;
        int slot = e.getRawSlot();
        String command = h.utility ? switch(slot) {
            case 10 -> "camp craft"; case 12 -> "camp sort"; case 14 -> "camp ender"; case 16 -> "camp tree";
            case 28 -> "camp tpa"; case 30 -> "camp tpaccept"; case 32 -> "camp tpdeny"; case 34 -> "camp graves";
            case 22 -> "camp book";
            case 24 -> "camp fun";
            case 40 -> "camp menu"; default -> null;
        } : switch (slot) {
            case 10 -> "camp status"; case 12 -> "camp donate"; case 14 -> "camp reward";
            case 16 -> "camp home"; case 28 -> "camp adventure start"; case 30 -> "camp adventure";
            case 38 -> "camp graves"; case 42 -> "camp utils";
            case 22 -> "camp book";
            case 24 -> "camp fun";
            default -> null;
        };
        if (command == null && slot != 32 && slot != 34 && slot != 40) return;
        if (!clicked.add(p.getUniqueId())) return;
        // Bukkit requires changes to the open view and player inventory to happen after the click event.
        Bukkit.getScheduler().runTask(plugin.owner(), () -> {
            clicked.remove(p.getUniqueId());
            if (!p.isOnline() || p.getOpenInventory().getTopInventory() != h.inventory) return;
            if (slot == 34 && !h.utility) { toggle(p); fill(h.inventory, p); return; }
            p.closeInventory();
            if (command != null) p.performCommand(command);
            else if (slot == 32) p.sendMessage(heading("◆ 하늘 택배  ", GOLD).append(text("지하에서도 2분마다 서버 전체 1명에게 적립됩니다. 내 미수령 " + plugin.extras().supplies().pending(p.getUniqueId()) + "개. 지상에 나오면 1초 간격으로 배송하며, 주워야 수령 완료됩니다.", NamedTextColor.WHITE)));
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent e) {
        if (ours(e.getView().getTopInventory())) e.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        quiet.remove(e.getPlayer().getUniqueId()); clicked.remove(e.getPlayer().getUniqueId()); expedition.removePlayer(e.getPlayer());
        supplyTitles.remove(e.getPlayer().getUniqueId());
    }
    void toggle(Player p) {
        if (!quiet.add(p.getUniqueId())) quiet.remove(p.getUniqueId());
        p.sendMessage(text("◆ 이펙트: " + (quiet.contains(p.getUniqueId()) ? "조용히" : "표준") + " (이번 접속 동안 적용)", TEAL));
    }
    private boolean soundEnabled(Player p) { return plugin.getConfig().getBoolean("presentation.effects", true) && !quiet.contains(p.getUniqueId()); }
    private boolean low() { return plugin.getConfig().getBoolean("presentation.low-particles", false); }
    private List<Player> local(Location at, double radius) {
        return Bukkit.getOnlinePlayers().stream().filter(p -> p.getWorld().equals(at.getWorld())
            && p.getLocation().distanceSquared(at) <= radius * radius).map(p -> (Player)p).toList();
    }
    private void title(Player p, String main, String subtitle, TextColor color) {
        if (!plugin.getConfig().getBoolean("presentation.titles", true)) return;
        p.showTitle(Title.title(heading(main, color), text(subtitle, NamedTextColor.WHITE),
            Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400))));
    }
    void welcome(Player p) { title(p, "CAMPFIRE", "함께 떠나는 수상한 모험  ·  /camp", GOLD); }
    private void tick() {
        if (plugin.extras() == null) return;
        var a = plugin.extras().snapshot();
        if (a.active()) {
            expedition.setVisible(true);
            expedition.setColor(a.wave() == 3 ? BarColor.PURPLE : BarColor.BLUE);
            expedition.setTitle("§b◆ §f" + a.name() + "  §8|  §e" + a.wave() + "/3  §8|  §f적 " + a.enemies() + "  §8|  §b" + clock(a.seconds()));
            expedition.setProgress(a.maxHealth() <= 0 ? 1 : Math.clamp(a.health() / a.maxHealth(), 0, 1));
        } else { expedition.removeAll(); expedition.setVisible(false); }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (a.active() && p.getWorld().getUID().equals(a.world()) && p.getLocation().distanceSquared(new Location(p.getWorld(), a.x(), a.y(), a.z())) <= 80 * 80) expedition.addPlayer(p);
            else expedition.removePlayer(p);
            if (ours(p.getOpenInventory().getTopInventory())) fill(p.getOpenInventory().getTopInventory(), p);
            if (!plugin.getConfig().getBoolean("presentation.hud", true)) continue;
            long warp = plugin.warpSeconds(p);
            if (warp > 0) {
                p.sendActionBar(heading("◆ 기지 귀환  ", VIOLET).append(text(warp + "초  ·  움직이지 마세요", NamedTextColor.WHITE)));
                ring(p.getLocation().clone().add(0, .1, 0), 1.0, Color.fromRGB(201,156,255));
            } else if (a.active() && expedition.getPlayers().contains(p)) {
                p.sendActionBar(heading("◆ 원정 진행  ", TEAL).append(text("WAVE " + a.wave() + "  ·  적 " + a.enemies() + "  ·  " + clock(a.seconds()), NamedTextColor.WHITE)));
            } else {
                Component combo = plugin.funGames()==null?null:plugin.funGames().hud(p,System.currentTimeMillis());
                if(combo!=null) { p.sendActionBar(combo);continue; }
                String supply = plugin.getConfig().getBoolean("sky-supply.enabled", true) ? "택배 " + plugin.extras().supplies().pending(p.getUniqueId()) + "개 · 적립 " + clock(a.supplySeconds()) : "택배 OFF";
                p.sendActionBar(text("◆ " + supply, GOLD).append(text("   |   보상 " + plugin.menuState(p).rewards() + "   |   /camp", TEAL)));
            }
        }
    }
    private void ring(Location center, double radius, Color color) {
        if (!plugin.getConfig().getBoolean("presentation.effects", true)) return;
        int points = low() ? 12 : 24;
        List<Player> viewers = local(center, 40).stream().filter(p -> !quiet.contains(p.getUniqueId())).toList();
        if (viewers.isEmpty()) return;
        Particle.DustOptions dust = new Particle.DustOptions(color, .9f);
        for (int i = 0; i < points; i++) {
            double angle = i * Math.PI * 2 / points;
            Location point = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            for (Player p : viewers) p.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
        }
    }
    void ambient(Location at, Particle particle, int count) {
        for (Player p : local(at, 40)) if (soundEnabled(p)) p.spawnParticle(particle, at, low() ? Math.max(1, count / 3) : count, 1.5, 1, 1.5, .01);
    }
    void ambientSound(Location at, Sound sound) {
        for (Player p : local(at, 40)) if (soundEnabled(p)) p.playSound(at, sound, .5f, .8f);
    }
    void pulse(Location at, Color color) {
        if (!plugin.getConfig().getBoolean("presentation.effects", true)) return;
        new BukkitRunnable() {
            int frame;
            BukkitTask task;
            void begin() { task = runTaskTimer(plugin.owner(), 0, 3); animations.add(task); }
            @Override public void run() {
                if (++frame > 6) { animations.remove(task); cancel(); return; }
                ring(at.clone().add(0, .15, 0), .5 + frame * .4, color);
            }
        }.begin();
    }
    void supplied(Player p, Item item, boolean rare) {
        long now = System.currentTimeMillis();
        if (rare || now - supplyTitles.getOrDefault(p.getUniqueId(),0L) >= 5000) {
            supplyTitles.put(p.getUniqueId(),now);
            title(p, rare ? "RARE DROP" : "SKY SUPPLY", "밀린 택배가 도착합니다 · 머리 위 확인", rare ? VIOLET : TEAL);
        }
        if (soundEnabled(p)) p.playSound(p.getLocation(), rare ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_BEACON_ACTIVATE, .45f, 1.2f);
        if (!plugin.getConfig().getBoolean("presentation.effects", true)) return;
        new BukkitRunnable() {
            int frame;
            boolean landed;
            BukkitTask task;
            void begin() { task = runTaskTimer(plugin.owner(), 0, 2); animations.add(task); }
            @Override public void run() {
                if (++frame > 40 || !item.isValid()) { animations.remove(task); cancel(); return; }
                Location at = item.getLocation();
                for (Player viewer : local(at, 40)) if (soundEnabled(viewer)) viewer.spawnParticle(Particle.END_ROD, at, low() ? 1 : 3, .1, .15, .1, .01);
                if (!landed && item.isOnGround()) { landed = true; pulse(at, rare ? Color.fromRGB(201,156,255) : Color.fromRGB(98,234,210)); }
            }
        }.begin();
    }
    void wave(Location at, int number) {
        for (Player p : local(at, 80)) {
            title(p, number == 3 ? "BOSS INBOUND" : "WAVE 0" + number, number == 3 ? "마지막 상대가 출근했습니다" : "원정대, 전투 준비", number == 3 ? VIOLET : TEAL);
            if (soundEnabled(p)) p.playSound(p.getLocation(), number == 3 ? Sound.ENTITY_WITHER_SPAWN : Sound.BLOCK_BEACON_ACTIVATE, .35f, number == 3 ? 1.3f : 1.6f);
        }
        pulse(at, number == 3 ? Color.fromRGB(201,156,255) : Color.fromRGB(98,234,210));
    }
    void countdown(Location at, int seconds) {
        for (Player p : local(at, 80)) {
            title(p, String.valueOf(seconds), "원정 시작까지", GOLD);
            if (soundEnabled(p)) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, .35f, 1.1f + (3 - seconds) * .2f);
        }
    }
    void result(Location at, boolean success) {
        for (Player p : local(at, 80)) {
            title(p, success ? "EXPEDITION CLEAR" : "원정 종료", success ? "원정대의 또 다른 승리" : "장비를 정비하고 다시 도전하세요", success ? GOLD : VIOLET);
            if (success && soundEnabled(p)) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, .5f, 1f);
        }
        expedition.removeAll(); expedition.setVisible(false);
        if (success) pulse(at, Color.fromRGB(255,198,109));
    }
    void chapter(Player p, String name) { title(p, "CHAPTER CLEAR", name + "  ·  보상이 도착했습니다", GOLD); }
    void home(Player p) {
        pulse(p.getLocation(), Color.fromRGB(201,156,255));
        if (soundEnabled(p)) p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, .45f, 1.3f);
    }
    void close() {
        ticker.cancel(); for (BukkitTask task : animations) task.cancel(); animations.clear();
        expedition.removeAll();
        for (Player p : Bukkit.getOnlinePlayers()) if (ours(p.getOpenInventory().getTopInventory())) p.closeInventory();
    }
}
