package io.github.yylsping.bilipartfix;

import android.os.Looper;
import android.util.Base64;

import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/** Retry only the legacy UGC player's explicit protocol-upgrade rejection. */
final class PlayerUniteFix {
    private static final String OLD = "com.bapis.bilibili.app.playurl.v1.";
    private static final String METHOD = "bilibili.app.playerunite.v1.Player/PlayViewUnite";
    private static final ThreadLocal<Boolean> UNITE_HEADERS = new ThreadLocal<>();
    private static final Map<Object, Boolean> GRPC_CALLS = Collections.synchronizedMap(new WeakHashMap<>());
    private static ClassLoader loader;
    private static Object descriptor;

    private PlayerUniteFix() {}

    static void install(ClassLoader classLoader) {
        loader = classLoader;
        try {
            Class<?> empty = type("com.google.protobuf.Empty");
            Object marshaller = callStatic("io.grpc.protobuf.lite.b", "b",
                    XposedHelpers.callStaticMethod(empty, "getDefaultInstance"));
            Object builder = callStatic("io.grpc.MethodDescriptor", "i");
            call(builder, "b", METHOD);
            call(builder, "f", XposedHelpers.getStaticObjectField(
                    type("io.grpc.MethodDescriptor$MethodType"), "UNARY"));
            call(builder, "c", marshaller);
            call(builder, "d", marshaller);
            descriptor = call(builder, "a");
            installHeaders();
            XposedHelpers.findAndHookMethod(OLD + "PlayURLMoss", loader, "playView",
                    type(OLD + "PlayViewReq"), new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object reply = param.getResult();
                            if (reply == null || Looper.myLooper() == Looper.getMainLooper()) return;
                            try {
                                if ((Boolean) call(reply, "hasVideoInfo")
                                        || (Boolean) call(reply, "hasPlayLimit")
                                        || !(Boolean) call(reply, "hasUpgradeLimit")
                                        || (Integer) call(call(reply, "getUpgradeLimit"), "getCode") != 87002)
                                    return;
                                Object request = param.args[0];
                                if ((Integer) call(request, "getDownload") != 0
                                        || (Integer) call(request, "getTeenagersMode") != 0) return;
                                Object repaired = fallback(param.thisObject, request, reply);
                                if (repaired != null) param.setResult(repaired);
                            } catch (Throwable failure) {
                                // Preserve the original reply on protocol/network/permission failures.
                                XposedBridge.log("player-unite compatibility failed: "
                                        + failure.getClass().getSimpleName());
                            }
                        }
                    });
            PreviewNotice.install();
            XposedBridge.log("player-unite compatibility hook installed");
        } catch (Throwable failure) {
            XposedBridge.log("player-unite hook installation failed", failure);
        }
    }

    private static void installHeaders() {
        XposedHelpers.findAndHookMethod(
                "com.bilibili.lib.moss.internal.impl.okhttp.interceptor.a", loader,
                "intercept", type("okhttp3.Interceptor$Chain"), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object url = call(call(param.args[0], "request"), "url");
                        String host = (String) call(url, "host");
                        if (("app.bilibili.com".equals(host) || "grpc.biliapi.net".equals(host)
                                || "grpc.biliapi.com".equals(host))
                                && ("/" + METHOD).equals(call(url, "encodedPath"))) {
                            param.setObjectExtra("uniteHeaders", true);
                            UNITE_HEADERS.set(true);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getObjectExtra("uniteHeaders") != null) UNITE_HEADERS.remove();
                    }
                });
        XposedHelpers.findAndHookMethod("com.bilibili.lib.moss.internal.impl.grpc.interceptor.a",
                loader, "a", type("io.grpc.MethodDescriptor"), type("io.grpc.c"), type("io.grpc.d"),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args[0] == descriptor && param.getResult() != null)
                            GRPC_CALLS.put(param.getResult(), true);
                    }
                });
        XposedHelpers.findAndHookMethod("com.bilibili.lib.moss.internal.impl.grpc.interceptor.a$a",
                loader, "e", type("io.grpc.e$a"), type("io.grpc.n0"), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (GRPC_CALLS.remove(param.thisObject) != null) {
                            param.setObjectExtra("uniteHeaders", true);
                            UNITE_HEADERS.set(true);
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getObjectExtra("uniteHeaders") != null) UNITE_HEADERS.remove();
                    }
                });
        header("d", "com.bapis.bilibili.metadata.Metadata", false, true);
        header("i", "com.bapis.bilibili.metadata.device.Device", true, true);
        header("n", "com.bapis.bilibili.metadata.Metadata", false, false);
        header("j", "com.bapis.bilibili.metadata.device.Device", true, false);
    }

    private static void header(String method, String message, boolean device, boolean encoded) {
        XposedHelpers.findAndHookMethod(
                "com.bilibili.lib.moss.internal.impl.common.header.HeadersKt", loader,
                method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!Boolean.TRUE.equals(UNITE_HEADERS.get()) || param.getResult() == null) return;
                        byte[] raw = encoded ? Base64.decode((String) param.getResult(), Base64.NO_WRAP)
                                : (byte[]) param.getResult();
                        Object original = XposedHelpers.callStaticMethod(type(message), "parseFrom",
                                raw);
                        Object builder = call(original, "toBuilder");
                        // This adapter implements the 7.70 player-unite protocol. All session,
                        // device identity, restriction and authorization fields stay native.
                        call(builder, "setBuild", 7700100);
                        if (device) call(builder, "setVersionName", "7.70.0");
                        byte[] adapted = (byte[]) call(call(builder, "build"), "toByteArray");
                        param.setResult(encoded ? Base64.encodeToString(adapted, Base64.NO_WRAP) : adapted);
                    }
                });
    }

    private static Object fallback(Object moss, Object request, Object original) {
        long aid = (Long) call(request, "getAid"), cid = (Long) call(request, "getCid");
        if (aid <= 0 || cid <= 0) return null;
        ProtoWire.Writer vod = new ProtoWire.Writer()
                .number(1, aid).number(2, cid).number(3, (Long) call(request, "getQn"))
                .number(4, (Integer) call(request, "getFnver"))
                .number(5, (Integer) call(request, "getFnval"))
                .number(7, (Integer) call(request, "getForceHost"))
                .number(8, (Boolean) call(request, "getFourk") ? 1 : 0)
                .number(9, (Integer) call(request, "getPreferCodecTypeValue"))
                .number(10, (Long) call(request, "getVoiceBalance"))
                .number(11, 1); // Request only the trial the server permits for this account.
        byte[] wire = new ProtoWire.Writer().message(1, vod.bytes())
                .text(2, (String) call(request, "getSpmid"))
                .text(3, (String) call(request, "getFromSpmid")).bytes();
        Object req = callStatic("com.google.protobuf.Empty", "parseFrom", wire);
        Object service = XposedHelpers.getObjectField(moss, "service");
        Object options = call(XposedHelpers.getObjectField(service, "options"), "withTimeout",
                8L, TimeUnit.SECONDS);
        Object boundedService = XposedHelpers.newInstance(type("com.bilibili.lib.moss.api.MossService"),
                XposedHelpers.getObjectField(service, "host"),
                XposedHelpers.getObjectField(service, "port"), options);
        Object result = call(boundedService, "blockingUnaryCall", descriptor, req, null);
        List<ProtoWire.Field> response = ProtoWire.read((byte[]) call(result, "toByteArray"));
        byte[] video = ProtoWire.message(response, 1);
        if (video.length == 0) return null;
        List<ProtoWire.Field> arc = ProtoWire.read(ProtoWire.message(response, 6));
        // Do not translate PGC, interactive/DRM content, or mismatched video identities.
        if (ProtoWire.number(arc, 1) != 1 || ProtoWire.number(arc, 2) != aid
                || ProtoWire.number(arc, 3) != cid || ProtoWire.number(arc, 4) != 0
                || ProtoWire.message(arc, 6).length != 0 || ProtoWire.number(arc, 6) != 0) return null;
        Object info = callStatic(OLD + "VideoInfo", "parseFrom", video);
        Object selected = selectedStream(info);
        if (selected == null) return null;
        boolean preview = ProtoWire.number(arc, 9) != 0;
        if (preview) {
            long allowed = ProtoWire.number(arc, 10);
            // Only use trial media whose own segment durations enforce the gate.
            // A full-length DASH stream would need a new native preview controller.
            if (allowed <= 0 || !(Boolean) call(selected, "hasSegmentVideo")) return null;
            long mediaLength = 0;
            for (Object segment : (List<?>) call(call(selected, "getSegmentVideo"), "getSegmentList")) {
                long length = (Long) call(segment, "getLength");
                if (length <= 0 || length > allowed - mediaLength) return null;
                mediaLength += length;
            }
            if (mediaLength <= 0) return null;
            Object infoBuilder = call(info, "toBuilder");
            // The legacy player has no preview gate. Its seek bar must end at the
            // server's trial boundary, never at the full programme duration.
            call(infoBuilder, "setTimelength", Math.min(allowed, (Long) call(info, "getTimelength")));
            info = call(infoBuilder, "build");
        }
        Object builder = call(original, "toBuilder");
        call(builder, "setVideoInfo", info);
        byte[] arcConf = mapArcConf(ProtoWire.message(response, 2));
        if (arcConf.length > 0) call(builder, "setPlayArc", callStatic(OLD + "PlayArcConf", "parseFrom", arcConf));
        call(builder, "clearUpgradeLimit");
        PreviewNotice.update(aid, preview ? ProtoWire.message(response, 9) : null);
        XposedBridge.log("player-unite compatibility resolved; preview=" + preview);
        return call(builder, "build");
    }

    private static Object selectedStream(Object info) {
        int quality = (Integer) call(info, "getQuality");
        for (Object stream : (List<?>) call(info, "getStreamListList")) {
            Object streamInfo = call(stream, "getStreamInfo");
            if ((Integer) call(streamInfo, "getQuality") != quality
                    || (Integer) call(streamInfo, "getErrCodeValue") != 0) continue;
            if ((Boolean) call(stream, "hasDashVideo")) {
                Object dash = call(stream, "getDashVideo");
                if (!((String) call(dash, "getWidevinePssh")).isEmpty()) return null;
                if (!((String) call(dash, "getBaseUrl")).isEmpty()) return stream;
            }
            if ((Boolean) call(stream, "hasSegmentVideo")) {
                List<?> segments = (List<?>) call(call(stream, "getSegmentVideo"), "getSegmentList");
                if (segments.isEmpty()) continue;
                for (Object segment : segments)
                    if (((String) call(segment, "getUrl")).isEmpty()) return null;
                return stream;
            }
        }
        return null;
    }

    static byte[] mapArcConf(byte[] source) {
        ProtoWire.Writer result = new ProtoWire.Writer();
        for (ProtoWire.Field field : ProtoWire.read(source)) {
            if (field.number != 1 || field.wire != 2) continue;
            List<ProtoWire.Field> entry = ProtoWire.read(field.bytes);
            int modern = (int) ProtoWire.number(entry, 1);
            // 7.70 ConfType IDs differ from legacy PlayArcConf field numbers.
            int legacy = modern >= 1 && modern <= 8 ? modern + 1
                    : modern == 9 ? 1 : modern >= 10 && modern <= 28 ? modern
                    : modern == 29 ? 30 : modern == 30 ? 31 : modern == 34 ? 29 : 0;
            if (legacy != 0) result.message(legacy, ProtoWire.message(entry, 2));
        }
        return result.bytes();
    }

    private static Class<?> type(String name) { return XposedHelpers.findClass(name, loader); }
    private static Object call(Object target, String method, Object... args) {
        return XposedHelpers.callMethod(target, method, args);
    }
    private static Object callStatic(String name, String method, Object... args) {
        return XposedHelpers.callStaticMethod(type(name), method, args);
    }
}
