package com.sattof.s16remote;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends Activity {
    private static final int CAPTURE = 200;
    private TextView status;
    private ImageView qr;

    public static String localIp() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface net = nets.nextElement();
                if (!net.isUp() || net.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addresses = net.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress a = addresses.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "RECEIVER_IP";
    }

    private TextView label(String text, int size) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextSize(size); v.setTextColor(Color.WHITE);
        v.setPadding(14, 12, 14, 12);
        v.setGravity(android.view.Gravity.CENTER);
        return v;
    }

    private Button button(String text, Runnable click) {
        Button v = new Button(this);
        v.setText(text); v.setTextSize(20);
        v.setOnClickListener(view -> click.run());
        return v;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(17, 27, 44));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 22, 28, 22);
        scroll.addView(root);
        root.addView(label("S16 Phone Remote", 30));
        root.addView(label("Use only with your trusted home Wi-Fi. Do not port-forward port 8766.", 17));
        root.addView(button("1. Allow Remote Touch (Accessibility)", () -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }));
        root.addView(button("2. Start Screen Sharing", () -> {
            MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mgr.createScreenCaptureIntent(), CAPTURE);
        }));
        status = label("Waiting for screen sharing permission...", 18);
        root.addView(status);
        qr = new ImageView(this);
        root.addView(qr, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 360));
        root.addView(label("Scan the QR with the iPhone Camera. No commands or password typing required.", 18));
        root.addView(button("Refresh QR / Status", this::refresh));
        root.addView(button("Stop Sharing", () -> {
            stopService(new Intent(this, CaptureService.class));
            refresh();
        }));
        setContentView(scroll);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) refresh();
    }

    private void refresh() {
        boolean sharing = CaptureService.running != null;
        boolean touch = ControlAccessibility.instance != null;
        String url = "http://" + localIp() + ":8766/?k=" + CaptureService.TOKEN;
        status.setText("Screen: " + (sharing ? "ON" : "OFF")
            + "     Remote Touch: " + (touch ? "ON" : "OFF")
            + "\nWi-Fi address: " + localIp() + ":8766");
        try {
            BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 440, 440);
            Bitmap b = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.RGB_565);
            for (int y=0; y<m.getHeight(); y++)
                for (int x=0; x<m.getWidth(); x++)
                    b.setPixel(x,y,m.get(x,y) ? Color.BLACK : Color.WHITE);
            qr.setImageBitmap(b);
        } catch (WriterException ignored) {}
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class);
            service.putExtra("code", resultCode);
            service.putExtra("data", data);
            startForegroundService(service);
            status.postDelayed(this::refresh, 1000);
        }
    }
}
