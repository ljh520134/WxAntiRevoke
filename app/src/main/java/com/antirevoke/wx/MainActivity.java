package com.antirevoke.wx;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 模块 App 主界面 - 只展示信息，配置在微信内操作
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.parseColor("#F5F5F5"));

        root.addView(card(tv("微信增强模块", 24, "#07C160", Gravity.CENTER)));
        root.addView(card(tv("LSPosed Xposed Module v2.0", 13, "#999999", Gravity.CENTER)));

        boolean active = false;
        try { Class.forName("de.robv.android.xposed.XposedBridge"); active = true; } catch (Exception e) {}
        root.addView(card(tv(
            active
                ? "✅ 模块运行环境正常\n请在 LSPosed 中启用并勾选微信，重启微信后生效"
                : "⚠️ 未检测到 Xposed 环境\n请确保已在 LSPosed 管理器中启用本模块",
            14, active ? "#07C160" : "#FF9800", Gravity.START)));

        root.addView(sectionTitle("使用说明"));
        root.addView(card(tv(
            "1. 在 LSPosed 中启用本模块，勾选微信\n" +
            "2. 重启微信\n" +
            "3. 进入微信 → 我 → 设置 → 点击底部「自动抢红包」\n" +
            "4. 开启开关，重启微信生效\n\n" +
            "配置保存在:\n" +
            "/sdcard/Android/data/com.tencent.mm/files/WxEnhance/config.json",
            13, "#666666", Gravity.START)));

        root.addView(sectionTitle("功能"));
        root.addView(card(tv(
            "• DexKit 动态定位混淆类，兼容所有版本\n" +
            "• Hook AddMsg 预监听，检测 wcpayinfo 红包 XML\n" +
            "• 反射构造 NetSceneReceiveLuckyMoney 请求\n" +
            "• 通过 NetSceneQueue 发送网络请求\n" +
            "• 微信设置页注入入口，原生体验\n" +
            "• 兼容微信 8.0.49 ~ 8.0.74+",
            13, "#666666", Gravity.START)));

        sv.addView(root);
        setContentView(sv);
    }

    private TextView tv(String text, int size, String color, int gravity) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTextColor(Color.parseColor(color));
        tv.setGravity(gravity);
        tv.setPadding(16, 12, 16, 12);
        return tv;
    }

    private View sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(Color.parseColor("#333333"));
        tv.setPadding(0, 24, 0, 8);
        return tv;
    }

    private View card(View child) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(16);
        card.setBackground(bg);
        card.addView(child);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, 16);
        card.setLayoutParams(p);
        return card;
    }
}