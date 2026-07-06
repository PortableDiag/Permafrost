package com.portablediag.permafrost.ui;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * On Android 15 (targetSdk 35) apps are edge-to-edge by default, so content
 * draws under the status bar and the gesture/button nav bar. This pads a root
 * view by the system-bar insets so nothing hides behind them.
 */
final class SystemBars {

    private SystemBars() {}

    static void pad(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }
}
