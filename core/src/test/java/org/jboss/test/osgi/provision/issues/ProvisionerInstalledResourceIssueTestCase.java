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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jboss.osgi.provision.AbstractResourceProvisioner;
import org.jboss.osgi.provision.ProvisionResult;
import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.spi.AbstractPersistentRepository;
import org.jboss.osgi.repository.spi.MemoryRepositoryStorage;
import org.jboss.osgi.resolver.XEnvironment;
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

/**
 * XResourceProvisioner.findResources ignores requirements whose resource is already installed in the environment: for
 * an installed but unresolved bundle it returns no resources and no unsatisfied requirements (false success), even when
 * the repository has the missing provider or when nothing can provide it.
 *
 * <h2>Severity</h2>
 * major. The provisioner's main use case - "find what an installed bundle that does not resolve is missing" - silently
 * reports success without doing anything. In WildFly the provisioner is registered as an OSGi service
 * (SystemServicesIntegration.registerProvisionerServices) over the framework's XEnvironment, in which every installed
 * bundle revision has state INSTALLED; ProvisionerSupport.installCapabilities trusts getUnsatisfiedRequirements().
 *
 * <h2>Component</h2>
 * jbosgi-provision, module core, class org.jboss.osgi.provision.AbstractResourceProvisioner (findResources,
 * resolveInEnvironment).
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, provisioning correctness tests (noticed while reusing resource objects across environments).
 *
 * <h2>Spec reference</h2>
 * Not an OSGi spec clause (jbosgi provisioning API, no javadoc). ProvisionResult.getUnsatisfiedRequirements() is the only
 * failure signal of findResources; reporting an empty set for requirements nobody can satisfy contradicts its purpose.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/provision/AbstractResourceProvisioner.java (WF41-osgi-r8 3505b85), pre-existing (only
 * a debug message changed on the branch, c878cde):
 * <pre>
 * for (XRequirement req : reqs) {
 *     XResource res = req.getResource();
 *     if (res.getState() != State.INSTALLED) {      // line 98: an installed resource is not added to 'unresolved'
 *         envclone.installResources(res);
 *         unresolved.add(res);
 *     }
 * }
 * ...
 * mandatory.addAll(unresolved); mandatory.addAll(resources);   // resolveInEnvironment: empty list
 * resolver.resolve(context) ...                                // trivially succeeds
 * unstatisfied.clear();                                        // line 308: every requirement counted as satisfied
 * </pre>
 *
 * <h2>Root cause</h2>
 * "Installed" (XResource.State.INSTALLED = present in an environment) is treated as "resolved". The requiring resource is
 * then not part of the mandatory set, the empty resolve succeeds and all requirements are cleared.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-provision
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=ProvisionerInstalledResourceIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testProvisionForInstalledUnresolvedResource:59 Expected the provisioner to provide acme.p for the installed but
 *   unresolved acme.root, but it returned [] with unsatisfied [] expected:&lt;1&gt; but was:&lt;0&gt;
 * testUnsatisfiableRequirementOfInstalledResourceIsReported:73 Expected the requirement on org.acme.missing (no provider
 *   anywhere) to be reported unsatisfied, but the provisioner reports unsatisfied [] and resources []
 * </pre>
 *
 * <h2>Expected</h2>
 * For an installed but unresolved resource the provisioner returns the repository resources that make it resolvable
 * (acme.p), and reports requirements that nothing can satisfy as unsatisfied.
 *
 * <h2>Reference behaviour</h2>
 * n/a (jbosgi-specific API).
 *
 * <h2>Suggested fix</h2>
 * Add every requiring resource that is not resolved (wiring == null / not in env's wiring map) to 'unresolved', whether or
 * not it is installed (install it in the clone only when it is not installed yet); only clear the requirements that were
 * actually wired in the resolve result.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same logic on WF41).
 */
public class ProvisionerInstalledResourceIssueTestCase {

    private static final String PKG = "osgi.wiring.package";

    @Test
    public void testProvisionForInstalledUnresolvedResource() throws Exception {
        AbstractPersistentRepository repository = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        repository.adapt(RepositoryStorage.class).addResource(exporter("acme.p", "org.acme.p"));

        // acme.root is installed in the environment but cannot resolve: it imports org.acme.p
        XEnvironment env = new AbstractEnvironment();
        XResource root = importer("acme.root", "org.acme.p");
        env.installResources(root);

        ProvisionResult result = new AbstractResourceProvisioner(new AbstractResolver(), repository).findResources(env, requirements(root));
        assertEquals("Expected the provisioner to provide acme.p for the installed but unresolved acme.root, but it returned "
                + result.getResources() + " with unsatisfied " + result.getUnsatisfiedRequirements(), 1, result.getResources().size());
    }

    @Test
    public void testUnsatisfiableRequirementOfInstalledResourceIsReported() throws Exception {
        AbstractPersistentRepository repository = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());

        XEnvironment env = new AbstractEnvironment();
        XResource root = importer("acme.root", "org.acme.missing");
        env.installResources(root);

        Set<XRequirement> reqs = requirements(root);
        ProvisionResult result = new AbstractResourceProvisioner(new AbstractResolver(), repository).findResources(env, reqs);
        assertTrue("Expected the requirement on org.acme.missing (no provider anywhere) to be reported unsatisfied, but the provisioner "
                + "reports unsatisfied " + result.getUnsatisfiedRequirements() + " and resources " + result.getResources(),
                result.getUnsatisfiedRequirements().containsAll(reqs));
    }

    private static Set<XRequirement> requirements(XResource res) {
        Set<XRequirement> reqs = new HashSet<XRequirement>();
        for (Requirement req : res.getRequirements(null)) {
            reqs.add((XRequirement) req);
        }
        return reqs;
    }

    private static XResource exporter(String bsn, String pkg) {
        XResourceBuilder<XResource> builder = identity(bsn);
        Map<String, Object> atts = new HashMap<String, Object>();
        atts.put(PKG, pkg);
        atts.put("version", Version.parseVersion("1.0"));
        builder.addCapability(PKG, atts, Collections.<String, String> emptyMap());
        return builder.getResource();
    }

    private static XResource importer(String bsn, String pkg) {
        XResourceBuilder<XResource> builder = identity(bsn);
        builder.addRequirement(PKG, new HashMap<String, Object>(), Collections.singletonMap("filter", "(" + PKG + "=" + pkg + ")"));
        return builder.getResource();
    }

    private static XResourceBuilder<XResource> identity(String bsn) {
        XResourceBuilder<XResource> builder = XResourceBuilderFactory.create();
        XIdentityCapability icap = builder.addIdentityCapability(bsn, Version.parseVersion("1.0"));
        icap.getAttributes().put("type", "osgi.bundle");
        return builder;
    }
}
