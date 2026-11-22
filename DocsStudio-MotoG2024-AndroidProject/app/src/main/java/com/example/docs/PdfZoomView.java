package com.example.docs;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline PDF viewer with touch zoom, rotation, reading themes, and page annotations.
 * Fix: PdfRenderer composites onto existing bitmap pixels; transparent PDFs previously
 * rendered black text onto dark #0E141D bitmaps. Now renders onto crisp white paper
 * clipped strictly to page bounds, with optional Sepia and luminance-inverted Night modes.
 */
public final class PdfZoomView extends View {
    public interface PageListener {
        void onPageChanged(int current, int total);
        void onTap();
        void onError(String message);
        void onAnnotationsChanged();
    }

    public static final int VIEW = 0, PEN = 1, HIGHLIGHT = 2, SIGN = 3, TEXT = 4,
            UNDERLINE = 5, RECTANGLE = 6, ERASER = 7, ARROW = 8;

    public static final int THEME_PAPER = 0, THEME_SEPIA = 1, THEME_NIGHT = 2;

    /** Deep-copiable annotation in unrotated PDF point coordinates. */
    public static final class Annotation {
        public int page, type, color;
        public float width;
        public String text = "";
        public final ArrayList<PointF> points = new ArrayList<>();

        Annotation(int p, int t, int c, float w) {
            page = p;
            type = t;
            color = c;
            width = w;
        }

        Annotation copy() {
            Annotation a = new Annotation(page, type, color, width);
            a.text = text;
            for (PointF q : points) a.points.add(new PointF(q.x, q.y));
            return a;
        }
    }

    private static final class EditAction {
        final ArrayList<Annotation> added = new ArrayList<>(), removed = new ArrayList<>();
    }

    private static final int WORKSPACE_BG = Color.rgb(11, 17, 32);
    private static final int PAPER_SEPIA = Color.rgb(251, 245, 231);
    private static final ColorMatrixColorFilter NIGHT_FILTER = new ColorMatrixColorFilter(new float[]{
            -0.90f, 0f, 0f, 0f, 240f,
            0f, -0.90f, 0f, 0f, 242f,
            0f, 0f, -0.88f, 0f, 248f,
            0f, 0f, 0f, 1f, 0f
    });

    private final PdfRenderer pdf;
    private final ParcelFileDescriptor descriptor;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint paperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final Runnable debouncedRender = this::submitRender;

    private volatile int renderGeneration = 0;
    private volatile boolean released = false;
    private Bitmap rendered;
    private int pageNumber = 0;
    private int pageWidth = 612, pageHeight = 792;
    private int rotation = 0; // 0, 90, 180, 270
    private int readingTheme = THEME_PAPER;
    private float zoom = 1f, panX = 0f, panY = 0f;
    private float renderedZoom = 1f, renderedPanX = 0f, renderedPanY = 0f;
    private int renderedRotation = 0;
    private float lastX, lastY, lastMidX, lastMidY;
    private boolean multiTouch = false;
    private int tool = VIEW;
    private int penColor = Color.rgb(20, 184, 166);
    private int markerColor = Color.rgb(250, 204, 21);
    private int penSize = 2;
    private Annotation draft;
    private final ArrayList<Annotation> annotations = new ArrayList<>();
    private final ArrayList<EditAction> undo = new ArrayList<>(), redo = new ArrayList<>();
    private PageListener listener;

    public PdfZoomView(Context context, PdfRenderer renderer, ParcelFileDescriptor file) {
        super(context);
        pdf = renderer;
        descriptor = file;
        setBackgroundColor(WORKSPACE_BG);
        shadowPaint.setColor(Color.argb(95, 0, 0, 0));

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                setZoomAt(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (tool != VIEW) return false;
                setZoomAt(zoom < 1.8f ? 2.6f : 1f, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tool == VIEW && listener != null) {
                    listener.onTap();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent start, MotionEvent end, float vx, float vy) {
                if (tool != VIEW || zoom > 1.03f || start == null || end == null) return false;
                float dx = end.getX() - start.getX();
                if (Math.abs(dx) < dp(70) || Math.abs(dx) < Math.abs(end.getY() - start.getY()) * 1.3f)
                    return false;
                if (dx < 0) next();
                else previous();
                return true;
            }
        });
        updatePageSize();
    }

    public void setPageListener(PageListener p) {
        listener = p;
        notifyPage();
    }

    public void setTool(int value) {
        tool = value;
        draft = null;
        invalidate();
    }

    public int getTool() { return tool; }
    public void setPenColor(int color) { penColor = color; }
    public void setMarkerColor(int color) { markerColor = color; }
    public void setPenSize(int value) { penSize = Math.max(1, Math.min(5, value)); }
    public int getPenSize() { return penSize; }
    public int getPenColor() { return penColor; }

    public void setReadingTheme(int theme) {
        readingTheme = Math.max(THEME_PAPER, Math.min(THEME_NIGHT, theme));
        requestRender();
        invalidate();
    }

    public int getReadingTheme() { return readingTheme; }

    public void rotateClockwise() {
        rotation = (rotation + 90) % 360;
        zoom = 1f;
        panX = 0f;
        panY = 0f;
        requestRender();
        invalidate();
    }

    public int getRotationDegrees() { return rotation; }
    public boolean hasAnnotations() { return !annotations.isEmpty(); }
    public int getAnnotationCountForCurrentPage() {
        int c = 0;
        for (Annotation a : annotations) if (a.page == pageNumber) c++;
        return c;
    }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public int getPageCount() { return pdf.getPageCount(); }
    public int getPageNumber() { return pageNumber; }
    public int getPageWidthPoints() { return pageWidth; }
    public int getPageHeightPoints() { return pageHeight; }
    public int getZoomPercent() { return Math.round(zoom * 100f); }

    private float dp(float n) { return n * getResources().getDisplayMetrics().density; }

    public void next() { setPage(pageNumber + 1); }
    public void previous() { setPage(pageNumber - 1); }
    public void resetZoom() { setZoomAt(1f, getWidth() * 0.5f, getHeight() * 0.5f); }
    public void setZoomPreset(float targetZoom) { setZoomAt(targetZoom, getWidth() * 0.5f, getHeight() * 0.5f); }
    public void zoomBy(float factor) { setZoomAt(zoom * factor, getWidth() * 0.5f, getHeight() * 0.5f); }

    public ArrayList<Annotation> snapshot() {
        ArrayList<Annotation> copy = new ArrayList<>();
        for (Annotation a : annotations) copy.add(a.copy());
        return copy;
    }

    public void undo() {
        if (undo.isEmpty()) return;
        EditAction action = undo.remove(undo.size() - 1);
        annotations.removeAll(action.added);
        annotations.addAll(action.removed);
        redo.add(action);
        changed();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        EditAction action = redo.remove(redo.size() - 1);
        annotations.removeAll(action.removed);
        annotations.addAll(action.added);
        undo.add(action);
        changed();
    }

    public boolean clearCurrentPage() {
        EditAction action = new EditAction();
        for (Annotation a : annotations) if (a.page == pageNumber) action.removed.add(a);
        if (action.removed.isEmpty()) return false;
        commit(action);
        return true;
    }

    private void changed() {
        invalidate();
        if (listener != null) listener.onAnnotationsChanged();
    }

    private void commit(EditAction action) {
        annotations.removeAll(action.removed);
        annotations.addAll(action.added);
        undo.add(action);
        redo.clear();
        changed();
    }

    private void add(Annotation a) {
        EditAction action = new EditAction();
        action.added.add(a);
        commit(action);
    }

    public void setPage(int index) {
        if (released || index < 0 || index >= pdf.getPageCount() || index == pageNumber) return;
        pageNumber = index;
        Bitmap b = rendered;
        rendered = null;
        if (b != null && !b.isRecycled()) b.recycle();
        draft = null;
        zoom = 1f;
        panX = panY = 0f;
        updatePageSize();
        notifyPage();
        requestRender();
        invalidate();
    }

    private void updatePageSize() {
        final int index = pageNumber;
        ++renderGeneration;
        worker.execute(() -> {
            if (released || index != pageNumber) return;
            try (PdfRenderer.Page page = pdf.openPage(index)) {
                final int w = page.getWidth(), h = page.getHeight();
                main.post(() -> {
                    if (released || index != pageNumber) return;
                    pageWidth = Math.max(1, w);
                    pageHeight = Math.max(1, h);
                    requestRender();
                    invalidate();
                });
            } catch (Exception e) {
                report(e);
            }
        });
    }

    private void notifyPage() {
        if (listener != null && !released) listener.onPageChanged(pageNumber + 1, pdf.getPageCount());
    }

    private float rotatedWidth() {
        return (rotation == 90 || rotation == 270) ? pageHeight : pageWidth;
    }

    private float rotatedHeight() {
        return (rotation == 90 || rotation == 270) ? pageWidth : pageHeight;
    }

    private void setZoomAt(float proposed, float focusX, float focusY) {
        if (released || getWidth() < 1 || getHeight() < 1) return;
        float next = Math.max(1f, Math.min(8f, proposed));
        if (Math.abs(next - zoom) < 0.001f) return;
        float cx = getWidth() * 0.5f, cy = getHeight() * 0.5f, ratio = next / zoom;
        panX = (focusX - cx) - (focusX - cx - panX) * ratio;
        panY = (focusY - cy) - (focusY - cy - panY) * ratio;
        zoom = next;
        clampPan();
        invalidate();
        requestRender();
        notifyPage();
    }

    private void clampPan() {
        if (getWidth() < 1 || getHeight() < 1) return;
        float rw = rotatedWidth(), rh = rotatedHeight();
        float fit = Math.min(getWidth() / rw, getHeight() / rh);
        float mx = Math.max(0, (rw * fit * zoom - getWidth()) * 0.5f);
        float my = Math.max(0, (rh * fit * zoom - getHeight()) * 0.5f);
        panX = Math.max(-mx, Math.min(mx, panX));
        panY = Math.max(-my, Math.min(my, panY));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        panX = panY = 0;
        requestRender();
    }

    private void requestRender() {
        if (released || getWidth() < 1 || getHeight() < 1) return;
        ++renderGeneration;
        main.removeCallbacks(debouncedRender);
        main.postDelayed(debouncedRender, 65);
    }

    private static Matrix buildPageMatrix(int pw, int ph, int rot, float scale, float left, float top) {
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        if (rot == 90) {
            m.postRotate(90f);
            m.postTranslate(ph * scale, 0f);
        } else if (rot == 180) {
            m.postRotate(180f);
            m.postTranslate(pw * scale, ph * scale);
        } else if (rot == 270) {
            m.postRotate(270f);
            m.postTranslate(0f, pw * scale);
        }
        m.postTranslate(left, top);
        return m;
    }

    private void submitRender() {
        if (released || getWidth() < 1 || getHeight() < 1) return;
        final int token = renderGeneration, index = pageNumber, w = getWidth(), h = getHeight();
        final float z = zoom, px = panX, py = panY;
        final int rot = rotation, theme = readingTheme;
        worker.execute(() -> {
            if (released || token != renderGeneration) return;
            Bitmap target = null;
            try (PdfRenderer.Page page = pdf.openPage(index)) {
                int pw = page.getWidth(), ph = page.getHeight();
                float rw = (rot == 90 || rot == 270) ? ph : pw;
                float rh = (rot == 90 || rot == 270) ? pw : ph;
                float fit = Math.min(w / rw, h / rh);
                float s = fit * z;
                float left = (w - rw * s) * 0.5f + px;
                float top = (h - rh * s) * 0.5f + py;

                Matrix transform = buildPageMatrix(pw, ph, rot, s, left, top);

                target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                // CRITICAL FIX: Paint the paper sheet white (or sepia) BEFORE PdfRenderer.render().
                // Never paint the page canvas dark prior to rendering black PDF text.
                int paperColor = (theme == THEME_SEPIA) ? PAPER_SEPIA : Color.WHITE;
                target.eraseColor(paperColor);

                Rect destClip = new Rect(
                        Math.max(0, (int) Math.floor(left)),
                        Math.max(0, (int) Math.floor(top)),
                        Math.min(w, (int) Math.ceil(left + rw * s)),
                        Math.min(h, (int) Math.ceil(top + rh * s))
                );
                page.render(target, destClip, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                final Bitmap ready = target;
                main.post(() -> {
                    if (released || token != renderGeneration || index != pageNumber) {
                        ready.recycle();
                        return;
                    }
                    Bitmap old = rendered;
                    rendered = ready;
                    renderedZoom = z;
                    renderedPanX = px;
                    renderedPanY = py;
                    renderedRotation = rot;
                    if (old != null && old != ready && !old.isRecycled()) old.recycle();
                    invalidate();
                });
            } catch (Throwable e) {
                if (target != null && !target.isRecycled()) target.recycle();
                report(e);
            }
        });
    }

    private void report(Throwable error) {
        main.post(() -> {
            if (!released && listener != null) {
                listener.onError(error.getMessage() == null ? "Unable to render PDF page." : error.getMessage());
            }
        });
    }

    private float scale() {
        return Math.min(getWidth() / rotatedWidth(), getHeight() / rotatedHeight()) * zoom;
    }

    private PointF screenToPage(float x, float y) {
        float s = Math.max(0.01f, scale());
        float rw = rotatedWidth(), rh = rotatedHeight();
        float left = (getWidth() - rw * s) * 0.5f + panX;
        float top = (getHeight() - rh * s) * 0.5f + panY;
        float vx = (x - left) / s;
        float vy = (y - top) / s;
        if (rotation == 90) return new PointF(vy, pageHeight - vx);
        if (rotation == 180) return new PointF(pageWidth - vx, pageHeight - vy);
        if (rotation == 270) return new PointF(pageWidth - vy, vx);
        return new PointF(vx, vy);
    }

    private boolean insidePage(PointF p) {
        return p.x >= 0 && p.y >= 0 && p.x <= pageWidth && p.y <= pageHeight;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(WORKSPACE_BG);

        float s = Math.max(0.01f, scale());
        float rw = rotatedWidth(), rh = rotatedHeight();
        float left = (getWidth() - rw * s) * 0.5f + panX;
        float top = (getHeight() - rh * s) * 0.5f + panY;
        RectF pageRect = new RectF(left, top, left + rw * s, top + rh * s);

        // Draw subtle shadow and guaranteed paper backdrop so zooming never flashes black.
        canvas.drawRect(pageRect.left + dp(2), pageRect.top + dp(3), pageRect.right + dp(2), pageRect.bottom + dp(4), shadowPaint);
        if (readingTheme == THEME_NIGHT) {
            paperPaint.setColor(Color.rgb(18, 24, 38));
        } else if (readingTheme == THEME_SEPIA) {
            paperPaint.setColor(PAPER_SEPIA);
        } else {
            paperPaint.setColor(Color.WHITE);
        }
        canvas.drawRect(pageRect, paperPaint);

        if (rendered != null && !rendered.isRecycled() && renderedRotation == rotation) {
            float ratio = zoom / renderedZoom, cx = getWidth() * 0.5f, cy = getHeight() * 0.5f;
            bitmapPaint.setColorFilter(readingTheme == THEME_NIGHT ? NIGHT_FILTER : null);
            canvas.save();
            canvas.clipRect(pageRect);
            canvas.translate(cx + panX - (cx + renderedPanX) * ratio,
                    cy + panY - (cy + renderedPanY) * ratio);
            canvas.scale(ratio, ratio);
            canvas.drawBitmap(rendered, 0, 0, bitmapPaint);
            canvas.restore();
        }

        canvas.save();
        canvas.clipRect(pageRect);
        canvas.translate(left, top);
        if (rotation == 90) {
            canvas.translate(pageHeight * s, 0);
            canvas.rotate(90f);
        } else if (rotation == 180) {
            canvas.translate(pageWidth * s, pageHeight * s);
            canvas.rotate(180f);
        } else if (rotation == 270) {
            canvas.translate(0, pageWidth * s);
            canvas.rotate(270f);
        }
        canvas.scale(s, s);
        canvas.clipRect(0, 0, pageWidth, pageHeight);
        drawOnPdf(canvas, annotations, pageNumber);
        if (draft != null) drawOne(canvas, draft);
        canvas.restore();
    }

    /** Draw annotations at native PDF point scale onto a PdfDocument page. */
    public static void drawOnPdf(Canvas canvas, List<Annotation> items, int pageNo) {
        for (Annotation a : items) if (a.page == pageNo) drawOne(canvas, a);
    }

    private static void drawOne(Canvas canvas, Annotation a) {
        if (a.points.isEmpty()) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(a.color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(a.width);
        if (a.type == HIGHLIGHT) p.setAlpha(92);

        if (a.type == TEXT) {
            p.setStyle(Paint.Style.FILL);
            p.setAlpha(255);
            p.setTextSize(Math.max(12, a.width));
            float y = a.points.get(0).y;
            String[] lines = a.text.split("\n", -1);
            for (String line : lines) {
                canvas.drawText(line, a.points.get(0).x, y, p);
                y += p.getTextSize() * 1.25f;
            }
            return;
        }
        if ((a.type == RECTANGLE || a.type == UNDERLINE || a.type == ARROW) && a.points.size() > 1) {
            PointF first = a.points.get(0), last = a.points.get(a.points.size() - 1);
            if (a.type == RECTANGLE) {
                canvas.drawRect(new RectF(
                        Math.min(first.x, last.x), Math.min(first.y, last.y),
                        Math.max(first.x, last.x), Math.max(first.y, last.y)), p);
            } else if (a.type == UNDERLINE) {
                canvas.drawLine(first.x, first.y, last.x, last.y, p);
            } else {
                canvas.drawLine(first.x, first.y, last.x, last.y, p);
                double angle = Math.atan2(last.y - first.y, last.x - first.x);
                float headLen = Math.max(10f, a.width * 3.5f);
                float x1 = (float) (last.x - headLen * Math.cos(angle - Math.PI / 6));
                float y1 = (float) (last.y - headLen * Math.sin(angle - Math.PI / 6));
                float x2 = (float) (last.x - headLen * Math.cos(angle + Math.PI / 6));
                float y2 = (float) (last.y - headLen * Math.sin(angle + Math.PI / 6));
                canvas.drawLine(last.x, last.y, x1, y1, p);
                canvas.drawLine(last.x, last.y, x2, y2, p);
            }
            return;
        }
        PointF first = a.points.get(0);
        if (a.points.size() == 1) {
            p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(first.x, first.y, a.width * 0.5f, p);
            return;
        }
        Path path = new Path();
        path.moveTo(first.x, first.y);
        for (int i = 1; i < a.points.size(); i++) {
            PointF q = a.points.get(i);
            path.lineTo(q.x, q.y);
        }
        canvas.drawPath(path, p);
    }

    private float widthForTool() {
        if (tool == HIGHLIGHT) return 10f + penSize * 2.2f;
        if (tool == SIGN) return 1.1f + penSize * 0.4f;
        if (tool == TEXT) return 12f + penSize * 2f;
        return 0.9f + penSize * 0.85f;
    }

    private int colorForTool() {
        if (tool == HIGHLIGHT) return markerColor;
        if (tool == SIGN) return Color.rgb(37, 99, 235);
        return penColor;
    }

    private void eraseAt(PointF point) {
        Annotation closest = null;
        float best = Math.max(12f, dp(16) / Math.max(0.01f, scale()));
        for (Annotation a : annotations) {
            if (a.page != pageNumber) continue;
            for (PointF p : a.points) {
                float d = (float) Math.hypot(point.x - p.x, point.y - p.y);
                if (d < best) {
                    best = d;
                    closest = a;
                }
            }
        }
        if (closest != null) {
            EditAction action = new EditAction();
            action.removed.add(closest);
            commit(action);
        }
    }

    private void promptText(final PointF at) {
        EditText entry = new EditText(getContext());
        entry.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        entry.setSingleLine(false);
        entry.setMinLines(2);
        entry.setHint("Text to place on this page");
        entry.setPadding(28, 18, 28, 18);
        new AlertDialog.Builder(getContext()).setTitle("Add text to PDF")
                .setView(entry).setPositiveButton("Place", (d, w) -> {
                    String content = entry.getText().toString().trim();
                    if (content.isEmpty()) return;
                    Annotation a = new Annotation(pageNumber, TEXT, colorForTool(), widthForTool());
                    a.text = content;
                    a.points.add(at);
                    add(a);
                }).setNegativeButton("Cancel", null).show();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (released) return false;
        if (tool == VIEW) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    int rest = event.getActionIndex() == 0 ? 1 : 0;
                    if (rest < event.getPointerCount()) {
                        lastX = event.getX(rest);
                        lastY = event.getY(rest);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 1 && !scaleDetector.isInProgress() && zoom > 1.01f) {
                        float x = event.getX(), y = event.getY();
                        panX += x - lastX;
                        panY += y - lastY;
                        clampPan();
                        invalidate();
                        requestRender();
                        lastX = x;
                        lastY = y;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    requestRender();
                    return true;
                default:
                    return true;
            }
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
            draft = null;
            multiTouch = true;
            scaleDetector.onTouchEvent(event);
            if (event.getPointerCount() > 1) {
                float mx = (event.getX(0) + event.getX(1)) * 0.5f;
                float my = (event.getY(0) + event.getY(1)) * 0.5f;
                if (action == MotionEvent.ACTION_MOVE) {
                    panX += mx - lastMidX;
                    panY += my - lastMidY;
                    clampPan();
                    requestRender();
                    invalidate();
                }
                lastMidX = mx;
                lastMidY = my;
            }
            if (action == MotionEvent.ACTION_POINTER_UP) multiTouch = true;
            return true;
        }
        if (multiTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) multiTouch = false;
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            PointF p = screenToPage(event.getX(), event.getY());
            if (!insidePage(p)) return true;
            if (tool == ERASER) { eraseAt(p); return true; }
            if (tool == TEXT) { promptText(p); return true; }
            draft = new Annotation(pageNumber, tool, colorForTool(), widthForTool());
            draft.points.add(p);
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && draft != null) {
            PointF p = screenToPage(event.getX(), event.getY());
            p.x = Math.max(0, Math.min(pageWidth, p.x));
            p.y = Math.max(0, Math.min(pageHeight, p.y));
            if (draft.type == RECTANGLE || draft.type == UNDERLINE || draft.type == ARROW) {
                if (draft.points.size() == 1) draft.points.add(p);
                else draft.points.set(1, p);
            } else {
                PointF last = draft.points.get(draft.points.size() - 1);
                if (Math.hypot(last.x - p.x, last.y - p.y) > 0.4f) draft.points.add(p);
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (draft != null) {
                Annotation committed = draft;
                draft = null;
                add(committed);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            draft = null;
            invalidate();
            return true;
        }
        return true;
    }

    public void release() {
        if (released) return;
        released = true;
        renderGeneration++;
        main.removeCallbacks(debouncedRender);
        Bitmap b = rendered;
        rendered = null;
        if (b != null && !b.isRecycled()) b.recycle();
        worker.execute(() -> {
            try { pdf.close(); } catch (Exception ignored) {}
            try { descriptor.close(); } catch (Exception ignored) {}
        });
        worker.shutdown();
    }
}
