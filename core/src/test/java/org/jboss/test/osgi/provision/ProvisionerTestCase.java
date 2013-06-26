/*
 * #%L
 * JBossOSGi Provision: Core
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
import java.util.Iterator;

import junit.framework.Assert;

import org.jboss.osgi.provision.ProvisionResult;
import org.jboss.osgi.provision.XResourceProvisioner;
import org.jboss.osgi.repository.RepositoryStorage;
import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.resolver.MavenCoordinates;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XRequirementBuilder;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceBuilder;
import org.jboss.osgi.resolver.XResourceBuilderFactory;
import org.junit.Test;
import org.osgi.framework.namespace.IdentityNamespace;


/**
 * Test the {@link XResourceProvisioner}.
 *
 * @author thomas.diesler@jboss.com
 * @since 06-May-2013
 */
public class ProvisionerTestCase extends AbstractProvisionerTest {

    @Test
    public void testEmptyEnvironment() {
        XEnvironment env = getEnvironment();
        Iterator<XResource> itres = env.getResources(null);
        Assert.assertFalse("Empty environment", itres.hasNext());
    }

    @Test
    public void testEmptyRepository() {
        XResourceProvisioner provision = getProvisioner();
        XRepository repository = provision.getRepository();
        RepositoryStorage storage = repository.adapt(RepositoryStorage.class);
        XResource resource = storage.getRepositoryReader().nextResource();
        Assert.assertNull("Empty repository", resource);
    }

    @Test
    public void testCapabilityInEnvironment() {
        XResourceBuilder<XResource> cbuilder = XResourceBuilderFactory.create();
        cbuilder.addCapability(IdentityNamespace.IDENTITY_NAMESPACE, "res1");
        XResource res = cbuilder.getResource();
        getEnvironment().installResources(res);

        XRequirementBuilder rbuilder = XRequirementBuilder.create(IdentityNamespace.IDENTITY_NAMESPACE, "res1");
        XRequirement req = rbuilder.getRequirement();

        ProvisionResult result = findResources(Collections.singleton(req));
        Assert.assertEquals(res, result.getRequirementMapping().get(req));
        Assert.assertTrue("Empty resources", result.getResources().isEmpty());
        Assert.assertTrue("Nothing unsatisfied", result.getUnsatisfiedRequirements().isEmpty());
    }

    @Test
    public void testCapabilityInRepository() {
        XResourceBuilder<XResource> cbuilder = XResourceBuilderFactory.create();
        cbuilder.addCapability(IdentityNamespace.IDENTITY_NAMESPACE, "res1");
        XResource res = cbuilder.getResource();
        XRepository repository = getProvisioner().getRepository();
        RepositoryStorage storage = repository.adapt(RepositoryStorage.class);
        storage.addResource(res);

        XRequirementBuilder rbuilder = XRequirementBuilder.create(IdentityNamespace.IDENTITY_NAMESPACE, "res1");
        XRequirement req = rbuilder.getRequirement();

        ProvisionResult result = findResources(Collections.singleton(req));
        Assert.assertEquals(res, result.getRequirementMapping().get(req));
        Assert.assertEquals("One resource", 1, result.getResources().size());
        Assert.assertEquals(res, result.getResources().iterator().next());
        Assert.assertTrue("Nothing unsatisfied", result.getUnsatisfiedRequirements().isEmpty());
    }


    @Test
    public void testCascadingRequirement() {
        XResourceBuilder<XResource> cbuilder = XResourceBuilderFactory.create();
        cbuilder.addCapability(IdentityNamespace.IDENTITY_NAMESPACE, "res1");
        cbuilder.addRequirement(IdentityNamespace.IDENTITY_NAMESPACE, "res2");
        XResource res1 = cbuilder.getResource();

        cbuilder = XResourceBuilderFactory.create();
        cbuilder.addCapability(IdentityNamespace.IDENTITY_NAMESPACE, "res2");
        XResource res2 = cbuilder.getResource();

        XRepository repository = getProvisioner().getRepository();
        RepositoryStorage storage = repository.adapt(RepositoryStorage.class);
        storage.addResource(res1);
        storage.addResource(res2);

        XRequirementBuilder rbuilder = XRequirementBuilder.create(IdentityNamespace.IDENTITY_NAMESPACE, "res1");
        XRequirement req = rbuilder.getRequirement();

        ProvisionResult result = findResources(Collections.singleton(req));
        Assert.assertEquals("Two resources", 2, result.getResources().size());
        Assert.assertTrue("Nothing unsatisfied", result.getUnsatisfiedRequirements().isEmpty());
        Assert.assertEquals(res1, result.getRequirementMapping().get(req));
    }

    @Test
    public void testPreferHigherVersion() {
        XResourceBuilder<XResource> cbuilder = XResourceBuilderFactory.create();
        cbuilder.addCapability(IdentityNamespace.IDENTITY_NAMESPACE, "res1").getAttributes().put("version", "1.0.0");
        XResource res1 = cbuilder.getResource();

        cbuilder = XResourceBuilderFactory.create();
        cbuilder.addCapability(IdentityNamespace.IDENTITY_NAMESPACE, "res1").getAttributes().put("version", "2.0.0");
        XResource res2 = cbuilder.getResource();

        XRepository repository = getProvisioner().getRepository();
        RepositoryStorage storage = repository.adapt(RepositoryStorage.class);
        storage.addResource(res1);
        storage.addResource(res2);

        XRequirementBuilder rbuilder = XRequirementBuilder.create(IdentityNamespace.IDENTITY_NAMESPACE, "res1");
        XRequirement req = rbuilder.getRequirement();

        ProvisionResult result = findResources(Collections.singleton(req));
        Assert.assertEquals(res2, result.getRequirementMapping().get(req));
        Assert.assertEquals("One resources", 1, result.getResources().size());
        Assert.assertEquals(res2, result.getResources().iterator().next());
        Assert.assertTrue("Nothing unsatisfied", result.getUnsatisfiedRequirements().isEmpty());
    }

    @Test
    public void testMavenCoordinates() throws Exception {

        MavenCoordinates mavenid = MavenCoordinates.parse("org.jboss.spec.javax.transaction:jboss-transaction-api_1.1_spec:1.0.1.Final");
        XRequirement req = XRequirementBuilder.create(mavenid).getRequirement();

        XResourceProvisioner provisionService = getProvisioner();
        ProvisionResult result = provisionService.findResources(getEnvironment(), Collections.singleton(req));
        Assert.assertEquals("One resource", 1, result.getResources().size());
        Assert.assertTrue("Nothing unsatisfied", result.getUnsatisfiedRequirements().isEmpty());
    }
}
