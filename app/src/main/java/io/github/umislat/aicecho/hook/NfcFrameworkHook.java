package io.github.umislat.aicecho.hook;

import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

public final class NfcFrameworkHook extends XposedModule {
    private static final String TAG = "AICEcho";
    private static final String NFC_PACKAGE = "com.android.nfc";
    private static final AtomicBoolean VALIDATION_HOOKS_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean NATIVE_LOADED = new AtomicBoolean();

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded in " + param.getProcessName()
                + " with " + getFrameworkName() + " API " + getApiVersion());
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!NFC_PACKAGE.equals(param.getPackageName())) return;
        installValidationHooks(param.getDefaultClassLoader());
        if (Build.VERSION.SDK_INT <= 34 && propertyEnabled()) {
            loadLegacyPatch();
        }
    }

    private void installValidationHooks(ClassLoader loader) {
        if (!VALIDATION_HOOKS_INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> manager = Class.forName(
                    "android.nfc.cardemulation.NfcFCardEmulation", false, loader);
            int installed = 0;
            installed += hookBooleanValidators(manager, "isValidNfcid2", 16, false);
            installed += hookBooleanValidators(manager, "isValidSystemCode", 4, true);
            if (installed == 0) {
                VALIDATION_HOOKS_INSTALLED.set(false);
                log(Log.WARN, TAG, "No NFC-F validation methods were found");
            } else {
                log(Log.INFO, TAG, "Installed " + installed + " NFC-F validation hooks");
            }
        } catch (Throwable error) {
            VALIDATION_HOOKS_INSTALLED.set(false);
            log(Log.ERROR, TAG, "Failed to install NFC-F validation hooks", error);
        }
    }

    private int hookBooleanValidators(Class<?> owner, String methodName,
                                      int hexLength, boolean rejectReservedCode) {
        int installed = 0;
        for (Method method : owner.getDeclaredMethods()) {
            if (!methodName.equals(method.getName()) || method.getReturnType() != boolean.class) {
                continue;
            }
            hook(method).intercept(chain -> {
                String value = firstString(chain.getArgs());
                if (isPermittedHex(value, hexLength, rejectReservedCode)) return true;
                return chain.proceed();
            });
            installed++;
        }
        return installed;
    }

    private static boolean isPermittedHex(String value, int hexLength,
                                          boolean rejectReservedCode) {
        if (value == null) return false;
        String normalized = value.toUpperCase(Locale.ROOT);
        boolean valid = normalized.length() == hexLength && normalized.matches("[0-9A-F]+");
        if (rejectReservedCode) {
            valid = valid && !"0000".equals(normalized) && !"FFFF".equals(normalized);
        }
        return valid;
    }

    private static String firstString(List<Object> arguments) {
        for (Object argument : arguments) {
            if (argument instanceof String) return (String) argument;
        }
        return null;
    }

    @SuppressLint("PrivateApi")
    private boolean propertyEnabled() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method getter = properties.getDeclaredMethod("getBoolean", String.class, boolean.class);
            return (Boolean) getter.invoke(null, "tmp.aicecho.pmm.enabled", false);
        } catch (ReflectiveOperationException error) {
            log(Log.WARN, TAG, "Cannot query the legacy PMm property", error);
            return false;
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private void loadLegacyPatch() {
        if (!NATIVE_LOADED.compareAndSet(false, true)) return;
        try {
            ApplicationInfo moduleInfo = getModuleApplicationInfo();
            File nativeLibrary = new File(moduleInfo.nativeLibraryDir, "libaicecho_pmm.so");
            if (!nativeLibrary.isFile()) {
                throw new IllegalStateException("Native library is not extracted: " + nativeLibrary);
            }
            System.load(nativeLibrary.getAbsolutePath());
            log(Log.INFO, TAG, "Legacy PMm patch loaded");
        } catch (Throwable error) {
            NATIVE_LOADED.set(false);
            log(Log.ERROR, TAG, "Legacy PMm patch failed", error);
        }
    }
}
