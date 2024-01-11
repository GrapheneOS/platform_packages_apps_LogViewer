package app.grapheneos.logviewer;

import android.annotation.Nullable;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static android.text.TextUtils.isEmpty;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

public class LogcatActivity extends BaseActivity {
    private static final String TAG = LogcatActivity.class.getSimpleName();

    private static final String ACTION_SHOW_EVENT_LOG = LogcatActivity.class.getName() + ".SHOW_EVENT_LOG";
    private static final String ACTION_SHOW_RADIO_LOG = LogcatActivity.class.getName() + ".SHOW_RADIO_LOG";
    private static final String EXTRA_LOG_BUFFERS = LogcatActivity.class.getName() + ".LOG_BUFFERS";
    private static final String EXTRA_LOG_LEVEL = LogcatActivity.class.getName() + ".LOG_LEVEL";
    private static final String EXTRA_FILTER_REGEX = LogcatActivity.class.getName() + ".FILTER_REGEX";

    static final int TYPE_APP_LOG = 1;
    static final int TYPE_SYSTEM_LOG = 2;
    static final int TYPE_EVENT_LOG = 3;
    static final int TYPE_RADIO_LOG = 4;

    private boolean isSystemUser;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        isSystemUser = getSystemService(UserManager.class).isSystemUser();
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public ViewModel createViewModel() {
        Intent intent = getIntent();
        String targetPkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);

        int logType = getLogType();

        if (logType != TYPE_APP_LOG) {
            if (!isSystemUser) {
                // don't allow secondary users to see logs of other users
                return null;
            }
        }

        var cmd = new ArrayList<String>();
        cmd.add("logcat");

        ArrayList<String> logBuffers = getLogBuffers();
        String logBuffersStr = String.join(",", logBuffers);
        cmd.add("--buffer=" + logBuffersStr);

        cmd.add("-d");
        cmd.add("--dividers");

        var format = new ArrayList<String>();
        format.add("epoch");
        format.add("printable");
        if (targetPkg == null) {
            format.add("uid");
        }
        format.add("descriptive");
        cmd.add("--format=" + String.join(",", format));

        String filterRegex = getFilterRegex();
        if (!isEmpty(filterRegex)) {
            cmd.add("--regex=" + filterRegex);
        }

        int logLevel = getLogLevel();
        String logLevelStr = LOG_LEVELS.get(logLevel);
        cmd.add("*:" + logLevelStr.charAt(0));

        long packageVersion = 0L;

        if (targetPkg != null) {
            ApplicationInfo ai;
            try {
                ai = getPackageManager().getApplicationInfo(targetPkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.d(TAG, "", e);
                return null;
            }

            cmd.add("--uid=" + ai.uid);
            packageVersion = ai.longVersionCode;
        }

        var pb = new ProcessBuilder();
        pb.command(cmd);

        Log.d(TAG, "command: " + String.join(" ", cmd));

        byte[] logcatBytes = null;
        try {
            Process proc = pb.start();
            try (InputStream is = proc.getInputStream()) {
                logcatBytes = is.readAllBytes();
            }
            int ret = proc.waitFor();
            Log.d(TAG, "logcat return code: " + ret);
        } catch (IOException|InterruptedException e) {
            Log.e(TAG, "", e);
        }

        if (logcatBytes == null) {
            return null;
        }

        String header =
            "type: logcat"
            + "\nosVersion: " + Build.FINGERPRINT
            + (targetPkg != null ? "\npackageName: " + targetPkg + ":" + packageVersion : "")
            + "\nbuffers: " + logBuffersStr
            + "\nlevel: " + logLevelStr.toLowerCase()
            + (!isEmpty(filterRegex) ? ("\nfilterRegex: " + filterRegex) : "")
        ;

        String text = new String(logcatBytes, StandardCharsets.UTF_8);

        String title = switch (logType) {
            case TYPE_APP_LOG -> getString(R.string.app_log_title, Utils.loadAppLabel(this, targetPkg));
            case TYPE_SYSTEM_LOG -> getString(R.string.system_log_title);
            default -> throw new IllegalStateException();
        };

        if (!getDefaultLogBuffers().equals(logBuffers)) {
            var b = new StringBuilder(" | ");
            for (String s : logBuffers) {
                b.append(Character.toUpperCase(s.charAt(0)));
            }
            title += b.toString();
        }

        if (logLevel != Log.VERBOSE) {
            title += " | " + logLevelStr.charAt(0) + '+';
        }

        if (!isEmpty(filterRegex)) {
            title += " | " + filterRegex;
        }

        return new ViewModel(targetPkg, title, header, text);
    }

    @Override
    boolean shouldScrollToBottom() {
        return true;
    }

    @Override
    float getInitialFontSizeSp() {
        return 9f;
    }

    @Override
    String prepareLineForDisplay(String s) {
        String res = s.replace('\t', ' ');
        return res.trim();
    }

    @Override
    String prepareLineForCopy(String s) {
        return s.trim();
    }

    @Override
    List<BottomButton> createExtraBottomButtons() {
        switch (getLogType()) {
            case TYPE_APP_LOG -> {
                if (isSystemUser) {
                    var bb = new BottomButton(getText(R.string.action_show_system_log), v -> {
                        var i = new Intent(this, LogcatActivity.class);
                        startActivity(i);
                    });
                    return singletonList(bb);
                }
            }
        }

        return emptyList();
    }

    private int getLogType() {
        Intent i = getIntent();
        String targetPkg = i.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (targetPkg != null) {
            return TYPE_APP_LOG;
        }
        return TYPE_SYSTEM_LOG;
    }

    @Log.Level
    private int getLogLevel() {
        int v = getIntent().getIntExtra(EXTRA_LOG_LEVEL, Log.VERBOSE);
        return min(Log.ASSERT, max(Log.VERBOSE, v));
    }

    private static SparseArray<String> LOG_LEVELS = new SparseArray<>();

    static {
        var map = LOG_LEVELS;
        map.put(Log.VERBOSE, "Verbose");
        map.put(Log.DEBUG, "Debug");
        map.put(Log.INFO, "Info");
        map.put(Log.WARN, "Warn");
        map.put(Log.ERROR, "Error");
        map.put(Log.ASSERT, "Assert");
    }

    @Nullable
    private String getFilterRegex() {
        return getIntent().getStringExtra(EXTRA_FILTER_REGEX);
    }

    private MenuItem miLogBuffers;
    private MenuItem miLogLevel;
    private MenuItem miSetFilter;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        miSetFilter = menu.add(R.string.set_filter)
                .setIcon(R.drawable.ic_search)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        if (getLogType() != TYPE_EVENT_LOG) {
            miLogLevel = menu.add(R.string.log_level)
                    .setIcon(R.drawable.ic_log_level)
                    .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        miLogBuffers = menu.add(R.string.log_buffers);
        return true;
    }

    private ArrayList<String> getLogBuffers() {
        ArrayList<String> l = getIntent().getStringArrayListExtra(EXTRA_LOG_BUFFERS);
        if (l == null) {
            l = getDefaultLogBuffers();
        }
        return l;
    }

    private static ArrayList<String> getDefaultLogBuffers() {
        var l = new ArrayList<String>();
        l.add("main");
        l.add("system");
        l.add("crash");
        l.add("events");
        return l;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (super.onOptionsItemSelected(item)) {
            return true;
        }

        if (item == miLogBuffers) {
            var b = new AlertDialog.Builder(this);
            b.setTitle(R.string.log_buffers);
            String[] items = { "main", "system", "crash", "events", "kernel", "radio", };
            ArrayList<String> curBuffers = getLogBuffers();

            boolean[] checkedItems = new boolean[items.length];
            for (int i = 0; i < items.length; ++i) {
                checkedItems[i] = curBuffers.contains(items[i]);
            }

            b.setMultiChoiceItems(items, checkedItems, (dialog, which, isChecked) -> checkedItems[which] = isChecked);
            b.setPositiveButton(R.string.action_apply, (d, w) -> {
                var list = new ArrayList<String>();
                for (int i = 0; i < items.length; ++i) {
                    if (checkedItems[i]) {
                        list.add(items[i]);
                    }
                }
                if (list.isEmpty()) {
                    return;
                }
                var i = new Intent(getIntent());
                i.putExtra(EXTRA_LOG_BUFFERS, list);
                startActivity(i);
            });
            b.show();
            return true;
        }

        if (item == miLogLevel) {
            var map = LOG_LEVELS;
            int numLevels = map.size();

            int curLevel = getLogLevel();
            String[] items = new String[numLevels];
            int curLevelIdx = 0;
            for (int i = 0; i < numLevels; ++i) {
                int level = map.keyAt(i);
                if (level == curLevel) {
                    curLevelIdx = i;
                }
                items[i] =  map.valueAt(i);
            }

            var b = new AlertDialog.Builder(this);
            b.setTitle(R.string.log_level);
            b.setSingleChoiceItems(items, curLevelIdx, (d, idx) -> {
                var i = new Intent(getIntent());
                i.putExtra(EXTRA_LOG_LEVEL, map.keyAt(idx));
                startActivity(i);
                d.dismiss();
            });
            b.show();
            return true;
        }

        if (item == miSetFilter) {
            String initial = getFilterRegex();
            EditorDialog.show(this, true, getText(R.string.set_filter), initial,
                    getText(R.string.set_filter_editor_hint), (Editable res) -> {
                String s = res.toString();
                if (!isEmpty(s) || !isEmpty(initial)) {
                    var i = new Intent(getIntent());
                    i.putExtra(EXTRA_FILTER_REGEX, s);
                    startActivity(i);
                }
            });
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
