package com.example.docs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class DocumentToolbox {
    public static final int PICK_IMAGES = 6101, PICK_OCR = 6102, PICK_PDF = 6103;
    public static final int SAVE_PDF = 6104, SAVE_TEXT = 6105, CAMERA_OCR = 6106;
    private final MainActivity activity;
    private final ArrayList<Uri> imageUris = new ArrayList<>();
    private Uri pdfUri;
    private byte[] pendingBytes;
    private String pendingName = "Document.pdf";
    private String pageSelection = "1";
    private boolean working;

    public DocumentToolbox(MainActivity host) { activity = host; }

    private void message(String title, String message) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                .setPositiveButton("OK", null).show();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    public void show() {
        String[] names = {
                "Scan image text (offline OCR)",
                "Take a photo and recognize text",
                "Convert photos / images to PDF",
                "Extract selected PDF pages",
                "Create PDF from typed text",
                "Quick document templates",
                "Text cleanup & formatting",
                "Word and character counter",
                "Document formatting tips",
                "Privacy & offline processing"
        };
        new AlertDialog.Builder(activity).setTitle("Document Toolbox")
                .setItems(names, (dialog, index) -> {
                    switch (index) {
                        case 0: pick("image/*", false, PICK_OCR); break;
                        case 1:
                            try {
                                Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                                activity.startActivityForResult(camera, CAMERA_OCR);
                            } catch (Exception e) { message("Camera", "No compatible camera app was found."); }
                            break;
                        case 2: pick("image/*", true, PICK_IMAGES); break;
                        case 3: pick("application/pdf", false, PICK_PDF); break;
                        case 4: typePdf(); break;
                        case 5: templates(); break;
                        case 6: cleanup(); break;
                        case 7: wordCounter(); break;
                        case 8: message("Formatting tips", "PDF: use Pen, Highlight, Sign, Text, and Export to save annotations.\n\nDOCX: save a copy after editing.\n\nCSV: separate values with commas, use quotes around values containing commas.\n\nMarkdown: use # for headings and - for bullet points."); break;
                        case 9: message("Your privacy", "The bundled text recognizer runs on your device. Photos, text, and PDF documents are processed locally. Choosing Share or Save passes content only to the destination app or folder you select."); break;
                    }
                }).show();
    }

    private void pick(String mime, boolean multiple, int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType(mime);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { activity.startActivityForResult(i, request); }
        catch (Exception e) { message("File picker", "Could not open the file picker: " + e.getMessage()); }
    }

    public boolean handles(int code) {
        return code >= PICK_IMAGES && code <= CAMERA_OCR;
    }

    public void result(int request, int result, Intent data) {
        if (!handles(request) || result != Activity.RESULT_OK || data == null) return;
        if (request == CAMERA_OCR) {
            Object thumbnail = data.getExtras() == null ? null : data.getExtras().get("data");
            if (thumbnail instanceof Bitmap) recognize((Bitmap) thumbnail);
            else message("Camera", "No image was returned by the camera.");
            return;
        }
        if (request == SAVE_PDF || request == SAVE_TEXT) {
            Uri target = data.getData();
            if (target == null || pendingBytes == null) return;
            byte[] resultBytes = pendingBytes;
            pendingBytes = null;
            try (OutputStream output = activity.getContentResolver().openOutputStream(target, "wt")) {
                if (output == null) throw new IOException("Cannot write selected file.");
                output.write(resultBytes);
                output.flush();
                toast("Saved successfully");
            } catch (Exception e) { message("Save failed", e.getMessage()); }
            return;
        }
        if (request == PICK_OCR && data.getData() != null) {
            Uri selected = data.getData();
            background("Recognizing image text", () -> {
                Bitmap b = readImage(selected, 2048);
                activity.runOnUiThread(() -> recognize(b));
            });
        } else if (request == PICK_IMAGES) {
            imageUris.clear();
            if (data.getData() != null) imageUris.add(data.getData());
            if (data.getClipData() != null) {
                for (int x = 0; x < data.getClipData().getItemCount(); x++) {
                    Uri uri = data.getClipData().getItemAt(x).getUri();
                    if (!imageUris.contains(uri)) imageUris.add(uri);
                }
            }
            if (!imageUris.isEmpty()) imagesToPdf();
        } else if (request == PICK_PDF && data.getData() != null) {
            pdfUri = data.getData();
            choosePages();
        }
    }

    private interface Task { void run() throws Exception; }

    private void background(String label, Task task) {
        if (working) { toast("Another document task is in progress."); return; }
        working = true;
        activity.runOnUiThread(() -> toast(label + "…"));
        new Thread(() -> {
            try { task.run(); }
            catch (Throwable e) {
                activity.runOnUiThread(() -> message(label + " failed",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally { working = false; }
        }, "DocumentToolbox").start();
    }

    private Bitmap readImage(Uri uri, int maxSide) throws IOException {
        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("Cannot open image");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(stream, null, options);
            int scale = 1;
            while (Math.max(options.outWidth, options.outHeight) / scale > maxSide) scale *= 2;
            options.inSampleSize = scale;
            options.inJustDecodeBounds = false;
            try (InputStream raw = activity.getContentResolver().openInputStream(uri)) {
                Bitmap b = BitmapFactory.decodeStream(raw, null, options);
                if (b == null) throw new IOException("Unsupported image");
                return b;
            }
        }
    }

    private void recognize(Bitmap image) {
        if (image == null) return;
        final com.google.mlkit.vision.text.TextRecognizer recognizer =
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener(text -> {
                    recognizer.close();
                    showRecognizedText(text.getText());
                })
                .addOnFailureListener(e -> {
                    recognizer.close();
                    message("Text recognition failed", e.getMessage());
                });
    }

    private void showRecognizedText(String words) {
        EditText edit = field(words.isEmpty() ? "" : words, 12);
        new AlertDialog.Builder(activity).setTitle("Recognized text")
                .setView(edit)
                .setPositiveButton("Save TXT", (d,w) -> saveText(edit.getText().toString(), "Recognized-text.txt"))
                .setNeutralButton("Copy", (d,w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Recognized text", edit.getText().toString()));
                    toast("Copied to clipboard");
                })
                .setNegativeButton("Close", null).show();
    }

    private EditText field(String contents, int lines) {
        EditText edit = new EditText(activity);
        edit.setText(contents);
        edit.setTextSize(15);
        edit.setMinLines(Math.min(4, lines));
        edit.setMaxLines(lines);
        edit.setPadding(28, 20, 28, 20);
        edit.setGravity(Gravity.TOP | Gravity.START);
        return edit;
    }

    private void imagesToPdf() {
        int count = imageUris.size();
        if (count > 40) { message("Too many images", "Select up to 40 images per PDF to keep memory use manageable."); return; }
        background("Creating image PDF", () -> {
            PdfDocument doc = new PdfDocument();
            try {
                int pageNo = 1;
                for (Uri u : imageUris) {
                    Bitmap image = readImage(u, 2200);
                    try {
                        int w = 595, h = 842;
                        PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(w, h, pageNo++).create());
                        Canvas c = page.getCanvas();
                        c.drawColor(Color.WHITE);
                        float fit = Math.min((w - 48f) / image.getWidth(), (h - 48f) / image.getHeight());
                        float dw = image.getWidth() * fit, dh = image.getHeight() * fit;
                        Rect to = new Rect(Math.round((w - dw) / 2), Math.round((h - dh) / 2),
                                Math.round((w + dw) / 2), Math.round((h + dh) / 2));
                        c.drawBitmap(image, null, to, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
                        doc.finishPage(page);
                    } finally { image.recycle(); }
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                doc.writeTo(buffer);
                byte[] bytes = buffer.toByteArray();
                activity.runOnUiThread(() -> savePdf(bytes, "Images-converted.pdf"));
            } finally { doc.close(); }
        });
    }

    private void choosePages() {
        EditText range = field("1-3", 1);
        range.setSingleLine(true);
        new AlertDialog.Builder(activity).setTitle("Extract PDF pages")
                .setMessage("Enter pages such as 1,3,5-8. The selected pages will become a new PDF (rendered pages).")
                .setView(range)
                .setPositiveButton("Extract", (dialog, which) -> {
                    pageSelection = range.getText().toString().trim();
                    extractPdf();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private List<Integer> pages(String expression, int max) throws IOException {
        ArrayList<Integer> result = new ArrayList<>();
        if (expression.isEmpty()) throw new IOException("Enter at least one page number.");
        for (String token : expression.split(",")) {
            String[] parts = token.trim().split("-");
            int first, last;
            try {
                first = Integer.parseInt(parts[0].trim());
                last = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : first;
            } catch (Exception e) { throw new IOException("Invalid page range: " + token); }
            if (parts.length > 2 || first < 1 || last < first || last > max)
                throw new IOException("Page range outside 1-" + max + ": " + token);
            for (int page = first; page <= last; page++) {
                if (!result.contains(page - 1)) result.add(page - 1);
                if (result.size() > 60) throw new IOException("Extract up to 60 pages at once.");
            }
        }
        return result;
    }

    private void extractPdf() {
        final Uri source = pdfUri;
        final String selection = pageSelection;
        background("Extracting PDF pages", () -> {
            PdfDocument output = new PdfDocument();
            try (ParcelFileDescriptor fd = activity.getContentResolver().openFileDescriptor(source, "r");
                 PdfRenderer renderer = new PdfRenderer(fd)) {
                if (fd == null) throw new IOException("Cannot open PDF");
                List<Integer> indices = pages(selection, renderer.getPageCount());
                int pageNo = 1;
                for (int index : indices) {
                    try (PdfRenderer.Page original = renderer.openPage(index)) {
                        int w = original.getWidth(), h = original.getHeight();
                        double scale = Math.min(2.0, Math.sqrt(3500000.0 / Math.max(1, (long) w * h)));
                        Bitmap rendered = Bitmap.createBitmap(Math.max(1, (int) (w * scale)),
                                Math.max(1, (int) (h * scale)), Bitmap.Config.ARGB_8888);
                        try {
                            rendered.eraseColor(Color.WHITE);
                            Matrix matrix = new Matrix();
                            matrix.setScale((float) scale, (float) scale);
                            original.render(rendered, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                            PdfDocument.Page page = output.startPage(new PdfDocument.PageInfo.Builder(w,h,pageNo++).create());
                            page.getCanvas().drawColor(Color.WHITE);
                            page.getCanvas().drawBitmap(rendered, null, new Rect(0,0,w,h),new Paint(Paint.FILTER_BITMAP_FLAG));
                            output.finishPage(page);
                        } finally { rendered.recycle(); }
                    }
                }
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                output.writeTo(stream);
                byte[] bytes = stream.toByteArray();
                activity.runOnUiThread(() -> savePdf(bytes, "Extracted-pages.pdf"));
            } finally { output.close(); }
        });
    }

    private void typePdf() {
        EditText edit = field("Document title\n\nType your content here.", 14);
        new AlertDialog.Builder(activity).setTitle("Create PDF from text")
                .setView(edit)
                .setPositiveButton("Create PDF", (d,w) -> createTextPdf(edit.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private void createTextPdf(String text) {
        background("Creating text PDF", () -> {
            PdfDocument pdf = new PdfDocument();
            try {
                String[] lines = text.split("\\r?\\n", -1);
                int pageNo = 0, cursor = 0;
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(Color.rgb(35, 45, 60));
                paint.setTextSize(12f);
                while (cursor < lines.length && pageNo < 120) {
                    PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,++pageNo).create());
                    Canvas canvas = page.getCanvas();
                    canvas.drawColor(Color.WHITE);
                    float y = 60;
                    while (cursor < lines.length && y < 794) {
                        String line = lines[cursor++];
                        if (line.isEmpty()) { y += 18; continue; }
                        String remaining = line;
                        while (!remaining.isEmpty() && y < 794) {
                            int end = paint.breakText(remaining, true, 490, null);
                            end = Math.max(1, end);
                            canvas.drawText(remaining.substring(0,end), 52, y, paint);
                            remaining = remaining.substring(end);
                            y += 18;
                        }
                    }
                    pdf.finishPage(page);
                }
                if (cursor < lines.length) throw new IOException("Document exceeds the 120-page limit.");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                pdf.writeTo(bytes);
                activity.runOnUiThread(() -> savePdf(bytes.toByteArray(), "Created-document.pdf"));
            } finally { pdf.close(); }
        });
    }

    private void templates() {
        String[] names = {"Meeting notes", "To-do checklist", "Invoice / expenses", "Project plan", "Daily journal", "Resume outline", "Cover letter", "Study notes"};
        String[] bodies = {
            "MEETING NOTES\nDate:\nAttendees:\nTopic:\n\nAgenda:\n- \n\nDecisions:\n- \n\nAction items:\n- [ ] Task / owner / due date\n",
            "TO-DO LIST\nDate:\n\nPriority tasks\n[ ] \n[ ] \n\nLater\n[ ] \n\nCompleted\n",
            "EXPENSE TRACKER\nDate,Description,Category,Amount\n,,,\n,,,\n\nSubtotal:\nTax:\nTotal:\n",
            "PROJECT PLAN\nProject name:\nObjective:\nOwner:\nDeadline:\n\nMilestones\n1. \n2. \n3. \n\nRisks and mitigation:\n\nNext actions:\n",
            "DAILY JOURNAL\nDate:\n\nToday's priorities:\n\nNotes:\n\nGratitude:\n\nTomorrow:\n",
            "RESUME\nFull name / contact\n\nProfessional summary\n\nSkills\n\nExperience\nRole, organization, dates\n- Accomplishment\n\nEducation\n",
            "COVER LETTER\nDate:\nHiring manager:\nOrganization:\n\nDear Hiring Manager,\n\nI am writing to apply for...\n\nMy relevant experience includes...\n\nThank you for your consideration.\n\nSincerely,\n",
            "STUDY NOTES\nTopic:\nDate:\n\nKey concepts:\n- \n\nExamples:\n\nQuestions:\n\nSummary:\n"
        };
        new AlertDialog.Builder(activity).setTitle("Document templates")
                .setItems(names, (d, selected) -> {
                    EditText edit = field(bodies[selected], 14);
                    new AlertDialog.Builder(activity).setTitle(names[selected])
                            .setView(edit)
                            .setPositiveButton("Save TXT", (a,b) ->
                                    saveText(edit.getText().toString(), names[selected].replace(" / ", "-").replace(" ", "-") + ".txt"))
                            .setNegativeButton("Cancel", null).show();
                }).show();
    }

    private void cleanup() {
        EditText input = field("", 14);
        new AlertDialog.Builder(activity).setTitle("Paste text to clean up")
                .setView(input)
                .setPositiveButton("Continue", (d,w) -> {
                    String text = input.getText().toString();
                    String[] tools = {"Trim trailing spaces", "Remove empty lines", "Normalize multiple spaces", "UPPERCASE", "lowercase", "Sort lines"};
                    new AlertDialog.Builder(activity).setTitle("Text formatting")
                            .setItems(tools, (dd,index) -> {
                                String value = text;
                                switch (index) {
                                    case 0: value = value.replaceAll("(?m)[ \\t]+$", ""); break;
                                    case 1: value = value.replaceAll("(?m)^[ \\t]*\\r?\\n", ""); break;
                                    case 2: value = value.replaceAll("[ \\t]{2,}", " "); break;
                                    case 3: value = value.toUpperCase(java.util.Locale.ROOT); break;
                                    case 4: value = value.toLowerCase(java.util.Locale.ROOT); break;
                                    case 5: String[] lines = value.split("\\r?\\n"); java.util.Arrays.sort(lines, String.CASE_INSENSITIVE_ORDER); value = android.text.TextUtils.join("\n", lines); break;
                                }
                                showRecognizedText(value);
                            }).show();
                }).setNegativeButton("Cancel", null).show();
    }

    private void wordCounter() {
        EditText input = field("", 12);
        new AlertDialog.Builder(activity).setTitle("Count text")
                .setView(input)
                .setPositiveButton("Count", (d,w) -> {
                    String value = input.getText().toString();
                    int words = value.trim().isEmpty() ? 0 : value.trim().split("\\s+").length;
                    int lines = value.isEmpty() ? 0 : value.split("\\r?\\n", -1).length;
                    message("Text statistics", "Words: " + words + "\nCharacters: " + value.length()
                            + "\nCharacters without spaces: " + value.replaceAll("\\s", "").length()
                            + "\nLines: " + lines);
                }).setNegativeButton("Cancel", null).show();
    }

    private void savePdf(byte[] data, String suggestedName) {
        save(data, suggestedName, "application/pdf", SAVE_PDF);
    }

    private void saveText(String data, String suggestedName) {
        save(data.getBytes(StandardCharsets.UTF_8), suggestedName, "text/plain", SAVE_TEXT);
    }

    private void save(byte[] data, String suggestedName, String mime, int request) {
        pendingBytes = data;
        pendingName = suggestedName;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType(mime);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_TITLE, suggestedName);
        try { activity.startActivityForResult(i, request); }
        catch (Exception e) { pendingBytes = null; message("Save", e.getMessage()); }
    }
}
