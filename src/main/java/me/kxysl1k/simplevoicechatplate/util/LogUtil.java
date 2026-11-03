package me.kxysl1k.simplevoicechatplate.util;

import org.bukkit.Bukkit;

import java.io.File;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LogUtil {

    private static volatile Logger logger = Bukkit.getLogger();
    private static volatile boolean debugMode = false;
    private static volatile CrashLogger crashLogger;

    private LogUtil() {
    }

    public static void setLogger(Logger pluginLogger) {
        logger = pluginLogger;
    }

    public static void setCrashLogger(CrashLogger cl) {
        crashLogger = cl;
    }

    public static void setDebugMode(boolean debug) {
        debugMode = debug;
    }

    public static void info(String message) {
        Logger l = logger;
        if (l != null) l.info(message);
        CrashLogger cl = crashLogger;
        if (cl != null) cl.log("INFO", message, null);
    }

    public static void warn(String message) {
        Logger l = logger;
        if (l != null) l.warning(message);
        CrashLogger cl = crashLogger;
        if (cl != null) cl.log("WARN", message, null);
    }

    public static void error(String message) {
        Logger l = logger;
        if (l != null) l.severe(message);
        CrashLogger cl = crashLogger;
        if (cl != null) cl.log("ERROR", message, null);
    }

    public static void error(String message, Throwable t) {
        Logger l = logger;
        if (l != null) l.log(Level.SEVERE, message, t);
        CrashLogger cl = crashLogger;
        if (cl != null) cl.log("ERROR", message, t);
    }

    public static void debug(String message) {
        if (debugMode) {
            Logger l = logger;
            if (l != null) l.info("[DEBUG] " + message);
            CrashLogger cl = crashLogger;
            if (cl != null) cl.log("DEBUG", message, null);
        }
    }

    public static File crash(String description, Throwable t, Map<String, Object> context) {
        CrashLogger cl = crashLogger;
        return cl != null ? cl.saveCrashReport(description, t, context) : null;
    }

    public static File logProcessFailure(String processName, int exitCode, String command, String output, Map<String, Object> context) {
        CrashLogger cl = crashLogger;
        return cl != null ? cl.logProcessFailure(processName, exitCode, command, output, context) : null;
    }
}
