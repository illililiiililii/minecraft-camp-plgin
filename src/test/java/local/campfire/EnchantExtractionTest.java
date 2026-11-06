package local.campfire;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class EnchantExtractionTest {
    ServerMock server;CampfireRuntime plugin;EnchantExtraction feature;PlayerMock player;GrindstoneInventory inv;
    @BeforeEach void setup() {
        server=MockBukkit.mock(new BookTestServer());server.addSimpleWorld("world");plugin=RuntimeTestHost.load().runtime();
        feature=new EnchantExtraction(plugin);player=server.addPlayer();player.getInventory().clear();
        inv=(GrindstoneInventory)Bukkit.createInventory(null,InventoryType.GRINDSTONE);player.openInventory(inv);
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    ItemStack gear() {
        ItemStack gear=new ItemStack(Material.DIAMOND_PICKAXE);gear.addUnsafeEnchantment(Enchantment.EFFICIENCY,6);gear.addUnsafeEnchantment(Enchantment.VANISHING_CURSE,1);
        gear.editMeta(meta->{meta.setUnbreakable(true);((Damageable)meta).setDamage(7);});return gear;
    }
    InventoryClickEvent take(ClickType click,InventoryAction action) {
        return new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.RESULT,2,click,action);
    }
    @Test void transfersEveryEnchantAndPreservesGearMetadataInEitherOrder() {
        ItemStack original=gear();var plan=EnchantExtraction.plan(new ItemStack(Material.BOOK,3),original);
        assertEquals(original.getEnchantments(),((EnchantmentStorageMeta)plan.book().getItemMeta()).getStoredEnchants());
        assertTrue(plan.stripped().getEnchantments().isEmpty());assertEquals(2,original.getEnchantments().size());
        assertTrue(plan.stripped().getItemMeta().isUnbreakable());assertEquals(7,((Damageable)plan.stripped().getItemMeta()).getDamage());
        assertEquals(1,EnchantExtraction.plan(original,new ItemStack(Material.BOOK)).bookSlot());
        assertNull(EnchantExtraction.plan(new ItemStack(Material.BOOK),new ItemStack(Material.DIAMOND)));
    }
    @Test void takeConsumesExactlyOneBookAndRepeatedClicksCannotDuplicate() {
        inv.setItem(0,new ItemStack(Material.BOOK,3));inv.setItem(1,gear());
        var event=take(ClickType.LEFT,InventoryAction.PICKUP_ALL);feature.click(event);assertTrue(event.isCancelled());
        assertEquals(2,inv.getItem(0).getAmount());assertTrue(inv.getItem(1).getEnchantments().isEmpty());assertEquals(Material.ENCHANTED_BOOK,player.getItemOnCursor().getType());
        feature.click(take(ClickType.LEFT,InventoryAction.PICKUP_ALL));assertEquals(2,inv.getItem(0).getAmount());
    }
    @Test void occupiedCursorAndNumberKeysDoNotConsumeInputs() {
        inv.setItem(0,new ItemStack(Material.BOOK));inv.setItem(1,gear());player.setItemOnCursor(new ItemStack(Material.DIRT));
        feature.click(take(ClickType.LEFT,InventoryAction.PICKUP_ALL));assertEquals(2,inv.getItem(1).getEnchantments().size());
        player.setItemOnCursor(null);var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.RESULT,2,ClickType.NUMBER_KEY,InventoryAction.HOTBAR_SWAP,0);
        feature.click(event);assertTrue(event.isCancelled());assertEquals(Material.BOOK,inv.getItem(0).getType());
    }
    @Test void shiftTakeChecksSpaceAndDoesNotLoseItemsWhenFull() {
        inv.setItem(0,new ItemStack(Material.BOOK));inv.setItem(1,gear());
        for(int i=0;i<36;i++)player.getInventory().setItem(i,new ItemStack(Material.DIRT,64));
        feature.click(take(ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY));assertFalse(inv.getItem(1).getEnchantments().isEmpty());
        player.getInventory().setItem(0,null);feature.click(take(ClickType.SHIFT_LEFT,InventoryAction.MOVE_TO_OTHER_INVENTORY));
        assertEquals(Material.ENCHANTED_BOOK,player.getInventory().getItem(0).getType());assertTrue(inv.getItem(1).getEnchantments().isEmpty());
    }
    @Test void plainBookCanBeInsertedDespiteVanillaSlotRestriction() {
        inv.setItem(1,gear());player.setItemOnCursor(new ItemStack(Material.BOOK,3));
        var event=new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CRAFTING,0,ClickType.LEFT,InventoryAction.PLACE_ALL);
        feature.click(event);assertTrue(event.isCancelled());assertEquals(1,inv.getItem(0).getAmount());assertEquals(2,player.getItemOnCursor().getAmount());
        server.getScheduler().performTicks(2);assertEquals(Material.ENCHANTED_BOOK,inv.getItem(2).getType());
    }
}
