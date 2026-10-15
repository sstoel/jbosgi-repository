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

import static org.junit.Assert.fail;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import org.jboss.osgi.repository.spi.MavenIdentityRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.resource.Requirement;
import org.osgi.service.repository.Repository;

/**
 * MavenIdentityRepository.findProviders casts every maven.identity requirement that carries a filter directive to
 * XRequirement: a requirement built with the spec API (Repository.newRequirementBuilder) fails with ClassCastException.
 *
 * <h2>Severity</h2>
 * minor. Only requirements in jbosgi's own maven.identity namespace are affected; through the OSGi Repository service of
 * the server (AbstractPersistentRepository delegating to MavenIdentityRepository) the ClassCastException reaches the caller.
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.spi.MavenIdentityRepository (findProviders).
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, Maven resolution review.
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8 132.9.8.2 (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.repository.html) findProviders(Collection): "requirements The requirements for which matching
 * capabilities should be returned." and 132.9.8.5 newRequirementBuilder: "Return a new RequirementBuilder which provides a
 * convenient way to create a requirement." Requirements are org.osgi.resource.Requirement; a Repository may not assume its
 * own implementation class.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/spi/MavenIdentityRepository.java:135-136:
 * Filter filter = ((XRequirement)req).getFilter(); Pre-existing.
 *
 * <h2>Root cause</h2>
 * Implementation-class cast instead of parsing the filter directive (AbstractRequirement.getFilterFromDirective(req)
 * accepts any Requirement).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=MavenFilterRequirementIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testFilterRequirementFromRequirementBuilder: Expected a maven.identity requirement built with
 *   Repository.newRequirementBuilder() to be looked up (no provider in an empty repository), but findProviders failed
 *   with: java.lang.ClassCastException: class org.jboss.osgi.repository.internal.RequirementBuilderImpl$RequirementImpl
 *   cannot be cast to class org.jboss.osgi.resolver.XRequirement
 * </pre>
 *
 * <h2>Expected</h2>
 * The lookup runs (here: no provider, empty collection).
 *
 * <h2>Reference behaviour</h2>
 * n/a (jbosgi-specific namespace).
 *
 * <h2>Suggested fix</h2>
 * Use AbstractRequirement.getFilterFromDirective(req) instead of the cast.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class MavenFilterRequirementIssueTestCase {

    private File emptyRepo;

    @Before
    public void setUp() throws Exception {
        emptyRepo = Files.createTempDirectory("empty-maven-repo").toFile();
    }

    @After
    public void tearDown() {
        emptyRepo.delete();
    }

    @Test
    public void testFilterRequirementFromRequirementBuilder() throws Exception {
        final URL base = emptyRepo.toURI().toURL();
        Repository repository = new MavenIdentityRepository(new MavenIdentityRepository.Configuration() {
            @Override
            public List<URL> getBaseURLs() {
                return Collections.singletonList(base);
            }
        });
        Requirement req = repository.newRequirementBuilder("maven.identity")
                .addDirective("filter", "(&(groupId=org.acme)(artifactId=acme-bundle)(version=1.0.0))").build();
        try {
            repository.findProviders(Collections.singleton(req));
        } catch (ClassCastException ex) {
            fail("Expected a maven.identity requirement built with Repository.newRequirementBuilder() to be looked up "
                    + "(no provider in an empty repository), but findProviders failed with: " + ex);
        }
    }
}
