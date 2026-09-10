package io.github.yylsping.bilipartfix;

import org.json.JSONArray;
import org.json.JSONObject;

/** Adapt current watch-later CIDs and UGC playlist routes for the legacy page. */
final class WatchLaterFix {
    private static final long MAX_BODY_BYTES = 2L * 1024 * 1024;

    private WatchLaterFix() {}

    static void install(ClassLoader classLoader) {
        try {
            Class<?> response = XposedHelpers.findClass("okhttp3.Response", classLoader);
            Class<?> responseBody = XposedHelpers.findClass("okhttp3.ResponseBody", classLoader);
            XC_MethodHook compatibility = new XC_MethodHook() {
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
                    } catch (Throwable throwable) {
                        XposedBridge.log("watch-later response compatibility failed: "
                                + throwable.getClass().getSimpleName());
                    }
                }
            };
            // n parses network responses; m also parses the existing disk cache.
            // Repair both so pre-module cached responses cannot retain the overflow.
            for (String method : new String[]{"n", "m"}) {
                XposedHelpers.findAndHookMethod("com.bilibili.okretro.call.BiliCall",
                        classLoader, method, response, compatibility);
            }
            XposedBridge.log("watch-later CID compatibility hook installed");
        } catch (Throwable throwable) {
            XposedBridge.log("watch-later hook installation failed", throwable);
        }
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
            if (aid > 0 && item.optInt("card_type", 0) == 0
                    && uri.startsWith("bilibili://music/playlist/playpage/")
                    && uri.matches(".*[?&]page_type=2(?:&.*)?")) {
                // The legacy click handler adds the full CID, progress and spmid.
                // Its new music-playlist destination cannot load this UGC list.
                item.put("uri", "bilibili://video/" + aid);
                changed = true;
            }
            JSONObject page = item.optJSONObject("page");
            if (page == null) continue;
            long cid = page.optLong("cid", 0);
            if (cid <= Integer.MAX_VALUE) continue;
            // The nested Page.cid is unused by 7.4.0 (no dex field references).
            // Playback uses WatchLaterItem.cid, which is already a long. Preserve
            // that identity exactly; never truncate it or drop the real list item.
            long itemCid = item.optLong("cid", 0);
            if (itemCid == 0) item.put("cid", cid);
            page.remove("cid");
            changed = true;
        }
        return changed ? envelope.toString() : null;
    }
}
