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

import java.util.Arrays;
import java.util.Map;

import org.jboss.osgi.repository.spi.AbstractPersistentRepository;
import org.jboss.osgi.repository.spi.MemoryRepositoryStorage;
import org.junit.Test;
import org.osgi.resource.Requirement;
import org.osgi.resource.Capability;
import org.osgi.service.repository.Repository;
import org.osgi.service.repository.RequirementBuilder;

import java.util.Collection;

/**
 * RequirementBuilder.build() returns a requirement that is a live view of the builder's maps: changing the builder
 * afterwards (addDirective/addAttribute) changes requirements that were already built, including their hash code.
 *
 * <h2>Severity</h2>
 * minor. Reusing a builder (as the spec's own example chain suggests) silently changes earlier requirements; such a
 * requirement used as a key of the findProviders(Collection) result map can no longer be found in it.
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.internal.RequirementBuilderImpl (build, RequirementImpl).
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, Repository 1.1 API review; bnd 7.0.0 as reference.
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8 (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.repository.html) 132.9.10.3 build(): "Create a requirement based upon the values set in this
 * requirement builder." 132.9.8.2 findProviders: "Each specified requirement must appear as a key in the map."
 * Core R8 Resource API: Requirement.getDirectives/getAttributes return the requirement's directives/attributes (a built
 * requirement has no way to be changed by its creator).
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/internal/RequirementBuilderImpl.java:75-77 passes the builder's own
 * attributes/directives HashMaps; :96-100 RequirementImpl wraps them with Collections.unmodifiableMap (a view, not a copy).
 * Pre-existing (Repository 1.1 support, 2016).
 *
 * <h2>Root cause</h2>
 * No defensive copy in build(); addAttribute/addDirective mutate the shared maps (setAttributes/setDirectives replace them,
 * which is why only the add* path shows it).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=RequirementBuilderAliasingIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testBuiltRequirementIsNotChangedByBuilder: Expected the first requirement to keep the filter it was built with, but
 *   building a second requirement changed it to (osgi.identity=acme.b) expected:&lt;(osgi.identity=acme.[a])&gt; but was:&lt;(osgi.identity=acme.[b])&gt;
 * testFindProvidersMapKeysAfterBuilderReuse: Expected the requirement to remain a key of the findProviders result map
 *   (132.9.8.2: each specified requirement must appear as a key), but changing the builder afterwards changed the
 *   requirement's hash code
 * </pre>
 *
 * <h2>Expected</h2>
 * A built requirement is a snapshot; later builder changes do not affect it.
 *
 * <h2>Reference behaviour</h2>
 * bnd 7.0.0 BaseRepository.newRequirementBuilder: after build() and a further addDirective the first requirement keeps
 * filter (osgi.identity=acme.a).
 *
 * <h2>Suggested fix</h2>
 * In build(): new RequirementImpl(namespace, new HashMap&lt;&gt;(attributes), new HashMap&lt;&gt;(directives), resource).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class RequirementBuilderAliasingIssueTestCase {

    @Test
    public void testBuiltRequirementIsNotChangedByBuilder() throws Exception {
        Repository repo = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        RequirementBuilder rb = repo.newRequirementBuilder("osgi.identity");
        rb.addDirective("filter", "(osgi.identity=acme.a)").addAttribute("purpose", "first");
        Requirement first = rb.build();

        // Reuse the builder for a second requirement
        rb.addDirective("filter", "(osgi.identity=acme.b)").addAttribute("purpose", "second");
        Requirement second = rb.build();

        assertEquals("Expected the first requirement to keep the filter it was built with, but building a second requirement changed it to "
                + first.getDirectives().get("filter"), "(osgi.identity=acme.a)", first.getDirectives().get("filter"));
        assertEquals("Expected the first requirement to keep the attribute it was built with, but it is now "
                + first.getAttributes().get("purpose"), "first", first.getAttributes().get("purpose"));
        assertEquals("(osgi.identity=acme.b)", second.getDirectives().get("filter"));
    }

    @Test
    public void testFindProvidersMapKeysAfterBuilderReuse() throws Exception {
        Repository repo = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        RequirementBuilder rb = repo.newRequirementBuilder("osgi.identity");
        Requirement first = rb.addDirective("filter", "(osgi.identity=acme.a)").build();
        Map<Requirement, Collection<Capability>> result = repo.findProviders(Arrays.asList(first));
        rb.addDirective("filter", "(osgi.identity=acme.b)");
        assertTrue("Expected the requirement to remain a key of the findProviders result map (132.9.8.2: each specified requirement "
                + "must appear as a key), but changing the builder afterwards changed the requirement's hash code",
                result.containsKey(first));
    }
}
