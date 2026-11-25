# TRUE NAIN Rev 3 - source patch and validation status

## Code fix completed in this revision
- Extract All Frames uses its own decoder (retained from Rev 2).
- Failed decoder frame now triggers one attempt to reopen the source and retry the same frame. If it still fails, export reports the error; it does NOT silently drop frames.
- Export progress maximum uses the reopened source's actual frame count rather than a stale UI value.
- Rev 2 fix retained: changing control visibility doesn't recreate the video player.

## Known limitations, not claimed as fixed
- 0.05x custom slow-motion playback engine is NOT implemented; hardware MediaPlayer support varies.
- VideoView renderer zoom/pan is NOT tested across Android devices.
- Library lists user-picked videos, NOT an automatic scan of every device video.
- Control order/rearrangement is NOT implemented; only select Show/Hide controls.
- CFR/B-frame/VFR exact frame-count and frame timestamps NOT empirically verified.
- APK has NOT been compiled or tested in this environment; Android Studio build required.

The user requested all issues to be fixed. This revision is NOT equivalent to that goal. Do not label it final.
