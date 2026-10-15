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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jboss.osgi.repository.RepositoryReader;
import org.jboss.osgi.repository.RepositoryXMLReader;
import org.jboss.osgi.resolver.XResource;
import org.junit.Test;

/**
 * The repository XML reader stops at a referral element: every resource that follows a &lt;referral&gt; in a repository XML
 * document is silently dropped (no error, zero resources).
 *
 * <h2>Severity</h2>
 * minor. Federated repository indexes (referral elements, 132.5.2) read as empty without any error; jbosgi reads such
 * XML through RepositoryXMLReader (e.g. ProvisionerSupport.populateRepository for feature files).
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.RepositoryXMLReader (nextResource); Namespace100.Element
 * has no REFERRAL.
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, XML format conformance review; bnd 7.0.0 XMLResourceParser as reference.
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8 (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.repository.html) 132.5.1: "The repository element has the following child elements: referral* -
 * Referrals to other repositories for a federated model ... resource* - Resource definitions"; 132.5.2: "Referrals are
 * applied recursively. ... If the depth attribute is &gt;= 1, the referred repository must be included"; 132.6 schema
 * Trepository: &lt;choice minOccurs="0" maxOccurs="unbounded"&gt; resource | referral.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/RepositoryXMLReader.java:92-99 (WF41-osgi-r8 1eab73d), pre-existing:
 * <pre>
 * while (reader.hasNext() &amp;&amp; reader.nextTag() != END_ELEMENT) {
 *     Element element = Element.forName(reader.getLocalName());
 *     switch (element) { case RESOURCE: return readResourceElement(reader); }   // referral = UNKNOWN: not skipped
 * }
 * </pre>
 * The unknown element is not skipped; the next nextTag() returns its END_ELEMENT and the loop treats it as the end of
 * the repository.
 *
 * <h2>Root cause</h2>
 * Unknown/unsupported elements are not skipped (and referrals are not supported at all).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=XmlReferralElementIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testResourcesAfterReferral: Expected both resources that follow a &lt;referral&gt; element (132.5.2, schema: choice of
 *   resource|referral), but read expected:&lt;[acme.a, acme.b]&gt; but was:&lt;[]&gt;
 * </pre>
 *
 * <h2>Expected</h2>
 * Both resources are read (the referral itself followed, or at least skipped with a warning when no base URL is known).
 *
 * <h2>Reference behaviour</h2>
 * bnd 7.0.0 XMLResourceParser reads both resources and follows the referral (relative url resolved against the document).
 *
 * <h2>Suggested fix</h2>
 * Add REFERRAL to Namespace100.Element; in nextResource skip any element that is not RESOURCE up to its matching end tag
 * (track depth), optionally resolve referrals when the reader is given a base URL.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class XmlReferralElementIssueTestCase {

    private static final String HEAD = "<?xml version='1.0' encoding='UTF-8'?>"
            + "<repository xmlns='http://www.osgi.org/xmlns/repository/v1.0.0' name='test'>";

    private static String resource(String name, String extra) {
        return "<resource>"
                + "<capability namespace='osgi.identity'>"
                + "<attribute name='osgi.identity' value='" + name + "'/>"
                + "<attribute name='type' value='abstract'/>"
                + extra
                + "</capability>"
                + "</resource>";
    }

    @Test
    public void testResourcesAfterReferral() throws Exception {
        String xml = HEAD
                + "<referral url='https://repo.example.com/other.xml' depth='1'/>"
                + resource("acme.a", "")
                + resource("acme.b", "")
                + "</repository>";
        List<String> names = readNames(xml);
        assertEquals("Expected both resources that follow a <referral> element (132.5.2, schema: choice of resource|referral), but read",
                List.of("acme.a", "acme.b"), names);
    }

    private static List<String> readNames(String xml) {
        RepositoryReader reader = RepositoryXMLReader.create(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        List<String> names = new ArrayList<String>();
        try {
            XResource res = reader.nextResource();
            while (res != null) {
                names.add(res.getIdentityCapability().getName());
                res = reader.nextResource();
            }
        } catch (RuntimeException ex) {
            fail("Expected the repository XML to be read, but reading failed after " + names + " with: "
                    + ex.getMessage().replace('\n', ' ') + (ex.getCause() != null ? " / " + ex.getCause() : ""));
        } finally {
            reader.close();
        }
        return names;
    }
}
