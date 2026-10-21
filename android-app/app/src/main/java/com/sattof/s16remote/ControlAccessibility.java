package com.sattof.s16remote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class ControlAccessibility extends AccessibilityService {
    public static volatile ControlAccessibility instance;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
    public void navigation(String command) {
        handler.post(() -> {
            if (command.equals("home")) performGlobalAction(GLOBAL_ACTION_HOME);
            else if (command.equals("back")) performGlobalAction(GLOBAL_ACTION_BACK);
            else if (command.equals("recents")) performGlobalAction(GLOBAL_ACTION_RECENTS);
            else if (command.equals("notifications")) performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        });
    }
    public void gesture(final int x1, final int y1, final int x2, final int y2, final long duration) {
        handler.post(() -> {
            Path path = new Path();
            path.moveTo(x1, y1);
            if (Math.abs(x2-x1)+Math.abs(y2-y1) > 6) path.lineTo(x2,y2);
            GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(
                    path, 0, Math.max(100, Math.min(duration, 1500))))
                .build();
            dispatchGesture(g, null, null);
        });
    }
    public boolean setFocusedText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo input = findFocused(root);
            if (input == null) return false;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean result = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            input.recycle();
            return result;
        } finally {
            root.recycle();
        }
    }
    private AccessibilityNodeInfo findFocused(AccessibilityNodeInfo node) {
        if (node.isFocused() && node.isEditable()) return AccessibilityNodeInfo.obtain(node);
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    AccessibilityNodeInfo result = findFocused(child);
                    if (result != null) return result;
                } finally {
                    child.recycle();
                }
            }
        }
        return null;
    }
}
