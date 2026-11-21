package bms.player.beatoraja.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Programmatic Material 3 building blocks for the settings screen.
 *
 * <p>Widgets are Material Components (cards, switches, chips, sliders, text fields, dialogs) so the
 * screen follows the platform look on every device while the dynamic parts stay easy to build.</p>
 */
final class UiKit {

    enum ButtonStyle { FILLED, TONAL, OUTLINED, TEXT }

    static int dp(Context context, float value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private static int color(Context context, int attr, int fallback) {
        try {
            return MaterialColors.getColor(context, attr, fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static int background(Context context) {
        return color(context, com.google.android.material.R.attr.colorSurface, Color.rgb(14, 16, 21));
    }

    static int surface(Context context) {
        return color(context, com.google.android.material.R.attr.colorSurfaceContainer, Color.rgb(23, 26, 33));
    }

    static int surfaceHigh(Context context) {
        return color(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.rgb(32, 36, 45));
    }

    static int onSurface(Context context) {
        return color(context, com.google.android.material.R.attr.colorOnSurface, Color.rgb(230, 234, 242));
    }

    static int onSurfaceVariant(Context context) {
        return color(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.rgb(167, 174, 187));
    }

    static int primary(Context context) {
        return color(context, com.google.android.material.R.attr.colorPrimary, Color.rgb(168, 199, 250));
    }

    static int secondary(Context context) {
        return color(context, com.google.android.material.R.attr.colorSecondary, Color.rgb(130, 213, 198));
    }

    static int error(Context context) {
        return color(context, com.google.android.material.R.attr.colorError, Color.rgb(255, 180, 171));
    }

    static int outline(Context context) {
        return color(context, com.google.android.material.R.attr.colorOutline, Color.rgb(44, 49, 64));
    }

    static TextView text(Context context, CharSequence value, float size, int color, boolean bold) {
        final TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    static TextView section(Context context, String title) {
        final TextView view = text(context, title, 13f, primary(context), true);
        view.setLetterSpacing(0.1f);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(context, 6), dp(context, 26), dp(context, 6), dp(context, 8));
        view.setLayoutParams(params);
        return view;
    }

    /** Card container with the Material 3 tonal surface look. */
    static MaterialCardView card(Context context) {
        final MaterialCardView card = new MaterialCardView(context);
        card.setCardBackgroundColor(surface(context));
        card.setStrokeColor(outline(context));
        card.setStrokeWidth(dp(context, 1));
        card.setRadius(dp(context, 22));
        card.setCardElevation(0f);
        card.setContentPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 18));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(context, 4), 0, dp(context, 4));
        card.setLayoutParams(params);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        card.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setTag(content);
        return card;
    }

    /** Adds a view to a card created by {@link #card(Context)}. */
    static void addToCard(MaterialCardView card, View view) {
        ((LinearLayout) card.getTag()).addView(view);
    }

    static LinearLayout cardContent(MaterialCardView card) {
        return (LinearLayout) card.getTag();
    }

    static LinearLayout row(Context context) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 10), 0, dp(context, 10));
        return row;
    }

    static TextView label(Context context, CharSequence value) {
        final TextView label = text(context, value, 15f, onSurface(context), false);
        label.setSingleLine(false);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(0, 0, dp(context, 12), 0);
        label.setLayoutParams(params);
        return label;
    }

    static TextView hint(Context context, CharSequence value) {
        final TextView hint = text(context, value, 12.5f, onSurfaceVariant(context), false);
        hint.setPadding(0, dp(context, 3), 0, dp(context, 3));
        return hint;
    }

    static LinearLayout row(Context context, String title, View control) {
        final LinearLayout row = row(context);
        row.addView(label(context, title));
        row.addView(control);
        return row;
    }

    static void divider(Context context, LinearLayout parent) {
        final View line = new View(context);
        line.setBackgroundColor(outline(context));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 0.6f)));
        params.setMargins(0, dp(context, 4), 0, dp(context, 4));
        line.setLayoutParams(params);
        parent.addView(line);
    }

    static LinearLayout switchRow(Context context, String title, boolean checked, String hint,
            MaterialSwitch.OnCheckedChangeListener listener) {
        final LinearLayout row = row(context);
        row.addView(label(context, title));
        final MaterialSwitch toggle = new MaterialSwitch(context);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle);
        final LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row);
        if (hint != null && !hint.isEmpty()) wrapper.addView(hint(context, hint));
        return wrapper;
    }

    static LinearLayout editRow(Context context, String title, String value, String hint, int inputType,
            Consumer<String> listener) {
        final TextInputLayout layout = new TextInputLayout(context);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxCornerRadii(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        layout.setBoxStrokeColor(outline(context));
        layout.setHintTextColor(ColorStateList.valueOf(onSurfaceVariant(context)));
        layout.setHint(title);
        final TextInputEditText edit = new TextInputEditText(context);
        edit.setTextSize(14f);
        edit.setTextColor(onSurface(context));
        edit.setHint(hint);
        edit.setHintTextColor(onSurfaceVariant(context));
        edit.setInputType(inputType);
        edit.setSingleLine(true);
        edit.setText(value == null ? "" : value);
        edit.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) listener.accept(edit.getText() == null ? "" : edit.getText().toString());
        });
        layout.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, dp(context, 8), 0, dp(context, 4));
        wrapper.addView(layout);
        return wrapper;
    }

    /** A compact dropdown field: tapping opens a single-choice Material dialog. */
    static final class Dropdown {
        private final Context context;
        private final LinearLayout view;
        private final TextView valueText;
        private List<String> labels = new ArrayList<>();
        private Consumer<Integer> listener;
        private String title = "";
        private int selected;

        private Dropdown(Context context) {
            this.context = context;
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(context, 16), dp(context, 12), dp(context, 12), dp(context, 12));
            final com.google.android.material.shape.MaterialShapeDrawable fieldBackground =
                    new com.google.android.material.shape.MaterialShapeDrawable();
            fieldBackground.setFillColor(ColorStateList.valueOf(surfaceHigh(context)));
            fieldBackground.setStroke((float) dp(context, 1), ColorStateList.valueOf(outline(context)));
            fieldBackground.setShapeAppearanceModel(fieldBackground.getShapeAppearanceModel().toBuilder()
                    .setAllCornerSizes(dp(context, 14)).build());
            view.setBackground(fieldBackground);
            view.setClickable(true);
            view.setFocusable(true);
            valueText = text(context, "", 14f, onSurface(context), false);
            valueText.setSingleLine(true);
            valueText.setGravity(Gravity.CENTER_VERTICAL);
            valueText.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                    bms.player.beatoraja.R.drawable.ic_expand_more, 0);
            valueText.setCompoundDrawableTintList(ColorStateList.valueOf(onSurfaceVariant(context)));
            valueText.setCompoundDrawablePadding(dp(context, 6));
            valueText.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            view.addView(valueText);
            view.setOnClickListener(v -> showDialog());
        }

        Dropdown title(String title) {
            this.title = title;
            return this;
        }

        void bind(List<String> values, int selectedIndex, Consumer<Integer> listener) {
            this.labels = new ArrayList<>(values);
            this.listener = listener;
            this.selected = Math.max(0, Math.min(labels.size() - 1, selectedIndex));
            valueText.setText(labels.isEmpty() ? "" : labels.get(this.selected));
        }

        String value() {
            return labels.isEmpty() ? "" : labels.get(selected);
        }

        int selectedIndex() {
            return selected;
        }

        View view() {
            return view;
        }

        private void showDialog() {
            if (labels.isEmpty()) return;
            new MaterialAlertDialogBuilder(context)
                    .setTitle(title)
                    .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
                        selected = which;
                        valueText.setText(labels.get(which));
                        if (listener != null) listener.accept(which);
                        dialog.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    static Dropdown dropdown(Context context) {
        return new Dropdown(context);
    }

    static LinearLayout dropdownRow(Context context, String title, Dropdown dropdown) {
        return row(context, title, dropdown.title(title).view());
    }

    /** Slider row with a live value label. */
    static LinearLayout seekRow(Context context, String title, int initial, String suffix,
            Consumer<Integer> listener) {
        final LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, dp(context, 8), 0, dp(context, 4));
        final LinearLayout header = row(context);
        header.addView(label(context, title));
        final TextView value = text(context, initial + suffix, 13f, onSurfaceVariant(context), false);
        header.addView(value);
        wrapper.addView(header);
        final Slider slider = new Slider(context);
        slider.setValueFrom(0f);
        slider.setValueTo(100f);
        slider.setValue(Math.max(0, Math.min(100, initial)));
        slider.setMinimumHeight(dp(context, 40));
        slider.addOnChangeListener((s, v, fromUser) -> {
            final int progress = Math.round(v);
            value.setText(progress + suffix);
            if (fromUser) listener.accept(progress);
        });
        wrapper.addView(slider);
        return wrapper;
    }

    static MaterialButton button(Context context, String title, ButtonStyle style) {
        final int styleAttribute = switch (style) {
            case TONAL -> bms.player.beatoraja.R.attr.beatorajaButtonTonalStyle;
            case OUTLINED -> bms.player.beatoraja.R.attr.beatorajaButtonOutlinedStyle;
            case TEXT -> bms.player.beatoraja.R.attr.beatorajaButtonTextStyle;
            default -> com.google.android.material.R.attr.materialButtonStyle;
        };
        final MaterialButton button = new MaterialButton(context, null, styleAttribute);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14f);
        return button;
    }

    static Chip chip(Context context, String text) {
        final Chip chip = new Chip(context);
        chip.setText(text);
        chip.setCheckable(false);
        chip.setClickable(false);
        chip.setChipBackgroundColor(ColorStateList.valueOf(surfaceHigh(context)));
        chip.setTextColor(onSurface(context));
        chip.setCloseIconVisible(true);
        chip.setCloseIconTint(ColorStateList.valueOf(onSurfaceVariant(context)));
        return chip;
    }

    static ChipGroup chipGroup(Context context) {
        final ChipGroup group = new ChipGroup(context);
        group.setChipSpacingHorizontal(dp(context, 8));
        group.setChipSpacingVertical(dp(context, 8));
        group.setSingleLine(false);
        return group;
    }

    static void snack(View anchor, String message) {
        if (anchor != null) {
            Snackbar.make(anchor, message, Snackbar.LENGTH_SHORT).show();
        }
    }

    static int px(Context context, int dp) {
        return dp(context, dp);
    }

    static int colorFromAttr(Context context, int attr) {
        return color(context, attr, 0);
    }

    static ArrayAdapter<String> simpleAdapter(Context context, List<String> values) {
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_list_item_1, values);
        adapter.setDropDownViewResource(android.R.layout.simple_list_item_1);
        return adapter;
    }

    static MaterialAlertDialogBuilder dialog(Context context) {
        return new MaterialAlertDialogBuilder(context);
    }

    static int tint(Context context, int colorResource) {
        return ContextCompat.getColor(context, colorResource);
    }
}
