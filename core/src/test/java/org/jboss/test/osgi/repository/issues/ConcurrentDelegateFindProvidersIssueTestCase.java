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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.RepositoryStorageFactory;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.repository.spi.AbstractPersistentRepository;
import org.jboss.osgi.repository.spi.AbstractRepository;
import org.jboss.osgi.repository.spi.MemoryRepositoryStorage;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XRequirementBuilder;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.jboss.osgi.resolver.XResourceBuilderFactory;
import org.junit.Test;
import org.osgi.framework.Version;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;

/**
 * AbstractPersistentRepository.findProviders is not thread safe: two concurrent lookups of the same resource that the
 * storage does not hold yet both fetch it from a delegate (Maven/module repository) and both add it to the storage; the
 * second add fails with "JBOSGI020501: Resource already exists" and that caller's findProviders throws.
 *
 * <h2>Severity</h2>
 * major. In WildFly the XRepository (AbstractPersistentRepository with FileBasedRepositoryStorage and the module and Maven
 * delegates) is shared by the provisioner and registered as the OSGi Repository service; two deployments or bundles that
 * need the same not-yet-cached artifact at the same time make one of them fail. The window is wide: a Maven lookup
 * downloads the artifact (seconds) between the "already stored?" check and the add.
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.spi.AbstractPersistentRepository (findProviders).
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, concurrency review of findProviders/addResource; confirmed with a barrier that makes the two
 * lookups overlap deterministically.
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8, 132.9.8 public interface Repository: "Concurrency Thread-safe" (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.repository.html).
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/spi/AbstractPersistentRepository.java:104-128 (WF41-osgi-r8 1eab73d),
 * pre-existing:
 * <pre>
 * XResource storageResource = storage.getResource(icap);        // line 118, check
 * if (storageResource == null) {
 *     storageResource = storage.addResource(res);               // line 120, act: throws resourceAlreadyExists
 * }
 * </pre>
 * MemoryRepositoryStorage.addResource:156 throws MESSAGES.resourceAlreadyExists for an identical identity capability.
 * The method also iterates the unsynchronized delegates list while addRepositoryDelegate may modify it.
 *
 * <h2>Root cause</h2>
 * Check-then-act on the storage without a lock spanning getResource and addResource (each is synchronized on its own).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=ConcurrentDelegateFindProvidersIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testConcurrentLookupOfSameRemoteResource: Expected two concurrent findProviders calls for the same resource to both
 *   find it (Repository is Thread-safe), but a call failed with [java.lang.IllegalStateException: JBOSGI020501: Resource
 *   already exists: AbstractResource[acme.remote:1.0.0]]
 * </pre>
 * The test storage only inserts a barrier after the (unchanged) getResource check so that both threads reach it before
 * either adds; production timing gives the same interleaving whenever two delegate lookups overlap.
 *
 * <h2>Expected</h2>
 * Both calls return the (same) stored resource's capability.
 *
 * <h2>Reference behaviour</h2>
 * n/a (jbosgi-specific delegation).
 *
 * <h2>Suggested fix</h2>
 * Perform getResource-or-add atomically: synchronize the block on the storage (or a lock per identity), or catch the
 * "already exists" case and return storage.getResource(icap) instead. Copy the delegates list under its lock (or use
 * CopyOnWriteArrayList) before iterating.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class ConcurrentDelegateFindProvidersIssueTestCase {

    /** A delegate like MavenIdentityRepository: every lookup builds a new resource instance for the same artifact */
    static class RemoteDelegate extends AbstractRepository {
        @Override
        public Collection<Capability> findProviders(Requirement req) {
            if (!"osgi.identity".equals(req.getNamespace()))
                return Collections.emptyList();
            XResourceBuilder<XResource> builder = XResourceBuilderFactory.create();
            XIdentityCapability icap = builder.addIdentityCapability("acme.remote", Version.parseVersion("1.0"));
            icap.getAttributes().put("type", "abstract");
            return Collections.<Capability> singletonList(builder.getResource().getIdentityCapability());
        }
    }

    /**
     * The storage of the repository. The barrier in getResource only makes the two lookups reach the
     * "is it already stored?" check at the same time, which is what happens when two threads look up the same
     * remote artifact (the remote lookup takes seconds, the check-then-add window is not synchronized).
     */
    static class ObservedStorage extends MemoryRepositoryStorage {
        final CyclicBarrier barrier = new CyclicBarrier(2);

        ObservedStorage(XRepository repository) {
            super(repository);
        }

        @Override
        public XResource getResource(XIdentityCapability icap) {
            XResource result = super.getResource(icap);
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                // the other lookup did not come; continue alone
            }
            return result;
        }
    }

    @Test
    public void testConcurrentLookupOfSameRemoteResource() throws Exception {
        final AbstractPersistentRepository repository = new AbstractPersistentRepository(new RepositoryStorageFactory() {
            @Override
            public RepositoryStorage create(XRepository repo) {
                return new ObservedStorage(repo);
            }
        });
        repository.addRepositoryDelegate(new RemoteDelegate());
        final XRequirement req = XRequirementBuilder.create("osgi.identity", "acme.remote").getRequirement();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Collection<Capability>>> futures = new ArrayList<Future<Collection<Capability>>>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(new Callable<Collection<Capability>>() {
                    @Override
                    public Collection<Capability> call() {
                        return repository.findProviders(req);
                    }
                }));
            }
            List<String> failures = new ArrayList<String>();
            for (Future<Collection<Capability>> future : futures) {
                try {
                    Collection<Capability> caps = future.get(30, TimeUnit.SECONDS);
                    assertEquals(1, caps.size());
                } catch (java.util.concurrent.ExecutionException ex) {
                    failures.add(String.valueOf(ex.getCause()));
                }
            }
            assertTrue("Expected two concurrent findProviders calls for the same resource to both find it (Repository is Thread-safe), "
                    + "but a call failed with " + failures, failures.isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }
}
