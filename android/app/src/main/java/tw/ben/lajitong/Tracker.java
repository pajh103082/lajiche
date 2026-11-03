package tw.ben.lajitong;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 背景追蹤的判斷邏輯（App 在背景、鎖螢幕時用）。
 * 跟網頁裡的 routeState()／checkAlarms()／checkGates() 是同一套規則，改一邊要記得改另一邊。
 * 這個檔案只用 java.*，可以在電腦上直接測試（見 tests/TrackerTest.java）。
 */
public class Tracker {

    public static class Stop { public long id; public String name; public String time; public double lat, lng; }
    public static class Home {
        public long routeId, pointId; public String pointName; public int[] period; public List<Stop> stops = new ArrayList<>();
        /** 路線形狀 [lat, lng]，判斷回收車有沒有沿著路線走 */
        public List<double[]> line = new ArrayList<>();
        /** 使用者指定的回收車（車輛代號），空字串＝自動判斷 */
        public String recyclePin = "";
        /** 網頁比對出來的回收車（今天的，或前幾週同一個星期幾的） */
        public String recycleLearned = "";
        /** 網頁判斷今天這條路線沒有回收車（今天垃圾車收了好幾站都沒有回收車跟，或前幾週同一天都沒有） */
        public boolean recycleNone = false;
        /** 清潔隊班表上今天的回收車車牌（網頁傳過來；車輛代號還不知道時用車牌找） */
        public String recyclePlate = "";
    }
    public static class Gate { public String id; public double lat, lng, r; public String name; }
    public static class Cfg {
        public String cust, team; public int poll = 20, thr = 3;
        public boolean arrive = true, gateOn = true, sound = true, voice = true, vib = true;
        public List<Gate> gates = new ArrayList<>(); public List<Home> homes = new ArrayList<>();
    }
    /** GetRemovalStatus 的一筆 */
    public static class Status { public long routeId, nextPointId; public int reachMinutes, reachDistance; public String car; }
    /** GetCarStatusGarbage 的一筆；type 是 "garbage" 或 "recycle"，t 是位置回報時間（毫秒） */
    public static class Car { public String id, type, plate; public double lat, lng; public long t; }
    /** 要響的鬧鐘；key 跟網頁的 fired／gateSt 用同一種寫法，兩邊才不會重複響 */
    public static class Alarm { public String key, type, kind, title, text, say; }

    private final Map<String, double[]> prev = new HashMap<>();   // 車輛代號 → {lat, lng, t}
    private final Map<String, Car> seen = new HashMap<>();         // 車輛代號 → 最後一次看到的樣子（離線時用）
    private final Map<Long, String> routeCar = new HashMap<>();    // 路線 → 今天負責的車
    /** 超過 3 分鐘沒回報位置，或整台從清單消失，就算離線（跟網頁的 OFFLINE_MS 一樣） */
    public static final long OFFLINE_MS = 3 * 60000L;
    /** 回收車：沿著路線（60 公尺內）走滿 4 分鐘才算；跟網頁的 MATE_MIN、ONROUTE_M 一樣 */
    public static final double MATE_MIN = 4, ONROUTE_M = 60;
    private final Map<Long, Map<String, double[]>> score = new HashMap<>(); // 路線 → 回收車 → {分鐘, 經過我家(0/1), 上次位置時間}
    private final Map<String, Long> gateFiredAt = new HashMap<>();
    public String summary = "";

    // ---------- 小工具 ----------
    static int toMin(String t) {
        try { String[] p = t.split(":"); return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim()); }
        catch (Exception e) { return 0; }
    }
    /** 跟 JavaScript 的 new Date().toDateString()+"|"+id 一樣，例如 "Mon Nov 02 2026|1073775" */
    static String dayKey(Calendar cal, String id) {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy", Locale.US);
        f.setTimeZone(cal.getTimeZone());
        return f.format(cal.getTime()) + "|" + id;
    }
    /** 資料平台的星期：0 = 週一 … 6 = 週日 */
    static int todayIdx(Calendar cal) { return (cal.get(Calendar.DAY_OF_WEEK) - 1 + 6) % 7; }
    static double dist(double lat1, double lng1, double lat2, double lng2) {
        double x = (lng2 - lng1) * Math.cos(Math.toRadians(lat1)) * 111320, y = (lat2 - lat1) * 110574;
        return Math.hypot(x, y);
    }
    static String kindName(String type) { return "recycle".equals(type) ? "資源回收車" : "垃圾車"; }
    static String kindPair(String type, String mateType) {
        if (mateType == null || mateType.equals(type)) return kindName(type);
        return "garbage".equals(type) ? "垃圾車和回收車" : "回收車和垃圾車";
    }
    static boolean fresh(Car c, long now) { return c != null && c.lat != 0 && now - c.t < 5 * 60000L; }

    /** 車子從上一次位置開到這一次位置的線段，離提醒點最近多遠（開太快跳過提醒點也抓得到） */
    double passDist(Gate g, Car c) {
        double[] p = prev.get(c.id);
        if (p == null || c.t - (long) p[2] > 3 * 60000L) return dist(g.lat, g.lng, c.lat, c.lng);
        double ax = (p[1] - g.lng) * Math.cos(Math.toRadians(g.lat)) * 111320, ay = (p[0] - g.lat) * 110574;
        double bx = (c.lng - g.lng) * Math.cos(Math.toRadians(g.lat)) * 111320, by = (c.lat - g.lat) * 110574;
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 > 0 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2)) : 0;
        return Math.hypot(ax + t * dx, ay + t * dy);
    }

    // ---------- 一條路線現在的狀況 ----------
    public static class RState {
        public Home h; public int hi, ni = -1, away; public double eta; public int meters;
        public String phase; public Car car, mate; public boolean runsToday, carMissing;
        public long winStart; // 今天第一站前 30 分鐘
        public boolean offline(long now) { return car != null && (carMissing || (car.t >= winStart && now - car.t > OFFLINE_MS)); } // 今天還沒出車的不算離線
    }

    public RState state(Cfg cfg, Home h, Calendar cal, long now, List<Status> sts, List<Car> cars) {
        RState r = new RState(); r.h = h;
        r.hi = 0;
        for (int i = 0; i < h.stops.size(); i++) if (h.stops.get(i).id == h.pointId) r.hi = i;
        int ti = todayIdx(cal);
        int lo = 9999; for (Stop st0 : h.stops) { int m = toMin(st0.time); if (m > 0) lo = Math.min(lo, m); }
        Calendar c0 = (Calendar) cal.clone(); c0.set(Calendar.HOUR_OF_DAY, 0); c0.set(Calendar.MINUTE, 0); c0.set(Calendar.SECOND, 0); c0.set(Calendar.MILLISECOND, 0);
        r.winStart = c0.getTimeInMillis() + (long) (lo - 30) * 60000L;
        r.runsToday = false;
        if (h.period != null) for (int d : h.period) if (d == ti) r.runsToday = true;
        Status s = null;
        for (Status x : sts) if (x.routeId == h.routeId) s = x;
        String carId = s != null && s.car != null && !"null".equals(s.car) ? s.car : routeCar.get(h.routeId);
        if (s != null && carId != null) routeCar.put(h.routeId, carId);
        if (carId != null) {
            for (Car c : cars) if (c.id.equals(carId)) r.car = c;
            if (r.car == null && seen.containsKey(carId)) { r.car = seen.get(carId); r.carMissing = true; } // 從清單消失：用最後的位置
        }
        r.mate = pickMate(h, cars, now);
        if (!r.runsToday) { r.phase = "off"; return r; }
        int homeT = toMin(h.stops.get(r.hi).time);
        if (s != null && s.reachMinutes >= 0 && s.nextPointId > 0) {
            int ni = -1;
            for (int i = 0; i < h.stops.size(); i++) if (h.stops.get(i).id == s.nextPointId) ni = i;
            r.ni = ni;
            if (ni >= 0 && ni <= r.hi) {
                r.away = r.hi - ni + 1;
                r.eta = Math.max(0, s.reachMinutes + Math.max(0, homeT - toMin(h.stops.get(ni).time)));
                r.meters = s.reachDistance;
                if (ni == r.hi && s.reachDistance >= 0 && s.reachDistance <= 80) { r.phase = "here"; r.away = 0; r.eta = 0; }
                else r.phase = "coming";
                return r;
            }
            if (ni > r.hi) { r.phase = ni == r.hi + 1 ? "justgone" : "gone"; return r; }
            r.phase = "far"; return r;
        }
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        r.phase = nowMin < homeT + 15 ? "wait" : "done";
        return r;
    }

    // ---------- 回收車 ----------
    // 資料平台只登記垃圾車。指定的優先；不然看哪台回收車今天沿著這條路線走最久（至少 4 分鐘）；再不然用之前學到的那台（要在路線 300 公尺內）。
    static double distToLine(List<double[]> line, double lat, double lng) {
        double best = 1e12;
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1), b = line.get(i);
            double kx = Math.cos(Math.toRadians(lat)) * 111320, ky = 110574;
            double ax = (a[1] - lng) * kx, ay = (a[0] - lat) * ky, bx = (b[1] - lng) * kx, by = (b[0] - lat) * ky;
            double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
            double t = l2 > 0 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2)) : 0;
            best = Math.min(best, Math.hypot(ax + t * dx, ay + t * dy));
        }
        return best;
    }
    static List<double[]> lineOf(Home h) {
        if (h.line != null && h.line.size() > 1) return h.line;
        List<double[]> l = new ArrayList<>();
        for (Stop s : h.stops) if (s.lat != 0) l.add(new double[]{s.lat, s.lng});
        return l;
    }
    void updateScores(Cfg cfg, Calendar cal, long now, List<Car> cars) {
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), ti = todayIdx(cal);
        for (Home h : cfg.homes) {
            boolean runs = false; if (h.period != null) for (int d : h.period) if (d == ti) runs = true;
            if (!runs) continue;
            int lo = 9999, hi = 0; for (Stop s : h.stops) { int m = toMin(s.time); if (m > 0) { lo = Math.min(lo, m); hi = Math.max(hi, m); } }
            if (nowMin < lo - 30 || nowMin > hi + 60) continue;
            List<double[]> line = lineOf(h);
            Stop home = null; for (Stop s : h.stops) if (s.id == h.pointId) home = s;
            Map<String, double[]> sc = score.computeIfAbsent(h.routeId, k -> new HashMap<>());
            for (Car c : cars) {
                if (!"recycle".equals(c.type) || !fresh(c, now)) continue;
                double[] e = sc.computeIfAbsent(c.id, k -> new double[]{0, 0, 0});
                if ((long) e[2] == c.t) continue;
                double dt = e[2] > 0 ? Math.max(0, Math.min(2, (c.t - e[2]) / 60000.0)) : 0;
                e[2] = c.t;
                if (distToLine(line, c.lat, c.lng) <= ONROUTE_M) e[0] += dt;
                if (home != null && home.lat != 0) { Gate g = new Gate(); g.lat = home.lat; g.lng = home.lng; if (passDist(g, c) <= ONROUTE_M) e[1] = 1; }
            }
        }
    }
    Car carById(String id, List<Car> cars) {
        if (id == null || id.isEmpty()) return null;
        for (Car c : cars) if (c.id.equals(id)) return c;
        return seen.get(id);
    }
    static String plateKey(String p) { return p == null ? "" : p.toUpperCase().replaceAll("[^A-Z0-9-]", ""); }
    Car pickMate(Home h, List<Car> cars, long now) {
        if (h.recyclePin != null && !h.recyclePin.isEmpty()) return carById(h.recyclePin, cars);
        // 網頁用歷史軌跡比對出來的（跟著垃圾車一站一站收的那台），App 打開時會傳過來
        if (h.recycleLearned != null && !h.recycleLearned.isEmpty()) { Car l = carById(h.recycleLearned, cars); if (l != null) return l; }
        if (h.recyclePlate != null && !h.recyclePlate.isEmpty()) { // 清潔隊班表上的那台
            String k = plateKey(h.recyclePlate);
            for (Car c : cars) if ("recycle".equals(c.type) && plateKey(c.plate).equals(k)) return c;
            for (Car c : seen.values()) if ("recycle".equals(c.type) && plateKey(c.plate).equals(k)) return c;
            return null;
        }
        if (h.recycleNone) return null; // 今天這條路線沒有回收車：不要拿剛好經過的車來湊
        Map<String, double[]> sc = score.get(h.routeId);
        String best = null; double bv = -1;
        if (sc != null) for (Map.Entry<String, double[]> en : sc.entrySet()) {
            double[] e = en.getValue(); double v = e[0] + (e[1] > 0 ? 10 : 0);
            if (e[0] >= MATE_MIN && v > bv) { bv = v; best = en.getKey(); }
        }
        if (best != null) return carById(best, cars);
        return null;
    }

    // ---------- 每次查完資料呼叫一次，回傳要響的鬧鐘 ----------
    public List<Alarm> step(Cfg cfg, Calendar cal, long now, List<Status> sts, List<Car> cars, Set<String> fired) {
        List<Alarm> out = new ArrayList<>();
        updateScores(cfg, cal, now, cars);
        List<RState> rs = new ArrayList<>();
        for (Home h : cfg.homes) rs.add(state(cfg, h, cal, now, sts, cars));

        for (RState r : rs) {
            String k = dayKey(cal, String.valueOf(r.h.pointId));
            if ("coming".equals(r.phase) && r.away <= cfg.thr && !fired.contains(k + "pre")) {
                fired.add(k + "pre"); out.add(routeAlarm(r, k + "pre"));
            }
            if ("here".equals(r.phase) && cfg.arrive && !fired.contains(k + "here")) {
                fired.add(k + "here"); out.add(routeAlarm(r, k + "here"));
            }
        }
        if (cfg.gateOn) for (Gate g : cfg.gates) for (RState r : rs) {
            if (!r.runsToday) continue;
            List<Car> cs = new ArrayList<>();
            if (fresh(r.car, now)) cs.add(r.car);
            if (fresh(r.mate, now)) cs.add(r.mate);
            if (cs.isEmpty()) continue;
            String key = dayKey(cal, "g" + g.id + "|" + r.h.pointId);
            Car hit = null;
            for (Car c : cs) if (passDist(g, c) <= g.r) { hit = c; break; }
            if (hit != null && !fired.contains(key)) {
                fired.add(key); gateFiredAt.put(key, now); out.add(gateAlarm(g, r, hit, key));
            } else if (hit == null && fired.contains(key)) {
                long at = gateFiredAt.containsKey(key) ? gateFiredAt.get(key) : 0;
                boolean far = true;
                for (Car c : cs) if (dist(g.lat, g.lng, c.lat, c.lng) <= g.r * 2.5) far = false;
                if (at > 0 && now - at > 5 * 60000L && far) { fired.remove(key); gateFiredAt.remove(key); } // 走遠了，下次再經過還會響
            }
        }
        for (Car c : cars) { // 記住這次的位置，下次算線段用；也記住最後的樣子，離線時用
            double[] p = prev.get(c.id);
            if (p == null || (long) p[2] < c.t) prev.put(c.id, new double[]{c.lat, c.lng, c.t});
            Car old = seen.get(c.id);
            if (c.lat != 0 && (old == null || old.t <= c.t)) seen.put(c.id, c);
        }
        summary = rs.isEmpty() ? "" : summaryOf(rs.get(0), now);
        return out;
    }

    Alarm routeAlarm(RState r, String key) {
        Alarm a = new Alarm();
        a.key = key; a.type = r.car != null ? r.car.type : "garbage";
        a.kind = kindPair(a.type, r.mate != null ? r.mate.type : null);
        boolean here = "here".equals(r.phase);
        long eta = Math.max(1, Math.round(r.eta));
        a.title = a.kind + (here ? "到您家了！" : (r.away == 1 ? "下一站就到" : "還有 " + r.away + " 站"));
        a.text = here ? "在「" + r.h.pointName + "」，快拿出去" : "大約 " + eta + " 分鐘後到，可以準備了";
        a.say = a.kind + (here ? "到了，請快出門" : "還有" + r.away + "站，大約" + eta + "分鐘後到");
        return a;
    }

    Alarm gateAlarm(Gate g, RState r, Car hit, String key) {
        Alarm a = new Alarm();
        Car other = hit == r.car ? r.mate : r.car;
        String mt = other != null && !other.type.equals(hit.type) ? other.type : null;
        a.key = key; a.type = hit.type; a.kind = kindPair(hit.type, mt);
        boolean coming = "coming".equals(r.phase);
        long eta = Math.max(1, Math.round(r.eta));
        a.title = a.kind + "經過提醒點了";
        a.text = "在「" + g.name + "」" + (coming ? "，到您家還有 " + r.away + " 站，大約 " + eta + " 分鐘" : "here".equals(r.phase) ? "，已經到您家這站" : "");
        a.say = a.kind + "經過" + g.name + "了" + (coming ? "，還有" + r.away + "站到您家" : "");
        return a;
    }

    static String summaryOf(RState r, long now) {
        String s = summaryOf(r);
        if (r.runsToday && r.offline(now)) {
            long min = Math.max(1, Math.round((now - r.car.t) / 60000.0));
            if (r.car.t == 0) min = 0;
            s = kindName(r.car.type) + "離線" + (min > 0 ? " " + min + " 分鐘" : "") + "・" + s + "（離線前的資料）";
        }
        return s;
    }

    static String summaryOf(RState r) {
        switch (r.phase) {
            case "off": return "今天您家這站不收";
            case "wait": return "車子還沒出發";
            case "coming": return kindName(r.car != null ? r.car.type : "garbage") + "還有 " + r.away + " 站，大約 " + Math.max(1, Math.round(r.eta)) + " 分鐘";
            case "here": return "車子到您家了！";
            case "justgone": return "車子剛開走";
            case "gone": return "車子已經開走了";
            case "far": return "車子在路上";
            default: return "今天收完了";
        }
    }
}
