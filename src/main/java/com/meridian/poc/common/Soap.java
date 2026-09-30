package com.meridian.poc.common;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SOAP 1.1 envelope building/parsing for the core-banking simulator and the façade that fronts it. */
public final class Soap {
    private Soap() {}

    public static final String ENV_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    public static final String CB_NS = "urn:meridian:corebanking:v3";

    /** Builds an envelope whose body holds {@code <cb:op>} with simple child elements. */
    public static String envelope(String op, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
          .append("<soapenv:Envelope xmlns:soapenv=\"").append(ENV_NS).append("\" xmlns:cb=\"").append(CB_NS).append("\">")
          .append("<soapenv:Header/><soapenv:Body><cb:").append(op).append('>');
        fields.forEach((k, v) -> element(sb, k, v));
        sb.append("</cb:").append(op).append("></soapenv:Body></soapenv:Envelope>");
        return sb.toString();
    }

    /** Envelope with a list of repeated complex elements, e.g. transactions. */
    public static String envelopeWithList(String op, String itemName, List<Map<String, String>> items) {
        StringBuilder inner = new StringBuilder();
        for (Map<String, String> item : items) {
            inner.append("<cb:").append(itemName).append('>');
            item.forEach((k, v) -> element(inner, k, v));
            inner.append("</cb:").append(itemName).append('>');
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope xmlns:soapenv=\"" + ENV_NS
                + "\" xmlns:cb=\"" + CB_NS + "\"><soapenv:Header/><soapenv:Body><cb:" + op + ">" + inner
                + "</cb:" + op + "></soapenv:Body></soapenv:Envelope>";
    }

    public static String fault(String code, String message) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope xmlns:soapenv=\"" + ENV_NS
                + "\"><soapenv:Body><soapenv:Fault><faultcode>soapenv:Server</faultcode><faultstring>"
                + esc(code) + "</faultstring><detail>" + esc(message) + "</detail></soapenv:Fault></soapenv:Body></soapenv:Envelope>";
    }

    private static void element(StringBuilder sb, String k, String v) {
        if (v == null) return;
        sb.append("<cb:").append(k).append('>').append(esc(v)).append("</cb:").append(k).append('>');
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Returns the first element inside soapenv:Body (the operation or a Fault). */
    public static Element body(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            // XXE hardening: no DOCTYPEs, no external entities.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            Document d = b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList bodies = d.getElementsByTagNameNS(ENV_NS, "Body");
            if (bodies.getLength() == 0) throw new IllegalArgumentException("No SOAP Body");
            for (Node n = bodies.item(0).getFirstChild(); n != null; n = n.getNextSibling())
                if (n instanceof Element e) return e;
            throw new IllegalArgumentException("Empty SOAP Body");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid SOAP: " + e.getMessage(), e);
        }
    }

    public static boolean isFault(Element e) { return "Fault".equals(e.getLocalName()); }

    public static String text(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && localName.equals(e.getLocalName())) return e.getTextContent();
        return null;
    }

    public static Map<String, String> fields(Element parent) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e) m.put(e.getLocalName(), e.getTextContent());
        return m;
    }

    public static List<Map<String, String>> items(Element parent, String itemName) {
        List<Map<String, String>> l = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && itemName.equals(e.getLocalName())) l.add(fields(e));
        return l;
    }
}
