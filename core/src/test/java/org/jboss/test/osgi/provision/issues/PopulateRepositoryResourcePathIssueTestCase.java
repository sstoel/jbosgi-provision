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

import java.io.File;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.jboss.osgi.provision.AbstractResourceProvisioner;
import org.jboss.osgi.provision.ProvisionerSupport;
import org.jboss.osgi.provision.XResourceProvisioner;
import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.spi.AbstractPersistentRepository;
import org.jboss.osgi.repository.spi.MemoryRepositoryStorage;
import org.jboss.osgi.resolver.XRequirementBuilder;
import org.jboss.osgi.resolver.spi.AbstractResolver;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * ProvisionerSupport.populateRepository looks features up with the resource name "/repository/&lt;feature&gt;.xml" (leading
 * '/'), which a standard ClassLoader never finds, and silently ignores a feature it cannot find.
 *
 * <h2>Severity</h2>
 * minor. Public API of jbosgi-provision core: with a standard class loader nothing is added and no error is reported
 * (the only callers, the umbrella example tests, pass bundle class loaders of jboss-modules; whether those accept the
 * leading '/' was not verified here), so the following installCapabilities fails later with "unsatisfied requirements" far from the cause.
 *
 * <h2>Component</h2>
 * jbosgi-provision, module core, class org.jboss.osgi.provision.ProvisionerSupport (populateRepository,
 * getFeatureResource).
 *
 * <h2>Found by</h2>
 * subrepos-b workstream, provisioning API review.
 *
 * <h2>Spec reference</h2>
 * Not an OSGi spec clause. java.lang.ClassLoader.getResource(String) javadoc: "The name of a resource is a '/'-separated
 * path name that identifies the resource." Names are relative to the class path roots (no leading '/'; the leading '/'
 * convention belongs to Class.getResource, which strips it).
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/provision/ProvisionerSupport.java:166 (WF41-osgi-r8 3505b85):
 * return classLoader.getResourceAsStream("/repository/" + feature + ".xml"); and :146 if (input != null) { ... }
 * with no else. The stream and reader are never closed either. Pre-existing.
 *
 * <h2>Root cause</h2>
 * Class.getResource naming used with ClassLoader.getResourceAsStream; missing feature not reported.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-provision
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl core test -Dtest=PopulateRepositoryResourcePathIssueTestCase
 * </pre>
 *
 * <h2>Observed</h2>
 * <pre>
 * testFeatureFromStandardClassLoader:75 Expected populateRepository(classLoader, "acme.feature") to add the feature
 *   resource from repository/acme.feature.xml of the given class loader, but nothing was added (the lookup uses the name
 *   "/repository/acme.feature.xml" with a leading '/') expected:&lt;1&gt; but was:&lt;0&gt;
 * </pre>
 *
 * <h2>Expected</h2>
 * The feature repository/acme.feature.xml of the given class loader is read and its resources are added; an unknown
 * feature raises an exception naming it.
 *
 * <h2>Reference behaviour</h2>
 * n/a (jbosgi-specific API).
 *
 * <h2>Suggested fix</h2>
 * Use "repository/" + feature + ".xml" (no leading '/'), throw MESSAGES... (IllegalArgumentException/IOException) when
 * the stream is null, and close reader and stream in finally.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing, same code on WF41).
 */
public class PopulateRepositoryResourcePathIssueTestCase {

    private static final String FEATURE_XML = "<?xml version='1.0' encoding='UTF-8'?>"
            + "<repository xmlns='http://www.osgi.org/xmlns/repository/v1.0.0' name='features'>"
            + "<resource><capability namespace='osgi.identity'>"
            + "<attribute name='osgi.identity' value='acme.feature'/><attribute name='type' value='abstract'/>"
            + "</capability></resource></repository>";

    @Test
    public void testFeatureFromStandardClassLoader() throws Exception {
        File root = Files.createTempDirectory("features").toFile();
        File xml = new File(root, "repository/acme.feature.xml");
        xml.getParentFile().mkdirs();
        Files.write(xml.toPath(), FEATURE_XML.getBytes(StandardCharsets.UTF_8));

        final AbstractPersistentRepository repository = new AbstractPersistentRepository(new MemoryRepositoryStorage.Factory());
        final XResourceProvisioner provisioner = new AbstractResourceProvisioner(new AbstractResolver(), repository);
        final ServiceReference<?> sref = (ServiceReference<?>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { ServiceReference.class }, (proxy, method, args) -> null);
        BundleContext syscontext = (BundleContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { BundleContext.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getServiceReference"))
                        return args[0] == XResourceProvisioner.class ? sref : null;
                    if (method.getName().equals("getService"))
                        return args[0] == sref ? provisioner : null;
                    return null;
                });

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] { root.toURI().toURL() }, null)) {
            // Precondition: the feature is a resource of this class loader
            assertEquals(true, classLoader.getResource("repository/acme.feature.xml") != null);

            new ProvisionerSupport(syscontext).populateRepository(classLoader, "acme.feature");
        }

        RepositoryStorage storage = repository.adapt(RepositoryStorage.class);
        assertEquals("Expected populateRepository(classLoader, \"acme.feature\") to add the feature resource from repository/acme.feature.xml "
                + "of the given class loader, but nothing was added (the lookup uses the name \"/repository/acme.feature.xml\" with a leading '/')",
                1, storage.findProviders(XRequirementBuilder.create("osgi.identity", "acme.feature").getRequirement()).size());
    }
}
