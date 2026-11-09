# Bluetooth Tracker

A small, dependency-free Android BLE tracker. Open this folder in Android Studio and run the `app` configuration.

Run the shift calculation tests with `./gradlew testDebugUnitTest`.

## What it does

- Scans for nearby BLE advertisements in Android low-power mode.
- Lets you select one detected device and filters subsequent scans to that device's Bluetooth address.
- Separates the calendar and device controls into **Calendar** and **Settings** pages, accessible from the side menu.
- Stores an hourly shift-pay rate in Settings and shows the selected month's tracked hours × rate = total on the calendar. Amounts use the units entered; no currency is assumed.
- Settings lets you choose the tracked-device result reporting interval (1–300 seconds); shorter intervals update sooner but use more battery.
- Settings lets you change the no-signal timeout (default 120 seconds, max 7,200). Its minimum is 30 seconds or twice the scan reporting interval, whichever is greater, to avoid false leave/return alerts from batching. Notifications fire once per visit start/end, not for each scan result.
- Saves visit sessions locally in SQLite and shows daily tracked hours in a month calendar.
- Month arrows navigate month-by-month across years; tapping the month title opens a date picker to jump to a month and year, and **Today** returns to the current month.
- **Copy month** puts one `date : start - end` row per shift on the clipboard, ordered by start time.
- Tapping a day shows all sessions that started on that date. The calendar total combines their full durations; sessions spanning midnight belong to their start date.
- Long-pressing a day lets you add a shift, edit its start/end times, or remove it. The selected day supplies the date; an end time earlier than the start is treated as overnight.
- Starts a new visit after the configured no-signal timeout; the end time is the last time the device was heard.
- Continues filtered scans using Android's `PendingIntent` scan API when the app is in the background, subject to Android's scan and permission limits.
- Remembers the selected device when tracking is stopped or the app is reopened; use **Start tracking** to resume without selecting it again.
- Resumes an active scan after device restart, after the user unlocks the phone.

The app does not connect to devices or upload data. Address-based tracking may stop matching devices that rotate their private Bluetooth address. Some BLE advertisements can also be filtered by Android because this app declares that it does not use scans to derive location.

On Android 10 and 11, Android may require background-location permission for background BLE scanning. On newer versions, allow the Nearby devices permission. Android can still delay or throttle background scans. If permission was revoked or Bluetooth is off during startup, reopen the app after enabling them to resume tracking.
