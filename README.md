# Ruijie Voucher Printer 3.0

Android source project for printing vouchers on paired Bluetooth Classic SPP / ESC-POS thermal printers.

## New in version 3
- Manage User Group profiles (profile name, validity period, data quota, upload speed, download speed).
- Preloaded group names matching the screenshots: 1Month, 1Week, 1Day, 5Hour, 3Hour, 2Hour, 1Hour.
- Add voucher codes by pasting multiple codes (one per line), then assign them to a selected user group.
- Select individual vouchers or select all and print.
- Ticket includes voucher code and its assigned User Group settings.
- Optional CSV import: columns `code,profile,password` (header optional); profile is matched by name.
- 58mm / 80mm paper choice.

## Important Ruijie limitation
This is a companion printing app. It does **not** sign in to Ruijie Cloud or automatically read the private voucher list from the Ruijie Reyee app. The screenshots show the fields and group names, but do not provide a downloadable data file. Copy/paste or CSV import is needed to get the real voucher codes into this app. Enter/check each group's quota and speeds against your own Ruijie settings; unclear screenshot text is deliberately not guessed.

## Bluetooth setup
1. Pair the thermal printer in Android Settings > Bluetooth.
2. Open the app, grant Bluetooth permission, and tap **Refresh printers**.
3. Choose the paired printer.
4. Select 58mm or 80mm paper.
5. Add group profiles and voucher codes, select vouchers, and tap **Print selected**.

Printer compatibility: Bluetooth Classic RFCOMM SPP + ESC/POS. BLE-only or vendor-proprietary printers may not work.

## Build APK without a PC
1. Create a GitHub repository and upload the contents of this folder (not the folder itself).
2. Open the repository's **Actions** tab.
3. Run **Build Android APK** (or push to main/master).
4. Open the completed workflow run and download artifact `RuijieVoucherPrinter-v3-debug-apk`.
5. Extract `app-debug.apk` and install it on Android.

Build locally with JDK 17, Android SDK 35, and Gradle 8.9:
`gradle assembleDebug`
