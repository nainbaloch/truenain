# Verification

## Source/config checks for this updated archive
- Package: `com.truenain.app`; minSdk 29; targetSdk 35.
- Manifest declares no permissions; `allowBackup=false`; no Internet permission/service/provider/receiver additions.
- Release config keeps `debuggable=false`, R8 minification and resource shrinking enabled.
- Frame path uses MediaExtractor + MediaCodec (`ExactFrameDecoder`) and no longer uses MediaMetadataRetriever for frame stepping.
- Trim safe path uses stream-copy only at exact safe video sync boundaries; unsafe GOP boundaries route through `ExactTrimTranscoder` instead of shifting the requested range start.
- Source ZIP cleanup excludes `.gradle`, `.idea`, build outputs, local.properties, real keystore.properties, JKS/keystore files, APK/AAB files and machine-specific state.

## Build status
- The supplied base v1.4.0 archive recorded a prior `assembleRelease` PASS before these final exact-decoder/transcoder changes.
- A fresh `assembleRelease` was attempted for this updated source, but this environment could not resolve/download `https://services.gradle.org/distributions/gradle-9.6.0-bin.zip` (network/DNS unavailable). Therefore a new build PASS is **not** claimed.
- No on-device regression suite was run here. Vendor-specific codec behaviour remains device-test dependent.
