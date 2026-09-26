package io.github.yylsping.bilipartfix;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Adds a native Preference entry and a module-owned decoder settings subpage. */
final class SettingsInjection {
    private static final String ROOT_FRAGMENT =
            "com.bilibili.app.preferences.BiliPreferencesActivity$BiliPreferencesFragment";
    private static final String HOST_SUBPAGE_FRAGMENT =
            "com.bilibili.app.preferences.fragment.PlaySettingPrefFragment";
    private static final String PREFERENCE_CLASS = "androidx.preference.Preference";
    private static final String ENTRY_KEY = "bili_part_fix_entry";
    private static final String MODE_KEY = "bili_part_fix_decoder_mode";
    private static final String ARG_MODULE_SUBPAGE =
            "io.github.yylsping.bilipartfix.moduleSettings";
    private static final String ENTRY_TITLE = "bili-part-fix";
    private static final String MODE_TITLE = "解码模式选择";
    private static final CharSequence[] MODE_LABELS = {
            "Smart Auto（推荐）",
            "V3 硬解优先（ijkplayer）",
            "软件解码优先（兼容模式）"
    };
    private static final CodecModeStore.Mode[] MODES = {
            CodecModeStore.Mode.AUTO,
            CodecModeStore.Mode.V3_HW,
            CodecModeStore.Mode.SOFTWARE
    };
    private static final CharSequence AUTO_SUMMARY =
            "针对现代设备优化，自动选择低功耗且稳定的解码方式";
    private static final CharSequence HARDWARE_SUMMARY =
            "优先使用宿主 IJK/MediaCodec 硬解，失败时保留安全回退";
    private static final CharSequence SOFTWARE_SUMMARY =
            "普通视频优先软件解；HDR、DRM 等特殊播放遵循宿主安全策略";

    private SettingsInjection() {}

    static boolean install(Context context, ClassLoader classLoader, HostVersion host) {
        CodecModeStore store = new CodecModeStore(context);
        Class<?> rootClass = XposedHelpers.findClass(ROOT_FRAGMENT, classLoader);
        Class<?> subpageClass = XposedHelpers.findClass(HOST_SUBPAGE_FRAGMENT, classLoader);
        XC_MethodHook rootHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    addRootEntry(param.thisObject, classLoader);
                } catch (Throwable throwable) {
                    XposedBridge.log("unable to inject bili-part-fix settings entry", throwable);
                }
            }
        };
        XC_MethodHook subpageHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    replaceMarkedSubpage(param.thisObject, classLoader, store);
                } catch (Throwable throwable) {
                    XposedBridge.log("unable to build bili-part-fix settings subpage", throwable);
                }
            }
        };
        // Install the subpage hook first and only expose the root entry after it
        // is in place: a visible entry that cannot open its module page is worse
        // than no entry at all. If either hook fails the host PreferenceScreen
        // is left untouched.
        try {
            XposedHelpers.findAndHookMethod(subpageClass, "onCreatePreferences",
                    Bundle.class, String.class, subpageHook);
        } catch (Throwable throwable) {
            XposedBridge.log("settings subpage hook installation failed; "
                    + "root entry not installed", throwable);
            return false;
        }
        XposedHelpers.findAndHookMethod(rootClass, "onCreatePreferences",
                Bundle.class, String.class, rootHook);
        XposedBridge.log(host + " native settings injection installed");
        return true;
    }

    private static void addRootEntry(Object fragment, ClassLoader classLoader) {
        Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (screen == null || XposedHelpers.callMethod(screen, "findPreference", ENTRY_KEY) != null) {
            return;
        }
        Context context = (Context) XposedHelpers.callMethod(fragment, "requireContext");
        Object preference = newPreference(classLoader, context, ENTRY_KEY, ENTRY_TITLE, null);
        XposedHelpers.callMethod(preference, "setOrder", orderBeforeNativePreferences(screen));
        XposedHelpers.callMethod(preference, "setFragment", HOST_SUBPAGE_FRAGMENT);
        Bundle extras = (Bundle) XposedHelpers.callMethod(preference, "getExtras");
        extras.putBoolean(ARG_MODULE_SUBPAGE, true);
        XposedHelpers.callMethod(screen, "addPreference", preference);
    }

    private static void replaceMarkedSubpage(Object fragment, ClassLoader classLoader,
                                             CodecModeStore store) {
        Bundle arguments = (Bundle) XposedHelpers.callMethod(fragment, "getArguments");
        if (arguments == null || !arguments.getBoolean(ARG_MODULE_SUBPAGE, false)) return;

        Context context = (Context) XposedHelpers.callMethod(fragment, "requireContext");
        // Build an unattached module-owned screen, then swap it in atomically. This
        // leaves the host's original preferences untouched if any reflective step fails.
        Object preferenceManager = XposedHelpers.callMethod(fragment, "getPreferenceManager");
        Object screen = XposedHelpers.callMethod(preferenceManager,
                "createPreferenceScreen", context);
        XposedHelpers.callMethod(screen, "setTitle", ENTRY_TITLE);
        Object modePreference = newPreference(classLoader, context, MODE_KEY, MODE_TITLE,
                summaryFor(store.getMode()));
        Object listener = newClickListener(modePreference, () ->
                showModeDialog(context, store, modePreference));
        XposedHelpers.callMethod(modePreference, "setOnPreferenceClickListener", listener);
        XposedHelpers.callMethod(screen, "addPreference", modePreference);
        XposedHelpers.callMethod(fragment, "setPreferenceScreen", screen);
    }

    private static Object newPreference(ClassLoader classLoader, Context context, String key,
                                        CharSequence title, CharSequence summary) {
        Class<?> preferenceClass = XposedHelpers.findClass(PREFERENCE_CLASS, classLoader);
        Object preference = XposedHelpers.newInstance(preferenceClass, context);
        XposedHelpers.callMethod(preference, "setKey", key);
        XposedHelpers.callMethod(preference, "setTitle", title);
        if (summary != null && summary.length() > 0) {
            XposedHelpers.callMethod(preference, "setSummary", summary);
        }
        XposedHelpers.callMethod(preference, "setPersistent", false);
        return preference;
    }

    private static int orderBeforeNativePreferences(Object screen) {
        int minimumOrder = 0;
        try {
            Object countValue = XposedHelpers.callMethod(screen, "getPreferenceCount");
            int count = countValue instanceof Number ? ((Number) countValue).intValue() : 0;
            for (int index = 0; index < count; index++) {
                Object child = XposedHelpers.callMethod(screen, "getPreference", index);
                Object orderValue = XposedHelpers.callMethod(child, "getOrder");
                if (orderValue instanceof Number) {
                    minimumOrder = Math.min(minimumOrder, ((Number) orderValue).intValue());
                }
            }
        } catch (Throwable throwable) {
            XposedBridge.log("unable to inspect native settings order; using top fallback",
                    throwable);
        }
        return minimumOrder == Integer.MIN_VALUE ? Integer.MIN_VALUE : minimumOrder - 1;
    }

    private static Object newClickListener(Object preference, Runnable action) {
        Class<?> listenerClass = findListenerType(preference.getClass());
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("onPreferenceClick".equals(method.getName())) {
                    action.run();
                    return true;
                }
                if ("toString".equals(method.getName())) return "BiliPartFixPreferenceListener";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) {
                    return args != null && args.length == 1 && proxy == args[0];
                }
                return null;
            }
        };
        return Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass}, handler);
    }

    private static Class<?> findListenerType(Class<?> preferenceClass) {
        Class<?> current = preferenceClass;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if ("setOnPreferenceClickListener".equals(method.getName())
                        && method.getParameterTypes().length == 1) {
                    return method.getParameterTypes()[0];
                }
            }
            current = current.getSuperclass();
        }
        throw new IllegalStateException("Preference click listener type not found");
    }

    private static void showModeDialog(Context context, CodecModeStore store,
                                       Object modePreference) {
        int checked = indexOf(store.getMode());
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(MODE_TITLE)
                .setSingleChoiceItems(MODE_LABELS, checked, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener(
                (parent, view, position, id) -> {
                    CodecModeStore.Mode mode = MODES[position];
                    if (store.setMode(mode)) {
                        XposedHelpers.callMethod(modePreference, "setSummary", summaryFor(mode));
                        XposedBridge.log("decoder mode changed to " + mode.storedValue
                                + "; applies to the next media item");
                    }
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private static int indexOf(CodecModeStore.Mode mode) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i] == mode) return i;
        }
        return 0;
    }

    private static CharSequence summaryFor(CodecModeStore.Mode mode) {
        if (mode == CodecModeStore.Mode.V3_HW) return HARDWARE_SUMMARY;
        if (mode == CodecModeStore.Mode.SOFTWARE) return SOFTWARE_SUMMARY;
        return AUTO_SUMMARY;
    }
}
