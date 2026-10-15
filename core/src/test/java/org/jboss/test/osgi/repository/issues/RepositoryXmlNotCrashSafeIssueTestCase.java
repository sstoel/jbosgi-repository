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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.parsers.DocumentBuilderFactory;

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
 * The persistent repository is not crash safe: repository.xml is rewritten in place on every change, so a failed or
 * interrupted write (exception, kill -9, power loss) truncates it and the storage, with every resource in it, can no
 * longer be opened; a failed addResource also leaves the resource half-added in memory.
 *
 * <h2>Severity</h2>
 * major. In WildFly the storage is created by RepositoryService.start (subsystem osgi); when repository.xml cannot be read
 * the FileBasedRepositoryStorage constructor throws and the XRepository service (and everything depending on it:
 * provisioner, bootstrap bundles integration) fails to start until the file is deleted by hand. Every addResource rewrites
 * the whole file (O(n) per change), so for a large repository most of the time is spent inside the unsafe window.
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.spi.FileBasedRepositoryStorage (writeRepositoryXML,
 * addContentResource, addAbstractResource, constructor).
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, review of persistence/crash consistency; confirmed by this test (failure injection with an
 * attribute the XML writer cannot serialize, and by observing the file while resources are added).
 *
 * <h2>Spec reference</h2>
 * Not an OSGi spec clause (implementation robustness). XPersistentRepository / RepositoryStorage promise persistent
 * storage; Repository 132.9.8 is "Thread-safe".
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/spi/FileBasedRepositoryStorage.java (WF41-osgi-r8 1eab73d), pre-existing:
 * <ul>
 * <li>:292-310 writeRepositoryXML opens new FileOutputStream(repoFile) (truncates the live file) and streams the whole
 * repository into it; no temporary file + atomic rename, no fsync, the stream is never closed (XMLStreamWriter.close()
 * does not close the underlying stream) and is leaked on an exception.</li>
 * <li>:155-158 and :163-166 the resource is added to the in-memory cache (super.addResource) before the XML is written;
 * a write failure is not rolled back.</li>
 * <li>:86-101 the constructor aborts on the first XML error, so one truncated file loses all resources.</li>
 * </ul>
 *
 * <h2>Root cause</h2>
 * In-place rewrite of the only copy of the repository state, without write-to-temp-and-rename and without rollback of
 * the in-memory state.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=RepositoryXmlNotCrashSafeIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testFailedWriteKeepsPersistedResources: Expected the storage to restart with the previously persisted resource acme.a
 *   after a failed addResource, but repository.xml (395 bytes) can no longer be read: java.lang.IllegalStateException:
 *   JBOSGI020504: Cannot read resource element: Line number = 1 Column number = 396 ...
 * testFailedAddLeavesNoHalfAddedResource: Expected a resource whose addResource failed not to be found afterwards (it is
 *   not persisted), but it is expected:&lt;0&gt; but was:&lt;1&gt;
 * testRepositoryXmlIsNeverSeenPartiallyWritten: Expected repository.xml to be replaced atomically, so that a crash at any
 *   moment leaves a readable file, but after 4 observations it was found incomplete (629 bytes: XML document structures
 *   must start and end within the same entity.)
 *   (a complete repository.xml had been observed before; 3 of 3 runs failed within 5 observations)
 * </pre>
 * (the injected write failure is the IllegalArgumentException "No enum constant ...Namespace100.Type.Integer" of
 * AttributeValue.create for an Integer attribute; any IOException, e.g. disk full, has the same effect)
 *
 * <h2>Expected</h2>
 * At every moment repository.xml is either the previous or the new complete state; a failed addResource leaves both the
 * file and the in-memory storage unchanged.
 *
 * <h2>Reference behaviour</h2>
 * n/a (jbosgi-specific storage).
 *
 * <h2>Suggested fix</h2>
 * Write to repository.xml.tmp in the same directory, close (and FileChannel.force) it, then
 * Files.move(tmp, repoFile, ATOMIC_MOVE, REPLACE_EXISTING); close the stream in finally. Validate/serialize the resource
 * before mutating the cache, or remove it from the cache when the write fails. Optionally keep the previous file as
 * repository.xml.bak and fall back to it in the constructor.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class RepositoryXmlNotCrashSafeIssueTestCase {

    private File storageDir;
    private XRepository repository;

    @Before
    public void setUp() throws Exception {
        storageDir = Files.createTempDirectory("crash-safe").toFile();
        repository = Mockito.mock(XRepository.class);
        Mockito.when(repository.getName()).thenReturn("MockedRepo");
    }

    @After
    public void tearDown() {
        deleteRecursive(storageDir);
    }

    @Test
    public void testFailedWriteKeepsPersistedResources() throws Exception {
        RepositoryStorage storage = new FileBasedRepositoryStorage(repository, storageDir, (key, def) -> def);
        storage.addResource(createResource("acme.a", null));

        // A resource whose attribute cannot be written as XML (Integer is not one of the XML types)
        try {
            storage.addResource(createResource("acme.b", Integer.valueOf(1)));
        } catch (RuntimeException ex) {
            // a rejected resource is fine
        }

        RepositoryStorage restarted;
        try {
            restarted = new FileBasedRepositoryStorage(repository, storageDir, (key, def) -> def);
        } catch (RuntimeException ex) {
            fail("Expected the storage to restart with the previously persisted resource acme.a after a failed addResource, "
                    + "but repository.xml (" + new File(storageDir, "repository.xml").length() + " bytes) can no longer be read: " + ex);
            return;
        }
        assertEquals("Expected acme.a to be persisted after a failed addResource of another resource",
                1, restarted.findProviders(XRequirementBuilder.create("osgi.identity", "acme.a").getRequirement()).size());
    }

    @Test
    public void testFailedAddLeavesNoHalfAddedResource() throws Exception {
        RepositoryStorage storage = new FileBasedRepositoryStorage(repository, storageDir, (key, def) -> def);
        try {
            storage.addResource(createResource("acme.b", Integer.valueOf(1)));
        } catch (RuntimeException ex) {
            // a rejected resource is fine
        }
        assertEquals("Expected a resource whose addResource failed not to be found afterwards (it is not persisted), but it is",
                0, storage.findProviders(XRequirementBuilder.create("osgi.identity", "acme.b").getRequirement()).size());
    }

    @Test
    public void testRepositoryXmlIsNeverSeenPartiallyWritten() throws Exception {
        final RepositoryStorage storage = new FileBasedRepositoryStorage(repository, storageDir, (key, def) -> def);
        final File repoFile = new File(storageDir, "repository.xml");
        final AtomicReference<Throwable> writerFailure = new AtomicReference<Throwable>();
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 400 && !stop.get(); i++) {
                    storage.addResource(createResource("acme.r" + i, null));
                }
            } catch (Throwable th) {
                writerFailure.set(th);
            }
        });
        writer.start();

        // Every observation of repository.xml is what a crash (kill -9, power loss) at that moment would leave behind
        String partial = null;
        int observations = 0;
        boolean sawComplete = false;
        while (writer.isAlive() && partial == null) {
            if (repoFile.exists()) {
                byte[] bytes = Files.readAllBytes(repoFile.toPath());
                observations++;
                try {
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
                    sawComplete = true;
                } catch (Exception ex) {
                    // only count it once a complete repository.xml existed: that previous state is what a crash now loses
                    if (sawComplete) {
                        partial = bytes.length + " bytes: " + ex.getMessage();
                    }
                }
            }
        }
        stop.set(true);
        writer.join();
        assertNull(writerFailure.get());
        assertNull("Expected repository.xml to be replaced atomically, so that a crash at any moment leaves a readable file, "
                + "but after " + observations + " observations it was found incomplete (" + partial + ")", partial);
    }

    private static XResource createResource(String name, Object extra) {
        XResourceBuilder<XResource> builder = XResourceBuilderFactory.create();
        XIdentityCapability icap = builder.addIdentityCapability(name, Version.parseVersion("1.0"));
        icap.getAttributes().put("type", "abstract");
        if (extra != null) {
            java.util.Map<String, Object> atts = new java.util.HashMap<String, Object>();
            atts.put("com.acme.extra", name);
            atts.put("count", extra);
            builder.addCapability("com.acme.extra", atts, Collections.<String, String> emptyMap());
        }
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
