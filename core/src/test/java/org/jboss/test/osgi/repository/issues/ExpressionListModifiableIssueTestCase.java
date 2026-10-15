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

import java.util.List;

import org.jboss.osgi.repository.spi.AbstractPersistentRepository;
import org.jboss.osgi.repository.spi.MemoryRepositoryStorage;
import org.junit.Test;
import org.osgi.service.repository.ExpressionCombiner;
import org.osgi.service.repository.Repository;
import org.osgi.service.repository.RequirementExpression;

/**
 * AndExpression/OrExpression.getRequirementExpressions() return a modifiable ArrayList although the API requires an
 * unmodifiable list.
 *
 * <h2>Severity</h2>
 * cosmetic (API contract deviation; a fresh list is returned per call, so modifying it has no effect on the expression).
 *
 * <h2>Component</h2>
 * jbosgi-repository, module core, class org.jboss.osgi.repository.internal.ExpressionCombinerImpl.
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, Repository 1.1 API javadoc review; bnd 7.0.0 as reference.
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8 (https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.repository.html) 132.9.2.1 AndExpression.getRequirementExpressions() and 132.9.7.1
 * OrExpression.getRequirementExpressions(): "Returns An unmodifiable list of requirement expressions that are combined by
 * this AndExpression/OrExpression."
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/repository/internal/ExpressionCombinerImpl.java:47-54 and :101-108
 * (new ArrayList, returned as is). Pre-existing.
 *
 * <h2>Root cause</h2>
 * Missing Collections.unmodifiableList.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-repository
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=ExpressionListModifiableIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testAndExpressionListIsUnmodifiable: Expected AndExpression.getRequirementExpressions() to return an unmodifiable list
 *   (132.9.2.1 / 132.9.7.1), but add() succeeded on java.util.ArrayList
 * testOrExpressionListIsUnmodifiable: Expected OrExpression.getRequirementExpressions() to return an unmodifiable list
 *   (132.9.2.1 / 132.9.7.1), but add() succeeded on java.util.ArrayList
 * </pre>
 *
 * <h2>Expected</h2>
 * add() throws UnsupportedOperationException.
 *
 * <h2>Reference behaviour</h2>
 * bnd 7.0.0 ExpressionCombiner: Collections.unmodifiableList (add() throws).
 *
 * <h2>Suggested fix</h2>
 * Build the list once in and()/or() and return Collections.unmodifiableList(list).
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class ExpressionListModifiableIssueTestCase {

    @Test
    public void testAndExpressionListIsUnmodifiable() throws Exception {
        Repository repo = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        ExpressionCombiner ec = repo.getExpressionCombiner();
        RequirementExpression a = repo.newRequirementBuilder("osgi.identity").addDirective("filter", "(osgi.identity=a)").buildExpression();
        RequirementExpression b = repo.newRequirementBuilder("osgi.identity").addDirective("filter", "(osgi.identity=b)").buildExpression();
        assertUnmodifiable("AndExpression", ec.and(a, b).getRequirementExpressions(), a);
    }

    @Test
    public void testOrExpressionListIsUnmodifiable() throws Exception {
        Repository repo = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        ExpressionCombiner ec = repo.getExpressionCombiner();
        RequirementExpression a = repo.newRequirementBuilder("osgi.identity").addDirective("filter", "(osgi.identity=a)").buildExpression();
        RequirementExpression b = repo.newRequirementBuilder("osgi.identity").addDirective("filter", "(osgi.identity=b)").buildExpression();
        assertUnmodifiable("OrExpression", ec.or(a, b).getRequirementExpressions(), a);
    }

    private static void assertUnmodifiable(String type, List<RequirementExpression> list, RequirementExpression any) {
        try {
            list.add(any);
        } catch (UnsupportedOperationException ex) {
            return;
        }
        fail("Expected " + type + ".getRequirementExpressions() to return an unmodifiable list (132.9.2.1 / 132.9.7.1), "
                + "but add() succeeded on " + list.getClass().getName());
    }
}
