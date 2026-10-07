package com.evodesign.playstoreclient;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String JSON_URL = "https://raw.githubusercontent.com/engrpanda/Evo_AppStoreCL/refs/heads/main/evoapp.json";
    private static final long PROGRESS_POLL_MS = 400;
    private static final String APP_PIN = "0000";
    private static final String STATE_UNLOCKED = "unlocked";

    private static final int FILTER_ALL = 0;
    private static final int FILTER_UPDATES = 1;
    private static final int FILTER_INSTALLED = 2;

    private enum AppState { NOT_INSTALLED, UPDATE_AVAILABLE, UP_TO_DATE }

    private final ArrayList<AppInfo> appList = new ArrayList<>();
    private final Map<String, Integer> downloads = new HashMap<>(); // packageName -> progress 0..100
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AppAdapter adapter;
    private RecyclerView appListView;
    private View loadingView;
    private View stateView;
    private ImageView stateIcon;
    private TextView stateTitle;
    private TextView stateBody;
    private View retryButton;

    private String query = "";
    private int filter = FILTER_ALL;
    private boolean loaded = false;

    private boolean unlocked = false;
    private View pinOverlay;
    private TextView pinDots;
    private TextView pinError;
    private final StringBuilder pinEntry = new StringBuilder();
    private boolean pinBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        unlocked = savedInstanceState != null && savedInstanceState.getBoolean(STATE_UNLOCKED);
        setContentView(R.layout.activity_main);

        appListView = findViewById(R.id.list_view);
        loadingView = findViewById(R.id.loading);
        stateView = findViewById(R.id.state_view);
        stateIcon = findViewById(R.id.state_icon);
        stateTitle = findViewById(R.id.state_title);
        stateBody = findViewById(R.id.state_body);
        retryButton = findViewById(R.id.btn_retry);

        int spanCount = Math.max(1, getResources().getConfiguration().screenWidthDp / getResources().getInteger(R.integer.card_min_width_dp));
        appListView.setLayoutManager(new GridLayoutManager(this, spanCount));
        adapter = new AppAdapter();
        appListView.setAdapter(adapter);

        TextView edition = findViewById(R.id.tv_edition);
        int accent = ContextCompat.getColor(this, R.color.accent);
        edition.setText(getString(R.string.edition_label) + " v" + BuildConfig.VERSION_NAME);
        edition.setTextColor(accent);
        edition.setBackgroundTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 36)));

        findViewById(R.id.btn_refresh).setOnClickListener(v -> fetchAppData());
        retryButton.setOnClickListener(v -> fetchAppData());

        EditText search = findViewById(R.id.et_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            }
            return false;
        });

        ChipGroup chips = findViewById(R.id.chip_filters);
        chips.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.chip_updates) filter = FILTER_UPDATES;
            else if (checkedId == R.id.chip_installed) filter = FILTER_INSTALLED;
            else filter = FILTER_ALL;
            applyFilter();
        });

        fetchAppData();
        if (!unlocked) showPin();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_UNLOCKED, unlocked);
    }

    // ----------------------------------------------------------------- PIN

    private void showPin() {
        pinOverlay = getLayoutInflater().inflate(R.layout.view_pin, null);
        addContentView(pinOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pinDots = pinOverlay.findViewById(R.id.tv_pin_dots);
        pinError = pinOverlay.findViewById(R.id.tv_pin_error);
        GridLayout pad = pinOverlay.findViewById(R.id.pin_pad);

        int key = getResources().getDimensionPixelSize(R.dimen.pin_key);
        int gap = getResources().getDimensionPixelSize(R.dimen.pin_key_margin);
        int dp1 = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "\u232B"};
        for (String label : labels) {
            View v;
            if (label.isEmpty()) {
                v = new View(this);
            } else {
                MaterialButton b = new MaterialButton(this);
                b.setText(label);
                b.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_pin_key));
                b.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
                b.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.app_surface)));
                b.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.app_outline)));
                b.setStrokeWidth(dp1);
                b.setPadding(0, 0, 0, 0);
                b.setAllCaps(false);
                if (Character.isDigit(label.charAt(0))) {
                    b.setOnClickListener(x -> onPinDigit(label));
                } else {
                    b.setContentDescription(getString(R.string.pin_backspace));
                    b.setOnClickListener(x -> onPinBackspace());
                }
                v = b;
            }
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = key;
            lp.height = key;
            lp.setMargins(gap, gap, gap, gap);
            pad.addView(v, lp);
        }
        updatePinDots();
    }

    private void onPinDigit(String digit) {
        if (pinBusy || pinEntry.length() >= APP_PIN.length()) return;
        pinEntry.append(digit);
        pinError.setVisibility(View.INVISIBLE);
        updatePinDots();
        if (pinEntry.length() == APP_PIN.length()) checkPin();
    }

    private void onPinBackspace() {
        if (pinBusy || pinEntry.length() == 0) return;
        pinEntry.setLength(pinEntry.length() - 1);
        pinError.setVisibility(View.INVISIBLE);
        updatePinDots();
    }

    private void checkPin() {
        if (pinEntry.toString().equals(APP_PIN)) {
            unlocked = true;
            ((ViewGroup) pinOverlay.getParent()).removeView(pinOverlay);
            pinOverlay = null;
            return;
        }
        pinBusy = true;
        pinError.setVisibility(View.VISIBLE);
        TranslateAnimation shake = new TranslateAnimation(-14, 14, 0, 0);
        shake.setDuration(60);
        shake.setRepeatCount(5);
        shake.setRepeatMode(TranslateAnimation.REVERSE);
        pinOverlay.findViewById(R.id.pin_content).startAnimation(shake);
        handler.postDelayed(() -> {
            pinEntry.setLength(0);
            updatePinDots();
            pinBusy = false;
        }, 450);
    }

    private void updatePinDots() {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < APP_PIN.length(); i++) {
            if (i > 0) dots.append("  ");
            dots.append(i < pinEntry.length() ? "\u25CF" : "\u25CB");
        }
        pinDots.setText(dots);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Install state may have changed while the system installer was in front.
        if (loaded) applyFilter();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- data

    private void fetchAppData() {
        showLoading();
        new Thread(() -> {
            try {
                URLConnection conn = new URL(JSON_URL).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                StringBuilder jsonBuilder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonBuilder.append(line);
                    }
                }

                JSONObject jsonObject = new JSONObject(jsonBuilder.toString());
                ArrayList<AppInfo> fetched = new ArrayList<>();
                Iterator<String> keys = jsonObject.keys();
                while (keys.hasNext()) {
                    String packageName = keys.next();
                    JSONObject appJson = jsonObject.getJSONObject(packageName);
                    fetched.add(new AppInfo(
                            this,
                            appJson.getString("name"),
                            packageName,
                            appJson.getInt("versionCode"),
                            appJson.getString("versionName"),
                            appJson.getString("apkUrl"),
                            appJson.getString("iconUrl")
                    ));
                }

                runOnUiThread(() -> {
                    appList.clear();
                    appList.addAll(fetched);
                    loaded = true;
                    applyFilter();
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    loaded = false;
                    showError();
                });
            }
        }).start();
    }

    private void applyFilter() {
        ArrayList<AppInfo> visible = new ArrayList<>();
        for (AppInfo app : appList) {
            AppState state = stateOf(app);
            if (filter == FILTER_UPDATES && state != AppState.UPDATE_AVAILABLE) continue;
            if (filter == FILTER_INSTALLED && state == AppState.NOT_INSTALLED) continue;
            if (!query.isEmpty() && !app.getName().toLowerCase(Locale.ROOT).contains(query)) continue;
            visible.add(app);
        }
        adapter.submit(visible);

        loadingView.setVisibility(View.GONE);
        if (visible.isEmpty()) {
            showState(R.drawable.ic_search, R.string.empty_title, R.string.empty_body, false);
            appListView.setVisibility(View.GONE);
        } else {
            stateView.setVisibility(View.GONE);
            appListView.setVisibility(View.VISIBLE);
        }
    }

    private void showLoading() {
        loadingView.setVisibility(View.VISIBLE);
        stateView.setVisibility(View.GONE);
        appListView.setVisibility(View.GONE);
    }

    private void showError() {
        loadingView.setVisibility(View.GONE);
        appListView.setVisibility(View.GONE);
        showState(R.drawable.ic_cloud_off, R.string.error_title, R.string.error_body, true);
    }

    private void showState(int icon, int title, int body, boolean retry) {
        stateIcon.setImageResource(icon);
        stateTitle.setText(title);
        stateBody.setText(body);
        retryButton.setVisibility(retry ? View.VISIBLE : View.GONE);
        stateView.setVisibility(View.VISIBLE);
    }

    private AppState stateOf(AppInfo app) {
        int local = getLocalVersionCode(app.getPackageName());
        if (local == -1) return AppState.NOT_INSTALLED;
        if (local < app.getVersionCode()) return AppState.UPDATE_AVAILABLE;
        return AppState.UP_TO_DATE;
    }

    // ------------------------------------------------------------- adapter

    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {
        private ArrayList<AppInfo> items = new ArrayList<>();

        void submit(ArrayList<AppInfo> newItems) {
            items = newItems;
            notifyDataSetChanged();
        }

        void notifyApp(String packageName) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getPackageName().equals(packageName)) {
                    notifyItemChanged(i);
                    return;
                }
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_row_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            AppInfo app = items.get(position);
            Context ctx = h.itemView.getContext();
            int accent = ContextCompat.getColor(ctx, R.color.accent);

            h.name.setText(app.getName());
            Glide.with(ctx).load(app.getIconUrl()).into(h.icon);

            AppState state = stateOf(app);
            String localVersion = getLocalVersionName(app.getPackageName());
            Integer progress = downloads.get(app.getPackageName());
            boolean downloading = progress != null;

            int statusColor;
            int statusText;
            int actionText;
            switch (state) {
                case UPDATE_AVAILABLE:
                    h.version.setText("v" + localVersion + "  →  v" + app.getVersionName());
                    statusColor = R.color.status_update;
                    statusText = R.string.status_update_available;
                    actionText = R.string.action_update;
                    break;
                case UP_TO_DATE:
                    h.version.setText("Version " + localVersion);
                    statusColor = R.color.status_ok;
                    statusText = R.string.status_up_to_date;
                    actionText = R.string.action_open;
                    break;
                default:
                    h.version.setText("Version " + app.getVersionName());
                    statusColor = R.color.status_new;
                    statusText = R.string.status_not_installed;
                    actionText = R.string.action_install;
                    break;
            }

            int color = ContextCompat.getColor(ctx, statusColor);
            h.status.setText(statusText);
            h.status.setTextColor(color);
            h.status.setBackgroundTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 36)));

            if (downloading) {
                h.button.setText(R.string.action_downloading);
                h.button.setEnabled(false);
                h.progressGroup.setVisibility(View.VISIBLE);
                h.progressBar.setProgress(progress);
                h.progressText.setText(progress + "%");
            } else {
                h.progressGroup.setVisibility(View.GONE);
                h.button.setEnabled(true);
                h.button.setText(actionText);
                if (state == AppState.UP_TO_DATE) {
                    // Tonal button for the low-emphasis "Open" action.
                    int surface = ContextCompat.getColor(ctx, R.color.app_surface);
                    h.button.setBackgroundTintList(ColorStateList.valueOf(
                            ColorUtils.compositeColors(ColorUtils.setAlphaComponent(accent, 36), surface)));
                    h.button.setTextColor(accent);
                } else {
                    h.button.setBackgroundTintList(ColorStateList.valueOf(accent));
                    h.button.setTextColor(ContextCompat.getColor(ctx, R.color.white));
                }
            }

            boolean canUninstall = state != AppState.NOT_INSTALLED
                    && !downloading
                    && !app.getPackageName().equals(getPackageName());
            h.uninstall.setVisibility(canUninstall ? View.VISIBLE : View.GONE);
            h.uninstall.setOnClickListener(v -> uninstallApp(app));

            h.button.setOnClickListener(v -> {
                switch (stateOf(app)) {
                    case UP_TO_DATE:
                        openApp(app);
                        break;
                    default:
                        startDownload(app);
                        break;
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, version, status, progressText;
            final MaterialButton button, uninstall;
            final LinearLayout progressGroup;
            final ProgressBar progressBar;

            Holder(@NonNull View v) {
                super(v);
                icon = v.findViewById(R.id.iv_app_logo);
                name = v.findViewById(R.id.tv_app_name);
                version = v.findViewById(R.id.curr_ver_display);
                status = v.findViewById(R.id.tv_status);
                button = v.findViewById(R.id.btn_check_update);
                uninstall = v.findViewById(R.id.btn_uninstall);
                progressGroup = v.findViewById(R.id.progress_group);
                progressBar = v.findViewById(R.id.download_progress);
                progressText = v.findViewById(R.id.tv_progress);
            }
        }
    }

    // ------------------------------------------------------------ actions

    private void openApp(AppInfo app) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (launch != null) {
            startActivity(launch);
        } else {
            Toast.makeText(this, "This app has no launcher screen", Toast.LENGTH_SHORT).show();
        }
    }

    private void uninstallApp(AppInfo app) {
        // The system shows its own confirmation; onResume() refreshes the list afterwards.
        Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + app.getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot uninstall " + app.getName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startDownload(AppInfo app) {
        String pkg = app.getPackageName();
        if (downloads.containsKey(pkg)) return;

        File destinationFile = new File(getExternalFilesDir(null), app.getName() + ".apk");
        if (destinationFile.exists()) destinationFile.delete();

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(app.getApkUrl()));
        request.setTitle("Downloading " + app.getName());
        request.setDescription("Updating app...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setMimeType("application/vnd.android.package-archive");
        request.setDestinationUri(Uri.fromFile(destinationFile));

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        long downloadId = dm.enqueue(request);

        downloads.put(pkg, 0);
        adapter.notifyApp(pkg);
        pollDownload(dm, downloadId, app, destinationFile);
    }

    private void pollDownload(DownloadManager dm, long downloadId, AppInfo app, File file) {
        String pkg = app.getPackageName();
        int status = -1;
        int progress = 0;

        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = dm.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                if (total > 0) progress = (int) ((done * 100L) / total);
            }
        }

        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            downloads.remove(pkg);
            adapter.notifyApp(pkg);
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            startInstallation(this, apkUri);
        } else if (status == DownloadManager.STATUS_FAILED || status == -1) {
            downloads.remove(pkg);
            adapter.notifyApp(pkg);
            Toast.makeText(this, "Download failed for " + app.getName(), Toast.LENGTH_SHORT).show();
        } else {
            downloads.put(pkg, progress);
            adapter.notifyApp(pkg);
            handler.postDelayed(() -> pollDownload(dm, downloadId, app, file), PROGRESS_POLL_MS);
        }
    }

    private void startInstallation(Context context, Uri apkUri) {
        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(installIntent);
    }

    public String getLocalVersionName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "N/A";
        }
    }

    public int getLocalVersionCode(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }
}
