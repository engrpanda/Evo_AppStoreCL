package com.evodesign.playstoreclient;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;

public class MainActivity extends AppCompatActivity {
    private ListView appListView;
    private ArrayList<AppInfo> appList = new ArrayList<>();
    private static final String JSON_URL = "https://raw.githubusercontent.com/engrpanda/Evo_AppStoreCL/refs/heads/main/evoapp.json";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appListView = findViewById(R.id.list_view);
        fetchAppData();
    }

    private void fetchAppData() {
        new Thread(() -> {
            try {
                URL url = new URL(JSON_URL);
                BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
                StringBuilder jsonBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonBuilder.append(line);
                }
                JSONObject jsonObject = new JSONObject(jsonBuilder.toString());
                Iterator<String> keys = jsonObject.keys();

                while (keys.hasNext()) {
                    String packageName = keys.next();
                    JSONObject appJson = jsonObject.getJSONObject(packageName);
                    AppInfo app = new AppInfo(
                            this,
                            appJson.getString("name"),
                            packageName,
                            appJson.getInt("versionCode"),
                            appJson.getString("versionName"),
                            appJson.getString("apkUrl"),
                            appJson.getString("iconUrl")
                    );
                    appList.add(app);
                }

                runOnUiThread(() -> {
                    AppUpdateAdapter adapter = new AppUpdateAdapter(MainActivity.this, appList);
                    appListView.setAdapter(adapter);
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error fetching JSON", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    public class AppUpdateAdapter extends BaseAdapter {
        private final Context context;
        private final ArrayList<AppInfo> appList;

        public AppUpdateAdapter(Context context, ArrayList<AppInfo> appList) {
            this.context = context;
            this.appList = appList;
        }

        @Override
        public int getCount() {
            return appList.size();
        }

        @Override
        public Object getItem(int position) {
            return appList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(context).inflate(R.layout.list_row_item, parent, false);
            }

            ImageView icon = row.findViewById(R.id.iv_app_logo);
            TextView name = row.findViewById(R.id.tv_app_name);
            TextView version = row.findViewById(R.id.curr_ver_display);
            Button updateButton = row.findViewById(R.id.btn_check_update);
            ProgressBar downloadProgress = row.findViewById(R.id.download_progress);

            AppInfo app = appList.get(position);

            name.setText(app.getName());

            String localVersion = getLocalVersionName(app.getPackageName());
            if (localVersion.equals("N/A")) {
                version.setText("Latest: v" + app.getVersionName());
            } else {
                version.setText("Installed: v" + localVersion + "\nLatest: v" + app.getVersionName());
            }

            Glide.with(context).load(app.getIconUrl()).into(icon);

            updateButton.setOnClickListener(v -> {
                if (getLocalVersionCode(app.getPackageName()) < app.getVersionCode() || getLocalVersionCode(app.getPackageName()) == -1)
                {
                    downloadProgress.setVisibility(View.VISIBLE);
                    downloadAndInstallApk(context, app.getApkUrl(), app.getName(), downloadProgress);
                } else {
                    Toast.makeText(context, "App is already up-to-date", Toast.LENGTH_SHORT).show();
                }
            });

            return row;
        }
    }

    private void downloadAndInstallApk(Context context, String apkUrl, String appName, ProgressBar downloadProgress) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setTitle("Downloading " + appName);
        request.setDescription("Updating app...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setMimeType("application/vnd.android.package-archive");

        // Save the APK to the app's external files directory
        File destinationFile = new File(context.getExternalFilesDir(null), appName + ".apk");
        request.setDestinationUri(Uri.fromFile(destinationFile));

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long downloadId = dm.enqueue(request);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    DownloadManager.Query query = new DownloadManager.Query();
                    query.setFilterById(downloadId);
                    Cursor cursor = dm.query(query);
                    if (cursor.moveToFirst()) {
                        int bytesDownloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        int bytesTotal = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        if (bytesTotal > 0) {
                            int progress = (int) ((bytesDownloaded * 100L) / bytesTotal);
                            downloadProgress.setProgress(progress);
                        }

                        if (bytesDownloaded == bytesTotal) {
                            // Use FileProvider to get a content URI
                            Uri apkUri = FileProvider.getUriForFile(context,
                                    context.getPackageName() + ".provider",
                                    destinationFile);
                            startInstallation(ctx, apkUri);
                            // Unregister after handling
                            unregisterReceiver(this);
                        }
                    }
                    cursor.close();
                }
            }
        };

        registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
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
