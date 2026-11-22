package com.example.docs;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Offline OOXML reader and lightweight editor for DOCX, XLSX, PPTX, plus CSV/text helpers.
 */
public final class OfficeEngine {
    private OfficeEngine() {}

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String PKG_R = "http://schemas.openxmlformats.org/package/2006/relationships";
    static final int MAX_PART = 24 * 1024 * 1024;
    static final int MAX_TOTAL = 72 * 1024 * 1024;

    public static final class TextStats {
        public final int words, characters, lines;
        TextStats(int w, int c, int l) { words = w; characters = c; lines = l; }
    }

    public static TextStats stats(String text) {
        if (text == null || text.isEmpty()) return new TextStats(0, 0, 1);
        String trimmed = text.trim();
        int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        int lines = text.split("\\n", -1).length;
        return new TextStats(words, text.length(), lines);
    }

    public static class PackageFile {
        final LinkedHashMap<String, byte[]> parts = new LinkedHashMap<>();
        private PackageFile() {}

        PackageFile fork() {
            PackageFile copy = new PackageFile();
            copy.parts.putAll(this.parts);
            return copy;
        }

        public PackageFile(byte[] zipBytes) throws Exception {
            int total = 0;
            try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = zin.read(b)) != -1) {
                        total += n;
                        if (bout.size() + n > MAX_PART || total > MAX_TOTAL)
                            throw new IOException("This document is too large for the lightweight editor.");
                        bout.write(b, 0, n);
                    }
                    parts.put(e.getName(), bout.toByteArray());
                    if (parts.size() > 3000) throw new IOException("Document contains too many components.");
                    zin.closeEntry();
                }
            }
            if (!parts.containsKey("[Content_Types].xml"))
                throw new IOException("This is not a supported Office document.");
        }

        Document xml(String path) throws Exception {
            byte[] data = parts.get(path);
            if (data == null) throw new IOException("Missing document component: " + path);
            return parse(data);
        }

        void update(String path, Document dom) throws Exception {
            parts.put(path, serialize(dom));
        }

        public byte[] zip() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (ZipOutputStream out = new ZipOutputStream(b)) {
                for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                    out.putNextEntry(new ZipEntry(e.getKey()));
                    out.write(e.getValue());
                    out.closeEntry();
                }
            }
            return b.toByteArray();
        }
    }

    static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
        try { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) {}
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    static byte[] serialize(Document dom) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        try { tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) {}
        Transformer trans = tf.newTransformer();
        trans.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        trans.transform(new DOMSource(dom), new StreamResult(b));
        return b.toByteArray();
    }

    static boolean is(Node n, String ns, String name) {
        return n instanceof Element && name.equals(n.getLocalName()) && ns.equals(n.getNamespaceURI());
    }

    static Element first(Element parent, String ns, String tag) {
        if (parent == null) return null;
        NodeList nodes = parent.getElementsByTagNameNS(ns, tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    static List<Element> direct(Element parent, String ns, String tag) {
        ArrayList<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++)
            if (is(nodes.item(i), ns, tag)) result.add((Element) nodes.item(i));
        return result;
    }

    static String allText(Element root, String namespace, String tag) {
        if (root == null) return "";
        NodeList nodes = root.getElementsByTagNameNS(namespace, tag);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < nodes.getLength(); i++) b.append(nodes.item(i).getTextContent());
        return b.toString();
    }

    static void clear(Element parent, String ns, String name) {
        for (Element c : direct(parent, ns, name)) parent.removeChild(c);
    }

    public static class WordDoc {
        public final PackageFile file;
        public final ArrayList<String> paragraphs = new ArrayList<>();

        WordDoc(PackageFile p) throws Exception {
            file = p;
            Document d = p.xml("word/document.xml");
            Element body = first(d.getDocumentElement(), W, "body");
            if (body == null) throw new IOException("Word document has no body.");
            NodeList children = body.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (is(node, W, "p")) {
                    paragraphs.add(allText((Element) node, W, "t"));
                } else if (is(node, W, "tbl")) {
                    for (Element row : direct((Element) node, W, "tr")) {
                        StringBuilder rowText = new StringBuilder();
                        for (Element cell : direct(row, W, "tc")) {
                            if (rowText.length() > 0) rowText.append(" | ");
                            rowText.append(allText(cell, W, "t"));
                        }
                        if (rowText.length() > 0) paragraphs.add(rowText.toString());
                    }
                }
            }
        }

        public String text() {
            StringBuilder b = new StringBuilder();
            for (String p : paragraphs) {
                if (b.length() > 0) b.append('\n');
                b.append(p);
            }
            return b.toString();
        }

        public byte[] save(String text) throws Exception {
            PackageFile copy = file.fork();
            Document d = copy.xml("word/document.xml");
            Element body = first(d.getDocumentElement(), W, "body");
            List<Element> existing = direct(body, W, "p");
            String[] lines = text.split("\\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Element para;
                if (i < existing.size()) {
                    if (i < paragraphs.size() && lines[i].equals(paragraphs.get(i))) continue;
                    para = existing.get(i);
                } else {
                    para = d.createElementNS(W, "w:p");
                    Element section = first(body, W, "sectPr");
                    if (section != null && section.getParentNode() == body) body.insertBefore(para, section);
                    else body.appendChild(para);
                }
                NodeList childNodes = para.getChildNodes();
                for (int j = childNodes.getLength() - 1; j >= 0; j--) {
                    Node node = childNodes.item(j);
                    if (!is(node, W, "pPr")) para.removeChild(node);
                }
                Element run = d.createElementNS(W, "w:r");
                Element t = d.createElementNS(W, "w:t");
                t.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
                t.setTextContent(lines[i]);
                run.appendChild(t);
                para.appendChild(run);
            }
            for (int i = lines.length; i < existing.size(); i++) body.removeChild(existing.get(i));
            copy.update("word/document.xml", d);
            return copy.zip();
        }
    }

    public static WordDoc word(byte[] zip) throws Exception { return new WordDoc(new PackageFile(zip)); }

    public static class Spreadsheet {
        public final PackageFile file;
        public final List<Sheet> sheets = new ArrayList<>();
        final List<String> sharedStrings = new ArrayList<>();

        Spreadsheet(PackageFile f) throws Exception {
            file = f;
            if (f.parts.containsKey("xl/sharedStrings.xml")) {
                Document d = f.xml("xl/sharedStrings.xml");
                NodeList si = d.getElementsByTagNameNS(S, "si");
                for (int i = 0; i < si.getLength(); i++) sharedStrings.add(allText((Element) si.item(i), S, "t"));
            }
            HashMap<String, String> rels = new HashMap<>();
            if (f.parts.containsKey("xl/_rels/workbook.xml.rels")) {
                Document d = f.xml("xl/_rels/workbook.xml.rels");
                NodeList rel = d.getElementsByTagNameNS(PKG_R, "Relationship");
                for (int i = 0; i < rel.getLength(); i++) {
                    Element r = (Element) rel.item(i);
                    String target = r.getAttribute("Target");
                    String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                    path = path.replace("xl/xl/", "xl/");
                    while (path.contains("/../")) path = path.replaceFirst("[^/]+/\\.\\./", "");
                    rels.put(r.getAttribute("Id"), path);
                }
            }
            if (f.parts.containsKey("xl/workbook.xml")) {
                Document wb = f.xml("xl/workbook.xml");
                NodeList nodes = wb.getElementsByTagNameNS(S, "sheet");
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element item = (Element) nodes.item(i);
                    String id = item.getAttributeNS(R, "id");
                    String path = rels.get(id);
                    if (path == null) path = "xl/worksheets/sheet" + (i + 1) + ".xml";
                    if (f.parts.containsKey(path)) sheets.add(new Sheet(this, item.getAttribute("name"), path));
                }
            }
            if (sheets.isEmpty()) {
                for (String name : f.parts.keySet())
                    if (name.matches("xl/worksheets/sheet[0-9]+\\.xml"))
                        sheets.add(new Sheet(this, "Sheet " + (sheets.size() + 1), name));
            }
            if (sheets.isEmpty()) throw new IOException("No worksheets found.");
        }

        public String toCsv(int sheetIndex) {
            Sheet s = sheets.get(Math.max(0, Math.min(sheetIndex, sheets.size() - 1)));
            StringBuilder out = new StringBuilder();
            for (int r = 1; r <= s.maxRow; r++) {
                for (int c = 1; c <= s.maxCol; c++) {
                    if (c > 1) out.append(',');
                    String val = s.get(r, c);
                    if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
                        out.append('"').append(val.replace("\"", "\"\"")).append('"');
                    } else {
                        out.append(val);
                    }
                }
                if (r < s.maxRow) out.append('\n');
            }
            return out.toString();
        }

        public byte[] save() throws Exception {
            PackageFile copy = file.fork();
            for (Sheet s : sheets) if (!s.edits.isEmpty()) s.apply(copy);
            return copy.zip();
        }
    }

    public static class Sheet {
        final Spreadsheet book;
        public final String name, entry;
        public final HashMap<String, String> values = new HashMap<>();
        public final HashMap<String, String> edits = new HashMap<>();
        public final HashMap<String, Boolean> formulas = new HashMap<>();
        public int maxRow = 1, maxCol = 1;

        Sheet(Spreadsheet b, String name, String entry) throws Exception {
            book = b;
            this.name = name == null || name.isEmpty() ? "Sheet" : name;
            this.entry = entry;
            Document d = b.file.xml(entry);
            NodeList cells = d.getElementsByTagNameNS(S, "c");
            for (int i = 0; i < cells.getLength(); i++) {
                Element c = (Element) cells.item(i);
                String ref = c.getAttribute("r").toUpperCase(Locale.ROOT);
                if (ref.isEmpty()) continue;
                String type = c.getAttribute("t");
                Element v = first(c, S, "v"), is = first(c, S, "is"), fn = first(c, S, "f");
                String value = v == null ? "" : v.getTextContent();
                if ("s".equals(type)) {
                    try {
                        int n = Integer.parseInt(value);
                        value = b.sharedStrings.get(n);
                    } catch (Exception ex) {
                        value = "";
                    }
                } else if ("inlineStr".equals(type) && is != null) value = allText(is, S, "t");
                if (fn != null) formulas.put(ref, true);
                values.put(ref, value);
                int[] coords = coordinates(ref);
                if (coords != null) {
                    maxRow = Math.max(maxRow, coords[0]);
                    maxCol = Math.max(maxCol, coords[1]);
                }
            }
        }

        public String get(int row, int col) {
            String ref = cellRef(row, col);
            return edits.containsKey(ref) ? edits.get(ref) : values.getOrDefault(ref, "");
        }

        public void set(int row, int col, String value) {
            String ref = cellRef(row, col);
            if (value.equals(values.getOrDefault(ref, ""))) edits.remove(ref);
            else {
                edits.put(ref, value);
                maxRow = Math.max(maxRow, row);
                maxCol = Math.max(maxCol, col);
            }
        }

        void apply(PackageFile copy) throws Exception {
            Document d = copy.xml(entry);
            Element data = first(d.getDocumentElement(), S, "sheetData");
            if (data == null) throw new IOException("Sheet data is missing.");
            for (Map.Entry<String, String> edit : edits.entrySet()) {
                String ref = edit.getKey();
                int[] pos = coordinates(ref);
                if (pos == null) continue;
                int rowNum = pos[0], colNum = pos[1];
                Element row = null;
                for (Element r : direct(data, S, "row"))
                    if (Integer.toString(rowNum).equals(r.getAttribute("r"))) { row = r; break; }
                if (row == null) {
                    row = d.createElementNS(S, "row");
                    row.setAttribute("r", Integer.toString(rowNum));
                    boolean inserted = false;
                    for (Element r : direct(data, S, "row")) {
                        int n = 0;
                        try { n = Integer.parseInt(r.getAttribute("r")); } catch (Exception ignored) {}
                        if (n > rowNum) { data.insertBefore(row, r); inserted = true; break; }
                    }
                    if (!inserted) data.appendChild(row);
                }
                Element cell = null;
                for (Element c : direct(row, S, "c"))
                    if (ref.equalsIgnoreCase(c.getAttribute("r"))) { cell = c; break; }
                if (cell == null) {
                    cell = d.createElementNS(S, "c");
                    cell.setAttribute("r", ref);
                    boolean inserted = false;
                    for (Element c : direct(row, S, "c")) {
                        int[] cp = coordinates(c.getAttribute("r"));
                        if (cp != null && cp[1] > colNum) { row.insertBefore(cell, c); inserted = true; break; }
                    }
                    if (!inserted) row.appendChild(cell);
                }
                clear(cell, S, "v");
                clear(cell, S, "is");
                clear(cell, S, "f");
                String val = edit.getValue();
                if (val == null || val.isEmpty()) { cell.removeAttribute("t"); continue; }
                if (val.startsWith("=") && val.length() > 1) {
                    cell.removeAttribute("t");
                    Element f = d.createElementNS(S, "f");
                    f.setTextContent(val.substring(1));
                    cell.appendChild(f);
                    Element v = d.createElementNS(S, "v");
                    cell.appendChild(v);
                } else if (val.matches("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")) {
                    cell.removeAttribute("t");
                    Element v = d.createElementNS(S, "v");
                    v.setTextContent(val);
                    cell.appendChild(v);
                } else {
                    cell.setAttribute("t", "inlineStr");
                    Element is = d.createElementNS(S, "is"), t = d.createElementNS(S, "t");
                    t.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
                    t.setTextContent(val);
                    is.appendChild(t);
                    cell.appendChild(is);
                }
            }
            copy.update(entry, d);
        }
    }

    public static Spreadsheet spreadsheet(byte[] zip) throws Exception { return new Spreadsheet(new PackageFile(zip)); }

    public static String cellRef(int row, int col) {
        StringBuilder b = new StringBuilder();
        int n = col;
        while (n > 0) {
            n--;
            b.insert(0, (char) ('A' + n % 26));
            n /= 26;
        }
        return b.toString() + row;
    }

    public static int[] coordinates(String ref) {
        Matcher m = Pattern.compile("([A-Za-z]+)([1-9][0-9]*)").matcher(ref);
        if (!m.matches()) return null;
        int col = 0;
        for (char c : m.group(1).toUpperCase(Locale.ROOT).toCharArray()) col = col * 26 + (c - 'A' + 1);
        try { return new int[]{Integer.parseInt(m.group(2)), col}; } catch (Exception ex) { return null; }
    }

    public static class Slide {
        public final String name, path;
        public final List<String> texts = new ArrayList<>();
        public final Map<Integer, String> edits = new HashMap<>();

        Slide(PackageFile f, String path, int no) throws Exception {
            this.path = path;
            this.name = "Slide " + no;
            Document d = f.xml(path);
            NodeList shapes = d.getElementsByTagNameNS(P, "sp");
            for (int i = 0; i < shapes.getLength(); i++) texts.add(allText((Element) shapes.item(i), A, "t"));
        }

        void save(PackageFile f) throws Exception {
            if (edits.isEmpty()) return;
            Document d = f.xml(path);
            NodeList shapes = d.getElementsByTagNameNS(P, "sp");
            for (Map.Entry<Integer, String> edit : edits.entrySet()) {
                int i = edit.getKey();
                if (i < 0 || i >= shapes.getLength()) continue;
                Element shape = (Element) shapes.item(i);
                NodeList txt = shape.getElementsByTagNameNS(A, "t");
                if (txt.getLength() == 0) continue;
                txt.item(0).setTextContent(edit.getValue());
                for (int n = 1; n < txt.getLength(); n++) txt.item(n).setTextContent("");
            }
            f.update(path, d);
        }
    }

    public static class Presentation {
        public final PackageFile file;
        public final List<Slide> slides = new ArrayList<>();

        Presentation(PackageFile f) throws Exception {
            file = f;
            List<String> entries = new ArrayList<>();
            for (String path : f.parts.keySet())
                if (path.matches("ppt/slides/slide[0-9]+\\.xml")) entries.add(path);
            Collections.sort(entries, new Comparator<String>() {
                public int compare(String a, String b) { return Integer.compare(slideNumber(a), slideNumber(b)); }
            });
            for (String path : entries) slides.add(new Slide(f, path, slideNumber(path)));
            if (slides.isEmpty()) throw new IOException("No slides found.");
        }

        public byte[] save() throws Exception {
            PackageFile copy = file.fork();
            for (Slide slide : slides) slide.save(copy);
            return copy.zip();
        }
    }

    static int slideNumber(String path) {
        Matcher m = Pattern.compile("slide([0-9]+)\\.xml").matcher(path);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    public static Presentation presentation(byte[] zip) throws Exception { return new Presentation(new PackageFile(zip)); }
}
