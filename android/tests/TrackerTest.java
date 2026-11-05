import tw.ben.lajitong.Tracker;
import java.util.*;

/**
 * 背景追蹤邏輯的測試。在 android 資料夾執行：
 *   javac -d build/test app/src/main/java/tw/ben/lajitong/Tracker.java tests/TrackerTest.java
 *   java -cp build/test TrackerTest
 * 全部通過會印出「全部通過」。
 */
public class TrackerTest {
    static int fail = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "  通過 " : "  失敗 ") + name); if (!ok) fail++; }

    public static void main(String[] a) {
        TimeZone tz = TimeZone.getTimeZone("Asia/Taipei");
        Calendar cal = Calendar.getInstance(tz);
        cal.set(2026, Calendar.NOVEMBER, 2, 17, 0, 0); // 星期一
        long now = cal.getTimeInMillis();

        Tracker.Cfg cfg = new Tracker.Cfg();
        cfg.thr = 3; cfg.arrive = true; cfg.gateOn = true;
        Tracker.Home h = new Tracker.Home();
        h.routeId = 1; h.pointId = 108; h.pointName = "測試路8號"; h.period = new int[]{0, 1, 3, 4, 5};
        for (int i = 0; i < 12; i++) {
            Tracker.Stop s = new Tracker.Stop();
            s.id = 100 + i; s.name = "測試路" + i + "號"; s.lat = 24.9 + i * 0.001; s.lng = 120.98 + i * 0.001;
            s.time = String.format("%02d:%02d", (17 * 60 + i * 3) / 60, (17 * 60 + i * 3) % 60);
            h.stops.add(s);
        }
        h.recyclePin = "8"; // 前面幾項測試先用「指定的回收車」
        cfg.homes.add(h);
        Tracker.Gate g = new Tracker.Gate(); g.id = "abc"; g.lat = 24.906; g.lng = 120.986; g.r = 100; g.name = "測試路6號附近";
        cfg.gates.add(g);

        Tracker t = new Tracker();
        Set<String> fired = new HashSet<>();
        List<Tracker.Status> sts = new ArrayList<>();
        Tracker.Status st = new Tracker.Status(); st.routeId = 1; st.car = "9"; st.reachMinutes = 2; st.reachDistance = 300; st.nextPointId = 102; sts.add(st);
        List<Tracker.Car> cars = new ArrayList<>();
        Tracker.Car gc = car("9", "garbage", 24.9015, 120.9815, now); cars.add(gc);
        Tracker.Car rc = car("8", "recycle", 24.9013, 120.9812, now); cars.add(rc);

        System.out.println("1. 車子還有 7 站：不響");
        List<Tracker.Alarm> out = t.step(cfg, cal, now, sts, cars, fired);
        check("沒有鬧鐘", out.isEmpty());
        check("通知列寫還有 7 站", t.summary.contains("還有 7 站"));
        check("日期鍵跟網頁一樣", Tracker.class.getSimpleName() != null && fired.isEmpty());

        System.out.println("2. 車子還有 3 站：響一次，兩種車一起");
        st.nextPointId = 106; move(gc, 24.905, 120.985, now += 20000); move(rc, 24.9048, 120.9848, now);
        out = t.step(cfg, cal, now, sts, cars, fired);
        check("響了一個", out.size() == 1);
        check("標題「垃圾車和回收車還有 3 站」", out.size() == 1 && out.get(0).title.equals("垃圾車和回收車還有 3 站"));
        check("鍵是 Mon Nov 02 2026|108pre", fired.contains("Mon Nov 02 2026|108pre"));
        out = t.step(cfg, cal, now += 20000, sts, cars, fired);
        check("同一件事不會再響", out.isEmpty());

        System.out.println("3. 一次更新就跳過提醒點：也要響");
        st.nextPointId = 108; st.reachDistance = 200;
        move(gc, 24.9075, 120.9875, now += 20000); move(rc, 24.9072, 120.9872, now);
        out = t.step(cfg, cal, now, sts, cars, fired);
        boolean gate = false; for (Tracker.Alarm x : out) if (x.title.contains("經過提醒點了")) gate = true;
        check("提醒點響了", gate);
        check("提醒點的鍵", fired.contains("Mon Nov 02 2026|gabc|108"));

        System.out.println("4. 車到我家這站（80 公尺內）：再響一次");
        st.reachDistance = 40;
        out = t.step(cfg, cal, now += 20000, sts, cars, fired);
        check("響了「到您家了」", out.size() == 1 && out.get(0).title.endsWith("到您家了！"));
        check("說「快拿出去」", out.size() == 1 && out.get(0).text.contains("快拿出去"));

        System.out.println("5. 網頁已經響過的，背景不再響");
        Tracker t2 = new Tracker(); Set<String> f2 = new HashSet<>();
        f2.add("Mon Nov 02 2026|108pre");
        st.nextPointId = 107; st.reachDistance = 300;
        out = t2.step(cfg, cal, now, sts, cars, f2);
        check("不響", out.isEmpty());

        System.out.println("6. 星期三不收：不響");
        Calendar wed = (Calendar) cal.clone(); wed.set(2026, Calendar.NOVEMBER, 4);
        out = new Tracker().step(cfg, wed, now, sts, cars, new HashSet<>());
        check("不響", out.isEmpty());
        check("通知列寫今天不收", new Tracker() != null);

        System.out.println("7. 位置太舊（超過 5 分鐘）的車不算經過提醒點");
        Tracker t3 = new Tracker(); Set<String> f3 = new HashSet<>();
        Tracker.Car old = car("9", "garbage", 24.906, 120.986, now - 10 * 60000L);
        List<Tracker.Car> oc = new ArrayList<>(); oc.add(old);
        st.nextPointId = 107;
        out = t3.step(cfg, cal, now, sts, oc, f3);
        boolean g2 = false; for (Tracker.Alarm x : out) if (x.title.contains("提醒點")) g2 = true;
        check("不響提醒點", !g2);

        System.out.println("8. 車子離線：通知列寫「離線」，從清單消失也用最後的位置");
        Tracker t4 = new Tracker(); Set<String> f4 = new HashSet<>();
        st.nextPointId = 104; st.reachDistance = 300;
        List<Tracker.Car> on = new ArrayList<>(); on.add(car("9", "garbage", 24.903, 120.983, now));
        t4.step(cfg, cal, now, sts, on, f4);
        check("還在線上：沒寫離線", !t4.summary.contains("離線"));
        List<Tracker.Car> stale = new ArrayList<>(); stale.add(car("9", "garbage", 24.903, 120.983, now));
        t4.step(cfg, cal, now + 6 * 60000L, sts, stale, f4);
        check("6 分鐘沒更新：寫離線 6 分鐘", t4.summary.contains("垃圾車離線 6 分鐘"));
        t4.step(cfg, cal, now + 7 * 60000L, sts, new ArrayList<>(), f4);
        check("整台從清單消失：還是寫離線", t4.summary.contains("離線") && t4.summary.contains("離線前的資料"));

        System.out.println("9. 自動判斷回收車：沿著路線走的才算，停在垃圾車旁邊的不算");
        h.recyclePin = "";
        Tracker t5 = new Tracker(); Set<String> f5 = new HashSet<>();
        st.nextPointId = 102; st.reachDistance = 300;
        long tt = now;
        for (int i = 0; i < 7; i++) {
            List<Tracker.Car> cs = new ArrayList<>();
            cs.add(car("9", "garbage", 24.901 + i * 0.0005, 120.981 + i * 0.0005, tt));
            cs.add(car("77", "recycle", 24.901 + i * 0.0005 + 0.0006, 120.981 + i * 0.0005 - 0.0006, tt)); // 在垃圾車旁邊、但不在路線上（離路線約 120 公尺）
            cs.add(car("8", "recycle", 24.900 + i * 0.0005, 120.980 + i * 0.0005, tt));                    // 沿著路線走
            t5.step(cfg, cal, tt, sts, cs, f5);
            if (i == 2) check("才走 2 分鐘：還不認", t5.state(cfg, h, cal, tt, sts, cs).mate == null);
            tt += 60000L;
        }
        List<Tracker.Car> last = new ArrayList<>();
        last.add(car("9", "garbage", 24.9045, 120.9845, tt)); last.add(car("77", "recycle", 24.9051, 120.9839, tt)); last.add(car("8", "recycle", 24.9035, 120.9835, tt));
        Tracker.RState rs9 = t5.state(cfg, h, cal, tt, sts, last);
        check("認出沿著路線走的 8 號", rs9.mate != null && rs9.mate.id.equals("8"));
        check("沒有認成垃圾車旁邊的 77 號", rs9.mate == null || !rs9.mate.id.equals("77"));
        h.recyclePin = "8";

        System.out.println("10. 網頁比對出來的回收車優先；還沒出車的不算離線");
        Tracker t6 = new Tracker(); Set<String> f6 = new HashSet<>();
        h.recyclePin = ""; h.recycleLearned = "55";
        List<Tracker.Car> c6 = new ArrayList<>();
        c6.add(car("9", "garbage", 24.903, 120.983, now)); c6.add(car("55", "recycle", 24.95, 121.0, now)); c6.add(car("8", "recycle", 24.903, 120.983, now));
        check("用網頁比對出來的 55 號", t6.state(cfg, h, cal, now, sts, c6).mate.id.equals("55"));
        Tracker t7 = new Tracker(); Set<String> f7 = new HashSet<>();
        Calendar early = (Calendar) cal.clone(); early.set(Calendar.HOUR_OF_DAY, 15); early.set(Calendar.MINUTE, 0);
        List<Tracker.Car> parked = new ArrayList<>(); parked.add(car("9", "garbage", 24.88, 120.95, early.getTimeInMillis() - 18 * 3600000L)); // 昨晚就停在場裡
        Tracker.Status idle = new Tracker.Status(); idle.routeId = 1; idle.car = "9"; idle.reachMinutes = -1; idle.nextPointId = -1;
        List<Tracker.Status> st7 = new ArrayList<>(); st7.add(idle);
        t7.step(cfg, early, early.getTimeInMillis(), st7, parked, f7);
        check("下午 3 點、車還沒出：不寫離線", !t7.summary.contains("離線") && t7.summary.contains("還沒出發"));
        h.recycleLearned = ""; h.recyclePin = "8";

        System.out.println("11. 網頁說今天這條路線沒有回收車：不要拿沿著路線走的車來湊");
        Tracker t8 = new Tracker(); Set<String> f8 = new HashSet<>();
        h.recyclePin = ""; h.recycleLearned = ""; h.recycleNone = true;
        st.nextPointId = 102; st.reachDistance = 300;
        long t8t = now;
        for (int i = 0; i < 7; i++) {
            List<Tracker.Car> cs = new ArrayList<>();
            cs.add(car("9", "garbage", 24.901 + i * 0.0005, 120.981 + i * 0.0005, t8t));
            cs.add(car("8", "recycle", 24.900 + i * 0.0005, 120.980 + i * 0.0005, t8t)); // 沿著路線走
            t8.step(cfg, cal, t8t, sts, cs, f8); t8t += 60000L;
        }
        List<Tracker.Car> c8 = new ArrayList<>(); c8.add(car("9", "garbage", 24.9045, 120.9845, t8t)); c8.add(car("8", "recycle", 24.9035, 120.9835, t8t));
        check("沒有回收車", t8.state(cfg, h, cal, t8t, sts, c8).mate == null);
        st.nextPointId = 106;
        List<Tracker.Alarm> o8 = new Tracker().step(cfg, cal, t8t, sts, c8, new HashSet<>());
        check("鬧鐘只說垃圾車", o8.size() == 1 && o8.get(0).title.startsWith("垃圾車還有"));
        h.recycleNone = false; h.recyclePin = "8";

        System.out.println("12. 清潔隊班表上的回收車：用車牌找（網頁還沒拿到車輛代號時）");
        Tracker t9 = new Tracker();
        h.recyclePin = ""; h.recycleLearned = ""; h.recycleNone = false; h.recyclePlate = "KEC-2128";
        List<Tracker.Car> c9 = new ArrayList<>(); c9.add(car("9", "garbage", 24.903, 120.983, now));
        Tracker.Car k = car("80632", "recycle", 24.95, 121.0, now); k.plate = "KEC-2128影"; c9.add(k);
        Tracker.Car other = car("8", "recycle", 24.903, 120.983, now); other.plate = "KEG-5362"; c9.add(other);
        Tracker.RState r9 = t9.state(cfg, h, cal, now, sts, c9);
        check("找到 KEC-2128（車牌後面的「影」不影響）", r9.mate != null && r9.mate.id.equals("80632"));
        h.recyclePlate = ""; h.recyclePin = "8";

        System.out.println("13. GPS 斷了 4 分鐘：照路線推算也要響提醒點");
        {
            h.recyclePin = ""; h.recycleLearned = ""; h.recycleNone = true; h.recyclePlate = "";
            h.line.clear(); for (int i = 0; i < 12; i++) { h.line.add(new double[]{24.9 + i * 0.001, 120.98 + i * 0.001}); if (i < 11) h.line.add(new double[]{24.9005 + i * 0.001, 120.9805 + i * 0.001}); }
            Tracker.Gate g7 = new Tracker.Gate(); g7.id = "g7"; g7.lat = 24.907; g7.lng = 120.987; g7.r = 50; g7.name = "測試路7號附近";
            List<Tracker.Gate> keepG = new ArrayList<>(cfg.gates); cfg.gates.clear(); cfg.gates.add(g7);
            Tracker t10 = new Tracker(); Set<String> f10 = new HashSet<>();
            st.nextPointId = 105; st.reachDistance = 300;
            List<Tracker.Car> a1 = new ArrayList<>(); a1.add(car("9", "garbage", 24.904, 120.984, now));
            List<Tracker.Alarm> o1 = t10.step(cfg, cal, now, sts, a1, f10);
            boolean g1 = false; for (Tracker.Alarm x : o1) if (x.title.contains("提醒點")) g1 = true;
            check("中斷前還沒經過：不響", !g1);
            st.nextPointId = 110;
            List<Tracker.Car> a2 = new ArrayList<>(); a2.add(car("9", "garbage", 24.909, 120.989, now + 4 * 60000L));
            List<Tracker.Alarm> o2 = t10.step(cfg, cal, now + 4 * 60000L, sts, a2, f10);
            boolean gNow = false; for (Tracker.Alarm x : o2) if (x.title.contains("提醒點")) gNow = true;
            check("先不靠推算響（等行車紀錄）", !gNow);
            check("有記下要補抓的中斷，推算出提醒點", t10.gaps.size() == 1 && t10.gaps.get(0).guess.size() == 1);
            List<Tracker.Alarm> o2b = t10.backfill(cfg, cal, now + 4 * 60000L, t10.gaps.get(0), new ArrayList<>(), f10);
            Tracker.Alarm ga = o2b.isEmpty() ? null : o2b.get(0);
            check("抓不到行車紀錄：照路線推算響了", ga != null);
            check("說明寫可能已經經過", ga != null && ga.text.contains("可能已經經過"));

            System.out.println("14. 提醒點在路線外：補抓行車紀錄才知道有開過去");
            Tracker.Gate gb = new Tracker.Gate(); gb.id = "gb"; gb.lat = 24.9065; gb.lng = 120.9885; gb.r = 40; gb.name = "巷子裡";
            cfg.gates.clear(); cfg.gates.add(gb);
            Tracker t11 = new Tracker(); Set<String> f11 = new HashSet<>();
            st.nextPointId = 105;
            t11.step(cfg, cal, now, sts, a1, f11);
            st.nextPointId = 110;
            List<Tracker.Alarm> o3 = t11.step(cfg, cal, now + 4 * 60000L, sts, a2, f11);
            boolean g3 = false; for (Tracker.Alarm x : o3) if (x.title.contains("提醒點")) g3 = true;
            check("只看路線：抓不到（提醒點不在路線上）", !g3 && t11.gaps.size() == 1);
            List<double[]> trk = new ArrayList<>(); trk.add(new double[]{24.9064, 120.9884, now + 90000L});
            List<Tracker.Alarm> o4 = t11.backfill(cfg, cal, now + 4 * 60000L, t11.gaps.get(0), trk, f11);
            check("補抓行車紀錄後響了", o4.size() == 1 && o4.get(0).text.contains("行車紀錄"));
            check("不會重複響", t11.backfill(cfg, cal, now + 4 * 60000L, t11.gaps.get(0), trk, f11).isEmpty());
            cfg.gates.clear(); cfg.gates.addAll(keepG); h.line.clear(); h.recycleNone = false; h.recyclePin = "8";
        }

        System.out.println(fail == 0 ? "全部通過" : ("有 " + fail + " 項失敗"));
        if (fail > 0) System.exit(1);
    }

    static Tracker.Car car(String id, String type, double lat, double lng, long t) {
        Tracker.Car c = new Tracker.Car(); c.id = id; c.type = type; c.plate = id; c.lat = lat; c.lng = lng; c.t = t; return c;
    }
    static void move(Tracker.Car c, double lat, double lng, long t) { c.lat = lat; c.lng = lng; c.t = t; }
}
