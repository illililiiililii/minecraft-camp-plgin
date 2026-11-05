package local.campfire;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FunGamesTest {
    ServerMock server;CampfireRuntime plugin;
    @BeforeEach void setup(){server=MockBukkit.mock(new BookTestServer());server.addSimpleWorld("world");plugin=RuntimeTestHost.load().runtime();}
    @AfterEach void cleanup(){MockBukkit.unmock();}
    PlayerMock player(String name){PlayerMock p=new PlayerMock(server,name){@Override public void saveData(){}};server.addPlayer(p);p.setGameMode(GameMode.SURVIVAL);p.teleport(new Location(p.getWorld(),.5,65,.5));return p;}
    Zombie mob(PlayerMock p){return p.getWorld().spawn(p.getLocation().clone().add(2,0,0),Zombie.class);}
    void natural(Zombie mob,int roll,long now){plugin.funGames().considerSpawn(new CreatureSpawnEvent(mob,CreatureSpawnEvent.SpawnReason.NATURAL),roll,now);}
    void kill(Zombie mob,PlayerMock p){((LivingEntityMock)mob).setKiller(p);plugin.funGames().death(new EntityDeathEvent(mob,DamageSource.builder(DamageType.GENERIC).build(),new ArrayList<>(),0));}
    @Test void naturalEliteHasBoundedStatsAndOnlyOneTargetExists(){
        PlayerMock p=player("Alice");Zombie a=mob(p),b=mob(p);long now=System.currentTimeMillis();natural(a,0,now);natural(b,0,now);
        assertTrue(plugin.funGames().isElite(a));assertFalse(plugin.funGames().isElite(b));
        assertTrue(a.getAttribute(Attribute.MAX_HEALTH).getValue()>=40);assertTrue(a.getAttribute(Attribute.MAX_HEALTH).getValue()<=100);
        assertTrue(a.getAttribute(Attribute.MOVEMENT_SPEED).getValue()<=.36);
    }
    @Test void spawnerCannotBecomeAnEliteAndExpiredEliteReturnsToNormal(){
        PlayerMock p=player("Alice");Zombie a=mob(p);long now=System.currentTimeMillis();
        plugin.funGames().considerSpawn(new CreatureSpawnEvent(a,CreatureSpawnEvent.SpawnReason.SPAWNER),0,now);assertFalse(plugin.funGames().isElite(a));
        double base=a.getAttribute(Attribute.MAX_HEALTH).getBaseValue();natural(a,0,now);plugin.funGames().maintain(now+300_001);
        assertFalse(plugin.funGames().isElite(a));assertEquals(base,a.getAttribute(Attribute.MAX_HEALTH).getBaseValue());
    }
    @Test void bountyPaysTheKillerByMailExactlyOnce(){
        PlayerMock p=player("Alice");Zombie a=mob(p);natural(a,0,System.currentTimeMillis());kill(a,p);
        assertEquals(2,plugin.extras().supplies().pending(p.getUniqueId()));kill(a,p);assertEquals(2,plugin.extras().supplies().pending(p.getUniqueId()));
    }
    @Test void fiveAlternatingNaturalKillsGiveBothTeammatesComboBuffs(){
        PlayerMock a=player("Alice"),b=player("Bob");
        for(int i=0;i<5;i++){Zombie z=mob(a);natural(z,99,System.currentTimeMillis());kill(z,i%2==0?a:b);}
        assertTrue(a.hasPotionEffect(PotionEffectType.SPEED));assertTrue(b.hasPotionEffect(PotionEffectType.REGENERATION));
    }
    PlayerFishEvent catchFish(PlayerMock p,PlayerFishEvent.State state){
        Item item=p.getWorld().dropItem(p.getLocation(),new ItemStack(Material.COD));
        FishHookMock hook=new FishHookMock(server,UUID.randomUUID());
        return new PlayerFishEvent(p,item,hook,state);
    }
    @Test void fishingKeepsTheOriginalCatchAndCooldownSurvivesRestart(){
        PlayerMock p=player("Alice");var event=catchFish(p,PlayerFishEvent.State.CAUGHT_FISH);long now=System.currentTimeMillis();
        plugin.funGames().fishing(event,0,0,now);assertEquals(1,plugin.extras().supplies().pending(p.getUniqueId()));
        assertEquals(Material.COD,((Item)event.getCaught()).getItemStack().getType());assertFalse(event.isCancelled());
        plugin.funGames().fishing(event,0,95,now+1000);assertEquals(1,plugin.extras().supplies().pending(p.getUniqueId()));
        server.getPluginManager().disablePlugin(plugin.owner());server.getPluginManager().enablePlugin(plugin.owner());assertTrue(plugin.isEnabled());
        plugin.funGames().fishing(event,0,95,now+1000);assertEquals(1,plugin.extras().supplies().pending(p.getUniqueId()));
        plugin.funGames().fishing(event,0,95,now+91_000);assertEquals(2,plugin.extras().supplies().pending(p.getUniqueId()));
    }
    @Test void creativeFailedAndUnluckyFishingDoNotGenerateBonuses(){
        PlayerMock p=player("Alice");long now=System.currentTimeMillis();
        plugin.funGames().fishing(catchFish(p,PlayerFishEvent.State.CAUGHT_ENTITY),0,95,now);
        plugin.funGames().fishing(catchFish(p,PlayerFishEvent.State.CAUGHT_FISH),20,95,now);
        p.setGameMode(GameMode.CREATIVE);plugin.funGames().fishing(catchFish(p,PlayerFishEvent.State.CAUGHT_FISH),0,95,now);
        assertEquals(0,plugin.extras().supplies().pending(p.getUniqueId()));
    }
}
