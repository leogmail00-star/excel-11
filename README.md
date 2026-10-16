# Excel Auto Dialer (Android / Kotlin)

A small Android Studio starter project that imports the **first worksheet** of an `.xlsx` file and calls its phone numbers sequentially.

## What it does

- Opens an `.xlsx` workbook using Android's document picker.
- Reads shared-string and inline-string cells without a third-party spreadsheet dependency.
- Uses the first row as headers.
- Finds a header containing `phone`, `mobile`, `telephone`, `tel`, or `number`; if none exists, it uses column A.
- Optionally reads a `name`, `contact`, or `customer` column.
- Calls numbers in sequence using Android's `ACTION_CALL` intent.
- Watches telephony state and advances after a call transitions to idle.
- Has a Stop button.

## Important limitations

This is a **starter project**, not a guarantee of unattended bulk calling on every Android device.

1. **Permissions:** it requests `CALL_PHONE` to place calls and `READ_PHONE_STATE` to observe call-state changes. The user must grant these at runtime.
2. **Android/OEM restrictions:** Android versions, manufacturers, carrier apps, dual-SIM settings, and background/activity-launch restrictions can change whether a call can be placed and whether state changes are reported as expected.
3. **Call completion:** this sample advances after observing a call state transition. If the device does not report the expected transition, it may wait or advance imperfectly. Test with a few consenting numbers before using a longer list.
4. **Not a default dialer:** it does not implement the full Android default-dialer role. Reliable system-level call handling may require a properly implemented default dialer using `InCallService`, which is a larger project.
5. **Target SDK:** `targetSdk = 28` is chosen for this sample's older call-state behavior and is not a recommendation for a public Play Store release. Modern Play distribution requirements and policy review may require a newer target SDK and a different design.
6. **XLSX support:** first worksheet only; ordinary `.xlsx` files with shared strings and inline strings are supported. Password-protected files, legacy `.xls`, formulas that lack cached values, unusual workbook relationships, and malformed spreadsheets may not work.
7. **Phone number formatting:** the app strips spaces and punctuation, preserves a leading `+`, and otherwise leaves the digits as supplied. For mixed international numbers, use full international format such as `+14155550123`. Numbers without a country code may dial incorrectly.
8. **No call-log or contact upload:** the app does not send spreadsheet data to a server and does not request contacts or call-log permissions.
9. **Responsible use:** call only people you are authorized to contact, comply with local telemarketing/privacy rules, respect opt-outs, and avoid rapid or repeated unwanted calls.

## Build an installable APK

### Requirements
- Android Studio (current stable release)
- Android SDK Platform 35
- JDK 17 (Android Studio's bundled JDK is usually suitable)

### Build
1. Unzip this project.
2. In Android Studio, choose **Open** and select the `ExcelAutoDialer` folder.
3. Allow Gradle sync to finish. If prompted, install Android SDK Platform 35.
4. Connect an Android phone with USB debugging enabled, or use an emulator with telephony support (real SIM-based calling requires a real phone).
5. To create an APK, choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
6. The debug APK is normally at `app/build/outputs/apk/debug/app-debug.apk`. Android Studio will show a notification with a link to locate it.
7. On the phone, install the APK and grant the requested permissions. Android may require you to allow installation from the source app.

### Command-line build
If you have a compatible Android SDK and Gradle installed:
```bash
gradle assembleDebug
```
The output should be:
`app/build/outputs/apk/debug/app-debug.apk`

## Example spreadsheet

| Name | Phone |
|---|---|
| Test contact | +14155550123 |
| Another contact | +442071838750 |

Keep phone-number cells formatted as **Text** in Excel so leading zeros and `+` signs are preserved.

## Before real use

Test with two or three numbers whose owners have agreed to receive a call. Confirm that call-state detection behaves correctly on your particular phone and carrier. Keep the phone unlocked and the app in the foreground while testing. Stop the queue immediately if it behaves unexpectedly.


## Build an APK automatically in GitHub Actions (no local Android Studio required)

This project includes `.github/workflows/android-apk.yml`.

1. Create a repository on GitHub and upload the contents of this folder (including the hidden `.github` folder).
2. Open the repository's **Actions** tab.
3. Select **Build Android APK** and choose **Run workflow**, or push to the `main`/`master` branch.
4. When the workflow finishes successfully, open that workflow run and download the artifact named `ExcelAutoDialer-debug-apk`.
5. Extract the artifact ZIP to get `app-debug.apk`, then transfer it to your Android phone and install it. You may need to allow installation from the app used to open the APK.

The GitHub workflow builds a **debug APK** for testing. It is not a Play Store release and is not signed with a private release key.
