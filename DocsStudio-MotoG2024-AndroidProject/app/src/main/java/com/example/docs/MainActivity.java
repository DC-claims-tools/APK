package com.example.docs;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Docs Studio: offline document suite with slide-out navigation drawer,
 * fixed white-paper/sepia/night PDF rendering, markup tools, and Office support.
 */
public class MainActivity extends Activity {
    private static final int OPEN_FILE = 41, SAVE_AS = 42;

    // Refined Slate & Teal Professional Palette
    private static final int BG = Color.rgb(11, 17, 32);
    private static final int SURFACE = Color.rgb(17, 24, 39);
    private static final int CARD = Color.rgb(30, 41, 59);
    private static final int BORDER = Color.rgb(51, 65, 85);
    private static final int INK = Color.rgb(241, 245, 249);
    private static final int MUTED = Color.rgb(148, 163, 184);
    private static final int BRAND = Color.rgb(20, 184, 166);
    private static final int PALE = Color.rgb(19, 78, 74);
    private static final int ON_BRAND = Color.rgb(4, 47, 46);

    private LinearLayout content, recentHolder;
    private FrameLayout drawerOverlay;
    private LinearLayout drawerPanel;
    private View drawerScrim;
    private boolean drawerOpen = false;

    private Uri currentUri;
    private String currentName = "", kind = "";
    private EditText textEditor;
    private OfficeEngine.WordDoc wordDoc;
    private OfficeEngine.Spreadsheet workbook;
    private OfficeEngine.Presentation slides;
    private int sheetIndex = 0, firstRow = 1, firstCol = 1, slideIndex = 0;
    private byte[] pendingSave;
    private PdfZoomView pdfView;
    private LinearLayout pdfToolbar, pdfTools;
    private TextView pageIndicator, pdfThemeButton;
    private boolean pdfToolbarVisible = true;
    private boolean pdfExportPending = false;
    private final Map<Integer, TextView> pdfToolButtons = new LinkedHashMap<>();
    private boolean pendingWork = false;
    private boolean hasUnsavedChanges = false;
    private String originalText = "";
    private boolean openedFromExternal = false;
    private OnBackInvokedCallback backCallback;
    private int loadGeneration = 0;
    private int homeTab = 0; // 0 = Workspace, 1 = Recent, 2 = Starred, 3 = About
    private long editRevision = 0, pendingSaveRevision = 0;
    private boolean monospaceText = false;
    private int editorFontSize = 15;
    private int defaultPdfTheme = PdfZoomView.THEME_PAPER;

    @Override
    public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        defaultPdfTheme = getPreferences(MODE_PRIVATE).getInt("pdf_theme", PdfZoomView.THEME_PAPER);
        configureSystemBars();
        showHome();
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        openFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromIntent(intent);
    }

    private void openFromIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action)) return;
        Uri uri = Intent.ACTION_VIEW.equals(action) ? intent.getData()
                : (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0)
            uri = intent.getClipData().getItemAt(0).getUri();
        if (uri == null) {
            error("No document was provided by the other app.");
            return;
        }
        grantReadIfPossible(uri, intent);
        openedFromExternal = true;
        open(uri, fileName(uri));
    }

    private void grantReadIfPossible(Uri uri, Intent source) {
        if ((source.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
    }

    private void configureSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void setSafeContentView(View screenContent) {
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(BG);
        shell.addView(screenContent, new FrameLayout.LayoutParams(-1, -1));
        buildSideDrawer(shell);

        shell.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = windowInsets.getInsets(WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = Math.max(bars.bottom, keyboard.bottom);
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
            }
            if (view.getPaddingLeft() != left || view.getPaddingTop() != top
                    || view.getPaddingRight() != right || view.getPaddingBottom() != bottom) {
                view.setPadding(left, top, right, bottom);
            }
            return windowInsets;
        });
        setContentView(shell);
        shell.requestApplyInsets();
    }

    // --- Slide-Out Side Navigation Menu ---
    private void buildSideDrawer(FrameLayout shell) {
        drawerOpen = false;
        drawerOverlay = new FrameLayout(this);
        drawerOverlay.setVisibility(View.GONE);

        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(Color.argb(165, 2, 6, 23));
        drawerScrim.setAlpha(0f);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        drawerOverlay.addView(drawerScrim, new FrameLayout.LayoutParams(-1, -1));

        drawerPanel = column();
        drawerPanel.setBackgroundColor(SURFACE);
        drawerPanel.setElevation(dp(16));
        int drawerWidth = Math.min(dp(300), Math.round(getResources().getDisplayMetrics().widthPixels * 0.84f));
        drawerPanel.setTranslationX(-drawerWidth);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(18), dp(16), dp(24));

        // Drawer Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text("D", 18, ON_BRAND, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(shape(BRAND, 11, 0));
        header.addView(badge, lp(38, 38));
        LinearLayout titles = column();
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("Docs Studio", 17, INK, true));
        titles.addView(text("Offline Document Suite", 12, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView closeBtn = text("✕", 18, MUTED, true);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setOnClickListener(v -> closeDrawer());
        header.addView(closeBtn, lp(38, 38));
        body.addView(header);

        View hr = new View(this);
        hr.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams hrp = lp(-1, 1);
        hrp.topMargin = dp(14);
        hrp.bottomMargin = dp(14);
        body.addView(hr, hrp);

        drawerSection(body, "WORKSPACE");
        drawerItem(body, "All Documents", homeTab == 0 && kind.isEmpty(), () -> { closeDrawer(); confirmLeaveToTab(0); });
        drawerItem(body, "Recent Files", homeTab == 1 && kind.isEmpty(), () -> { closeDrawer(); confirmLeaveToTab(1); });
        drawerItem(body, "Starred", homeTab == 2 && kind.isEmpty(), () -> { closeDrawer(); confirmLeaveToTab(2); });
        drawerItem(body, "Browse Device Files", false, () -> { closeDrawer(); selectFile(); });

        drawerSection(body, "CREATE NEW");
        drawerItem(body, "New Text Note (.txt)", false, () -> {
            closeDrawer();
            createNewDoc("Untitled.txt", "text", "");
        });
        drawerItem(body, "New Markdown (.md)", false, () -> {
            closeDrawer();
            createNewDoc("Notes.md", "text", "# Document Title\n\nWrite your notes here.\n");
        });
        drawerItem(body, "New Spreadsheet (.csv)", false, () -> {
            closeDrawer();
            createNewDoc("Sheet.csv", "csv", "Item,Category,Amount\n,,\n,,");
        });

        if ("pdf".equals(kind) && pdfView != null) {
            drawerSection(body, "PDF TOOLS");
            drawerItem(body, "Go to Page…", false, () -> { closeDrawer(); promptGoToPage(); });
            drawerItem(body, "Reading Theme (Paper / Sepia / Night)", false, () -> { closeDrawer(); chooseReadingTheme(); });
            drawerItem(body, "Rotate Page 90°", false, () -> {
                closeDrawer();
                pdfView.rotateClockwise();
                toast("Rotated " + pdfView.getRotationDegrees() + "°");
            });
            drawerItem(body, "Zoom 150%", false, () -> { closeDrawer(); pdfView.setZoomPreset(1.5f); });
            drawerItem(body, "Fit Page", false, () -> { closeDrawer(); pdfView.resetZoom(); });
            drawerItem(body, "Print PDF", false, () -> { closeDrawer(); printCurrentPdf(); });
            drawerItem(body, "Document Details", false, () -> { closeDrawer(); showPdfInfoDialog(); });
            drawerItem(body, "Export Annotated PDF", false, () -> { closeDrawer(); savePdfCopy(); });
        } else if ("text".equals(kind) || "csv".equals(kind) || "docx".equals(kind)) {
            drawerSection(body, "DOCUMENT TOOLS");
            drawerItem(body, "Find & Replace…", false, () -> { closeDrawer(); promptFindAndReplace(); });
            drawerItem(body, "Word & Character Count", false, () -> { closeDrawer(); showTextStatsDialog(); });
            drawerItem(body, monospaceText ? "Use Standard Font" : "Use Monospace Font", false, () -> {
                closeDrawer();
                monospaceText = !monospaceText;
                if (textEditor != null) textEditor.setTypeface(monospaceText ? Typeface.MONOSPACE : Typeface.DEFAULT);
            });
            drawerItem(body, "Cycle Text Size (" + editorFontSize + "sp)", false, () -> {
                closeDrawer();
                editorFontSize = editorFontSize == 13 ? 15 : (editorFontSize == 15 ? 18 : 13);
                if (textEditor != null) textEditor.setTextSize(editorFontSize);
                toast("Text size: " + editorFontSize + "sp");
            });
            drawerItem(body, "Share Text", false, () -> { closeDrawer(); shareCurrentText(); });
            drawerItem(body, "Save Copy", false, () -> { closeDrawer(); saveCopy(); });
        } else if ("xlsx".equals(kind) && workbook != null) {
            drawerSection(body, "SPREADSHEET TOOLS");
            drawerItem(body, "Switch Sheet…", false, () -> { closeDrawer(); promptChooseSheet(); });
            drawerItem(body, "Export Current Sheet as CSV", false, () -> { closeDrawer(); exportSheetAsCsv(); });
            drawerItem(body, "Save Edited XLSX Copy", false, () -> { closeDrawer(); saveCopy(); });
        } else if ("pptx".equals(kind) && slides != null) {
            drawerSection(body, "PRESENTATION TOOLS");
            drawerItem(body, "Jump to Slide…", false, () -> { closeDrawer(); promptChooseSlide(); });
            drawerItem(body, "Save Edited PPTX Copy", false, () -> { closeDrawer(); saveCopy(); });
        }

        drawerSection(body, "SETTINGS & HELP");
        drawerItem(body, "Default PDF Paper Theme", false, () -> { closeDrawer(); chooseDefaultPdfTheme(); });
        drawerItem(body, "Clear Recent History", false, () -> {
            closeDrawer();
            getPreferences(MODE_PRIVATE).edit().remove("recent").apply();
            renderRecent("");
            toast("Recent history cleared");
        });
        drawerItem(body, "About Docs Studio", homeTab == 3 && kind.isEmpty(), () -> { closeDrawer(); confirmLeaveToTab(3); });

        scroller.addView(body);
        drawerPanel.addView(scroller, new LinearLayout.LayoutParams(-1, -1));
        drawerOverlay.addView(drawerPanel, new FrameLayout.LayoutParams(drawerWidth, -1, Gravity.START));
        shell.addView(drawerOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void drawerSection(LinearLayout parent, String title) {
        TextView label = text(title, 11, MUTED, true);
        label.setLetterSpacing(0.10f);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(12);
        p.bottomMargin = dp(6);
        parent.addView(label, p);
    }

    private void drawerItem(LinearLayout parent, String label, boolean active, Runnable onClick) {
        TextView item = text(label, 14, active ? BRAND : INK, active);
        item.setPadding(dp(12), dp(11), dp(12), dp(11));
        item.setBackground(shape(active ? PALE : Color.TRANSPARENT, 10, 0));
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.bottomMargin = dp(2);
        parent.addView(item, p);
        item.setOnClickListener(v -> onClick.run());
    }

    private void openDrawer() {
        if (drawerOverlay == null || drawerOpen) return;
        drawerOpen = true;
        drawerOverlay.setVisibility(View.VISIBLE);
        ObjectAnimator.ofFloat(drawerPanel, "translationX", drawerPanel.getTranslationX(), 0f)
                .setDuration(220).start();
        ObjectAnimator.ofFloat(drawerScrim, "alpha", drawerScrim.getAlpha(), 1f)
                .setDuration(220).start();
    }

    private void closeDrawer() {
        if (drawerOverlay == null || !drawerOpen) return;
        drawerOpen = false;
        int w = drawerPanel.getWidth() > 0 ? drawerPanel.getWidth() : dp(300);
        ObjectAnimator slide = ObjectAnimator.ofFloat(drawerPanel, "translationX", drawerPanel.getTranslationX(), -w);
        slide.setDuration(190);
        slide.setInterpolator(new DecelerateInterpolator());
        slide.start();
        ObjectAnimator fade = ObjectAnimator.ofFloat(drawerScrim, "alpha", drawerScrim.getAlpha(), 0f);
        fade.setDuration(190);
        fade.start();
        drawerOverlay.postDelayed(() -> {
            if (!drawerOpen && drawerOverlay != null) drawerOverlay.setVisibility(View.GONE);
        }, 195);
    }

    private void confirmLeaveToTab(int tab) {
        if (kind.isEmpty()) {
            showHomeTab(tab);
            return;
        }
        if (hasUnsavedChanges) {
            new AlertDialog.Builder(this).setTitle("Discard unsaved changes?")
                    .setMessage("Save a copy first if you want to keep your edits.")
                    .setPositiveButton("Discard", (d, w) -> {
                        resetDocState();
                        showHomeTab(tab);
                    })
                    .setNegativeButton("Stay", null).show();
        } else {
            resetDocState();
            showHomeTab(tab);
        }
    }

    private void createNewDoc(String name, String docKind, String initial) {
        resetDocState();
        currentName = name;
        kind = docKind;
        showText(initial);
    }

    private void resetDocState() {
        loadGeneration++;
        closePdf();
        currentUri = null;
        currentName = "";
        kind = "";
        pendingWork = false;
        hasUnsavedChanges = false;
        pendingSave = null;
        editRevision = 0;
        wordDoc = null;
        workbook = null;
        slides = null;
        textEditor = null;
        openedFromExternal = false;
    }

    private int dp(float number) { return (int) (number * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radius));
        if (stroke != 0) gd.setStroke(dp(1), stroke);
        return gd;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private void space(int height) {
        View v = new View(this);
        content.addView(v, lp(1, height));
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private void screen(String title, String subtitle, boolean back) {
        recentHolder = null;
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(BG);
        content = column();
        content.setPadding(dp(20), dp(20), dp(20), dp(28));
        sc.addView(content);
        setSafeContentView(sc);
        if (back) {
            TextView go = text("‹  All documents", 15, BRAND, true);
            go.setPadding(0, dp(4), 0, dp(16));
            go.setOnClickListener(v -> navigateBack());
            content.addView(go);
        }
        content.addView(text(title, 26, INK, true));
        if (!subtitle.isEmpty()) {
            TextView sub = text(subtitle, 14, MUTED, false);
            sub.setPadding(0, dp(6), 0, 0);
            content.addView(sub);
        }
        space(20);
    }

    private void helper(String message) {
        TextView v = text(message, 13, MUTED, false);
        v.setLineSpacing(dp(3), 1);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(8);
        p.bottomMargin = dp(12);
        content.addView(v, p);
    }

    private Button action(String label, boolean primary) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(15);
        btn.setTextColor(primary ? ON_BRAND : BRAND);
        btn.setBackground(shape(primary ? BRAND : PALE, 12, primary ? 0 : BORDER));
        btn.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams p = lp(-1, 48);
        p.bottomMargin = dp(12);
        btn.setLayoutParams(p);
        content.addView(btn);
        return btn;
    }

    private void showHome() {
        resetDocState();
        showHomeTab(0);
    }

    private void showHomeTab(int tab) {
        homeTab = tab;
        LinearLayout root = column();
        root.setBackgroundColor(BG);

        LinearLayout appBar = new LinearLayout(this);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        appBar.setPadding(dp(12), dp(10), dp(14), dp(10));
        appBar.setBackgroundColor(SURFACE);

        TextView menuBtn = text("☰", 22, INK, true);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setOnClickListener(v -> openDrawer());
        appBar.addView(menuBtn, lp(44, 44));

        TextView appName = text("Docs Studio", 19, INK, true);
        appName.setPadding(dp(8), 0, 0, 0);
        appBar.addView(appName, new LinearLayout.LayoutParams(0, -2, 1));

        TextView openBtn = text("Open File", 13, ON_BRAND, true);
        openBtn.setGravity(Gravity.CENTER);
        openBtn.setBackground(shape(BRAND, 10, 0));
        openBtn.setOnClickListener(v -> selectFile());
        appBar.addView(openBtn, lp(88, 38));

        root.addView(appBar, lp(-1, -2));
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        root.addView(line, lp(-1, 1));

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(BG);
        content = column();
        content.setPadding(dp(18), dp(20), dp(18), dp(24));
        sc.addView(content);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        if (tab == 0) drawHome();
        else if (tab == 1) drawRecents(false);
        else if (tab == 2) drawRecents(true);
        else drawAbout();

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(SURFACE);
        nav.setPadding(dp(10), dp(6), dp(10), dp(8));
        String[] labels = {"Workspace", "Recent", "Starred", "About"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView item = text(labels[i], 12, tab == i ? BRAND : MUTED, tab == i);
            item.setGravity(Gravity.CENTER);
            item.setBackground(shape(tab == i ? PALE : Color.TRANSPARENT, 10, 0));
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(42), 1));
            item.setOnClickListener(v -> { if (homeTab != index) showHomeTab(index); });
        }
        View navTop = new View(this);
        navTop.setBackgroundColor(BORDER);
        root.addView(navTop, lp(-1, 1));
        root.addView(nav, lp(-1, -2));

        setSafeContentView(root);
    }

    private TextView sectionTitle(String title) {
        TextView v = text(title, 16, INK, true);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.bottomMargin = dp(10);
        content.addView(v, p);
        return v;
    }

    private TextView monoIcon(String letters, int foreground, int background, int width) {
        TextView v = text(letters, letters.length() > 2 ? 11 : 14, foreground, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(shape(background, 10, 0));
        v.setLayoutParams(lp(width, width));
        return v;
    }

    private void drawHome() {
        LinearLayout hero = column();
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        hero.setBackground(shape(CARD, 14, BORDER));
        hero.addView(text("Document Workspace", 21, INK, true));
        TextView copy = text("Open PDFs with crisp white paper, Sepia, or Night mode. Edit DOCX, XLSX, PPTX, TXT, and CSV offline.", 13, MUTED, false);
        copy.setLineSpacing(dp(3), 1f);
        copy.setPadding(0, dp(6), 0, dp(14));
        hero.addView(copy);

        TextView browse = text("Browse Device Storage  →", 14, ON_BRAND, true);
        browse.setGravity(Gravity.CENTER);
        browse.setBackground(shape(BRAND, 10, 0));
        browse.setOnClickListener(v -> selectFile());
        hero.addView(browse, lp(-1, 44));
        LinearLayout.LayoutParams hp = lp(-1, -2);
        hp.bottomMargin = dp(20);
        content.addView(hero, hp);

        sectionTitle("Create New");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        quickCard(row, "TXT", "Text Note", BRAND, PALE, () -> createNewDoc("Untitled.txt", "text", ""));
        quickCard(row, "MD", "Markdown", Color.rgb(129, 140, 248), Color.rgb(30, 27, 75), () -> createNewDoc("Notes.md", "text", "# Heading\n\n"));
        quickCard(row, "CSV", "Table", Color.rgb(74, 222, 128), Color.rgb(20, 83, 45), () -> createNewDoc("Sheet.csv", "csv", "Column A,Column B,Column C\n,,\n,,"));
        content.addView(row, lp(-1, -2));
        space(22);

        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.addView(text("Recent Documents", 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView seeAll = text("See all", 13, BRAND, true);
        seeAll.setOnClickListener(v -> showHomeTab(1));
        section.addView(seeAll);
        LinearLayout.LayoutParams sp = lp(-1, -2);
        sp.bottomMargin = dp(10);
        content.addView(section, sp);

        recentHolder = column();
        content.addView(recentHolder, lp(-1, -2));
        renderRecent("");
    }

    private void quickCard(LinearLayout row, String symbol, String title, int ink, int tint, Runnable onTap) {
        LinearLayout box = column();
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setBackground(shape(CARD, 12, BORDER));
        box.addView(monoIcon(symbol, ink, tint, 36));
        TextView heading = text(title, 13, INK, true);
        heading.setPadding(0, dp(10), 0, 0);
        box.addView(heading);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(98), 1);
        p.rightMargin = dp(6);
        row.addView(box, p);
        box.setOnClickListener(v -> onTap.run());
    }

    private void drawRecents(boolean starredOnly) {
        sectionTitle(starredOnly ? "Starred Documents" : "Recent Files");
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(INK);
        search.setHint("Filter by filename…");
        search.setHintTextColor(MUTED);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(shape(CARD, 10, BORDER));
        content.addView(search, lp(-1, 46));
        space(14);
        recentHolder = column();
        content.addView(recentHolder, lp(-1, -2));
        renderRecentList("", starredOnly);
        search.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) { renderRecentList(s.toString(), starredOnly); }
        });
    }

    private void drawAbout() {
        sectionTitle("About Docs Studio");
        infoCard("Fixed PDF Rendering", "PDF pages render on an isolated white paper canvas (with optional Sepia and luminance-inverted Night modes) so black text never disappears on dark backgrounds.");
        infoCard("Side Menu & Tools", "Tap the menu icon (☰) anywhere to access starred files, Find & Replace, page jump, rotation, Android PrintManager printing, and document stats.");
        infoCard("Local Privacy", "All files are opened and processed locally on your device with no network uploads.");
    }

    private void infoCard(String title, String body) {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(shape(CARD, 12, BORDER));
        box.addView(text(title, 15, INK, true));
        TextView desc = text(body, 13, MUTED, false);
        desc.setLineSpacing(dp(3), 1f);
        desc.setPadding(0, dp(6), 0, 0);
        box.addView(desc);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.bottomMargin = dp(10);
        content.addView(box, p);
    }

    private void renderRecent(String filter) { renderRecentList(filter, false); }

    private void renderRecentList(String filter, boolean starredOnly) {
        if (recentHolder == null) return;
        recentHolder.removeAllViews();
        JSONArray arr = recentArray();
        int count = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name"), url = o.optString("uri");
            boolean starred = o.optBoolean("starred", false);
            if (starredOnly && !starred) continue;
            if (!name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) continue;

            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) : "FILE";
            if (ext.length() > 4) ext = "FILE";
            int accent = BRAND, tint = PALE;
            if ("PDF".equals(ext)) { accent = Color.rgb(248, 113, 113); tint = Color.rgb(69, 26, 35); }
            if ("DOCX".equals(ext)) { accent = Color.rgb(96, 165, 250); tint = Color.rgb(30, 58, 95); }
            if ("XLSX".equals(ext) || "CSV".equals(ext)) { accent = Color.rgb(74, 222, 128); tint = Color.rgb(20, 83, 45); }
            if ("PPTX".equals(ext)) { accent = Color.rgb(251, 146, 60); tint = Color.rgb(67, 40, 24); }

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(10), dp(10));
            row.setBackground(shape(CARD, 12, BORDER));
            row.addView(monoIcon(ext, accent, tint, 40));

            LinearLayout names = column();
            names.setPadding(dp(12), 0, dp(6), 0);
            TextView filename = text(name, 14, INK, true);
            filename.setSingleLine(true);
            filename.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            names.addView(filename);
            names.addView(text(ext + " document", 12, MUTED, false));
            row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));

            TextView starBtn = text(starred ? "★" : "☆", 18, starred ? Color.rgb(250, 204, 21) : MUTED, false);
            starBtn.setGravity(Gravity.CENTER);
            starBtn.setOnClickListener(v -> {
                toggleStar(url);
                renderRecentList(filter, starredOnly);
            });
            row.addView(starBtn, lp(38, 38));

            LinearLayout.LayoutParams p = lp(-1, -2);
            p.bottomMargin = dp(8);
            recentHolder.addView(row, p);
            row.setOnClickListener(v -> {
                openedFromExternal = false;
                open(Uri.parse(url), name);
            });
            count++;
        }
        if (count == 0) {
            LinearLayout empty = column();
            empty.setPadding(dp(18), dp(22), dp(18), dp(22));
            empty.setBackground(shape(CARD, 12, BORDER));
            TextView emptyLabel = text(starredOnly ? "No starred files yet" : (filter.isEmpty() ? "No recent documents" : "No matching files"), 14, INK, true);
            emptyLabel.setGravity(Gravity.CENTER);
            empty.addView(emptyLabel);
            recentHolder.addView(empty);
        }
    }

    private JSONArray recentArray() {
        try { return new JSONArray(getPreferences(MODE_PRIVATE).getString("recent", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private void remember(Uri uri, String name) {
        try {
            JSONArray old = recentArray(), next = new JSONArray();
            boolean wasStarred = false;
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && uri.toString().equals(item.optString("uri"))) {
                    wasStarred = item.optBoolean("starred", false);
                }
            }
            JSONObject first = new JSONObject();
            first.put("uri", uri.toString());
            first.put("name", name);
            first.put("starred", wasStarred);
            next.put(first);
            for (int i = 0; i < old.length() && next.length() < 16; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && !uri.toString().equals(item.optString("uri"))) next.put(item);
            }
            getPreferences(MODE_PRIVATE).edit().putString("recent", next.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void toggleStar(String uriStr) {
        try {
            JSONArray arr = recentArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && uriStr.equals(o.optString("uri"))) {
                    o.put("starred", !o.optBoolean("starred", false));
                }
            }
            getPreferences(MODE_PRIVATE).edit().putString("recent", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void selectFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, OPEN_FILE);
    }

    private String fileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return "Document";
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == SAVE_AS) { pendingSave = null; pdfExportPending = false; }
            return;
        }
        Uri uri = data.getData();
        if (request == OPEN_FILE) {
            openedFromExternal = false;
            grantReadIfPossible(uri, data);
            open(uri, fileName(uri));
        } else if (request == SAVE_AS) {
            if (pdfExportPending) {
                pdfExportPending = false;
                exportPdfCopy(uri);
                return;
            }
            byte[] toSave = pendingSave;
            pendingSave = null;
            if (toSave == null) return;
            String savedName = fileName(uri);
            new Thread(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("Storage location is not writable.");
                    out.write(toSave);
                    out.flush();
                    runOnUiThread(() -> {
                        remember(uri, savedName);
                        if (editRevision == pendingSaveRevision) {
                            if (textEditor != null) originalText = textEditor.getText().toString();
                            hasUnsavedChanges = false;
                        }
                        toast("Saved copy: " + savedName);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> error("Could not save the file: " + e.getMessage()));
                }
            }).start();
        }
    }

    private String type(String name, Uri uri) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "pdf";
        if (n.endsWith(".docx")) return "docx";
        if (n.endsWith(".xlsx")) return "xlsx";
        if (n.endsWith(".pptx")) return "pptx";
        if (n.endsWith(".csv")) return "csv";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".log") || n.endsWith(".html"))
            return "text";
        String mimetype = null;
        try { mimetype = getContentResolver().getType(uri); } catch (Exception ignored) {}
        if (mimetype == null) return "unsupported";
        switch (mimetype.toLowerCase(Locale.ROOT)) {
            case "application/pdf": return "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation": return "pptx";
            case "text/csv": case "application/csv": return "csv";
            case "text/plain": return "text";
            default: return "unsupported";
        }
    }

    private void open(Uri uri, String name) {
        loadGeneration++;
        pendingWork = false;
        closePdf();
        currentUri = uri;
        currentName = name;
        kind = type(name, uri);
        hasUnsavedChanges = false;
        editRevision = 0;
        if ("unsupported".equals(kind)) {
            screen(name, "Unsupported document format", true);
            helper("Supported formats: PDF, DOCX, XLSX, PPTX, TXT, MD, CSV, JSON, XML.");
            action("Open another file", true).setOnClickListener(v -> selectFile());
            return;
        }
        remember(uri, name);
        if ("pdf".equals(kind)) { showPdf(); return; }
        pendingWork = true;
        final int generation = loadGeneration;
        final String openingType = kind;
        screen("Opening document", "Reading " + name + "…", true);
        ProgressBar spinner = new ProgressBar(this);
        content.addView(spinner, lp(48, 48));
        new Thread(() -> {
            try {
                byte[] data = readBytes(uri, 24 * 1024 * 1024);
                if ("docx".equals(openingType)) {
                    OfficeEngine.WordDoc doc = OfficeEngine.word(data);
                    runOnUiThread(() -> { if (generation != loadGeneration) return; pendingWork = false; wordDoc = doc; showText(doc.text()); });
                } else if ("xlsx".equals(openingType)) {
                    OfficeEngine.Spreadsheet book = OfficeEngine.spreadsheet(data);
                    runOnUiThread(() -> { if (generation != loadGeneration) return; pendingWork = false; workbook = book; sheetIndex = 0; firstRow = 1; firstCol = 1; showSheet(); });
                } else if ("pptx".equals(openingType)) {
                    OfficeEngine.Presentation pres = OfficeEngine.presentation(data);
                    runOnUiThread(() -> { if (generation != loadGeneration) return; pendingWork = false; slides = pres; slideIndex = 0; showSlide(); });
                } else {
                    String value = new String(data, StandardCharsets.UTF_8);
                    runOnUiThread(() -> { if (generation != loadGeneration) return; pendingWork = false; showText(value); });
                }
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration) return;
                    pendingWork = false;
                    screen(currentName, "Cannot open this file", true);
                    helper(ex.getMessage() == null ? "Unsupported or damaged file." : ex.getMessage());
                    action("Choose another file", true).setOnClickListener(v -> selectFile());
                });
            }
        }).start();
    }

    private byte[] readBytes(Uri uri, int max) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Could not access this document.");
            byte[] buf = new byte[8192];
            int count;
            while ((count = in.read(buf)) != -1) {
                if (out.size() + count > max) throw new IOException("File exceeds 24 MB limit.");
                out.write(buf, 0, count);
            }
            return out.toByteArray();
        }
    }

    private EditText editor(String initial, int minLines) {
        EditText field = new EditText(this);
        field.setText(initial);
        field.setTextSize(editorFontSize);
        field.setTypeface(monospaceText ? Typeface.MONOSPACE : Typeface.DEFAULT);
        field.setTextColor(INK);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(minLines);
        field.setPadding(dp(14), dp(14), dp(14), dp(14));
        field.setBackground(shape(CARD, 10, BORDER));
        field.setHorizontallyScrolling(false);
        return field;
    }

    private LinearLayout readerScreen(String title) {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(10), 0);
        bar.setBackgroundColor(SURFACE);

        TextView menuBtn = text("☰", 21, INK, true);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setOnClickListener(v -> openDrawer());
        bar.addView(menuBtn, lp(44, 52));

        TextView back = text("‹", 28, MUTED, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> navigateBack());
        bar.addView(back, lp(34, 52));

        TextView heading = text(title, 15, INK, true);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(heading, new LinearLayout.LayoutParams(0, dp(52), 1));

        TextView saveTop = text("Save", 13, ON_BRAND, true);
        saveTop.setGravity(Gravity.CENTER);
        saveTop.setBackground(shape(BRAND, 9, 0));
        saveTop.setOnClickListener(v -> saveCopy());
        bar.addView(saveTop, lp(64, 34));

        root.addView(bar, lp(-1, 52));
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        root.addView(divider, lp(-1, 1));
        return root;
    }

    private void showText(String initial) {
        originalText = initial;
        hasUnsavedChanges = false;
        LinearLayout root = readerScreen(currentName);
        textEditor = editor(initial, 8);
        textEditor.setBackgroundColor(BG);
        textEditor.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(textEditor, new LinearLayout.LayoutParams(-1, 0, 1));
        textEditor.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                editRevision++;
                hasUnsavedChanges = !originalText.equals(s.toString());
            }
        });
        setSafeContentView(root);
    }

    private void saveCopy() {
        if (pendingWork) return;
        if ("pdf".equals(kind) || "unsupported".equals(kind)) return;
        final String selectedType = kind, filename = currentName;
        final String text = (textEditor == null ? "" : textEditor.getText().toString());
        pendingSaveRevision = editRevision;
        pendingWork = true;
        new Thread(() -> {
            try {
                byte[] output;
                if ("docx".equals(selectedType)) output = wordDoc.save(text);
                else if ("xlsx".equals(selectedType)) output = workbook.save();
                else if ("pptx".equals(selectedType)) output = slides.save();
                else output = text.getBytes(StandardCharsets.UTF_8);
                runOnUiThread(() -> {
                    pendingWork = false;
                    pendingSave = output;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(mime(selectedType));
                    i.putExtra(Intent.EXTRA_TITLE, copyName(filename, selectedType));
                    startActivityForResult(i, SAVE_AS);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> { pendingWork = false; error("Could not prepare copy: " + ex.getMessage()); });
            }
        }).start();
    }

    private void exportSheetAsCsv() {
        if (workbook == null) return;
        String csv = workbook.toCsv(sheetIndex);
        pendingSave = csv.getBytes(StandardCharsets.UTF_8);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, workbook.sheets.get(sheetIndex).name + ".csv");
        startActivityForResult(i, SAVE_AS);
    }

    private String copyName(String name, String type) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem + "-edited" + (dot > 0 ? name.substring(dot) : ("csv".equals(type) ? ".csv" : ".txt"));
    }

    private String mime(String type) {
        switch (type) {
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "csv": return "text/csv";
            default: return "text/plain";
        }
    }

    private void showSheet() {
        if (workbook == null) return;
        OfficeEngine.Sheet sheet = workbook.sheets.get(sheetIndex);
        LinearLayout root = readerScreen(currentName);
        ScrollView sv = new ScrollView(this);
        content = column();
        content.setPadding(dp(12), dp(12), dp(12), dp(16));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        content.addView(text(sheet.name + " · Rows " + firstRow + "–" + (firstRow + 19), 15, INK, true));
        space(10);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button prevS = mini("‹ Sheet"), nextS = mini("Sheet ›"), up = mini("↑ Rows"), down = mini("↓ Rows");
        controls.addView(prevS, new LinearLayout.LayoutParams(0, dp(40), 1));
        controls.addView(nextS, new LinearLayout.LayoutParams(0, dp(40), 1));
        controls.addView(up, new LinearLayout.LayoutParams(0, dp(40), 1));
        controls.addView(down, new LinearLayout.LayoutParams(0, dp(40), 1));
        content.addView(controls);
        prevS.setOnClickListener(v -> { sheetIndex = (sheetIndex - 1 + workbook.sheets.size()) % workbook.sheets.size(); firstRow = 1; firstCol = 1; showSheet(); });
        nextS.setOnClickListener(v -> { sheetIndex = (sheetIndex + 1) % workbook.sheets.size(); firstRow = 1; firstCol = 1; showSheet(); });
        up.setOnClickListener(v -> { firstRow = Math.max(1, firstRow - 20); showSheet(); });
        down.setOnClickListener(v -> { firstRow += 20; showSheet(); });
        space(10);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout grid = column();
        grid.setBackground(shape(SURFACE, 8, BORDER));
        hsv.addView(grid);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(gridText("", 48, true));
        for (int c = firstCol; c < firstCol + 8; c++)
            header.addView(gridText(OfficeEngine.cellRef(1, c).replace("1", ""), 115, true));
        grid.addView(header);
        for (int r = firstRow; r < firstRow + 20; r++) {
            final int rowNo = r;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(gridText(String.valueOf(r), 48, true));
            for (int c = firstCol; c < firstCol + 8; c++) {
                final int colNo = c;
                String old = sheet.get(r, c);
                EditText cell = new EditText(this);
                cell.setSingleLine(true);
                cell.setTextSize(13);
                cell.setTextColor(INK);
                cell.setText(old);
                cell.setPadding(dp(8), 0, dp(6), 0);
                cell.setBackground(shape(BG, 0, BORDER));
                row.addView(cell, lp(115, 40));
                cell.addTextChangedListener(new SimpleWatcher() {
                    @Override public void afterTextChanged(Editable s) {
                        if (!old.equals(s.toString())) { hasUnsavedChanges = true; editRevision++; }
                        sheet.set(rowNo, colNo, s.toString());
                    }
                });
            }
            grid.addView(row);
        }
        content.addView(hsv, lp(-1, -2));
        setSafeContentView(root);
    }

    private TextView gridText(String value, int width, boolean strong) {
        TextView v = text(value, 12, INK, strong);
        v.setGravity(Gravity.CENTER);
        v.setBackground(shape(CARD, 0, BORDER));
        v.setLayoutParams(lp(width, 40));
        return v;
    }

    private Button mini(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setTextColor(BRAND);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(shape(CARD, 8, BORDER));
        return b;
    }

    private void showSlide() {
        if (slides == null) return;
        OfficeEngine.Slide slide = slides.slides.get(slideIndex);
        LinearLayout root = readerScreen(currentName);
        ScrollView sv = new ScrollView(this);
        content = column();
        content.setPadding(dp(14), dp(14), dp(14), dp(20));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        content.addView(text(slide.name + " of " + slides.slides.size(), 17, INK, true));
        space(10);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = mini("‹ Previous Slide"), next = mini("Next Slide ›");
        nav.addView(prev, new LinearLayout.LayoutParams(0, dp(42), 1));
        nav.addView(next, new LinearLayout.LayoutParams(0, dp(42), 1));
        content.addView(nav);
        space(14);
        prev.setOnClickListener(v -> { slideIndex = (slideIndex - 1 + slides.slides.size()) % slides.slides.size(); showSlide(); });
        next.setOnClickListener(v -> { slideIndex = (slideIndex + 1) % slides.slides.size(); showSlide(); });
        int count = 0;
        for (int i = 0; i < slide.texts.size(); i++) {
            String original = slide.texts.get(i);
            if (original.trim().isEmpty()) continue;
            final int shapeIndex = i;
            content.addView(text("Text Box " + (++count), 12, MUTED, true));
            space(4);
            EditText field = editor(slide.edits.getOrDefault(i, original), 2);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.bottomMargin = dp(12);
            content.addView(field, p);
            field.addTextChangedListener(new SimpleWatcher() {
                @Override public void afterTextChanged(Editable s) {
                    if (s.toString().equals(original)) slide.edits.remove(shapeIndex);
                    else { slide.edits.put(shapeIndex, s.toString()); hasUnsavedChanges = true; editRevision++; }
                }
            });
        }
        if (count == 0) helper("This slide contains no editable text boxes.");
        setSafeContentView(root);
    }

    // --- PDF Viewer with Side Menu, Reading Theme Toggle, Rotation, and Markup ---
    private void showPdf() {
        ParcelFileDescriptor fd = null;
        PdfRenderer renderer = null;
        try {
            fd = getContentResolver().openFileDescriptor(currentUri, "r");
            if (fd == null) throw new IOException("Cannot access this PDF.");
            renderer = new PdfRenderer(fd);
            if (renderer.getPageCount() < 1) throw new IOException("This PDF contains no pages.");

            LinearLayout root = column();
            root.setBackgroundColor(BG);

            pdfToolbar = column();
            pdfToolbar.setBackgroundColor(SURFACE);
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(6), 0, dp(10), 0);

            TextView menuBtn = text("☰", 21, INK, true);
            menuBtn.setGravity(Gravity.CENTER);
            menuBtn.setOnClickListener(v -> openDrawer());
            header.addView(menuBtn, lp(42, 50));

            TextView back = text("‹", 28, MUTED, false);
            back.setGravity(Gravity.CENTER);
            back.setOnClickListener(v -> navigateBack());
            header.addView(back, lp(32, 50));

            TextView title = text(currentName, 14, INK, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            title.setGravity(Gravity.CENTER_VERTICAL);
            header.addView(title, new LinearLayout.LayoutParams(0, dp(50), 1));

            pdfThemeButton = text(themeLabel(defaultPdfTheme), 11, BRAND, true);
            pdfThemeButton.setGravity(Gravity.CENTER);
            pdfThemeButton.setBackground(shape(PALE, 8, BORDER));
            pdfThemeButton.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams tp = lp(-2, 34);
            tp.rightMargin = dp(6);
            header.addView(pdfThemeButton, tp);

            TextView export = text("Export", 12, ON_BRAND, true);
            export.setGravity(Gravity.CENTER);
            export.setBackground(shape(BRAND, 8, 0));
            export.setOnClickListener(v -> savePdfCopy());
            header.addView(export, lp(64, 34));

            pdfToolbar.addView(header, lp(-1, 50));
            root.addView(pdfToolbar, lp(-1, -2));

            pdfView = new PdfZoomView(this, renderer, fd);
            pdfView.setReadingTheme(defaultPdfTheme);
            root.addView(pdfView, new LinearLayout.LayoutParams(-1, 0, 1));
            renderer = null;
            fd = null;

            pdfThemeButton.setOnClickListener(v -> {
                int next = (pdfView.getReadingTheme() + 1) % 3;
                pdfView.setReadingTheme(next);
                pdfThemeButton.setText(themeLabel(next));
            });

            pdfTools = column();
            pdfTools.setBackgroundColor(SURFACE);
            View separator = new View(this);
            separator.setBackgroundColor(BORDER);
            pdfTools.addView(separator, lp(-1, 1));

            LinearLayout pager = new LinearLayout(this);
            pager.setGravity(Gravity.CENTER_VERTICAL);
            pager.setPadding(dp(10), dp(4), dp(10), dp(4));
            TextView prev = pdfAction("‹ Prev", false), next = pdfAction("Next ›", false);
            pageIndicator = text("1 / " + pdfView.getPageCount(), 13, INK, true);
            pageIndicator.setGravity(Gravity.CENTER);
            pageIndicator.setOnClickListener(v -> promptGoToPage());
            TextView rotBtn = pdfAction("Rotate", false);
            TextView fit = pdfAction("Fit", false);
            pager.addView(prev, lp(68, 34));
            pager.addView(pageIndicator, new LinearLayout.LayoutParams(0, dp(34), 1));
            pager.addView(next, lp(68, 34));
            LinearLayout.LayoutParams rp = lp(58, 34);
            rp.leftMargin = dp(6);
            pager.addView(rotBtn, rp);
            LinearLayout.LayoutParams fp = lp(44, 34);
            fp.leftMargin = dp(6);
            pager.addView(fit, fp);
            pdfTools.addView(pager, lp(-1, 42));

            prev.setOnClickListener(v -> pdfView.previous());
            next.setOnClickListener(v -> pdfView.next());
            rotBtn.setOnClickListener(v -> pdfView.rotateClockwise());
            fit.setOnClickListener(v -> pdfView.resetZoom());

            HorizontalScrollView toolsScroller = new HorizontalScrollView(this);
            toolsScroller.setHorizontalScrollBarEnabled(false);
            LinearLayout toolRow = new LinearLayout(this);
            toolRow.setGravity(Gravity.CENTER_VERTICAL);
            toolRow.setPadding(dp(8), dp(2), dp(10), dp(8));
            toolsScroller.addView(toolRow);
            pdfToolButtons.clear();
            pdfMode(toolRow, "Read", PdfZoomView.VIEW);
            pdfMode(toolRow, "Pen", PdfZoomView.PEN);
            pdfMode(toolRow, "Highlight", PdfZoomView.HIGHLIGHT);
            pdfMode(toolRow, "Arrow", PdfZoomView.ARROW);
            pdfMode(toolRow, "Sign", PdfZoomView.SIGN);
            pdfMode(toolRow, "Text", PdfZoomView.TEXT);
            pdfMode(toolRow, "Underline", PdfZoomView.UNDERLINE);
            pdfMode(toolRow, "Box", PdfZoomView.RECTANGLE);
            pdfMode(toolRow, "Erase", PdfZoomView.ERASER);
            pdfActionChip(toolRow, "Undo", () -> pdfView.undo());
            pdfActionChip(toolRow, "Redo", () -> pdfView.redo());
            pdfActionChip(toolRow, "Color", this::selectPdfColor);
            pdfActionChip(toolRow, "Size", () -> {
                pdfView.setPenSize((pdfView.getPenSize() % 5) + 1);
                toast("Stroke size: " + pdfView.getPenSize() + "/5");
            });
            pdfActionChip(toolRow, "Clear", () -> new AlertDialog.Builder(this)
                    .setTitle("Clear page annotations?")
                    .setPositiveButton("Clear", (d, w) -> pdfView.clearCurrentPage())
                    .setNegativeButton("Cancel", null).show());
            pdfTools.addView(toolsScroller, lp(-1, 46));
            root.addView(pdfTools, lp(-1, -2));
            pdfToolbarVisible = true;

            pdfView.setPageListener(new PdfZoomView.PageListener() {
                @Override public void onPageChanged(int page, int count) {
                    if (pageIndicator != null) pageIndicator.setText(page + " / " + count);
                }
                @Override public void onTap() {
                    if (pdfView == null || pdfView.getTool() != PdfZoomView.VIEW) return;
                    pdfToolbarVisible = !pdfToolbarVisible;
                    if (pdfToolbar != null) pdfToolbar.setVisibility(pdfToolbarVisible ? View.VISIBLE : View.GONE);
                    if (pdfTools != null) pdfTools.setVisibility(pdfToolbarVisible ? View.VISIBLE : View.GONE);
                }
                @Override public void onError(String message) { toast("PDF: " + message); }
                @Override public void onAnnotationsChanged() { hasUnsavedChanges = true; editRevision++; }
            });
            setSafeContentView(root);
        } catch (Exception e) {
            if (renderer != null) try { renderer.close(); } catch (Exception ignored) {}
            if (fd != null) try { fd.close(); } catch (Exception ignored) {}
            closePdf();
            screen(currentName, "Unable to display PDF", true);
            helper(e.getMessage() == null ? "This PDF could not be opened." : e.getMessage());
        }
    }

    private String themeLabel(int theme) {
        if (theme == PdfZoomView.THEME_SEPIA) return "Sepia";
        if (theme == PdfZoomView.THEME_NIGHT) return "Night";
        return "Paper";
    }

    private void promptGoToPage() {
        if (pdfView == null) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 – " + pdfView.getPageCount());
        new AlertDialog.Builder(this).setTitle("Jump to Page")
                .setView(input)
                .setPositiveButton("Go", (d, w) -> {
                    try {
                        int page = Integer.parseInt(input.getText().toString().trim());
                        pdfView.setPage(Math.max(0, Math.min(pdfView.getPageCount() - 1, page - 1)));
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void chooseReadingTheme() {
        if (pdfView == null) return;
        String[] options = {"White Paper (Crisp Daylight)", "Warm Sepia", "Night Invert (Dark Mode)"};
        new AlertDialog.Builder(this).setTitle("PDF Reading Theme")
                .setItems(options, (d, i) -> {
                    pdfView.setReadingTheme(i);
                    if (pdfThemeButton != null) pdfThemeButton.setText(themeLabel(i));
                }).show();
    }

    private void chooseDefaultPdfTheme() {
        String[] options = {"White Paper", "Warm Sepia", "Night Invert"};
        new AlertDialog.Builder(this).setTitle("Default PDF Paper Theme")
                .setItems(options, (d, i) -> {
                    defaultPdfTheme = i;
                    getPreferences(MODE_PRIVATE).edit().putInt("pdf_theme", i).apply();
                    toast("Default theme set to " + options[i]);
                }).show();
    }

    private void showPdfInfoDialog() {
        if (pdfView == null) return;
        String msg = "File: " + currentName
                + "\nPages: " + pdfView.getPageCount()
                + "\nCurrent Page: " + (pdfView.getPageNumber() + 1)
                + "\nPage Size: " + pdfView.getPageWidthPoints() + " × " + pdfView.getPageHeightPoints() + " pt"
                + "\nRotation: " + pdfView.getRotationDegrees() + "°"
                + "\nAnnotations on page: " + pdfView.getAnnotationCountForCurrentPage();
        new AlertDialog.Builder(this).setTitle("Document Info").setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void printCurrentPdf() {
        if (currentUri == null) return;
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        if (pm == null) { toast("Print service unavailable"); return; }
        pm.print(currentName, new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes oldAttrs, PrintAttributes newAttrs, CancellationSignal signal,
                                 LayoutResultCallback callback, Bundle extras) {
                if (signal.isCanceled()) { callback.onLayoutCancelled(); return; }
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(currentName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .build();
                callback.onLayoutFinished(info, true);
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor dest, CancellationSignal signal,
                                WriteResultCallback callback) {
                try (InputStream in = getContentResolver().openInputStream(currentUri);
                     OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                    if (in == null) throw new IOException("Unreadable source");
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (Exception e) {
                    callback.onWriteFailed(e.getMessage());
                }
            }
        }, null);
    }

    private void promptFindAndReplace() {
        if (textEditor == null) return;
        LinearLayout form = column();
        form.setPadding(dp(18), dp(12), dp(18), dp(4));
        EditText findInput = new EditText(this);
        findInput.setHint("Find text");
        EditText replaceInput = new EditText(this);
        replaceInput.setHint("Replace with (optional)");
        form.addView(findInput);
        form.addView(replaceInput);

        new AlertDialog.Builder(this).setTitle("Find & Replace")
                .setView(form)
                .setPositiveButton("Replace All", (d, w) -> {
                    String query = findInput.getText().toString();
                    String rep = replaceInput.getText().toString();
                    if (query.isEmpty()) return;
                    String full = textEditor.getText().toString();
                    if (!full.contains(query)) { toast("No matches found"); return; }
                    textEditor.setText(full.replace(query, rep));
                    toast("Replaced occurrences of \"" + query + "\"");
                })
                .setNeutralButton("Find Next", (d, w) -> {
                    String query = findInput.getText().toString();
                    if (query.isEmpty()) return;
                    String full = textEditor.getText().toString();
                    int idx = full.indexOf(query, Math.max(0, textEditor.getSelectionEnd()));
                    if (idx < 0) idx = full.indexOf(query);
                    if (idx >= 0) {
                        textEditor.requestFocus();
                        textEditor.setSelection(idx, idx + query.length());
                    } else toast("No matches found");
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showTextStatsDialog() {
        if (textEditor == null) return;
        OfficeEngine.TextStats st = OfficeEngine.stats(textEditor.getText().toString());
        new AlertDialog.Builder(this).setTitle("Document Statistics")
                .setMessage("Words: " + st.words + "\nCharacters: " + st.characters + "\nLines: " + st.lines)
                .setPositiveButton("OK", null).show();
    }

    private void shareCurrentText() {
        if (textEditor == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, currentName);
        send.putExtra(Intent.EXTRA_TEXT, textEditor.getText().toString());
        startActivity(Intent.createChooser(send, "Share document text"));
    }

    private void promptChooseSheet() {
        if (workbook == null) return;
        String[] names = new String[workbook.sheets.size()];
        for (int i = 0; i < names.length; i++) names[i] = workbook.sheets.get(i).name;
        new AlertDialog.Builder(this).setTitle("Select Worksheet")
                .setItems(names, (d, i) -> { sheetIndex = i; firstRow = 1; firstCol = 1; showSheet(); })
                .show();
    }

    private void promptChooseSlide() {
        if (slides == null) return;
        String[] names = new String[slides.slides.size()];
        for (int i = 0; i < names.length; i++) names[i] = slides.slides.get(i).name;
        new AlertDialog.Builder(this).setTitle("Jump to Slide")
                .setItems(names, (d, i) -> { slideIndex = i; showSlide(); })
                .show();
    }

    private TextView pdfAction(String label, boolean chosen) {
        TextView button = text(label, 12, chosen ? ON_BRAND : INK, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(shape(chosen ? BRAND : CARD, 8, chosen ? 0 : BORDER));
        return button;
    }

    private void pdfMode(LinearLayout row, String label, int mode) {
        TextView button = pdfAction(label, mode == PdfZoomView.VIEW);
        LinearLayout.LayoutParams p = lp(-2, 34);
        p.setMargins(dp(3), 0, dp(3), 0);
        button.setPadding(dp(12), 0, dp(12), 0);
        row.addView(button, p);
        pdfToolButtons.put(mode, button);
        button.setOnClickListener(v -> {
            if (pdfView == null) return;
            pdfView.setTool(mode);
            for (Map.Entry<Integer, TextView> entry : pdfToolButtons.entrySet()) {
                boolean selected = entry.getKey() == mode;
                entry.getValue().setTextColor(selected ? ON_BRAND : INK);
                entry.getValue().setBackground(shape(selected ? BRAND : CARD, 8, selected ? 0 : BORDER));
            }
        });
    }

    private void pdfActionChip(LinearLayout row, String label, Runnable action) {
        TextView b = pdfAction(label, false);
        b.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams p = lp(-2, 34);
        p.setMargins(dp(3), 0, dp(3), 0);
        row.addView(b, p);
        b.setOnClickListener(v -> action.run());
    }

    private void selectPdfColor() {
        if (pdfView == null) return;
        String[] names = {"Teal", "Blue", "Emerald", "Coral Red", "Amber", "Black"};
        int[] values = {
                Color.rgb(20, 184, 166), Color.rgb(59, 130, 246), Color.rgb(34, 197, 94),
                Color.rgb(239, 68, 68), Color.rgb(250, 204, 21), Color.BLACK
        };
        new AlertDialog.Builder(this).setTitle("Annotation Color")
                .setItems(names, (d, choice) -> {
                    if (pdfView == null) return;
                    pdfView.setPenColor(values[choice]);
                    pdfView.setMarkerColor(values[choice]);
                    toast(names[choice] + " selected");
                }).show();
    }

    private void savePdfCopy() {
        if (pdfView == null || pendingWork) return;
        pendingSaveRevision = editRevision;
        pdfExportPending = true;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        String name = currentName;
        int dot = name.toLowerCase(Locale.ROOT).lastIndexOf(".pdf");
        if (dot > 0) name = name.substring(0, dot);
        intent.putExtra(Intent.EXTRA_TITLE, name + "-annotated.pdf");
        try { startActivityForResult(intent, SAVE_AS); }
        catch (Exception e) { pdfExportPending = false; error("Cannot choose save location: " + e.getMessage()); }
    }

    private void exportPdfCopy(Uri destination) {
        if (pdfView == null) return;
        final ArrayList<PdfZoomView.Annotation> edits = pdfView.snapshot();
        final Uri source = currentUri;
        final String name = fileName(destination);
        final long revision = pendingSaveRevision;
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Exporting PDF")
                .setMessage("Rendering pages and applying annotations…")
                .setCancelable(false).create();
        progress.show();
        pendingWork = true;
        new Thread(() -> {
            PdfDocument document = new PdfDocument();
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(source, "r");
                 PdfRenderer renderer = new PdfRenderer(fd)) {
                if (renderer.getPageCount() > 100)
                    throw new IOException("PDF export is limited to 100 pages on mobile.");
                for (int i = 0; i < renderer.getPageCount(); i++) {
                    Bitmap bmp = null;
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        int w = page.getWidth(), h = page.getHeight();
                        float scale = Math.min(2.4f, (float) Math.sqrt(4_500_000.0 / Math.max(1.0, w * (double) h)));
                        scale = Math.max(0.2f, scale);
                        int bw = Math.max(1, Math.round(w * scale));
                        int bh = Math.max(1, Math.round(h * scale));
                        bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        Matrix m = new Matrix();
                        m.setScale(scale, scale);
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(w, h, i + 1).create();
                        PdfDocument.Page outPage = document.startPage(info);
                        Canvas canvas = outPage.getCanvas();
                        canvas.drawColor(Color.WHITE);
                        Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                        canvas.drawBitmap(bmp, null, new Rect(0, 0, w, h), imagePaint);
                        PdfZoomView.drawOnPdf(canvas, edits, i);
                        document.finishPage(outPage);
                    } finally {
                        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                    }
                }
                try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new IOException("Cannot write selected location.");
                    document.writeTo(out);
                    out.flush();
                }
                runOnUiThread(() -> {
                    progress.dismiss();
                    pendingWork = false;
                    remember(destination, name);
                    if (editRevision == revision) hasUnsavedChanges = false;
                    toast("Exported: " + name);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    pendingWork = false;
                    error("Export failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                });
            } finally {
                try { document.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    private void closePdf() {
        if (pdfView != null) { pdfView.release(); pdfView = null; }
        pdfToolbar = null;
        pdfTools = null;
        pageIndicator = null;
        pdfThemeButton = null;
        pdfToolButtons.clear();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void error(String s) { new AlertDialog.Builder(this).setTitle("Docs Studio").setMessage(s).setPositiveButton("OK", null).show(); }

    private void navigateBack() {
        if (drawerOpen) { closeDrawer(); return; }
        if (kind.isEmpty()) {
            if (homeTab != 0) { showHomeTab(0); return; }
            finish();
            return;
        }
        if (pendingWork) {
            new AlertDialog.Builder(this).setMessage("Stop opening this document?")
                    .setPositiveButton("Leave", (d, w) -> leaveDocument())
                    .setNegativeButton("Stay", null).show();
            return;
        }
        if (hasUnsavedChanges) {
            new AlertDialog.Builder(this).setTitle("Discard changes?")
                    .setMessage("Save a copy before leaving to keep your edits.")
                    .setPositiveButton("Discard", (d, w) -> leaveDocument())
                    .setNegativeButton("Keep editing", null).show();
        } else leaveDocument();
    }

    private void leaveDocument() {
        if (openedFromExternal) {
            loadGeneration++;
            closePdf();
            finish();
        } else showHome();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { navigateBack(); }

    @Override
    protected void onDestroy() {
        loadGeneration++;
        closePdf();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        super.onDestroy();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int st, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
