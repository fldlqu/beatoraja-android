package bms.player.beatoraja.settings;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal directory chooser used for BMS roots and resource folders.
 *
 * <p>Direct file system access keeps paths usable by the game's {@code java.nio.file} code. On recent
 * Android versions this needs the all-files access toggle, which the screen links to when missing.</p>
 */
public final class FolderPickerActivity extends AppCompatActivity {

    static final String EXTRA_TITLE = "bms.player.beatoraja.settings.FOLDER_TITLE";
    static final String EXTRA_START = "bms.player.beatoraja.settings.FOLDER_START";
    static final String EXTRA_RESULT = "bms.player.beatoraja.settings.FOLDER_RESULT";
    private static final int REQUEST_STORAGE = 41;

    private ListView list;
    private MaterialToolbar toolbar;
    private MaterialCardView permissionCard;
    private File current;

    static Intent intent(Context context, String title, String startPath) {
        final Intent intent = new Intent(context, FolderPickerActivity.class);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_START, startPath);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        final String title = getIntent().getStringExtra(EXTRA_TITLE);
        final String start = getIntent().getStringExtra(EXTRA_START);
        current = directoryOrFallback(start);
        buildUi(title == null ? "选择目录" : title);
    }

    private void buildUi(String title) {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.background(this));
        root.setFitsSystemWindows(false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        toolbar = new MaterialToolbar(this);
        toolbar.setTitle(title);
        toolbar.setTitleTextColor(UiKit.onSurface(this));
        toolbar.setSubtitleTextColor(UiKit.onSurfaceVariant(this));
        toolbar.setBackgroundColor(UiKit.background(this));
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(view -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 64)));

        permissionCard = UiKit.card(this);
        final LinearLayout permissionBody = UiKit.cardContent(permissionCard);
        permissionBody.addView(UiKit.text(this, "需要文件访问权限", 16f, UiKit.onSurface(this), true));
        permissionBody.addView(UiKit.hint(this, "浏览外部存储中的谱面目录需要“所有文件访问权限”；"
                + "未授权时只能浏览应用目录。"));
        final MaterialButton grant = UiKit.button(this, "授予文件访问权限", UiKit.ButtonStyle.FILLED);
        grant.setOnClickListener(view -> requestStorageAccess());
        final LinearLayout grantRow = new LinearLayout(this);
        grantRow.setPadding(0, UiKit.dp(this, 8), 0, 0);
        grantRow.addView(grant);
        permissionBody.addView(grantRow);
        final LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(UiKit.dp(this, 16), 0, UiKit.dp(this, 16), UiKit.dp(this, 8));
        root.addView(permissionCard, cardParams);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(UiKit.dp(this, 8), 0, UiKit.dp(this, 8), 0);
        list.setClipToPadding(false);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 12), UiKit.dp(this, 16), UiKit.dp(this, 16));
        final MaterialButton choose = UiKit.button(this, "使用当前目录", UiKit.ButtonStyle.FILLED);
        choose.setOnClickListener(view -> returnSelection());
        final MaterialButton cancel = UiKit.button(this, "取消", UiKit.ButtonStyle.OUTLINED);
        cancel.setOnClickListener(view -> finish());
        actions.addView(choose, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(this, 8), 1));
        actions.addView(spacer);
        actions.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actions);

        setContentView(root);
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_STORAGE) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        UiKit.snack(toolbar, granted ? "已授予访问权限" : "未授予访问权限");
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) refresh();
    }

    private boolean storageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                final Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            }
        } catch (RuntimeException e) {
            UiKit.snack(toolbar, "无法打开权限页面，请手动在系统设置中授权");
        }
    }

    private File directoryOrFallback(String start) {
        if (start != null && !start.isEmpty()) {
            final File file = new File(start);
            if (file.isDirectory()) return file.getAbsoluteFile();
        }
        final File external = getExternalFilesDir(null);
        if (external != null && external.getParentFile() != null) return external.getParentFile();
        return getFilesDir();
    }

    private void refresh() {
        final boolean allowed = storageAccess();
        permissionCard.setVisibility(allowed ? View.GONE : View.VISIBLE);
        if (!allowed) {
            current = getFilesDir();
        }
        toolbar.setSubtitle(current.getAbsolutePath());
        final List<File> directories = new ArrayList<>();
        if (current.getParentFile() != null) directories.add(current.getParentFile());
        final File[] children = current.listFiles(File::isDirectory);
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(file -> file.getName().toLowerCase()));
            for (File child : children) {
                if (child.getName().startsWith(".")) continue;
                final String absolute = child.getAbsolutePath();
                if (absolute.startsWith("/proc") || absolute.startsWith("/sys")) continue;
                directories.add(child);
            }
        }
        final List<String> rows = new ArrayList<>();
        for (File directory : directories) {
            if (directory == null) continue;
            rows.add(directory.equals(current.getParentFile())
                    ? "..   " + directory.getAbsolutePath()
                    : directory.getName() + "\n" + directory.getAbsolutePath());
        }
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, rows) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                final TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(UiKit.onSurface(getContext()));
                view.setTextSize(14f);
                view.setLineSpacing(0f, 1.15f);
                view.setPadding(UiKit.dp(getContext(), 16), UiKit.dp(getContext(), 14),
                        UiKit.dp(getContext(), 16), UiKit.dp(getContext(), 14));
                view.setBackgroundColor(position % 2 == 0
                        ? UiKit.surface(getContext()) : UiKit.background(getContext()));
                return view;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            final File selected = directories.get(position);
            if (selected == null) return;
            current = selected.getAbsoluteFile();
            refresh();
        });
    }

    private void returnSelection() {
        final String path = current.getAbsolutePath();
        try {
            if (!Files.isDirectory(Path.of(path))) throw new IllegalStateException();
        } catch (Exception e) {
            UiKit.snack(toolbar, "目录不可用");
            return;
        }
        final Intent result = new Intent();
        result.putExtra(EXTRA_RESULT, path);
        setResult(RESULT_OK, result);
        finish();
    }
}
