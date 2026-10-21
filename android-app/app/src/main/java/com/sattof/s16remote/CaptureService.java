package com.sattof.s16remote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.AudioManager;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Surface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public class CaptureService extends Service {
    public static volatile CaptureService running;
    public static final String TOKEN = makeToken();
    private final ExecutorService workers = Executors.newFixedThreadPool(6);
    private ServerSocket listener;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private int screenW=1280, screenH=720, density=160;
    private volatile byte[] lastFrame;
    private volatile boolean stopped=false;

    private static String makeToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) return START_NOT_STICKY;
        if (running != null && running != this) return START_NOT_STICKY;
        showNotification();
        try {
            if (projection == null) {
                android.view.WindowManager wm =
                    (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
                DisplayMetrics metrics = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(metrics);
                screenW=metrics.widthPixels; screenH=metrics.heightPixels;
                density=metrics.densityDpi;
                MediaProjectionManager pm = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
                projection=pm.getMediaProjection(intent.getIntExtra("code", Activity.RESULT_CANCELED),
                    intent.getParcelableExtra("data"));
                if (projection == null) throw new IOException("Projection denied");
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { stopSelf(); }
                }, new Handler(Looper.getMainLooper()));
                reader=ImageReader.newInstance(screenW,screenH,PixelFormat.RGBA_8888,2);
                display=projection.createVirtualDisplay("S16 phone screen",screenW,screenH,
                    density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(),null,null);
            }
            if (listener == null) {
                listener = new ServerSocket(8766,16,InetAddress.getByName("0.0.0.0"));
                running=this;
                Thread t = new Thread(() -> {
                    while (!stopped) {
                        try {
                            Socket socket=listener.accept();
                            workers.execute(() -> serve(socket));
                        } catch (Exception ignored) { if (stopped) break; }
                    }
                }, "S16 http listener");
                t.setDaemon(true);t.start();
            }
        } catch (Exception e) {
            android.util.Log.e("S16", "Could not start screen server",e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void showNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String channel="screen-share";
        nm.createNotificationChannel(new NotificationChannel(channel,
            "S16 screen sharing",NotificationManager.IMPORTANCE_LOW));
        Intent i=new Intent(this,MainActivity.class);
        PendingIntent p=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,channel)
            .setContentTitle("S16 phone remote active")
            .setContentText("Your screen is being shared on your local Wi-Fi")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(p).build();
        startForeground(123,n);
    }

    @Override public void onDestroy() {
        stopped=true;
        running=null;
        try { if (listener!=null) listener.close(); } catch(Exception ignored){}
        workers.shutdownNow();
        if (display!=null) display.release();
        if (reader!=null) reader.close();
        if (projection!=null) projection.stop();
        super.onDestroy();
    }

    private static String value(String src, String key) {
        if (src==null) return "";
        for (String part: src.split("&")) {
            int index=part.indexOf('=');
            if (index<0) continue;
            String k=URLDecoder.decode(part.substring(0,index),"UTF-8");
            if (k.equals(key)) return URLDecoder.decode(part.substring(index+1),"UTF-8");
        }
        return "";
    }
    private static boolean equal(String a,String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8));
    }
    private void serve(Socket socket) {
        try (Socket s=socket) {
            s.setSoTimeout(10000);
            BufferedInputStream input=new BufferedInputStream(s.getInputStream());
            ByteArrayOutputStream header=new ByteArrayOutputStream();
            int state=0,n;
            while(header.size()<10000 && (n=input.read())!=-1) {
                header.write(n);
                state = (state==0 && n=='\r')?1:
                        (state==1 && n=='\n')?2:
                        (state==2 && n=='\r')?3:
                        (state==3 && n=='\n')?4:0;
                if(state==4) break;
            }
            if(state!=4) return;
            String request=header.toString("UTF-8");
            String[] lines=request.split("\r\n");
            if(lines.length<1) return;
            String[] first=lines[0].split(" ");
            if(first.length<2) return;
            String method=first[0], target=first[1];
            URI uri=URI.create(target);
            String path=uri.getPath();
            int length=0;
            String cookie="";
            for(String line:lines) {
                String low=line.toLowerCase(Locale.ROOT);
                if(low.startsWith("content-length:"))
                    length=Integer.parseInt(line.substring(15).trim());
                if(low.startsWith("cookie:")) cookie=line.substring(7).trim();
            }
            boolean qrAuth=equal(value(uri.getRawQuery(),"k"),TOKEN);
            boolean cookieAuth=false;
            for(String c:cookie.split(";")) {
                String v=c.trim();
                if(v.startsWith("s16=") && equal(v.substring(4),TOKEN)) cookieAuth=true;
            }
            if(!qrAuth && !cookieAuth) {
                respond(s,403,"text/plain","Access denied. Scan QR from receiver.".getBytes(StandardCharsets.UTF_8),false);
                return;
            }
            if(method.equals("GET") && path.equals("/")) {
                byte[] html=readAsset("remote.html");
                respond(s,200,"text/html; charset=utf-8",html,qrAuth);
            } else if(method.equals("GET") && path.equals("/api/status")) {
                JSONObject out=new JSONObject();
                out.put("sharing",true);
                out.put("touch",ControlAccessibility.instance!=null);
                out.put("width",screenW);out.put("height",screenH);
                respondJson(s,out);
            } else if(method.equals("GET") && path.equals("/api/screen")) {
                byte[] data=capture();
                if(data==null) respond(s,503,"text/plain","Waiting for screen".getBytes(StandardCharsets.UTF_8),false);
                else respond(s,200,"image/jpeg",data,false);
            } else if(method.equals("GET") && path.equals("/api/apps")) {
                JSONArray arr=new JSONArray();
                for(android.content.pm.ApplicationInfo a:getPackageManager().getInstalledApplications(0)) {
                    if(getPackageManager().getLaunchIntentForPackage(a.packageName)!=null)
                        arr.put(a.packageName);
                }
                JSONObject obj=new JSONObject();obj.put("apps",arr);
                respondJson(s,obj);
            } else if(method.equals("POST") && path.equals("/api/action")) {
                if(length<1 || length>4096) { respond(s,400,"text/plain",new byte[0],false);return; }
                byte[] body=new byte[length];
                int read=0;
                while(read<length) {
                    int got=input.read(body,read,length-read);
                    if(got<0) return;
                    read+=got;
                }
                String data=new String(body,StandardCharsets.UTF_8);
                JSONObject result=act(data);
                respondJson(s,result);
            } else {
                respond(s,404,"text/plain","Not found".getBytes(StandardCharsets.UTF_8),false);
            }
        } catch(Exception e) { android.util.Log.w("S16","Network issue",e); }
    }

    private JSONObject act(String body) throws Exception {
        String type=value(body,"type");
        ControlAccessibility c=ControlAccessibility.instance;
        JSONObject result=new JSONObject();boolean ok=true;
        if(type.equals("settings")) {
            Intent intent=new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else if(type.equals("launch")) {
            String pkg=value(body,"package");
            if(pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
                Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
                if(i==null) ok=false;
                else {i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);}
            } else ok=false;
        } else if(type.equals("volup") || type.equals("voldown")) {
            AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                type.equals("volup")?AudioManager.ADJUST_RAISE:AudioManager.ADJUST_LOWER,0);
        } else if(type.equals("tap") || type.equals("swipe")) {
            if(c==null) ok=false;
            else {
                int x=number(value(body,"x"),screenW);
                int y=number(value(body,"y"),screenH);
                int x2=number(value(body,"x2"),screenW);
                int y2=number(value(body,"y2"),screenH);
                c.gesture(x,y,type.equals("tap")?x:x2,type.equals("tap")?y:y2,
                    type.equals("tap")?110:350);
            }
        } else if(type.equals("text")) {
            String txt=value(body,"text");
            if(c==null || txt.length()>500) ok=false;
            else ok=c.setFocusedText(txt);
        } else if(Arrays.asList("home","back","recents","notifications").contains(type)) {
            if(c==null) ok=false;
            else c.navigation(type);
        } else ok=false;
        result.put("ok",ok);
        result.put("message",ok?"Command submitted": "Enable Accessibility / check app or input");
        return result;
    }
    private int number(String x,int bound) {
        try { return Math.max(0,Math.min(bound-1,Integer.parseInt(x))); }
        catch(Exception e) {return 0;}
    }

    private synchronized byte[] capture() {
        if(reader==null) return null;
        Image img=null;
        try {
            img=reader.acquireLatestImage();
            if(img==null) return lastFrame;
            Image.Plane p=img.getPlanes()[0];
            ByteBuffer bytes=p.getBuffer();
            int stride=p.getRowStride();
            int pixel=p.getPixelStride();
            int padded=screenW+(stride-pixel*screenW)/pixel;
            Bitmap wide=Bitmap.createBitmap(padded,screenH,Bitmap.Config.ARGB_8888);
            wide.copyPixelsFromBuffer(bytes);
            Bitmap cropped=Bitmap.createBitmap(wide,0,0,screenW,screenH);
            ByteArrayOutputStream baos=new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG,65,baos);
            lastFrame=baos.toByteArray();
            cropped.recycle();wide.recycle();
            return lastFrame;
        } catch(Exception e) {
            android.util.Log.w("S16","Capture failure",e);
            return lastFrame;
        } finally {if(img!=null) img.close();}
    }

    private byte[] readAsset(String path) throws IOException {
        try(InputStream in=getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))>=0)out.write(buf,0,n);
            return out.toByteArray();
        }
    }
    private void respondJson(Socket s,JSONObject obj) throws IOException {
        respond(s,200,"application/json; charset=utf-8",
            obj.toString().getBytes(StandardCharsets.UTF_8),false);
    }
    private void respond(Socket s,int status,String mime,byte[] body,boolean cookie) throws IOException {
        String label=status==200?"OK":status==403?"Forbidden":status==404?"Not Found":"Unavailable";
        OutputStream out=s.getOutputStream();
        String head="HTTP/1.1 "+status+" "+label+"\r\nContent-Type: "+mime+
            "\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\n"+
            "X-Content-Type-Options: nosniff\r\n"+
            "Connection: close\r\n"+
            (cookie?"Set-Cookie: s16="+TOKEN+"; Path=/; HttpOnly; SameSite=Strict\r\n":"")+
            "\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));out.write(body);out.flush();
    }
}
