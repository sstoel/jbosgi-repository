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
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.jboss.osgi.repository.RepositoryStorage;
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

/**
 * The repository XML writer emits attribute values verbatim at the XML level: a C0 control character (e.g. U+0007) makes
 * the persisted repository.xml unreadable, so the storage fails at the next start; tabs and newlines are silently turned
 * into spaces by XML attribute-value normalization.
 *
 * <h2>Severity</h2>
 * minor. Rare input (a manifest attribute value may contain any character except NUL, CR, LF), but the consequence is
 * the loss of the whole persistent repository at the next server start (FileBasedRepositoryStorage constructor fails,
 * see also subrepos/51); the tab/newline case silently changes data.
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.RepositoryXMLWriter (writeAttributes,
 * writeDirectives, writeResource) as used by FileBasedRepositoryStorage.
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, XML round-trip fidelity (escaping, Unicode) review.
 *
 * <h2>Spec reference</h2>
 * XML 1.0 (Fifth Edition) 2.2 Characters: Char ::= #x9 | #xA | #xD | [#x20-#xD7FF] | ... (U+0007 is not allowed, not even as
 * a character reference); 3.3.3 Attribute-Value Normalization: "For a white space character (#x20, #xD, #xA, #x9), append
 * a space character (#x20) to the normalized value" (only &amp;#9;/&amp;#10;/&amp;#13; references survive). OSGi Compendium R8 132.5.6
 * (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.repository.html): "String - No conversion, use s".
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/RepositoryXMLWriter.java:131/134/147 writer.writeAttribute(VALUE, ...);
 * the JDK XMLStreamWriter escapes only &amp; &lt; &gt; and quotes, it neither rejects invalid characters nor encodes
 * #x9/#xA/#xD. Pre-existing.
 *
 * <h2>Root cause</h2>
 * No character-level encoding/validation of attribute values before handing them to XMLStreamWriter, and the writer's
 * output is never validated before it replaces the previous file.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=XmlInvalidCharacterIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testControlCharacterInAttributeSurvivesRestart: Expected the storage to restart with its persisted resources, but the
 *   repository.xml it wrote itself cannot be read back: java.lang.IllegalStateException: JBOSGI020504: Cannot read resource
 *   element: ... / javax.xml.stream.XMLStreamException: ParseError at [row,col]:[1,927]
 *   Message: An invalid XML character (Unicode: 0x7) was found in the value of attribute "value" and element is "attribute".
 * testTabAndNewlineInAttributeSurviveRestart: Expected the attribute value with a newline and a tab to survive the restart
 *   unchanged (the writer must emit &amp;#10; and &amp;#9;, ...), but it was read back as 'line1 line2 column'
 * </pre>
 *
 * <h2>Expected</h2>
 * Tab, CR and LF are written as character references and read back unchanged; a value that XML 1.0 cannot carry is either
 * encoded in a reversible way or rejected by addResource before anything is written, and the previously persisted
 * resources stay readable.
 *
 * <h2>Reference behaviour</h2>
 * bnd 7.0.0 XMLResourceGenerator has the same shortcoming (writes raw tab/newline and U+0007; its own parser then fails
 * with the same ParseError), so this is not a jbosgi-only weakness; it matters more here because the file is the only
 * persistent state of the server's repository.
 *
 * <h2>Suggested fix</h2>
 * Before writing, replace #x9/#xA/#xD by &amp;#9;/&amp;#10;/&amp;#13; (XMLStreamWriter.writeAttribute cannot, so write the
 * attributes through a small escaping routine or post-process) and reject (IllegalArgumentException from addResource,
 * before the cache is updated) any value containing characters outside the XML 1.0 Char production.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class XmlInvalidCharacterIssueTestCase {

    private File storageDir;
    private XRepository repository;

    @Before
    public void setUp() throws Exception {
        storageDir = Files.createTempDirectory("xml-chars").toFile();
        repository = Mockito.mock(XRepository.class);
        Mockito.when(repository.getName()).thenReturn("MockedRepo");
    }

    @After
    public void tearDown() {
        deleteRecursive(storageDir);
    }

    @Test
    public void testControlCharacterInAttributeSurvivesRestart() throws Exception {
        RepositoryStorage storage = new FileBasedRepositoryStorage(repository, storageDir, (key, def) -> def);
        storage.addResource(createResource("acme.a", "plain"));
        // Export-Package: org.acmeb;note="bell\u0007" (any character but NUL, CR, LF is allowed in a manifest value)
        boolean added;
        try {
            storage.addResource(createResource("acme.b", "bell\u0007"));
            added = true;
        } catch (RuntimeException ex) {
            added = false; // rejecting the resource would be acceptable
        }

        RepositoryStorage restarted;
        try {
            restarted = new FileBasedRepositoryStorage(repository, storageDir, (key, def) -> def);
        } catch (RuntimeException ex) {
            fail("Expected the storage to restart with its persisted resources, but the repository.xml it wrote itself "
                    + "cannot be read back: " + ex + (ex.getCause() != null ? " / " + ex.getCause() : ""));
            return;
        }
        assertEquals("Expected acme.a after the restart", 1,
                restarted.findProviders(XRequirementBuilder.create("osgi.identity", "acme.a").getRequirement()).size());
        if (added) {
            assertEquals("Expected the accepted resource acme.b after the restart", 1,
                    restarted.findProviders(XRequirementBuilder.create("osgi.identity", "acme.b").getRequirement()).size());
        }
    }

    @Test
    public void testTabAndNewlineInAttributeSurviveRestart() throws Exception {
        RepositoryStorage storage = new FileBasedRepositoryStorage(repository, storageDir, (key, def) -> def);
        String note = "line1\nline2\tcolumn";
        storage.addResource(createResource("acme.c", note));

        RepositoryStorage restarted = new FileBasedRepositoryStorage(repository, storageDir, (key, def) -> def);
        Object actual = restarted.findProviders(XRequirementBuilder.create("osgi.wiring.package", "org.acmec").getRequirement())
                .iterator().next().getAttributes().get("note");
        assertEquals("Expected the attribute value with a newline and a tab to survive the restart unchanged "
                + "(the writer must emit &#10; and &#9;, XML attribute-value normalization turns raw ones into spaces), but it was read back as '"
                + actual + "'", note, actual);
    }

    private static XResource createResource(String name, String note) {
        XResourceBuilder<XResource> builder = XResourceBuilderFactory.create();
        XIdentityCapability icap = builder.addIdentityCapability(name, Version.parseVersion("1.0"));
        icap.getAttributes().put("type", "abstract");
        Map<String, Object> atts = new HashMap<String, Object>();
        atts.put("osgi.wiring.package", "org." + name.replace("acme.", "acme"));
        atts.put("note", note);
        builder.addCapability("osgi.wiring.package", atts, Collections.<String, String> emptyMap());
        return builder.getResource();
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
