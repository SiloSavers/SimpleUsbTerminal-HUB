package de.kai_morich.simple_usb_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Date;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * create notification and queue serial data while activity is not in the foreground
 * use listener chain: SerialSocket -> SerialService -> UI fragment
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class SerialService extends Service implements SerialListener {

    class SerialBinder extends Binder {
        SerialService getService() { return SerialService.this; }
    }

    private enum QueueType {Connect, ConnectError, Read, IoError}

    private static class QueueItem {
        QueueType type;
        ArrayDeque<byte[]> datas;
        Exception e;

        QueueItem(QueueType type) { this.type=type; if(type==QueueType.Read) init(); }
        QueueItem(QueueType type, Exception e) { this.type=type; this.e=e; }
        QueueItem(QueueType type, ArrayDeque<byte[]> datas) { this.type=type; this.datas=datas; }

        void init() { datas = new ArrayDeque<>(); }
        void add(byte[] data) { datas.add(data); }
    }

    private final Handler mainLooper;
    private final IBinder binder;
    private final ArrayDeque<QueueItem> queue1, queue2;
    private final QueueItem lastRead;

    private SerialSocket socket;
    private SerialListener listener;
    private boolean connected;

    private static int  phoneCharge = 0; //battery level of phone
    private Handler batteryCheckHandler;
    private Handler pressureCheckHandler;

    //Google Sheets Variables
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient();

    /**
     * Lifecycle
     */
    public SerialService() {
        mainLooper = new Handler(Looper.getMainLooper());
        binder = new SerialBinder();
        queue1 = new ArrayDeque<>();
        queue2 = new ArrayDeque<>();
        lastRead = new QueueItem(QueueType.Read);
        startBatteryCheckHandler();
        startPressureCheckHandler();
    }

    @Override
    public void onDestroy() {
        cancelNotification();
        disconnect();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private static BatteryManager bm;

    private final Runnable batteryCheckRunnable = new Runnable() { //written by GPT 3.5 with prompts from Coby's code
    //runnable that gets the Phone Battery level every 30 minutes and reports to the Google Sheets
        @Override
        public void run() {
            bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            phoneCharge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            //print_to_terminal("Read Phone battery level: " + phoneCharge);
            System.out.print("Battery level " +  String.valueOf(phoneCharge) + "\n");
            sendDataToSheet("log","Phone Battery level " + phoneCharge);
            //logToFile("Phone Battery level " + phoneCharge);

            //Log.d("BatteryLevel", String.valueOf(phoneCharge));

            batteryCheckHandler.postDelayed(batteryCheckRunnable, 30 * 60 * 1000); //delay
        }
    };

    private void startBatteryCheckHandler() {
        Looper looper = Looper.myLooper();
        if (looper != null) {
            batteryCheckHandler = new Handler(looper);
            batteryCheckHandler.post(batteryCheckRunnable);
        }
    }

    public static float getPhoneChargePercent() { return phoneCharge; }

    private void readPressureOnce() {//read the pressure once
        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE); //get a sensorManager
        Sensor pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE); //set the sensor to a Pressure Type

        if (pressureSensor == null) { //If the pressureSensor is null report to sheets and say no pressure sensor
            Log.e("Pressure", "No pressure sensor found");
            return;
        }

        SensorEventListener oneShotListener = new SensorEventListener() { //create a oneShotListener
            @Override
            public void onSensorChanged(SensorEvent event) { //on SensorChange send the log data to the sheet
                float pressure = event.values[0];
                Log.d("Pressure", "One-shot reading: " + pressure + " hPa");
                sendDataToSheet("log", "Pressure: " + pressure);

                sensorManager.unregisterListener(this); // stop after one reading
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };

        sensorManager.registerListener(oneShotListener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private final Runnable pressureCheckRunnable = new Runnable() { //runnable that gets the pressure reading every 15 minutes
        @Override
        public void run() {
            readPressureOnce();
            pressureCheckHandler.postDelayed(this, 15 * 60 * 1000); // 30 minutes
        }
    };

    private void startPressureCheckHandler() {//starts the pressure handler
        Looper looper = Looper.myLooper();
        if (looper != null) {
            pressureCheckHandler = new Handler(looper);
            pressureCheckHandler.post(pressureCheckRunnable);
        }
    }

private void sendDataToSheet(String type, String value){
    //logToFile("call sendDataToSheet type:" + type + " value: " + value);
    String sheetName;
    String[] parts = new String[5];
    if (type.equals("SENSOR:")){
        parts = value.split(" ");
        sheetName = parts[1];
    } else{
        sheetName = "log";
        parts[2] = type;
        parts[3] = value;
    }


    String json = "{\"targetSheet\":\"" + sheetName + "\","
            + "\"co2\":\"" + parts[2] + "\","
            + "\"temp\":\"" + parts[3] + "\","
            + "\"rh\":\"" + parts[4] + "\"}";

    RequestBody body = RequestBody.create(json, JSON);
    Request request = new Request.Builder()
            .url("https://script.google.com/macros/s/AKfycbwL7X5sIfikv70xaw_6yWRZGkkWEYaAIgQSUDaWThiWBk5IQczG5kWvSYTQRol2aXd2/exec") // Replace with your Apps Script Web App URL
            .post(body)
            .build();
    client.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
            //logToFile("onFailure called name:" + type + " Exception: " + e);
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            if (response.isSuccessful()) {
                System.out.println(response.body().string());
            } else {
                System.err.println("Request failed: " + response.code());
                //logToFile("Response failed name: " + type);
            }
        }
    });
}

    /**
     * Api
     */
    public void connect(SerialSocket socket) throws IOException {
        socket.connect(this);
        this.socket = socket;
        connected = true;
    }

    public void disconnect() {
        connected = false; // ignore data,errors while disconnecting
        cancelNotification();
        if(socket != null) {
            socket.disconnect();
            socket = null;
        }
    }

    /**
     * Write message to log file with timestamp
     */
    private void writeToLogFile(String message) {
        try {
            // Get external storage directory
            File externalDir = getExternalFilesDir(null);
            if (externalDir != null) {
                File logFile = new File(externalDir, "serial_debug.log");

                // Create timestamp
                //String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
                String logEntry = "timestamp" + " - " + message + "\n";

                // Append to file
                FileWriter writer = new FileWriter(logFile, true);
                writer.write(logEntry);
                writer.close();
            }
        } catch (IOException e) {
            Log.e("SerialService", "Error writing to log file", e);
        }
    }

    public void write(byte[] data) throws IOException {
        if(!connected)
            throw new IOException("not connected");
        socket.write(data);
    }

    public void attach(SerialListener listener) {
        if(Looper.getMainLooper().getThread() != Thread.currentThread())
            throw new IllegalArgumentException("not in main thread");
        initNotification();
        cancelNotification();
        // use synchronized() to prevent new items in queue2
        // new items will not be added to queue1 because mainLooper.post and attach() run in main thread
        synchronized (this) {
            this.listener = listener;
        }
        for(QueueItem item : queue1) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.datas); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        for(QueueItem item : queue2) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.datas); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        queue1.clear();
        queue2.clear();
    }

    public void detach() {
        if(connected)
            createNotification();
        // items already in event queue (posted before detach() to mainLooper) will end up in queue1
        // items occurring later, will be moved directly to queue2
        // detach() and mainLooper.post run in the main thread, so all items are caught
        listener = null;
    }

    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel nc = new NotificationChannel(Constants.NOTIFICATION_CHANNEL, "Background service", NotificationManager.IMPORTANCE_LOW);
            nc.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(nc);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public boolean areNotificationsEnabled() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel nc = nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL);
        return nm.areNotificationsEnabled() && nc != null && nc.getImportance() > NotificationManager.IMPORTANCE_NONE;
    }

    private void createNotification() {
        Intent disconnectIntent = new Intent()
                .setPackage(getPackageName())
                .setAction(Constants.INTENT_ACTION_DISCONNECT);
        Intent restartIntent = new Intent()
                .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, flags);
        PendingIntent restartPendingIntent = PendingIntent.getActivity(this, 1, restartIntent,  flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getResources().getColor(R.color.colorPrimary))
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(socket != null ? "Connected to "+socket.getName() : "Background Service")
                .setContentIntent(restartPendingIntent)
                .setOngoing(true)
                .addAction(new NotificationCompat.Action(R.drawable.ic_clear_white_24dp, "Disconnect", disconnectPendingIntent));
        // @drawable/ic_notification created with Android Studio -> New -> Image Asset using @color/colorPrimaryDark as background color
        // Android < API 21 does not support vectorDrawables in notifications, so both drawables used here, are created as .png instead of .xml
        Notification notification = builder.build();
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification);
    }

    private void cancelNotification() {
        stopForeground(true);
    }

    /**
     * SerialListener
     */
    public void onSerialConnect() {
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialConnect();
                        } else {
                            queue1.add(new QueueItem(QueueType.Connect));
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.Connect));
                }
            }
        }
    }

    public void onSerialConnectError(Exception e) {
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialConnectError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.ConnectError, e));
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.ConnectError, e));
                    disconnect();
                }
            }
        }
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) { throw new UnsupportedOperationException(); }

    /**
     * reduce number of UI updates by merging data chunks.
     * Data can arrive at hundred chunks per second, but the UI can only
     * perform a dozen updates if receiveText already contains much text.
     *
     * On new data inform UI thread once (1).
     * While not consumed (2), add more data (3).
     */
    public void onSerialRead(byte[] data) {
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    boolean first;
                    synchronized (lastRead) {
                        first = lastRead.datas.isEmpty(); // (1)
                        lastRead.add(data); // (3)
                    }
                    if(first) {
                        mainLooper.post(() -> {
                            ArrayDeque<byte[]> datas;
                            synchronized (lastRead) {
                                datas = lastRead.datas;
                                lastRead.init(); // (2)
                            }
                            if (listener != null) {
                                listener.onSerialRead(datas);
                            } else {
                                queue1.add(new QueueItem(QueueType.Read, datas));
                            }
                        });
                    }
                } else {
                    if(queue2.isEmpty() || queue2.getLast().type != QueueType.Read)
                        queue2.add(new QueueItem(QueueType.Read));
                    queue2.getLast().add(data);
                }
            }
        }
        //Toast.makeText(getActivity(), "hhhhard", Toast.LENGTH_SHORT).show();
        // Run network operations in background


    }

    public void onSerialIoError(Exception e) {
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialIoError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.IoError, e));
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.IoError, e));
                    disconnect();
                }
            }
        }
    }

}
