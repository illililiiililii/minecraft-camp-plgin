package local.campfire;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.meta.ItemMeta;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.inventory.ItemFactoryMock;
import org.mockbukkit.mockbukkit.inventory.meta.BookMetaMock;
import java.util.*;

/** MockBukkit 4.101 lacks Adventure book pages. Supply only that missing test API. */
class BookTestServer extends ServerMock {
    private ItemFactoryMock richFactory;
    @Override public ItemFactory getItemFactory() {
        if(richFactory==null) richFactory=new ItemFactoryMock() {
            @Override public ItemMeta getItemMeta(Material type) { return type==Material.WRITTEN_BOOK ? new RichBook() : super.getItemMeta(type); }
            @Override public ItemMeta asMetaFor(ItemMeta meta,Material type) { return type==Material.WRITTEN_BOOK ? new RichBook(meta) : super.asMetaFor(meta,type); }
        };
        return richFactory;
    }
    static class RichBook extends BookMetaMock {
        private List<Component> rich=new ArrayList<>();
        RichBook() { super(); }
        RichBook(ItemMeta other) { super(other); if(other instanceof RichBook b) rich=new ArrayList<>(b.rich); }
        @Override public Book pages(List<Component> pages) {
            rich=new ArrayList<>(pages);
            super.setPages(pages.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList());
            return this;
        }
        @Override public List<Component> pages() { return List.copyOf(rich); }
        @Override public Component page(int index) { return rich.get(index-1); }
        @Override public RichBook clone() { return new RichBook(this); }
    }
}
