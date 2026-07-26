package com.zzb.clashautotoggle;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements MonitorService.StatusListener {

    private static final int REQUEST_LOCATION = 1;
    private static final int REQUEST_BACKGROUND_LOCATION = 2;
    private static final int REQUEST_NOTIFICATION = 3;

    private Settings settings;
    private CompoundButton enabledSwitch;
    private TextView statusView;
    private TextView permissionView;
    private LinearLayout rulesContainer;
    private RadioGroup defaultActionGroup;
    private RadioGroup clientGroup;
    private EditText packageInput;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new Settings(this);

        enabledSwitch = findViewById(R.id.switch_enabled);
        statusView = findViewById(R.id.text_status);
        permissionView = findViewById(R.id.text_permissions);
        rulesContainer = findViewById(R.id.container_rules);
        defaultActionGroup = findViewById(R.id.group_default_action);
        clientGroup = findViewById(R.id.group_client);
        packageInput = findViewById(R.id.input_package);
        logView = findViewById(R.id.text_log);

        enabledSwitch.setChecked(settings.isEnabled());
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> onEnabledChanged(checked));

        switch (settings.getDefaultAction()) {
            case Settings.ACTION_ENABLE:
                defaultActionGroup.check(R.id.radio_default_enable);
                break;
            case Settings.ACTION_DISABLE:
                defaultActionGroup.check(R.id.radio_default_disable);
                break;
            default:
                defaultActionGroup.check(R.id.radio_default_keep);
                break;
        }
        defaultActionGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int action = Settings.ACTION_KEEP;
            if (checkedId == R.id.radio_default_enable) {
                action = Settings.ACTION_ENABLE;
            } else if (checkedId == R.id.radio_default_disable) {
                action = Settings.ACTION_DISABLE;
            }
            settings.setDefaultAction(action);
            reapply();
        });

        clientGroup.check(clientRadioId(settings.getClientType()));
        clientGroup.setOnCheckedChangeListener((group, checkedId) -> {
            settings.setClientType(clientType(checkedId));
            renderClientPackage();
            refreshStatus();
            reapply();
        });

        renderClientPackage();
        findViewById(R.id.button_save_package).setOnClickListener(v -> {
            String value = packageInput.getText().toString().trim();
            settings.setClashPackage(value.isEmpty()
                    ? Settings.getDefaultPackage(settings.getClientType()) : value);
            renderClientPackage();
            toast(getString(R.string.saved));
            reapply();
        });

        findViewById(R.id.button_add_current).setOnClickListener(v -> addCurrentWifi());
        findViewById(R.id.button_add_manual).setOnClickListener(v -> showAddDialog(null));
        findViewById(R.id.button_permissions).setOnClickListener(v -> requestNextPermission());

        findViewById(R.id.button_log_refresh).setOnClickListener(v -> renderLog());
        findViewById(R.id.button_log_copy).setOnClickListener(v -> copyLog());
        findViewById(R.id.button_log_clear).setOnClickListener(v -> {
            AppLog.clear(this);
            renderLog();
            toast(getString(R.string.log_cleared));
        });
    }

    private void renderLog() {
        String log = AppLog.read(this);
        logView.setText(TextUtils.isEmpty(log) ? getString(R.string.log_empty) : log);
    }

    private void copyLog() {
        String log = AppLog.read(this);
        if (TextUtils.isEmpty(log)) {
            toast(getString(R.string.log_empty));
            return;
        }
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.section_log), log));
        toast(getString(R.string.log_copied));
    }

    @Override
    protected void onStart() {
        super.onStart();
        MonitorService.setStatusListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderRules();
        refreshStatus();
        renderLog();
    }

    @Override
    protected void onStop() {
        MonitorService.setStatusListener(null);
        super.onStop();
    }

    @Override
    public void onStatusChanged() {
        refreshStatus();
        renderLog();
    }

    private void onEnabledChanged(boolean checked) {
        settings.setEnabled(checked);
        AppLog.i(this, "用户" + (checked ? "开启" : "关闭") + "了自动切换");
        if (checked) {
            if (!hasLocationPermission()) {
                requestNextPermission();
            }
            MonitorService.start(this);
        } else {
            MonitorService.stop(this);
        }
        refreshStatus();
    }

    private static int clientRadioId(int clientType) {
        switch (clientType) {
            case Settings.CLIENT_FLCLASH:
                return R.id.radio_client_flclash;
            case Settings.CLIENT_CUSTOM:
                return R.id.radio_client_custom;
            default:
                return R.id.radio_client_cmfa;
        }
    }

    private static int clientType(int radioId) {
        if (radioId == R.id.radio_client_flclash) {
            return Settings.CLIENT_FLCLASH;
        }
        if (radioId == R.id.radio_client_custom) {
            return Settings.CLIENT_CUSTOM;
        }
        return Settings.CLIENT_CMFA;
    }

    private void renderClientPackage() {
        int clientType = settings.getClientType();
        // The package name of the two known clients is fixed; only the custom
        // client is meant to be edited by hand.
        boolean editable = clientType == Settings.CLIENT_CUSTOM;
        packageInput.setHint(Settings.getDefaultPackage(clientType));
        packageInput.setText(settings.getClashPackage());
        packageInput.setEnabled(editable);
        findViewById(R.id.button_save_package).setEnabled(editable);
    }

    private void reapply() {
        if (settings.isEnabled()) {
            MonitorService.start(this);
        }
    }

    private void refreshStatus() {
        String status = MonitorService.getStatusText();
        if (!settings.isEnabled()) {
            status = getString(R.string.status_disabled);
        } else if (TextUtils.isEmpty(status)) {
            status = getString(R.string.status_starting);
        }
        statusView.setText(status);
        permissionView.setText(describeMissingPermissions());
    }

    private String describeMissingPermissions() {
        List<String> missing = new ArrayList<>();
        if (!hasLocationPermission()) {
            missing.add(getString(R.string.permission_location));
        } else if (!hasBackgroundLocationPermission()) {
            missing.add(getString(R.string.permission_background_location));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(getString(R.string.permission_notification));
        }
        if (!canDrawOverlays()) {
            missing.add(getString(R.string.permission_overlay));
        }
        if (!isIgnoringBatteryOptimizations()) {
            missing.add(getString(R.string.permission_battery));
        }
        if (!ClashController.isInstalled(this, settings.getClashPackage())) {
            missing.add(getString(R.string.permission_clash_missing));
        }
        return missing.isEmpty()
                ? getString(R.string.permission_all_granted)
                : getString(R.string.permission_missing_format, TextUtils.join("\n· ", missing));
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || android.provider.Settings.canDrawOverlays(this);
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** Walks the user through the permissions that are still missing. */
    private void requestNextPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasLocationPermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_background_location)
                    .setMessage(R.string.permission_background_location_reason)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> requestPermissions(
                            new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                            REQUEST_BACKGROUND_LOCATION))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION);
            return;
        }
        if (!canDrawOverlays()) {
            startSettings(new Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (!isIgnoringBatteryOptimizations()) {
            startSettings(new Intent(
                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        }
        toast(getString(R.string.permission_all_granted));
    }

    private void startSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast(getString(R.string.open_settings_failed));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
        if (settings.isEnabled()) {
            MonitorService.start(this);
        }
    }

    private void addCurrentWifi() {
        String ssid = MonitorService.getCurrentSsid();
        if (TextUtils.isEmpty(ssid)) {
            toast(getString(R.string.current_wifi_unknown));
            showAddDialog(null);
        } else {
            showAddDialog(ssid);
        }
    }

    private void showAddDialog(String presetSsid) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_rule, null);
        EditText ssidInput = view.findViewById(R.id.input_ssid);
        CheckBox enableBox = view.findViewById(R.id.check_enable);
        if (presetSsid != null) {
            ssidInput.setText(presetSsid);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_rule)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String ssid = ssidInput.getText().toString().trim();
                    if (ssid.isEmpty()) {
                        toast(getString(R.string.ssid_empty));
                        return;
                    }
                    addRule(new Rule(ssid, enableBox.isChecked()));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addRule(Rule rule) {
        List<Rule> rules = settings.getRules();
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).matches(rule.ssid)) {
                rules.set(i, rule);
                settings.setRules(rules);
                renderRules();
                reapply();
                return;
            }
        }
        rules.add(rule);
        settings.setRules(rules);
        renderRules();
        reapply();
    }

    private void renderRules() {
        rulesContainer.removeAllViews();
        List<Rule> rules = settings.getRules();
        if (rules.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_rules);
            rulesContainer.addView(empty);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < rules.size(); i++) {
            final int index = i;
            Rule rule = rules.get(i);
            ViewGroup row = (ViewGroup) inflater.inflate(R.layout.item_rule, rulesContainer, false);
            TextView ssidView = row.findViewById(R.id.text_ssid);
            Button actionButton = row.findViewById(R.id.button_action);
            Button deleteButton = row.findViewById(R.id.button_delete);
            ssidView.setText(rule.ssid);
            actionButton.setText(rule.enableClash ? R.string.action_enable : R.string.action_disable);
            actionButton.setOnClickListener(v -> {
                List<Rule> current = settings.getRules();
                if (index < current.size()) {
                    Rule old = current.get(index);
                    current.set(index, new Rule(old.ssid, !old.enableClash));
                    settings.setRules(current);
                    renderRules();
                    reapply();
                }
            });
            deleteButton.setOnClickListener(v -> {
                List<Rule> current = settings.getRules();
                if (index < current.size()) {
                    current.remove(index);
                    settings.setRules(current);
                    renderRules();
                    reapply();
                }
            });
            rulesContainer.addView(row);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
