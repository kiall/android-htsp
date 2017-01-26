package ie.macinnes.htspexample;

import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import ie.macinnes.htsp.HtspConnection;

public class MainActivity extends AppCompatActivity {
    private static final String NEWLINE = System.getProperty("line.separator");

    private HtspConnection mHtspConnection;
    private Thread mHtspConnectionThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    public void disconnect(View view) {
        mHtspConnection.closeConnection();
    }

    public void connect(View view) {
        if (mHtspConnection != null && !mHtspConnection.isClosed()) {
            // We're not disconnected, running the code below will dupe the thread and leave multiple
            // connections open.
            return;
        }
        mHtspConnection = new HtspConnection("10.5.1.21", 9982, "user", "password", "android-htsp example", BuildConfig.VERSION_NAME);

        final HtspConnection.ConnectionListener connectionListener = new HtspConnection.ConnectionListener() {
            @Override
            public void onConnectionStateChange(@NonNull HtspConnection.State state) {
                TextView v = (TextView) findViewById(R.id.debugOutput);
                v.append("Connection State Changed: " + state + NEWLINE);
            }
        };

        mHtspConnection.addConnectionListener(connectionListener);

        mHtspConnectionThread = new Thread(mHtspConnection);
        mHtspConnectionThread.start();
    }
}
