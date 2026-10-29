package com.dsh.android.a11y;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * 无障碍服务：让 Agent 读取屏幕内容、模拟点击/长按/双击/滑动/捏合/输入，
 * 以及执行返回、Home、最近任务等系统操作。
 */
public final class A11yService extends AccessibilityService {

    private static A11yService instance;
    private static boolean enabled = false;

    public static A11yService get() { return instance; }
    public static boolean isReady() { return enabled && instance != null; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        enabled = true;

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.DEFAULT;
        info.notificationTimeout = 100;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        enabled = false;
        instance = null;
        super.onDestroy();
    }

    // ================= 读屏 =================

    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            DumpIndex idx = new DumpIndex();
            dumpNode(root, sb, idx);
            root.recycle();
        }
        sb.append("]");
        return sb.toString();
    }

    private static final class DumpIndex { int n = 0; }

    private void dumpNode(AccessibilityNodeInfo node, StringBuilder sb, DumpIndex idx) {
        if (idx.n == 0) sb.append("{");
        else sb.append(",{");

        sb.append("\"index\":").append(idx.n);
        sb.append(",\"class\":\"").append(esc(node.getClassName()));
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        sb.append("\",\"text\":\"").append(esc(text == null ? "" : text.toString()));
        sb.append("\",\"desc\":\"").append(esc(desc == null ? "" : desc.toString()));
        String id = node.getViewIdResourceName();
        sb.append("\",\"id\":\"").append(esc(id == null ? "" : id));
        sb.append("\",\"clickable\":").append(node.isClickable());
        sb.append(",\"editable\":").append(node.isEditable());
        sb.append(",\"scrollable\":").append(node.isScrollable());
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        sb.append(",\"bounds\":[").append(r.left).append(",").append(r.top)
          .append(",").append(r.right).append(",").append(r.bottom).append("]");
        sb.append("}");

        idx.n++;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                dumpNode(child, sb, idx);
                child.recycle();
            }
        }
    }

    public String foregroundPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        CharSequence p = root.getPackageName();
        root.recycle();
        return p == null ? "" : p.toString();
    }

    private static String esc(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append("?");
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    // ================= 节点查找 =================

    private AccessibilityNodeInfo findByIndex(AccessibilityNodeInfo node, int target, DumpIndex idx) {
        if (idx.n == target) return node;
        idx.n++;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo r = findByIndex(child, target, idx);
                if (r != null) return r;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo nodeAt(int index) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo found = findByIndex(root, index, new DumpIndex());
        if (found == null) root.recycle();
        return found;
    }

    /** 按文本/描述查找节点（包含匹配，优先可点击祖先）。返回 dump 索引，-1 表示未找到。 */
    public int findTextIndex(String query) {
        if (query == null || query.length() == 0) return -1;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return -1;
        int[] found = new int[]{-1};
        DumpIndex idx = new DumpIndex();
        findTextRec(root, query.toLowerCase(), idx, found);
        root.recycle();
        return found[0];
    }

    private boolean findTextRec(AccessibilityNodeInfo node, String q, DumpIndex idx, int[] found) {
        int myIndex = idx.n;
        idx.n++;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        String hay = (t == null ? "" : t.toString()) + "\u0001" + (d == null ? "" : d.toString());
        if (hay.toLowerCase().indexOf(q) >= 0) {
            found[0] = myIndex;
            return true;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (findTextRec(child, q, idx, found)) { child.recycle(); return true; }
                child.recycle();
            }
        }
        return false;
    }

    private static Rect centerOf(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        r.set(r.centerX(), r.centerY(), r.centerX(), r.centerY());
        return r;
    }

    // ================= 操作：节点级 =================

    public String clickByIndex(int targetIndex) {
        AccessibilityNodeInfo node = nodeAt(targetIndex);
        if (node == null) return "{\"ok\":false,\"error\":\"index not found\"}";
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!ok) {
            // 退化为坐标点击
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            node.recycle();
            return tap(r.centerX(), r.centerY());
        }
        node.recycle();
        return ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"click failed\"}";
    }

    public String longClickByIndex(int targetIndex) {
        AccessibilityNodeInfo node = nodeAt(targetIndex);
        if (node == null) return "{\"ok\":false,\"error\":\"index not found\"}";
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 18) {
            ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
        }
        if (!ok) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            node.recycle();
            return longPress(r.centerX(), r.centerY());
        }
        node.recycle();
        return ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"longclick failed\"}";
    }

    public String doubleClickByIndex(int targetIndex) {
        AccessibilityNodeInfo node = nodeAt(targetIndex);
        if (node == null) return "{\"ok\":false,\"error\":\"index not found\"}";
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        node.recycle();
        return doubleTap(r.centerX(), r.centerY());
    }

    public String setText(int targetIndex, String text) {
        AccessibilityNodeInfo node = nodeAt(targetIndex);
        if (node == null) return "{\"ok\":false,\"error\":\"index not found\"}";
        android.os.Bundle args = new android.os.Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        node.recycle();
        return ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"settext failed\"}";
    }

    public String clearText(int targetIndex) {
        return setText(targetIndex, "");
    }

    /** 触发发送/提交：在屏幕上查找常见的发送按钮并点击。 */
    public String pressEnter(int targetIndex) {
        String[] candidates = new String[]{"发送", "Send", "提交", "确定", "完成", "搜索", "Search", "Go"};
        for (int i = 0; i < candidates.length; i++) {
            int idx = findTextIndex(candidates[i]);
            if (idx >= 0) {
                String r = clickByIndex(idx);
                if (r.indexOf("\"ok\":true") >= 0) {
                    return "{\"ok\":true,\"clicked\":\"" + esc(candidates[i]) + "\"}";
                }
            }
        }
        return "{\"ok\":false,\"error\":\"未找到发送/提交按钮，请先 a11y.dump 后用 a11y.click 指定索引\"}";
    }

    /** 在指定可滚动节点上滚动。 */
    public String scrollNode(int targetIndex, String direction) {
        AccessibilityNodeInfo node = nodeAt(targetIndex);
        if (node == null) return "{\"ok\":false,\"error\":\"index not found\"}";
        int action = "forward".equals(direction)
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        boolean ok = node.performAction(action);
        node.recycle();
        return ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"scroll failed\"}";
    }

    /** 全屏滚动（自动寻找可滚动容器）。 */
    public String scroll(String direction) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "{\"ok\":false,\"error\":\"no window\"}";
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable == null) {
            root.recycle();
            return "{\"ok\":false,\"error\":\"no scrollable\"}";
        }
        int action = "forward".equals(direction)
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        boolean ok = scrollable.performAction(action);
        root.recycle();
        return ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"scroll failed\"}";
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node.isScrollable()) return node;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo r = findScrollable(child);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ================= 操作：手势级 =================

    private boolean gesture(GestureDescription gd) {
        if (Build.VERSION.SDK_INT < 24) return false;
        return dispatchGesture(gd, null, null);
    }

    public String tap(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) return "{\"ok\":false,\"error\":\"手势需要 Android 7.0+\"}";
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 40))
                .build();
        return gesture(gd) ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"tap rejected\"}";
    }

    public String longPress(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) return "{\"ok\":false,\"error\":\"手势需要 Android 7.0+\"}";
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 700))
                .build();
        return gesture(gd) ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"longpress rejected\"}";
    }

    public String doubleTap(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) return "{\"ok\":false,\"error\":\"手势需要 Android 7.0+\"}";
        Path p1 = new Path();
        p1.moveTo(x, y);
        Path p2 = new Path();
        p2.moveTo(x, y);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p1, 0, 40))
                .addStroke(new GestureDescription.StrokeDescription(p2, 100, 40))
                .build();
        return gesture(gd) ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"doubletap rejected\"}";
    }

    public String swipe(int x1, int y1, int x2, int y2, int durationMs) {
        if (Build.VERSION.SDK_INT < 24) return "{\"ok\":false,\"error\":\"手势需要 Android 7.0+\"}";
        if (durationMs <= 0) durationMs = 300;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, durationMs))
                .build();
        return gesture(gd) ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"swipe rejected\"}";
    }

    /** 屏幕中央双指捏合：scale > 1 放大，< 1 缩小。 */
    public String pinch(float scale, int durationMs) {
        if (Build.VERSION.SDK_INT < 24) return "{\"ok\":false,\"error\":\"手势需要 Android 7.0+\"}";
        if (durationMs <= 0) durationMs = 400;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float cx = dm.widthPixels / 2f;
        float cy = dm.heightPixels / 2f;
        float d0 = Math.min(dm.widthPixels, dm.heightPixels) * 0.15f;
        float d1 = d0 * (scale > 0 ? scale : 1f);

        Path a = new Path();
        a.moveTo(cx, cy - d0);
        a.lineTo(cx, cy - d1);
        Path b = new Path();
        b.moveTo(cx, cy + d0);
        b.lineTo(cx, cy + d1);

        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(a, 0, durationMs))
                .addStroke(new GestureDescription.StrokeDescription(b, 0, durationMs))
                .build();
        return gesture(gd) ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"pinch rejected\"}";
    }

    // ================= 系统级 =================

    public boolean performGlobalBack() {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    public boolean performGlobalHome() {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    }

    public boolean performGlobalRecents() {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
    }

    public boolean performGlobalNotifications() {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
    }
}
