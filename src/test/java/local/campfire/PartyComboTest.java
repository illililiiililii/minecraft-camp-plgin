package local.campfire;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PartyComboTest {
    @Test void repeatedSoloKillsCannotBuildATeamChain(){PartyCombo c=new PartyCombo();UUID a=UUID.randomUUID(),w=UUID.randomUUID();for(int i=0;i<10;i++)assertEquals(1,c.hit(a,w,0,0,0,i*1000));}
    @Test void alternatingPlayersBuildAChainAndExpiryResetsIt(){PartyCombo c=new PartyCombo();UUID a=UUID.randomUUID(),b=UUID.randomUUID(),w=UUID.randomUUID();for(int i=0;i<5;i++)assertEquals(i+1,c.hit(i%2==0?a:b,w,0,0,0,i*1000));assertEquals(2,c.participants().size());assertEquals(0,c.count(19_000));assertEquals(1,c.hit(b,w,0,0,0,19_000));}
    @Test void movingFarAwayOrChangingWorldStartsOver(){PartyCombo c=new PartyCombo();UUID a=UUID.randomUUID(),b=UUID.randomUUID(),w=UUID.randomUUID();c.hit(a,w,0,0,0,0);assertEquals(1,c.hit(b,w,49,0,0,1000));assertEquals(1,c.hit(a,UUID.randomUUID(),49,0,0,2000));}
}
