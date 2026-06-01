package com.antirevoke.wx;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 红包抢包器 - 独立实现，纯反射，零 native 依赖
 * 使用 scanDexForAnchor 动态定位微信混淆类，兼容所有版本
 */
public class RedPacketGrabber {

    private static final String TAG = "WxEnhance";
    private final Context context;
    private final ClassLoader classLoader;
    private final Handler mainHandler;

    private Class<?> receiveLuckyMoneyClass;
    private Class<?> openLuckyMoneyClass;
    private Constructor<?> receiveCtor;
    private Constructor<?> openCtor;

    private Object networkQueue;
    private Method sendMethod;

    private final Set<String> processedSet = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, Object>> redPacketInfoMap = new ConcurrentHashMap<>();

    private int grabCount = 0;
    private int failCount = 0;

    public RedPacketGrabber(Context context, ClassLoader classLoader) {
        this.context = context;
        this.classLoader = classLoader;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public boolean init() {
        try {
            // 和原脚本 initSilentModeEnv 一样的逻辑
            List<Class<?>> rc = findClassListByAnchor("receivehongbao", 6);
            List<Class<?>> oc = findClassListByAnchor("openlucky", 6);

            // 从接收候选中找有 onGYNetEnd 的类
            for (Object c : rc) {
                if (!(c instanceof Class)) continue;
                for (Method m : ((Class<?>) c).getDeclaredMethods()) {
                    if ("onGYNetEnd".equals(m.getName())) {
                        receiveLuckyMoneyClass = (Class<?>) c;
                        break;
                    }
                }
                if (receiveLuckyMoneyClass != null) break;
            }

            // 从打开候选中找有 onGYNetEnd 的类
            for (Object c : oc) {
                if (!(c instanceof Class)) continue;
                for (Method m : ((Class<?>) c).getDeclaredMethods()) {
                    if ("onGYNetEnd".equals(m.getName())) {
                        openLuckyMoneyClass = (Class<?>) c;
                        break;
                    }
                }
                if (openLuckyMoneyClass != null) break;
            }

            if (receiveLuckyMoneyClass != null) {
                receiveCtor = findConstructor(receiveLuckyMoneyClass, 7);
                if (receiveCtor == null) receiveCtor = findConstructor(receiveLuckyMoneyClass, 10);
            }
            if (openLuckyMoneyClass != null) {
                openCtor = findConstructor(openLuckyMoneyClass, 10);
                if (openCtor == null) openCtor = findConstructor(openLuckyMoneyClass, 8);
            }

            if (receiveLuckyMoneyClass == null) {
                XposedBridge.log(TAG + ": [RED] no receive class found");
                return false;
            }

            XposedBridge.log(TAG + ": [RED] Init OK: receive=" + receiveLuckyMoneyClass.getName()
                + " ctor=" + (receiveCtor != null ? receiveCtor.getParameterCount() : "null")
                + " open=" + (openLuckyMoneyClass != null ? openLuckyMoneyClass.getName() : "null"));
            return true;
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": [RED] init failed: " + e.getMessage());
            return false;
        }
    }

    private List<Class<?>> findClassListByAnchor(String anchor, int limit) {
        // 复用 HookEntry.scanDexForAnchor 的逻辑，但这里独立实现
        List<Class<?>> result = new ArrayList<>();
        try {
            Class<?> cur = classLoader.getClass();
            Object pathList = null;
            while (cur != null) {
                try {
                    Field f = cur.getDeclaredField("pathList");
                    f.setAccessible(true);
                    pathList = f.get(classLoader);
                    break;
                } catch (NoSuchFieldException e) { cur = cur.getSuperclass(); }
            }
            if (pathList == null) return result;

            Class<?> plCls = pathList.getClass();
            Class<?> dfCls = XposedHelpers.findClass("dalvik.system.DexFile", classLoader);
            Field deField = null;
            for (Class<?> c = plCls; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    deField = c.getDeclaredField("dexElements");
                    deField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (deField == null) {
                XposedBridge.log(TAG + ": [RED] dexElements field not found");
                return result;
            }
            Object[] dexElements = (Object[]) deField.get(pathList);
            byte[] ab = anchor.getBytes("UTF-8");

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
                            Class<?> c = Class.forName(cn, false, classLoader);
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
        outer: for (int i = off; i <= off + len - nd.length; i++) {
            for (int j = 0; j < nd.length; j++) {
                if (d[i + j] != nd[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    public void handleAddMsgRedPacket(Object am, String content) {
        try {
            String xml = content;
            int idx = content.indexOf(":\n");
            if (idx > 0 && content.indexOf("<") > idx) xml = content.substring(idx + 2);

            String nativeUrl = getXmlField(xml, "nativeurl");
            if (TextUtils.isEmpty(nativeUrl)) return;
            String sendId = getUrlParam(nativeUrl, "sendid");
            if (TextUtils.isEmpty(sendId)) return;

            String from = readObjFieldString(am, "e");
            String to = readObjFieldString(am, "f");
            String sender = getXmlField(xml, "fromusername");
            if (TextUtils.isEmpty(sender)) sender = from;

            String myWxid = "";
            try {
                Object sp = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.tencent.mm.sdk.platformtools.MMApplicationContext", classLoader),
                    "getSharedPreferences", "login_info", 0);
                if (sp != null) myWxid = (String) XposedHelpers.callMethod(sp, "getString", "login_weixin_username", "");
            } catch (Throwable ignored) {}

            String talker = from;
            if (!TextUtils.isEmpty(myWxid) && myWxid.equals(from) && !TextUtils.isEmpty(to)) talker = to;
            if (TextUtils.isEmpty(talker)) talker = to;

            boolean skipSelf = ConfigStore.getBoolean("hb_skip_self", false);
            if (skipSelf && !TextUtils.isEmpty(myWxid) && !TextUtils.isEmpty(sender) && sender.equals(myWxid)) {
                XposedBridge.log(TAG + ": [RED] skip self-sent packet");
                return;
            }

            handleRedPacketInternal(xml, sender, talker, sendId, nativeUrl);
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": [RED] handleAddMsgRedPacket error: " + e.getMessage());
        }
    }

    public void handleRedBagMessage(String content, Object msgObj) {
        try {
            String nativeUrl = getXmlField(content, "nativeurl");
            if (TextUtils.isEmpty(nativeUrl)) return;
            String sendId = getUrlParam(nativeUrl, "sendid");
            if (TextUtils.isEmpty(sendId)) return;

            String sender = getXmlField(content, "fromusername");
            String talker = "";
            try { talker = (String) msgObj.getClass().getMethod("getTalker").invoke(msgObj); } catch (Throwable ignored) {}

            handleRedPacketInternal(content, sender, talker, sendId, nativeUrl);
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": [RED] handleRedBagMessage error: " + e.getMessage());
        }
    }

    private void handleRedPacketInternal(String xml, String sender, String talker, String sendId, String nativeUrl) {
        if (processedSet.contains(sendId)) return;
        processedSet.add(sendId);

        Map<String, Object> info = new ConcurrentHashMap<>();
        info.put("sendid", sendId);
        info.put("nativeurl", nativeUrl);
        info.put("sender", sender);
        info.put("talker", talker);
        redPacketInfoMap.put(sendId, info);

        XposedBridge.log(TAG + ": [RED] scheduled grab: sendid=" + sendId);
        final String ft = talker;
        mainHandler.postDelayed(() -> doGrab(nativeUrl, ft, sendId), 0);
    }

    private void doGrab(String nativeUrl, String talker, String sendId) {
        if (receiveCtor == null) {
            failCount++;
            XposedBridge.log(TAG + ": [RED] doGrab failed: receiveCtor is null");
            return;
        }
        try {
            int msgType = safeParseInt(getUrlParam(nativeUrl, "msgtype"), 1);
            int channelId = safeParseInt(getUrlParam(nativeUrl, "channelid"), 1);
            XposedBridge.log(TAG + ": [RED] doGrab: sendid=" + sendId + " msgType=" + msgType + " channelId=" + channelId + " ctor=" + receiveCtor.getParameterCount());
            Object request;
            if (receiveCtor.getParameterCount() == 10) {
                request = receiveCtor.newInstance(msgType, channelId, sendId, nativeUrl, 1, "v1.0", talker, "", "", "");
            } else if (receiveCtor.getParameterCount() == 7) {
                request = receiveCtor.newInstance(msgType, channelId, sendId, nativeUrl, 1, "v1.0", talker);
            } else {
                failCount++;
                XposedBridge.log(TAG + ": [RED] unsupported ctor param count=" + receiveCtor.getParameterCount());
                return;
            }
            if (sendNetworkRequest(request)) {
                grabCount++;
                XposedBridge.log(TAG + ": [RED] GRAB SUCCESS: sendid=" + sendId + " total=" + grabCount);
                notifyGrabSuccess();
            } else {
                failCount++;
                XposedBridge.log(TAG + ": [RED] GRAB FAILED: sendid=" + sendId);
            }
        } catch (Throwable e) {
            failCount++;
            XposedBridge.log(TAG + ": [RED] doGrab exception: " + e.getMessage());
        }
    }

    private boolean sendNetworkRequest(Object request) {
        if (networkQueue != null && sendMethod != null) {
            try {
                Object r = sendMethod.getParameterCount() == 2
                    ? sendMethod.invoke(networkQueue, request, 0)
                    : sendMethod.invoke(networkQueue, request);
                return !(r instanceof Boolean) || (Boolean) r;
            } catch (Throwable e) {
                XposedBridge.log(TAG + ": [RED] cached queue error: " + e.getMessage());
            }
        }
        try {
            Object queue = getNetworkQueue();
            if (queue != null) {
                XposedBridge.log(TAG + ": [RED] got queue=" + queue.getClass().getName());
                for (Method m : queue.getClass().getMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length >= 1 && pts[0].isAssignableFrom(request.getClass())) {
                        m.setAccessible(true);
                        Object r = m.invoke(queue, request);
                        networkQueue = queue;
                        sendMethod = m;
                        XposedBridge.log(TAG + ": [RED] send via " + m.getName() + " result=" + r);
                        return !(r instanceof Boolean) || (Boolean) r;
                    }
                }
            } else {
                XposedBridge.log(TAG + ": [RED] getNetworkQueue returned null");
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": [RED] sendNetworkRequest error: " + e.getMessage());
        }
        return false;
    }

    private Object getNetworkQueue() {
        try {
            Class<?> h = XposedHelpers.findClass("com.tencent.mm.kernel.h", classLoader);
            if (h == null) {
                XposedBridge.log(TAG + ": [RED] kernel.h class not found");
                return null;
            }
            Object kernel = XposedHelpers.callStaticMethod(h, "a");
            if (kernel == null) {
                XposedBridge.log(TAG + ": [RED] kernel.a() returned null");
                return null;
            }
            XposedBridge.log(TAG + ": [RED] kernel=" + kernel.getClass().getName());
            for (Method m : kernel.getClass().getMethods()) {
                String rn = m.getReturnType().getName();
                if (rn.contains("NetSceneQueue") || rn.contains("modelbase")) {
                    Object q = m.invoke(kernel);
                    if (q != null) {
                        XposedBridge.log(TAG + ": [RED] queue via " + m.getName() + " type=" + q.getClass().getName());
                        return q;
                    }
                }
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": [RED] getNetworkQueue error: " + e.getMessage());
        }
        return null;
    }

    private void notifyGrabSuccess() {
        if (!ConfigStore.getBoolean("hb_notify_toast_enable", true)) return;
        mainHandler.post(() -> {
            try {
                Toast.makeText(context, "Grabbed red packet!", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }

    private Constructor<?> findConstructor(Class<?> cls, int paramCount) {
        if (cls == null) return null;
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (c.getParameterCount() == paramCount) {
                c.setAccessible(true);
                return c;
            }
        }
        return null;
    }

    static String getXmlField(String xml, String tag) {
        if (TextUtils.isEmpty(xml) || TextUtils.isEmpty(tag)) return "";
        try {
            Matcher m1 = Pattern.compile("<" + tag + "><!\\[CDATA\\[(.*?)\\]\\]></" + tag + ">").matcher(xml);
            if (m1.find()) return m1.group(1);
            Matcher m2 = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">").matcher(xml);
            if (m2.find()) return m2.group(1);
        } catch (Throwable ignored) {}
        return "";
    }

    static String getUrlParam(String url, String key) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) return null;
        try {
            String prefix = key + "=";
            int s = url.indexOf('?');
            s = s >= 0 ? s + 1 : 0;
            while (s < url.length()) {
                int e = url.indexOf('&', s);
                if (e < 0) e = url.length();
                if (url.startsWith(prefix, s)) return url.substring(s + prefix.length(), e);
                s = e + 1;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static int safeParseInt(String v, int def) {
        if (TextUtils.isEmpty(v)) return def;
        try { return Integer.parseInt(v); } catch (Throwable e) { return def; }
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

    public int getGrabCount() { return grabCount; }
    public int getFailCount() { return failCount; }
}