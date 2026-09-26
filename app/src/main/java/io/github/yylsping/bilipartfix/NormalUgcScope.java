package io.github.yylsping.bilipartfix;

/**
 * Marks MediaResource instances proven to originate in NormalVideoPlayHandler.
 * The version-specific callback classes and resolve task type come from the
 * DecoderProfile; the preload path is identical on both supported hosts.
 */
final class NormalUgcScope {
    private static final String ABS_RESOLVE_TASK =
            "tv.danmaku.biliplayerv2.service.resolve.AbsMediaResourceResolveTask";
    private static final String NORMAL_PRELOAD =
            "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler$playPreloadRes$1";
    private static final String PRELOAD_RESULT =
            "tv.danmaku.biliplayer.preload.repository.b";

    private final WeakIdentitySet<Object> resources = new WeakIdentitySet<>();

    void install(ClassLoader classLoader, DecoderProfile profile) {
        Class<?> taskClass = XposedHelpers.findClass(profile.scopeTaskClass, classLoader);
        Class<?> absTaskClass = XposedHelpers.findClass(ABS_RESOLVE_TASK, classLoader);
        XC_MethodHook resolveHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object task = param.args[0];
                if (task == null || !absTaskClass.isInstance(task)) return;
                try {
                    mark(XposedHelpers.callMethod(task, "n"));
                } catch (Throwable throwable) {
                    debug("normal resolve resource unavailable", throwable);
                }
            }
        };
        for (String callbackClass : profile.scopeCallbackClasses) {
            XposedHelpers.findAndHookMethod(callbackClass, classLoader, "c",
                    taskClass, resolveHook);
        }

        Class<?> preloadResultClass = XposedHelpers.findClass(PRELOAD_RESULT, classLoader);
        XposedHelpers.findAndHookMethod(NORMAL_PRELOAD, classLoader, "invokeSuspend",
                Object.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object resumedValue = param.args[0];
                        if (resumedValue == null || !preloadResultClass.isInstance(resumedValue)) {
                            return;
                        }
                        try {
                            mark(XposedHelpers.callMethod(resumedValue, "f"));
                        } catch (Throwable throwable) {
                            debug("normal preload resource unavailable", throwable);
                        }
                    }
                });
    }

    boolean contains(Object mediaResource) {
        return resources.contains(mediaResource);
    }

    private void mark(Object mediaResource) {
        if (resources.add(mediaResource) && BuildConfig.DEBUG) {
            XposedBridge.log("BiliPartFix/SmartAuto: marked normal UGC resource identity="
                    + System.identityHashCode(mediaResource));
        }
    }

    private static void debug(String message, Throwable throwable) {
        if (BuildConfig.DEBUG) {
            XposedBridge.log("BiliPartFix/SmartAuto: " + message, throwable);
        }
    }
}
