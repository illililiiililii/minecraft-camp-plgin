package local.campfire;

import java.util.*;

/** Alternating teammates keep a chain; solo repeated kills never trigger team rewards. */
final class PartyCombo {
    private int count;
    private UUID last;
    private UUID world;
    private double x,y,z;
    private long expires;
    private final Set<UUID> participants=new HashSet<>();
    int hit(UUID killer,UUID world,double x,double y,double z,long now) {
        double distance=(this.x-x)*(this.x-x)+(this.y-y)*(this.y-y)+(this.z-z)*(this.z-z);
        boolean continues=count>0 && now<expires && world.equals(this.world) && !killer.equals(last) && distance<=48*48;
        if(!continues) { count=0;participants.clear(); }
        count=Math.min(1000,count+1);participants.add(killer);last=killer;this.world=world;this.x=x;this.y=y;this.z=z;expires=now+15_000;
        return count;
    }
    int count(long now) { return now<expires?count:0; }
    Set<UUID> participants() { return Set.copyOf(participants); }
    boolean nearby(UUID world,double x,double y,double z,long now) {
        return count(now)>=2 && world.equals(this.world) && (this.x-x)*(this.x-x)+(this.y-y)*(this.y-y)+(this.z-z)*(this.z-z)<=48*48;
    }
}
