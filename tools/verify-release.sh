#!/usr/bin/env bash
# BiliPartFix release verification (Git Bash / Linux).
#
# Guards against shipping a stale dist APK whose dex does not match the
# current source tree (the 1.8.0 "stale dist" incident), and against stale
# release-notes wording.
#
# Usage:
#   tools/verify-release.sh <release.apk> <release-notes.md>
#
# Exit code 0 = all checks passed; 1 = at least one check failed.

set -u

APK="${1:-}"
NOTES="${2:-}"
FAILED=0

say()  { printf '%s\n' "$*"; }
pass() { say "PASS: $*"; }
fail() { say "FAIL: $*"; FAILED=1; }

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    say "usage: $0 <release.apk> <release-notes.md>"
    exit 1
fi

# --- locate aapt from the Android SDK -------------------------------------
AAPT=""
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
    SDK=$(sed -n 's/^sdk\.dir=//p' local.properties | tr -d '\r')
    # Java properties escaping: 'D\:\\AndroidSdk' -> 'D:/AndroidSdk'
    SDK=${SDK//\\:/:}
    SDK=${SDK//\\\\//}
    SDK=${SDK//\\//}
fi
if [ -n "$SDK" ]; then
    for d in "$SDK"/build-tools/*/; do
        if [ -f "${d}aapt.exe" ]; then AAPT="${d}aapt.exe"; fi
        if [ -f "${d}aapt" ]; then AAPT="${d}aapt"; fi
    done
fi
if [ -z "$AAPT" ]; then
    say "FAIL: aapt not found (set ANDROID_HOME or sdk.dir in local.properties)"
    exit 1
fi
say "aapt: $AAPT"
say "apk:  $APK"

# --- 1. manifest metadata ---------------------------------------------------
BADGING=$("$AAPT" dump badging "$APK" 2>/dev/null)
PKG=$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
VCODE=$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='[^']*' versionCode='\([^']*\)'.*/\1/p")
VNAME=$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='[^']*' versionCode='[^']*' versionName='\([^']*\)'.*/\1/p")
MINSDK=$(printf '%s\n' "$BADGING" | sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p")
TGTSDK=$(printf '%s\n' "$BADGING" | sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p")

[ "$PKG" = "io.github.yylsping.bilipartfix" ] \
    && pass "package=$PKG" || fail "package='$PKG'"
[ "$VCODE" = "12" ] && pass "versionCode=$VCODE" || fail "versionCode='$VCODE' (want 12)"
[ "$VNAME" = "1.8.0" ] && pass "versionName=$VNAME" || fail "versionName='$VNAME' (want 1.8.0)"
[ "$MINSDK" = "27" ] && pass "minSdk=$MINSDK" || fail "minSdk='$MINSDK' (want 27)"
[ -n "$TGTSDK" ] && pass "targetSdk=$TGTSDK" || fail "targetSdk missing"

# debuggable must be absent or false
MANIFEST_TREE=$("$AAPT" dump xmltree "$APK" AndroidManifest.xml 2>/dev/null)
if printf '%s\n' "$MANIFEST_TREE" | grep -i 'debuggable' | grep -qv '0x0'; then
    if printf '%s\n' "$MANIFEST_TREE" | grep -qi 'debuggable.*0xffffffff'; then
        fail "manifest debuggable=true"
    else
        pass "debuggable not true"
    fi
else
    pass "debuggable absent/false"
fi

# --- 2. signature ------------------------------------------------------------
if command -v apksigner >/dev/null 2>&1; then
    APKSIGNER=apksigner
else
    APKSIGNER=""
    for d in "$SDK"/build-tools/*/; do
        [ -f "${d}apksigner.bat" ] && APKSIGNER="${d}apksigner.bat"
        [ -f "${d}apksigner" ] && APKSIGNER="${d}apksigner"
    done
fi
if [ -n "$APKSIGNER" ]; then
    if "$APKSIGNER" verify --print-certs "$APK" >/dev/null 2>&1; then
        pass "APK signature verifies"
    else
        fail "APK signature verification failed"
    fi
else
    say "SKIP: apksigner not found"
fi

# --- 3. SHA-256 ---------------------------------------------------------------
SHA=$(sha256sum "$APK" | cut -d' ' -f1 | tr 'A-F' 'a-f')
say "SHA-256: $SHA"
STALE_SHA="374ac0fc21be1b2393ac2a8885d544c0d5472a9ebad3db1dfa368d51765678cd"
[ "$SHA" != "$STALE_SHA" ] && pass "not the known-stale 1.8.0 candidate APK" \
    || fail "APK is the known-stale 1.8.0 candidate ($STALE_SHA)"

# --- 4. dex content ------------------------------------------------------------
DEX=$(unzip -p "$APK" 'classes*.dex' 2>/dev/null)
if [ -z "$DEX" ]; then
    fail "no classes*.dex found in APK"
else
    dex_has() { printf '%s' "$DEX" | grep -aqF "$1"; }
    for needle in \
        "io/github/yylsping/bilipartfix/DecoderProfile" \
        "ProtectionState" \
        "MediaItemParams" \
        "decoder_mode" \
        "Smart Auto" \
        "7040300" \
        "7420400" \
        "watch-later response repaired via BiliCall." \
        "page_type"; do
        dex_has "$needle" && pass "dex contains '$needle'" \
            || fail "dex missing '$needle'"
    done
    # Retired 1.8.0-candidate production logic must be gone. The old regex and
    # the card_type gate lived in production code of the stale build.
    if dex_has '.*[?&]page_type=2(?:&.*)?'; then
        fail "dex still contains the retired watch-later page_type regex"
    else
        pass "retired page_type regex absent from dex"
    fi
    if dex_has "card_type"; then
        fail "dex still contains card_type (old watch-later gate?)"
    else
        pass "card_type absent from dex"
    fi
fi

# --- 5. release notes ----------------------------------------------------------
if [ -n "$NOTES" ] && [ -f "$NOTES" ]; then
    if grep -qE '[0-9a-f]{64}' "$NOTES"; then
        NOTES_SHA=$(grep -oE '[0-9a-f]{64}' "$NOTES" | head -1)
        [ "$NOTES_SHA" = "$SHA" ] && pass "release notes SHA matches APK" \
            || fail "release notes SHA $NOTES_SHA != APK SHA $SHA"
    else
        fail "release notes contain no SHA-256"
    fi
    if grep -q "$STALE_SHA" "$NOTES"; then
        fail "release notes still carry the stale SHA"
    fi
    if grep -q "仅安装上述两个修复" "$NOTES"; then
        fail "release notes still claim 7420400 only installs two fixes"
    else
        pass "no stale '7420400 only two fixes' wording"
    fi
    if grep -n "解码模式" "$NOTES" | grep -q "仅"; then
        fail "release notes still claim decoder mode is 7.4.0-only"
    else
        pass "no stale 'decoder mode 7.4.0-only' wording"
    fi
    for needle in "7.42.0" "7420400" "7040300" "Smart Auto"; do
        grep -q "$needle" "$NOTES" && pass "notes mention '$needle'" \
            || fail "notes missing '$needle'"
    done
else
    fail "release notes file not found: $NOTES"
fi

# --- 6. build provenance ---------------------------------------------------------
if git rev-parse HEAD >/dev/null 2>&1; then
    say "build HEAD: $(git rev-parse HEAD)"
    if [ -n "$(git status --porcelain)" ]; then
        say "WARN: working tree not clean at verification time"
    fi
fi

say ""
if [ "$FAILED" -eq 0 ]; then
    say "RESULT: ALL CHECKS PASSED"
else
    say "RESULT: FAILURES PRESENT"
fi
exit $FAILED
