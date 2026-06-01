package com.antirevoke.wx;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 配置存储 - 和 WeKit 一样的方案
 * 使用微信的外部存储目录:
 *   /sdcard/Android/data/com.tencent.mm/files/WxEnhance/config.json
 * 模块App通过 createPackageContext 写入
 * 微信进程通过 getExternalFilesDir 直接读写
 */
public class ConfigStore {

    private static final String DIR_NAME = "WxEnhance";
    private static final String FILE_NAME = "config.json";
    private static final String WX_PKG = "com.tencent.mm";
    private static JSONObject config = new JSONObject();
    private static File configFile;

    public static void load(Context context) {
        try {
            String pkg = context.getPackageName();
            if (WX_PKG.equals(pkg)) {
                // 微信进程：直接用 getExternalFilesDir
                File extDir = context.getExternalFilesDir(null);
                if (extDir != null) {
                    File dir = new File(extDir, DIR_NAME);
                    if (!dir.exists()) dir.mkdirs();
                    configFile = new File(dir, FILE_NAME);
                }
            } else {
                // 模块App：createPackageContext 读写同一目录
                Context wxCtx = context.createPackageContext(WX_PKG,
                    Context.CONTEXT_IGNORE_SECURITY);
                File extDir = wxCtx.getExternalFilesDir(null);
                if (extDir != null) {
                    File dir = new File(extDir, DIR_NAME);
                    if (!dir.exists()) dir.mkdirs();
                    configFile = new File(dir, FILE_NAME);
                }
            }
        } catch (Throwable e) {
            // fallback: 硬编码路径（兼容分身 uid=999）
            try {
                int uid = android.os.Process.myUid();
                int userId = uid / 100000;
                configFile = new File("/storage/emulated/" + userId
                    + "/Android/data/" + WX_PKG + "/files/" + DIR_NAME + "/" + FILE_NAME);
            } catch (Throwable e2) {
                configFile = new File("/storage/emulated/0/Android/data/"
                    + WX_PKG + "/files/" + DIR_NAME + "/" + FILE_NAME);
            }
        }
        readFromFile();
    }

    public static String configPath() {
        return configFile != null ? configFile.getAbsolutePath() : "null";
    }

    private static void readFromFile() {
        try {
            if (configFile == null || !configFile.exists()) {
                config = new JSONObject();
                return;
            }
            byte[] buf = new byte[(int) configFile.length()];
            FileInputStream fis = new FileInputStream(configFile);
            fis.read(buf);
            fis.close();
            config = new JSONObject(new String(buf));
        } catch (Throwable e) {
            config = new JSONObject();
        }
    }

    public static void save() {
        try {
            if (configFile == null) return;
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(configFile);
            fos.write(config.toString(2).getBytes());
            fos.close();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static boolean getBoolean(String key, boolean defValue) {
        try { if (config.has(key)) return config.getBoolean(key); } catch (Throwable ignored) {}
        return defValue;
    }

    public static void putBoolean(String key, boolean value) {
        try { config.put(key, value); save(); } catch (Throwable ignored) {}
    }

    public static int getInt(String key, int defValue) {
        try { if (config.has(key)) return config.getInt(key); } catch (Throwable ignored) {}
        return defValue;
    }

    public static void putInt(String key, int value) {
        try { config.put(key, value); save(); } catch (Throwable ignored) {}
    }

    public static String getString(String key, String defValue) {
        try { if (config.has(key)) return config.getString(key); } catch (Throwable ignored) {}
        return defValue;
    }

    public static void putString(String key, String value) {
        try { config.put(key, value); save(); } catch (Throwable ignored) {}
    }
}