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
import java.util.Set;
import java.util.TreeSet;

import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.spi.AbstractPersistentRepository;
import org.jboss.osgi.repository.spi.MemoryRepositoryStorage;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.jboss.osgi.resolver.XResourceBuilderFactory;
import org.junit.Test;
import org.osgi.framework.Version;
import org.osgi.resource.Resource;
import org.osgi.service.repository.ExpressionCombiner;
import org.osgi.service.repository.Repository;
import org.osgi.service.repository.RequirementExpression;

/**
 * findProviders(RequirementExpression) throws NullPointerException for an and() whose operands are all not() expressions,
 * e.g. and(not(A), not(B)).
 *
 * <h2>Severity</h2>
 * minor. A valid Repository 1.1 expression crashes the call (synchronously, not as a failed Promise).
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.spi.AbstractRepository (findAndExpression).
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, expression combinators vs the Repository 1.1 spec; bnd 7.0.0 as reference.
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8 (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.repository.html): 132.9.4.1/132.9.4.2 and(...) "Combine ... RequirementExpressions into a requirement
 * expression using the and operator."; 132.9.8.3 findProviders(RequirementExpression) "Returns A promise to a collection of
 * matching Resources. If there are no matching resources, an empty collection is returned."
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/spi/AbstractRepository.java:147-173 (WF41-osgi-r8 1eab73d), pre-existing:
 * NotExpressions are collected and skipped, so l (initialized to null at :152) is still null when :170
 * l.removeAll(...) runs.
 *
 * <h2>Root cause</h2>
 * The positive set is only initialized from a non-not operand; with no such operand the starting set should be all
 * resources.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=AndOfNotExpressionsIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testAndOfTwoNotExpressions: Expected and(not(acme.a), not(acme.b)) to find [acme.c], but findProviders failed with:
 *   java.lang.NullPointerException: Cannot invoke "java.util.List.removeAll(java.util.Collection)" because "l" is null
 * </pre>
 *
 * <h2>Expected</h2>
 * [acme.c] (all resources minus those matching any negated operand).
 *
 * <h2>Reference behaviour</h2>
 * bnd 7.0.0 ResourcesRepository: and(not(acme.ab), not(acme.none)) over {acme.ab, acme.none, acme.c} = [acme.c].
 *
 * <h2>Suggested fix</h2>
 * If l is still null after the positive operands, start from all resources (findSimpleRequirement of an osgi.identity
 * requirement without filter, as findInverse does) before subtracting the not() operands.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class AndOfNotExpressionsIssueTestCase {

    @Test
    public void testAndOfTwoNotExpressions() throws Exception {
        AbstractPersistentRepository repository = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        RepositoryStorage storage = repository.adapt(RepositoryStorage.class);
        storage.addResource(createResource("acme.a"));
        storage.addResource(createResource("acme.b"));
        storage.addResource(createResource("acme.c"));

        Repository repo = repository;
        ExpressionCombiner ec = repo.getExpressionCombiner();
        RequirementExpression notA = ec.not(repo.newRequirementBuilder("osgi.identity").addDirective("filter", "(osgi.identity=acme.a)").buildExpression());
        RequirementExpression notB = ec.not(repo.newRequirementBuilder("osgi.identity").addDirective("filter", "(osgi.identity=acme.b)").buildExpression());

        Collection<Resource> result;
        try {
            result = repo.findProviders(ec.and(notA, notB)).getValue();
        } catch (NullPointerException ex) {
            fail("Expected and(not(acme.a), not(acme.b)) to find [acme.c], but findProviders failed with: " + ex);
            return;
        }
        Set<String> names = new TreeSet<String>();
        for (Resource res : result) {
            names.add(((XResource) res).getIdentityCapability().getName());
        }
        assertEquals("Expected and(not(acme.a), not(acme.b)) to find exactly acme.c, but found " + names, Set.of("acme.c"), names);
    }

    private static XResource createResource(String name) {
        XResourceBuilder<XResource> builder = XResourceBuilderFactory.create();
        XIdentityCapability icap = builder.addIdentityCapability(name, Version.parseVersion("1.0"));
        icap.getAttributes().put("type", "abstract");
        return builder.getResource();
    }
}
