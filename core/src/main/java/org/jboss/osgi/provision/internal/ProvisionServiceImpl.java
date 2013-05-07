/*
 * #%L
 * JBossOSGi Provision Core
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
package org.jboss.osgi.provision.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.jboss.osgi.provision.ProvisionResult;
import org.jboss.osgi.provision.ProvisionService;
import org.jboss.osgi.repository.XPersistentRepository;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResolveContext;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XIdentityRequirement;
import org.jboss.osgi.resolver.spi.AbstractEnvironment;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.resolver.ResolutionException;

/**
 * The Provision Service
 *
 * @author thomas.diesler@jboss.com
 * @since 06-May-2013
 */
public class ProvisionServiceImpl implements ProvisionService {

    private final XResolver resolver;
    private final XPersistentRepository repository;

    public ProvisionServiceImpl(XResolver resolver, XPersistentRepository repository) {
        this.resolver = resolver;
        this.repository = repository;
    }

    @Override
    public XResolver getResolver() {
        return resolver;
    }

    @Override
    public XPersistentRepository getRepository() {
        return repository;
    }

    @Override
    public ProvisionResult findResources(XEnvironment env, Set<XRequirement> reqs) {
        Set<XRequirement> unstatisfied = new HashSet<XRequirement>(reqs);
        AbstractProvisionResult result = new AbstractProvisionResult();
        findResources(cloneEnvironment(env), result, unstatisfied);
        result.addUnsatisfiedRequirements(unstatisfied);
        return result;
    }

    private void findResources(XEnvironment env, AbstractProvisionResult result, Set<XRequirement> unstatisfied) {

        // Resolve the unsatisfied reqs in the environment
        resolveInEnvironment(env, result, unstatisfied);
        if (unstatisfied.isEmpty())
            return;

        // Find installable resources that match the unsatisfied reqs
        Set<XResource> installable = new HashSet<XResource>();
        for (XRequirement req : unstatisfied) {
            Collection<Capability> providers = repository.findProviders(req);
            if (providers.size() == 1) {
                XIdentityCapability icap = (XIdentityCapability) providers.iterator().next();
                installable.add(icap.getResource());
            } else if (providers.size() > 1) {
                List<XIdentityCapability> sorted = new ArrayList<XIdentityCapability>();
                for (Capability cap : providers) {
                    sorted.add((XIdentityCapability) cap);
                }
                Collections.sort(sorted, new Comparator<XIdentityCapability>() {
                    @Override
                    public int compare(XIdentityCapability cap1, XIdentityCapability cap2) {
                        Version v1 = cap1.getVersion();
                        Version v2 = cap2.getVersion();
                        return v2.compareTo(v1);
                    }
                });
                XIdentityCapability icap = sorted.get(0);
                installable.add(icap.getResource());
            }
        }

        // Install the resources that match the unsatisfied reqs
        Set<XResource> installed = result.getResources();
        for (XResource res : installable) {
            if (!installed.contains(res)) {
                unstatisfied.addAll(getIdentityRequirements(res));
                env.installResources(res);
                result.addResource(res);
            }
        }

        // Recursivly find the missing resources
        findResources(env, result, unstatisfied);
    }

    private Collection<? extends XIdentityRequirement> getIdentityRequirements(XResource res) {
        Set<XIdentityRequirement> reqs = new HashSet<XIdentityRequirement>();
        for (Requirement req : res.getRequirements(IdentityNamespace.IDENTITY_NAMESPACE)) {
            reqs.add((XIdentityRequirement) req);
        }
        return reqs;
    }

    private void resolveInEnvironment(XEnvironment env, AbstractProvisionResult result, Set<XRequirement> unstatisfied) {
        Collection<XResource> unresolved = new HashSet<XResource>();
        for (XRequirement req : unstatisfied) {
            unresolved.add(req.getResource());
        }
        try {
            XResolveContext context = resolver.createResolveContext(env, null, unresolved);
            for (Entry<Resource, List<Wire>> entry : resolver.resolve(context).entrySet()) {
                Iterator<XRequirement> itunsat = unstatisfied.iterator();
                while (itunsat.hasNext()) {
                    XRequirement req = itunsat.next();
                    for (Wire wire : entry.getValue()) {
                        if (wire.getRequirement() == req) {
                            XResource provider = (XResource) wire.getProvider();
                            result.addRequirementMapping(req, provider);
                            itunsat.remove();
                        }
                    }
                }
            }
        } catch (ResolutionException ex) {
            // ignore
        }
    }

    private XEnvironment cloneEnvironment(XEnvironment env) {
        if (env instanceof AbstractEnvironment) {
            return ((AbstractEnvironment) env).clone();
        } else {
            AbstractEnvironment clone = new AbstractEnvironment();
            Iterator<XResource> itres = env.getResources(null);
            while (itres.hasNext()) {
                clone.installResources(itres.next());
            }
            return clone;
        }
    }

    @Override
    public void installResources(XEnvironment env, Set<XResource> resources) {
        if (resources.size() == 1) {
            env.installResources(resources.iterator().next());
        } else {
            XResource[] resarr = new XResource[resources.size()];
            resources.toArray(resarr);
            env.installResources(resarr);
        }
    }

    static class AbstractProvisionResult implements ProvisionResult {

        private final Map<XRequirement, XResource> mapping = new HashMap<XRequirement, XResource>();
        private final Set<XResource> resources = new HashSet<XResource>();
        private final Set<XRequirement> unsatisfied = new HashSet<XRequirement>();

        @Override
        public Map<XRequirement, XResource> getRequirementMapping() {
            return Collections.unmodifiableMap(mapping);
        }

        @Override
        public Set<XResource> getResources() {
            return Collections.unmodifiableSet(resources);
        }

        @Override
        public Set<XRequirement> getUnsatisfiedRequirements() {
            return Collections.unmodifiableSet(unsatisfied);
        }

        void addRequirementMapping(XRequirement req, XResource res) {
            mapping.put(req, res);
        }

        void addResource(XResource res) {
            resources.add(res);
        }

        void addUnsatisfiedRequirements(Set<XRequirement> reqs) {
            unsatisfied.addAll(reqs);
        }
    }
}
