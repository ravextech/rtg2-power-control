package io.github.ravextech.rtg2powercontrol;

import java.lang.reflect.Method;

/** Uses the firmware's optional shared library; no vendor code is bundled. */
final class VendorEnvironment implements BootModeController.Environment {
    private Object manager;
    private Method get;
    private Method set;

    private void connect() throws Exception {
        if (manager != null) return;
        Class<?> cls = Class.forName("com.droidlogic.app.SystemControlManager");
        manager = cls.getMethod("getInstance").invoke(null);
        get = cls.getMethod("getBootenv", String.class, String.class);
        set = cls.getMethod("setBootenv", String.class, String.class);
    }

    @Override public String read(String key) throws Exception {
        connect();
        return (String) get.invoke(manager, key, "__ABSENT__");
    }

    @Override public void write(String key, String value) throws Exception {
        if (!BootModeController.KEY.equals(key)) throw new IllegalArgumentException("Unexpected key");
        connect();
        set.invoke(manager, key, value);
    }
}
