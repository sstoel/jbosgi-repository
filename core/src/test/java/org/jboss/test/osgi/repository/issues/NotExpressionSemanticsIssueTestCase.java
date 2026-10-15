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
import java.util.Set;
import java.util.TreeSet;

import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.spi.AbstractPersistentRepository;
import org.jboss.osgi.repository.spi.MemoryRepositoryStorage;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.jboss.osgi.resolver.XResourceBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Version;
import org.osgi.resource.Resource;
import org.osgi.service.repository.ExpressionCombiner;
import org.osgi.service.repository.IdentityExpression;
import org.osgi.service.repository.Repository;

/**
 * findProviders(RequirementExpression) evaluates not(identity(req)) by negating the requirement's filter inside the same
 * namespace instead of taking the complement of the matching resources: resources without a capability in that namespace
 * are never found, resources with one matching and one non-matching capability are wrongly found, a requirement without
 * a filter throws IllegalStateException, and not(X) disagrees with and(all, not(X)).
 *
 * <h2>Severity</h2>
 * major. Silent wrong results of a Repository 1.1 API call (the OSGi Repository service registered by the WildFly
 * subsystem is this implementation). E.g. "all resources that do not export org.acme.a" returns the bundle that does
 * export it (because it also exports org.acme.b) and omits every bundle that exports nothing.
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.spi.AbstractRepository (findNotExpression,
 * negateRequirement).
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, expression combinators vs the Repository 1.1 spec (the Repository TCK only covers a not()
 * nested in and()); bnd 7.0.0 ResourcesRepository/BaseRepository as reference.
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8 (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.repository.html): 132.9.8.3 findProviders(RequirementExpression)
 * "Find the resources that match the specified requirement expression."; 132.9.6 NotExpression "A RequirementExpression
 * representing the not (negation) of a requirement expression."; 132.9.4.4 not "Return the negation of a
 * RequirementExpression." A resource matches identity(req) when one of its capabilities matches req, so it matches not(..)
 * exactly when it does not match the inner expression.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/spi/AbstractRepository.java:184-197 and :216-224 (WF41-osgi-r8 1eab73d),
 * unchanged since c95e776 (2016), pre-existing:
 * <pre>
 * if (re instanceof IdentityExpression) {
 *     Requirement nreq = negateRequirement(req);     // "(!" + filter + ")" in the same namespace
 *     return findSimpleRequirement(nreq);
 * ...
 * if (filter == null) throw new IllegalStateException("No filter directive: " + req);
 * </pre>
 * (not(and(..)) and not(or(..)) use the correct complement via findInverse; only not(identity) is wrong. findAndExpression
 * :166-171 also uses the complement, hence the inconsistency.)
 *
 * <h2>Root cause</h2>
 * "Has a capability that does not match F" is used for "has no capability that matches F".
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=NotExpressionSemanticsIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testNotOfPackageRequirement: Expected not(exports org.acme.a) to find the resources that do not match the expression,
 *   [acme.none], but found [acme.ab] expected:&lt;[acme.none]&gt; but was:&lt;[acme.ab]&gt;
 * testNotAgreesWithAndNot: Expected not(X) and and(&lt;all resources&gt;, not(X)) to find the same resources, but not(X) found
 *   [acme.ab] and and(all, not(X)) found expected:&lt;[acme.none]&gt; but was:&lt;[acme.ab]&gt;
 * testNotOfRequirementWithoutFilter: Expected not(&lt;any osgi.wiring.package capability&gt;) to find [acme.none], but
 *   findProviders failed with: java.lang.IllegalStateException: No filter directive: RequirementImpl [namespace=osgi.wiring.package, ...]
 * </pre>
 *
 * <h2>Expected</h2>
 * not(E) = all resources of the repository minus the resources matching E (here [acme.none]).
 *
 * <h2>Reference behaviour</h2>
 * bnd 7.0.0 ResourcesRepository with the same resources (plus acme.c without packages): not(exports org.acme.a) =
 * [acme.c, acme.none], not(any package) = [acme.c, acme.none] (BaseRepository.dispatch: all resources minus matches).
 *
 * <h2>Suggested fix</h2>
 * In findNotExpression, handle IdentityExpression like the other cases: return findInverse(re) (all osgi.identity
 * resources minus findProvidersImpl(re)); drop negateRequirement.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class NotExpressionSemanticsIssueTestCase {

    private static final String PACKAGE_NAMESPACE = "osgi.wiring.package";

    private Repository repo;
    private ExpressionCombiner ec;

    @Before
    public void setUp() throws Exception {
        AbstractPersistentRepository repository = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        RepositoryStorage storage = repository.adapt(RepositoryStorage.class);
        // acme.ab exports org.acme.a and org.acme.b; acme.none exports nothing
        storage.addResource(createResource("acme.ab", "org.acme.a", "org.acme.b"));
        storage.addResource(createResource("acme.none"));
        repo = repository;
        ec = repo.getExpressionCombiner();
    }

    @Test
    public void testNotOfPackageRequirement() throws Exception {
        IdentityExpression exportsA = repo.newRequirementBuilder(PACKAGE_NAMESPACE)
                .addDirective("filter", "(osgi.wiring.package=org.acme.a)").buildExpression();
        Set<String> names = names(repo.findProviders(ec.not(exportsA)).getValue());
        assertEquals("Expected not(exports org.acme.a) to find the resources that do not match the expression, [acme.none], but found "
                + names, Set.of("acme.none"), names);
    }

    @Test
    public void testNotAgreesWithAndNot() throws Exception {
        IdentityExpression exportsA = repo.newRequirementBuilder(PACKAGE_NAMESPACE)
                .addDirective("filter", "(osgi.wiring.package=org.acme.a)").buildExpression();
        IdentityExpression all = repo.newRequirementBuilder("osgi.identity").buildExpression();
        Set<String> notOnly = names(repo.findProviders(ec.not(exportsA)).getValue());
        Set<String> allAndNot = names(repo.findProviders(ec.and(all, ec.not(exportsA))).getValue());
        assertEquals("Expected not(X) and and(<all resources>, not(X)) to find the same resources, but not(X) found " + notOnly
                + " and and(all, not(X)) found", allAndNot, notOnly);
    }

    @Test
    public void testNotOfRequirementWithoutFilter() throws Exception {
        IdentityExpression anyPackage = repo.newRequirementBuilder(PACKAGE_NAMESPACE).buildExpression();
        Collection<Resource> result;
        try {
            result = repo.findProviders(ec.not(anyPackage)).getValue();
        } catch (RuntimeException ex) {
            fail("Expected not(<any osgi.wiring.package capability>) to find [acme.none], but findProviders failed with: " + ex);
            return;
        }
        assertEquals("Expected not(<any osgi.wiring.package capability>) to find [acme.none], but found " + names(result),
                Set.of("acme.none"), names(result));
    }

    private static Set<String> names(Collection<Resource> resources) {
        Set<String> names = new TreeSet<String>();
        for (Resource res : resources) {
            names.add(((XResource) res).getIdentityCapability().getName());
        }
        return names;
    }

    private static XResource createResource(String name, String... packages) {
        XResourceBuilder<XResource> builder = XResourceBuilderFactory.create();
        XIdentityCapability icap = builder.addIdentityCapability(name, Version.parseVersion("1.0"));
        icap.getAttributes().put("type", "abstract");
        for (String pkg : packages) {
            builder.addCapability(PACKAGE_NAMESPACE, Collections.<String, Object> singletonMap(PACKAGE_NAMESPACE, pkg),
                    Collections.<String, String> emptyMap());
        }
        return builder.getResource();
    }
}
