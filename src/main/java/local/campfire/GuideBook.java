package local.campfire;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

/** A small, readable written-book manual; pages use dark ink on vanilla parchment. */
final class GuideBook implements Listener {
    private static final int EDITION=4;
    private static final TextColor INK=TextColor.color(0x252b30), ACCENT=TextColor.color(0x00675f), LINK=TextColor.color(0x245c9c);
    private final CampfireRuntime plugin;
    private final NamespacedKey key;
    private final BukkitTask retry;
    GuideBook(CampfireRuntime plugin) {
        this.plugin=plugin; key=new NamespacedKey(plugin.owner(),"guide_book");
        Bukkit.getPluginManager().registerEvents(this,plugin.owner());
        retry=Bukkit.getScheduler().runTaskTimer(plugin.owner(),()->Bukkit.getOnlinePlayers().forEach(p->ensure(p,false)),600,600);
    }
    boolean isGuide(ItemStack item) {
        return item!=null && item.getType()==Material.WRITTEN_BOOK && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(key,PersistentDataType.INTEGER);
    }
    private static Component plain(String s,TextColor c) { return Component.text(s,c).decoration(TextDecoration.ITALIC,false); }
    private static Component jump(String label,int page) {
        return plain(label,LINK).decorate(TextDecoration.UNDERLINED).clickEvent(ClickEvent.changePage(page));
    }
    private static Component command(String label,String command) {
        return plain(label,LINK).decorate(TextDecoration.UNDERLINED).clickEvent(ClickEvent.runCommand(command));
    }
    private static Component page(String heading,String body) {
        return plain(heading,ACCENT).decorate(TextDecoration.BOLD).append(plain("\n\n"+body,INK));
    }
    private static Component action(String heading,String body,String label,String command) {
        return page(heading,body).append(plain("\n\n",INK)).append(command(label,command));
    }
    ItemStack create() {
        ItemStack book=new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta=(BookMeta)Bukkit.getItemFactory().getItemMeta(Material.WRITTEN_BOOK);
        meta.setTitle("모닥불 원정대 안내서"); meta.setAuthor("모닥불 원정 본부"); meta.setGeneration(BookMeta.Generation.TATTERED);
        meta.displayName(CampPresentation.heading("모닥불 원정대 · 서버 안내서",CampPresentation.GOLD));
        meta.lore(List.of(CampPresentation.text("모든 기능과 명령어 · 클릭형 목차",CampPresentation.TEAL),
            CampPresentation.text("사망 시 유지 · /camp book 재지급",NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(key,PersistentDataType.INTEGER,EDITION);
        List<Component> pages=new ArrayList<>();
        pages.add(action("모닥불 원정대","3~4인 협동 생존\n\n친구와 목표를 깨고\n수상한 원정을 떠나요.\n\n이 책은 사망해도\n인벤토리에 남습니다.\n잃으면 /camp book","[ 원정 본부 열기 ]","/camp menu"));
        pages.add(page("클릭형 목차","밑줄을 누르면 이동!\n\n")
            .append(jump("공용 목표·보상",4)).append(plain("\n",INK)).append(jump("귀환·하늘 보급",9)).append(plain("\n",INK))
            .append(jump("효율 VI 도구",12)).append(plain("\n",INK)).append(jump("원정·황당한 사건",13)).append(plain("\n",INK))
            .append(jump("사망 회수 상자",16)).append(plain("\n",INK)).append(jump("찹트리·편의 기능",18)).append(plain("\n",INK))
            .append(jump("친구 이동·화면",22)).append(plain("\n",INK)).append(jump("명령어·운영자",24)));
        pages.add(action("처음 시작하기","난이도 보통 · 최대 4명\nPvP는 꺼져 있습니다.\n1명만 자도 밤이 돼요.\n아니, 아침이 됩니다.\n\n/camp 로 메뉴 열기\n친구와 자원 나누기\n운영자가 기지를 지정\n이후 공동 목표 진행!","[ 현재 목표 보기 ]","/camp status"));
        pages.add(page("공용 목표 1~4","① 원목 합계 128개\n② 철 주괴 64개\n③ 적대 몬스터 40마리\n④ 농작물 합계 128개\n\n농작물은 밀·당근·감자.\n자원은 미리 모아도 돼요.\n처치는 활성 단계에서!"));
        pages.add(page("공용 목표 5~8","⑤ 블레이즈 막대 12개\n⑥ 다이아몬드 16개\n⑦ 위더 1마리\n⑧ 엔더 드래곤 1마리\n\n보스는 생존 플레이어가\n직접 마무리해 주세요.\n침대·함정은 누락될 수\n있으니 무기를 써요."));
        pages.add(action("개인 보상 1~4","① 빵 16개\n② 스테이크 16개\n③ 황금 사과 3개\n④ 에메랄드 8개\n\n단계마다 각자 지급!\n각 단계 경험치 100도\n함께 받습니다.","[ 보상 하나 받기 ]","/camp reward"));
        pages.add(action("개인 보상 5~8","⑤ 엔더 진주 4개\n⑥ 다이아몬드 4개\n⑦ 네더라이트 파편 2개\n⑧ 네더의 별 1개\n\n보상은 단계별 한 번.\n공간이 부족하면 보관.\n명령을 반복해 수령!","[ 보상 하나 받기 ]","/camp reward"));
        pages.add(action("납품과 보상 규칙","납품은 생존 모드에서.\n필요 수량만 가져가요.\n이름 붙은 템은 보존!\n원목은 일반 원목 9종.\n\n접속 등록 후 완료한\n목표는 오프라인도 적립.\n늦게 온 사람에게 과거\n보상은 소급하지 않아요.","[ 현재 자원 납품 ]","/camp donate"));
        pages.add(action("공용 기지 귀환","운영자가 지정한 기지로\n8초 대기 후 돌아갑니다.\n이동·피해를 받으면 취소.\n피해 후 10초는 불가.\n성공 후 재사용 60초.\n\n도착 지점이 막히거나\n위험하면 취소됩니다.","[ 기지로 돌아가기 ]","/camp home"));
        pages.add(page("하늘 택배","처음 1분, 이후 2분마다\n서버 전체 한 명에게!\n지하에 있어도 적립돼요.\n\n다이아 1개 30%\n날카 VII 나무검 20%\n날카 V 철검 16%\n효율 VI 도구 24%\n보호 VI 흉갑 8%\n날카 VII 철검 2%").append(plain("\n",INK)).append(jump("밀린 택배 안내 →",25)));
        pages.add(page("하늘 장비의 한계","나무검: 유효 타격 32회\n철검 V: 유효 타격 24회\n철검 VII: 타격 12회\n보호 VI 흉갑: 피해 24회\n\n남은 횟수는 툴팁 표시.\n소진하면 사라집니다.\n기본 내구도도 소모돼요.\n수리·합성·추가 인챈트\n이전은 할 수 없어요."));
        pages.add(page("효율 VI 도구","다이아 곡괭이·도끼\n다이아 삽·괭이·가위\n5종, 각 4.8% 확률.\n\n성공한 채굴 96회 제한.\n경작·껍질 벗기기 등은\n기본 내구도를 소모해요.\n행운·섬세한 손길 없음.\n찹트리도 원목당 1회!"));
        pages.add(action("수상한 원정 출발","닭 부장의 야근 던전\n감자 왕국 세무조사\n우주 출장 해골\n\n생존 2명 이상, 지상에서\n48블록 안에 모이세요.\n평평하고 넓은 곳 필요.\n출발 10초 뒤 전투!","[ 원정 시작 요청 ]","/camp adventure start"));
        pages.add(action("원정 전투 규칙","전투는 총 3차례.\n마지막에는 보스 등장!\n원정 재사용은 20분.\n제한 시간은 10분.\n\n모두 현장 80블록 밖으로\n떠나면 원정이 끝나요.\n서버 종료 시도 끝나요.\n좌표·적·시간을 확인!","[ 원정 현황 보기 ]","/camp adventure"));
        pages.add(page("보상과 황당한 일","현장 64블록 안의\n생존 참여자에게 지급!\n다이아 2개·에메랄드 4개\n수상한 감자·경험치 150\n\n감자: 20초간 신속/성급함/\n야간 투시 중 하나.\n전투 중 닭 감사팀 방문,\n몬스터 부양, 감자 속보!\n농담이어도 전투는 진짜."));
        pages.add(page("죽으면 어떻게 돼요?","사망 드롭을 한 상자에!\n갑옷·보조손도 보관해요.\n27칸 초과는 여러 페이지.\n\n주변에 놓을 수 없으면\n재생성 지점·스폰에서\n안전한 곳을 찾습니다.\n경험치는 기본 규칙.\n인벤 보존이 켜져 있으면\n회수 상자는 안 만들어요."));
        pages.add(action("내 아이템 회수","좌표로 가서 상자 우클릭\n→ 모두 회수 버튼 누르기.\n본인·운영자만 회수 가능.\n상자 8블록 안에서만!\n\n공간 부족분은 계속 보관.\n자동 만료는 없습니다.\n다 받으면 상자도 정리.\n이 책은 사망해도 유지!","[ 내 상자 좌표 ]","/camp graves"));
        pages.add(action("찹트리","웅크린 채 도끼로\n자연 원목을 베세요.\n기본 최대 64개입니다.\n추가 원목도 내구도와\n보급 횟수가 줄어듭니다.\n\n웅크림 해제·도끼 파손·\n멀리 이동하면 멈춰요.\n건축물 곁에서는 주의!","[ 찹트리 켜기/끄기 ]","/camp tree"));
        pages.add(page("수확·재심기","웅크린 채 다 자란\n작물에 우클릭하세요.\n밀·당근·감자·비트 가능.\n\n수확물에서 씨앗/작물\n1개를 써서 다시 심어요.\n미성숙 작물은 그대로.\n심을 것이 안 나오면\n수확하지 않습니다.\n보호 구역에서는 취소!"));
        pages.add(action("정리와 제작대","/camp sort\n핫바·장비·보조손 유지.\n나머지를 합쳐 정렬해요.\n이름·인챈트도 보존해요.\n다른 창은 닫고 사용!\n\n/camp craft\n어디서든 제작대 열기.","[ 휴대용 제작대 ]","/camp craft"));
        pages.add(action("엔더 창고","엔더 상자 아이템을\n인벤토리에 지니고\n있으면 원격으로 열어요.\n아이템은 소비하지 않아요.\n\n창고 내용은 개인별!\n원래 엔더 상자와 같은\n아이템에 접근합니다.","[ 엔더 창고 열기 ]","/camp ender"));
        pages.add(action("친구에게 이동","/camp tpa 닉네임\n상대가 30초 안에\n/camp tpaccept 수락\n/camp tpdeny 거절\n\n수락 당시 위치로 이동.\n8초 대기·피해 시 취소.\n귀환과 재사용을 공유!\n무작정 이동하진 않아요.","[ 받은 요청 수락 ]","/camp tpaccept"));
        pages.add(action("화면과 이펙트","/camp 클릭형 메뉴\n/camp utils 편의 메뉴\n\n위쪽 막대: 목표·전투\n아래 HUD: 시간·남은 적\n보스 등장·보급은 타이틀!\n\n연출이 부담스러우면\n개인 이펙트를 끄세요.","[ 내 이펙트 전환 ]","/camp effects"));
        pages.add(page("명령어와 운영자","/camp help 전체 도움말\n/camp book 안내서 재지급\n/coop 도 같은 명령입니다.\n\n운영자 전용:\n/camp sethome 기지 지정\n/camp adventure cancel\n진행 중 원정 취소.\n\n상자를 얻으면 월드와\n플러그인도 함께 백업!"));
        pages.add(page("광질 후 택배 비!","지하에서도 개인 적립.\n지상으로 나오면 밀린\n택배가 1초마다 뚝뚝!\n주워야 수령 완료예요.\n\n한 번에 최대 3개 낙하.\n못 주운 택배는 재보관.\n재접속·재시작해도 유지.\n미접속 시간은 적립 없음.\nHUD에서 개수를 봐요."));
        pages.add(action("현상수배 정예몹","자연 몬스터 중 3% 확률.\n한 번에 1마리, 제한 5분.\n체력·이동 속도가 강화!\n다음 등장 최소 3분 간격.\n\n근처 기여자는 에메랄드 1\n막타 플레이어는 다이아 1\n보상은 택배함에 적립.\n참여자 경험치 50도 지급!","[ 현상수배 현황 ]","/camp fun"));
        pages.add(page("파티 콤보","친구와 15초 안에\n번갈아 몬스터를 잡아요.\n같은 사람이 연속 처치,\n멀리 이동하면 처음부터.\n\n5연속마다 신속 10초\n재생 6초·경험치 25!\n보상 재사용은 60초.\n자연 생성 몬스터만 인정.\n원정·스포너 몹은 제외."));
        pages.add(page("수상한 낚시","낚시 성공 시 20% 확률!\n개인 재사용 90초.\n원래 낚시 보상은 유지.\n\n에메랄드·수상한 감자\n엔더 진주·희귀 다이아\n또는 꽝 사직서 등장!\n추가 보상은 택배 적립.\n지상에서 받아 가세요.\n/camp fun 으로 안내!"));
        pages.add(action("접속 유지 패치","기능 모듈은 서버를\n끄지 않고 교체합니다.\n진행 중 원정은 종료돼요.\n아이템·목표·택배는 유지.\n\n운영자 전용:\n/camp update 패치 적용\n/camp reload 설정 재적용\n서버 본체·로더 교체는\n별도 재시작이 필요해요.","[ 실행 버전 확인 ]","/camp version"));
        meta.pages(pages); book.setItemMeta(meta); return book;
    }
    void ensure(Player p,boolean requested) {
        if(!p.isOnline() || p.isDead()) return;
        ItemStack[] contents=p.getInventory().getContents();
        for(int i=0;i<contents.length;i++) if(isGuide(contents[i])) {
            if(contents[i].getItemMeta().getPersistentDataContainer().getOrDefault(key,PersistentDataType.INTEGER,0)!=EDITION) {
                p.getInventory().setItem(i,create()); p.saveData();
            }
            if(requested) p.sendMessage(CampPresentation.text("◆ 안내서가 이미 인벤토리에 있습니다. 책을 우클릭해 읽으세요.",CampPresentation.TEAL));
            return;
        }
        int free=-1;
        ItemStack[] storage=p.getInventory().getStorageContents();
        for(int i=0;i<storage.length;i++) if(storage[i]==null || storage[i].getType().isAir()) { free=i; break; }
        if(free<0) {
            if(requested) p.sendMessage(CampPresentation.text("◆ 한 칸을 비운 뒤 /camp book 을 입력하세요. 기존 아이템은 건드리지 않습니다.",CampPresentation.GOLD));
            return;
        }
        ItemStack guide=create();
        if(p.getInventory().getItem(8)==null || p.getInventory().getItem(8).getType().isAir()) p.getInventory().setItem(8,guide);
        else p.getInventory().setItem(free,guide);
        p.saveData();
        p.sendMessage(CampPresentation.text("◆ 서버 안내서를 지급했습니다. 책을 우클릭하면 모든 기능을 읽을 수 있습니다.",CampPresentation.GOLD));
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void death(PlayerDeathEvent e) {
        if(e.getKeepInventory()) return;
        boolean kept=e.getItemsToKeep().stream().anyMatch(this::isGuide);
        for(Iterator<ItemStack> iterator=e.getDrops().iterator();iterator.hasNext();) {
            ItemStack drop=iterator.next(); if(!isGuide(drop)) continue;
            iterator.remove();
            if(!kept) { ItemStack one=create(); e.getItemsToKeep().add(one); kept=true; }
        }
    }
    @EventHandler public void respawn(PlayerRespawnEvent e) { later(e.getPlayer()); }
    @EventHandler public void closed(InventoryCloseEvent e) { if(e.getPlayer() instanceof Player p) later(p); }
    private void later(Player p) { if(plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin.owner(),()->ensure(p,false)); }
    void close() { retry.cancel(); }
}
