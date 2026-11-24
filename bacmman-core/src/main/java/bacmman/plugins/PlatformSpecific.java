package bacmman.plugins;

import bacmman.utils.Utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/**
 * Restricts a plugin to some operating systems: on other systems it is not registered by {@link PluginFactory}, so it is
 * not proposed to the user.
 * For development, set the system property {@value #IGNORE_PROPERTY} to true to register such plugins on any system.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PlatformSpecific {
    String IGNORE_PROPERTY = "bacmman.plugins.ignorePlatform";

    OS[] value();

    enum OS {
        WINDOWS, LINUX, MAC;

        public static OS current() {
            if (Utils.isWindows()) return WINDOWS;
            if (Utils.isMac()) return MAC;
            return LINUX;
        }
    }

    final class Check {
        private Check() {}
        /**
         * @return true if the class has no {@link PlatformSpecific} annotation or if it lists the current operating system
         */
        public static boolean isAvailable(Class<?> pluginClass) {
            PlatformSpecific ps = pluginClass.getAnnotation(PlatformSpecific.class);
            if (ps == null || Boolean.getBoolean(IGNORE_PROPERTY)) return true;
            return Arrays.asList(ps.value()).contains(OS.current());
        }
    }
}
