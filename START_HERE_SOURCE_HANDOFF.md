# TRUE NAIN - Full Android Studio Source Handoff (2026-10-21)

This archive is a FULL SOURCE PROJECT, not an APK. Unzip and open the `TRUE_NAIN_FINAL_LOCKED` directory in Android Studio.

## Contents
- Android Gradle Plugin 8.7.3 / Gradle wrapper 8.9 / Java source level 17
- com.truenain.app (versionName 1.4.1, versionCode 141)
- Main Menu, MediaStore video-library scan plus manually added videos, Media Player and Frame Mode, Back navigation
- Advanced frame decoder, 24 MiB cache implementation, Trim/Merge tools
- Zoom/pan clipping/gesture handling, controls visibility and optional left/right ordering
- Decoded-frame slow-playback path, including requested 0.05x setting, with silent audio in decoded slow mode
- Extract All Frames workflow (PNG output, independent decoder, retry, progress/partial completion reporting)

## IMPORTANT verification limits
- ZIP CRC and project structural checks were performed on this archive before handoff.
- Java compile, assembleDebug, assembleRelease and installation of THIS source revision have not been run here (no Android SDK / Gradle distribution installed).
- There is NO basis to claim guaranteed 0.05x smoothness, visual fidelity on every video, truly exact all-frame parity for B-frame/VFR media, or perfect zoom bounds across devices without device tests.
- Control customization supports show/hide and specific button reordering; it is NOT arbitrary drag/drop placement of every UI widget.
- MediaStore library is permission-scoped; Android 14+ partial grants can restrict visible results.
- Rev3 was superseded by Fix01, Fix02 and Fix03-06; this handoff is built from the latter and includes their code.

## Windows Build (source project folder)
  .\gradlew.bat :app:assembleDebug --stacktrace --console=plain
  .\gradlew.bat :app:assembleRelease --stacktrace --console=plain

If no `keystore.properties` exists, the release is UNSIGNED. To update installed TRUE NAIN, use the same original release keystore and ensure a higher versionCode when upgrading from an already-installed build with code 141. Do not put keystore files or passwords in any ZIP shared with others.

See `FOUR_ISSUES_AUDIT_2026-10-21.md` and other audit notes for outstanding testing.
