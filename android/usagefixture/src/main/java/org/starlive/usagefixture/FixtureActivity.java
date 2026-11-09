package org.starlive.usagefixture;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;

/** An independent, permission-free foreground target for controlled device tests. */
public final class FixtureActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView elapsed;
    private long resumedAt;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            elapsed.setText("本次前台已运行：" + ((SystemClock.elapsedRealtime() - resumedAt) / 1000) + " 秒");
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        column.setOnApplyWindowInsetsListener((view, insets) -> {
            column.setPadding(padding + insets.getSystemWindowInsetLeft(),
                    padding + insets.getSystemWindowInsetTop(),
                    padding + insets.getSystemWindowInsetRight(),
                    padding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("StarLive 测试计时页\n非正式产品，仅用于受控测试");
        title.setTextSize(24);
        column.addView(title);
        TextView description = new TextView(this);
        description.setText("此独立应用不申请任何权限。\n停留在本页产生真实 Android 前台使用事件。\n熄屏、离开或切换应用会暂停本页计时。\n没有保持唤醒设置，遵循系统锁屏。\n\n进入时间：" + DateFormat.getDateTimeInstance().format(new Date()));
        description.setTextSize(17);
        column.addView(description);
        elapsed = new TextView(this);
        elapsed.setTextSize(28);
        elapsed.setPadding(0, 36, 0, 36);
        column.addView(elapsed);
        Button leave = new Button(this);
        leave.setText("结束本次测试并返回");
        leave.setOnClickListener(view -> finish());
        column.addView(leave);
        setContentView(column);
    }

    @Override public void onResume() {
        super.onResume();
        resumedAt = SystemClock.elapsedRealtime();
        handler.post(update);
    }

    @Override public void onPause() {
        handler.removeCallbacks(update);
        super.onPause();
    }
}
