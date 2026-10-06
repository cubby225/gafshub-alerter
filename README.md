# GAFSHub Alerter (Android)

Get a phone notification when someone posts in the gafshub.com buying section with an item matching your search terms.

## Features
- Signs in to gafshub.com with your username/email and password (stored encrypted on the phone)
- Search terms: `glock 19` (all words, any order), `"red dot"` (exact phrase), `ar -upper` (exclude a word)
- Picks the buying section automatically (any section named buy / WTB / wanted); change it with checkboxes
- **Regular mode**: Android checks every 15, 30 or 60 minutes, even with the app closed
- **Fast mode**: checks every 1–10 minutes using a small ongoing notification
- "Check now" lists matching posts already in the section; tap any match or notification to open the post

## Build
**GitHub (no Android Studio needed):** push this repo to GitHub. The `Build APK` action runs automatically;
open the run under the **Actions** tab and download `GAFSHubAlerter-apk`. Unzip it and install the APK on your phone
(allow "install unknown apps" for your browser or Files app when asked).

**Android Studio:** File → Open this folder, wait for Gradle sync, then Run ▶ with your phone plugged in
(USB debugging on), or Build → Build APK(s).

Requires Android 8.0 or newer.
