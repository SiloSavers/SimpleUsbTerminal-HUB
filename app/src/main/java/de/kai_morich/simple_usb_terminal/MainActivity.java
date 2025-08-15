package de.kai_morich.simple_usb_terminal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.content.IntentFilter;
import android.os.UserManager;

@RequiresApi(api = Build.VERSION_CODES.O)
public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {


    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                unregisterReceiver(this);
                initializeAfterUnlock();
            }
        }
    };



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        //following line will keep the screen active
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        if (userManager.isUserUnlocked()) {
            initializeAfterUnlock();
        } else {
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            registerReceiver(unlockReceiver, filter);
        }

        //setup toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();

        //TODO Remove this button
        /*
        Button sendButton = findViewById(R.id.sendButton);
        sendButton.setOnClickListener(v -> {
            try {
                sendDataToSheet("Hello there", "123");
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        */
    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment)getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null) {
                terminal.status("USB device detected");
                terminal.connect();
            }
        }
        super.onNewIntent(intent);
    }

    private void initializeAfterUnlock() {
        // Firebase Auth
        //mAuth = FirebaseAuth.getInstance();

        // Start GPS/heading service
        //startService(new Intent(this, SensorHelper.class));

        // Start Firebase service
        //startService(new Intent(this, FirebaseService.class)); //TODO Fix this I think it might actually be important

        // Location tracking
        //locationHelper = new LocationHelper(this);
        //locationHelper.startLocationUpdates();
    }

}
