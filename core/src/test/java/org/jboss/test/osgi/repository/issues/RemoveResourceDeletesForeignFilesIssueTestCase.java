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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;

import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.URLResourceBuilderFactory;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.repository.spi.FileBasedRepositoryStorage;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XRequirementBuilder;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.osgi.framework.Version;
import org.osgi.resource.Capability;
import org.osgi.service.repository.ContentNamespace;

/**
 * FileBasedRepositoryStorage.removeResource deletes the parent directory of whatever file the caller's resource
 * points to, even outside the repository storage and even if the resource was never added (data loss); stored
 * content under a path that needs URL encoding is never deleted.
 *
 * <h2>Severity</h2>
 * major (data loss). A recursive delete of a directory outside the repository, e.g. the directory that holds the
 * user's bundle jars. Reachable through the public RepositoryStorage API (XRepository.adapt(RepositoryStorage.class),
 * the XRepository is registered as an OSGi service in WildFly by SystemServicesIntegration); not on a default server path.
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.spi.FileBasedRepositoryStorage (removeResourceInternal).
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, code review of the storage implementation, confirmed by this test.
 *
 * <h2>Spec reference</h2>
 * Not an OSGi spec clause (jbosgi SPI). RepositoryStorage.removeResource javadoc: "Remove a the given resource from the
 * cache." Nothing allows it to delete files the storage did not create.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/spi/FileBasedRepositoryStorage.java:176-189 (WF41-osgi-r8 1eab73d),
 * unchanged since 17f5720 (2016), pre-existing:
 * <pre>
 * List&lt;Capability&gt; ccaps = res.getCapabilities(ContentNamespace.CONTENT_NAMESPACE);
 * if (!ccaps.isEmpty()) {
 *     String fileURL = (String) ccap.getAttribute(ContentNamespace.CAPABILITY_URL_ATTRIBUTE);
 *     File contentFile = new File(fileURL.substring("file:".length()));      // line 181
 *     if (contentFile.exists()) {
 *         result = deleteRecursive(contentFile.getParentFile());           // line 183
 *     }
 * }
 * result &amp;= super.removeResource(res);                                   // membership checked only now
 * </pre>
 *
 * <h2>Root cause</h2>
 * The file to delete is derived from the content URL of the resource object passed in by the caller, not from the
 * resource held by the storage, and it is deleted before (and regardless of) the check whether the resource is in the
 * storage at all. addResource copies foreign content into the storage and returns a different resource object, so a
 * caller that removes the resource it added (or any resource with a file: content URL) makes the storage delete the
 * caller's directory recursively. In addition the path is cut out of the URL string without URL decoding, so a storage
 * directory containing a space (file:/.../server%20data/...) is never matched and the stored content leaks.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=RemoveResourceDeletesForeignFilesIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testRemoveAddedResourceKeepsCallersFiles: Expected the caller's file .../user-bundles/important-notes.txt to survive
 *   removeResource (it is not repository storage), but the directory .../user-bundles was deleted
 * testRemoveUnknownResourceKeepsCallersFiles: Expected removeResource of a resource that is not in the storage to touch
 *   nothing, but the directory .../user-bundles was deleted
 * testRemoveDeletesStoredContentWhenPathNeedsEncoding: Expected removeResource to delete the stored content
 *   .../server data/repository/d2/c6cf.../content (storage directory with a space), but it is still there
 * </pre>
 *
 * <h2>Expected</h2>
 * removeResource only deletes content the storage owns: look up the stored resource first (by identity), return false and
 * touch nothing if it is not stored, and delete only its content directory below storageDir.
 *
 * <h2>Reference behaviour</h2>
 * n/a (jbosgi-specific storage SPI).
 *
 * <h2>Suggested fix</h2>
 * In removeResourceInternal: resolve the stored resource (getResource(res.getIdentityCapability())), return false if it
 * is absent; convert its content URL with URLResourceBuilderFactory.urlToFile(new URL(...)), canonicalize it and only delete
 * when it lies below storageDir.getCanonicalFile(); then remove it from the cache.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class RemoveResourceDeletesForeignFilesIssueTestCase {

    private File workDir;
    private File userDir;
    private File userJar;
    private File userNotes;
    private XRepository repository;

    @Before
    public void setUp() throws Exception {
        workDir = Files.createTempDirectory("remove-resource").toFile();
        userDir = new File(workDir, "user-bundles");
        userDir.mkdirs();
        userJar = new File(userDir, "acme.jar");
        Files.write(userJar.toPath(), "not really a jar".getBytes(StandardCharsets.UTF_8));
        userNotes = new File(userDir, "important-notes.txt");
        Files.write(userNotes.toPath(), "do not delete".getBytes(StandardCharsets.UTF_8));
        repository = Mockito.mock(XRepository.class);
        Mockito.when(repository.getName()).thenReturn("MockedRepo");
    }

    @After
    public void tearDown() {
        deleteRecursive(workDir);
    }

    @Test
    public void testRemoveAddedResourceKeepsCallersFiles() throws Exception {
        RepositoryStorage storage = new FileBasedRepositoryStorage(repository, new File(workDir, "storage"), (key, def) -> def);
        XResource original = createResource(userJar.toURI().toURL());
        storage.addResource(original);

        // The caller removes the resource it added
        storage.removeResource(original);

        assertTrue("Expected the caller's file " + userNotes + " to survive removeResource (it is not repository storage), but the directory "
                + userDir + " was deleted", userNotes.exists());
        assertTrue("Expected the caller's bundle " + userJar + " to survive removeResource, but it was deleted", userJar.exists());
    }

    @Test
    public void testRemoveUnknownResourceKeepsCallersFiles() throws Exception {
        RepositoryStorage storage = new FileBasedRepositoryStorage(repository, new File(workDir, "storage"), (key, def) -> def);
        XResource neverAdded = createResource(userJar.toURI().toURL());

        boolean removed = storage.removeResource(neverAdded);

        assertFalse("A resource that was never added cannot be removed", removed);
        assertTrue("Expected removeResource of a resource that is not in the storage to touch nothing, but the directory "
                + userDir + " was deleted", userNotes.exists());
    }

    @Test
    public void testRemoveDeletesStoredContentWhenPathNeedsEncoding() throws Exception {
        File storageDir = new File(workDir, "server data/repository");
        RepositoryStorage storage = new FileBasedRepositoryStorage(repository, storageDir, (key, def) -> def);
        storage.addResource(createResource(userJar.toURI().toURL()));

        Collection<Capability> caps = storage.findProviders(XRequirementBuilder.create("osgi.identity", "acme").getRequirement());
        assertEquals(1, caps.size());
        XResource stored = (XResource) caps.iterator().next().getResource();
        String contentURL = (String) stored.getCapabilities(ContentNamespace.CONTENT_NAMESPACE).get(0).getAttributes()
                .get(ContentNamespace.CAPABILITY_URL_ATTRIBUTE);
        File contentFile = URLResourceBuilderFactory.urlToFile(new URL(contentURL));
        assertTrue(contentFile.exists());

        assertTrue(storage.removeResource(stored));
        assertFalse("Expected removeResource to delete the stored content " + contentFile + " (storage directory with a space), but it is still there",
                contentFile.exists());
    }

    private static XResource createResource(URL contentURL) {
        XResourceBuilder<XResource> builder = URLResourceBuilderFactory.create(contentURL, null);
        XIdentityCapability icap = builder.addIdentityCapability("acme", Version.parseVersion("1.0"));
        icap.getAttributes().put("type", "osgi.bundle");
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
