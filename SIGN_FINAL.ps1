# Re-signs the release APK with the NEW key + v3 lineage (mandatory before distribution).
# Usage: powershell -ExecutionPolicy Bypass -File SIGN_FINAL.ps1
$ErrorActionPreference = 'Stop'

$newProps = 'C:\Users\BGL Test T1\Desktop\ZCode\Apk\KEYS_BACKUP\true_nain_v2_keystore.properties'
$new = @{}
foreach ($line in [System.IO.File]::ReadAllLines($newProps)) {
    if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $new[$matches[1].Trim()] = $matches[2].Trim() }
}
$newJks = $new['storeFile']
$newAlias = $new['keyAlias']
$env:NEW_STORE = $new['storePassword']
$env:NEW_KEY   = $new['keyPassword']

$lineage = 'C:\Users\BGL Test T1\Desktop\ZCode\Apk\KEYS_BACKUP\true_nain_v2.lineage'
$bt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0"
$src = 'C:\Users\BGL Test T1\Desktop\ZCode\Apk\True Nain APK\TRUE_NAIN_V2.0_ENGINE\app\build\outputs\apk\release\app-release-unsigned.apk'
$dst = 'C:\Users\BGL Test T1\Desktop\ZCode\Apk\True Nain APK\TRUE_NAIN_V2.0_ENGINE\app\build\outputs\apk\release\TRUE_NAIN_v2.4.4_FINAL.apk'

& "$bt\apksigner.bat" sign `
    --ks "$newJks" --ks-key-alias "$newAlias" --ks-pass env:NEW_STORE --key-pass env:NEW_KEY `
    --lineage "$lineage" `
    --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true `
    --rotation-min-sdk-version 29 `
    --out "$dst" "$src"

if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed with exit code $LASTEXITCODE" }
Remove-Item Env:NEW_STORE, Env:NEW_KEY -ErrorAction SilentlyContinue

Write-Host "ROTATED APK SIGNED: $dst"
& "$bt\apksigner.bat" verify --verbose --print-certs "$dst"
