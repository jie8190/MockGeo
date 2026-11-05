package com.mockgeo.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Disposable app process for testing the Root loader alongside an injected framework. */
public final class RootCompatActivity extends Activity {
    private volatile boolean stopped;
    private static void write(File file, String text) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView view = new TextView(this);
        view.setTextSize(18);
        view.setPadding(32, 80, 32, 32);
        setContentView(view);
        String nonce = getIntent().getStringExtra("nonce");
        if (nonce == null || !nonce.matches("[a-f0-9]{32}")) { view.setText("Invalid test nonce"); return; }
        File directory = new File(getFilesDir(), "root-compat-" + nonce);
        directory.mkdirs();
        view.setText("Root coexistence probe\nPID " + Process.myPid() + "\nWaiting for the isolated test. No system hooks are installed by this test.");
        try { write(new File(directory, "host.json"), new JSONObject().put("pid",Process.myPid()).put("uid",Process.myUid()).toString()); }
        catch (Exception error) { view.setText(error.toString()); return; }
        new Thread(() -> {
            long until = SystemClock.elapsedRealtime() + 120000;
            try {
                while (!stopped && SystemClock.elapsedRealtime() < until) {
                    write(new File(directory,"heartbeat"), Long.toString(SystemClock.elapsedRealtime()));
                    File receipt = new File(directory,"receipt.json");
                    if (receipt.isFile()) {
                        String text = new String(Files.readAllBytes(receipt.toPath()),StandardCharsets.UTF_8);
                        JSONObject result = new JSONObject(text);
                        runOnUiThread(() -> {
                            try {
                                result.put("mainThreadResumed",true);
                                write(new File(directory,"activity-result.json"),result.toString());
                                view.setText("Isolated test finished\n" + result.toString(2));
                            } catch (Exception error) { view.setText(error.toString()); }
                        });
                        return;
                    }
                    Thread.sleep(250);
                }
            } catch (Exception error) { runOnUiThread(() -> view.setText(error.toString())); }
        },"MockGeo-RootCompatHost").start();
    }
    @Override protected void onDestroy() { stopped=true; super.onDestroy(); }
}
