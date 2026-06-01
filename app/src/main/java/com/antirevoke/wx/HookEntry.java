package com.antirevoke.wx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "WxEnhance";
    private static final String WX_PACKAGE = "com.tencent.mm";

    private RedPacketGrabber sGrabber;
    private boolean sMainSettingsHooked = false;
    private boolean sRedPacketHooksRegistered = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!WX_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": [INIT] loaded process=" + lpparam.processName);

        try {
            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Context ctx = ((android.app.Application) param.thisObject).getApplicationContext();
                            if (!WX_PACKAGE.equals(ctx.getPackageName())) return;
                            XposedBridge.log(TAG + ": [INIT] Application.onCreate");
                            ConfigStore.load(ctx);
                            XposedBridge.log(TAG + ": [INIT] config=" + ConfigStore.configPath());
                            XposedBridge.log(TAG + ": [INIT] hb_auto_enable=" + ConfigStore.getBoolean("hb_auto_enable", false));
                            hookWeChatSettings(lpparam.classLoader, ctx);
                            initRedPacket(lpparam.classLoader, ctx);
                        } catch (Throwable e) {
                            XposedBridge.log(TAG + ": [INIT] error: " + e.getMessage());
                        }
                    }
                });
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": [INIT] Application hook failed: " + e.getMessage());
        }
    }

    // ==================== 注入微信设置页 ====================

    private void hookWeChatSettings(ClassLoader cl, Context ctx) {
        if (sMainSettingsHooked) return;
        try {
            String[] candidates = {
                "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
                "com.tencent.mm.plugin.setting.ui.setting.SettingsUI",
                "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingUI"
            };
            for (String className : candidates) {
                try {
                    Class<?> cls = XposedHelpers.findClass(className, cl);
                    if (cls == null) continue;
                    XposedBridge.hookAllMethods(cls, "onResume", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Activity activity = (Activity) param.thisObject;
                                injectSettingsEntry(activity);
                            } catch (Throwable ignored) {}
                        }
                    });
                    XposedBridge.log(TAG + ": [UI] Settings hook OK: " + className);
                    sMainSettingsHooked = true;
                    return;
                } catch (Throwable ignored) {}
            }
            XposedBridge.log(TAG + ": [UI] No settings class found");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": [UI] hookWeChatSettings error: " + e.getMessage());
        }
    }

    private void injectSettingsEntry(Activity activity) {
        try {
            if (activity.getWindow() == null || activity.getWindow().getDecorView() == null) return;
            View root = activity.getWindow().getDecorView();
            if (root.findViewWithTag("wx_enhance_entry") != null) return;

            android.widget.FrameLayout container = new android.widget.FrameLayout(activity);
            container.setTag("wx_enhance_entry");

            LinearLayout btn = new LinearLayout(activity);
            btn.setOrientation(LinearLayout.HORIZONTAL);
            btn.setPadding(24, 16, 24, 16);
            btn.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#F0FFF0"));
            bg.setCornerRadius(12);
            bg.setStroke(2, Color.parseColor("#07C160"));
            btn.setBackground(bg);

            TextView icon = new TextView(activity);
            icon.setText("\uD83C\uDF81");
            icon.setTextSize(20);
            icon.setPadding(0, 0, 16, 0);
            btn.addView(icon);

            LinearLayout textCol = new LinearLayout(activity);
            textCol.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(activity);
            title.setText("\u81EA\u52A8\u62A2\u7EA2\u5305");
            title.setTextSize(16);
            title.setTextColor(Color.parseColor("#333333"));
            textCol.addView(title);
            TextView sub = new TextView(activity);
            boolean enabled = ConfigStore.getBoolean("hb_auto_enable", false);
            sub.setText(enabled ? "\u2705 \u5DF2\u5F00\u542F" : "\u23F8 \u5DF2\u5173\u95ED");
            sub.setTextSize(12);
            sub.setTextColor(enabled ? Color.parseColor("#07C160") : Color.parseColor("#999999"));
            textCol.addView(sub);
            btn.addView(textCol);
            btn.setOnClickListener(v -> showSettingsDialog(activity));

            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM;
            lp.setMargins(32, 0, 32, 32);
            container.addView(btn, lp);

            android.view.ViewGroup parent = (android.view.ViewGroup) root;
            parent.addView(container);
        } catch (Throwable ignored) {}
    }

    private void showSettingsDialog(Activity activity) {
        try {
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(40, 30, 40, 30);

            TextView header = new TextView(activity);
            header.setText("\u81EA\u52A8\u62A2\u7EA2\u5305\u8BBE\u7F6E");
            header.setTextSize(18);
            header.setTextColor(Color.parseColor("#07C160"));
            header.setGravity(Gravity.CENTER);
            header.setPadding(0, 0, 0, 24);
            layout.addView(header);

            Switch swEnable = new Switch(activity);
            swEnable.setText("\u5F00\u542F\u81EA\u52A8\u62A2\u7EA2\u5305");
            swEnable.setTextSize(15);
            swEnable.setChecked(ConfigStore.getBoolean("hb_auto_enable", false));
            swEnable.setPadding(0, 8, 0, 8);
            layout.addView(swEnable);

            Switch swSkipSelf = new Switch(activity);
            swSkipSelf.setText("\u8DF3\u8FC7\u81EA\u5DF1\u53D1\u7684\u7EA2\u5305");
            swSkipSelf.setTextSize(15);
            swSkipSelf.setChecked(ConfigStore.getBoolean("hb_skip_self", false));
            swSkipSelf.setPadding(0, 8, 0, 8);
            layout.addView(swSkipSelf);

            Switch swNotify = new Switch(activity);
            swNotify.setText("\u62A2\u5230\u540E\u5F39\u51FA\u63D0\u793A");
            swNotify.setTextSize(15);
            swNotify.setChecked(ConfigStore.getBoolean("hb_notify_toast_enable", true));
            swNotify.setPadding(0, 8, 0, 16);
            layout.addView(swNotify);

            TextView info = new TextView(activity);
            info.setText("\u914D\u7F6E\u4FDD\u5B58\u5728\u5FAE\u4FE1\u5B58\u50A8\u76EE\u5F55\n\u91CD\u542F\u5FAE\u4FE1\u540E\u751F\u6548");
            info.setTextSize(12);
            info.setTextColor(Color.parseColor("#999999"));
            info.setGravity(Gravity.CENTER);
            layout.addView(info);

            new AlertDialog.Builder(activity)
                .setView(layout)
                .setPositiveButton("\u4FDD\u5B58", (dialog, which) -> {
                    ConfigStore.putBoolean("hb_auto_enable", swEnable.isChecked());
                    ConfigStore.putBoolean("hb_skip_self", swSkipSelf.isChecked());
                    ConfigStore.putBoolean("hb_notify_toast_enable", swNotify.isChecked());
                    Toast.makeText(activity, swEnable.isChecked() ? "\u5DF2\u5F00\u542F\uFF0C\u91CD\u542F\u5FAE\u4FE1\u751F\u6548" : "\u5DF2\u5173\u95ED", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("\u53D6\u6D88", null)
                .show();
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": [UI] showSettingsDialog error: " + e.getMessage());
        }
    }

    // ==================== 红包（纯反射，零native依赖） ====================

    private void initRedPacket(ClassLoader cl, Context ctx) {
        if (sRedPacketHooksRegistered) return;
        sGrabber = new RedPacketGrabber(ctx, cl);
        boolean initOk = sGrabber.init();
        XposedBridge.log(TAG + ": [RED] grabber.init()=" + initOk);
        if (initOk) {
            hookAddMsgBeforeDb(cl, ctx);
            sRedPacketHooksRegistered = true;
            XposedBridge.log(TAG + ": [RED] hooks registered");
        } else {
            XposedBridge.log(TAG + ": [RED] grabber init failed");
        }
    }

    private void hookAddMsgBeforeDb(ClassLoader cl, Context ctx) {
        try {
            String[] anchors = {
                "MicroMsg.MessageSyncExtension",
                "dkAddMsg",
                "processAddMsg"
            };

            List<Class<?>> addMsgClasses = new ArrayList<>();
            for (String anchor : anchors) {
                List<Class<?>> found = scanDexForAnchor(cl, anchor, 6);
                for (Class<?> c : found) {
                    if (!addMsgClasses.contains(c)) addMsgClasses.add(c);
                }
                if (!addMsgClasses.isEmpty()) break;
            }

            if (addMsgClasses.isEmpty()) {
                String[] fallbacks = {
                    "com.tencent.mm.storage.ak",
                    "com.tencent.mm.storage.al",
                    "com.tencent.mm.storage.MsgInfoStorage"
                };
                for (String name : fallbacks) {
                    try {
                        Class<?> c = XposedHelpers.findClass(name, cl);
                        if (c != null) addMsgClasses.add(c);
                    } catch (Throwable ignored) {}
                }
            }

            XposedBridge.log(TAG + ": [RED] AddMsg candidates: " + addMsgClasses.size());

            int totalHooked = 0;
            for (Class<?> cls : addMsgClasses) {
                int hooked = 0;
                for (Method m : cls.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts == null || pts.length == 0) continue;
                    List<Integer> addMsgArgIndexes = new ArrayList<>();
                    for (int pi = 0; pi < pts.length; pi++) {
                        if (isLikelyAddMsgClass(pts[pi])) addMsgArgIndexes.add(pi);
                    }
                    if (addMsgArgIndexes.isEmpty()) continue;

                    final List<Integer> argIndexes = addMsgArgIndexes;
                    final String hookClassName = cls.getName();
                    final String methodName = m.getName();

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (param.args == null) return;
                                for (Object idxObj : argIndexes) {
                                    int ai = (Integer) idxObj;
                                    if (ai < 0 || ai >= param.args.length || param.args[ai] == null) continue;
                                    Object am = param.args[ai];
                                    String content = findAddMsgContent(am);
                                    if (content != null && content.contains("<wcpayinfo>")) {
                                        if (!ConfigStore.getBoolean("hb_auto_enable", false)) return;
                                        XposedBridge.log(TAG + ": [RED] wcpayinfo in " + hookClassName + "." + methodName);
                                        if (sGrabber != null) sGrabber.handleAddMsgRedPacket(am, content);
                                    }
                                }
                            } catch (Throwable e) {
                                XposedBridge.log(TAG + ": [RED] hook error: " + e.getMessage());
                            }
                        }
                    });
                    hooked++;
                }
                XposedBridge.log(TAG + ": [RED] hooked " + cls.getName() + " methods=" + hooked);
                totalHooked += hooked;
            }
            XposedBridge.log(TAG + ": [RED] total hooks: " + totalHooked);
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": [RED] hookAddMsgBeforeDb error: " + e.getMessage());
        }
    }

    // ==================== 纯反射 DEX 扫描（原脚本 scanDexForAnchor） ====================

    private static Field findFieldInHierarchy(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private List<Class<?>> scanDexForAnchor(ClassLoader cl, String anchorText, int limit) {
        List<Class<?>> result = new ArrayList<>();
        try {
            Class<?> cur = cl.getClass();
            Object pathList = null;
            while (cur != null) {
                try {
                    Field f = cur.getDeclaredField("pathList");
                    f.setAccessible(true);
                    pathList = f.get(cl);
                    break;
                } catch (NoSuchFieldException e) { cur = cur.getSuperclass(); }
            }
            if (pathList == null) return result;

            Class<?> plCls = pathList.getClass();
            Class<?> dfCls = XposedHelpers.findClass("dalvik.system.DexFile", cl);
            Field deField = findFieldInHierarchy(plCls, "dexElements");
            if (deField == null) {
                XposedBridge.log(TAG + ": [RED] dexElements field not found in " + plCls.getName());
                return result;
            }
            Object[] dexElements = (Object[]) deField.get(pathList);
            byte[] ab = anchorText.getBytes("UTF-8");

            for (Object el : dexElements) {
                if (result.size() >= limit) break;
                try {
                    Field dfF = el.getClass().getDeclaredField("dexFile");
                    dfF.setAccessible(true);
                    Object df = dfF.get(el);
                    if (df == null) continue;

                    Field ckF = dfCls.getDeclaredField("mCookie");
                    ckF.setAccessible(true);
                    Object ck = ckF.get(df);
                    if (ck == null) continue;

                    Method gcl = dfCls.getMethod("getClassNameList", Object.class);
                    String[] names = (String[]) gcl.invoke(df, ck);
                    if (names == null) continue;

                    for (String cn : names) {
                        if (result.size() >= limit) break;
                        try {
                            Class<?> c = Class.forName(cn, false, cl);
                            for (Method m : c.getDeclaredMethods()) {
                                try {
                                    Field dmF = m.getClass().getDeclaredField("dexMethod");
                                    dmF.setAccessible(true);
                                    Object dm = dmF.get(m);
                                    if (dm == null) continue;

                                    Field dmDf = dm.getClass().getDeclaredField("dexFile");
                                    dmDf.setAccessible(true);
                                    Object df2 = dmDf.get(dm);
                                    if (df2 == null) continue;

                                    Field dcF = dm.getClass().getDeclaredField("declaringClass");
                                    dcF.setAccessible(true);
                                    Object dc = dcF.get(dm);
                                    if (dc == null) continue;

                                    Field ciF = dc.getClass().getDeclaredField("classIdx");
                                    ciF.setAccessible(true);
                                    int ci = ciF.getInt(dc);

                                    Field cdF = df2.getClass().getDeclaredField("mClassDefs");
                                    cdF.setAccessible(true);
                                    Object[] cds = (Object[]) cdF.get(df2);
                                    if (cds == null || ci < 0 || ci >= cds.length) continue;

                                    Field aoF = cds[ci].getClass().getDeclaredField("annotationsOff");
                                    aoF.setAccessible(true);
                                    int ao = aoF.getInt(cds[ci]);

                                    Field dbF = df2.getClass().getDeclaredField("mDexBuffer");
                                    dbF.setAccessible(true);
                                    Object db = dbF.get(df2);
                                    if (db == null) continue;

                                    Field dF = db.getClass().getDeclaredField("data");
                                    dF.setAccessible(true);
                                    byte[] data = (byte[]) dF.get(db);
                                    if (data == null || ao <= 0 || ao >= data.length) continue;

                                    int sl = Math.min(256, data.length - ao);
                                    if (bytesContains(data, ao, sl, ab)) {
                                        result.add(c);
                                        break;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": [RED] scanDexForAnchor error: " + e.getMessage());
        }
        return result;
    }

    private boolean bytesContains(byte[] d, int off, int len, byte[] nd) {
        if (nd.length == 0) return true;
        outer:
        for (int i = off; i <= off + len - nd.length; i++) {
            for (int j = 0; j < nd.length; j++) {
                if (d[i + j] != nd[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private boolean isLikelyAddMsgClass(Class<?> c) {
        if (c == null || c.isPrimitive() || c.isArray()) return false;
        if (c == String.class || Number.class.isAssignableFrom(c) || c == Boolean.class) return false;
        return hasField(c, "e") && hasField(c, "f") && (hasField(c, "h") || hasField(c, "i") || hasField(c, "m"));
    }

    private boolean hasField(Class<?> c, String name) {
        if (c == null) return false;
        try {
            Class<?> cur = c;
            while (cur != null && cur != Object.class) {
                for (Field f : cur.getDeclaredFields()) {
                    if (name.equals(f.getName())) return true;
                }
                cur = cur.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private String findAddMsgContent(Object am) {
        for (String fn : new String[]{"h", "i", "m"}) {
            String v = readObjFieldString(am, fn);
            if (v != null && v.contains("<wcpayinfo>")) return v;
        }
        String h = readObjFieldString(am, "h");
        if (h != null && !h.isEmpty() && !h.matches("^-?\\d+$")) return h;
        return null;
    }

    private String readObjFieldString(Object obj, String fn) {
        try {
            Object v = readObjField(obj, fn);
            if (v == null) return null;
            try {
                Object inner = readObjField(v, "d");
                if (inner != null) return String.valueOf(inner);
            } catch (Throwable ignored) {}
            return String.valueOf(v);
        } catch (Throwable ignored) {}
        return null;
    }

    private Object readObjField(Object obj, String fn) {
        if (obj == null) return null;
        try {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(fn);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}