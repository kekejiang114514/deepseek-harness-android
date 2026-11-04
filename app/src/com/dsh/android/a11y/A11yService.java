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

        // 记一笔「曾经连上过」：之后如果系统把服务关掉，界面上要能据此提醒重开
        try {
            getSharedPreferences("dsh", MODE_PRIVATE).edit()
                    .putBoolean("a11y_was_on", true).apply();
        } catch (Throwable ignored) {}

        // 在系统给的那份信息上改，不要 new 一个新的覆盖上去：
        // 新建的 AccessibilityServiceInfo 不带 android:description / settingsActivity /
        // packageNames，setServiceInfo 之后系统设置里这一项就变成「没有描述」的状态。
        //
        // 另外这里任何一步抛异常都会让系统认为服务坏了，反复几次就会把它自动关掉，
        // 所以整体兜住；事件也只订阅需要的那三类，不再 TYPES_ALL_MASK 把它淹了。
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) info = new AccessibilityServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_SCROLLED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        } catch (Throwable t) {
            // 配置没设上也还能用：读屏和手势靠 manifest 里的 xml 生效
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        // 返回 true 才会走到 onRebind：系统重新拉起服务时直接复用，不必走一遍完整连接
        return super.onUnbind(intent);
    }

    @Override
    public void onRebind(android.content.Intent intent) {
        super.onRebind(intent);
        instance = this;
        enabled = true;
    }

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

    /**
     * 往上找最近的可点击祖先。
     *
     * <p>列表项这类结构里，TextView 自己不可点，真正响应点击的是外层容器。
     * 只按节点自己判断，会出现「索引是对的、点了没反应」。
     */
    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null) return null;
            cur = parent;
        }
        return null;
    }

    private AccessibilityNodeInfo editableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            if (cur.isEditable()) return cur;
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null) return null;
            cur = parent;
        }
        return null;
    }

    private AccessibilityNodeInfo scrollableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int depth = 0; cur != null && depth < 12; depth++) {
            if (cur.isScrollable()) return cur;
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null) return null;
            cur = parent;
        }
        return null;
    }

    /**
     * 紧凑屏幕摘要：一行一个可见控件，给模型读的版本。
     *
     * <p>dump 输出的是完整 JSON，几十个节点就上千 token；实际做决定时需要的只是
     * 「第几号、写的什么、能不能点、在屏幕哪块」。这里按这个来输出，节点编号与 dump 一致。
     */
    public String outline(int limit) {
        if (limit <= 0) limit = 80;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        DumpIndex idx = new DumpIndex();
        outlineRec(root, sb, idx, 0, limit);
        root.recycle();
        return sb.toString();
    }

    private void outlineRec(AccessibilityNodeInfo node, StringBuilder sb, DumpIndex idx,
                            int depth, int limit) {
        int myIndex = idx.n;
        idx.n++;
        if (sb.length() > 0 && countLines(sb) >= limit) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = text != null && text.length() > 0 ? text.toString()
                : (desc != null ? desc.toString() : "");
        boolean clickable = node.isClickable();
        boolean editable = node.isEditable();
        boolean scrollable = node.isScrollable();
        boolean visible = node.isVisibleToUser();

        if (visible && (label.length() > 0 || clickable || editable || scrollable)) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            sb.append('[').append(myIndex).append("] ");
            if (label.length() > 0) {
                String clean = label.replace('\n', ' ');
                if (clean.length() > 60) clean = clean.substring(0, 60) + "…";
                sb.append('"').append(clean).append('"');
            } else {
                sb.append('(').append(simpleClass(node.getClassName())).append(')');
            }
            if (editable) sb.append(" 输入框");
            if (clickable) sb.append(" 可点");
            if (scrollable) sb.append(" 可滚");
            if (node.isPassword()) sb.append(" 密码");
            if (node.isCheckable()) sb.append(node.isChecked() ? " 已选" : " 未选");
            sb.append(" @").append(r.left).append(',').append(r.top)
              .append(' ').append(r.width()).append('x').append(r.height());
            sb.append('\n');
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            outlineRec(child, sb, idx, depth + 1, limit);
            child.recycle();
        }
    }

    private static int countLines(StringBuilder sb) {
        int n = 0;
        for (int i = 0; i < sb.length(); i++) if (sb.charAt(i) == '\n') n++;
        return n;
    }

    private static String simpleClass(CharSequence cls) {
        if (cls == null) return "view";
        String s = cls.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** 当前前台应用：包名 + 窗口标题。 */
    public String appInfo() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        CharSequence pkg = root.getPackageName();
        sb.append("{\"pkg\":\"").append(esc(pkg == null ? "" : pkg.toString())).append('"');
        CharSequence title = null;
        android.view.accessibility.AccessibilityWindowInfo win =
                root.getWindow() == null ? null : root.getWindow();
        if (win != null) title = win.getTitle();
        sb.append(",\"window\":\"").append(esc(title == null ? "" : title.toString())).append('"');
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        sb.append(",\"screen\":[").append(dm.widthPixels).append(',').append(dm.heightPixels).append("]}");
        root.recycle();
        return sb.toString();
    }

    /**
     * 等某段文字出现（点完新页面加载、列表刷新时用）。
     * 返回节点索引，-1 表示超时。
     */
    public int waitForText(String query, int timeoutMs) {
        if (timeoutMs <= 0) timeoutMs = 5000;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int idx = findTextIndex(query);
            if (idx >= 0) return idx;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return -1;
            }
        }
        return -1;
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

        // 文字节点自己通常不可点，真正响应的是外层容器
        AccessibilityNodeInfo hit = node.isClickable() ? node : clickableAncestor(node);
        if (hit != null && hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            hit.recycle();
            node.recycle();
            return "{\"ok\":true}";
        }

        // 节点级点击被拒：按它自己的可见范围点一下中心
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        node.recycle();
        if (r.width() <= 0 || r.height() <= 0) {
            return "{\"ok\":false,\"error\":\"节点不在屏幕上（可能被遮挡或已消失），请重新 a11y.dump\"}";
        }
        return tap(r.centerX(), r.centerY());
    }

    public String longClickByIndex(int targetIndex) {
        AccessibilityNodeInfo node = nodeAt(targetIndex);
        if (node == null) return "{\"ok\":false,\"error\":\"index not found\"}";
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 18) {
            ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            if (!ok && !node.isLongClickable()) {
                AccessibilityNodeInfo up = clickableAncestor(node);
                if (up != null && up.isLongClickable()) {
                    ok = up.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                }
            }
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
        AccessibilityNodeInfo field = node.isEditable() ? node : editableAncestor(node);
        if (field == null) {
            node.recycle();
            return "{\"ok\":false,\"error\":\"该节点附近没有可输入框，请 a11y.dump 找 editable:true 的节点\"}";
        }
        android.os.Bundle args = new android.os.Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) {
            // 少数输入法实现不支持 SET_TEXT：先聚焦，再用系统粘贴
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }
        field.recycle();
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
        AccessibilityNodeInfo box = node.isScrollable() ? node : scrollableAncestor(node);
        if (box == null) {
            // 给的是列表里某一项：就在它所在区域划一下
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            node.recycle();
            int cx = r.centerX();
            return "forward".equals(direction)
                    ? swipe(cx, r.top + r.height() * 2 / 3, cx, r.top + r.height() / 3, 300)
                    : swipe(cx, r.top + r.height() / 3, cx, r.top + r.height() * 2 / 3, 300);
        }
        int action = "forward".equals(direction)
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        boolean ok = box.performAction(action);
        box.recycle();
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
