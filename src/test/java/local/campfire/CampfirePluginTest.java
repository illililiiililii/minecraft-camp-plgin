package local.campfire;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

class CampfireRuntimeTest {
    ServerMock server;
    CampfireRuntime plugin;

    @BeforeEach void setUp() {
        server = MockBukkit.mock(new BookTestServer());
        server.addSimpleWorld("world");
        plugin = RuntimeTestHost.load().runtime();
        assertTrue(plugin.isEnabled());
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }
    PlayerMock player(String name) {
        PlayerMock p = new PlayerMock(server, name) {
            // MockBukkit does not implement disk-backed player saveData.
            @Override public void saveData() { }
        };
        server.addPlayer(p);
        p.setGameMode(GameMode.SURVIVAL);
        return p;
    }
    YamlConfiguration state() {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "state.yml"));
    }
    void finishLogs(PlayerMock p) {
        p.getInventory().addItem(new ItemStack(Material.OAK_LOG, 128));
        p.performCommand("camp donate");
    }
    @Test void twoPlayersShareProgressAndExcessStaysInInventory() {
        PlayerMock a = player("Alice");
        PlayerMock b = player("Bob");
        a.getInventory().addItem(new ItemStack(Material.OAK_LOG, 64));
        b.getInventory().addItem(new ItemStack(Material.BIRCH_LOG, 80));
        assertEquals(GameMode.SURVIVAL, a.getGameMode());
        a.performCommand("camp donate");
        assertEquals(64, state().getInt("amount"));
        b.performCommand("camp donate");
        assertEquals(1, state().getInt("chapter"));
        assertEquals(0, state().getInt("amount"));
        assertTrue(b.getInventory().containsAtLeast(new ItemStack(Material.BIRCH_LOG), 16));
        assertFalse(b.getInventory().containsAtLeast(new ItemStack(Material.BIRCH_LOG), 17));
    }
    @Test void customItemsAndUnrelatedResourcesArePreserved() {
        PlayerMock p = player("Alice");
        ItemStack special = new ItemStack(Material.OAK_LOG, 32);
        special.editMeta(meta -> meta.displayName(Component.text("소중한 원목")));
        p.getInventory().addItem(special, new ItemStack(Material.DIAMOND, 5), new ItemStack(Material.SPRUCE_LOG, 10));
        p.performCommand("camp donate");
        assertEquals(10, state().getInt("amount"));
        assertTrue(p.getInventory().containsAtLeast(special, 32));
        assertTrue(p.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), 5));
    }
    @Test void rewardsCanOnlyBeClaimedOnce() {
        PlayerMock p = player("Alice");
        finishLogs(p);
        p.performCommand("camp reward");
        p.performCommand("camp reward");
        assertTrue(p.getInventory().containsAtLeast(new ItemStack(Material.BREAD), 16));
        assertFalse(p.getInventory().containsAtLeast(new ItemStack(Material.BREAD), 17));
        assertEquals(0, state().getInt("rewards." + p.getUniqueId()));
    }
    @Test void fullInventoryKeepsRewardPending() {
        PlayerMock p = player("Alice");
        finishLogs(p);
        for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64));
        p.performCommand("camp reward");
        assertEquals(1, state().getInt("rewards." + p.getUniqueId()));
        assertFalse(p.getInventory().contains(Material.BREAD));
        p.getInventory().setItem(0, null);
        p.performCommand("camp reward");
        assertEquals(0, state().getInt("rewards." + p.getUniqueId()));
        assertTrue(p.getInventory().containsAtLeast(new ItemStack(Material.BREAD), 16));
    }
    @Test void lateJoinersDoNotReceivePastRewards() {
        PlayerMock a = player("Alice");
        finishLogs(a);
        PlayerMock b = player("Bob");
        b.performCommand("camp reward");
        assertFalse(b.getInventory().contains(Material.BREAD));
        assertEquals(1, state().getInt("rewards." + a.getUniqueId()));
        assertEquals(0, state().getInt("rewards." + b.getUniqueId()));
    }
    @Test void creativeModeCannotDonate() {
        PlayerMock p = player("Alice");
        p.setGameMode(GameMode.CREATIVE);
        p.getInventory().addItem(new ItemStack(Material.OAK_LOG, 64));
        p.performCommand("camp donate");
        assertEquals(0, state().getInt("amount"));
        assertTrue(p.getInventory().containsAtLeast(new ItemStack(Material.OAK_LOG), 64));
    }
    @Test void onlyOperatorCanSetSharedHome() {
        PlayerMock p = player("Alice");
        World w = p.getWorld();
        w.getBlockAt(0, 64, 0).setType(Material.STONE);
        p.teleport(new Location(w, 0.5, 65, 0.5));
        p.setOp(false);
        p.performCommand("camp sethome");
        assertNull(state().getLocation("home"));
    }
    @Test void savedStateCanReloadWithoutLosingProgressOrRewards() {
        PlayerMock p = player("Alice");
        finishLogs(p);
        p.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 7));
        p.performCommand("camp donate");
        server.getPluginManager().disablePlugin(plugin.owner());
        server.getPluginManager().enablePlugin(plugin.owner());
        assertTrue(plugin.isEnabled());
        assertEquals(1, state().getInt("chapter"));
        assertEquals(7, state().getInt("amount"));
        assertEquals(1, state().getInt("rewards." + p.getUniqueId()));
        p.performCommand("camp reward");
        assertTrue(p.getInventory().containsAtLeast(new ItemStack(Material.BREAD), 16));
    }
    @Test void supplyGearHasAdvertisedEnchantmentsAndBoundedCharges() {
        ItemStack sword = plugin.extras().supplyItem(SupplyRules.Prize.JACKPOT);
        assertTrue(sword.getType().name().endsWith("_SWORD"));
        assertEquals(7, sword.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS));
        assertEquals(-1, plugin.extras().remaining(sword)); assertTrue(sword.getItemMeta().isUnbreakable());
        ItemStack armor = plugin.extras().supplyItem(SupplyRules.Prize.ARMOR);
        assertEquals(6, armor.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.PROTECTION));
        assertEquals(-1, plugin.extras().remaining(armor)); assertTrue(armor.getItemMeta().isUnbreakable());
        assertTrue(plugin.extras().supplyItem(SupplyRules.Prize.DIAMOND).getAmount() >= 1);
    }
    @Test void swordExpiresOnTwelfthSuccessfulHit() {
        PlayerMock p = player("Alice");
        p.getInventory().setItemInMainHand(plugin.extras().supplyItem(SupplyRules.Prize.JACKPOT));
        var zombie = p.getWorld().spawn(p.getLocation(), org.bukkit.entity.Zombie.class);
        for (int i = 0; i < 11; i++) plugin.extras().damage(new org.bukkit.event.entity.EntityDamageByEntityEvent(p, zombie,
            org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1));
        assertEquals(-1, plugin.extras().remaining(p.getInventory().getItemInMainHand()));
        plugin.extras().damage(new org.bukkit.event.entity.EntityDamageByEntityEvent(p, zombie,
            org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1));
        assertFalse(p.getInventory().getItemInMainHand().getType().isAir());
    }
    @Test void armorExpiresOnTwentyFourthDamagingEvent() {
        PlayerMock p = player("Alice");
        p.getInventory().setChestplate(plugin.extras().supplyItem(SupplyRules.Prize.ARMOR));
        for (int i = 0; i < 23; i++) plugin.extras().damage(new org.bukkit.event.entity.EntityDamageEvent(p,
            org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL, 1));
        assertEquals(-1, plugin.extras().remaining(p.getInventory().getChestplate()));
        plugin.extras().damage(new org.bukkit.event.entity.EntityDamageEvent(p,
            org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL, 1));
        assertNotNull(p.getInventory().getChestplate());
    }
    @Test void defaultCommandOpensAReadOnlyFortyFiveSlotMenu() {
        PlayerMock p = player("Alice");
        p.performCommand("camp");
        var inv = p.getOpenInventory().getTopInventory();
        assertEquals(45, inv.getSize());
        assertInstanceOf(CampPresentation.MenuHolder.class, inv.getHolder());
        assertEquals(Material.HOPPER, inv.getItem(12).getType());
        var e = new org.bukkit.event.inventory.InventoryClickEvent(p.getOpenInventory(),
            org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 12,
            org.bukkit.event.inventory.ClickType.NUMBER_KEY, org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP, 0);
        plugin.presentation().click(e);
        assertTrue(e.isCancelled());
        assertEquals(Material.HOPPER, inv.getItem(12).getType());
    }
    @Test void donationClickRunsNextTickAndRapidRepeatedClicksOnlyRunOnce() {
        PlayerMock p = player("Alice");
        p.getInventory().addItem(new ItemStack(Material.OAK_LOG, 16));
        p.performCommand("camp");
        var e = new org.bukkit.event.inventory.InventoryClickEvent(p.getOpenInventory(),
            org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 12,
            org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        plugin.presentation().click(e); plugin.presentation().click(e);
        assertTrue(e.isCancelled());
        assertEquals(0, state().getInt("amount"));
        server.getScheduler().performTicks(2);
        assertEquals(16, state().getInt("amount"));
        assertFalse(p.getInventory().contains(Material.HOPPER));
    }
    @Test void menusBlockDraggingAndBottomInventoryShiftClicks() {
        PlayerMock p = player("Alice"); p.performCommand("camp");
        var click = new org.bukkit.event.inventory.InventoryClickEvent(p.getOpenInventory(),
            org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 45,
            org.bukkit.event.inventory.ClickType.SHIFT_LEFT, org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY);
        plugin.presentation().click(click); assertTrue(click.isCancelled());
        var drag = new org.bukkit.event.inventory.InventoryDragEvent(p.getOpenInventory(), new ItemStack(Material.STONE),
            new ItemStack(Material.STONE, 2), false, java.util.Map.of(1, new ItemStack(Material.STONE)));
        plugin.presentation().drag(drag); assertTrue(drag.isCancelled());
    }
    @Test void normalChestsAreNotInterceptedByTheMenuHandler() {
        PlayerMock p = player("Alice");
        p.openInventory(Bukkit.createInventory(null, 45, "모닥불 원정대"));
        var e = new org.bukkit.event.inventory.InventoryClickEvent(p.getOpenInventory(),
            org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 12,
            org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        plugin.presentation().click(e); assertFalse(e.isCancelled());
    }
    @Test void allFiveEfficiencyToolsHaveLevelSixAndNinetySixMiningCharges() {
        for (Material type : new Material[]{Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE, Material.DIAMOND_SHOVEL, Material.DIAMOND_HOE, Material.SHEARS}) {
            ItemStack item = plugin.extras().efficiencyTool(type);
            assertEquals(type, item.getType());
            assertEquals(6, item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.EFFICIENCY));
            assertEquals(-1, plugin.extras().remaining(item)); assertTrue(item.getItemMeta().isUnbreakable());
        }
    }
    @Test void lastMiningChargePreservesToolForOreDropsThenExpiresNextTick() {
        PlayerMock p = player("Alice");
        p.getInventory().setItemInMainHand(plugin.extras().efficiencyTool(Material.DIAMOND_PICKAXE));
        var event = new org.bukkit.event.block.BlockBreakEvent(p.getWorld().getBlockAt(0, 64, 0), p);
        for (int i = 0; i < 95; i++) plugin.extras().mine(event);
        assertEquals(-1, plugin.extras().remaining(p.getInventory().getItemInMainHand()));
        plugin.extras().mine(event);
        assertEquals(Material.DIAMOND_PICKAXE, p.getInventory().getItemInMainHand().getType());
        assertEquals(-1, plugin.extras().remaining(p.getInventory().getItemInMainHand()));
        plugin.extras().exhaustedTool(event); assertFalse(event.isCancelled());
        server.getScheduler().performTicks(2);
        assertFalse(p.getInventory().getItemInMainHand().getType().isAir());
    }
    @Test void freshServerHasOneMinuteFirstDropAndTwoMinuteInterval() {
        assertTrue(plugin.extras().snapshot().supplySeconds() <= 60);
        assertTrue(plugin.extras().snapshot().supplySeconds() >= 55);
        assertEquals(120, plugin.getConfig().getInt("sky-supply.interval-seconds"));
    }
    @Test void allEquipmentTiersAndMineralsAppearAndNetheriteIsRare() throws Exception {
        var field=AdventureExtras.class.getDeclaredField("random");field.setAccessible(true);
        ((java.util.Random)field.get(plugin.extras())).setSeed(1800);
        java.util.Set<Material> swords=new java.util.HashSet<>(),armor=new java.util.HashSet<>(),tools=new java.util.HashSet<>(),minerals=new java.util.HashSet<>();
        int netherite=0;
        for(int i=0;i<10000;i++) {
            swords.add(plugin.extras().supplyItem(SupplyRules.Prize.WOOD_SWORD).getType());
            armor.add(plugin.extras().supplyItem(SupplyRules.Prize.ARMOR).getType());
            tools.add(plugin.extras().supplyItem(SupplyRules.Prize.TOOLS).getType());
            Material mineral=plugin.extras().supplyItem(SupplyRules.Prize.DIAMOND).getType();minerals.add(mineral);if(mineral==Material.NETHERITE_INGOT)netherite++;
        }
        for(String tier:java.util.List.of("WOODEN","STONE","IRON","GOLDEN","DIAMOND","NETHERITE")) {
            assertTrue(swords.contains(Material.valueOf(tier+"_SWORD")));
            for(String tool:java.util.List.of("PICKAXE","AXE","SHOVEL","HOE")) assertTrue(tools.contains(Material.valueOf(tier+"_"+tool)));
        }
        for(String tier:java.util.List.of("LEATHER","CHAINMAIL","IRON","GOLDEN","DIAMOND","NETHERITE"))
            for(String slot:java.util.List.of("HELMET","CHESTPLATE","LEGGINGS","BOOTS")) assertTrue(armor.contains(Material.valueOf(tier+"_"+slot)));
        assertEquals(java.util.Set.of(Material.IRON_INGOT,Material.GOLD_INGOT,Material.LAPIS_LAZULI,Material.DIAMOND,Material.NETHERITE_INGOT),minerals);
        assertTrue(netherite>0 && netherite<100);
    }
    @Test void legacyChargesAreRemovedAndOrdinaryEquipmentCannotTakeDurabilityDamage() {
        PlayerMock p=player("Durability");ItemStack tool=new ItemStack(Material.DIAMOND_PICKAXE);
        tool.editMeta(meta->meta.getPersistentDataContainer().set(new NamespacedKey(plugin.owner(),"supply_charges"),org.bukkit.persistence.PersistentDataType.INTEGER,1));
        assertEquals(-1,plugin.extras().remaining(tool));
        assertFalse(tool.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin.owner(),"supply_charges")));
        ItemStack sword=new ItemStack(Material.DIAMOND_SWORD);
        var event=new org.bukkit.event.player.PlayerItemDamageEvent(p,sword,3);
        plugin.extras().noDurability(event);assertTrue(event.isCancelled());assertTrue(sword.getItemMeta().isUnbreakable());
    }
}
