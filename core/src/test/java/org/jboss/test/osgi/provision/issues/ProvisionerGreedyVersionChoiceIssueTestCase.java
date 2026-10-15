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
package org.jboss.test.osgi.provision.issues;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jboss.osgi.provision.AbstractResourceProvisioner;
import org.jboss.osgi.provision.ProvisionResult;
import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.spi.AbstractPersistentRepository;
import org.jboss.osgi.repository.spi.MemoryRepositoryStorage;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.jboss.osgi.resolver.XResourceBuilderFactory;
import org.jboss.osgi.resolver.spi.AbstractEnvironment;
import org.jboss.osgi.resolver.spi.AbstractResolver;
import org.junit.Test;
import org.osgi.framework.Version;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;

/**
 * XResourceProvisioner.findResources picks the highest version per requirement independently and never reconsiders: when
 * that choice violates a uses constraint it reports the requirements unsatisfied although a consistent set exists in the
 * repository (and it returns two versions of the same bundle in the attempt).
 *
 * <h2>Severity</h2>
 * minor. Only repositories that hold several versions of a bundle are affected (in WildFly Maven/module lookups usually
 * add exact versions); then provisioning (ProvisionerSupport.installCapabilities) fails with "unsatisfied requirements"
 * for a solvable request.
 *
 * <h2>Component</h2>
 * jbosgi-provision, module core, class org.jboss.osgi.provision.AbstractResourceProvisioner (findProviderInRepository,
 * findResources).
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, provisioning correctness (version choice, uses constraints); the jbosgi resolver itself is used as
 * reference.
 *
 * <h2>Spec reference</h2>
 * Not an OSGi spec clause (jbosgi provisioning API). Uses constraints: OSGi Core R8 3.7.6 Package Constraints;
 * a provisioner that feeds the resolver one candidate per requirement cannot honour them.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/provision/AbstractResourceProvisioner.java:252-286 findProviderInRepository sorts
 * the providers by identity version descending (:278 return v2.compareTo(v1)) and returns only the first; findResources
 * :152-226 installs that single choice and recurses, without backtracking. Pre-existing.
 *
 * <h2>Root cause</h2>
 * Greedy per-requirement selection; the resolver is only used to check, not to choose among the repository candidates.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-provision
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=ProvisionerGreedyVersionChoiceIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testSolvableUsesConstraint:63 Expected the provisioner to satisfy root's requirements with the consistent set
 *   [acme.p 1.0, acme.q 1.0], but it picked [acme.p 2.0.0, acme.q 1.0.0, acme.q 2.0.0] and reports unsatisfied
 *   [XPackageRequirement[dirs={filter=(osgi.wiring.package=org.acme.p)},[acme.root:1.0.0]],
 *    XPackageRequirement[dirs={filter=(&amp;(osgi.wiring.package=org.acme.q)(version&gt;=1.0)(!(version&gt;=2.0)))},[acme.root:1.0.0]],
 *    XPackageRequirement[dirs={filter=(&amp;(osgi.wiring.package=org.acme.q)(version&gt;=2.0)(!(version&gt;=3.0)))},[acme.p:2.0.0]]]
 * </pre>
 * Scenario: acme.root imports org.acme.p (any) and org.acme.q [1,2); acme.p 1.0/2.0 export org.acme.p uses:=org.acme.q and
 * import org.acme.q [1,2) / [2,3); acme.q 1.0/2.0 export org.acme.q. The test's precondition shows that the same
 * AbstractResolver, given all four as optional candidates, resolves acme.root with acme.p 1.0 + acme.q 1.0.
 *
 * <h2>Expected</h2>
 * No unsatisfied requirements; the result is the consistent set [acme.p 1.0, acme.q 1.0].
 *
 * <h2>Reference behaviour</h2>
 * jbosgi's own AbstractResolver (Felix resolver) with the repository resources as optional resources finds the solution
 * (precondition of the test).
 *
 * <h2>Suggested fix</h2>
 * Let the resolver choose: build a ResolveContext whose findProviders also returns repository capabilities (repository
 * resources as optional resources, preferring installed ones), resolve once, and return the repository resources that
 * appear in the wiring; report the ResolutionException's unresolved requirements as unsatisfied.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same logic on WF41).
 */
public class ProvisionerGreedyVersionChoiceIssueTestCase {

    private static final String PKG = "osgi.wiring.package";

    @Test
    public void testSolvableUsesConstraint() throws Exception {
        // Precondition: the resolver the provisioner uses finds the consistent solution when it sees all candidates
        List<XResource> reference = usesScenario();
        Map<Resource, List<Wire>> wiring = resolve(reference.get(0), reference.subList(1, reference.size()));
        assertTrue("Precondition: acme.p 1.0 + acme.q 1.0 is a consistent solution " + wiring.keySet(),
                wiring.containsKey(reference.get(1)) && wiring.containsKey(reference.get(3)));

        // Fresh resource instances for the provisioner (installing them above changed their state)
        List<XResource> scenario = usesScenario();
        ProvisionResult result = provision(scenario.get(0), scenario.subList(1, scenario.size()));
        assertTrue("Expected the provisioner to satisfy root's requirements with the consistent set [acme.p 1.0, acme.q 1.0], but it "
                + "picked " + names(result.getResources()) + " and reports unsatisfied " + result.getUnsatisfiedRequirements(),
                result.getUnsatisfiedRequirements().isEmpty());
    }

    /** [root, acme.p 1.0, acme.p 2.0, acme.q 1.0, acme.q 2.0] */
    private static List<XResource> usesScenario() {
        // acme.p 1.0 exports org.acme.p (uses org.acme.q) and imports org.acme.q [1,2)
        // acme.p 2.0 exports org.acme.p (uses org.acme.q) and imports org.acme.q [2,3)
        XResource p1 = bundle("acme.p", "1.0", new String[] { "org.acme.p;1.0;org.acme.q" }, range("org.acme.q", "1.0", "2.0"));
        XResource p2 = bundle("acme.p", "2.0", new String[] { "org.acme.p;2.0;org.acme.q" }, range("org.acme.q", "2.0", "3.0"));
        XResource q1 = bundle("acme.q", "1.0", new String[] { "org.acme.q;1.0" });
        XResource q2 = bundle("acme.q", "2.0", new String[] { "org.acme.q;2.0" });
        // root imports org.acme.p (any version) and org.acme.q [1,2): only acme.p 1.0 + acme.q 1.0 is consistent
        XResource root = bundle("acme.root", "1.0", new String[0], "(osgi.wiring.package=org.acme.p)", range("org.acme.q", "1.0", "2.0"));
        return Arrays.asList(root, p1, p2, q1, q2);
    }

    private static ProvisionResult provision(XResource root, List<XResource> repoResources) {
        AbstractPersistentRepository repository = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        RepositoryStorage storage = repository.adapt(RepositoryStorage.class);
        for (XResource res : repoResources) {
            storage.addResource(res);
        }
        AbstractResourceProvisioner provisioner = new AbstractResourceProvisioner(new AbstractResolver(), repository);
        Set<XRequirement> reqs = new HashSet<XRequirement>();
        for (Requirement req : root.getRequirements(null)) {
            reqs.add((XRequirement) req);
        }
        return provisioner.findResources(new AbstractEnvironment(), reqs);
    }

    private static Map<Resource, List<Wire>> resolve(XResource root, List<XResource> candidates) throws Exception {
        AbstractEnvironment env = new AbstractEnvironment();
        for (XResource res : candidates) {
            env.installResources(res);
        }
        env.installResources(root);
        AbstractResolver resolver = new AbstractResolver();
        return resolver.resolve(resolver.createResolveContext(env, Collections.<Resource> singletonList(root), new ArrayList<Resource>(candidates)));
    }

    private static Set<String> names(List<XResource> resources) {
        Set<String> names = new TreeSet<String>();
        for (XResource res : resources) {
            XIdentityCapability icap = res.getIdentityCapability();
            names.add(icap.getName() + " " + icap.getVersion());
        }
        return names;
    }

    private static String range(String pkg, String floor, String ceiling) {
        return "(&(osgi.wiring.package=" + pkg + ")(version>=" + floor + ")(!(version>=" + ceiling + ")))";
    }

    private static XResource bundle(String bsn, String version, String[] exports, String... importFilters) {
        XResourceBuilder<XResource> builder = XResourceBuilderFactory.create();
        XIdentityCapability icap = builder.addIdentityCapability(bsn, Version.parseVersion(version));
        icap.getAttributes().put("type", "osgi.bundle");
        for (String export : exports) {
            String[] parts = export.split(";");
            Map<String, Object> atts = new HashMap<String, Object>();
            atts.put(PKG, parts[0]);
            atts.put("version", Version.parseVersion(parts[1]));
            atts.put("bundle-symbolic-name", bsn);
            atts.put("bundle-version", Version.parseVersion(version));
            Map<String, String> dirs = new HashMap<String, String>();
            if (parts.length > 2) {
                dirs.put("uses", parts[2]);
            }
            builder.addCapability(PKG, atts, dirs);
        }
        for (String filter : importFilters) {
            builder.addRequirement(PKG, new HashMap<String, Object>(), Collections.singletonMap("filter", filter));
        }
        return builder.getResource();
    }
}
