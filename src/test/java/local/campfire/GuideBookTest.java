package local.campfire;

import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.damage.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GuideBookTest {
    ServerMock server; CampfireRuntime plugin;
    @BeforeEach void setup() { server=MockBukkit.mock(new BookTestServer());server.addSimpleWorld("world");plugin=RuntimeTestHost.load().runtime(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    PlayerMock player() {
        PlayerMock p=new PlayerMock(server,"Reader") { @Override public void saveData() { } };
        server.addPlayer(p);return p;
    }
    int books(PlayerMock p) { return Arrays.stream(p.getInventory().getContents()).filter(plugin.guideBook()::isGuide).mapToInt(ItemStack::getAmount).sum(); }
    @Test void joinGivesExactlyOneManualAndRepeatedCommandsDoNotDuplicateIt() {
        PlayerMock p=player();assertEquals(1,books(p));
        p.performCommand("camp book");p.performCommand("camp book");assertEquals(1,books(p));
        p.getInventory().clear();p.performCommand("camp book");assertEquals(1,books(p));
    }
    @Test void fullInventoryIsNeverOverwrittenAndBookArrivesAfterSpaceIsFreed() {
        PlayerMock p=player();p.getInventory().clear();
        for(int i=0;i<36;i++) p.getInventory().setItem(i,new ItemStack(Material.DIAMOND,64));
        p.performCommand("camp book");assertEquals(0,books(p));
        assertEquals(2304,p.getInventory().all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum());
        p.getInventory().setItem(8,null);plugin.guideBook().ensure(p,false);assertEquals(1,books(p));
    }
    @Test void guideIsKeptOnDeathAndExcludedFromTheDeathChest() {
        PlayerMock p=player();World w=p.getWorld();w.getBlockAt(0,64,0).setType(Material.STONE);p.teleport(new Location(w,.5,65,.5));
        List<ItemStack> drops=new ArrayList<>(List.of(plugin.guideBook().create(),new ItemStack(Material.DIAMOND,3)));
        var death=new PlayerDeathEvent(p,DamageSource.builder(DamageType.GENERIC).build(),drops,0,(String)null);
        Bukkit.getPluginManager().callEvent(death);
        assertEquals(1,death.getItemsToKeep().stream().filter(plugin.guideBook()::isGuide).count());
        assertTrue(death.getDrops().isEmpty());
        var grave=plugin.deathChests().owned(p.getUniqueId()).getFirst();
        assertEquals(1,grave.items.size());assertEquals(Material.DIAMOND,grave.items.getFirst().getType());
    }
    @Test void manualHasClickableContentsAndCoversEveryFeature() {
        BookMeta meta=(BookMeta)plugin.guideBook().create().getItemMeta();
        assertEquals(30,meta.getPageCount());
        assertFalse(meta.page(2).children().isEmpty());
        String text=meta.pages().stream().map(PlainTextComponentSerializer.plainText()::serialize).reduce("",(a,b)->a+"\n"+b);
        for(String term:List.of("원목","위더","철검","효율 VI","사망","재심기","찹트리","엔더 창고","tpaccept","이펙트","/camp book")) assertTrue(text.contains(term),term);
        assertEquals(BookMeta.Generation.TATTERED,meta.getGeneration());
        for(var p:meta.pages()) assertTrue(PlainTextComponentSerializer.plainText().serialize(p).lines().count()<=14,"book page height");
    }
}
