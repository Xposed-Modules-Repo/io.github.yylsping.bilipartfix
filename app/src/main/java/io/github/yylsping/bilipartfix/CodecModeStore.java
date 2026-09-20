package io.github.yylsping.bilipartfix;

import android.content.Context;
import android.content.SharedPreferences;

/** Host-process persistence for the decoder mode exposed in Bilibili settings. */
final class CodecModeStore {
    static final String PREFERENCES_NAME = "bili_part_fix";
    static final String KEY_DECODER_MODE = "decoder_mode";

    enum Mode {
        AUTO("auto"),
        V3_HW("v3_hw"),
        SOFTWARE("software");

        final String storedValue;

        Mode(String storedValue) {
            this.storedValue = storedValue;
        }

        static Mode fromStoredValue(String value) {
            for (Mode mode : values()) {
                if (mode.storedValue.equals(value)) return mode;
            }
            return AUTO;
        }
    }

    private final SharedPreferences preferences;

    CodecModeStore(Context context) {
        Context applicationContext = context.getApplicationContext();
        Context storageContext = applicationContext != null ? applicationContext : context;
        preferences = storageContext.getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    Mode getMode() {
        try {
            return Mode.fromStoredValue(preferences.getString(
                    KEY_DECODER_MODE, Mode.AUTO.storedValue));
        } catch (Throwable throwable) {
            XposedBridge.log("unable to read decoder mode; falling back to auto", throwable);
            return Mode.AUTO;
        }
    }

    boolean setMode(Mode mode) {
        if (mode == null) mode = Mode.AUTO;
        try {
            return preferences.edit().putString(KEY_DECODER_MODE, mode.storedValue).commit();
        } catch (Throwable throwable) {
            XposedBridge.log("unable to persist decoder mode", throwable);
            return false;
        }
    }
}
