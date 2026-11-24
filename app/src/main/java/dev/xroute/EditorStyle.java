package dev.xroute;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class EditorStyle {
    static final int BACKGROUND = 0xffedf3f1;
    static final int SURFACE = Color.WHITE;
    static final int INK = 0xff152b2a;
    static final int MUTED = 0xff485f5c;
    static final int STROKE = 0xffcbd9d4;
    static final int ACCENT = 0xff006c62;
    static final int ACCENT_LIGHT = 0xffd8f1e9;
    static final int WARNING = 0xff913533;

    private final Context context;

    EditorStyle(Context context) {
        this.context = context;
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    TextView label(CharSequence value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    TextView heading(String value) {
        TextView view = label(value, 16, INK, true);
        view.setAccessibilityHeading(true);
        return view;
    }

    Button button(String label, int ink, int fill, Runnable click) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setLetterSpacing(0);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][] {
                {-android.R.attr.state_enabled}, {}
        }, new int[] {0xff627773, ink}));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(9), dp(4), dp(9), dp(4));
        button.setStateListAnimator(null);
        StateListDrawable backgrounds = new StateListDrawable();
        backgrounds.addState(new int[] {-android.R.attr.state_enabled},
                shape(0xffe8eeec, 12));
        backgrounds.addState(new int[] {}, ripple(fill, 12));
        button.setBackground(backgrounds);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    void styleField(EditText field) {
        field.setTextSize(16);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setMinHeight(dp(48));
        StateListDrawable backgrounds = new StateListDrawable();
        backgrounds.addState(new int[] {android.R.attr.state_focused}, shape(SURFACE, 12, ACCENT));
        backgrounds.addState(new int[] {}, shape(BACKGROUND, 12, STROKE));
        field.setBackground(backgrounds);
    }

    GradientDrawable shape(int fill, int radiusDp) {
        return shape(fill, radiusDp, Color.TRANSPARENT);
    }

    GradientDrawable shape(int fill, int radiusDp, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(1), stroke);
        return shape;
    }

    RippleDrawable ripple(int fill, int radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x24152b2a), shape(fill, radiusDp), null);
    }

    void spacing(LinearLayout column, int dp) {
        column.addView(new android.widget.Space(context), new LinearLayout.LayoutParams(1, dp(dp)));
    }

    void weightedButton(LinearLayout row, Button button, float weight, int marginStart) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        params.setMarginStart(dp(marginStart));
        row.addView(button, params);
    }
}
