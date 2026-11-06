package local.campfire;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

/** Recompute from current inputs on every take; no cached preview is a transaction. */
final class EnchantExtraction implements Listener {
    private final CampfireRuntime plugin;
    EnchantExtraction(CampfireRuntime plugin) {
        this.plugin=plugin;Bukkit.getPluginManager().registerEvents(this,plugin.owner());
    }
    record Extraction(int bookSlot,int gearSlot,ItemStack book,ItemStack stripped) { }
    static Extraction plan(ItemStack first,ItemStack second) {
        int slot=first!=null && first.getType()==Material.BOOK?0:second!=null && second.getType()==Material.BOOK?1:-1;
        if(slot<0)return null;
        ItemStack gear=slot==0?second:first;
        if(gear==null || gear.getAmount()!=1 || gear.getType().getMaxDurability()==0 || gear.getEnchantments().isEmpty())return null;
        ItemStack book=new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta=(EnchantmentStorageMeta)book.getItemMeta();
        gear.getEnchantments().forEach((enchantment,level)->meta.addStoredEnchant(enchantment,level,true));book.setItemMeta(meta);
        ItemStack stripped=gear.clone();gear.getEnchantments().keySet().forEach(stripped::removeEnchantment);
        return new Extraction(slot,1-slot,book,stripped);
    }
    private void refresh(GrindstoneInventory inv) {
        Extraction result=plan(inv.getItem(0),inv.getItem(1));
        inv.setItem(2,result==null?null:result.book());
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void prepare(PrepareGrindstoneEvent e) {
        Extraction result=plan(e.getInventory().getItem(0),e.getInventory().getItem(1));
        if(result!=null)e.setResult(result.book());
        else if(isBook(e.getInventory().getItem(0))||isBook(e.getInventory().getItem(1)))e.setResult(null);
    }
    private static boolean isBook(ItemStack item) { return item!=null && item.getType()==Material.BOOK; }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST)
    public void click(InventoryClickEvent e) {
        if(!(e.getView().getTopInventory() instanceof GrindstoneInventory inv) || !(e.getWhoClicked() instanceof Player p))return;
        if(e.getAction()==InventoryAction.COLLECT_TO_CURSOR && plan(inv.getItem(0),inv.getItem(1))!=null) { e.setCancelled(true);return; }
        if(e.getRawSlot()==2) {
            Extraction result=plan(inv.getItem(0),inv.getItem(1));
            if(result==null) {
                if(isBook(inv.getItem(0))||isBook(inv.getItem(1)))e.setCancelled(true);
                return;
            }
            e.setCancelled(true);
            if(e.isShiftClick()) {
                if(p.getInventory().firstEmpty()<0)return;
            } else if((e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT) || !p.getItemOnCursor().getType().isAir())return;
            ItemStack books=inv.getItem(result.bookSlot()).clone();books.setAmount(books.getAmount()-1);
            inv.setItem(2,null);inv.setItem(result.bookSlot(),books.getAmount()==0?null:books);inv.setItem(result.gearSlot(),result.stripped());
            if(e.isShiftClick())p.getInventory().addItem(result.book());else p.setItemOnCursor(result.book());
            Bukkit.getScheduler().runTask(plugin.owner(),()->refresh(inv));
            return;
        }
        // Vanilla refuses plain books in grindstone input slots; explicitly admit them.
        if(e.getRawSlot()>=0 && e.getRawSlot()<2 && isBook(p.getItemOnCursor()) && (e.getClick()==ClickType.LEFT||e.getClick()==ClickType.RIGHT)) {
            ItemStack current=inv.getItem(e.getRawSlot());
            if(current!=null && !current.getType().isAir())return;
            e.setCancelled(true);ItemStack cursor=p.getItemOnCursor().clone(),one=cursor.clone();one.setAmount(1);
            inv.setItem(e.getRawSlot(),one);cursor.setAmount(cursor.getAmount()-1);p.setItemOnCursor(cursor.getAmount()==0?null:cursor);
            Bukkit.getScheduler().runTask(plugin.owner(),()->refresh(inv));
        } else if(e.isShiftClick() && e.getClickedInventory()==p.getInventory() && isBook(e.getCurrentItem())) {
            int target=inv.getItem(0)==null||inv.getItem(0).getType().isAir()?0:inv.getItem(1)==null||inv.getItem(1).getType().isAir()?1:-1;
            if(target<0)return;
            e.setCancelled(true);ItemStack stack=e.getCurrentItem().clone(),one=stack.clone();one.setAmount(1);
            inv.setItem(target,one);stack.setAmount(stack.getAmount()-1);e.setCurrentItem(stack.getAmount()==0?null:stack);
            Bukkit.getScheduler().runTask(plugin.owner(),()->refresh(inv));
        }
    }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent e) {
        if(e.getView().getTopInventory() instanceof GrindstoneInventory && e.getRawSlots().contains(2))e.setCancelled(true);
    }
}
