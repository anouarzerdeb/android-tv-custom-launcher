package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Theme or layout picker with a sidebar: the options on the right, and on the left a picture of Home as it
 * looks with the focused option (previews/theme-<stem>.jpg, previews/layout-<slug>.jpg, made on the PC).
 * OK applies the option and returns RESULT_OK, so Launcher settings and then Home rebuild.
 */
public final class PreviewPickerActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String MODE_THEME = "theme", MODE_LAYOUT = "layout";
    static final String THEME_KEY = "theme_preset", LAYOUT_KEY = "layout_choice";

    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private ImageView preview;
    private TextView caption;
    private volatile String wanted = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = MODE_LAYOUT.equals(getIntent().getStringExtra(EXTRA_MODE)) ? MODE_LAYOUT : MODE_THEME;
        SharedPreferences choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);

        List<String> titles = new ArrayList<>(), previews = new ArrayList<>();
        List<File> themes = ConfigWriter.themes(this);
        if (MODE_THEME.equals(mode)) {
            for (File f : themes) {
                titles.add(ConfigWriter.themeTitle(f.getName()));
                previews.add("theme-" + f.getName().replaceFirst("\\.json$", ""));
            }
        } else {
            for (String n : ConfigWriter.LAYOUT_NAMES) {
                titles.add(n);
                previews.add("layout-" + ConfigWriter.slug(n));
            }
        }
        if (titles.isEmpty()) {
            Toast.makeText(this, R.string.theme_missing, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String current = choices.getString(MODE_THEME.equals(mode) ? THEME_KEY : LAYOUT_KEY, "");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Sidebar: a dark glass panel down the right edge.
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(28), dp(36), dp(28), dp(28));
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xCC0B0E14, 0xF20B0E14});
        side.setBackground(panel);
        FrameLayout.LayoutParams sideLp = new FrameLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.MATCH_PARENT);
        sideLp.gravity = Gravity.END;
        root.addView(side, sideLp);

        TextView title = new TextView(this);
        title.setText(MODE_THEME.equals(mode) ? R.string.theme_label : R.string.layout_label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        side.addView(title);
        caption = new TextView(this);
        caption.setTextColor(0x99FFFFFF);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        caption.setPadding(0, dp(4), 0, dp(16));
        side.addView(caption);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        side.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View focusFirst = null;
        for (int i = 0; i < titles.size(); i++) {
            final int index = i;
            TextView row = new TextView(this, null, 0, R.style.SettingsRow);
            row.setText(titles.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.topMargin = dp(10);
            list.addView(row, lp);
            boolean isCurrent = MODE_THEME.equals(mode) ? themes.get(i).getName().equals(current)
                    : titles.get(i).equals(current);
            row.setSelected(isCurrent);
            if (isCurrent || focusFirst == null) focusFirst = row;
            row.setOnFocusChangeListener((v, has) -> {
                if (has) show(previews.get(index), titles.get(index));
            });
            row.setOnClickListener(v -> {
                boolean ok = MODE_THEME.equals(mode) ? ConfigWriter.applyTheme(this, themes.get(index))
                        : ConfigWriter.patch(this, ConfigWriter.layout(index));
                if (!ok) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
                    return;
                }
                choices.edit().putString(MODE_THEME.equals(mode) ? THEME_KEY : LAYOUT_KEY,
                        MODE_THEME.equals(mode) ? themes.get(index).getName() : titles.get(index)).commit();
                Toast.makeText(this, R.string.theme_applied, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        }
        setContentView(root);
        View first = focusFirst;
        first.post(first::requestFocus);
    }

    /** Decodes the focused option's picture off the main thread; the latest focus wins. */
    private void show(String name, String title) {
        wanted = name;
        File file = ConfigWriter.preview(this, name);
        if (file == null) {
            preview.setImageDrawable(null);
            caption.setText(getString(R.string.preview_missing));
            return;
        }
        caption.setText(title);
        decoder.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
            runOnUiThread(() -> {
                if (!isFinishing() && name.equals(wanted)) preview.setImageBitmap(b);
            });
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }
}
