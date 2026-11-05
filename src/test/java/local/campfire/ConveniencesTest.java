package local.campfire;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.damage.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConveniencesTest {
    ServerMock server; CampfireRuntime plugin;
    @BeforeEach void setup() { server=MockBukkit.mock(new BookTestServer()); server.addSimpleWorld("world"); plugin=RuntimeTestHost.load().runtime(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    PlayerMock player(String name) {
        PlayerMock p=new PlayerMock(server,name) { @Override public void saveData() { } @Override public boolean isOnGround() { return true; } };
        server.addPlayer(p); p.setGameMode(GameMode.SURVIVAL); p.setOp(false);
        p.getWorld().getBlockAt(0,64,0).setType(Material.STONE);
        p.teleport(new Location(p.getWorld(),.5,65,.5)); p.getInventory().clear(); return p;
    }
    PlayerDeathEvent death(PlayerMock p,List<ItemStack> drops) {
        return new PlayerDeathEvent(p,DamageSource.builder(DamageType.GENERIC).build(),new ArrayList<>(drops),0,(String)null);
    }
    @Test void deathCapturesMoreThanARegularChestsCapacityAndOnlyClearsDropsAfterSave() {
        PlayerMock p=player("Alice");
        List<ItemStack> items=new ArrayList<>(); for(int i=0;i<41;i++) items.add(new ItemStack(Material.DIAMOND,64));
        var e=death(p,items); plugin.deathChests().death(e);
        assertTrue(e.getDrops().isEmpty());
        var g=plugin.deathChests().owned(p.getUniqueId()).getFirst();
        assertEquals(41,g.items.size()); assertEquals(Material.CHEST,g.location().getBlock().getType());
        assertTrue(new java.io.File(plugin.getDataFolder(),"graves.yml").isFile());
        plugin.deathChests().open(p,g,0); assertEquals(54,p.getOpenInventory().getTopInventory().getSize());
    }
    @Test void fullInventoryKeepsAllDeathItemsUntilSpaceExistsAndClaimCannotRepeat() {
        PlayerMock p=player("Alice"); var e=death(p,List.of(new ItemStack(Material.DIAMOND,12))); plugin.deathChests().death(e);
        var g=plugin.deathChests().owned(p.getUniqueId()).getFirst();
        for(int i=0;i<36;i++) p.getInventory().setItem(i,new ItemStack(Material.COBBLESTONE,64));
        plugin.deathChests().claim(p,g); assertEquals(12,g.items.getFirst().getAmount());
        p.getInventory().clear(); plugin.deathChests().claim(p,g); plugin.deathChests().claim(p,g);
        assertTrue(plugin.deathChests().owned(p.getUniqueId()).isEmpty());
        assertTrue(p.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND),12));
        assertFalse(p.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND),13));
        assertEquals(Material.AIR,g.location().getBlock().getType());
    }
    @Test void strangersCannotTakeOrBreakDeathChests() {
        PlayerMock owner=player("Alice"), stranger=player("Bob");
        plugin.deathChests().death(death(owner,List.of(new ItemStack(Material.DIAMOND,3))));
        var g=plugin.deathChests().owned(owner.getUniqueId()).getFirst();
        plugin.deathChests().claim(stranger,g);
        assertFalse(stranger.getInventory().contains(Material.DIAMOND)); assertEquals(3,g.items.getFirst().getAmount());
        var breakEvent=new org.bukkit.event.block.BlockBreakEvent(g.location().getBlock(),stranger);
        plugin.deathChests().protect(breakEvent); assertTrue(breakEvent.isCancelled());
    }
    @Test void keepInventorySkipsDeathChestAndRestartPreservesCustomGear() {
        PlayerMock p=player("Alice");
        var keep=death(p,List.of(new ItemStack(Material.DIAMOND))); keep.setKeepInventory(true); plugin.deathChests().death(keep);
        assertTrue(plugin.deathChests().owned(p.getUniqueId()).isEmpty()); assertEquals(1,keep.getDrops().size());
        var tool=plugin.extras().efficiencyTool(Material.DIAMOND_AXE);
        plugin.deathChests().death(death(p,List.of(tool)));
        server.getPluginManager().disablePlugin(plugin.owner()); server.getPluginManager().enablePlugin(plugin.owner());
        assertTrue(plugin.isEnabled());
        var g=plugin.deathChests().owned(p.getUniqueId()).getFirst();
        assertEquals(96,plugin.extras().remaining(g.items.getFirst()));
        assertEquals(6,g.items.getFirst().getEnchantmentLevel(org.bukkit.enchantments.Enchantment.EFFICIENCY));
    }
    @Test void sortingPreservesHotbarArmorOffhandAndCustomMetadata() {
        PlayerMock p=player("Alice");
        ItemStack named=new ItemStack(Material.DIAMOND,4); named.editMeta(meta->meta.displayName(net.kyori.adventure.text.Component.text("추억")));
        p.getInventory().setItem(0,new ItemStack(Material.GOLDEN_APPLE,2));
        p.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET)); p.getInventory().setItemInOffHand(new ItemStack(Material.TORCH,8));
        p.getInventory().setItem(9,new ItemStack(Material.DIAMOND,32)); p.getInventory().setItem(10,named);
        p.getInventory().setItem(11,new ItemStack(Material.DIAMOND,16));
        plugin.conveniences().sort(p);
        assertEquals(Material.GOLDEN_APPLE,p.getInventory().getItem(0).getType());
        assertEquals(Material.IRON_HELMET,p.getInventory().getHelmet().getType());
        assertEquals(8,p.getInventory().getItemInOffHand().getAmount());
        assertTrue(p.getInventory().containsAtLeast(named,4));
        assertTrue(p.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND),48));
        assertFalse(p.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND),49));
    }
    Block naturalTree(PlayerMock p) {
        World w=p.getWorld(); w.getChunkAt(0,0).load(); w.getChunkAt(0,-1).load(); w.getBlockAt(3,64,0).setType(Material.DIRT);
        for(int y=65;y<=68;y++) w.getBlockAt(3,y,0).setType(Material.OAK_LOG);
        for(int dx:new int[]{-1,1}) for(int dz:new int[]{-1,1}) {
            Leaves leaf=(Leaves)Bukkit.createBlockData(Material.OAK_LEAVES); leaf.setPersistent(false); w.getBlockAt(3+dx,68,dz).setBlockData(leaf);
        }
        return w.getBlockAt(3,65,0);
    }
    @Test void treeDetectionRequiresNaturalLeavesAndSoilAndHasAHardCap() {
        PlayerMock p=player("Alice"); Block root=naturalTree(p);
        assertEquals(4,plugin.conveniences().tree(root).size());
        plugin.getConfig().set("convenience.tree-max-logs",2);
        assertTrue(plugin.conveniences().tree(root).isEmpty());
    }
    @Test void playerPlacedLogsAreExcludedEvenWhenTheyTouchANaturalTree() {
        PlayerMock p=player("Alice"); Block root=naturalTree(p);
        plugin.conveniences().place(new org.bukkit.event.block.BlockPlaceEvent(root,root.getState(),root.getRelative(org.bukkit.block.BlockFace.DOWN),
            new ItemStack(Material.OAK_LOG),p,true,EquipmentSlot.HAND));
        assertTrue(plugin.conveniences().tree(root).isEmpty());
    }
    @Test void partialRecoveryStoresEveryStackThatDoesNotFit() {
        PlayerMock p=player("Alice"); List<ItemStack> items=new ArrayList<>();
        for(int i=0;i<41;i++) items.add(new ItemStack(Material.DIAMOND,64));
        plugin.deathChests().death(death(p,items)); var g=plugin.deathChests().owned(p.getUniqueId()).getFirst();
        plugin.deathChests().claim(p,g);
        assertEquals(5,g.items.size()); assertEquals(320,g.items.stream().mapToInt(ItemStack::getAmount).sum());
        assertEquals(2304,p.getInventory().all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum());
        p.getInventory().clear(); plugin.deathChests().claim(p,g);
        assertEquals(320,p.getInventory().all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum());
        assertTrue(plugin.deathChests().owned(p.getUniqueId()).isEmpty());
    }
    @Test void teleportRequiresAcceptanceAndMovementCancelsTheWarmup() {
        PlayerMock a=player("Alice"), b=player("Bob");
        plugin.conveniences().request(a,"Bob"); assertEquals(0,plugin.warpSeconds(a));
        plugin.conveniences().accept(b,true); assertTrue(plugin.warpSeconds(a)>0);
        plugin.onMove(new org.bukkit.event.player.PlayerMoveEvent(a,a.getLocation(),a.getLocation().clone().add(1,0,0)));
        assertEquals(0,plugin.warpSeconds(a));
    }
}
