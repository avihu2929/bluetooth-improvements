package com.example.bluetoothtracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.InputFilter;
import android.view.WindowInsets;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Typeface;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_BACKGROUND_PERMISSION = 11;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 12;
    private static final String KEY_HOURLY_PAY = "hourly_pay_per_hour";

    private final Map<String, NearbyDevice> nearbyDevices = new LinkedHashMap<>();
    private final ArrayList<String> nearbyRows = new ArrayList<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault());
    private YearMonth displayedMonth = YearMonth.now();
    private final ArrayList<TrackingSession> calendarSessions = new ArrayList<>();

    private SharedPreferences preferences;
    private TrackerDatabase database;
    private BluetoothLeScanner scanner;
    private ScanCallback discoveryCallback;
    private ArrayAdapter<String> nearbyAdapter;
    private CalendarAdapter calendarAdapter;
    private TextView statusView;
    private TextView trackingView;
    private TextView rangeStatusView;
    private TextView listHeading;
    private TextView monthTitle;
    private TextView settingsHeading;
    private TextView monthlySummaryView;
    private EditText hourlyPayInput;
    private EditText scanIntervalInput;
    private EditText sessionGapInput;
    private ListView nearbyList;
    private GridView calendarGrid;
    private LinearLayout monthNavigation;
    private LinearLayout weekdayHeader;
    private LinearLayout calendarPage;
    private LinearLayout settingsPage;
    private FrameLayout pageContainer;
    private View drawerScrim;
    private LinearLayout drawerPanel;
    private Button chooseButton;
    private Button stopButton;
    private boolean resumed;
    private boolean choosingDevice;
    private boolean askedForBackgroundThisRun;
    private boolean askedForNotificationsThisRun;
    private final Runnable rangeStatusUpdater = new Runnable() {
        @Override
        public void run() {
            updateRangeStatus();
            if (resumed) uiHandler.postDelayed(this, 30_000);
        }
    };

    private final BroadcastReceiver logUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshTrackingSummary();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(BleScanReceiver.PREFS, MODE_PRIVATE);
        BleScanReceiver.sessionGapMillis(this);
        database = new TrackerDatabase(this);
        SessionNotifications.createChannel(this);
        choosingDevice = preferences.getString(BleScanReceiver.KEY_ADDRESS, "").isEmpty();
        buildUi();
        showPage(!choosingDevice);
        renderTrackingState();
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onResume() {
        super.onResume();
        resumed = true;
        IntentFilter filter = new IntentFilter(BleScanReceiver.ACTION_LOG_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logUpdatedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logUpdatedReceiver, filter);
        }
        uiHandler.removeCallbacks(rangeStatusUpdater);
        uiHandler.post(rangeStatusUpdater);
        if (preferences.getBoolean(BleScanReceiver.KEY_ACTIVE, false)) {
            ensurePermissionsAndStartTracking();
        } else if (choosingDevice || preferences.getString(BleScanReceiver.KEY_ADDRESS, "").isEmpty()) {
            ensurePermissionsAndStartDiscovery();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        uiHandler.removeCallbacks(rangeStatusUpdater);
        stopDiscovery();
        unregisterReceiver(logUpdatedReceiver);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (database != null) database.close();
        super.onDestroy();
    }

    private void buildUi() {
        int padding = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, dp(16), padding, dp(8));
        int baseTop = dp(16);
        int baseBottom = dp(8);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(padding + left, baseTop + top, padding + right, baseBottom + bottom);
            return insets;
        });

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView menuButton = new TextView(this);
        menuButton.setText("☰");
        menuButton.setTextSize(24);
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setContentDescription("Open navigation menu");
        menuButton.setBackgroundResource(android.R.drawable.list_selector_background);
        menuButton.setOnClickListener(view -> openDrawer());
        toolbar.addView(menuButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText("Bluetooth Tracker");
        title.setTextSize(25);
        title.setTextColor(0xFF17202A);
        toolbar.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(toolbar);

        rangeStatusView = new TextView(this);
        rangeStatusView.setTextSize(13);
        rangeStatusView.setPadding(dp(4), 0, 0, dp(6));
        rangeStatusView.setVisibility(View.GONE);
        root.addView(rangeStatusView);

        pageContainer = new FrameLayout(this);
        root.addView(pageContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        calendarPage = new LinearLayout(this);
        calendarPage.setOrientation(LinearLayout.VERTICAL);
        pageContainer.addView(calendarPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        settingsPage = new LinearLayout(this);
        settingsPage.setOrientation(LinearLayout.VERTICAL);
        pageContainer.addView(settingsPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(0xFF455A64);
        statusView.setPadding(0, dp(8), 0, dp(8));
        settingsPage.addView(statusView);

        trackingView = new TextView(this);
        trackingView.setTextSize(14);
        trackingView.setTextColor(0xFF17202A);
        trackingView.setPadding(0, dp(8), 0, dp(12));
        settingsPage.addView(trackingView);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        chooseButton = new Button(this);
        chooseButton.setText("Find a device");
        chooseButton.setOnClickListener(view -> beginChoosingDevice());
        controls.addView(chooseButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        stopButton = new Button(this);
        stopButton.setText("Stop tracking");
        stopButton.setOnClickListener(view -> {
            if (preferences.getBoolean(BleScanReceiver.KEY_ACTIVE, false)) stopTrackingByUser();
            else resumeTracking();
        });
        controls.addView(stopButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        settingsPage.addView(controls);

        TextView payLabel = new TextView(this);
        payLabel.setText("Shift pay per hour");
        payLabel.setTextSize(16);
        payLabel.setTextColor(0xFF17202A);
        payLabel.setPadding(0, dp(12), 0, dp(4));
        settingsPage.addView(payLabel);

        hourlyPayInput = new EditText(this);
        hourlyPayInput.setSingleLine(true);
        hourlyPayInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        hourlyPayInput.setHint("Enter hourly rate");
        hourlyPayInput.setText(preferences.getString(KEY_HOURLY_PAY, ""));
        settingsPage.addView(hourlyPayInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        hourlyPayInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable text) {
                preferences.edit().putString(KEY_HOURLY_PAY, text.toString()).apply();
                updateMonthlySummary();
            }
        });

        TextView scanIntervalLabel = new TextView(this);
        scanIntervalLabel.setText("Scan result interval (seconds)");
        scanIntervalLabel.setTextSize(16);
        scanIntervalLabel.setTextColor(0xFF17202A);
        scanIntervalLabel.setPadding(0, dp(12), 0, dp(4));
        settingsPage.addView(scanIntervalLabel);

        scanIntervalInput = new EditText(this);
        scanIntervalInput.setSingleLine(true);
        scanIntervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        scanIntervalInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        scanIntervalInput.setHint("1–300 seconds");
        scanIntervalInput.setText(Integer.toString(preferences.getInt(
                BleScanReceiver.KEY_SCAN_INTERVAL_SECONDS,
                BleScanReceiver.DEFAULT_SCAN_INTERVAL_SECONDS)));
        settingsPage.addView(scanIntervalInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView scanIntervalHint = new TextView(this);
        scanIntervalHint.setText("Lower intervals update sooner but use more battery.");
        scanIntervalHint.setTextSize(12);
        scanIntervalHint.setTextColor(0xFF607D8B);
        settingsPage.addView(scanIntervalHint);

        Button applyScanIntervalButton = new Button(this);
        applyScanIntervalButton.setText("Apply scan interval");
        applyScanIntervalButton.setOnClickListener(view -> applyScanInterval());
        settingsPage.addView(applyScanIntervalButton);

        TextView sessionGapLabel = new TextView(this);
        sessionGapLabel.setText("No-signal timeout (seconds)");
        sessionGapLabel.setTextSize(16);
        sessionGapLabel.setTextColor(0xFF17202A);
        sessionGapLabel.setPadding(0, dp(12), 0, dp(4));
        settingsPage.addView(sessionGapLabel);

        sessionGapInput = new EditText(this);
        sessionGapInput.setSingleLine(true);
        sessionGapInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        sessionGapInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        sessionGapInput.setHint(BleScanReceiver.minimumSessionGapSeconds(this) + "–7,200 seconds");
        sessionGapInput.setText(Integer.toString(preferences.getInt(
                BleScanReceiver.KEY_SESSION_GAP_SECONDS,
                BleScanReceiver.DEFAULT_SESSION_GAP_SECONDS)));
        settingsPage.addView(sessionGapInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView sessionGapHint = new TextView(this);
        sessionGapHint.setText("Minimum 30 seconds, or twice the scan interval if higher.");
        sessionGapHint.setTextSize(12);
        sessionGapHint.setTextColor(0xFF607D8B);
        settingsPage.addView(sessionGapHint);

        Button applySessionGapButton = new Button(this);
        applySessionGapButton.setText("Apply no-signal timeout");
        applySessionGapButton.setOnClickListener(view -> applySessionGap());
        settingsPage.addView(applySessionGapButton);

        listHeading = new TextView(this);
        listHeading.setTextSize(19);
        listHeading.setTextColor(0xFF17202A);
        listHeading.setPadding(0, dp(12), 0, dp(4));
        listHeading.setText("Tracking time by day");
        calendarPage.addView(listHeading);

        monthlySummaryView = new TextView(this);
        monthlySummaryView.setTextSize(14);
        monthlySummaryView.setTextColor(0xFF455A64);
        monthlySummaryView.setPadding(0, dp(2), 0, dp(6));
        calendarPage.addView(monthlySummaryView);

        LinearLayout exportRow = new LinearLayout(this);
        exportRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button copyMonthButton = new Button(this);
        copyMonthButton.setText("Copy month");
        copyMonthButton.setOnClickListener(view -> copyDisplayedMonth());
        exportRow.addView(copyMonthButton);
        calendarPage.addView(exportRow);

        settingsHeading = new TextView(this);
        settingsHeading.setTextSize(16);
        settingsHeading.setTextColor(0xFF17202A);
        settingsHeading.setPadding(0, dp(12), 0, dp(4));
        settingsPage.addView(settingsHeading);

        monthNavigation = new LinearLayout(this);
        monthNavigation.setGravity(Gravity.CENTER_VERTICAL);
        Button previousMonth = new Button(this);
        previousMonth.setText("‹");
        previousMonth.setOnClickListener(view -> changeMonth(-1));
        monthNavigation.addView(previousMonth, new LinearLayout.LayoutParams(dp(52),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        monthTitle = new TextView(this);
        monthTitle.setGravity(Gravity.CENTER);
        monthTitle.setTextSize(17);
        monthTitle.setTextColor(0xFF17202A);
        monthTitle.setOnClickListener(view -> showMonthPicker());
        monthNavigation.addView(monthTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button todayButton = new Button(this);
        todayButton.setText("Today");
        todayButton.setOnClickListener(view -> {
            displayedMonth = YearMonth.now();
            renderCalendar();
        });
        monthNavigation.addView(todayButton, new LinearLayout.LayoutParams(dp(76),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button nextMonth = new Button(this);
        nextMonth.setText("›");
        nextMonth.setOnClickListener(view -> changeMonth(1));
        monthNavigation.addView(nextMonth, new LinearLayout.LayoutParams(dp(52),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        calendarPage.addView(monthNavigation);

        weekdayHeader = new LinearLayout(this);
        weekdayHeader.setOrientation(LinearLayout.HORIZONTAL);
        buildWeekdayHeader();
        calendarPage.addView(weekdayHeader);

        calendarAdapter = new CalendarAdapter();
        calendarGrid = new GridView(this);
        calendarGrid.setNumColumns(7);
        calendarGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        calendarGrid.setHorizontalSpacing(dp(2));
        calendarGrid.setVerticalSpacing(dp(2));
        calendarGrid.setAdapter(calendarAdapter);
        calendarGrid.setOnItemClickListener((parent, view, position, id) -> {
            LocalDate date = calendarAdapter.getItem(position);
            if (date != null) showDayDetails(date);
        });
        calendarGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            LocalDate date = calendarAdapter.getItem(position);
            if (date == null) return false;
            showDayShiftManager(date);
            return true;
        });
        calendarPage.addView(calendarGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        nearbyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, nearbyRows);
        nearbyList = new ListView(this);
        nearbyList.setAdapter(nearbyAdapter);
        nearbyList.setOnItemClickListener((parent, view, position, id) -> {
            if (choosingDevice && position >= 0 && position < nearbyDevices.size()) {
                NearbyDevice device = new ArrayList<>(nearbyDevices.values()).get(position);
                selectDevice(device);
            }
        });
        settingsPage.addView(nearbyList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout appRoot = new FrameLayout(this);
        appRoot.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(view -> closeDrawer());
        appRoot.addView(drawerScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setPadding(dp(20), dp(24), dp(16), dp(16));
        drawerPanel.setBackgroundColor(0xFFFFFFFF);
        drawerPanel.setElevation(dp(12));
        drawerPanel.setVisibility(View.GONE);
        drawerPanel.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                top = bars.top;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
            }
            view.setPadding(dp(20) + left, dp(24) + top, dp(16), dp(16));
            return insets;
        });
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(dp(280),
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.TOP);
        appRoot.addView(drawerPanel, drawerParams);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("Bluetooth Tracker");
        drawerTitle.setTextSize(20);
        drawerTitle.setTextColor(0xFF17202A);
        drawerTitle.setPadding(0, 0, 0, dp(20));
        drawerPanel.addView(drawerTitle);
        addDrawerItem("Calendar", this::showCalendarPage);
        addDrawerItem("Settings", this::showSettingsPage);

        setContentView(appRoot);
    }

    private void addDrawerItem(String label, Runnable action) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(18);
        item.setTextColor(0xFF17202A);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(8), dp(14), dp(8), dp(14));
        item.setBackgroundResource(android.R.drawable.list_selector_background);
        item.setOnClickListener(view -> action.run());
        drawerPanel.addView(item, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void openDrawer() {
        drawerScrim.setVisibility(View.VISIBLE);
        drawerPanel.setVisibility(View.VISIBLE);
    }

    private void closeDrawer() {
        drawerScrim.setVisibility(View.GONE);
        drawerPanel.setVisibility(View.GONE);
    }

    private void showPage(boolean showCalendar) {
        calendarPage.setVisibility(showCalendar ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(showCalendar ? View.GONE : View.VISIBLE);
    }

    private void showCalendarPage() {
        if (choosingDevice) {
            stopDiscovery();
            choosingDevice = false;
        }
        showPage(true);
        closeDrawer();
        renderTrackingState();
    }

    private void showSettingsPage() {
        showPage(false);
        closeDrawer();
        if (preferences.getString(BleScanReceiver.KEY_ADDRESS, "").isEmpty()) {
            choosingDevice = true;
        }
        renderTrackingState();
        if (choosingDevice && resumed) ensurePermissionsAndStartDiscovery();
    }

    private void ensurePermissionsAndStartDiscovery() {
        if (!hasScanPermission()) {
            requestPermissions(new String[]{requiredScanPermission()}, REQUEST_PERMISSIONS);
            statusView.setText("Bluetooth scan permission is needed to find devices.");
            return;
        }
        if (needsBackgroundPermission() && !hasBackgroundPermission() && !askedForBackgroundThisRun) {
            askedForBackgroundThisRun = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQUEST_BACKGROUND_PERMISSION);
            statusView.setText("Allow background location for reliable scanning while the app is closed.");
            return;
        }
        startDiscovery();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || askedForNotificationsThisRun
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        askedForNotificationsThisRun = true;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATION_PERMISSION);
    }

    private void ensurePermissionsAndStartTracking() {
        if (!hasScanPermission()) {
            requestPermissions(new String[]{requiredScanPermission()}, REQUEST_PERMISSIONS);
            statusView.setText("Bluetooth scan permission is needed to continue tracking.");
            return;
        }
        if (needsBackgroundPermission() && !hasBackgroundPermission() && !askedForBackgroundThisRun) {
            askedForBackgroundThisRun = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQUEST_BACKGROUND_PERMISSION);
            statusView.setText("Allow background location to keep tracking while the app is closed.");
            return;
        }
        startTrackedScan();
    }

    private String requiredScanPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_SCAN
                : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private boolean hasScanPermission() {
        return checkSelfPermission(requiredScanPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsBackgroundPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R;
    }

    private boolean hasBackgroundPermission() {
        return !needsBackgroundPermission()
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasScanPermission()) continueCurrentMode();
            else statusView.setText("Permission denied. Allow Bluetooth scanning in app settings to continue.");
        } else if (requestCode == REQUEST_BACKGROUND_PERMISSION) {
            if (hasBackgroundPermission()) {
                continueCurrentMode();
            } else {
                statusView.setText("Background permission denied. Scanning may pause when the app is closed.");
                continueCurrentMode();
            }
        } else if (requestCode == REQUEST_NOTIFICATION_PERMISSION
                && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow notifications to receive in-range and out-of-range alerts.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void continueCurrentMode() {
        if (!resumed) return;
        if (preferences.getBoolean(BleScanReceiver.KEY_ACTIVE, false)) startTrackedScan();
        else if (choosingDevice || preferences.getString(BleScanReceiver.KEY_ADDRESS, "").isEmpty()) {
            startDiscovery();
        }
    }

    private ScanSettings lowPowerSettings() {
        return new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(0)
                .build();
    }

    private ScanSettings trackingSettings() {
        return new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(BleScanReceiver.scanReportDelayMillis(this))
                .build();
    }

    private BluetoothLeScanner getScanner() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        return adapter == null ? null : adapter.getBluetoothLeScanner();
    }

    private void startDiscovery() {
        if (!resumed || !hasScanPermission()) return;
        requestNotificationPermissionIfNeeded();
        stopDiscovery();
        scanner = getScanner();
        if (scanner == null) {
            statusView.setText("Turn on Bluetooth to find nearby devices.");
            return;
        }
        nearbyDevices.clear();
        nearbyRows.clear();
        nearbyAdapter.notifyDataSetChanged();
        discoveryCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                addNearbyDevice(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) addNearbyDevice(result);
            }

            @Override
            public void onScanFailed(int errorCode) {
                statusView.setText("Bluetooth scan failed (" + errorCode + "). Check Bluetooth and try again.");
            }
        };
        try {
            scanner.startScan(null, lowPowerSettings(), discoveryCallback);
            statusView.setText("Scanning nearby BLE devices in low-power mode. Select the device you want to track.");
        } catch (SecurityException exception) {
            statusView.setText("Bluetooth scan permission is required.");
        }
    }

    private void addNearbyDevice(ScanResult result) {
        String address = result.getDevice().getAddress();
        String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
        if (name == null || name.trim().isEmpty()) name = "Unnamed BLE device";
        NearbyDevice device = new NearbyDevice(address, name, result.getRssi(),
                BleScanReceiver.wallClockTimestamp(result));
        nearbyDevices.put(address, device);
        nearbyRows.clear();
        for (NearbyDevice nearby : nearbyDevices.values()) {
            nearbyRows.add(nearby.name + "\n" + nearby.address + "  ·  " + nearby.rssi + " dBm");
        }
        nearbyAdapter.notifyDataSetChanged();
    }

    private void selectDevice(NearbyDevice device) {
        stopDiscovery();
        preferences.edit()
                .putString(BleScanReceiver.KEY_ADDRESS, device.address)
                .putString(BleScanReceiver.KEY_NAME, device.name)
                .putBoolean(BleScanReceiver.KEY_ACTIVE, true)
                .apply();
        SessionEvents events = database.recordSeen(device.address, device.name, device.timestamp,
                BleScanReceiver.sessionGapMillis(this));
        SessionNotifications.handleEvents(this, device.address, events);
        choosingDevice = false;
        showPage(true);
        renderTrackingState();
        startTrackedScan();
    }

    private void startTrackedScan() {
        String address = preferences.getString(BleScanReceiver.KEY_ADDRESS, "");
        if (!resumed || address.isEmpty() || !preferences.getBoolean(BleScanReceiver.KEY_ACTIVE, false)
                || !hasScanPermission()) return;

        scanner = getScanner();
        if (scanner == null) {
            statusView.setText("Turn on Bluetooth to track the selected device.");
            return;
        }
        PendingIntent pendingIntent = trackingPendingIntent();
        try {
            requestNotificationPermissionIfNeeded();
            scanner.stopScan(pendingIntent);
            ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(address).build();
            scanner.startScan(java.util.Collections.singletonList(filter), trackingSettings(), pendingIntent);
            statusView.setText("Low-power tracking active. Android may delay background scan results.");
            TrackingSession latest = database.latestForAddress(address);
            if (latest != null && !latest.manual) {
                SessionNotifications.scheduleEnd(this, address, latest);
            }
            renderTrackingState();
        } catch (SecurityException exception) {
            statusView.setText("Bluetooth scan permission is required to track this device.");
        } catch (IllegalArgumentException exception) {
            statusView.setText("This device address cannot be used for filtered tracking.");
        }
    }

    private PendingIntent trackingPendingIntent() {
        return BleScanReceiver.trackingPendingIntent(this);
    }

    private void stopTrackedScan() {
        if (!hasScanPermission()) return;
        BluetoothLeScanner currentScanner = getScanner();
        if (currentScanner == null) return;
        try {
            currentScanner.stopScan(trackingPendingIntent());
        } catch (SecurityException ignored) {
            // Permission may have been revoked while the app was open.
        }
    }

    private void stopDiscovery() {
        if (scanner != null && discoveryCallback != null && hasScanPermission()) {
            try {
                scanner.stopScan(discoveryCallback);
            } catch (SecurityException ignored) {
                // Permission may have been revoked while scanning.
            }
        }
        discoveryCallback = null;
    }

    private void beginChoosingDevice() {
        stopTrackedScan();
        SessionNotifications.cancelEnd(this);
        preferences.edit().putBoolean(BleScanReceiver.KEY_ACTIVE, false).apply();
        choosingDevice = true;
        showPage(false);
        ensurePermissionsAndStartDiscovery();
        renderTrackingState();
    }

    private void stopTrackingByUser() {
        stopTrackedScan();
        SessionNotifications.cancelEnd(this);
        preferences.edit().putBoolean(BleScanReceiver.KEY_ACTIVE, false).apply();
        choosingDevice = false;
        statusView.setText("Tracking stopped. Your device selection and log are saved.");
        renderTrackingState();
    }

    private void resumeTracking() {
        preferences.edit().putBoolean(BleScanReceiver.KEY_ACTIVE, true).apply();
        choosingDevice = false;
        stopDiscovery();
        statusView.setText("Resuming tracking for your saved device.");
        ensurePermissionsAndStartTracking();
        renderTrackingState();
    }

    private void applyScanInterval() {
        String input = scanIntervalInput.getText().toString().trim();
        int seconds;
        try {
            seconds = Integer.parseInt(input);
        } catch (NumberFormatException exception) {
            Toast.makeText(this, "Enter a whole number from 1 to 300.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (seconds < 1 || seconds > BleScanReceiver.MAX_SCAN_INTERVAL_SECONDS) {
            Toast.makeText(this, "Choose an interval from 1 to 300 seconds.", Toast.LENGTH_SHORT).show();
            return;
        }

        preferences.edit().putInt(BleScanReceiver.KEY_SCAN_INTERVAL_SECONDS, seconds).apply();
        int effectiveGapSeconds = (int) (BleScanReceiver.sessionGapMillis(this) / 1_000L);
        sessionGapInput.setText(Integer.toString(effectiveGapSeconds));
        sessionGapInput.setHint(BleScanReceiver.minimumSessionGapSeconds(this) + "–7,200 seconds");
        if (preferences.getBoolean(BleScanReceiver.KEY_ACTIVE, false)) {
            startTrackedScan();
            scheduleLatestSessionEnd();
        }
        statusView.setText("Scan result interval set to " + seconds + " seconds.");
    }

    private void applySessionGap() {
        String input = sessionGapInput.getText().toString().trim();
        int seconds;
        try {
            seconds = Integer.parseInt(input);
        } catch (NumberFormatException exception) {
            Toast.makeText(this, "Enter a whole number from 1 to 7,200.", Toast.LENGTH_SHORT).show();
            return;
        }
        int minimumSeconds = BleScanReceiver.minimumSessionGapSeconds(this);
        if (seconds < minimumSeconds || seconds > BleScanReceiver.MAX_SESSION_GAP_SECONDS) {
            Toast.makeText(this, "Choose a timeout from " + minimumSeconds + " to 7,200 seconds.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        preferences.edit().putInt(BleScanReceiver.KEY_SESSION_GAP_SECONDS, seconds).apply();
        scheduleLatestSessionEnd();
        renderTrackingState();
        statusView.setText("No-signal timeout set to " + seconds + " seconds.");
    }

    private void scheduleLatestSessionEnd() {
        String address = preferences.getString(BleScanReceiver.KEY_ADDRESS, "");
        if (address.isEmpty() || !preferences.getBoolean(BleScanReceiver.KEY_ACTIVE, false)) {
            SessionNotifications.cancelEnd(this);
            return;
        }
        TrackingSession latest = database.latestForAddress(address);
        if (latest == null || latest.manual) SessionNotifications.cancelEnd(this);
        else SessionNotifications.scheduleEnd(this, address, latest);
    }

    private void renderTrackingState() {
        String address = preferences.getString(BleScanReceiver.KEY_ADDRESS, "");
        boolean active = preferences.getBoolean(BleScanReceiver.KEY_ACTIVE, false);
        chooseButton.setText(address.isEmpty() ? "Find a device" : "Find / change device");
        stopButton.setVisibility(!address.isEmpty() ? View.VISIBLE : View.GONE);
        stopButton.setText(active ? "Stop tracking" : "Start tracking");

        if (address.isEmpty()) {
            trackingView.setText("No device selected yet.");
            rangeStatusView.setVisibility(View.GONE);
            calendarSessions.clear();
            renderCalendar();
            showNearbyDevices();
            return;
        }
        if (!active) statusView.setText("Tracking stopped. Your device selection is saved.");
        String name = preferences.getString(BleScanReceiver.KEY_NAME, "Bluetooth device");
        trackingView.setText((choosingDevice ? "Previously tracked: " : "Tracking: ") + name
                + "\n" + address + (active ? "\nTracking active" : "\nTracking stopped"));

        if (choosingDevice) {
            rangeStatusView.setVisibility(View.GONE);
            showNearbyDevices();
            return;
        }

        listHeading.setText("Tracking time by day");
        showNearbyDevices();
        showCalendar();
        updateRangeStatus();
    }

    private void refreshTrackingSummary() {
        renderTrackingState();
    }

    private void showNearbyDevices() {
        if (choosingDevice) {
            settingsHeading.setText("Nearby BLE devices — tap one to track");
            nearbyList.setVisibility(View.VISIBLE);
        } else {
            settingsHeading.setText("Device settings");
            nearbyList.setVisibility(View.GONE);
        }
    }

    private void showCalendar() {
        calendarSessions.clear();
        calendarSessions.addAll(database.sessionsFor(
                preferences.getString(BleScanReceiver.KEY_ADDRESS, "")));
        renderCalendar();
    }

    private void buildWeekdayHeader() {
        DayOfWeek firstDay = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
        for (int i = 0; i < 7; i++) {
            DayOfWeek day = firstDay.plus(i);
            TextView label = new TextView(this);
            label.setText(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()));
            label.setTextSize(12);
            label.setTextColor(0xFF607D8B);
            label.setGravity(Gravity.CENTER);
            weekdayHeader.addView(label, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private void changeMonth(int months) {
        displayedMonth = displayedMonth.plusMonths(months);
        renderCalendar();
    }

    private void showMonthPicker() {
        new DatePickerDialog(this, (picker, year, month, day) -> {
            displayedMonth = YearMonth.of(year, month + 1);
            renderCalendar();
        }, displayedMonth.getYear(), displayedMonth.getMonthValue() - 1, 1).show();
    }

    private void renderCalendar() {
        monthTitle.setText(displayedMonth.format(monthFormatter));
        calendarAdapter.rebuildDays();
        calendarAdapter.notifyDataSetChanged();
        updateMonthlySummary();
    }

    private long durationInDisplayedMonth() {
        return ShiftCalculator.durationInMonth(displayedMonth, calendarSessions, ZoneId.systemDefault());
    }

    private void updateMonthlySummary() {
        if (monthlySummaryView == null) return;
        long duration = durationInDisplayedMonth();
        String rateText = preferences.getString(KEY_HOURLY_PAY, "").trim();
        if (rateText.isEmpty()) {
            monthlySummaryView.setText("Monthly total: " + formatHours(duration)
                    + " · Enter hourly pay in Settings");
            return;
        }

        try {
            double rate = Double.parseDouble(rateText.replace(',', '.'));
            if (!Double.isFinite(rate) || rate < 0) throw new NumberFormatException();
            monthlySummaryView.setText("Monthly pay: " + formatHours(duration) + " × "
                    + formatNumber(rate) + "/h = " + formatNumber(rate * duration / 3_600_000.0));
        } catch (NumberFormatException exception) {
            monthlySummaryView.setText("Enter a valid hourly rate in Settings");
        }
    }

    private String formatNumber(double number) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(number);
    }

    private void copyDisplayedMonth() {
        List<TrackingSession> sessions = ShiftCalculator.sessionsStartingInMonth(
                displayedMonth, calendarSessions, ZoneId.systemDefault());
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No shifts in this month to copy.", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder export = new StringBuilder();
        DateTimeFormatter exportDateFormat = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault());
        ZoneId zone = ZoneId.systemDefault();
        for (TrackingSession session : sessions) {
            if (export.length() > 0) export.append('\n');
            String date = java.time.Instant.ofEpochMilli(session.startedAt)
                    .atZone(zone).toLocalDate().format(exportDateFormat);
            export.append(date).append(" : ")
                    .append(formatTime(session.startedAt)).append(" - ")
                    .append(formatTime(session.lastSeen));
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Bluetooth tracking month", export.toString()));
            Toast.makeText(this, "Month copied to clipboard.", Toast.LENGTH_SHORT).show();
        }
    }

    private long durationOnDay(LocalDate day) {
        return ShiftCalculator.durationOnDay(day, calendarSessions, ZoneId.systemDefault());
    }

    private void showDayDetails(LocalDate date) {
        StringBuilder details = new StringBuilder();
        for (TrackingSession session : ShiftCalculator.sessionsStartingOn(
                date, calendarSessions, ZoneId.systemDefault())) {
            long duration = Math.max(0, session.lastSeen - session.startedAt);
            if (details.length() > 0) details.append("\n\n");
            details.append(formatShift(session.startedAt, session.lastSeen, duration));
        }
        if (details.length() == 0) details.append("No tracking recorded for this day.");
        String title = date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault()))
                + " · " + formatHours(durationOnDay(date));
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(details.toString())
                .setPositiveButton("Close", null)
                .show();
    }

    private void showDayShiftManager(LocalDate date) {
        String address = preferences.getString(BleScanReceiver.KEY_ADDRESS, "");
        if (address.isEmpty()) {
            Toast.makeText(this, "Select a device in Settings first.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<TrackingSession> shifts = ShiftCalculator.sessionsStartingOn(
                date, calendarSessions, ZoneId.systemDefault());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(4));
        android.app.AlertDialog[] managerReference = new android.app.AlertDialog[1];
        if (shifts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No shifts recorded for this day.");
            empty.setPadding(0, dp(8), 0, dp(12));
            content.addView(empty);
        } else {
            ArrayList<String> rows = new ArrayList<>();
            for (TrackingSession shift : shifts) {
                rows.add(formatShift(shift.startedAt, shift.lastSeen,
                        Math.max(0, shift.lastSeen - shift.startedAt)));
            }
            ListView shiftsList = new ListView(this);
            shiftsList.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows));
            shiftsList.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.min(dp(300), dp(76 * shifts.size()))));
            content.addView(shiftsList);
            shiftsList.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < shifts.size()) {
                    if (managerReference[0] != null) managerReference[0].dismiss();
                    showShiftEditor(date, shifts.get(position));
                }
            });
        }

        Button addShiftButton = new Button(this);
        addShiftButton.setText("Add shift");
        content.addView(addShiftButton);

        android.app.AlertDialog manager = new android.app.AlertDialog.Builder(this)
                .setTitle(date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())))
                .setView(content)
                .setNegativeButton("Close", null)
                .create();
        managerReference[0] = manager;
        addShiftButton.setOnClickListener(view -> {
            manager.dismiss();
            showShiftEditor(date, null);
        });
        manager.show();
    }

    private void showShiftEditor(LocalDate calendarDay, TrackingSession session) {
        String address = preferences.getString(BleScanReceiver.KEY_ADDRESS, "");
        String name = preferences.getString(BleScanReceiver.KEY_NAME, "Bluetooth device");
        ZoneId zone = ZoneId.systemDefault();
        LocalTime[] startTime = {session == null ? null
                : java.time.Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalTime()};
        LocalTime[] endTime = {session == null ? null
                : java.time.Instant.ofEpochMilli(session.lastSeen).atZone(zone).toLocalTime()};
        long[] start = {0};
        long[] end = {0};
        updateShiftTimestamps(calendarDay, startTime[0], endTime[0], start, end);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(4));

        Button startButton = new Button(this);
        startButton.setText(startTime[0] == null ? "Choose start time"
                : "Start: " + formatTime(startTime[0]));
        content.addView(startButton);
        startButton.setOnClickListener(view -> showTimePicker(startTime[0], LocalTime.of(9, 0),
                "Shift start time", time -> {
                    startTime[0] = time;
                    updateShiftTimestamps(calendarDay, startTime[0], endTime[0], start, end);
                    startButton.setText("Start: " + formatTime(time));
                }));

        Button endButton = new Button(this);
        endButton.setText(endTime[0] == null ? "Choose end time"
                : "End: " + formatTime(endTime[0]));
        content.addView(endButton);
        endButton.setOnClickListener(view -> showTimePicker(endTime[0], LocalTime.of(17, 0),
                "Shift end time", time -> {
                    endTime[0] = time;
                    updateShiftTimestamps(calendarDay, startTime[0], endTime[0], start, end);
                    endButton.setText("End: " + formatTime(time));
                }));

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this)
                .setTitle(session == null ? "Add shift" : "Edit shift")
                .setView(content)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (session != null) builder.setNeutralButton("Remove", null);
        android.app.AlertDialog editor = builder.create();
        editor.setOnShowListener(ignored -> {
            editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (start[0] == 0 || end[0] == 0) {
                    Toast.makeText(this, "Choose both start and end times.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (end[0] <= start[0]) {
                    Toast.makeText(this, "The end must be after the start.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (session == null) database.addManualSession(address, name, start[0], end[0]);
                else database.updateManualSession(session.id, address, name, start[0], end[0]);
                editor.dismiss();
                refreshTrackingSummary();
            });
            if (session != null) {
                editor.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("Remove shift?")
                                .setMessage("This shift will be deleted from the calendar.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Remove", (dialog, which) -> {
                                    database.deleteSession(session.id, address);
                                    editor.dismiss();
                                    refreshTrackingSummary();
                                }).show());
            }
        });
        editor.show();
    }

    private void updateShiftTimestamps(LocalDate day, LocalTime startTime, LocalTime endTime,
                                       long[] start, long[] end) {
        ZoneId zone = ZoneId.systemDefault();
        start[0] = startTime == null ? 0
                : ShiftCalculator.timestampAt(day, startTime, zone);
        if (endTime == null) {
            end[0] = 0;
            return;
        }
        end[0] = startTime == null ? ShiftCalculator.timestampAt(day, endTime, zone)
                : ShiftCalculator.endTimestamp(day, startTime, endTime, zone);
    }

    private void showTimePicker(LocalTime currentTime, LocalTime defaultTime,
                                String title, TimeListener listener) {
        LocalTime initial = currentTime == null ? defaultTime : currentTime;
        TimePickerDialog picker = new TimePickerDialog(this,
                (timePicker, hour, minute) -> listener.onSelected(LocalTime.of(hour, minute)),
                initial.getHour(), initial.getMinute(), DateFormat.is24HourFormat(this));
        picker.setTitle(title);
        picker.show();
    }

    private String formatTime(LocalTime time) {
        return DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(time);
    }

    private interface TimeListener {
        void onSelected(LocalTime time);
    }

    private String formatTime(long timestamp) {
        return DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
                .withZone(ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(timestamp));
    }

    private String formatShift(long start, long end, long duration) {
        return formatTime(start) + " - " + formatTime(end) + " : "
                + String.format(Locale.getDefault(), "%.1f h", duration / 3_600_000.0);
    }

    private String formatHours(long durationMillis) {
        return String.format(Locale.getDefault(), "%.2f h", durationMillis / 3_600_000.0);
    }

    private final class CalendarAdapter extends BaseAdapter {
        private final ArrayList<LocalDate> days = new ArrayList<>();

        void rebuildDays() {
            days.clear();
            LocalDate first = displayedMonth.atDay(1);
            DayOfWeek firstDay = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
            int offset = (first.getDayOfWeek().getValue() - firstDay.getValue() + 7) % 7;
            for (int cell = 0; cell < 42; cell++) {
                int dayOfMonth = cell - offset + 1;
                days.add(dayOfMonth >= 1 && dayOfMonth <= displayedMonth.lengthOfMonth()
                        ? displayedMonth.atDay(dayOfMonth) : null);
            }
        }

        @Override
        public int getCount() {
            return days.size();
        }

        @Override
        public LocalDate getItem(int position) {
            return days.get(position);
        }

        @Override
        public long getItemId(int position) {
            LocalDate day = getItem(position);
            return day == null ? position : day.toEpochDay();
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            TextView cell = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
            LocalDate day = getItem(position);
            cell.setGravity(Gravity.CENTER);
            cell.setTextSize(12);
            cell.setTextColor(0xFF17202A);
            cell.setPadding(dp(2), dp(3), dp(2), dp(3));
            cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                    android.widget.AbsListView.LayoutParams.MATCH_PARENT, dp(58)));
            if (day == null) {
                cell.setText("");
                cell.setEnabled(false);
                cell.setBackgroundColor(0x00000000);
            } else {
                long duration = durationOnDay(day);
                cell.setText(duration > 0
                        ? day.getDayOfMonth() + "\n" + formatHours(duration)
                        : Integer.toString(day.getDayOfMonth()));
                cell.setEnabled(true);
                boolean isToday = day.equals(LocalDate.now());
                cell.setTextColor(isToday ? 0xFF1565C0 : 0xFF17202A);
                cell.setTypeface(null, isToday ? Typeface.BOLD : Typeface.NORMAL);
                cell.setBackgroundResource(android.R.drawable.list_selector_background);
            }
            return cell;
        }
    }

    private void updateRangeStatus() {
        if (rangeStatusView == null) return;
        String address = preferences.getString(BleScanReceiver.KEY_ADDRESS, "");
        boolean active = preferences.getBoolean(BleScanReceiver.KEY_ACTIVE, false);
        if (address.isEmpty() || choosingDevice) {
            rangeStatusView.setVisibility(View.GONE);
            return;
        }
        rangeStatusView.setVisibility(View.VISIBLE);
        if (!active) {
            rangeStatusView.setText("Tracking is stopped");
            rangeStatusView.setTextColor(0xFF607D8B);
            return;
        }

        List<TrackingSession> sessions = database.sessionsFor(address);
        if (sessions.isEmpty()) {
            rangeStatusView.setText("Waiting for the first signal · tracking");
            rangeStatusView.setTextColor(0xFF607D8B);
            return;
        }
        long age = System.currentTimeMillis() - sessions.get(0).lastSeen;
        if (age >= 0 && age <= BleScanReceiver.sessionGapMillis(this)) {
            rangeStatusView.setText("● In range · tracking");
            rangeStatusView.setTextColor(0xFF2E7D32);
        } else {
            rangeStatusView.setText("Not currently in range · tracking continues");
            rangeStatusView.setTextColor(0xFF607D8B);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class NearbyDevice {
        final String address;
        final String name;
        final int rssi;
        final long timestamp;

        NearbyDevice(String address, String name, int rssi, long timestamp) {
            this.address = address;
            this.name = name;
            this.rssi = rssi;
            this.timestamp = timestamp;
        }
    }
}
