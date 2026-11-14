package com.example.ytdl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** NOVA library for completed and interrupted media downloads. */
public final class HistoryActivity extends Activity {
    static final String EXTRA_START_IMPORT = "nova_start_video_import";
    private static final int PICK_VIDEO = 7301;
    private boolean importing;
    private final List<DownloadService.Task> items = new ArrayList<>();
    private HistoryAdapter adapter;
    private TextView summary;
    private EditText search;
    private Spinner category;
    private Spinner sort;
    private Spinner source;
    private Set<Long> possibleDuplicates = Collections.emptySet();

    private String tr(String english, String arabic) {
        return NovaPreferences.text(this, english, arabic);
    }

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);
        Ui.applyChrome(this);
        DownloadService.ensureLoaded(this);
        setContentView(buildUi());
        refresh();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_START_IMPORT, false)) {
            getIntent().removeExtra(EXTRA_START_IMPORT);
            openVideoPicker();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(NovaPreferences.arabic(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 18),
                Ui.dp(this, 18), Ui.dp(this, 10));

        final LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);

        final TextView back = Ui.headline(this, 25);
        back.setText("←");
        back.setTextColor(Ui.ACCENT);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(tr("Back to Explore and Transfers", "العودة للبحث والتنزيلات"));
        back.setBackground(Ui.pressable(this, Ui.SURFACE, Ui.ACCENT_SOFT, 15));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(
                Ui.dp(this, 48), Ui.dp(this, 48)));

        final TextView brand = Ui.eyebrow(this, tr("NOVA / YOUR LIBRARY", "نوفا / مكتبتك"));
        final LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1f);
        brandParams.leftMargin = Ui.dp(this, 13);
        top.addView(brand, brandParams);
        root.addView(top);

        final LinearLayout heading = Ui.section(this,
                tr("LIBRARY / 03", "المكتبة / ٠٣"),
                tr("Your collection", "مكتبتك"),
                tr("Everything you've saved, with the option to open or share.",
                        "كل ما حفظته في مكان واحد، جاهز للتشغيل والمشاركة."));
        final LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = Ui.dp(this, 18);
        headingParams.bottomMargin = Ui.dp(this, 18);
        root.addView(heading, headingParams);

        summary = Ui.mono(this, 13, Ui.TEXT);
        summary.setBackground(Ui.hero(this));
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(Ui.dp(this, 17), Ui.dp(this, 15),
                Ui.dp(this, 17), Ui.dp(this, 15));
        summary.setMinHeight(Ui.dp(this, 65));
        final LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.bottomMargin = Ui.dp(this, 14);
        root.addView(summary, summaryParams);

        final android.widget.Button importVideo = Ui.button(this,
                tr("＋ Import a video from your device",
                        "＋ استيراد فيديو من جهازك"), true);
        importVideo.setContentDescription(tr("Import a saved local video",
                "استيراد فيديو محفوظ على الجهاز"));
        importVideo.setOnClickListener(v -> openVideoPicker());
        final LinearLayout.LayoutParams importParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        importParams.bottomMargin = Ui.dp(this, 12);
        root.addView(importVideo, importParams);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTED);
        search.setTextSize(Ui.size(14));
        search.setHint(tr("Search saved files, titles or artists",
                "ابحث في الملفات أو العناوين أو الفنانين"));
        search.setBackground(Ui.box(this, Ui.SURFACE_RAISED, Ui.LINE, 15));
        search.setPadding(Ui.dp(this, 13), 0, Ui.dp(this, 13), 0);
        final LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                -1, Ui.dp(this, 52));
        searchParams.bottomMargin = Ui.dp(this, 10);
        root.addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                refresh();
            }
            @Override public void afterTextChanged(Editable value) {}
        });

        final LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        category = selector(new String[]{
                tr("All files", "الكل"), tr("Videos", "فيديو"),
                tr("Audio", "صوت"), tr("Subtitles", "ترجمة"),
                tr("Needs attention", "تحتاج متابعة"),
                tr("Favorites", "المفضلة"),
                tr("Possible duplicates", "تكرارات محتملة")
        });
        sort = selector(new String[]{
                tr("Newest first", "الأحدث أولاً"), tr("Oldest first", "الأقدم أولاً"),
                tr("By title", "حسب العنوان")
        });
        tools.addView(category, new LinearLayout.LayoutParams(0, Ui.dp(this, 49), 1.1f));
        final LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 49), 1f);
        sortParams.leftMargin = Ui.dp(this, 7);
        tools.addView(sort, sortParams);
        final LinearLayout.LayoutParams toolParams = new LinearLayout.LayoutParams(-1, -2);
        toolParams.bottomMargin = Ui.dp(this, 12);
        root.addView(tools, toolParams);

        source = selector(new String[]{
                tr("Every source", "جميع المصادر"), tr("YouTube", "يوتيوب"),
                tr("SoundCloud", "ساوندكلاود"), tr("X / Twitter", "إكس / تويتر"),
                tr("Spotify matches", "مطابقات سبوتيفاي"),
                tr("Local imports", "ملفات مستوردة"), tr("Other", "أخرى")
        });
        final LinearLayout.LayoutParams sourceParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 49));
        sourceParams.bottomMargin = Ui.dp(this, 12);
        root.addView(source, sourceParams);

        final AdapterView.OnItemSelectedListener changed = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) { refresh(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        category.setOnItemSelectedListener(changed);
        sort.setOnItemSelectedListener(changed);
        source.setOnItemSelectedListener(changed);

        final ListView list = new ListView(this);
        adapter = new HistoryAdapter();
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 12));
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= items.size()) return;
            final DownloadService.Task task = items.get(position);
            if (task.state == DownloadService.State.FAILED
                    || task.state == DownloadService.State.CANCELLED) {
                DownloadService.retry(this, task);
                Toast.makeText(this, tr("Added back to Transfers", "أضيف إلى التنزيلات مجددًا"),
                        Toast.LENGTH_SHORT).show();
                finish();
            } else if (task.state == DownloadService.State.DONE && task.outputUri != null) {
                if (LocalMediaPlayerActivity.canPlay(task)) playSaved(task);
                else openSaved(task);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private void openVideoPicker() {
        if (importing) return;
        final Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("video/*");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(picker, PICK_VIDEO);
        } catch (android.content.ActivityNotFoundException unavailable) {
            Toast.makeText(this, tr("No file picker installed",
                    "لا يوجد تطبيق لاختيار الملفات"), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_VIDEO || resultCode != RESULT_OK
                || data == null || data.getData() == null || importing) return;
        importing = true;
        final android.net.Uri chosen = data.getData();
        Toast.makeText(this, tr("Importing selected video…", "جاري استيراد الفيديو…"),
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            android.net.Uri importedUri = null;
            try {
                final LocalVideoImport.Imported imported = LocalVideoImport.copy(this, chosen);
                importedUri = imported.uri;
                DownloadService.registerImported(this, imported);
                runOnUiThread(() -> {
                    importing = false;
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, tr("Video imported into Library",
                            "تم استيراد الفيديو إلى المكتبة"), Toast.LENGTH_LONG).show();
                    refresh();
                });
            } catch (Exception error) {
                // If queue persistence fails after publishing, avoid an untracked
                // duplicate in Movies/NOVA.
                if (importedUri != null) {
                    try { getContentResolver().delete(importedUri, null, null); }
                    catch (RuntimeException ignored) {}
                }
                final String message = error.getMessage();
                runOnUiThread(() -> {
                    importing = false;
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this,
                            tr("Video import failed: ", "تعذر استيراد الفيديو: ")
                                    + (message == null ? "" : message),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "nova-video-import").start();
    }

    private Spinner selector(String[] labels) {
        final Spinner control = new Spinner(this);
        final ArrayAdapter<String> values = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        values.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        control.setAdapter(values);
        control.setBackground(Ui.box(this, Ui.SURFACE_RAISED, Ui.LINE, 14));
        return control;
    }

    private void refresh() {
        final List<DownloadService.Task> all = DownloadService.snapshot();
        int saved = 0;
        int failed = 0;
        for (final DownloadService.Task task : all) {
            if (task.state == DownloadService.State.DONE) saved++;
            if (task.state == DownloadService.State.FAILED) failed++;
        }
        possibleDuplicates = LibraryOrganizer.possibleDuplicateIds(all);
        final int selectedCategory = category == null ? 0 : category.getSelectedItemPosition();
        final int selectedSource = source == null ? 0 : source.getSelectedItemPosition();
        final List<DownloadService.Task> visible = LibraryFilter.select(all,
                search == null ? "" : search.getText().toString(),
                selectedCategory == 6 ? LibraryFilter.ALL : selectedCategory,
                sort == null ? 0 : sort.getSelectedItemPosition(),
                task -> LibraryFavorites.isFavorite(this, task));
        items.clear();
        for (final DownloadService.Task task : visible) {
            if (selectedSource != LibraryOrganizer.ALL_SOURCES
                    && LibraryOrganizer.sourceOf(task) != selectedSource) continue;
            if (selectedCategory == 6 && !possibleDuplicates.contains(task.id)) continue;
            items.add(task);
        }
        if (summary != null) {
            summary.setText(tr("✦  " + saved + " saved    ·    " + failed
                            + " need attention\n" + items.size() + " visible items · "
                            + possibleDuplicates.size() + " possible duplicate entries",
                    "✦  " + saved + " محفوظ    ·    " + failed
                            + " تحتاج متابعة\n" + items.size() + " عناصر ظاهرة · "
                            + possibleDuplicates.size() + " عناصر محتملة التكرار"));
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private TextView action(final String label, final int color, final View.OnClickListener click) {
        final TextView button = Ui.chip(this, label, color);
        button.setMinHeight(Ui.dp(this, 48));
        button.setMinWidth(Ui.dp(this, 48));
        button.setBackground(Ui.pressable(this, Ui.SURFACE_RAISED, Ui.ACCENT_SOFT, 14));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(click);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
        params.rightMargin = Ui.dp(this, 9);
        button.setLayoutParams(params);
        return button;
    }

    private void openSaved(final DownloadService.Task task) {
        try {
            final android.net.Uri uri = android.net.Uri.parse(task.outputUri);
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, task.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (final Exception e) {
            Toast.makeText(this, "No app available to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void playSaved(final DownloadService.Task task) {
        if (!LocalMediaPlayerActivity.canPlay(task)) return;
        startActivity(LocalMediaPlayerActivity.intentFor(this, task));
    }

    private void toggleFavorite(final DownloadService.Task task) {
        LibraryFavorites.toggle(this, task);
        refresh();
    }

    private void shareSaved(final DownloadService.Task task) {
        try {
            final android.net.Uri uri = android.net.Uri.parse(task.outputUri);
            final Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType(task.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share download"));
        } catch (final Exception e) {
            Toast.makeText(this, "Could not share this file", Toast.LENGTH_SHORT).show();
        }
    }

    private final class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.max(1, items.size()); }
        @Override public Object getItem(final int p) {
            return p < items.size() ? items.get(p) : null;
        }
        @Override public long getItemId(final int p) { return p; }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            if (items.isEmpty()) {
                final LinearLayout empty = Ui.section(HistoryActivity.this,
                        tr("YOUR SPACE", "مساحتك"),
                        tr("Nothing matches yet", "لا توجد نتائج"),
                        tr("Try another search or category.", "جرّب البحث بكلمات أخرى أو غيّر التصنيف."));
                empty.setPadding(Ui.dp(HistoryActivity.this, 18),
                        Ui.dp(HistoryActivity.this, 26), Ui.dp(HistoryActivity.this, 18),
                        Ui.dp(HistoryActivity.this, 26));
                empty.setBackground(Ui.box(HistoryActivity.this, Ui.SURFACE, Ui.LINE, 20));
                return empty;
            }

            final DownloadService.Task task = items.get(position);
            final LinearLayout card = new LinearLayout(HistoryActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.box(HistoryActivity.this, Ui.SURFACE, Ui.LINE, 20));
            card.setPadding(Ui.dp(HistoryActivity.this, 16), Ui.dp(HistoryActivity.this, 15),
                    Ui.dp(HistoryActivity.this, 16), Ui.dp(HistoryActivity.this, 15));

            final LinearLayout heading = new LinearLayout(HistoryActivity.this);
            heading.setOrientation(LinearLayout.HORIZONTAL);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            final TextView title = Ui.headline(HistoryActivity.this, 15);
            title.setText((LibraryFavorites.isFavorite(HistoryActivity.this, task)
                    ? "★  " : "") + (task.title == null
                    ? tr("Untitled download", "تنزيل بلا عنوان") : task.title));
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

            final int color = task.state == DownloadService.State.DONE
                    ? Ui.OK : task.state == DownloadService.State.FAILED ? Ui.ERR : Ui.MUTED;
            final TextView state = Ui.chip(HistoryActivity.this, task.state.label, color);
            final LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
            badgeParams.leftMargin = Ui.dp(HistoryActivity.this, 9);
            heading.addView(state, badgeParams);
            card.addView(heading);

            final TextView detail = Ui.sans(HistoryActivity.this, 12, Ui.MUTED);
            detail.setText(task.formatLabel()
                    + (possibleDuplicates.contains(task.id)
                    ? tr(" · Possible duplicate, review manually",
                            " · تكرار محتمل، راجع الملف يدويًا") : "")
                    + (task.state == DownloadService.State.FAILED
                    ? tr(" · Tap to retry", " · اضغط لإعادة المحاولة") : ""));
            final LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = Ui.dp(HistoryActivity.this, 8);
            card.addView(detail, detailParams);

            if (task.state == DownloadService.State.DONE && task.outputUri != null) {
                final LinearLayout actions = new LinearLayout(HistoryActivity.this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setPadding(0, Ui.dp(HistoryActivity.this, 12), 0, 0);
                if (LocalMediaPlayerActivity.canPlay(task)) {
                    actions.addView(action(tr("Play in NOVA", "تشغيل في نوفا"), Ui.OK,
                            v -> playSaved(task)));
                } else {
                    actions.addView(action(tr("Open file", "فتح"), Ui.OK,
                            v -> openSaved(task)));
                }
                if (LocalMediaPlayerActivity.canPlay(task)) {
                    actions.addView(action(tr("Open externally", "فتح خارجي"), Ui.MUTED,
                            v -> openSaved(task)));
                }
                actions.addView(action(tr("Share", "مشاركة"), Ui.ACCENT,
                        v -> shareSaved(task)));
                final boolean starred = LibraryFavorites.isFavorite(HistoryActivity.this, task);
                actions.addView(action(starred ? tr("★ Saved", "★ مفضلة")
                                : tr("☆ Favorite", "☆ المفضلة"), Ui.SECONDARY,
                        v -> toggleFavorite(task)));
                final android.widget.HorizontalScrollView actionScroll =
                        new android.widget.HorizontalScrollView(HistoryActivity.this);
                actionScroll.setHorizontalScrollBarEnabled(false);
                actionScroll.setFillViewport(false);
                actionScroll.addView(actions);
                card.addView(actionScroll);
            }
            final LinearLayout wrap = new LinearLayout(HistoryActivity.this);
            wrap.setPadding(0, 0, 0, Ui.dp(HistoryActivity.this, 11));
            wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
            return wrap;
        }
    }
}
