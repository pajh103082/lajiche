package tw.ben.lajitong;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 背景追蹤：App 不在畫面上（切到別的 App、鎖螢幕）時，由這裡定時查資料平台的資料、判斷要不要響鈴。
 * App 在畫面上時這裡不查，交給網頁自己處理，兩邊才不會重複響。
 */
public class TrackerService extends Service {
    static final String EP = "https://customer-tw.eupfin.com/Eup_Servlet_Nuser_SOAP/Eup_Servlet_Nuser_SOAP";
    static final String ACTION_START = "start", ACTION_UPDATE = "update", ACTION_STOP = "stop";
    static final String CH_RUN = "tracking", CH_ALARM = "truck_alarm";
    static final int ID_RUN = 1;
    static final String PREFS = "ljt";

    static volatile boolean running = false;

    private ScheduledExecutorService exec;
    private ScheduledFuture<?> job;
    private PowerManager.WakeLock wakeLock;
    private final Tracker tracker = new Tracker();
    private Tracker.Cfg cfg;
    private int pollSec = 20;
    private int alarmId = 100;
    private static TextToSpeech tts;
    private static boolean ttsReady = false;

    // ---------- 給 MainActivity 用的 ----------
    static void start(Context c, String cfgJson) {
        prefs(c).edit().putString("cfg", cfgJson).putBoolean("tracking", true).apply();
        Intent i = new Intent(c, TrackerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    static void update(Context c, String cfgJson) {
        prefs(c).edit().putString("cfg", cfgJson).apply();
        if (running) c.startService(new Intent(c, TrackerService.class).setAction(ACTION_UPDATE));
    }
    static void stop(Context c) {
        prefs(c).edit().putBoolean("tracking", false).apply();
        c.startService(new Intent(c, TrackerService.class).setAction(ACTION_STOP));
    }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, MODE_PRIVATE); }

    /** 已經響過的鬧鐘（網頁和背景共用），只留今天的 */
    static synchronized Set<String> firedKeys(Context c) {
        Set<String> all = new HashSet<>(prefs(c).getStringSet("fired", new HashSet<>()));
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy", Locale.US);
        String today = f.format(Calendar.getInstance().getTime()) + "|";
        Set<String> keep = new HashSet<>();
        for (String k : all) if (k.startsWith(today)) keep.add(k);
        return keep;
    }
    static synchronized void saveFired(Context c, Set<String> keys) {
        prefs(c).edit().putStringSet("fired", new HashSet<>(keys)).apply();
    }
    static synchronized void markFired(Context c, String key) {
        Set<String> s = firedKeys(c); s.add(key); saveFired(c, s);
    }

    static void speak(Context c, String text) {
        if (tts == null) {
            tts = new TextToSpeech(c.getApplicationContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) { tts.setLanguage(Locale.TAIWAN); tts.setSpeechRate(0.85f); tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ljt"); }
            });
            return;
        }
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ljt");
    }

    // ---------- Service ----------
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START; // 被系統重開時 intent 是 null
        if (ACTION_STOP.equals(action)) { stopTracking(); return START_NOT_STICKY; }
        createChannels(this);
        Notification runN = runNotification("追蹤中，車子快到時會提醒您");
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_RUN, runN, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID_RUN, runN);
        if (!prefs(this).getBoolean("tracking", false)) { stopTracking(); return START_NOT_STICKY; }
        loadCfg();
        running = true;
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ljt:tracking");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(8 * 60 * 60 * 1000L); // 最多 8 小時
        schedule();
        return START_STICKY;
    }

    private void loadCfg() {
        try { cfg = parseCfg(new JSONObject(prefs(this).getString("cfg", "{}"))); }
        catch (Exception e) { cfg = new Tracker.Cfg(); }
        pollSec = Math.max(10, cfg.poll);
    }

    private void schedule() {
        if (exec == null) exec = Executors.newSingleThreadScheduledExecutor();
        if (job != null) job.cancel(false);
        job = exec.scheduleWithFixedDelay(this::pollOnce, 1, pollSec, TimeUnit.SECONDS);
    }

    private void pollOnce() {
        try {
            if (MainActivity.visible) return; // 畫面開著，網頁自己會查、會響
            if (cfg == null || cfg.homes.isEmpty()) return;
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"));
            if (cal.get(Calendar.HOUR_OF_DAY) >= 23) { stopTracking(); return; } // 晚上 11 點自動停
            List<Tracker.Status> sts = new ArrayList<>();
            JSONArray a = call(new JSONObject().put("Cust_ID", cfg.cust).put("MethodName", "GetRemovalStatus"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tracker.Status s = new Tracker.Status();
                s.routeId = o.optLong("routeId"); s.nextPointId = o.optLong("removealPointToArrivalId", -1);
                s.reachMinutes = o.optInt("reachMinutes", -1); s.reachDistance = o.optInt("reachDistance", -1);
                s.car = String.valueOf(o.opt("responsibleCar"));
                sts.add(s);
            }
            List<Tracker.Car> cars = new ArrayList<>();
            JSONArray b = call(new JSONObject().put("Cust_ID", Long.parseLong(cfg.cust)).put("Team_ID", Long.parseLong(cfg.team)).put("MethodName", "GetCarStatusGarbage"));
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));
            for (int i = 0; i < b.length(); i++) {
                JSONObject o = b.getJSONObject(i);
                Tracker.Car c = new Tracker.Car();
                c.id = String.valueOf(o.opt("Car_Unicode"));
                c.type = o.optString("Car_Style").contains("回收") ? "recycle" : "garbage";
                c.plate = o.optString("Car_Number");
                c.lat = o.optDouble("Log_GISY", 0); c.lng = o.optDouble("Log_GISX", 0);
                try { c.t = f.parse(o.optString("Log_DTime")).getTime(); } catch (Exception e) { c.t = 0; }
                cars.add(c);
            }
            Set<String> fired = firedKeys(this);
            List<Tracker.Alarm> alarms = tracker.step(cfg, cal, System.currentTimeMillis(), sts, cars, fired);
            saveFired(this, fired);
            updateRunNotification(tracker.summary);
            for (Tracker.Alarm al : alarms) ring(al);
        } catch (Exception e) {
            updateRunNotification("暫時連不上垃圾車的資料，會自動再試");
        }
    }

    private JSONArray call(JSONObject param) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(EP).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(15000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] body = ("Param=" + URLEncoder.encode(param.toString(), "UTF-8")).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) { os.write(body); }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream is = c.getInputStream()) { byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) bo.write(buf, 0, n); }
        c.disconnect();
        JSONObject j = new JSONObject(bo.toString("UTF-8"));
        if (j.optInt("status") != 1) throw new Exception(j.optString("error"));
        JSONArray r = j.optJSONArray("result");
        return r != null ? r : new JSONArray();
    }

    // ---------- 響鈴 ----------
    private void ring(Tracker.Alarm a) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, alarmId, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_ALARM) : new Notification.Builder(this))
                .setSmallIcon(R.drawable.ic_stat_truck)
                .setContentTitle(a.title)
                .setContentText(a.text)
                .setStyle(new Notification.BigTextStyle().bigText(a.text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor("recycle".equals(a.type) ? 0xFF1D5FD6 : 0xFFE58A00)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) {
            if (cfg.sound) nb.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            if (cfg.vib) nb.setVibrate(new long[]{0, 600, 300, 600, 300, 600});
        }
        Notification n = nb.build();
        if (cfg.sound) n.flags |= Notification.FLAG_INSISTENT; // 一直響到按掉或打開 App
        nm.notify(alarmId++, n);
        if (cfg.voice) speak(this, a.say);
    }

    static void cancelAlarms(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications())
            if (s.getId() >= 100) nm.cancel(s.getId());
    }

    // ---------- 通知 ----------
    static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel run = new NotificationChannel(CH_RUN, "追蹤中", NotificationManager.IMPORTANCE_LOW);
        run.setDescription("顯示正在追蹤垃圾車");
        nm.createNotificationChannel(run);
        NotificationChannel al = new NotificationChannel(CH_ALARM, "垃圾車快到了", NotificationManager.IMPORTANCE_HIGH);
        al.setDescription("垃圾車、回收車快到或經過提醒點時響鈴");
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        al.setSound(sound, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        al.enableVibration(true);
        al.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        al.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(al);
    }

    private Notification runNotification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TrackerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_RUN) : new Notification.Builder(this))
                .setSmallIcon(R.drawable.ic_stat_truck)
                .setContentTitle("垃圾車來了：追蹤中")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "停止追蹤", stop).build());
        return nb.build();
    }

    private void updateRunNotification(String text) {
        if (!running) return;
        getSystemService(NotificationManager.class).notify(ID_RUN, runNotification(text == null || text.isEmpty() ? "追蹤中" : text));
    }

    private void stopTracking() {
        prefs(this).edit().putBoolean("tracking", false).apply();
        running = false;
        if (job != null) job.cancel(false);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        if (exec != null) exec.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    // ---------- 網頁傳來的設定（appCfg()） ----------
    static Tracker.Cfg parseCfg(JSONObject j) {
        Tracker.Cfg c = new Tracker.Cfg();
        c.cust = j.optString("cust"); c.team = j.optString("team");
        c.poll = j.optInt("poll", 20); c.thr = j.optInt("thr", 3);
        c.arrive = j.optBoolean("arrive", true); c.gateOn = j.optBoolean("gateOn", true);
        c.sound = j.optBoolean("sound", true); c.voice = j.optBoolean("voice", true); c.vib = j.optBoolean("vib", true);
        JSONArray gs = j.optJSONArray("gates");
        if (gs != null) for (int i = 0; i < gs.length(); i++) {
            JSONObject o = gs.optJSONObject(i); if (o == null) continue;
            Tracker.Gate g = new Tracker.Gate();
            g.id = o.optString("id"); g.lat = o.optDouble("lat"); g.lng = o.optDouble("lng"); g.r = o.optDouble("r", 100); g.name = o.optString("name");
            c.gates.add(g);
        }
        JSONArray hs = j.optJSONArray("homes");
        if (hs != null) for (int i = 0; i < hs.length(); i++) {
            JSONObject o = hs.optJSONObject(i); if (o == null) continue;
            Tracker.Home h = new Tracker.Home();
            h.routeId = o.optLong("routeId"); h.pointId = o.optLong("pointId"); h.pointName = o.optString("pointName");
            JSONArray p = o.optJSONArray("period");
            h.period = new int[p == null ? 0 : p.length()];
            for (int k = 0; k < h.period.length; k++) h.period[k] = p.optInt(k);
            JSONArray ss = o.optJSONArray("stops");
            if (ss != null) for (int k = 0; k < ss.length(); k++) {
                JSONObject so = ss.optJSONObject(k); if (so == null) continue;
                Tracker.Stop s = new Tracker.Stop();
                s.id = so.optLong("id"); s.name = so.optString("name"); s.time = so.optString("time");
                s.lat = so.optDouble("lat", 0); s.lng = so.optDouble("lng", 0);
                h.stops.add(s);
            }
            JSONArray ln = o.optJSONArray("line");
            if (ln != null) for (int k = 0; k < ln.length(); k++) {
                JSONArray pt = ln.optJSONArray(k); if (pt == null || pt.length() < 2) continue;
                h.line.add(new double[]{pt.optDouble(0), pt.optDouble(1)});
            }
            h.recyclePin = o.optString("recyclePin", "");
            h.recycleLearned = o.optString("recycleLearned", "");
            h.recycleNone = o.optBoolean("recycleNone", false);
            h.recyclePlate = o.optString("recyclePlate", "");
            c.homes.add(h);
        }
        return c;
    }
}
