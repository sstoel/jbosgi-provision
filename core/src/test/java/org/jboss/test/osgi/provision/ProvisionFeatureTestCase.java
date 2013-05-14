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
import junit.framework.Assert;

import org.jboss.osgi.provision.ProvisionResult;
import org.jboss.osgi.provision.XResourceProvisioner;
import org.jboss.osgi.resolver.XRequirementBuilder;
import org.junit.Test;
import org.osgi.framework.namespace.IdentityNamespace;


/**
 * Test the {@link XResourceProvisioner}.
 *
 * @author thomas.diesler@jboss.com
 * @since 06-May-2013
 */
public class ProvisionFeatureTestCase extends AbstractProvisionerTest {

    @Test
    public void testAbstractFeature() throws Exception {

        setupFrameworkEnvironment();
        setupRepository("xml/eventadmin-feature.xml");
        
        XRequirementBuilder reqbuilder = XRequirementBuilder.create(IdentityNamespace.IDENTITY_NAMESPACE, "felix.eventadmin.feature");
        ProvisionResult result = findResources(Collections.singleton(reqbuilder.getRequirement()));
        Assert.assertEquals("One resource", 1, result.getResources().size());
        Assert.assertTrue("Nothing unsatisfied", result.getUnsatisfiedRequirements().isEmpty());
        
        installResources(result);

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
}
