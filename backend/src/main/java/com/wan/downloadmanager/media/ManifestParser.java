package com.wan.downloadmanager.media;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads HLS (m3u8) and DASH (mpd) manifests. Only what the manifest states is reported — no quality
 * is ever guessed. Protected content (any HLS key other than METHOD=NONE, any DASH ContentProtection)
 * is reported as protected and must not be offered for download.
 */
public final class ManifestParser {
    private ManifestParser() {}

    public record Variant(URI uri, Integer width, Integer height, Long bandwidth, String codecs, String audioGroup) {}

    public record Rendition(String groupId, String name, String language, URI uri) {}

    public record HlsMaster(List<Variant> variants, List<Rendition> audio, boolean isMediaPlaylist, boolean protectedContent) {}

    public record Segment(URI uri, double durationSec) {}

    public record HlsMedia(List<Segment> segments, boolean protectedContent, boolean endList, URI initSegment) {
        public double durationSec() {
            return segments.stream().mapToDouble(Segment::durationSec).sum();
        }
    }

    public record DashRepresentation(String id, String mimeType, String codecs, Integer width, Integer height, Long bandwidth,
                                     URI singleFileUrl) {}

    public record Dash(List<DashRepresentation> representations, boolean protectedContent, Double durationSec) {}

    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    public static HlsMaster parseHlsMaster(String text, URI base) {
        List<Variant> variants = new ArrayList<>();
        List<Rendition> audio = new ArrayList<>();
        boolean prot = false;
        boolean media = false;
        String[] lines = text.split("\\R");
        if (lines.length == 0 || !lines[0].trim().startsWith("#EXTM3U")) throw new IllegalArgumentException("Not an HLS playlist");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("#EXTINF") || l.startsWith("#EXT-X-TARGETDURATION")) media = true;
            if (l.startsWith("#EXT-X-SESSION-KEY") || (l.startsWith("#EXT-X-KEY") && !"NONE".equals(attrs(l).get("METHOD")))) prot = true;
            if (l.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> a = attrs(l);
                if ("AUDIO".equals(a.get("TYPE")) && a.containsKey("URI")) {
                    audio.add(new Rendition(a.get("GROUP-ID"), a.get("NAME"), a.get("LANGUAGE"), base.resolve(a.get("URI"))));
                }
            }
            if (l.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> a = attrs(l);
                String next = null;
                while (++i < lines.length) {
                    String n = lines[i].trim();
                    if (!n.isEmpty() && !n.startsWith("#")) {
                        next = n;
                        break;
                    }
                }
                if (next == null) break;
                Integer w = null, h = null;
                String res = a.get("RESOLUTION");
                if (res != null && res.matches("\\d+x\\d+")) {
                    w = Integer.parseInt(res.split("x")[0]);
                    h = Integer.parseInt(res.split("x")[1]);
                }
                Long bw = a.containsKey("BANDWIDTH") ? parseLong(a.get("BANDWIDTH")) : null;
                variants.add(new Variant(base.resolve(next), w, h, bw, a.get("CODECS"), a.get("AUDIO")));
            }
        }
        return new HlsMaster(variants, audio, media && variants.isEmpty(), prot);
    }

    public static HlsMedia parseHlsMedia(String text, URI base) {
        List<Segment> segs = new ArrayList<>();
        boolean prot = false, end = false;
        URI init = null;
        double pending = -1;
        for (String raw : text.split("\\R")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-KEY") && !"NONE".equals(attrs(l).get("METHOD"))) prot = true;
            else if (l.startsWith("#EXT-X-ENDLIST")) end = true;
            else if (l.startsWith("#EXT-X-MAP:")) {
                String u = attrs(l).get("URI");
                if (u != null) init = base.resolve(u);
            } else if (l.startsWith("#EXTINF:")) {
                String v = l.substring(8).split(",")[0].trim();
                pending = v.isEmpty() ? 0 : Double.parseDouble(v);
            } else if (!l.startsWith("#") && pending >= 0) {
                segs.add(new Segment(base.resolve(l), pending));
                pending = -1;
            }
        }
        return new HlsMedia(segs, prot, end, init);
    }

    public static Dash parseDash(String xml, URI base) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // No external entities / DTDs (XXE).
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        boolean prot = doc.getElementsByTagName("ContentProtection").getLength() > 0;
        Double duration = parseIsoDuration(doc.getDocumentElement().getAttribute("mediaPresentationDuration"));
        URI mpdBase = base;
        NodeList topBase = doc.getDocumentElement().getElementsByTagName("BaseURL");
        if (topBase.getLength() > 0 && topBase.item(0).getParentNode() == doc.getDocumentElement()) {
            mpdBase = base.resolve(topBase.item(0).getTextContent().trim());
        }
        List<DashRepresentation> reps = new ArrayList<>();
        NodeList sets = doc.getElementsByTagName("AdaptationSet");
        for (int s = 0; s < sets.getLength(); s++) {
            Element set = (Element) sets.item(s);
            NodeList rs = set.getElementsByTagName("Representation");
            for (int r = 0; r < rs.getLength(); r++) {
                Element rep = (Element) rs.item(r);
                String mime = first(rep.getAttribute("mimeType"), set.getAttribute("mimeType"), set.getAttribute("contentType"));
                URI single = null;
                NodeList bases = rep.getElementsByTagName("BaseURL");
                boolean segmented = rep.getElementsByTagName("SegmentTemplate").getLength() > 0
                        || rep.getElementsByTagName("SegmentList").getLength() > 0
                        || set.getElementsByTagName("SegmentTemplate").getLength() > 0;
                if (bases.getLength() > 0 && !segmented) single = mpdBase.resolve(bases.item(0).getTextContent().trim());
                reps.add(new DashRepresentation(rep.getAttribute("id"), mime, first(rep.getAttribute("codecs"), set.getAttribute("codecs")),
                        intOrNull(rep.getAttribute("width")), intOrNull(rep.getAttribute("height")),
                        parseLong(rep.getAttribute("bandwidth")), single));
            }
        }
        return new Dash(reps, prot, duration);
    }

    static Map<String, String> attrs(String line) {
        Map<String, String> m = new LinkedHashMap<>();
        int colon = line.indexOf(':');
        if (colon < 0) return m;
        Matcher x = ATTR.matcher(line.substring(colon + 1));
        while (x.find()) {
            String v = x.group(2);
            if (v.startsWith("\"")) v = v.substring(1, v.length() - 1);
            m.put(x.group(1), v);
        }
        return m;
    }

    static Double parseIsoDuration(String s) {
        if (s == null || s.isBlank()) return null;
        Matcher m = Pattern.compile("PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?").matcher(s.toUpperCase(Locale.ROOT));
        if (!m.matches()) return null;
        double h = m.group(1) == null ? 0 : Double.parseDouble(m.group(1));
        double mi = m.group(2) == null ? 0 : Double.parseDouble(m.group(2));
        double se = m.group(3) == null ? 0 : Double.parseDouble(m.group(3));
        return h * 3600 + mi * 60 + se;
    }

    private static String first(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s;
        return null;
    }

    private static Integer intOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return s == null || s.isBlank() ? null : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
