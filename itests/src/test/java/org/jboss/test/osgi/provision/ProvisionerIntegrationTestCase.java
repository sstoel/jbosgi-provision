/*
 * #%L
 * JBossOSGi Provision: Integration Tests
 * %%
 * Copyright (C) 2013 JBoss by Red Hat
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.jboss.test.osgi.provision;

import java.util.Collections;
import java.util.List;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.provision.ProvisionResult;
import org.jboss.osgi.provision.XResourceProvisioner;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.resolver.MavenCoordinates;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XRequirementBuilder;
import org.jboss.osgi.resolver.XResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.service.repository.Repository;

/**
 * Test simple provision service access
 *
 * @author thomas.diesler@jboss.com
 * @since 07-May-2013
 */
@RunWith(Arquillian.class)
public class ProvisionerIntegrationTestCase extends AbstractProvisionerIntegrationTest {

    @ArquillianResource
    BundleContext context;

    @Deployment
    public static JavaArchive createdeployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "provision-tests");
        archive.addClasses(AbstractProvisionerIntegrationTest.class);
        archive.addAsResource("repository/repository.xml");
        archive.setManifest(() -> {
            OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
            builder.addBundleSymbolicName(archive.getName());
            builder.addBundleManifestVersion(2);
            builder.addImportPackages(BundleContext.class, XResourceProvisioner.class);
            builder.addImportPackages(XRepository.class, Repository.class, XResource.class);
            return builder.openStream();
        });
        return archive;
    }

    @Test
    public void testAbstractFeature() throws Exception {
        XRequirementBuilder reqbuilder = XRequirementBuilder.create(IdentityNamespace.IDENTITY_NAMESPACE, "felix.eventadmin.feature");
        ProvisionResult result = findResources(Collections.singleton(reqbuilder.getRequirement()));
        Assert.assertEquals("One resource", 1, result.getResources().size());
        Assert.assertTrue("Nothing unsatisfied", result.getUnsatisfiedRequirements().isEmpty());

        List<Bundle> bundles = installResources(result.getResources());
        try {
            Assert.assertEquals("One bundle", 1, bundles.size());

            // Verify that we can now access the installed resource directly
            reqbuilder = XRequirementBuilder.create(IdentityNamespace.IDENTITY_NAMESPACE, "org.apache.felix.eventadmin");
            result = findResources(Collections.singleton(reqbuilder.getRequirement()));
            Assert.assertEquals("No resource", 0, result.getResources().size());
            Assert.assertTrue("Nothing unsatisfied", result.getUnsatisfiedRequirements().isEmpty());

            // Verify that we can require the feature again
            reqbuilder = XRequirementBuilder.create(IdentityNamespace.IDENTITY_NAMESPACE, "felix.eventadmin.feature");
            result = findResources(Collections.singleton(reqbuilder.getRequirement()));
            Assert.assertEquals("No resource", 0, result.getResources().size());
            Assert.assertTrue("Nothing unsatisfied", result.getUnsatisfiedRequirements().isEmpty());
        }
        finally {
            for (Bundle b : bundles) {
                try {
                    b.uninstall();
                }
                catch (Exception ignored) {
                }
            }
        }
    }

    @Test
    public void testMavenCoordinates() throws Exception {

        MavenCoordinates mvnid = MavenCoordinates.parse("jakarta.transaction:jakarta.transaction-api:2.0.1");
        XRequirement req = XRequirementBuilder.create(mvnid).getRequirement();
        Assert.assertNotNull("Requirement not null", req);

        XResourceProvisioner provisionService = getProvisionService();
        ProvisionResult result = provisionService.findResources(getEnvironment(), Collections.singleton(req));
        Assert.assertEquals("One resource", 1, result.getResources().size());
        result.getUnsatisfiedRequirements().forEach(System.out::println);
        Assert.assertTrue("Nothing unsatisfied", result.getUnsatisfiedRequirements().isEmpty());

        List<Bundle> bundles = installResources(result.getResources());
        Assert.assertEquals("One bundle", 1, bundles.size());
        Bundle bundle = bundles.get(0);
        try {
            Assert.assertEquals("jakarta.transaction-api", bundle.getSymbolicName());
            Assert.assertEquals(Version.parseVersion("2.0.1"), bundle.getVersion());
        } finally {
            bundle.uninstall();
        }
    }
}
