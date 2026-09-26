package io.github.yylsping.bilipartfix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Adapt current watch-later CIDs and UGC playlist routes for the legacy page. */
final class WatchLaterFix {
    private static final long MAX_BODY_BYTES = 2L * 1024 * 1024;
    private static final String UGC_PLAYLIST_PREFIX = "bilibili://music/playlist/playpage/";

    private WatchLaterFix() {}

    static boolean install(ClassLoader classLoader) {
        try {
            Class<?> response = XposedHelpers.findClass("okhttp3.Response", classLoader);
            Class<?> responseBody = XposedHelpers.findClass("okhttp3.ResponseBody", classLoader);
            // BiliCall.n parses network responses; BiliCall.m parses the disk
            // cache. Both signatures are identical in 7040300 and 7420400
            // (verified in both DEXes), and n falls back to getFromCache()/m()
            // on network errors, so hooking both covers every parse path.
            for (String method : new String[]{"n", "m"}) {
                installParseHook(classLoader, response, responseBody, method);
            }
            XposedBridge.log("watch-later CID compatibility hook installed");
            return true;
        } catch (Throwable throwable) {
            XposedBridge.log("watch-later hook installation failed", throwable);
            return false;
        }
    }

    private static void installParseHook(ClassLoader classLoader, Class<?> response,
            Class<?> responseBody, String method) throws Exception {
        XposedHelpers.findAndHookMethod("com.bilibili.okretro.call.BiliCall",
                classLoader, method, response, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object original = param.args[0];
                    Object request = XposedHelpers.callMethod(original, "request");
                    Object url = XposedHelpers.callMethod(request, "url");
                    if (!"api.bilibili.com".equals(XposedHelpers.callMethod(url, "host"))
                            || !"/x/v2/history/toview".equals(
                                    XposedHelpers.callMethod(url, "encodedPath"))
                            || !"GET".equals(XposedHelpers.callMethod(request, "method"))
                            || (Integer) XposedHelpers.callMethod(original, "code") != 200) {
                        return;
                    }
                    // Peek leaves the original stream available on every failure path.
                    Object peek = XposedHelpers.callMethod(original, "peekBody",
                            MAX_BODY_BYTES + 1);
                    String raw;
                    try {
                        if ((Long) XposedHelpers.callMethod(peek, "contentLength")
                                > MAX_BODY_BYTES) return;
                        raw = (String) XposedHelpers.callMethod(peek, "string");
                    } finally {
                        XposedHelpers.callMethod(peek, "close");
                    }
                    String repaired = repairResponse(raw);
                    if (repaired == null) return;
                    Object body = XposedHelpers.callMethod(original, "body");
                    Object mediaType = XposedHelpers.callMethod(body, "contentType");
                    Object replacement = XposedHelpers.callStaticMethod(responseBody,
                            "create", mediaType, repaired);
                    Object builder = XposedHelpers.callMethod(original, "newBuilder");
                    XposedHelpers.callMethod(builder, "body", replacement);
                    XposedHelpers.callMethod(builder, "removeHeader", "Content-Length");
                    Object updated = XposedHelpers.callMethod(builder, "build");
                    param.args[0] = updated;
                    XposedHelpers.callMethod(body, "close");
                    XposedBridge.log("watch-later response repaired via BiliCall."
                            + method + " (" + ("n".equals(method) ? "network" : "cache")
                            + " parse path)");
                } catch (Throwable throwable) {
                    XposedBridge.log("watch-later response compatibility failed: "
                            + throwable.getClass().getSimpleName());
                }
            }
        });
    }

    static String repairResponse(String raw) throws Exception {
        JSONObject envelope = new JSONObject(raw);
        if (envelope.optInt("code", -1) != 0) return null;
        JSONObject data = envelope.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("list");
        if (list == null) return null;
        boolean changed = false;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String uri = item.optString("uri", "");
            long aid = item.optLong("aid", 0);
            if (aid > 0 && isUgcPlaylistRoute(uri)) {
                // The legacy click handler routes items through item.uri into
                // VideoRouter, and the music-playlist destination cannot load
                // this UGC list. card_type is never read by the click handler
                // or adapter on either 7040300 or 7420400 (7040300's
                // WatchLaterItem model has no card_type field at all), so it
                // must not gate this rewrite.
                item.put("uri", "bilibili://video/" + aid);
                changed = true;
            }
            JSONObject page = item.optJSONObject("page");
            if (page == null) continue;
            long cid = page.optLong("cid", 0);
            if (cid <= Integer.MAX_VALUE) continue;
            // The nested Page.cid is an int in both supported models and is
            // not consumed by their watch-later click handlers; both 7040300
            // and 7420400 have been verified (DEX + on-device) to use the
            // top-level long WatchLaterItem.cid for playback. Preserve that
            // identity exactly; never truncate it or drop the real list item.
            long itemCid = item.optLong("cid", 0);
            if (itemCid == 0) item.put("cid", cid);
            page.remove("cid");
            changed = true;
        }
        return changed ? envelope.toString() : null;
    }

    /**
     * True when the URI is a UGC playlist route: the music playpage scheme
     * with a page_type=2 query parameter. Query order, extra parameters,
     * fragments and percent-encoding must not affect the decision.
     */
    static boolean isUgcPlaylistRoute(String uri) {
        if (uri == null || !uri.startsWith(UGC_PLAYLIST_PREFIX)) return false;
        int queryStart = uri.indexOf('?');
        if (queryStart < 0) return false;
        int end = uri.indexOf('#', queryStart);
        String query = end < 0 ? uri.substring(queryStart + 1)
                : uri.substring(queryStart + 1, end);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!"page_type".equals(urlDecode(key))) continue;
            return "2".equals(urlDecode(eq < 0 ? "" : pair.substring(eq + 1)));
        }
        return false;
    }

    private static String urlDecode(String value) {
        if (value.indexOf('%') < 0 && value.indexOf('+') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '+') {
                flushBytes(bytes, out);
                out.append(' ');
            } else if (c == '%' && i + 2 < value.length()
                    && Character.digit(value.charAt(i + 1), 16) >= 0
                    && Character.digit(value.charAt(i + 2), 16) >= 0) {
                bytes.write((Character.digit(value.charAt(i + 1), 16) << 4)
                        + Character.digit(value.charAt(i + 2), 16));
                i += 2;
            } else {
                flushBytes(bytes, out);
                out.append(c);
            }
        }
        flushBytes(bytes, out);
        return out.toString();
    }

    private static void flushBytes(ByteArrayOutputStream bytes, StringBuilder out) {
        if (bytes.size() == 0) return;
        out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        bytes.reset();
    }
}
