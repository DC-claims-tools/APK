package com.example.docs;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Documents: offline document reader, PDF studio, and Office editor for Android.
 */
public class MainActivity extends Activity {
    private static final int OPEN_FILE = 41, SAVE_AS = 42, EXPORT_PNG = 43;

    private boolean darkMode = true;
    private int bg() { return darkMode ? Color.rgb(11, 17, 32) : Color.rgb(248, 250, 252); }
    private int surface() { return darkMode ? Color.rgb(17, 24, 39) : Color.WHITE; }
    private int card() { return darkMode ? Color.rgb(30, 41, 59) : Color.WHITE; }
    private int border() { return darkMode ? Color.rgb(51, 65, 85) : Color.rgb(226, 232, 240); }
    private int ink() { return darkMode ? Color.rgb(241, 245, 249) : Color.rgb(15, 23, 42); }
    private int muted() { return darkMode ? Color.rgb(148, 163, 184) : Color.rgb(100, 116, 139); }
    private int brand() { return darkMode ? Color.rgb(20, 184, 166) : Color.rgb(13, 148, 136); }
    private int pale() { return darkMode ? Color.rgb(19, 78, 74) : Color.rgb(204, 251, 241); }
    private int onBrand() { return darkMode ? Color.rgb(4, 47, 46) : Color.WHITE; }
    private int ripple() { return darkMode ? Color.argb(45, 255, 255, 255) : Color.argb(30, 15, 23, 42); }

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
    private int homeTab = 0; // 0 = All, 1 = Starred, 2 = Settings & Info
    private String searchQuery = "";
    private String typeFilter = "ALL"; // ALL, PDF, OFFICE, TEXT
    private int sortMode = 0; // 0 = Recent, 1 = Name, 2 = Size
    private long editRevision = 0, pendingSaveRevision = 0;
    private boolean monospaceText = false;
    private int editorFontSize = 15;
    private int defaultPdfTheme = PdfZoomView.THEME_PAPER;

    @Override
    public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        darkMode = getPreferences(MODE_PRIVATE).getBoolean("dark_mode", true);
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
        if (uri == null) { error("No document was provided."); return; }
        grantReadIfPossible(uri, intent);
        openedFromExternal = true;
        open(uri, fileName(uri));
    }

    private void grantReadIfPossible(Uri uri, Intent source) {
        if ((source.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
        }
    }

    private void configureSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT < 30) {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (!darkMode) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        } else {
            // PhoneWindow.getInsetsController() can throw before the decor view exists.
            // Query the decor view directly and apply appearance when it is attached.
            android.view.WindowInsetsController controller =
                    getWindow().getDecorView().getWindowInsetsController();
            if (controller != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(darkMode ? 0 : mask, mask);
            }
        }
    }

    private void setSafeContentView(View screenContent) {
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(bg());
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
        configureSystemBars();
        shell.requestApplyInsets();
    }

    // --- Native Vector Icons & Ripple Helpers ---
    private Drawable rippleBg(int fillColor, int radiusDp, int strokeColor) {
        GradientDrawable base = shape(fillColor, radiusDp, strokeColor);
        return new RippleDrawable(ColorStateList.valueOf(ripple()), base, shape(Color.WHITE, radiusDp, 0));
    }

    private ImageView iconBtn(int drawableRes, int tintColor, int boxDp, Runnable onClick) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(drawableRes);
        iv.setColorFilter(tintColor);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(Math.max(8, (boxDp - 22) / 2));
        iv.setPadding(pad, pad, pad, pad);
        iv.setBackground(rippleBg(Color.TRANSPARENT, 12, 0));
        iv.setLayoutParams(lp(boxDp, boxDp));
        iv.setOnClickListener(v -> onClick.run());
        return iv;
    }

    // --- Slide-out Side Navigation Menu ---
    private void buildSideDrawer(FrameLayout shell) {
        drawerOpen = false;
        drawerOverlay = new FrameLayout(this);
        drawerOverlay.setVisibility(View.GONE);

        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(Color.argb(160, 2, 6, 23));
        drawerScrim.setAlpha(0f);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        drawerOverlay.addView(drawerScrim, new FrameLayout.LayoutParams(-1, -1));

        drawerPanel = column();
        drawerPanel.setBackgroundColor(surface());
        drawerPanel.setElevation(dp(16));
        int drawerWidth = Math.min(dp(304), Math.round(getResources().getDisplayMetrics().widthPixels * 0.84f));
        drawerPanel.setTranslationX(-drawerWidth);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        LinearLayout body = column();
        body.setPadding(dp(14), dp(16), dp(14), dp(24));

        // Drawer Header with Real App Icon
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), dp(4), dp(4), dp(4));

        FrameLayout iconBadge = new FrameLayout(this);
        iconBadge.setBackground(shape(brand(), 12, 0));
        ImageView docLogo = new ImageView(this);
        docLogo.setImageResource(R.drawable.ic_doc_file);
        docLogo.setColorFilter(onBrand());
        FrameLayout.LayoutParams lbp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
        iconBadge.addView(docLogo, lbp);
        header.addView(iconBadge, lp(40, 40));

        LinearLayout titles = column();
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("Documents", 18, ink(), true));
        titles.addView(text("PDF & Office Workspace", 12, muted(), false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(header);

        View hr = new View(this);
        hr.setBackgroundColor(border());
        LinearLayout.LayoutParams hrp = lp(-1, 1);
        hrp.topMargin = dp(14);
        hrp.bottomMargin = dp(10);
        body.addView(hr, hrp);

        drawerSection(body, "LIBRARY");
        drawerItem(body, R.drawable.ic_doc_file, "All Documents", homeTab == 0 && kind.isEmpty(), () -> { closeDrawer(); confirmLeaveToTab(0); });
        drawerItem(body, R.drawable.ic_star, "Starred", homeTab == 1 && kind.isEmpty(), () -> { closeDrawer(); confirmLeaveToTab(1); });
        drawerItem(body, R.drawable.ic_folder, "Browse Device Files", false, () -> { closeDrawer(); selectFile(); });

        drawerSection(body, "DOCUMENT STUDIO");
        drawerItem(body, R.drawable.ic_grid, "Power Tools • PDF / OCR / Convert", false, () -> {
            closeDrawer(); openToolbox();
        });

        drawerSection(body, "CREATE NEW");
        drawerItem(body, R.drawable.ic_add, "Text Note (.txt)", false, () -> { closeDrawer(); createNewDoc("Untitled.txt", "text", ""); });
        drawerItem(body, R.drawable.ic_add, "Markdown (.md)", false, () -> { closeDrawer(); createNewDoc("Notes.md", "text", "# Document Title\n\n"); });
        drawerItem(body, R.drawable.ic_add, "Spreadsheet (.csv)", false, () -> { closeDrawer(); createNewDoc("Sheet.csv", "csv", "Item,Category,Amount\n,,\n,,"); });

        if ("pdf".equals(kind) && pdfView != null) {
            drawerSection(body, "PDF TOOLS");
            drawerItem(body, R.drawable.ic_grid, "All Pages Grid", false, () -> { closeDrawer(); showPdfThumbnailsDialog(); });
            drawerItem(body, R.drawable.ic_bookmark, "Page Bookmarks", false, () -> { closeDrawer(); showPdfBookmarksDialog(); });
            drawerItem(body, R.drawable.ic_search, "Jump to Page…", false, () -> { closeDrawer(); promptGoToPage(); });
            drawerItem(body, R.drawable.ic_theme, "Reading Theme (Paper / Sepia / Night)", false, () -> { closeDrawer(); chooseReadingTheme(); });
            drawerItem(body, R.drawable.ic_rotate, "Rotate Page 90°", false, () -> { closeDrawer(); pdfView.rotateClockwise(); toast("Rotated " + pdfView.getRotationDegrees() + "°"); });
            drawerItem(body, R.drawable.ic_print, "Print PDF", false, () -> { closeDrawer(); printCurrentPdf(); });
            drawerItem(body, R.drawable.ic_download, "Export Current Page as PNG", false, () -> { closeDrawer(); startExportPagePng(); });
            drawerItem(body, R.drawable.ic_share, "Share Original PDF", false, () -> { closeDrawer(); shareCurrentFile(); });
            drawerItem(body, R.drawable.ic_download, "Export Annotated PDF", false, () -> { closeDrawer(); savePdfCopy(); });
        } else if ("text".equals(kind) || "csv".equals(kind) || "docx".equals(kind)) {
            drawerSection(body, "DOCUMENT TOOLS");
            drawerItem(body, R.drawable.ic_search, "Find & Replace", false, () -> { closeDrawer(); promptFindAndReplace(); });
            drawerItem(body, R.drawable.ic_doc_file, "Word & Character Count", false, () -> { closeDrawer(); showTextStatsDialog(); });
            drawerItem(body, R.drawable.ic_theme, monospaceText ? "Use Standard Font" : "Use Monospace Font", false, () -> {
                closeDrawer();
                monospaceText = !monospaceText;
                if (textEditor != null) textEditor.setTypeface(monospaceText ? Typeface.MONOSPACE : Typeface.DEFAULT);
            });
            drawerItem(body, R.drawable.ic_share, "Share Text", false, () -> { closeDrawer(); shareCurrentText(); });
            drawerItem(body, R.drawable.ic_download, "Save Copy", false, () -> { closeDrawer(); saveCopy(); });
        } else if ("xlsx".equals(kind) && workbook != null) {
            drawerSection(body, "SPREADSHEET TOOLS");
            drawerItem(body, R.drawable.ic_grid, "Switch Worksheet", false, () -> { closeDrawer(); promptChooseSheet(); });
            drawerItem(body, R.drawable.ic_download, "Export Sheet as CSV", false, () -> { closeDrawer(); exportSheetAsCsv(); });
            drawerItem(body, R.drawable.ic_download, "Save XLSX Copy", false, () -> { closeDrawer(); saveCopy(); });
        } else if ("pptx".equals(kind) && slides != null) {
            drawerSection(body, "SLIDE TOOLS");
            drawerItem(body, R.drawable.ic_grid, "Jump to Slide", false, () -> { closeDrawer(); promptChooseSlide(); });
            drawerItem(body, R.drawable.ic_download, "Save PPTX Copy", false, () -> { closeDrawer(); saveCopy(); });
        }

        drawerSection(body, "PREFERENCES");
        drawerItem(body, R.drawable.ic_theme, darkMode ? "Switch to Light Mode" : "Switch to Dark Mode", false, () -> {
            closeDrawer();
            toggleAppTheme();
        });
        drawerItem(body, R.drawable.ic_doc_file, "Settings & Format Info", homeTab == 2 && kind.isEmpty(), () -> { closeDrawer(); confirmLeaveToTab(2); });

        scroller.addView(body);
        drawerPanel.addView(scroller, new LinearLayout.LayoutParams(-1, -1));
        drawerOverlay.addView(drawerPanel, new FrameLayout.LayoutParams(drawerWidth, -1, Gravity.START));
        shell.addView(drawerOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void drawerSection(LinearLayout parent, String title) {
        TextView label = text(title, 11, muted(), true);
        label.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(12);
        p.bottomMargin = dp(4);
        p.leftMargin = dp(10);
        parent.addView(label, p);
    }

    private void drawerItem(LinearLayout parent, int iconRes, String label, boolean active, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        row.setBackground(rippleBg(active ? pale() : Color.TRANSPARENT, 10, 0));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(active ? brand() : muted());
        row.addView(icon, lp(20, 20));

        TextView tv = text(label, 14, active ? brand() : ink(), active);
        tv.setPadding(dp(12), 0, 0, 0);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout.LayoutParams p = lp(-1, -2);
        p.bottomMargin = dp(2);
        parent.addView(row, p);
        row.setOnClickListener(v -> onClick.run());
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
        int w = drawerPanel.getWidth() > 0 ? drawerPanel.getWidth() : dp(304);
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

    private void toggleAppTheme() {
        darkMode = !darkMode;
        getPreferences(MODE_PRIVATE).edit().putBoolean("dark_mode", darkMode).apply();
        configureSystemBars();
        if (kind.isEmpty()) showHomeTab(homeTab);
        else if ("pdf".equals(kind) && currentUri != null) showPdf();
        else if ("xlsx".equals(kind)) showSheet();
        else if ("pptx".equals(kind)) showSlide();
        else showText(textEditor != null ? textEditor.getText().toString() : originalText);
    }

    private void confirmLeaveToTab(int tab) {
        if (kind.isEmpty()) { showHomeTab(tab); return; }
        if (hasUnsavedChanges) {
            new AlertDialog.Builder(this).setTitle("Discard unsaved changes?")
                    .setMessage("Save a copy first if you want to keep your edits.")
                    .setPositiveButton("Discard", (d, w) -> { resetDocState(); showHomeTab(tab); })
                    .setNegativeButton("Stay", null).show();
        } else { resetDocState(); showHomeTab(tab); }
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
        sc.setBackgroundColor(bg());
        content = column();
        content.setPadding(dp(20), dp(20), dp(20), dp(28));
        sc.addView(content);
        setSafeContentView(sc);
        if (back) {
            TextView go = text("‹  Documents", 15, brand(), true);
            go.setPadding(0, dp(4), 0, dp(16));
            go.setOnClickListener(v -> navigateBack());
            content.addView(go);
        }
        content.addView(text(title, 24, ink(), true));
        if (!subtitle.isEmpty()) {
            TextView sub = text(subtitle, 14, muted(), false);
            sub.setPadding(0, dp(6), 0, 0);
            content.addView(sub);
        }
        space(18);
    }

    private void helper(String message) {
        TextView v = text(message, 13, muted(), false);
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
        btn.setTextColor(primary ? onBrand() : brand());
        btn.setBackground(rippleBg(primary ? brand() : pale(), 12, primary ? 0 : border()));
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
        FrameLayout rootContainer = new FrameLayout(this);
        LinearLayout root = column();
        root.setBackgroundColor(bg());

        // Top Bar
        LinearLayout appBar = new LinearLayout(this);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        appBar.setPadding(dp(8), dp(6), dp(12), dp(6));
        appBar.setBackgroundColor(surface());

        appBar.addView(iconBtn(R.drawable.ic_menu, ink(), 44, this::openDrawer));

        TextView appName = text(tab == 1 ? "Starred" : (tab == 2 ? "Settings" : "Documents"), 19, ink(), true);
        appName.setPadding(dp(8), 0, 0, 0);
        appBar.addView(appName, new LinearLayout.LayoutParams(0, -2, 1));

        appBar.addView(iconBtn(R.drawable.ic_theme, muted(), 42, this::toggleAppTheme));

        TextView openBtn = text("Open", 13, onBrand(), true);
        openBtn.setGravity(Gravity.CENTER);
        openBtn.setBackground(rippleBg(brand(), 10, 0));
        openBtn.setOnClickListener(v -> selectFile());
        LinearLayout.LayoutParams obp = lp(70, 36);
        obp.leftMargin = dp(4);
        appBar.addView(openBtn, obp);

        root.addView(appBar, lp(-1, -2));
        View line = new View(this);
        line.setBackgroundColor(border());
        root.addView(line, lp(-1, 1));

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(bg());
        content = column();
        content.setPadding(dp(16), dp(14), dp(16), dp(84));
        sc.addView(content);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        if (tab == 0 || tab == 1) drawWorkspace(tab == 1);
        else drawSettings();

        // Bottom Navigation Bar
        View navTop = new View(this);
        navTop.setBackgroundColor(border());
        root.addView(navTop, lp(-1, 1));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(surface());
        nav.setPadding(dp(8), dp(6), dp(8), dp(6));
        String[] labels = {"Files", "Starred", "Settings"};
        int[] icons = {R.drawable.ic_doc_file, R.drawable.ic_star, R.drawable.ic_theme};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            item.setBackground(rippleBg(tab == i ? pale() : Color.TRANSPARENT, 12, 0));
            ImageView iv = new ImageView(this);
            iv.setImageResource(icons[i]);
            iv.setColorFilter(tab == i ? brand() : muted());
            item.addView(iv, lp(20, 20));
            TextView tv = text(labels[i], 11, tab == i ? brand() : muted(), tab == i);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(2), 0, 0);
            item.addView(tv);
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(48), 1));
            item.setOnClickListener(v -> { if (homeTab != index) showHomeTab(index); });
        }
        root.addView(nav, lp(-1, -2));

        rootContainer.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // Floating Action Button (+) for quick Browse/Create
        if (tab != 2) {
            FrameLayout fab = new FrameLayout(this);
            fab.setBackground(rippleBg(brand(), 16, 0));
            fab.setElevation(dp(6));
            ImageView plus = new ImageView(this);
            plus.setImageResource(R.drawable.ic_add);
            plus.setColorFilter(onBrand());
            fab.addView(plus, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.BOTTOM | Gravity.END);
            fabLp.setMargins(0, 0, dp(18), dp(74));
            fab.setOnClickListener(v -> showCreateOrOpenSheet());
            rootContainer.addView(fab, fabLp);
        }

        setSafeContentView(rootContainer);
    }

    private void showCreateOrOpenSheet() {
        String[] opts = {"Open File from Device", "Document Studio • PDF & OCR Tools", "New Text Note (.txt)", "New Markdown (.md)", "New Spreadsheet (.csv)"};
        new AlertDialog.Builder(this).setTitle("Documents")
                .setItems(opts, (d, which) -> {
                    if (which == 0) selectFile();
                    else if (which == 1) openToolbox();
                    else if (which == 2) createNewDoc("Untitled.txt", "text", "");
                    else if (which == 3) createNewDoc("Notes.md", "text", "# Heading\n\n");
                    else createNewDoc("Sheet.csv", "csv", "Column A,Column B,Column C\n,,\n,,");
                }).show();
    }

    private void openToolbox() {
        try { startActivity(new Intent(this, ToolboxActivity.class)); }
        catch (Exception e) { error("Cannot open tools: " + e.getMessage()); }
    }

    private void drawWorkspace(boolean starredOnly) {
        if (!starredOnly) {
            LinearLayout studio = column();
            studio.setPadding(dp(16), dp(14), dp(14), dp(14));
            studio.setBackground(rippleBg(card(), 14, border()));
            studio.addView(text("DOCUMENT STUDIO 6.0", 11, brand(), true));
            studio.addView(text("PDF Tools, OCR & Conversions", 17, ink(), true));
            studio.addView(text("Merge, split, reorder, encrypt, scan text, create PDFs and more.", 12, muted(), false));
            LinearLayout.LayoutParams stp = lp(-1, -2);
            stp.bottomMargin = dp(16);
            content.addView(studio, stp);
            studio.setOnClickListener(v -> openToolbox());
        }
        // Integrated Search Bar + Sort trigger
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(shape(card(), 12, border()));
        searchBox.setPadding(dp(12), 0, dp(6), 0);

        ImageView sIcon = new ImageView(this);
        sIcon.setImageResource(R.drawable.ic_search);
        sIcon.setColorFilter(muted());
        searchBox.addView(sIcon, lp(18, 18));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(ink());
        search.setText(searchQuery);
        search.setHint("Search documents…");
        search.setHintTextColor(muted());
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(dp(10), 0, dp(8), 0);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1));

        TextView sortBtn = text(sortMode == 0 ? "Recent ▾" : (sortMode == 1 ? "Name ▾" : "Size ▾"), 12, brand(), true);
        sortBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        sortBtn.setBackground(rippleBg(pale(), 8, 0));
        sortBtn.setOnClickListener(v -> {
            sortMode = (sortMode + 1) % 3;
            sortBtn.setText(sortMode == 0 ? "Recent ▾" : (sortMode == 1 ? "Name ▾" : "Size ▾"));
            renderList(starredOnly);
        });
        searchBox.addView(sortBtn);
        content.addView(searchBox, lp(-1, 46));
        space(10);

        // Filter Chips
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        String[][] filters = {{"ALL", "All"}, {"PDF", "PDFs"}, {"OFFICE", "Word / Excel / Slides"}, {"TEXT", "Notes & CSV"}};
        for (String[] f : filters) {
            boolean sel = f[0].equals(typeFilter);
            TextView chip = text(f[1], 12, sel ? onBrand() : muted(), sel);
            chip.setPadding(dp(13), dp(7), dp(13), dp(7));
            chip.setBackground(rippleBg(sel ? brand() : card(), 9, sel ? 0 : border()));
            LinearLayout.LayoutParams cp = lp(-2, -2);
            cp.rightMargin = dp(8);
            chip.setOnClickListener(v -> {
                typeFilter = f[0];
                showHomeTab(homeTab);
            });
            chipRow.addView(chip, cp);
        }
        chipScroll.addView(chipRow);
        content.addView(chipScroll, lp(-1, -2));
        space(14);

        recentHolder = column();
        content.addView(recentHolder, lp(-1, -2));
        renderList(starredOnly);

        search.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString();
                renderList(starredOnly);
            }
        });
    }

    private void drawSettings() {
        sectionTitle("Appearance & Reading");
        settingRow("App Theme", darkMode ? "Dark Slate" : "Daylight Light", () -> toggleAppTheme());
        settingRow("Default PDF Paper", themeLabel(defaultPdfTheme), () -> chooseDefaultPdfTheme());
        space(12);
        sectionTitle("Storage & History");
        settingRow("Clear Recent List", "Remove remembered shortcuts", () -> {
            getPreferences(MODE_PRIVATE).edit().remove("recent").apply();
            toast("History cleared");
        });
        space(12);
        sectionTitle("Supported Formats");
        infoCard("Offline Document Engine", "Documents opens PDF, DOCX, XLSX, PPTX, TXT, MD, CSV, JSON, and XML files locally on your Moto G 2024 without uploading any data.");
    }

    private void settingRow(String title, String value, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(rippleBg(card(), 12, border()));
        LinearLayout col = column();
        col.addView(text(title, 15, ink(), true));
        col.addView(text(value, 12, muted(), false));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text("›", 22, muted(), false));
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.bottomMargin = dp(8);
        content.addView(row, p);
        row.setOnClickListener(v -> onClick.run());
    }

    private TextView sectionTitle(String title) {
        TextView v = text(title, 14, muted(), true);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.bottomMargin = dp(8);
        content.addView(v, p);
        return v;
    }

    private void infoCard(String title, String body) {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(shape(card(), 12, border()));
        box.addView(text(title, 15, ink(), true));
        TextView desc = text(body, 13, muted(), false);
        desc.setLineSpacing(dp(3), 1f);
        desc.setPadding(0, dp(6), 0, 0);
        box.addView(desc);
        content.addView(box, lp(-1, -2));
    }

    private void renderList(boolean starredOnly) {
        if (recentHolder == null) return;
        recentHolder.removeAllViews();
        JSONArray arr = recentArray();
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (starredOnly && !o.optBoolean("starred", false)) continue;
            String name = o.optString("name");
            if (!name.toLowerCase(Locale.ROOT).contains(searchQuery.toLowerCase(Locale.ROOT))) continue;
            String ext = extOf(name);
            if ("PDF".equals(typeFilter) && !"PDF".equals(ext)) continue;
            if ("OFFICE".equals(typeFilter) && !("DOCX".equals(ext) || "XLSX".equals(ext) || "PPTX".equals(ext))) continue;
            if ("TEXT".equals(typeFilter) && !("TXT".equals(ext) || "MD".equals(ext) || "CSV".equals(ext) || "JSON".equals(ext))) continue;
            items.add(o);
        }

        if (sortMode == 1) {
            Collections.sort(items, (a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
        } else if (sortMode == 2) {
            Collections.sort(items, (a, b) -> Long.compare(b.optLong("size", 0), a.optLong("size", 0)));
        }

        for (JSONObject o : items) {
            String name = o.optString("name"), url = o.optString("uri");
            boolean starred = o.optBoolean("starred", false);
            long sizeBytes = o.optLong("size", 0);
            String opened = o.optString("openedAt", "Recent");
            String ext = extOf(name);

            int accent = brand(), tint = pale();
            if ("PDF".equals(ext)) { accent = Color.rgb(248, 113, 113); tint = Color.rgb(69, 26, 35); }
            else if ("DOCX".equals(ext)) { accent = Color.rgb(96, 165, 250); tint = Color.rgb(30, 58, 95); }
            else if ("XLSX".equals(ext) || "CSV".equals(ext)) { accent = Color.rgb(74, 222, 128); tint = Color.rgb(20, 83, 45); }
            else if ("PPTX".equals(ext)) { accent = Color.rgb(251, 146, 60); tint = Color.rgb(67, 40, 24); }
            if (!darkMode) tint = Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent));

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(11), dp(6), dp(11));
            row.setBackground(rippleBg(card(), 12, border()));

            FrameLayout badge = new FrameLayout(this);
            badge.setBackground(shape(tint, 10, 0));
            TextView extLabel = text(ext, ext.length() > 3 ? 10 : 11, accent, true);
            badge.addView(extLabel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            row.addView(badge, lp(42, 42));

            LinearLayout names = column();
            names.setPadding(dp(12), 0, dp(4), 0);
            TextView filename = text(name, 14, ink(), true);
            filename.setSingleLine(true);
            filename.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            names.addView(filename);
            String meta = ext + (sizeBytes > 0 ? " · " + formatSize(sizeBytes) : "") + " · " + opened;
            TextView metaTv = text(meta, 12, muted(), false);
            metaTv.setPadding(0, dp(2), 0, 0);
            names.addView(metaTv);
            row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));

            row.addView(iconBtn(starred ? R.drawable.ic_star : R.drawable.ic_star_outline,
                    starred ? Color.rgb(250, 204, 21) : muted(), 38, () -> {
                        toggleStar(url);
                        renderList(starredOnly);
                    }));

            ImageView moreBtn = iconBtn(R.drawable.ic_more, muted(), 38, () -> {});
            moreBtn.setOnClickListener(v -> showRowOverflow(v, url, name, starred, starredOnly));
            row.addView(moreBtn);

            LinearLayout.LayoutParams p = lp(-1, -2);
            p.bottomMargin = dp(8);
            recentHolder.addView(row, p);
            row.setOnClickListener(v -> {
                openedFromExternal = false;
                open(Uri.parse(url), name);
            });
        }

        if (items.isEmpty()) {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(34), dp(24), dp(34));
            empty.setBackground(shape(card(), 14, border()));
            ImageView emptyIcon = new ImageView(this);
            emptyIcon.setImageResource(R.drawable.ic_folder);
            emptyIcon.setColorFilter(muted());
            empty.addView(emptyIcon, lp(36, 36));
            TextView emptyLabel = text(starredOnly ? "No starred documents" : (searchQuery.isEmpty() ? "Open a document to begin" : "No matching files"), 15, ink(), true);
            emptyLabel.setPadding(0, dp(10), 0, dp(4));
            empty.addView(emptyLabel);
            TextView hint = text("Tap Open or + to browse PDF, Word, Excel, PowerPoint, or text files.", 13, muted(), false);
            hint.setGravity(Gravity.CENTER);
            empty.addView(hint);
            recentHolder.addView(empty, lp(-1, -2));
        }
    }

    private void showRowOverflow(View anchor, String uriStr, String name, boolean starred, boolean starredOnly) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Open");
        menu.getMenu().add(0, 2, 1, starred ? "Remove Star" : "Add Star");
        menu.getMenu().add(0, 3, 2, "Share File");
        menu.getMenu().add(0, 4, 3, "Remove from History");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) { openedFromExternal = false; open(Uri.parse(uriStr), name); }
            else if (id == 2) { toggleStar(uriStr); renderList(starredOnly); }
            else if (id == 3) { shareUri(Uri.parse(uriStr), name); }
            else if (id == 4) { removeRecent(uriStr); renderList(starredOnly); }
            return true;
        });
        menu.show();
    }

    private String extOf(String name) {
        if (name == null || !name.contains(".")) return "FILE";
        String e = name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        return e.length() > 4 ? "FILE" : e;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private JSONArray recentArray() {
        try { return new JSONArray(getPreferences(MODE_PRIVATE).getString("recent", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private void remember(Uri uri, String name) {
        try {
            long size = queryFileSize(uri);
            String stamp = new SimpleDateFormat("MMM d", Locale.US).format(new Date());
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
            first.put("size", size);
            first.put("openedAt", stamp);
            first.put("starred", wasStarred);
            next.put(first);
            for (int i = 0; i < old.length() && next.length() < 24; i++) {
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

    private void removeRecent(String uriStr) {
        try {
            JSONArray old = recentArray(), next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !uriStr.equals(o.optString("uri"))) next.put(o);
            }
            getPreferences(MODE_PRIVATE).edit().putString("recent", next.toString()).apply();
        } catch (Exception ignored) {}
    }

    private long queryFileSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {}
        return 0;
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
        } else if (request == EXPORT_PNG) {
            exportCurrentPdfPageAsPng(uri);
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
                    runOnUiThread(() -> error("Could not save file: " + e.getMessage()));
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
            screen(name, "Unsupported format", true);
            helper("Documents supports PDF, DOCX, XLSX, PPTX, TXT, MD, CSV, JSON, and XML.");
            action("Open another file", true).setOnClickListener(v -> selectFile());
            return;
        }
        remember(uri, name);
        if ("pdf".equals(kind)) { showPdf(); return; }
        pendingWork = true;
        final int generation = loadGeneration;
        final String openingType = kind;
        screen("Opening document", name, true);
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
                    screen(currentName, "Cannot open file", true);
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
        field.setTextColor(ink());
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(minLines);
        field.setPadding(dp(14), dp(14), dp(14), dp(14));
        field.setBackground(shape(card(), 10, border()));
        field.setHorizontallyScrolling(false);
        return field;
    }

    private LinearLayout readerScreen(String title) {
        LinearLayout root = column();
        root.setBackgroundColor(bg());
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(10), 0);
        bar.setBackgroundColor(surface());

        bar.addView(iconBtn(R.drawable.ic_menu, ink(), 42, this::openDrawer));
        bar.addView(iconBtn(R.drawable.ic_back, muted(), 38, this::navigateBack));

        TextView heading = text(title, 15, ink(), true);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(6), 0, dp(6), 0);
        bar.addView(heading, new LinearLayout.LayoutParams(0, dp(52), 1));

        TextView saveTop = text("Save", 13, onBrand(), true);
        saveTop.setGravity(Gravity.CENTER);
        saveTop.setBackground(rippleBg(brand(), 9, 0));
        saveTop.setOnClickListener(v -> saveCopy());
        bar.addView(saveTop, lp(64, 34));

        root.addView(bar, lp(-1, 52));
        View divider = new View(this);
        divider.setBackgroundColor(border());
        root.addView(divider, lp(-1, 1));
        return root;
    }

    private void showText(String initial) {
        originalText = initial;
        hasUnsavedChanges = false;
        LinearLayout root = readerScreen(currentName);
        textEditor = editor(initial, 8);
        textEditor.setBackgroundColor(bg());
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

        content.addView(text(sheet.name + " · Rows " + firstRow + "–" + (firstRow + 19), 15, ink(), true));
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
        grid.setBackground(shape(surface(), 8, border()));
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
                cell.setTextColor(ink());
                cell.setText(old);
                cell.setPadding(dp(8), 0, dp(6), 0);
                cell.setBackground(shape(bg(), 0, border()));
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
        TextView v = text(value, 12, ink(), strong);
        v.setGravity(Gravity.CENTER);
        v.setBackground(shape(card(), 0, border()));
        v.setLayoutParams(lp(width, 40));
        return v;
    }

    private Button mini(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setTextColor(brand());
        b.setPadding(0, 0, 0, 0);
        b.setBackground(rippleBg(card(), 8, border()));
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

        content.addView(text(slide.name + " of " + slides.slides.size(), 17, ink(), true));
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
            content.addView(text("Text Box " + (++count), 12, muted(), true));
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

    // --- PDF Reader with Vector Icons, Page Grid, Bookmarks, Stamps, & PNG Export ---
    private void showPdf() {
        ParcelFileDescriptor fd = null;
        PdfRenderer renderer = null;
        try {
            fd = getContentResolver().openFileDescriptor(currentUri, "r");
            if (fd == null) throw new IOException("Cannot access this PDF.");
            renderer = new PdfRenderer(fd);
            if (renderer.getPageCount() < 1) throw new IOException("This PDF contains no pages.");

            LinearLayout root = column();
            root.setBackgroundColor(bg());

            pdfToolbar = column();
            pdfToolbar.setBackgroundColor(surface());
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(4), 0, dp(8), 0);

            header.addView(iconBtn(R.drawable.ic_menu, ink(), 40, this::openDrawer));
            header.addView(iconBtn(R.drawable.ic_back, muted(), 36, this::navigateBack));

            TextView title = text(currentName, 14, ink(), true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setPadding(dp(4), 0, dp(4), 0);
            header.addView(title, new LinearLayout.LayoutParams(0, dp(50), 1));

            header.addView(iconBtn(R.drawable.ic_grid, muted(), 38, this::showPdfThumbnailsDialog));
            header.addView(iconBtn(R.drawable.ic_bookmark, muted(), 38, this::toggleCurrentPageBookmark));

            pdfThemeButton = text(themeLabel(defaultPdfTheme), 11, brand(), true);
            pdfThemeButton.setGravity(Gravity.CENTER);
            pdfThemeButton.setBackground(rippleBg(pale(), 8, border()));
            pdfThemeButton.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams tp = lp(-2, 32);
            tp.rightMargin = dp(6);
            header.addView(pdfThemeButton, tp);

            TextView export = text("Export", 12, onBrand(), true);
            export.setGravity(Gravity.CENTER);
            export.setBackground(rippleBg(brand(), 8, 0));
            export.setOnClickListener(v -> savePdfCopy());
            header.addView(export, lp(60, 32));

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
            pdfTools.setBackgroundColor(surface());
            View separator = new View(this);
            separator.setBackgroundColor(border());
            pdfTools.addView(separator, lp(-1, 1));

            LinearLayout pager = new LinearLayout(this);
            pager.setGravity(Gravity.CENTER_VERTICAL);
            pager.setPadding(dp(10), dp(4), dp(10), dp(4));
            TextView prev = pdfAction("‹ Prev", false), next = pdfAction("Next ›", false);
            pageIndicator = text("1 / " + pdfView.getPageCount(), 13, ink(), true);
            pageIndicator.setGravity(Gravity.CENTER);
            pageIndicator.setOnClickListener(v -> promptGoToPage());
            TextView rotBtn = pdfAction("Rotate", false);
            TextView fit = pdfAction("Fit", false);
            pager.addView(prev, lp(66, 34));
            pager.addView(pageIndicator, new LinearLayout.LayoutParams(0, dp(34), 1));
            pager.addView(next, lp(66, 34));
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
            pdfMode(toolRow, "Check ✓", PdfZoomView.STAMP_CHECK);
            pdfMode(toolRow, "Date", PdfZoomView.STAMP_DATE);
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

    private void showPdfThumbnailsDialog() {
        if (pdfView == null) return;
        int count = pdfView.getPageCount();
        int limit = Math.min(count, 40);
        ScrollView sv = new ScrollView(this);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(12), dp(12), dp(12), dp(12));
        sv.addView(grid);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("All Pages (" + count + ")")
                .setView(sv)
                .setNegativeButton("Close", null)
                .create();

        int thumbW = dp(130);
        for (int i = 0; i < limit; i++) {
            final int pageIdx = i;
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(8), dp(8), dp(8), dp(8));
            boolean active = pageIdx == pdfView.getPageNumber();
            cell.setBackground(rippleBg(card(), 10, active ? brand() : border()));

            ImageView iv = new ImageView(this);
            iv.setBackgroundColor(Color.WHITE);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(iv, lp(120, 155));

            TextView lbl = text("Page " + (pageIdx + 1), 12, active ? brand() : ink(), active);
            lbl.setPadding(0, dp(6), 0, 0);
            cell.addView(lbl);

            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.setMargins(dp(6), dp(6), dp(6), dp(6));
            grid.addView(cell, glp);

            pdfView.renderThumbnailAsync(pageIdx, thumbW, bmp -> iv.setImageBitmap(bmp));
            cell.setOnClickListener(v -> {
                pdfView.setPage(pageIdx);
                dialog.dismiss();
            });
        }
        dialog.show();
    }

    private String bookmarkKey() {
        return "bm_" + (currentUri == null ? currentName : currentUri.toString());
    }

    private List<Integer> getPageBookmarks() {
        List<Integer> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(getPreferences(MODE_PRIVATE).getString(bookmarkKey(), "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getInt(i));
        } catch (Exception ignored) {}
        return out;
    }

    private void toggleCurrentPageBookmark() {
        if (pdfView == null) return;
        int page = pdfView.getPageNumber();
        List<Integer> list = getPageBookmarks();
        boolean added;
        if (list.contains(page)) {
            list.remove(Integer.valueOf(page));
            added = false;
        } else {
            list.add(page);
            Collections.sort(list);
            added = true;
        }
        JSONArray arr = new JSONArray();
        for (int p : list) arr.put(p);
        getPreferences(MODE_PRIVATE).edit().putString(bookmarkKey(), arr.toString()).apply();
        toast(added ? "Bookmarked page " + (page + 1) : "Removed bookmark on page " + (page + 1));
    }

    private void showPdfBookmarksDialog() {
        if (pdfView == null) return;
        List<Integer> list = getPageBookmarks();
        if (list.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Page Bookmarks")
                    .setMessage("No pages bookmarked in this PDF yet. Tap the bookmark icon in the top bar to save a page.")
                    .setPositiveButton("Bookmark Current Page", (d, w) -> toggleCurrentPageBookmark())
                    .setNegativeButton("Close", null).show();
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) labels[i] = "Page " + (list.get(i) + 1);
        new AlertDialog.Builder(this).setTitle("Bookmarked Pages")
                .setItems(labels, (d, i) -> pdfView.setPage(list.get(i)))
                .setNegativeButton("Close", null).show();
    }

    private void startExportPagePng() {
        if (pdfView == null || currentUri == null) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/png");
        i.putExtra(Intent.EXTRA_TITLE, currentName + "-page-" + (pdfView.getPageNumber() + 1) + ".png");
        startActivityForResult(i, EXPORT_PNG);
    }

    private void exportCurrentPdfPageAsPng(Uri dest) {
        if (pdfView == null || currentUri == null) return;
        final int pageIdx = pdfView.getPageNumber();
        final ArrayList<PdfZoomView.Annotation> edits = pdfView.snapshot();
        new Thread(() -> {
            Bitmap bmp = null;
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(currentUri, "r");
                 PdfRenderer renderer = new PdfRenderer(fd);
                 PdfRenderer.Page page = renderer.openPage(pageIdx)) {
                int w = page.getWidth(), h = page.getHeight();
                float scale = 2.0f;
                bmp = Bitmap.createBitmap(Math.round(w * scale), Math.round(h * scale), Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                Matrix m = new Matrix();
                m.setScale(scale, scale);
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                Canvas c = new Canvas(bmp);
                c.scale(scale, scale);
                PdfZoomView.drawOnPdf(c, edits, pageIdx);
                try (OutputStream out = getContentResolver().openOutputStream(dest, "wt")) {
                    if (out == null) throw new IOException("Cannot write PNG");
                    bmp.compress(Bitmap.CompressFormat.PNG, 95, out);
                    out.flush();
                }
                runOnUiThread(() -> toast("Saved page image"));
            } catch (Exception e) {
                runOnUiThread(() -> error("PNG export failed: " + e.getMessage()));
            } finally {
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            }
        }).start();
    }

    private void shareCurrentFile() {
        if (currentUri == null) return;
        shareUri(currentUri, currentName);
    }

    private void shareUri(Uri uri, String name) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("*/*");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, name);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share " + name));
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
        String[] options = {"White Paper (Daylight)", "Warm Sepia", "Night Invert (Dark Mode)"};
        new AlertDialog.Builder(this).setTitle("PDF Reading Theme")
                .setItems(options, (d, i) -> {
                    pdfView.setReadingTheme(i);
                    if (pdfThemeButton != null) pdfThemeButton.setText(themeLabel(i));
                }).show();
    }

    private void chooseDefaultPdfTheme() {
        String[] options = {"White Paper", "Warm Sepia", "Night Invert"};
        new AlertDialog.Builder(this).setTitle("Default PDF Paper")
                .setItems(options, (d, i) -> {
                    defaultPdfTheme = i;
                    getPreferences(MODE_PRIVATE).edit().putInt("pdf_theme", i).apply();
                    if (homeTab == 2 && kind.isEmpty()) showHomeTab(2);
                    toast("Default theme: " + options[i]);
                }).show();
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
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build();
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
                } catch (Exception e) { callback.onWriteFailed(e.getMessage()); }
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
                    toast("Replaced all matches");
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
        startActivity(Intent.createChooser(send, "Share text"));
    }

    private void promptChooseSheet() {
        if (workbook == null) return;
        String[] names = new String[workbook.sheets.size()];
        for (int i = 0; i < names.length; i++) names[i] = workbook.sheets.get(i).name;
        new AlertDialog.Builder(this).setTitle("Select Worksheet")
                .setItems(names, (d, i) -> { sheetIndex = i; firstRow = 1; firstCol = 1; showSheet(); }).show();
    }

    private void promptChooseSlide() {
        if (slides == null) return;
        String[] names = new String[slides.slides.size()];
        for (int i = 0; i < names.length; i++) names[i] = slides.slides.get(i).name;
        new AlertDialog.Builder(this).setTitle("Jump to Slide")
                .setItems(names, (d, i) -> { slideIndex = i; showSlide(); }).show();
    }

    private TextView pdfAction(String label, boolean chosen) {
        TextView button = text(label, 12, chosen ? onBrand() : ink(), true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rippleBg(chosen ? brand() : card(), 8, chosen ? 0 : border()));
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
                entry.getValue().setTextColor(selected ? onBrand() : ink());
                entry.getValue().setBackground(rippleBg(selected ? brand() : card(), 8, selected ? 0 : border()));
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
    private void error(String s) { new AlertDialog.Builder(this).setTitle("Documents").setMessage(s).setPositiveButton("OK", null).show(); }

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
