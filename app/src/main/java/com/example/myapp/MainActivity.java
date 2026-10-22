package com.example.myapp;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity
        implements SensorEventListener, LocationListener {

    private static final int LOCATION_REQUEST = 1001;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private LocationManager locationManager;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    private final float[] gravity = new float[3];
    private final float[] magnetic = new float[3];

    private boolean hasGravity = false;
    private boolean hasMagnetic = false;
    private boolean hasLocation = false;
    private boolean qiblaMode = false;
    private boolean headingLocked = false;
    private boolean webPageReady = false;
    private int pendingWebPage = 2;

    private double latitude = 0;
    private double longitude = 0;
    private double qiblaBearing = 0;

    private float heading = 0;
    private int currentPage = 0;

    private TextView degreeText;
    private TextView directionText;
    private TextView infoText;
    private TextView statusText;
    private Button lockButton;

    private CompassView compassView;
    private WebView webView;
    private LinearLayout root;
    private FrameLayout contentFrame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        createInterface();
        createWebView();
        showPage(0);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView makeText(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        return b;
    }

    private void createInterface() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(8));
        root.setBackgroundColor(Color.rgb(12, 17, 30));

        root.addView(makeText("COMPASS V3.1", 25, Color.WHITE, true));
        root.addView(makeText("البوصلة • القبلة • الجغرافيا", 15, Color.LTGRAY, false));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);

        String[] names = {"البوصلة", "القبلة", "الخريطة", "الكرة 3D"};
        for (int i = 0; i < names.length; i++) {
            final int page = i;
            Button button = makeButton(names[i]);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f);
            p.setMargins(dp(2), dp(8), dp(2), dp(8));
            tabs.addView(button, p);
            button.setOnClickListener(v -> showPage(page));
        }
        root.addView(tabs);

        contentFrame = new FrameLayout(this);
        root.addView(contentFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        statusText = makeText("جاهز • بانتظار المستشعرات", 13, Color.LTGRAY, false);
        root.addView(statusText);
        setContentView(root);
    }

    private void createWebView() {
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        webView.setBackgroundColor(Color.rgb(12, 17, 30));

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webPageReady = true;
                runWebPage(pendingWebPage);
                if (hasLocation) sendLocationToWeb();
            }
        });

        webView.loadDataWithBaseURL(
                "https://localhost/",
                createMapHtml(),
                "text/html",
                "UTF-8",
                null);
    }

    private String createMapHtml() {
        return "<!doctype html><html><head>"
        + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
        + "<style>"
        + "html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#0c111e;color:white;font-family:sans-serif}"
        + "canvas{display:block;width:100%;height:100%}"
        + "#label{position:absolute;left:8px;right:8px;bottom:8px;padding:8px;border-radius:8px;"
        + "background:#101827dd;font-size:12px;text-align:center}"
        + "</style></head><body><canvas id='c'></canvas><div id='label'>خطوط جغرافية توضيحية • اضغط واسحب للتدوير</div>"
        + "<script>"
        + "var c=document.getElementById('c'),ctx=c.getContext('2d'),mode='map',latNow=null,lonNow=null,rot=0;"
        + "function resize(){var d=window.devicePixelRatio||1;c.width=Math.max(1,innerWidth*d);c.height=Math.max(1,innerHeight*d);ctx.setTransform(d,0,0,d,0,0);draw();}"
        + "function line(a,b,color,w){ctx.strokeStyle=color;ctx.lineWidth=w;ctx.beginPath();ctx.moveTo(a[0],a[1]);ctx.lineTo(b[0],b[1]);ctx.stroke();}"
        + "function mapXY(lat,lon){return [(lon+180)*innerWidth/360,(90-lat)*innerHeight/180];}"
        + "function drawMap(){ctx.fillStyle='#101a2d';ctx.fillRect(0,0,innerWidth,innerHeight);"
        + "for(var lon=-180;lon<=180;lon+=30){var a=mapXY(-90,lon),b=mapXY(90,lon);line(a,b,'#33445f',1)}"
        + "for(var lat=-60;lat<=60;lat+=30){var a=mapXY(lat,-180),b=mapXY(lat,180);line(a,b,'#33445f',1)}"
        + "/* simplified continent silhouettes */ctx.fillStyle='#263d55';"
        + "function poly(points){ctx.beginPath();points.forEach(function(p,i){var q=mapXY(p[0],p[1]);if(i===0)ctx.moveTo(q[0],q[1]);else ctx.lineTo(q[0],q[1]);});ctx.closePath();ctx.fill();}"
        + "poly([[70,-165],[55,-130],[50,-105],[25,-80],[8,-78],[10,-60],[30,-45],[50,-55],[65,-80],[72,-110]]);"
        + "poly([[12,-80],[-5,-70],[-25,-65],[-55,-75],[-35,-45],[-5,-35],[10,-50]]);"
        + "poly([[70,-10],[60,40],[35,55],[5,40],[-35,20],[-30,-5],[5,-15],[35,-10],[50,5]]);"
        + "poly([[70,40],[60,100],[45,150],[10,120],[0,80],[20,55],[40,40]]);"
        + "poly([[-10,110],[-20,150],[-40,150],[-45,115],[-20,110]]);"
        + "var lines=[{lat:0,col:'#00e5ff'},{lat:23.44,col:'#ffb74d'},{lat:-23.44,col:'#ffb74d'},{lat:66.56,col:'#ce93d8'},{lat:-66.56,col:'#ce93d8'}];"
        + "lines.forEach(function(o){var a=mapXY(o.lat,-180),b=mapXY(o.lat,180);line(a,b,o.col,1.5)});"
        + "var a=mapXY(-90,0),b=mapXY(90,0);line(a,b,'#ffffff',1.5);"
        + "if(latNow!==null){var p=mapXY(latNow,lonNow);ctx.fillStyle='#ff5252';ctx.beginPath();ctx.arc(p[0],p[1],6,0,Math.PI*2);ctx.fill();ctx.strokeStyle='white';ctx.lineWidth=2;ctx.stroke();}"
        + "ctx.fillStyle='white';ctx.font='13px sans-serif';ctx.fillText('خريطة جغرافية توضيحية (دون بلاطات الإنترنت)',8,18);}"
        + "function drawGlobe(){ctx.fillStyle='#0c111e';ctx.fillRect(0,0,innerWidth,innerHeight);"
        + "var R=Math.min(innerWidth,innerHeight)*0.39,cx=innerWidth/2,cy=innerHeight/2;"
        + "var g=ctx.createRadialGradient(cx-R*.35,cy-R*.4,R*.05,cx,cy,R);g.addColorStop(0,'#326b91');g.addColorStop(1,'#10253f');"
        + "ctx.fillStyle=g;ctx.beginPath();ctx.arc(cx,cy,R,0,Math.PI*2);ctx.fill();ctx.save();ctx.beginPath();ctx.arc(cx,cy,R,0,Math.PI*2);ctx.clip();"
        + "ctx.strokeStyle='#8dd7ee';ctx.lineWidth=1.2;"
        + "for(var lon=-150;lon<=180;lon+=30){ctx.beginPath();for(var j=0;j<=180;j++){var la=-90+j;var x=cx+R*Math.cos(la*Math.PI/180)*Math.sin((lon+rot)*Math.PI/180);var y=cy-R*Math.sin(la*Math.PI/180);if(j===0)ctx.moveTo(x,y);else ctx.lineTo(x,y);}ctx.stroke();}"
        + "var parallels=[{lat:0,col:'#00e5ff'},{lat:23.44,col:'#ffb74d'},{lat:-23.44,col:'#ffb74d'},{lat:66.56,col:'#ce93d8'},{lat:-66.56,col:'#ce93d8'}];"
        + "parallels.forEach(function(o){ctx.strokeStyle=o.col;ctx.beginPath();for(var i=0;i<=360;i++){var lo=i*Math.PI/180;var x=cx+R*Math.cos(o.lat*Math.PI/180)*Math.sin(lo+rot*Math.PI/180);var y=cy-R*Math.sin(o.lat*Math.PI/180);if(i===0)ctx.moveTo(x,y);else ctx.lineTo(x,y);}ctx.stroke();});"
        + "ctx.strokeStyle='white';ctx.beginPath();for(var j=0;j<=180;j++){var la=-90+j;var x=cx+R*Math.sin(rot*Math.PI/180)*Math.cos(la*Math.PI/180);var y=cy-R*Math.sin(la*Math.PI/180);if(j===0)ctx.moveTo(x,y);else ctx.lineTo(x,y);}ctx.stroke();"
        + "if(latNow!==null){var x=cx+R*Math.cos(latNow*Math.PI/180)*Math.sin((lonNow+rot)*Math.PI/180);var y=cy-R*Math.sin(latNow*Math.PI/180);ctx.fillStyle='#ff5252';ctx.beginPath();ctx.arc(x,y,5,0,Math.PI*2);ctx.fill();}"
        + "ctx.restore();ctx.strokeStyle='#6e9ac4';ctx.lineWidth=2;ctx.beginPath();ctx.arc(cx,cy,R,0,Math.PI*2);ctx.stroke();"
        + "ctx.fillStyle='white';ctx.font='13px sans-serif';ctx.textAlign='center';ctx.fillText('كرة أرضية توضيحية ثلاثية الأبعاد',cx,cy+R+24);}"
        + "function draw(){if(!ctx)return;if(mode==='globe')drawGlobe();else drawMap();}"
        + "function showMap(){mode='map';document.getElementById('label').style.display='block';draw();}"
        + "function showGlobe(){mode='globe';document.getElementById('label').style.display='none';draw();}"
        + "function setUserLocation(lat,lon){latNow=lat;lonNow=lon;draw();}"
        + "var down=false,lastX=0;c.addEventListener('touchstart',function(e){down=true;lastX=e.touches[0].clientX;},{passive:true});"
        + "c.addEventListener('touchmove',function(e){if(!down)return;var x=e.touches[0].clientX;rot+=(x-lastX)*0.6;lastX=x;if(mode==='globe')draw();},{passive:true});"
        + "c.addEventListener('touchend',function(){down=false;});"
        + "window.addEventListener('resize',resize);resize();"
        + "</script></body></html>";
    }

    private void showPage(int page) {
        currentPage = page;
        contentFrame.removeAllViews();

        if (page == 2 || page == 3) {
            contentFrame.addView(webView, new FrameLayout.LayoutParams(-1, -1));
            pendingWebPage = page;
            if (webPageReady) runWebPage(page);
            statusText.setText(page == 2
                    ? "الخريطة تحتاج إلى اتصال بالإنترنت"
                    : "الكرة الأرضية ثلاثية الأبعاد تحتاج إلى الإنترنت");
            return;
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(panel);
        contentFrame.addView(scroll);

        if (page == 0) {
            qiblaMode = false;
            degreeText = makeText("0.0°", 42, Color.WHITE, true);
            directionText = makeText("شمال • North", 23, Color.rgb(255, 90, 90), true);
            compassView = new CompassView(this);
            panel.addView(degreeText);
            panel.addView(directionText);
            panel.addView(compassView, new LinearLayout.LayoutParams(-1, dp(330)));

            Button locationButton = makeButton("تحديد موقعي الجغرافي");
            lockButton = makeButton(headingLocked ? "إلغاء تثبيت الاتجاه" : "تثبيت الاتجاه");
            panel.addView(locationButton);
            panel.addView(lockButton);
            infoText = makeText(hasLocation ? formatLocation() : "الموقع: غير محدد", 14, Color.LTGRAY, false);
            panel.addView(infoText);
            locationButton.setOnClickListener(v -> requestLocation());
            lockButton.setOnClickListener(v -> {
                headingLocked = !headingLocked;
                lockButton.setText(headingLocked ? "إلغاء تثبيت الاتجاه" : "تثبيت الاتجاه");
                Toast.makeText(this, headingLocked ? "تم تثبيت الاتجاه الحالي" : "تم إلغاء تثبيت الاتجاه",
                        Toast.LENGTH_SHORT).show();
            });
            updateHeading(heading);
        } else {
            qiblaMode = true;
            panel.addView(makeText("تحديد اتجاه القبلة", 27, Color.WHITE, true));
            panel.addView(makeText("اسمح بالوصول إلى الموقع لحساب الاتجاه.", 16, Color.LTGRAY, false));
            compassView = new CompassView(this);
            panel.addView(compassView, new LinearLayout.LayoutParams(-1, dp(300)));
            infoText = makeText(hasLocation ? "" : "لم يتم تحديد الموقع بعد", 16, Color.WHITE, true);
            panel.addView(infoText);
            Button b = makeButton("تحديد القبلة من موقعي");
            panel.addView(b);
            b.setOnClickListener(v -> requestLocation());
            if (hasLocation) calculateQibla();
        }
    }

    private void runWebPage(int page) {
        if (webView == null || !webPageReady) return;
        String function = page == 3 ? "showGlobe()" : "showMap()";
        webView.evaluateJavascript(function, null);
        if (hasLocation) sendLocationToWeb();
    }

    private void sendLocationToWeb() {
        if (webView == null || !webPageReady || !hasLocation) return;
        webView.evaluateJavascript("setUserLocation(" + latitude + "," + longitude + ")", null);
    }

    private String formatLocation() {
        return String.format(Locale.US, "خط العرض: %.5f°\nخط الطول: %.5f°", latitude, longitude);
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        if (!hasLocationPermission()) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, LOCATION_REQUEST);
            }
            return;
        }
        if (locationManager == null) {
            statusText.setText("خدمة الموقع غير متاحة على هذا الجهاز");
            return;
        }

        try {
            Location best = null;
            boolean fineGranted = Build.VERSION.SDK_INT < 23
                    || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

            if (fineGranted && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (gps != null) best = gps;
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (network != null && (best == null || network.getTime() > best.getTime())) best = network;
            }

            if (best != null) onLocationChanged(best);

            if (fineGranted && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 5, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10, this);
            }

            statusText.setText(best == null ? "جارٍ تحديد الموقع..." : "تم استخدام آخر موقع معروف؛ جارٍ تحديثه...");
        } catch (SecurityException e) {
            statusText.setText("إذن الموقع غير كافٍ. اسمح بالموقع من إعدادات التطبيق.");
        } catch (Exception e) {
            statusText.setText("تعذر تحديد الموقع. تأكد من تشغيل خدمة الموقع.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST) {
            boolean granted = false;
            for (int result : grantResults) if (result == PackageManager.PERMISSION_GRANTED) granted = true;
            if (granted) requestLocation();
            else Toast.makeText(this, "يلزم إذن الموقع لحساب القبلة وعرض موقعك", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        hasLocation = true;
        sendLocationToWeb();

        if (infoText != null) {
            if (qiblaMode) calculateQibla();
            else infoText.setText(formatLocation());
        }
        statusText.setText("تم تحديد الموقع • الدقة التقريبية: " + Math.round(location.getAccuracy()) + " متر");
    }

    private void calculateQibla() {
        double kaabaLat = Math.toRadians(21.4225);
        double kaabaLon = Math.toRadians(39.8262);
        double userLat = Math.toRadians(latitude);
        double userLon = Math.toRadians(longitude);
        double deltaLon = kaabaLon - userLon;

        double y = Math.sin(deltaLon);
        double x = Math.cos(userLat) * Math.tan(kaabaLat)
                - Math.sin(userLat) * Math.cos(deltaLon);

        qiblaBearing = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
        if (infoText != null) {
            infoText.setText(String.format(Locale.US,
                    "اتجاه القبلة: %.1f°\nموقعك: %.5f, %.5f\nاتجاه الهاتف: %.1f°",
                    qiblaBearing, latitude, longitude, heading));
        }
        if (compassView != null) compassView.invalidate();
    }

    private void updateHeading(float degrees) {
        float normalized = (degrees + 360f) % 360f;
        if (headingLocked) return;
        heading = normalized;

        String[] directions = {
                "شمال • North", "شمال شرقي • NE", "شرق • East", "جنوب شرقي • SE",
                "جنوب • South", "جنوب غربي • SW", "غرب • West", "شمال غربي • NW"
        };
        int index = ((int) ((heading + 22.5f) / 45f)) % 8;
        if (degreeText != null) degreeText.setText(String.format(Locale.US, "%.1f°", heading));
        if (directionText != null) directionText.setText(directions[index]);
        if (qiblaMode && infoText != null && hasLocation) {
            infoText.setText(String.format(Locale.US,
                    "اتجاه القبلة: %.1f°\nموقعك: %.5f, %.5f\nاتجاه الهاتف: %.1f°",
                    qiblaBearing, latitude, longitude, heading));
        }
        if (compassView != null) compassView.invalidate();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientation);
            updateHeading((float) Math.toDegrees(orientation[0]));
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, 3);
            hasGravity = true;
            updateFallback();
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetic, 0, 3);
            hasMagnetic = true;
            updateFallback();
        }
    }

    private void updateFallback() {
        if (!hasGravity || !hasMagnetic) return;
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnetic)) {
            SensorManager.getOrientation(rotationMatrix, orientation);
            updateHeading((float) Math.toDegrees(orientation[0]));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (statusText != null && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            statusText.setText("دقة المستشعر منخفضة؛ أبعد الهاتف عن المغناطيس وحرّكه على شكل رقم 8");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager == null) return;
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        } else {
            if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            if (magnetometer != null) sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) {}
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    private class CompassView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CompassView(Context context) { super(context); }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) * 0.88f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(25, 36, 57));
            canvas.drawCircle(cx, cy, r, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.rgb(90, 125, 165));
            canvas.drawCircle(cx, cy, r, paint);

            for (int i = 0; i < 360; i += 5) {
                canvas.save();
                canvas.rotate(i, cx, cy);
                paint.setColor(Color.LTGRAY);
                paint.setStrokeWidth(i % 30 == 0 ? dp(2) : dp(1));
                float start = r - (i % 30 == 0 ? dp(17) : dp(9));
                canvas.drawLine(cx, cy - start, cx, cy - r + dp(4), paint);
                canvas.restore();
            }

            canvas.save();
            canvas.rotate(-heading, cx, cy);
            drawLabel(canvas, "N", cx, cy - r + dp(38), Color.RED, dp(22));
            drawLabel(canvas, "S", cx, cy + r - dp(17), Color.WHITE, dp(18));
            drawLabel(canvas, "W", cx - r + dp(18), cy + dp(6), Color.WHITE, dp(18));
            drawLabel(canvas, "E", cx + r - dp(18), cy + dp(6), Color.WHITE, dp(18));

            Path north = new Path();
            north.moveTo(cx, cy - r + dp(53));
            north.lineTo(cx - dp(14), cy);
            north.lineTo(cx, cy - dp(8));
            north.lineTo(cx + dp(14), cy);
            north.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.RED);
            canvas.drawPath(north, paint);
            canvas.restore();

            if (qiblaMode && hasLocation) {
                canvas.save();
                canvas.rotate((float) (qiblaBearing - heading), cx, cy);
                Path qibla = new Path();
                qibla.moveTo(cx, cy - r + dp(53));
                qibla.lineTo(cx - dp(12), cy + dp(8));
                qibla.lineTo(cx, cy);
                qibla.lineTo(cx + dp(12), cy + dp(8));
                qibla.close();
                paint.setColor(Color.GREEN);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(qibla, paint);
                canvas.restore();
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, dp(5), paint);
            if (qiblaMode) drawLabel(canvas, "السهم الأخضر: القبلة",
                    cx, getHeight() - dp(10), Color.GREEN, dp(12));
        }

        private void drawLabel(Canvas canvas, String text, float x, float y, int color, float size) {
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(text, x, y, paint);
        }
    }


    /*
     * =====================================================================
     * MAINTENANCE GUIDE — COMPASS V4 SOURCE NOTES
     * =====================================================================
     *
     * The following notes document the design and safe extension points.
     * They are comments only and do not alter application behavior.
     *
     * SENSOR AND HEADING NOTES
     * ---------------------------------------------------------------------
     * 1. TYPE_ROTATION_VECTOR is preferred when the device provides it.
     * 2. The fallback uses accelerometer and magnetic-field readings.
     * 3. Magnetic interference can make any magnetic compass inaccurate.
     * 4. Keep the phone away from speakers, magnetic cases, and metal.
     * 5. Move the phone gently in a figure-eight pattern to recalibrate.
     * 6. The heading is normalized to the range [0, 360).
     * 7. North is represented by zero degrees.
     * 8. East is represented by ninety degrees.
     * 9. South is represented by one hundred eighty degrees.
     * 10. West is represented by two hundred seventy degrees.
     * 11. The displayed eight-point label is a rounded sector label.
     * 12. The numeric heading remains more precise than the label.
     * 13. Device orientation and sensor quality affect the result.
     * 14. A software compass cannot correct a physically faulty sensor.
     * 15. Test headings outdoors and away from strong electromagnetic fields.
     *
     * LOCATION AND PERMISSION NOTES
     * ---------------------------------------------------------------------
     * 16. Location access is requested only after the user taps a button.
     * 17. Approximate location can be enough for a rough Qibla bearing.
     * 18. Precise location generally improves the calculated bearing.
     * 19. Android may return a cached location before a fresh fix arrives.
     * 20. A cached location can be old, so the status message says so.
     * 21. Location services must be enabled in Android system settings.
     * 22. Permission approval alone does not guarantee a GPS fix.
     * 23. Indoors, network location may work better than GPS.
     * 24. Outdoors, GPS may provide better precision after a short wait.
     * 25. The application stops location updates when paused.
     * 26. The app should not request background location for this use case.
     * 27. Never publish a user's coordinates without their permission.
     * 28. Treat location as private information.
     * 29. A location accuracy radius is an estimate, not a guarantee.
     * 30. A displayed accuracy value should not be interpreted as exact truth.
     *
     * QIBLA CALCULATION NOTES
     * ---------------------------------------------------------------------
     * 31. The Kaaba coordinate used here is an approximate reference point.
     * 32. The calculation returns the initial great-circle bearing.
     * 33. It is a bearing measured clockwise from geographic north.
     * 34. The phone's compass reading is compared with that bearing.
     * 35. The green arrow is rotated relative to the current heading.
     * 36. The arrow can appear unstable when the sensor is unstable.
     * 37. Nearby metal can alter the magnetic heading significantly.
     * 38. The calculation does not determine the phone's physical direction.
     * 39. The sensor supplies the direction; the formula supplies the target.
     * 40. Confirm the result with a trusted Qibla tool when precision matters.
     * 41. Do not treat this simple app as a surveying instrument.
     * 42. The calculation assumes latitude and longitude use decimal degrees.
     * 43. Radians are used internally by Java trigonometric functions.
     * 44. The final angle is normalized to [0, 360).
     * 45. A location extremely close to the Kaaba is a special edge case.
     *
     * OFFLINE MAP VIEW NOTES
     * ---------------------------------------------------------------------
     * 46. The map view in this version is a schematic drawing.
     * 47. It is not a satellite map and does not download map tiles.
     * 48. Its continent shapes are illustrative, not cartographically exact.
     * 49. Grid lines are drawn to make latitude and longitude understandable.
     * 50. Greenwich is the zero-degree longitude meridian.
     * 51. The equator is the zero-degree latitude parallel.
     * 52. The Tropic of Cancer is approximately 23.44 degrees north.
     * 53. The Tropic of Capricorn is approximately 23.44 degrees south.
     * 54. The Arctic Circle is approximately 66.56 degrees north.
     * 55. The Antarctic Circle is approximately 66.56 degrees south.
     * 56. The red point indicates the latest available user location.
     * 57. The map drawing stretches to the WebView dimensions.
     * 58. Resizing the WebView triggers a redraw.
     * 59. The schematic should not be used to measure distances.
     * 60. The schematic does not provide routing or place search.
     *
     * OFFLINE GLOBE VIEW NOTES
     * ---------------------------------------------------------------------
     * 61. The globe is a canvas illustration, not a photorealistic Earth.
     * 62. It uses a radial gradient to suggest spherical shading.
     * 63. The globe is clipped to a circular boundary.
     * 64. Meridians and parallels are drawn as simplified curves.
     * 65. The user can drag horizontally to change the displayed rotation.
     * 66. Touch movement changes the globe's longitude rotation.
     * 67. The red dot marks the user location in the simplified projection.
     * 68. This projection is educational and not a full 3D geospatial model.
     * 69. The canvas is redrawn after rotation.
     * 70. High device pixel ratios are accounted for in resize().
     *
     * WEBVIEW NOTES
     * ---------------------------------------------------------------------
     * 71. JavaScript is required for the local canvas drawing.
     * 72. This version does not require third-party JavaScript libraries.
     * 73. The page is generated locally as an HTML string.
     * 74. Remote map libraries are intentionally not loaded by this version.
     * 75. This reduces reliance on CDNs and internet availability.
     * 76. WebView JavaScript callbacks should only run after page readiness.
     * 77. evaluateJavascript is used to call functions in the local page.
     * 78. Keep function names consistent between Java and HTML.
     * 79. If a JavaScript function is renamed, update its Java caller too.
     * 80. JavaScript errors can be inspected with WebView debugging in testing.
     * 81. Avoid enabling file access unnecessarily for a local HTML string.
     * 82. Do not add arbitrary external navigation without checking destinations.
     *
     * USER INTERFACE NOTES
     * ---------------------------------------------------------------------
     * 83. The interface is built programmatically in Java.
     * 84. No layout XML files are required by this Activity's interface.
     * 85. dp() converts density-independent sizes to screen pixels.
     * 86. makeText() centralizes the basic text style.
     * 87. makeButton() centralizes button construction.
     * 88. showPage() rebuilds the selected page's content.
     * 89. currentPage records the selected tab index.
     * 90. statusText communicates sensor and location status.
     * 91. The interface uses a dark background for contrast.
     * 92. Text size and contrast should be tested on small screens.
     * 93. If adding settings, preserve the user's current selected page.
     * 94. Avoid creating multiple WebViews for each tab.
     * 95. Reuse the existing WebView to reduce memory usage.
     *
     * TESTING CHECKLIST
     * ---------------------------------------------------------------------
     * 96. Build the project before installing the APK.
     * 97. Confirm that the package declaration matches the project package.
     * 98. Confirm that the Activity name matches the file name.
     * 99. Confirm that the Android manifest declares this Activity.
     * 100. Confirm INTERNET permission if other online features are added.
     * 101. Confirm coarse and fine location permissions are declared.
     * 102. Test the first launch without granting location permission.
     * 103. Test the permission prompt and denial path.
     * 104. Test approximate location permission where Android offers it.
     * 105. Test precise location permission where Android offers it.
     * 106. Test with location services switched off.
     * 107. Test with location services switched on.
     * 108. Test with no cached location available.
     * 109. Test after a fresh location fix arrives.
     * 110. Test rotating the device if the app supports rotation.
     * 111. Test the compass while facing each cardinal direction.
     * 112. Test the lock button and ensure the displayed heading freezes.
     * 113. Test that unlocking resumes sensor updates.
     * 114. Test switching between all four tabs repeatedly.
     * 115. Test the map without an internet connection.
     * 116. Test the globe without an internet connection.
     * 117. Verify that neither view depends on an external CDN.
     * 118. Verify that the user marker appears after location is known.
     * 119. Verify that a new location updates the marker.
     * 120. Test on the actual device, not only an emulator.
     *
     * TROUBLESHOOTING NOTES
     * ---------------------------------------------------------------------
     * 121. If Build fails, capture the first compiler error, not only the last.
     * 122. A missing import can cause several follow-on compiler errors.
     * 123. A wrong package declaration can prevent class discovery.
     * 124. A manifest issue may cause installation or launch problems.
     * 125. A runtime exception is different from a compiler error.
     * 126. A blank WebView is different from a blank Activity.
     * 127. If only the map is blank, inspect the WebView content first.
     * 128. If all tabs are blank, inspect the Activity layout and lifecycle.
     * 129. If the app closes, collect the Android crash message.
     * 130. Do not replace unrelated project files without a backup.
     * 131. Keep a copy of the last version that builds successfully.
     * 132. Change one major component at a time while diagnosing.
     * 133. Rebuild after changes so the failing change can be isolated.
     * 134. Do not assume an internet issue when local rendering also fails.
     * 135. Do not assume a Java issue when the app has not compiled yet.
     *
     * MAINTAINABILITY NOTES
     * ---------------------------------------------------------------------
     * 136. Keep UI creation separate from sensor calculations.
     * 137. Keep location permission checks separate from bearing math.
     * 138. Keep drawing helpers small and easy to inspect.
     * 139. Prefer descriptive names for future additions.
     * 140. Avoid blocking the main thread with network operations.
     * 141. Do not place API keys in source code.
     * 142. Do not collect location when the user has not requested it.
     * 143. Stop listeners when they are no longer needed.
     * 144. Validate values received from sensors before displaying them.
     * 145. Handle missing sensors gracefully.
     * 146. Treat null system services as possible on unusual devices.
     * 147. Keep the manifest permissions limited to actual requirements.
     * 148. Do not request background location for a foreground compass.
     * 149. Keep error messages understandable to the user.
     * 150. Verify every claimed fix with a real build and a real-device test.
     *
     * These notes are intentionally kept inside the source file to make
     * the implementation easier to maintain and troubleshoot in Klencod.
     * They do not replace a real compiler build or device test.
     * =====================================================================
     */

}
