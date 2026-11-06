package local.campfire;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SupplyQueueTest {
    ServerMock server;CampfireRuntime plugin;
    @BeforeEach void setup() {
        server=MockBukkit.mock(new BookTestServer());
        // ItemMock lacks ownership metadata; retain all other entity behavior and supply just that API.
        server.addWorld(new org.mockbukkit.mockbukkit.world.WorldMock(new WorldCreator("world")) {
            @Override public Item dropItem(Location location,ItemStack stack) {
                var item=new org.mockbukkit.mockbukkit.entity.ItemEntityMock(server,UUID.randomUUID(),stack) {
                    UUID owner;
                    @Override public void setOwner(UUID id) { owner=id; }
                    @Override public UUID getOwner() { return owner; }
                };
                item.setLocation(location);server.registerEntity(item);return item;
            }
        });
        plugin=RuntimeTestHost.load().runtime();
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    PlayerMock player(String name) {
        PlayerMock p=new PlayerMock(server,name) { @Override public void saveData() { } };
        server.addPlayer(p);p.setGameMode(GameMode.SURVIVAL);p.teleport(new Location(p.getWorld(),.5,65,.5));return p;
    }
    SupplyQueue queue() { return plugin.extras().supplies(); }
    long accrue(PlayerMock p,int count) {
        long t=queue().nextAt();
        for(int i=0;i<count;i++) { queue().tick(t,List.of(p),l->false);if(i+1<count)t+=120_000; }
        return t;
    }
    @Test void undergroundAccumulatesEveryBeatWithoutSpawningItems() {
        PlayerMock p=player("Miner");accrue(p,5);
        assertEquals(5,queue().pending(p.getUniqueId()));assertEquals(0,queue().inFlight(p.getUniqueId()));
    }
    @Test void surfacingRainsOneParcelPerSecondAndCapsPhysicalItemsAtThree() {
        PlayerMock p=player("Miner");long t=accrue(p,5);
        queue().tick(t+1000,List.of(p),l->true);assertEquals(1,queue().inFlight(p.getUniqueId()));
        queue().tick(t+1500,List.of(p),l->true);assertEquals(1,queue().inFlight(p.getUniqueId()));
        queue().tick(t+2000,List.of(p),l->true);assertEquals(2,queue().inFlight(p.getUniqueId()));
        queue().tick(t+3000,List.of(p),l->true);queue().tick(t+4000,List.of(p),l->true);
        assertEquals(3,queue().inFlight(p.getUniqueId()));assertEquals(5,queue().pending(p.getUniqueId()));
    }
    @Test void onlyCompleteOwnerPickupConsumesOneParcel() {
        PlayerMock p=player("Miner"),other=player("Other");long t=accrue(p,1);
        queue().tick(t+1000,List.of(p),l->true);Item item=queue().drops(p.getUniqueId()).getFirst();
        var wrong=new EntityPickupItemEvent(other,item,0);queue().pickup(wrong);assertTrue(wrong.isCancelled());
        var full=new EntityPickupItemEvent(p,item,1);queue().pickup(full);assertTrue(full.isCancelled());assertEquals(1,queue().pending(p.getUniqueId()));
        var own=new EntityPickupItemEvent(p,item,0);queue().pickup(own);assertFalse(own.isCancelled());
        assertEquals(0,queue().pending(p.getUniqueId()));assertEquals(0,queue().inFlight(p.getUniqueId()));
        var duplicate=new EntityPickupItemEvent(p,item,0);queue().pickup(duplicate);assertTrue(duplicate.isCancelled());
    }
    @Test void reenteringCaveRecallsUncollectedDropsWithoutRerolling() {
        PlayerMock p=player("Miner");long t=accrue(p,1);
        queue().tick(t+1000,List.of(p),l->true);Item first=queue().drops(p.getUniqueId()).getFirst();ItemStack prize=first.getItemStack().clone();
        queue().tick(t+2000,List.of(p),l->false);assertFalse(first.isValid());assertEquals(1,queue().pending(p.getUniqueId()));
        queue().tick(t+3000,List.of(p),l->true);Item again=queue().drops(p.getUniqueId()).getFirst();
        assertTrue(prize.isSimilar(again.getItemStack()));assertNotEquals(first.getUniqueId(),again.getUniqueId());
    }
    @Test void restartRetainsBothUndeliveredAndInFlightParcels() {
        PlayerMock p=player("Miner");long t=accrue(p,4);queue().tick(t+1000,List.of(p),l->true);
        Item old=queue().drops(p.getUniqueId()).getFirst();
        server.getPluginManager().disablePlugin(plugin.owner());assertFalse(old.isValid());
        server.getPluginManager().enablePlugin(plugin.owner());assertTrue(plugin.isEnabled());
        assertEquals(4,queue().pending(p.getUniqueId()));assertEquals(0,queue().inFlight(p.getUniqueId()));
    }
    @Test void damageAndDespawnKeepParcelsAvailableForRedelivery() {
        PlayerMock p=player("Miner");long t=accrue(p,1);queue().tick(t+1000,List.of(p),l->true);
        Item item=queue().drops(p.getUniqueId()).getFirst();
        var damage=new EntityDamageEvent(item,EntityDamageEvent.DamageCause.LAVA,5);queue().damage(damage);assertTrue(damage.isCancelled());
        assertEquals(1,queue().pending(p.getUniqueId()));queue().tick(t+2000,List.of(p),l->true);
        item=queue().drops(p.getUniqueId()).getFirst();var despawn=new ItemDespawnEvent(item,item.getLocation());queue().despawn(despawn);
        assertTrue(despawn.isCancelled());assertEquals(1,queue().pending(p.getUniqueId()));assertEquals(0,queue().inFlight(p.getUniqueId()));
    }
    @Test void hopperAndItemMergingCannotConsumeOrDuplicateAParcel() {
        PlayerMock p=player("Miner");long t=accrue(p,1);queue().tick(t+1000,List.of(p),l->true);Item item=queue().drops(p.getUniqueId()).getFirst();
        var hopper=new InventoryPickupItemEvent(Bukkit.createInventory(null,27),item);queue().hopper(hopper);assertTrue(hopper.isCancelled());
        Item normal=p.getWorld().dropItem(p.getLocation(),new ItemStack(Material.DIAMOND));
        var merge=new ItemMergeEvent(item,normal);queue().merge(merge);assertTrue(merge.isCancelled());assertEquals(1,queue().pending(p.getUniqueId()));
    }
    @Test void emptyServerAndLongGapDoNotGenerateOfflineBackPay() {
        PlayerMock p=player("Miner");long t=queue().nextAt();queue().tick(t,List.of(),l->false);
        queue().tick(t+120_000*100,List.of(p),l->false);
        assertEquals(1,queue().pending(p.getUniqueId()));
    }
    @Test void pausePersistsAndAccumulatesUntilResumeWithoutLosingFlyingParcels() throws Exception {
        PlayerMock p=player("PausedMiner");long t=accrue(p,2);
        queue().tick(t+1000,List.of(p),l->true);assertEquals(1,queue().inFlight(p.getUniqueId()));
        queue().command(p,new String[]{"delivery","pause"});
        assertTrue(queue().paused(p.getUniqueId()));assertEquals(0,queue().inFlight(p.getUniqueId()));
        queue().tick(t+120000,List.of(p),l->true);assertEquals(3,queue().pending(p.getUniqueId()));assertEquals(0,queue().inFlight(p.getUniqueId()));
        queue().close();
        SupplyQueue reloaded=new SupplyQueue(plugin,0,plugin.extras()::supplyItem,(owner,item,rare)->{});
        assertTrue(reloaded.paused(p.getUniqueId()));assertEquals(3,reloaded.pending(p.getUniqueId()));
        reloaded.command(p,new String[]{"delivery","resume"});
        reloaded.tick(System.currentTimeMillis()+1000,List.of(p),l->true);
        assertFalse(reloaded.paused(p.getUniqueId()));assertEquals(1,reloaded.inFlight(p.getUniqueId()));reloaded.close();
    }
}
