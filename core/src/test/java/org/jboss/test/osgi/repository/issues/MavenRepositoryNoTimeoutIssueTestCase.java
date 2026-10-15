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

import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jboss.osgi.repository.spi.MavenIdentityRepository;
import org.jboss.osgi.resolver.MavenCoordinates;
import org.jboss.osgi.resolver.XRequirementBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.resource.Requirement;

/**
 * MavenIdentityRepository uses URL.openStream() without connect or read timeouts: a Maven repository host that accepts
 * the connection but does not answer blocks findProviders (and the thread of every caller) forever.
 *
 * <h2>Severity</h2>
 * major. The Maven delegate is consulted by the WildFly XRepository (RepositoryService adds a MavenIdentityRepository with
 * the default base URLs ~/.m2/repository, https://repository.jboss.org/nexus/repository/public, https://repo1.maven.org/maven2)
 * for every requirement the local storage cannot satisfy in the maven.identity namespace (provisioner, OSGi Repository
 * service clients, BootstrapBundlesIntegration's Maven capability path). A stalled proxy, half-open firewall or
 * captive portal hangs the calling deployment/management thread indefinitely instead of failing.
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.spi.MavenIdentityRepository (findProviders); the same
 * pattern is in URLResourceBuilderFactory.URLResource.getContent and FileBasedRepositoryStorage.getResourceContent
 * (url.openStream()) which download the artifact afterwards.
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, Maven resolution failure modes (network failure, timeouts, offline behaviour); confirmed with a
 * local socket that accepts and never responds.
 *
 * <h2>Spec reference</h2>
 * Not an OSGi spec clause. java.net.URLConnection javadoc: setConnectTimeout/setReadTimeout "A timeout of zero is
 * interpreted as an infinite timeout"; URL.openStream() uses the defaults (0 = infinite unless the JVM-wide
 * sun.net.client.defaultConnectTimeout/defaultReadTimeout are set, which WildFly does not set).
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/spi/MavenIdentityRepository.java:151-160 (WF41-osgi-r8 1eab73d):
 * <pre>
 * URL url = mavenId.getArtifactURL(baseURL);
 * try {
 *     url.openStream().close();          // line 154: no timeout, also a full GET just to test existence
 * </pre>
 * Only the base URLs changed on the branch (329d3e6, http -&gt; https); the missing timeouts are pre-existing.
 *
 * <h2>Root cause</h2>
 * No timeouts on the URLConnection; existence is probed with a full GET whose body is never read.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=MavenRepositoryNoTimeoutIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testLookupAgainstUnresponsiveRepositoryTimesOut -- Time elapsed: 60.04 s &lt;&lt;&lt; FAILURE!
 * java.lang.AssertionError: Expected MavenIdentityRepository.findProviders to give up on a repository that accepts
 *   connections but never answers, but it was still blocked after 60 s (no connect/read timeout on the URLConnection)
 * </pre>
 *
 * <h2>Expected</h2>
 * The lookup fails over to the next base URL (or returns no provider) after a bounded, configurable timeout.
 *
 * <h2>Reference behaviour</h2>
 * Maven resolver (used by Maven itself) applies a connect timeout of 10 s and a request timeout of 30 min by default
 * (ConfigurationProperties.DEFAULT_CONNECT_TIMEOUT / DEFAULT_REQUEST_TIMEOUT). Not run as a differential.
 *
 * <h2>Suggested fix</h2>
 * Open a URLConnection, setConnectTimeout/setReadTimeout from a configuration property (e.g.
 * org.jboss.osgi.repository.maven.timeout, default 10-30 s), probe with HEAD for http(s), and apply the same settings to
 * the later content downloads.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing; WF41 used http URLs with the same code).
 */
public class MavenRepositoryNoTimeoutIssueTestCase {

    /** Generous upper bound for one lookup against an unresponsive repository */
    private static final int MAX_SECONDS = 60;

    private ServerSocket server;
    private final List<Socket> accepted = Collections.synchronizedList(new ArrayList<Socket>());
    private Thread acceptor;

    @Before
    public void setUp() throws Exception {
        // A repository host that accepts the connection but never answers (overloaded proxy, half-open firewall, tarpit)
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            try {
                while (!server.isClosed()) {
                    accepted.add(server.accept());
                }
            } catch (Exception ex) {
                // closed
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void tearDown() throws Exception {
        server.close();
        for (Socket socket : accepted) {
            socket.close();
        }
    }

    @Test
    public void testLookupAgainstUnresponsiveRepositoryTimesOut() throws Exception {
        final URL base = new URL("http://127.0.0.1:" + server.getLocalPort() + "/maven2");
        final MavenIdentityRepository repository = new MavenIdentityRepository(new MavenIdentityRepository.Configuration() {
            @Override
            public List<URL> getBaseURLs() {
                return Collections.singletonList(base);
            }
        });
        final Requirement req = XRequirementBuilder.create(MavenCoordinates.parse("org.acme:acme-bundle:1.0.0")).getRequirement();

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "maven-lookup");
            th.setDaemon(true);
            return th;
        });
        Future<?> future = executor.submit(() -> repository.findProviders(req));
        boolean finished;
        try {
            future.get(MAX_SECONDS, TimeUnit.SECONDS);
            finished = true;
        } catch (TimeoutException ex) {
            finished = false;
        } finally {
            executor.shutdownNow();
        }
        assertTrue("Expected MavenIdentityRepository.findProviders to give up on a repository that accepts connections but never answers, "
                + "but it was still blocked after " + MAX_SECONDS + " s (no connect/read timeout on the URLConnection)", finished);
    }
}
