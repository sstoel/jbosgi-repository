/*
 * #%L
 * JBossOSGi
 * %%
 * Copyright (C) 2026 RTR
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * #L%
 */
package org.jboss.test.osgi.repository.issues;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.jboss.osgi.repository.RepositoryReader;
import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.RepositoryWriter;
import org.jboss.osgi.repository.RepositoryXMLReader;
import org.jboss.osgi.repository.RepositoryXMLWriter;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.repository.spi.FileBasedRepositoryStorage;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XRequirementBuilder;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.jboss.osgi.resolver.XResourceBuilderFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.osgi.framework.Version;
import org.osgi.resource.Capability;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The repository XML writer escapes ',' and '\' in scalar String attribute values, but the reader does not unescape them:
 * every write/read round trip (every restart of the persistent repository followed by a change) corrupts such values,
 * and the corruption grows on each cycle.
 *
 * <h2>Severity</h2>
 * major. Silent corruption of persisted capability attributes, e.g. Export-Package: org.acme;vendor="Acme, Inc." becomes
 * "Acme\, Inc." after the first restart, "Acme\\\, Inc." after the second (verified), so requirements filtering on the
 * attribute stop matching. The XML written is also not what the spec format means: any other Repository XML consumer
 * reads the backslashes as part of the value.
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, classes org.jboss.osgi.repository.AttributeValueHandler (AttributeValue.getValueString /
 * escape) and RepositoryXMLWriter.writeAttributes; used by FileBasedRepositoryStorage.
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, XML round-trip fidelity review; bnd 7.0.0 (biz.aQute.bndlib XMLResourceGenerator/XMLResourceParser)
 * used as reference.
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8, 132.5.6 Attribute Element (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.repository.html):
 * "The default type is String, the value of the value attribute. ... The conversion of value s, when scalar, must take
 * place with the following methods: String - No conversion, use s". The escaping of commas and reverse solidi applies to
 * List elements only (Core R8 3.3.4 "Commas or reverse solidi ('\' U+005C) that are part of an element value must be
 * escaped by prefixing them with a reverse solidus.", referenced from 132.5.6 for "comma separated lists").
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/AttributeValueHandler.java:193 result.append(escape(value)) for a scalar,
 * :198-212 escape() prefixes ',' and '\' for Type.String; the reader path :62-63 uses the scalar String value as is.
 * RepositoryXMLWriter.java:134 writes getValueString(). Unchanged since 2013 (pre-existing).
 *
 * <h2>Root cause</h2>
 * getValueString() applies the list-element escaping to scalar Strings too; the reader correctly (per spec) does no
 * conversion for scalars, so writer and reader disagree.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=XmlStringAttributeEscapingIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testFileBasedStorageRestart: Expected the persisted String attribute 'vendor' unchanged after a restart, but it was
 *   expected:&lt;Acme[], Inc.&gt; but was:&lt;Acme[\], Inc.&gt;
 * testWriterOutputIsVerbatimForStringAttributes: Expected the String attribute 'vendor' to be written verbatim as
 *   value='Acme, Inc.' (132.5.6: String - no conversion), but the XML holds expected:&lt;Acme[], Inc.&gt; but was:&lt;Acme[\], Inc.&gt;
 * testWriterReaderRoundTrip: Expected the String attribute 'vendor' to survive an XML write/read round trip unchanged,
 *   but it was read back as expected:&lt;Acme[], Inc.&gt; but was:&lt;Acme[\], Inc.&gt;
 * </pre>
 * Repeated restarts (exploratory run): "Acme\, Inc." then "Acme\\\, Inc." then "Acme\\\\\\\, Inc.".
 *
 * <h2>Expected</h2>
 * Scalar String values are written and read verbatim; only List element values are escaped.
 *
 * <h2>Reference behaviour</h2>
 * bnd 7.0.0 XMLResourceGenerator writes value="Acme, Inc." and value="C:\osgi\bundles" verbatim, and its parser reads
 * value="Acme\, Inc." verbatim (no unescaping of scalars) - i.e. the spec behaviour this test expects.
 *
 * <h2>Suggested fix</h2>
 * In AttributeValue.getValueString(), call escape() only for list elements (listType == true).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class XmlStringAttributeEscapingIssueTestCase {

    private static final String PACKAGE_NAMESPACE = "osgi.wiring.package";
    private static final String VENDOR = "Acme, Inc.";
    private static final String PATH = "C:\\osgi\\bundles";

    private File storageDir;

    @Before
    public void setUp() throws Exception {
        storageDir = Files.createTempDirectory("xml-escaping").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursive(storageDir);
    }

    @Test
    public void testWriterOutputIsVerbatimForStringAttributes() throws Exception {
        byte[] xml = writeXML(createResource());
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        Map<String, String> values = new HashMap<String, String>();
        NodeList atts = doc.getElementsByTagNameNS("*", "attribute");
        for (int i = 0; i < atts.getLength(); i++) {
            Element att = (Element) atts.item(i);
            values.put(att.getAttribute("name"), att.getAttribute("value"));
        }
        assertEquals("Expected the String attribute 'vendor' to be written verbatim as value='" + VENDOR
                + "' (132.5.6: String - no conversion), but the XML holds", VENDOR, values.get("vendor"));
        assertEquals("Expected the String attribute 'path' to be written verbatim as value='" + PATH
                + "' (132.5.6: String - no conversion), but the XML holds", PATH, values.get("path"));
    }

    @Test
    public void testWriterReaderRoundTrip() throws Exception {
        byte[] xml = writeXML(createResource());
        RepositoryReader reader = RepositoryXMLReader.create(new ByteArrayInputStream(xml));
        XResource res = reader.nextResource();
        reader.close();
        Capability cap = res.getCapabilities(PACKAGE_NAMESPACE).get(0);
        assertEquals("Expected the String attribute 'vendor' to survive an XML write/read round trip unchanged, but it was read back as",
                VENDOR, cap.getAttributes().get("vendor"));
        assertEquals("Expected the String attribute 'path' to survive an XML write/read round trip unchanged, but it was read back as",
                PATH, cap.getAttributes().get("path"));
    }

    @Test
    public void testFileBasedStorageRestart() throws Exception {
        XRepository repository = Mockito.mock(XRepository.class);
        Mockito.when(repository.getName()).thenReturn("MockedRepo");
        RepositoryStorage storage = new FileBasedRepositoryStorage(repository, storageDir, (key, def) -> def);
        storage.addResource(createResource());

        // Restart: a new storage instance reads repository.xml
        RepositoryStorage restarted = new FileBasedRepositoryStorage(repository, storageDir, (key, def) -> def);
        Collection<Capability> caps = restarted.findProviders(XRequirementBuilder.create(PACKAGE_NAMESPACE, "org.acme").getRequirement());
        assertEquals(1, caps.size());
        Capability cap = caps.iterator().next();
        assertEquals("Expected the persisted String attribute 'vendor' unchanged after a restart, but it was",
                VENDOR, cap.getAttributes().get("vendor"));
        assertEquals("Expected the persisted String attribute 'path' unchanged after a restart, but it was",
                PATH, cap.getAttributes().get("path"));
    }

    private static XResource createResource() {
        XResourceBuilder<XResource> builder = XResourceBuilderFactory.create();
        XIdentityCapability icap = builder.addIdentityCapability("acme.feature", Version.parseVersion("1.0"));
        icap.getAttributes().put("type", "abstract");
        Map<String, Object> atts = new HashMap<String, Object>();
        atts.put(PACKAGE_NAMESPACE, "org.acme");
        atts.put("vendor", VENDOR);
        atts.put("path", PATH);
        builder.addCapability(PACKAGE_NAMESPACE, atts, Collections.<String, String> emptyMap());
        XResource res = builder.getResource();
        assertNotNull(res);
        return res;
    }

    private static byte[] writeXML(XResource res) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RepositoryWriter writer = RepositoryXMLWriter.create(out);
        writer.writeRepositoryElement(Collections.singletonMap("name", "test"));
        writer.writeResource(res);
        writer.close();
        return out.toByteArray();
    }

    private static void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }
}
