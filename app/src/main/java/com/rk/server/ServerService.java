package com.rk.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Lightweight RK Chat backend. Designed for Android API 19 and low-memory tablets. */
public class ServerService extends Service {
    public static final String ACTION_START = "com.rk.server.START";
    public static final int PORT = 8080;
    public static volatile boolean isRunning = false;
    public static volatile String lastError = "";

    private static final int MAX_MESSAGES = 500;
    private static final int MAX_CALL_SIGNALS = 100;
    private static final int MAX_STATUSES = 100;
    private static final int MAX_HEADER_BYTES = 16384;
    private static final int MAX_BODY_BYTES = 280 * 1024;
    private static final int MAX_IMAGE_BYTES = 180 * 1024;
    private static final Object DATA_LOCK = new Object();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private volatile boolean stopRequested = false;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    private ThreadPoolExecutor workers;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private JSONArray messages = new JSONArray();
    private JSONArray users = new JSONArray();
    private JSONArray sessions = new JSONArray();
    private JSONArray callSignals = new JSONArray();
    private JSONArray statuses = new JSONArray();
    private File messagesFile, usersFile, sessionsFile, callsFile, statusesFile, imagesDir, profilesDir;
    private long nextSignalId = System.currentTimeMillis();

    @Override public void onCreate() {
        super.onCreate();
        messagesFile = new File(getFilesDir(), "rk-messages.json");
        usersFile = new File(getFilesDir(), "rk-users.json");
        sessionsFile = new File(getFilesDir(), "rk-sessions.json");
        callsFile = new File(getFilesDir(), "rk-call-signals.json");
        statusesFile = new File(getFilesDir(), "rk-statuses.json");
        imagesDir = new File(getFilesDir(), "rk-images");
        profilesDir = new File(getFilesDir(), "rk-profiles");
        if (!imagesDir.exists()) imagesDir.mkdirs();
        if (!profilesDir.exists()) profilesDir.mkdirs();
        loadAll();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(41, makeNotification());
        if (acceptThread == null || !acceptThread.isAlive()) startServer();
        return START_STICKY;
    }

    private Notification makeNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel("rk_server_status", "Status RK Server", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Menunjukkan server lokal sedang berjalan");
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, "rk_server_status");
        } else builder = new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.rk_icon).setContentTitle("RKM Server berjalan")
                .setContentText("RKM Chat, media, status dan panggilan · port " + PORT)
                .setOngoing(true).setContentIntent(pending).setWhen(System.currentTimeMillis());
        return builder.build();
    }

    private void startServer() {
        stopRequested = false; lastError = "";
        workers = new ThreadPoolExecutor(1, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) { wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RKServer:Cpu"); wakeLock.acquire(); }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) { wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "RKServer:Wifi"); wifiLock.acquire(); }
        } catch (Exception ignored) { }
        acceptThread = new Thread(new Runnable() { @Override public void run() {
            try {
                ServerSocket socket = new ServerSocket(); socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("0.0.0.0", PORT), 24); serverSocket = socket; isRunning = true;
                while (!stopRequested) {
                    try {
                        final Socket client = socket.accept(); client.setSoTimeout(8000);
                        try { workers.execute(new Runnable() { @Override public void run() { handleClient(client); }}); }
                        catch (Exception rejected) { closeQuietly(client); }
                    } catch (SocketException closed) { if (!stopRequested) lastError = "Koneksi server terputus"; break; }
                }
            } catch (Exception e) { if (!stopRequested) lastError = cleanError(e); }
            finally {
                isRunning = false; closeServerSocket(); releaseLocks(); ThreadPoolExecutor pool = workers;
                if (pool != null) pool.shutdownNow(); if (!stopRequested && !TextUtils.isEmpty(lastError)) stopSelf();
            }
        }}, "RKServer-Accept");
        acceptThread.setDaemon(true); acceptThread.start();
    }

    private String cleanError(Exception e) {
        String message = e.getMessage();
        if (e instanceof java.net.BindException) return "Port " + PORT + " sedang dipakai aplikasi lain";
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    private void handleClient(Socket client) {
        try {
            InputStream input = client.getInputStream(); OutputStream output = client.getOutputStream();
            byte[] headerBytes = readHeaders(input); if (headerBytes == null) return;
            String rawHeaders = new String(headerBytes, LATIN1); String[] lines = rawHeaders.split("\r\n");
            if (lines.length == 0) { sendJson(output, 400, "Bad Request", errorJson("Request tidak valid")); return; }
            String[] request = lines[0].split(" ");
            if (request.length < 2) { sendJson(output, 400, "Bad Request", errorJson("Request tidak valid")); return; }
            String method = request[0].toUpperCase(Locale.US); String rawPath = request[1];
            String query = "", path = rawPath; int qi = rawPath.indexOf('?');
            if (qi >= 0) { query = rawPath.substring(qi + 1); path = rawPath.substring(0, qi); }
            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && "content-length".equals(lines[i].substring(0, colon).trim().toLowerCase(Locale.US))) {
                    try { contentLength = Integer.parseInt(lines[i].substring(colon + 1).trim()); } catch (NumberFormatException ignored) { contentLength = -1; }
                }
            }
            if ("OPTIONS".equals(method)) { sendResponse(output, 204, "No Content", "text/plain; charset=utf-8", new byte[0]); return; }
            if (contentLength < 0) { sendJson(output, 400, "Bad Request", errorJson("Content-Length tidak valid")); return; }
            if (contentLength > MAX_BODY_BYTES) { sendJson(output, 413, "Payload Too Large", errorJson("Data terlalu besar. Foto otomatis diperkecil dari web.")); return; }
            if ("GET".equals(method) && "/".equals(path)) {
                sendResponse(output, 200, "OK", "text/html; charset=utf-8", readAsset("index.html"));
            } else if ("GET".equals(method) && "/health".equals(path)) {
                JSONObject r = new JSONObject(); r.put("ok", true); r.put("service", "RKM Server"); r.put("version", "1.0.0");
                r.put("users", users.length()); r.put("messages", getMessageCount()); sendJson(output, 200, "OK", r.toString());
            } else if ("GET".equals(method) && "/api/me".equals(path)) {
                JSONObject u = userForToken(queryParam(query, "token"));
                if (u == null) { sendJson(output, 401, "Unauthorized", errorJson("Sesi habis. Silakan masuk lagi.")); return; }
                sendJson(output, 200, "OK", userPublic(u).toString());
            } else if ("GET".equals(method) && "/api/users".equals(path)) {
                JSONObject me = userForToken(queryParam(query, "token"));
                if (me == null) { sendJson(output, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
                JSONObject result = new JSONObject(); result.put("ok", true); JSONArray list = new JSONArray();
                synchronized (DATA_LOCK) { for (int i=0;i<users.length();i++) { JSONObject u=users.optJSONObject(i); if (u!=null && !u.optString("phone").equals(me.optString("phone"))) list.put(userPublic(u)); } }
                result.put("users", list); sendJson(output, 200, "OK", result.toString());
            } else if ("GET".equals(method) && "/api/messages".equals(path)) {
                getMessages(output, query);
            } else if ("GET".equals(method) && "/api/calls".equals(path)) {
                getCallSignals(output, query);
            } else if ("GET".equals(method) && "/api/status".equals(path)) {
                getStatuses(output, query);
            } else if ("GET".equals(method) && "/api/image".equals(path)) {
                getImage(output, query);
            } else if ("GET".equals(method) && "/api/avatar".equals(path)) {
                getAvatar(output, query);
            } else if ("POST".equals(method) && (path.startsWith("/api/"))) {
                byte[] body = readBody(input, contentLength);
                if (body == null) { sendJson(output, 400, "Bad Request", errorJson("Isi permintaan tidak lengkap")); return; }
                handlePost(output, path, new String(body, UTF8));
            } else sendJson(output, 404, "Not Found", errorJson("Alamat tidak ditemukan"));
        } catch (Exception e) {
            try { sendJson(client.getOutputStream(), 500, "Internal Server Error", errorJson("Kesalahan server. Coba lagi.")); } catch (Exception ignored) { }
        } finally { closeQuietly(client); }
    }

    private void handlePost(OutputStream output, String path, String body) throws IOException {
        try {
            JSONObject data = new JSONObject(body);
            if ("/api/register".equals(path)) { register(output, data); return; }
            if ("/api/login".equals(path)) { login(output, data); return; }
            if ("/api/profile".equals(path)) { updateProfile(output, data); return; }
            if ("/api/profile-photo".equals(path)) { updateProfilePhoto(output, data); return; }
            if ("/api/messages".equals(path)) { postMessage(output, data); return; }
            if ("/api/messages/edit".equals(path)) { editMessage(output, data); return; }
            if ("/api/messages/delete".equals(path)) { deleteMessage(output, data); return; }
            if ("/api/images".equals(path)) { postImage(output, data); return; }
            if ("/api/calls".equals(path)) { postCallSignal(output, data); return; }
            if ("/api/status".equals(path)) { postStatus(output, data); return; }
            sendJson(output, 404, "Not Found", errorJson("API tidak ditemukan"));
        } catch (JSONException e) { sendJson(output, 400, "Bad Request", errorJson("Format data tidak valid")); }
    }

    private void register(OutputStream out, JSONObject d) throws IOException, JSONException {
        String phone = normalizePhone(d.optString("phone", "")); String name = cleanName(d.optString("name", "")); String password = d.optString("password", "");
        if (!validPhone(phone)) { sendJson(out, 400, "Bad Request", errorJson("Nomor HP harus 8–15 angka")); return; }
        if (name.length() < 1) { sendJson(out, 400, "Bad Request", errorJson("Nama wajib diisi")); return; }
        if (password.length() < 6 || password.length() > 100) { sendJson(out, 400, "Bad Request", errorJson("Kata sandi minimal 6 karakter")); return; }
        synchronized (DATA_LOCK) {
            if (findUser(phone) != null) { sendJson(out, 409, "Conflict", errorJson("Nomor ini sudah terdaftar. Silakan masuk.")); return; }
            String salt = UUID.randomUUID().toString(); JSONObject u = new JSONObject();
            u.put("phone", phone); u.put("name", name); u.put("about", "Hai, aku menggunakan RKM Chat"); u.put("salt", salt); u.put("passwordHash", hashPassword(salt, password));
            users.put(u); saveArray(usersFile, users);
            String token = createSession(phone); JSONObject result = userPublic(u); result.put("ok", true); result.put("token", token);
            sendJson(out, 201, "Created", result.toString());
        }
    }

    private void login(OutputStream out, JSONObject d) throws IOException, JSONException {
        String phone = normalizePhone(d.optString("phone", "")); String password = d.optString("password", "");
        synchronized (DATA_LOCK) {
            JSONObject u = findUser(phone);
            if (u == null) { sendJson(out, 401, "Unauthorized", errorJson("Nomor HP atau kata sandi salah")); return; }
            String salt=u.optString("salt", ""), stored=u.optString("passwordHash", "");
            boolean valid;
            if (stored.startsWith("pbkdf2$")) valid=constantTimeEquals(stored, hashPassword(salt,password));
            else valid=constantTimeEquals(stored, legacyHashPassword(salt,password));
            if (!valid) { sendJson(out, 401, "Unauthorized", errorJson("Nomor HP atau kata sandi salah")); return; }
            if (!stored.startsWith("pbkdf2$")) { u.put("passwordHash",hashPassword(salt,password)); saveArray(usersFile,users); }
            String token = createSession(phone); JSONObject result = userPublic(u); result.put("ok", true); result.put("token", token);
            sendJson(out, 200, "OK", result.toString());
        }
    }

    private void updateProfile(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject sessionUser = userForToken(d.optString("token", ""));
        if (sessionUser == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String phone = sessionUser.optString("phone"); String name = cleanName(d.optString("name", sessionUser.optString("name")));
        String about = d.optString("about", sessionUser.optString("about", ""));
        if (name.length()==0 || name.length()>40 || about.length()>120) { sendJson(out, 400, "Bad Request", errorJson("Nama harus 1–40 karakter dan info maksimal 120 karakter")); return; }
        synchronized (DATA_LOCK) { JSONObject u=findUser(phone); if(u==null){sendJson(out,404,"Not Found",errorJson("Akun tidak ditemukan"));return;} u.put("name",name);u.put("about",about);saveArray(usersFile,users);JSONObject r=userPublic(u);r.put("ok",true);sendJson(out,200,"OK",r.toString()); }
    }

    private void postMessage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String sender = me.optString("phone"); String recipient = normalizePhone(d.optString("recipient", "")); String text = d.optString("text", "").trim();
        if (!validPhone(recipient) || samePhone(sender, recipient) || findUser(recipient)==null) { sendJson(out, 400, "Bad Request", errorJson("Pilih nomor teman yang sudah terdaftar")); return; }
        if (text.length()==0 || text.length()>2000) { sendJson(out, 400, "Bad Request", errorJson("Pesan harus 1–2000 karakter")); return; }
        JSONObject msg=newMessage(sender, recipient, text, "text", "");
        synchronized(DATA_LOCK) { appendLimited(messages, msg, MAX_MESSAGES); saveArray(messagesFile,messages); }
        JSONObject r=new JSONObject();r.put("ok",true);r.put("message",msg);sendJson(out,200,"OK",r.toString());
    }

    private void postImage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String sender=me.optString("phone"), recipient=normalizePhone(d.optString("recipient", "")), encoded=d.optString("data", "");
        if (!validPhone(recipient) || samePhone(sender,recipient) || findUser(recipient)==null) { sendJson(out,400,"Bad Request",errorJson("Pilih teman yang sudah terdaftar")); return; }
        if (encoded.length()==0 || encoded.length()>((MAX_IMAGE_BYTES*4/3)+1000)) { sendJson(out,413,"Payload Too Large",errorJson("Foto terlalu besar")); return; }
        byte[] image;
        try { image=Base64.decode(encoded,Base64.DEFAULT); } catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Data gambar tidak valid"));return;}
        if(image.length==0||image.length>MAX_IMAGE_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Foto maksimal sekitar 180 KB setelah dikompres"));return;}
        String id=UUID.randomUUID().toString().replace("-","");File file=new File(imagesDir,id+".jpg");
        FileOutputStream fos=null;try{fos=new FileOutputStream(file);fos.write(image);fos.flush();fos.getFD().sync();}catch(Exception e){if(file.exists())file.delete();sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan foto"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
        JSONObject msg=newMessage(sender,recipient,"","image",id);
        synchronized(DATA_LOCK){appendLimited(messages,msg,MAX_MESSAGES);saveArray(messagesFile,messages);}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("message",msg);sendJson(out,200,"OK",r.toString());
    }

    private void editMessage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String id = d.optString("id", ""), text = d.optString("text", "").trim();
        if (id.length() == 0 || text.length() == 0 || text.length() > 2000) { sendJson(out, 400, "Bad Request", errorJson("Pesan edit tidak valid")); return; }
        synchronized (DATA_LOCK) {
            for (int i=0; i<messages.length(); i++) {
                JSONObject m=messages.optJSONObject(i);
                if (m!=null && id.equals(m.optString("id"))) {
                    if (!samePhone(me.optString("phone"), m.optString("sender"))) { sendJson(out,403,"Forbidden",errorJson("Hanya pengirim yang dapat mengedit pesan ini")); return; }
                    if (!"text".equals(m.optString("type"))) { sendJson(out,400,"Bad Request",errorJson("Hanya pesan teks yang dapat diedit")); return; }
                    m.put("text",text); m.put("edited",true); m.put("editedAt",timeNow()); saveArray(messagesFile,messages);
                    JSONObject r=new JSONObject();r.put("ok",true);r.put("message",m);sendJson(out,200,"OK",r.toString());return;
                }
            }
        }
        sendJson(out,404,"Not Found",errorJson("Pesan tidak ditemukan"));
    }

    private void deleteMessage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String id=d.optString("id", ""); if(id.length()==0){sendJson(out,400,"Bad Request",errorJson("ID pesan kosong"));return;}
        synchronized(DATA_LOCK) {
            JSONArray next=new JSONArray(); boolean found=false;
            for(int i=0;i<messages.length();i++) {
                JSONObject m=messages.optJSONObject(i);
                if(m!=null && id.equals(m.optString("id"))) {
                    found=true;
                    if(!samePhone(me.optString("phone"),m.optString("sender"))) { sendJson(out,403,"Forbidden",errorJson("Hanya pengirim yang dapat menghapus pesan untuk semua")); return; }
                    continue;
                }
                if(m!=null) next.put(m);
            }
            if(!found){sendJson(out,404,"Not Found",errorJson("Pesan tidak ditemukan"));return;}
            messages=next;saveArray(messagesFile,messages);
        }
        JSONObject r=new JSONObject();r.put("ok",true);sendJson(out,200,"OK",r.toString());
    }

    private void updateProfilePhoto(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token", ""));
        if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String encoded=d.optString("data", ""); if(encoded.length()==0 || encoded.length()>((MAX_IMAGE_BYTES*4/3)+1000)){sendJson(out,413,"Payload Too Large",errorJson("Foto profil terlalu besar"));return;}
        byte[] image;try{image=Base64.decode(encoded,Base64.DEFAULT);}catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Foto profil tidak valid"));return;}
        if(image.length==0||image.length>MAX_IMAGE_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Foto profil maksimal 180 KB"));return;}
        File file=new File(profilesDir,me.optString("phone")+".jpg");FileOutputStream fos=null;
        try{fos=new FileOutputStream(file);fos.write(image);fos.flush();fos.getFD().sync();}catch(Exception e){sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan foto profil"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
        JSONObject r=new JSONObject();r.put("ok",true);sendJson(out,200,"OK",r.toString());
    }

    private void getAvatar(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=normalizePhone(queryParam(query,"phone"));if(!validPhone(phone)||findUser(phone)==null){sendJson(out,404,"Not Found",errorJson("Profil tidak ditemukan"));return;}
        File f=new File(profilesDir,phone+".jpg");if(!f.exists()||f.length()>MAX_IMAGE_BYTES){sendJson(out,404,"Not Found",errorJson("Foto profil belum dipasang"));return;}
        ByteArrayOutputStream buf=new ByteArrayOutputStream();FileInputStream in=null;
        try{in=new FileInputStream(f);byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)buf.write(b,0,n);}finally{if(in!=null)try{in.close();}catch(Exception ignored){}}
        sendResponse(out,200,"OK","image/jpeg",buf.toByteArray());
    }

    private JSONObject newMessage(String sender, String recipient, String text, String type, String imageId) throws JSONException {
        JSONObject s=findUser(sender), r=findUser(recipient); JSONObject msg=new JSONObject();
        msg.put("id",UUID.randomUUID().toString());msg.put("sender",sender);msg.put("recipient",recipient);
        msg.put("senderName",s==null?sender:s.optString("name",sender));msg.put("recipientName",r==null?recipient:r.optString("name",recipient));
        msg.put("text",text);msg.put("type",type);msg.put("imageId",imageId);msg.put("time",timeNow());return msg;
    }

    private void getMessages(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token")); if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=me.optString("phone"), peer=normalizePhone(queryParam(query,"peer")); JSONArray copy=new JSONArray();
        synchronized(DATA_LOCK){for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m==null)continue;String s=m.optString("sender"),r=m.optString("recipient");boolean allowed=(samePhone(s,phone)||samePhone(r,phone));if(!allowed)continue;if(peer.length()>0 && !((samePhone(s,phone)&&samePhone(r,peer))||(samePhone(s,peer)&&samePhone(r,phone))))continue;copy.put(m);}}
        JSONObject result=new JSONObject();result.put("ok",true);result.put("messages",copy);result.put("count",copy.length());sendJson(out,200,"OK",result.toString());
    }

    private void postCallSignal(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token", "")); if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String from=me.optString("phone"), to=normalizePhone(d.optString("to", "")), type=d.optString("type", ""), callId=d.optString("callId", "");
        if(!validPhone(to)||samePhone(from,to)||findUser(to)==null){sendJson(out,400,"Bad Request",errorJson("Nomor teman tidak valid"));return;}
        if(!(type.equals("offer")||type.equals("answer")||type.equals("ice")||type.equals("end")||type.equals("reject")||type.equals("busy"))){sendJson(out,400,"Bad Request",errorJson("Jenis sinyal tidak valid"));return;}
        if(callId.length()==0||callId.length()>80){sendJson(out,400,"Bad Request",errorJson("ID panggilan tidak valid"));return;}
        JSONObject payload=d.optJSONObject("payload"); if(payload==null)payload=new JSONObject();
        JSONObject sig=new JSONObject(); sig.put("id",nextSignalId());sig.put("from",from);sig.put("fromName",me.optString("name",from));sig.put("to",to);sig.put("type",type);sig.put("callId",callId);sig.put("payload",payload);sig.put("time",System.currentTimeMillis());
        synchronized(DATA_LOCK){appendLimited(callSignals,sig,MAX_CALL_SIGNALS);saveArray(callsFile,callSignals);}
        JSONObject r=new JSONObject();r.put("ok",true);sendJson(out,200,"OK",r.toString());
    }

    private void getCallSignals(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=me.optString("phone");long since=0;try{since=Long.parseLong(queryParam(query,"since"));}catch(Exception ignored){}
        JSONArray resultList=new JSONArray();long now=System.currentTimeMillis();
        synchronized(DATA_LOCK){for(int i=0;i<callSignals.length();i++){JSONObject s=callSignals.optJSONObject(i);if(s==null)continue;long id=s.optLong("id",0);if(samePhone(s.optString("to"),phone)&&id>since&&now-s.optLong("time",now)<120000)resultList.put(s);}}
        JSONObject result=new JSONObject();result.put("ok",true);result.put("signals",resultList);sendJson(out,200,"OK",result.toString());
    }

    private void postStatus(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token", ""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String text=d.optString("text", "").trim();if(text.length()==0||text.length()>240){sendJson(out,400,"Bad Request",errorJson("Status harus 1–240 karakter"));return;}
        JSONObject st=new JSONObject();st.put("id",UUID.randomUUID().toString());st.put("phone",me.optString("phone"));st.put("name",me.optString("name"));st.put("text",text);st.put("time",System.currentTimeMillis());
        synchronized(DATA_LOCK){appendLimited(statuses,st,MAX_STATUSES);saveArray(statusesFile,statuses);}JSONObject r=new JSONObject();r.put("ok",true);r.put("status",st);sendJson(out,200,"OK",r.toString());
    }

    private void getStatuses(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        JSONArray list=new JSONArray();long min=System.currentTimeMillis()-24L*60L*60L*1000L;
        synchronized(DATA_LOCK){for(int i=0;i<statuses.length();i++){JSONObject s=statuses.optJSONObject(i);if(s!=null&&s.optLong("time",0)>=min)list.put(s);}}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("statuses",list);sendJson(out,200,"OK",r.toString());
    }

    private void getImage(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String id=queryParam(query,"id");if(id.length()<16||id.length()>64||!id.matches("[a-fA-F0-9]+")){sendJson(out,400,"Bad Request",errorJson("ID foto tidak valid"));return;}
        boolean authorized=false;String phone=me.optString("phone");
        synchronized(DATA_LOCK){for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&id.equals(m.optString("imageId"))&&(samePhone(phone,m.optString("sender"))||samePhone(phone,m.optString("recipient")))){authorized=true;break;}}}
        if(!authorized){sendJson(out,404,"Not Found",errorJson("Foto tidak ditemukan"));return;}
        File f=new File(imagesDir,id+".jpg");if(!f.exists()||f.length()>MAX_IMAGE_BYTES){sendJson(out,404,"Not Found",errorJson("Foto tidak ditemukan"));return;}
        FileInputStream in=null;ByteArrayOutputStream buf=new ByteArrayOutputStream();try{in=new FileInputStream(f);byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)buf.write(b,0,n);}finally{if(in!=null)try{in.close();}catch(Exception ignored){}}
        sendResponse(out,200,"OK","image/jpeg",buf.toByteArray());
    }

    private void loadAll(){ synchronized(DATA_LOCK){messages=loadArray(messagesFile,MAX_MESSAGES);users=loadArray(usersFile,1000);sessions=loadArray(sessionsFile,2000);callSignals=loadArray(callsFile,MAX_CALL_SIGNALS);statuses=loadArray(statusesFile,MAX_STATUSES);for(int i=0;i<callSignals.length();i++){JSONObject s=callSignals.optJSONObject(i);if(s!=null)nextSignalId=Math.max(nextSignalId,s.optLong("id",0));}} }
    private JSONArray loadArray(File file,int limit){if(file==null||!file.exists())return new JSONArray();try{JSONArray a=new JSONArray(readFile(file));JSONArray b=new JSONArray();int from=Math.max(0,a.length()-limit);for(int i=from;i<a.length();i++)b.put(a.get(i));return b;}catch(Exception e){return new JSONArray();}}
    private String readFile(File f)throws IOException{FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);in.close();return new String(out.toByteArray(),UTF8);}
    private void saveArray(File f,JSONArray a){File temp=new File(getFilesDir(),f.getName()+".tmp");try{FileOutputStream o=new FileOutputStream(temp);o.write(a.toString().getBytes(UTF8));o.flush();o.getFD().sync();o.close();if(f.exists()&&!f.delete())throw new IOException("replace failed");if(!temp.renameTo(f))throw new IOException("rename failed");}catch(Exception e){lastError="Gagal menyimpan data";try{if(temp.exists())temp.delete();}catch(Exception ignored){}}}
    private void appendLimited(JSONArray a,JSONObject item,int max)throws JSONException{JSONArray n=new JSONArray();int from=Math.max(0,a.length()-max+1);for(int i=from;i<a.length();i++)n.put(a.get(i));n.put(item);if(a==messages)messages=n;else if(a==callSignals)callSignals=n;else if(a==statuses)statuses=n;else if(a==sessions)sessions=n;else if(a==users)users=n;}
    private JSONObject findUser(String phone){if(phone==null)return null;for(int i=0;i<users.length();i++){JSONObject u=users.optJSONObject(i);if(u!=null&&samePhone(phone,u.optString("phone")))return u;}return null;}
    private JSONObject userForToken(String token){if(token==null||token.length()==0)return null; synchronized(DATA_LOCK){for(int i=0;i<sessions.length();i++){JSONObject s=sessions.optJSONObject(i);if(s!=null&&constantTimeEquals(token,s.optString("token", ""))){JSONObject u=findUser(s.optString("phone"));if(u!=null)return u;}}}return null;}
    private String createSession(String phone)throws JSONException{String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");JSONObject s=new JSONObject();s.put("phone",phone);s.put("token",token);s.put("time",System.currentTimeMillis());appendLimited(sessions,s,2000);saveArray(sessionsFile,sessions);return token;}
    private JSONObject userPublic(JSONObject u)throws JSONException{JSONObject r=new JSONObject();r.put("phone",u.optString("phone"));r.put("name",u.optString("name"));r.put("about",u.optString("about",""));return r;}
    private String normalizePhone(String p){if(p==null)return "";return p.replaceAll("[^0-9]", "");}
    private boolean validPhone(String p){return p!=null&&p.matches("[0-9]{8,15}");}
    private String cleanName(String n){if(n==null)return "";n=n.trim();if(n.length()>40)n=n.substring(0,40);return n;}
    private String hashPassword(String salt,String password){try{PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt.getBytes(UTF8),30000,256);byte[] b=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();spec.clearPassword();StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x&0xff));return "pbkdf2$30000$"+s.toString();}catch(Exception e){return "";}}
    private String legacyHashPassword(String salt,String password){try{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=md.digest((salt+":"+password).getBytes(UTF8));StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x&0xff));return s.toString();}catch(Exception e){return "";}}
    private boolean constantTimeEquals(String a,String b){if(a==null||b==null)return false;int diff=a.length()^b.length();int n=Math.max(a.length(),b.length());for(int i=0;i<n;i++){char x=i<a.length()?a.charAt(i):0,y=i<b.length()?b.charAt(i):0;diff|=x^y;}return diff==0;}
    private boolean samePhone(String a,String b){return a!=null&&b!=null&&a.equals(b);}
    private long nextSignalId(){nextSignalId=Math.max(nextSignalId+1,System.currentTimeMillis());return nextSignalId;}
    private String timeNow(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date());}
    private String queryParam(String query,String wanted){if(query==null||query.length()==0)return "";String[] pairs=query.split("&");for(String pair:pairs){int e=pair.indexOf('=');String k=e>=0?pair.substring(0,e):pair,v=e>=0?pair.substring(e+1):"";try{k=URLDecoder.decode(k,"UTF-8");v=URLDecoder.decode(v,"UTF-8");}catch(Exception ignored){}if(wanted.equals(k))return v;}return "";}
    private JSONObject errorJson(String msg){JSONObject o=new JSONObject();try{o.put("ok",false);o.put("error",msg);}catch(JSONException ignored){}return o;}
    private int getMessageCount(){synchronized(DATA_LOCK){return messages.length();}}
    private byte[] readHeaders(InputStream input)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int state=0;while(b.size()<MAX_HEADER_BYTES){int c=input.read();if(c<0)return null;b.write(c);if(state==0&&c=='\r')state=1;else if(state==1&&c=='\n')state=2;else if(state==2&&c=='\r')state=3;else if(state==3&&c=='\n')return b.toByteArray();else if(c=='\r')state=1;else state=0;}throw new IOException("Header terlalu besar");}
    private byte[] readBody(InputStream input,int length)throws IOException{byte[] r=new byte[length];int total=0;while(total<length){int n=input.read(r,total,length-total);if(n<0)return null;total+=n;}return r;}
    private byte[] readAsset(String name)throws IOException{InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);in.close();return out.toByteArray();}
    private void sendJson(OutputStream out,int code,String reason,String json)throws IOException{sendResponse(out,code,reason,"application/json; charset=utf-8",json.getBytes(UTF8));}
    // Build fix: accept JSONObject responses and serialize them to JSON text.
    private void sendJson(OutputStream out,int code,String reason,JSONObject json)throws IOException{sendJson(out,code,reason,json==null?"{}":json.toString());}
    private void sendResponse(OutputStream out,int code,String reason,String type,byte[] body)throws IOException{String header="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\n\r\n";out.write(header.getBytes(LATIN1));out.write(body);out.flush();}
    private void closeServerSocket(){ServerSocket s=serverSocket;serverSocket=null;if(s!=null)try{s.close();}catch(Exception ignored){}}
    private void releaseLocks(){try{if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}catch(Exception ignored){}try{if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();}catch(Exception ignored){}wakeLock=null;wifiLock=null;}
    private void closeQuietly(Socket s){if(s!=null)try{s.close();}catch(Exception ignored){}}
    @Override public void onDestroy(){stopRequested=true;isRunning=false;closeServerSocket();releaseLocks();ThreadPoolExecutor p=workers;if(p!=null)p.shutdownNow();if(acceptThread!=null)acceptThread.interrupt();stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
