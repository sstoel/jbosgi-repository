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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.repository.spi.MemoryRepositoryStorage;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.jboss.osgi.resolver.XResourceBuilderFactory;
import org.junit.Test;
import org.mockito.Mockito;
import org.osgi.framework.Version;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.service.repository.Repository;
import org.jboss.osgi.repository.spi.AbstractPersistentRepository;

/**
 * MemoryRepositoryStorage casts the value of a capability's namespace attribute to String: a resource with a typed
 * namespace attribute (Provide-Capability: com.acme.level;com.acme.level:Long=3, or :Version, :List&lt;String&gt;) cannot be
 * added to (or removed from) the repository storage (ClassCastException).
 *
 * <h2>Severity</h2>
 * minor. Typed namespace attributes are legal (Core R8 3.3.4) but uncommon; such a bundle can never be stored or
 * provisioned through the jbosgi repository (FileBasedRepositoryStorage inherits the cache).
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.spi.MemoryRepositoryStorage (addCachedCapability,
 * removeResource); inherited by FileBasedRepositoryStorage.
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, attribute-type coverage of the storage.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.3.4 Bundle Capability Attributes: "Attributes can be typed. ... attr:Long=24" (types String, Version, Long,
 * Double, List). OSGi Compendium R8 132.3 (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.repository.html): "A Repository must not perform any namespace specific actions
 * or matching. The Repository must therefore match a requirement to a capability with the following rules: The namespace
 * must be identical, and The requirement's filter is absent or it must match the capability's attributes."
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/spi/MemoryRepositoryStorage.java:239 (addCachedCapability) and :204
 * (removeResource): String nsvalue = (String) cap.getAttribute(namespace); the value is only used as a cache key.
 * Pre-existing.
 *
 * <h2>Root cause</h2>
 * The per-namespace cache is keyed by the namespace attribute and assumes it is a String.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=NonStringNamespaceAttributeIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testAddResourceWithLongNamespaceAttribute: Expected a resource with the capability com.acme.level;com.acme.level:Long=3
 *   to be added to the storage (any attribute type of Core 3.3.4 is allowed), but addResource failed with:
 *   java.lang.ClassCastException: class java.lang.Long cannot be cast to class java.lang.String
 * testFindProvidersForVersionNamespaceAttribute: Expected a resource with the capability
 *   com.acme.level;com.acme.level:Version=2.1 to be added to the storage, but addResource failed with:
 *   java.lang.ClassCastException: class org.osgi.framework.Version cannot be cast to class java.lang.String
 * </pre>
 *
 * <h2>Expected</h2>
 * The resource is stored and (com.acme.level&gt;=2.0) finds its capability.
 *
 * <h2>Reference behaviour</h2>
 * n/a (not run; bnd's ResourcesRepository indexes by namespace only).
 *
 * <h2>Suggested fix</h2>
 * Key the cache by String.valueOf(value) (or by Object), or index by namespace only (the lookup with nsvalue == null
 * already iterates all capabilities of the namespace).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class NonStringNamespaceAttributeIssueTestCase {

    private static final String NAMESPACE = "com.acme.level";

    @Test
    public void testAddResourceWithLongNamespaceAttribute() throws Exception {
        XRepository repository = Mockito.mock(XRepository.class);
        RepositoryStorage storage = new MemoryRepositoryStorage(repository);

        // Provide-Capability: com.acme.level;com.acme.level:Long=3
        XResource res = createResource(Long.valueOf(3));
        try {
            storage.addResource(res);
        } catch (ClassCastException ex) {
            fail("Expected a resource with the capability com.acme.level;com.acme.level:Long=3 to be added to the storage "
                    + "(any attribute type of Core 3.3.4 is allowed), but addResource failed with: " + ex);
        }
    }

    @Test
    public void testFindProvidersForVersionNamespaceAttribute() throws Exception {
        AbstractPersistentRepository repository = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        RepositoryStorage storage = repository.adapt(RepositoryStorage.class);

        // Provide-Capability: com.acme.level;com.acme.level:Version=2.1
        try {
            storage.addResource(createResource(Version.parseVersion("2.1")));
        } catch (ClassCastException ex) {
            fail("Expected a resource with the capability com.acme.level;com.acme.level:Version=2.1 to be added to the storage, "
                    + "but addResource failed with: " + ex);
        }
        Repository repo = repository;
        Requirement req = repo.newRequirementBuilder(NAMESPACE).addDirective("filter", "(" + NAMESPACE + ">=2.0)").build();
        Collection<Capability> caps = repo.findProviders(Collections.singleton(req)).get(req);
        assertEquals("Expected one capability matching (com.acme.level>=2.0), but found " + caps, 1, caps.size());
    }

    private static XResource createResource(Object level) {
        XResourceBuilder<XResource> builder = XResourceBuilderFactory.create();
        XIdentityCapability icap = builder.addIdentityCapability("acme.level", Version.parseVersion("1.0"));
        icap.getAttributes().put("type", "abstract");
        Map<String, Object> atts = new HashMap<String, Object>();
        atts.put(NAMESPACE, level);
        builder.addCapability(NAMESPACE, atts, Collections.<String, String> emptyMap());
        return builder.getResource();
    }
}
