package com.example.docs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.drawable.GradientDrawable;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Document Studio Toolbox
 *
 * All processing is local on the device. SAF picker is used to select input and choose
 * a destination for every output. Original documents are never overwritten.
 */
public class ToolboxActivity extends Activity {
    private static final int PICK = 601, SAVE = 602;
    private static final int MERGE=1, EXTRACT=2, SPLIT=3, DELETE=4, REVERSE=5,
            ROTATE=6, ADD_BLANK=7, WATERMARK=8, NUMBERS=9, INFO=10,
            LOCK=11, UNLOCK=12, PDF_TEXT=13, PDF_IMAGES=14, METADATA=15,
            REORDER=16, FORM=17, PDF_OCR=18, PHOTOS_PDF=19, IMAGE_OCR=20,
            DUPLICATE=21;
    private static final int NAVY=Color.rgb(11,17,32), PANEL=Color.rgb(17,24,39),
            TILE=Color.rgb(30,41,59), TEAL=Color.rgb(45,212,191),
            WHITE=Color.rgb(241,245,249), SOFT=Color.rgb(148,163,184),
            LINE=Color.rgb(51,65,85);
    private int operation = 0;
    private File pendingOutput;
    private String pendingMime, pendingName;
    private boolean busy;
    private AlertDialog busyDialog;
    private LinearLayout body;
    private boolean dark = true;

    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        dark = getPreferences(MODE_PRIVATE).getBoolean("dark_mode",true);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        PDFBoxResourceLoader.init(getApplicationContext());
        showTools();
    }
    private int c(int darkColor,int lightColor){return dark?darkColor:lightColor;}
    private int bkg(){return c(NAVY,Color.rgb(248,250,252));}
    private int panel(){return c(PANEL,Color.WHITE);}
    private int tile(){return c(TILE,Color.rgb(240,244,248));}
    private int ink(){return c(WHITE,Color.rgb(20,34,52));}
    private int secondary(){return c(SOFT,Color.rgb(83,100,119));}
    private int line(){return c(LINE,Color.rgb(213,222,232));}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int border,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));d.setStroke(dp(1),border);return d;
    }
    private TextView tv(String t,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(t);v.setTextColor(color);
        v.setTextSize(size); if(bold)v.setTypeface(null,1);return v;
    }
    private void showTools(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(bkg());
        body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(16),dp(10),dp(16),dp(26));
        scroll.addView(body);
        LinearLayout nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=tv("‹",34,ink(),false);
        back.setGravity(Gravity.CENTER);
        nav.addView(back,new LinearLayout.LayoutParams(dp(50),dp(46)));
        back.setOnClickListener(v->finish());
        LinearLayout labels=new LinearLayout(this); labels.setOrientation(1);
        labels.addView(tv("Document Toolbox",23,ink(),true));
        labels.addView(tv("Offline PDF, scan and conversion studio",12,secondary(),false));
        nav.addView(labels);
        body.addView(nav);
        TextView intro=tv("Real document tools — your files stay on your phone. Outputs are always saved as new files.",13,secondary(),false);
        intro.setPadding(dp(4),dp(12),dp(4),dp(16));body.addView(intro);
        section("CREATE & CONVERT");
        tile("Photos → PDF","Combine multiple photos into one multipage PDF",PHOTOS_PDF);
        tile("Text → PDF","Create a printable PDF from your own text",-1);
        tile("PDF → text","Extract selectable text from a PDF",PDF_TEXT);
        tile("PDF → page images","Export each page as PNG in a ZIP file",PDF_IMAGES);
        tile("Scan image text (OCR)","Recognize and copy text in a photo, offline",IMAGE_OCR);
        tile("Scan a PDF page (OCR)","Recognize text in a scanned PDF page",PDF_OCR);
        tile("QR code creator","Create a crisp PNG QR code for text or a link",-2);
        section("PDF ORGANIZER");
        tile("Merge PDFs","Join two or more PDFs in the selected order",MERGE);
        tile("Split into pages","Create one PDF per page in a ZIP archive",SPLIT);
        tile("Extract page range","Keep only pages you choose",EXTRACT);
        tile("Delete page range","Remove selected pages from a new copy",DELETE);
        tile("Reverse pages","Reverse all PDF pages",REVERSE);
        tile("Reorder pages","Type a custom order, e.g. 3,1,2",REORDER);
        tile("Duplicate pages","Append a copy of a selected page",DUPLICATE);
        tile("Rotate pages","Rotate every page by 90°, 180° or 270°",ROTATE);
        tile("Add blank pages","Insert blank letter-size pages at the end",ADD_BLANK);
        section("PDF MARKUP & SECURITY");
        tile("Add watermark","Place a subtle watermark on each page",WATERMARK);
        tile("Add page numbers","Number pages along the bottom edge",NUMBERS);
        tile("Edit metadata","Set document title and author",METADATA);
        tile("Password-protect","Encrypt a PDF with an open password",LOCK);
        tile("Remove password","Remove encryption when you know the password",UNLOCK);
        tile("Fill a PDF form","Select a text field and save a filled copy",FORM);
        tile("PDF properties","Read page count, title, author and security status",INFO);
        section("STARTER DOCUMENTS");
        template("Meeting notes","MEETING NOTES\nDate:\nAttendees:\n\nAgenda\n1.\n2.\n\nDiscussion\n\nDecisions\n\nAction items\nOwner:\nDeadline:");
        template("Daily planner","DAILY PLANNER\nDate:\n\nTop priorities\n1.\n2.\n3.\n\nSchedule\n09:00 -\n10:00 -\n11:00 -\n\nNotes\n\nCompleted");
        template("Invoice","INVOICE\nInvoice no.:\nDate:\nFrom:\nBill to:\n\nDescription         Qty        Price\n\nSubtotal:\nTax:\nTotal:\n\nPayment details:");
        template("Checklist","CHECKLIST\nTitle:\nDate:\n\n[ ] First task\n[ ] Second task\n[ ] Third task\n\nNotes:");
        template("Formal letter","Date:\nTo:\nAddress:\n\nSubject:\n\nDear ,\n\n\n\nSincerely,\n");
        template("Project brief","PROJECT BRIEF\nProject:\nOwner:\nDate:\n\nObjective\n\nScope\n\nRequirements\n\nMilestones\n\nRisks\n\nNext steps");
        template("Study notes","STUDY NOTES\nTopic:\nDate:\n\nMain ideas\n\nKey terms\n\nExamples\n\nPractice questions\n\nSummary");
        template("Receipt","RECEIPT\nDate:\nSold by:\nCustomer:\n\nItem           Qty          Amount\n\nSubtotal:\nTax:\nTotal:\n\nNotes:");
        setContentView(scroll);
    }
    private void section(String name){
        TextView h=tv(name,12,TEAL,true);h.setLetterSpacing(.08f);
        h.setPadding(dp(3),dp(19),0,dp(9));body.addView(h);
    }
    private void tile(String title,String desc,int op){
        LinearLayout card=new LinearLayout(this);card.setOrientation(1);
        card.setPadding(dp(15),dp(13),dp(15),dp(13));
        card.setBackground(background(tile(),line(),12));
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView text=tv(title,15,ink(),true);heading.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(tv("›",24,TEAL,false));
        card.addView(heading);
        TextView info=tv(desc,12,secondary(),false);info.setPadding(0,dp(4),0,0);card.addView(info);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(8);
        body.addView(card,p);
        card.setOnClickListener(v->{if(busy)return;if(op==-1)promptTextPdf(null);else if(op==-2)qrPrompt();else choose(op);});
    }
    private void template(String title,String content){
        tile("Template: "+title,"Edit content and export a new PDF",-100);
        View row=body.getChildAt(body.getChildCount()-1);
        row.setOnClickListener(v->promptTextPdf(content));
    }
    private void choose(int op){
        operation=op;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(op==PHOTOS_PDF||op==IMAGE_OCR?"image/*":"application/pdf");
        if(op==MERGE||op==PHOTOS_PDF)i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        try{startActivityForResult(i,PICK);}catch(Exception e){showError("Cannot open file picker: "+e.getMessage());}
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==SAVE){
            File f=pendingOutput;pendingOutput=null;
            if(result==RESULT_OK&&data!=null&&data.getData()!=null&&f!=null)copyResult(f,data.getData());
            else if(f!=null)f.delete();
            return;
        }
        if(req!=PICK||result!=RESULT_OK||data==null)return;
        ArrayList<Uri> files=new ArrayList<>();
        if(data.getClipData()!=null){
            for(int j=0;j<data.getClipData().getItemCount();j++)
                files.add(data.getClipData().getItemAt(j).getUri());
        } else if(data.getData()!=null)files.add(data.getData());
        if(files.isEmpty())return;
        if(operation==MERGE&&files.size()<2){showError("Select at least two PDFs to merge.");return;}
        requestOptions(operation,files);
    }
    private interface StringResult{void accept(String s);}
    private void ask(String title,String initial,String hint,boolean multi,boolean secret,StringResult result){
        EditText input=new EditText(this); input.setText(initial);input.setTextColor(ink());
        input.setHintTextColor(secondary());input.setHint(hint);
        input.setPadding(dp(12),dp(12),dp(12),dp(12));
        if(secret)input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else if(multi){input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setMinLines(4);}
        else input.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout wrapper=new LinearLayout(this);wrapper.setPadding(dp(12),dp(5),dp(12),dp(5));wrapper.addView(input,new LinearLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(this).setTitle(title).setView(wrapper)
                .setPositiveButton("Continue",(d,w)->result.accept(input.getText().toString()))
                .setNegativeButton("Cancel",null).show();
    }
    private void requestOptions(int op,List<Uri> files){
        switch(op){
            case EXTRACT:case DELETE:
                ask(op==EXTRACT?"Extract pages":"Delete pages","1-2","Range, e.g. 2-5",false,false,s->run(op,files,s));return;
            case REORDER:
                ask("Reorder PDF pages","3,1,2","Every page once, e.g. 3,1,2",false,false,s->run(op,files,s));return;
            case DUPLICATE:
                ask("Duplicate which page?","1","Page number",false,false,s->run(op,files,s));return;
            case ROTATE:
                new AlertDialog.Builder(this).setTitle("Rotate pages").setItems(
                    new String[]{"90° clockwise","180°","270° clockwise"},
                    (d,w)->run(op,files,Integer.toString((w+1)*90))).show();return;
            case ADD_BLANK:
                ask("How many blank pages?","1","1–20",false,false,s->run(op,files,s));return;
            case WATERMARK:
                ask("Watermark on every page","CONFIDENTIAL","Watermark text",false,false,s->run(op,files,s));return;
            case LOCK:
                ask("Set PDF open password","","Password (minimum 4 characters)",false,true,s->run(op,files,s));return;
            case UNLOCK:
                ask("Enter existing PDF password","","Open password",false,true,s->run(op,files,s));return;
            case METADATA:
                ask("Document title","","Title",false,false,s->ask("Document author","","Author",false,false,
                    a->run(op,files,s+"\n"+a)));return;
            case FORM:showFormFields(files.get(0));return;
            case PDF_OCR:
                ask("Scan page number","1","1, 2, 3...",false,false,s->ocrPdf(files.get(0),s));return;
            case IMAGE_OCR:ocrImage(files.get(0));return;
            default:run(op,files,"");
        }
    }
    private void progress(String label){
        busy=true;
        busyDialog=new AlertDialog.Builder(this).setTitle("Document Studio")
            .setMessage(label+"\n\nWorking offline on your device…")
            .setCancelable(false).create();
        busyDialog.show();
    }
    private void endProgress(){
        busy=false;
        if(busyDialog!=null){busyDialog.dismiss();busyDialog=null;}
    }
    private void run(int op,List<Uri> files,String option){
        progress("Processing document");
        new Thread(()->{
            try{
                File output=execute(op,files,option);
                runOnUiThread(()->{
                    endProgress();
                    if(output!=null)offerSave(output,filename(op),mime(op));
                });
            }catch(Exception e){fail(e);}
        }).start();
    }
    private void fail(Exception e){
        runOnUiThread(()->{endProgress();showError(e.getMessage()==null?e.toString():e.getMessage());});
    }
    private File temp(String suffix)throws IOException{
        return File.createTempFile("docstudio_",suffix,getCacheDir());
    }
    private File copyInput(Uri uri)throws IOException{
        File file=temp(".source");
        try(InputStream in=getContentResolver().openInputStream(uri);
            OutputStream out=new FileOutputStream(file)){
            if(in==null)throw new IOException("Cannot read selected file.");
            byte[] b=new byte[16384];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
        }catch(IOException e){file.delete();throw e;}
        return file;
    }
    private byte[] read(Uri uri,int limit)throws IOException{
        try(InputStream in=getContentResolver().openInputStream(uri);
            ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("Cannot read selected file.");
            byte[] b=new byte[16384];int n;
            while((n=in.read(b))!=-1){
                if(out.size()+n>limit)throw new IOException("File is too large for this operation.");
                out.write(b,0,n);
            }
            return out.toByteArray();
        }
    }
    private File execute(int op,List<Uri> files,String option)throws Exception{
        if(op==PHOTOS_PDF)return photosPdf(files);
        if(op==PDF_IMAGES)return pdfImages(files.get(0));
        if(op==MERGE)return merge(files);
        File input=copyInput(files.get(0));
        try(PDDocument doc= op==UNLOCK?PDDocument.load(input,option):PDDocument.load(input)){
            File output;
            if(op==INFO){
                PDDocumentInformation inf=doc.getDocumentInformation();
                String details="Pages: "+doc.getNumberOfPages()+"\nTitle: "+inf.getTitle()
                        +"\nAuthor: "+inf.getAuthor()+"\nSubject: "+inf.getSubject()
                        +"\nEncrypted: "+doc.isEncrypted()
                        +"\nPDF version: "+doc.getVersion();
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("PDF properties")
                        .setMessage(details).setPositiveButton("OK",null).show());
                return null;
            }
            if(op==PDF_TEXT){
                String text=new PDFTextStripper().getText(doc);
                return writeString(text.isEmpty()?"No selectable text found. Try OCR for scanned pages.":text,".txt");
            }
            if(op==SPLIT)return split(doc);
            if(op==EXTRACT||op==REVERSE||op==REORDER||op==DUPLICATE){
                try(PDDocument out=new PDDocument()){
                    int n=doc.getNumberOfPages();
                    if(op==REVERSE){
                        for(int i=n-1;i>=0;i--)out.importPage(doc.getPage(i));
                    }else if(op==EXTRACT){
                        int[] range=parseRange(option,n);
                        for(int i=range[0]-1;i<range[1];i++)out.importPage(doc.getPage(i));
                    }else if(op==DUPLICATE){
                        int index=positive(option,1,n)-1;
                        for(int i=0;i<n;i++)out.importPage(doc.getPage(i));
                        out.importPage(doc.getPage(index));
                    }else{
                        String[] order=option.split(",");
                        if(order.length!=n)throw new IOException("Enter exactly "+n+" page numbers separated by commas.");
                        boolean[] seen=new boolean[n];
                        for(String p:order){
                            int index=positive(p.trim(),1,n)-1;
                            if(seen[index])throw new IOException("Each page must appear only once.");
                            seen[index]=true;out.importPage(doc.getPage(index));
                        }
                    }
                    output=temp(".pdf");out.save(output);return output;
                }
            }
            if(op==DELETE){
                int[] range=parseRange(option,doc.getNumberOfPages());
                if(range[1]-range[0]+1>=doc.getNumberOfPages())
                    throw new IOException("Keep at least one page in the PDF.");
                for(int i=range[1]-1;i>=range[0]-1;i--)doc.removePage(i);
            } else if(op==ROTATE){
                int deg=Integer.parseInt(option);
                for(PDPage page:doc.getPages())page.setRotation((page.getRotation()+deg)%360);
            } else if(op==ADD_BLANK){
                int count=positive(option,1,20);
                for(int i=0;i<count;i++)doc.addPage(new PDPage(PDRectangle.LETTER));
            } else if(op==WATERMARK){
                if(option.trim().isEmpty())throw new IOException("Enter watermark text.");
                stamp(doc,option.trim(),false);
            } else if(op==NUMBERS){
                stamp(doc,"",true);
            } else if(op==METADATA){
                String[] parts=option.split("\n",2);
                doc.getDocumentInformation().setTitle(parts[0]);
                doc.getDocumentInformation().setAuthor(parts.length==2?parts[1]:"");
            } else if(op==LOCK){
                if(option.length()<4)throw new IOException("Password must contain at least four characters.");
                AccessPermission p=new AccessPermission();
                StandardProtectionPolicy policy=new StandardProtectionPolicy(option,option,p);
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            } else if(op==UNLOCK){
                doc.setAllSecurityToBeRemoved(true);
            } else if(op==FORM){
                String[] parts=option.split("\n",2);
                PDAcroForm form=doc.getDocumentCatalog().getAcroForm();
                if(form==null)throw new IOException("This PDF has no fillable form.");
                PDField field=form.getField(parts[0]);
                if(field==null)throw new IOException("Form field not found.");
                field.setValue(parts.length==2?parts[1]:"");
            }
            output=temp(".pdf");
            doc.save(output);
            return output;
        }finally{input.delete();}
    }
    private int positive(String s,int lower,int upper)throws IOException{
        int n;
        try{n=Integer.parseInt(s.trim());}catch(Exception e){throw new IOException("Enter a valid whole number.");}
        if(n<lower||n>upper)throw new IOException("Enter a number between "+lower+" and "+upper+".");
        return n;
    }
    private int[] parseRange(String s,int count)throws IOException{
        String[] p=s.trim().split("-");
        if(p.length>2)throw new IOException("Use a page range such as 2-5.");
        int a=positive(p[0],1,count),b=p.length==1?a:positive(p[1],1,count);
        if(b<a)throw new IOException("End page must not be before start page.");
        return new int[]{a,b};
    }
    private String safePdfText(String s){
        return s.replaceAll("[^\\x20-\\x7e]","?").replace("\\","").trim();
    }
    private void stamp(PDDocument doc,String label,boolean number)throws IOException{
        int i=1;
        for(PDPage page:doc.getPages()){
            PDRectangle rect=page.getMediaBox();
            String msg=safePdfText(number?("Page "+i+" of "+doc.getNumberOfPages()):label);
            float size=number?10:Math.min(36,Math.max(14,360f/Math.max(1,msg.length())));
            try(PDPageContentStream cs=new PDPageContentStream(doc,page,
                    PDPageContentStream.AppendMode.APPEND,true,true)){
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD,size);
                cs.setNonStrokingColor(number?65:185,number?80:190,number?95:200);
                float estimated=msg.length()*size*.53f;
                float x=number?(rect.getWidth()-estimated)/2:(rect.getWidth()-estimated)/2;
                float y=number?30:rect.getHeight()/2;
                cs.newLineAtOffset(Math.max(12,x),y);
                cs.showText(msg);cs.endText();
            } i++;
        }
    }
    private File merge(List<Uri> uris)throws Exception{
        ArrayList<File> inputs=new ArrayList<>();
        try{
            PDFMergerUtility merger=new PDFMergerUtility();
            File out=temp(".pdf");
            for(Uri uri:uris){File f=copyInput(uri);inputs.add(f);merger.addSource(f);}
            merger.setDestinationFileName(out.getAbsolutePath());
            merger.mergeDocuments(null);
            return out;
        }finally{for(File f:inputs)f.delete();}
    }
    private File split(PDDocument doc)throws Exception{
        File result=temp(".zip");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(result))){
            int n=doc.getNumberOfPages();
            if(n>250)throw new IOException("Split is limited to 250 pages at once.");
            for(int i=0;i<n;i++){
                try(PDDocument one=new PDDocument()){
                    one.importPage(doc.getPage(i));
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    one.save(bytes);
                    zip.putNextEntry(new ZipEntry(String.format(Locale.US,"page-%03d.pdf",i+1)));
                    zip.write(bytes.toByteArray());zip.closeEntry();
                }
            }
        }return result;
    }
    private File photosPdf(List<Uri> photos)throws Exception{
        File out=temp(".pdf");
        try(PDDocument doc=new PDDocument()){
            if(photos.size()>120)throw new IOException("Select up to 120 images at a time.");
            for(Uri uri:photos){
                Bitmap b=sample(uri,1800);
                if(b==null)throw new IOException("Unable to open an image.");
                try{
                    ByteArrayOutputStream buf=new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.JPEG,86,buf);
                    com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject image=
                        com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(
                            doc,buf.toByteArray(),"photo");
                    PDPage page=new PDPage(PDRectangle.A4);doc.addPage(page);
                    float width=page.getMediaBox().getWidth(),height=page.getMediaBox().getHeight();
                    float scale=Math.min((width-32)/b.getWidth(),(height-32)/b.getHeight());
                    float w=b.getWidth()*scale,h=b.getHeight()*scale;
                    try(PDPageContentStream stream=new PDPageContentStream(doc,page)){
                        stream.drawImage(image,(width-w)/2,(height-h)/2,w,h);
                    }
                }finally{b.recycle();}
            }
            doc.save(out);
        }return out;
    }
    private Bitmap sample(Uri uri,int max)throws IOException{
        BitmapFactory.Options bound=new BitmapFactory.Options();bound.inJustDecodeBounds=true;
        try(InputStream in=getContentResolver().openInputStream(uri)){
            BitmapFactory.decodeStream(in,null,bound);
        }
        BitmapFactory.Options options=new BitmapFactory.Options();
        int size=1;
        while(Math.max(bound.outWidth,bound.outHeight)/size>max)size*=2;
        options.inSampleSize=Math.max(1,size);
        try(InputStream in=getContentResolver().openInputStream(uri)){
            Bitmap image=BitmapFactory.decodeStream(in,null,options);
            if(image==null)throw new IOException("Unsupported image file.");
            return image;
        }
    }
    private File pdfImages(Uri uri)throws Exception{
        File out=temp(".zip");
        try(ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(uri,"r")){
            if(fd==null)throw new IOException("Could not open PDF.");
            try(PdfRenderer renderer=new PdfRenderer(fd);
                ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(out))){
                if(renderer.getPageCount()>120)throw new IOException("Image export limited to 120 pages per PDF.");
                for(int i=0;i<renderer.getPageCount();i++){
                    try(PdfRenderer.Page page=renderer.openPage(i)){
                        float factor=Math.min(2.0f,(float)Math.sqrt(3200000.0/(page.getWidth()*(double)page.getHeight())));
                        int w=Math.max(1,Math.round(page.getWidth()*factor));
                        int h=Math.max(1,Math.round(page.getHeight()*factor));
                        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                        try{
                            bitmap.eraseColor(Color.WHITE);
                            page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                            bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);
                            zip.putNextEntry(new ZipEntry(String.format(Locale.US,"page-%03d.png",i+1)));
                            zip.write(bytes.toByteArray());zip.closeEntry();
                        }finally{bitmap.recycle();}
                    }
                }
            }
        }return out;
    }
    private File writeString(String s,String suffix)throws Exception{
        File file=temp(suffix);
        try(OutputStream out=new FileOutputStream(file)){out.write(s.getBytes(StandardCharsets.UTF_8));}
        return file;
    }
    private void showFormFields(Uri uri){
        progress("Reading fillable form fields");
        new Thread(()->{
            File tmp=null;
            try{
                tmp=copyInput(uri);
                ArrayList<String> names=new ArrayList<>();
                try(PDDocument doc=PDDocument.load(tmp)){
                    PDAcroForm form=doc.getDocumentCatalog().getAcroForm();
                    if(form==null)throw new IOException("This PDF has no fillable fields.");
                    for(PDField field:form.getFieldTree()){
                        if(field.getFullyQualifiedName()!=null)names.add(field.getFullyQualifiedName());
                    }
                }
                if(names.isEmpty())throw new IOException("No interactive fields found in this PDF.");
                runOnUiThread(()->{
                    endProgress();String[] entries=names.toArray(new String[0]);
                    new AlertDialog.Builder(this).setTitle("Choose a PDF field")
                        .setItems(entries,(d,which)->ask("Value for "+entries[which],"","Field value",false,false,
                            value->run(FORM,Arrays.asList(uri),entries[which]+"\n"+value)))
                        .setNegativeButton("Cancel",null).show();
                });
            }catch(Exception e){fail(e);}
            finally{if(tmp!=null)tmp.delete();}
        }).start();
    }
    private void offerSave(File output,String filename,String mime){
        pendingOutput=output;pendingName=filename;pendingMime=mime;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE,filename);
        try{startActivityForResult(i,SAVE);}
        catch(Exception e){output.delete();pendingOutput=null;showError("Cannot open Save dialog: "+e.getMessage());}
    }
    private void copyResult(File from,Uri destination){
        progress("Saving your new file");
        new Thread(()->{
            try(InputStream in=new FileInputStream(from);
                OutputStream out=getContentResolver().openOutputStream(destination,"wt")){
                if(out==null)throw new IOException("Selected location is not writable.");
                byte[] b=new byte[16384];int n;
                while((n=in.read(b))!=-1)out.write(b,0,n);
                out.flush();
                runOnUiThread(()->{endProgress();new AlertDialog.Builder(this)
                    .setTitle("Saved successfully")
                    .setMessage("Your original document was not changed.\n\nFile: "+pendingName)
                    .setPositiveButton("Done",null).show();});
            }catch(Exception e){fail(e);}
            finally{from.delete();}
        }).start();
    }
    private String filename(int op){
        switch(op){
            case MERGE:return "merged.pdf";
            case EXTRACT:return "extracted-pages.pdf";
            case SPLIT:return "split-pages.zip";
            case DELETE:return "pages-removed.pdf";
            case REVERSE:return "reversed-pages.pdf";
            case ROTATE:return "rotated.pdf";
            case ADD_BLANK:return "added-pages.pdf";
            case WATERMARK:return "watermarked.pdf";
            case NUMBERS:return "page-numbered.pdf";
            case LOCK:return "protected.pdf";
            case UNLOCK:return "unlocked.pdf";
            case PDF_TEXT:return "extracted-text.txt";
            case PDF_IMAGES:return "pdf-pages-images.zip";
            case METADATA:return "updated-metadata.pdf";
            case REORDER:return "reordered.pdf";
            case FORM:return "filled-form.pdf";
            case PHOTOS_PDF:return "photos.pdf";
            case DUPLICATE:return "duplicated-page.pdf";
            default:return "document.pdf";
        }
    }
    private String mime(int op){
        if(op==PDF_TEXT)return "text/plain";
        if(op==SPLIT||op==PDF_IMAGES)return "application/zip";
        return "application/pdf";
    }
    private void ocrImage(Uri uri){
        progress("Recognizing image text");
        new Thread(()->{
            try{
                Bitmap b=sample(uri,2000);
                runOnUiThread(()->processOcr(b));
            }catch(Exception e){fail(e);}
        }).start();
    }
    private void ocrPdf(Uri uri,String number){
        progress("Rendering page for OCR");
        new Thread(()->{
            try(ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(uri,"r")){
                if(fd==null)throw new IOException("Unable to read PDF.");
                Bitmap bitmap;
                try(PdfRenderer renderer=new PdfRenderer(fd)){
                    int p=positive(number,1,renderer.getPageCount())-1;
                    try(PdfRenderer.Page page=renderer.openPage(p)){
                        float scale=Math.min(2.2f,(float)Math.sqrt(3000000.0/(page.getWidth()*(double)page.getHeight())));
                        bitmap=Bitmap.createBitmap(Math.max(1,(int)(page.getWidth()*scale)),
                                Math.max(1,(int)(page.getHeight()*scale)),Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                }
                runOnUiThread(()->processOcr(bitmap));
            }catch(Exception e){fail(e);}
        }).start();
    }
    private void processOcr(Bitmap bitmap){
        try{
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromBitmap(bitmap,0))
                .addOnSuccessListener(r->{bitmap.recycle();endProgress();
                    showRecognizedText(r.getText());})
                .addOnFailureListener(e->{bitmap.recycle();endProgress();showError("OCR failed: "+e.getMessage());});
        }catch(Exception e){bitmap.recycle();endProgress();showError("OCR failed: "+e.getMessage());}
    }
    private void showRecognizedText(String result){
        if(result.trim().isEmpty()){
            showError("No readable text detected. Try a sharper, well-lit image.");return;
        }
        EditText edit=new EditText(this);edit.setText(result);edit.setMinLines(5);
        edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        edit.setTextColor(ink());edit.setPadding(dp(16),dp(14),dp(16),dp(14));
        ScrollView scroll=new ScrollView(this);scroll.addView(edit);
        new AlertDialog.Builder(this).setTitle("Recognized text")
            .setView(scroll)
            .setPositiveButton("Save as TXT",(d,w)->{
                try{offerSave(writeString(edit.getText().toString(),".txt"),"scanned-text.txt","text/plain");}
                catch(Exception e){showError(e.getMessage());}
            }).setNeutralButton("Copy",(d,w)->{
                ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Recognized text",edit.getText()));
                toast("Text copied");})
            .setNegativeButton("Close",null).show();
    }
    private void promptTextPdf(String suggested){
        ask("Create PDF from text",suggested==null?"":suggested,
            "Write your document here",true,false,text->{
                if(text.trim().isEmpty()){showError("Enter some text first.");return;}
                progress("Creating PDF");
                new Thread(()->{
                    try{File pdf=createTextPdf(text);
                        runOnUiThread(()->{endProgress();offerSave(pdf,"new-document.pdf","application/pdf");});
                    }catch(Exception e){fail(e);}
                }).start();
            });
    }
    private File createTextPdf(String content)throws Exception{
        File out=temp(".pdf");
        try(PdfDocument document=new PdfDocument()){
            int width=595,height=842;
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.rgb(30,41,59));paint.setTextSize(13);
            float y=55;int pageNumber=0;
            PdfDocument.Page page=null;Canvas canvas=null;
            String[] paragraphs=content.replace("\r","").split("\n",-1);
            for(String paragraph:paragraphs){
                String remaining=paragraph;
                do{
                    String line=remaining;
                    int end=line.length();
                    while(end>1&&paint.measureText(line.substring(0,end))>width-94)end--;
                    line=line.substring(0,end);
                    remaining=remaining.substring(end);
                    if(page==null||y>height-58){
                        if(page!=null)document.finishPage(page);
                        pageNumber++;
                        page=document.startPage(new PdfDocument.PageInfo.Builder(width,height,pageNumber).create());
                        canvas=page.getCanvas();canvas.drawColor(Color.WHITE);y=55;
                    }
                    canvas.drawText(line,46,y,paint);y+=22;
                }while(!remaining.isEmpty());
                y+=4;
            }
            if(page!=null)document.finishPage(page);
            try(OutputStream stream=new FileOutputStream(out)){document.writeTo(stream);}
        }return out;
    }
    private void qrPrompt(){
        ask("Create QR code","","Type a web link or any text",true,false,s->{
            if(s.trim().isEmpty()){showError("Enter text for the QR code.");return;}
            progress("Generating QR image");
            new Thread(()->{
                try{
                    Map<EncodeHintType,Object> opts=new HashMap<>();
                    opts.put(EncodeHintType.MARGIN,2);
                    BitMatrix qr=new MultiFormatWriter().encode(s,BarcodeFormat.QR_CODE,850,850,opts);
                    Bitmap bitmap=Bitmap.createBitmap(qr.getWidth(),qr.getHeight(),Bitmap.Config.ARGB_8888);
                    for(int y=0;y<qr.getHeight();y++)for(int x=0;x<qr.getWidth();x++)
                        bitmap.setPixel(x,y,qr.get(x,y)?Color.BLACK:Color.WHITE);
                    File image=temp(".png");
                    try(OutputStream out=new FileOutputStream(image)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
                    bitmap.recycle();
                    runOnUiThread(()->{endProgress();offerSave(image,"qr-code.png","image/png");});
                }catch(Exception e){fail(e);}
            }).start();
        });
    }
    private void showError(String reason){
        new AlertDialog.Builder(this).setTitle("Document Studio")
                .setMessage(reason).setPositiveButton("OK",null).show();
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override public void onBackPressed(){
        if(busy)return;
        super.onBackPressed();
    }
}
