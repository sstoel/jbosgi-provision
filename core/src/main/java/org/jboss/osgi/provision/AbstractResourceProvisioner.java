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
package org.jboss.osgi.provision;

import static org.jboss.osgi.provision.ProvisionLogger.LOGGER;
import static org.jboss.osgi.provision.ProvisionMessages.MESSAGES;
import static org.osgi.framework.namespace.HostNamespace.HOST_NAMESPACE;
import static org.osgi.framework.namespace.PackageNamespace.RESOLUTION_DYNAMIC;
import static org.osgi.framework.namespace.AbstractWiringNamespace.RESOLUTION_OPTIONAL;
import static org.osgi.resource.Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.jboss.osgi.repository.XRepository;
import org.jboss.osgi.resolver.XCapability;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResolveContext;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResource.State;
import org.jboss.osgi.resolver.spi.AbstractEnvironment;
import org.osgi.framework.Version;
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
public class AbstractResourceProvisioner implements XResourceProvisioner {

    private final XResolver resolver;
    private final XRepository repository;

    public AbstractResourceProvisioner(XResolver resolver, XRepository repository) {
        this.resolver = resolver;
        this.repository = repository;
    }

    @Override
    public final XResolver getResolver() {
        return resolver;
    }

    @Override
    public final XRepository getRepository() {
        return repository;
    }

    @Override
    public final ProvisionResult findResources(XEnvironment env, Set<XRequirement> reqs) {
        if (env == null)
            throw MESSAGES.illegalArgumentNull("env");
        if (reqs == null)
            throw MESSAGES.illegalArgumentNull("reqs");

        LOGGER.debugf("START findResources: %s", reqs);

        // Install the unresolved resources into the cloned environment
        // An installed resource is not necessarily resolved, it must be resolved as well
        List<XResource> unresolved = new ArrayList<XResource>();
        List<XResource> uninstalled = new ArrayList<XResource>();
        for (XRequirement req : reqs) {
            XResource res = req.getResource();
            if (unresolved.contains(res) || res.getWiringSupport().getWiring(true) != null) {
                continue;
            }
            if (res.getState() != State.INSTALLED) {
                uninstalled.add(res);
                LOGGER.debugf("Installed and unresolved: %s", res);
            } else {
                LOGGER.debugf("Unresolved: %s", res);
            }
            unresolved.add(res);
        }
        XEnvironment envclone = cloneEnvironment(env, uninstalled);
        LOGGER.debugf("Cloned environment");

        // Find the resources in the cloned environment, the highest version of each provider
        List<XResource> resources = new ArrayList<XResource>();
        Set<XRequirement> unstatisfied = new HashSet<XRequirement>(reqs);
        Map<XRequirement, XResource> mapping = new HashMap<XRequirement, XResource>();
        findResources(envclone, unresolved, mapping, unstatisfied, resources, false);

        // The highest versions may not fit together (e.g. because of a uses constraint)
        // Then let the resolver choose among all versions in the repository, in a fresh clone of the environment
        if (!unstatisfied.isEmpty()) {
            LOGGER.debugf("Find resources with all versions");
            XEnvironment altenv = cloneEnvironment(env, uninstalled);
            List<XResource> altresources = new ArrayList<XResource>();
            Set<XRequirement> altunsatisfied = new HashSet<XRequirement>(reqs);
            Map<XRequirement, XResource> altmapping = new HashMap<XRequirement, XResource>();
            if (findResources(altenv, unresolved, altmapping, altunsatisfied, altresources, true)) {
                envclone = altenv;
                resources = altresources;
                unstatisfied = altunsatisfied;
                mapping = altmapping;
            }
        }

        // Remove abstract resources
        Iterator<XResource> itres = resources.iterator();
        while (itres.hasNext()) {
            XResource res = itres.next();
            if (res.isAbstract()) {
                itres.remove();
            }
        }

        AbstractProvisionResult result = new AbstractProvisionResult(mapping, unstatisfied, resources);
        LOGGER.debugf("END findResources");
        LOGGER.debugf("  resources: %s", result.getResources());
        LOGGER.debugf("  unsatisfied: %s", result.getUnsatisfiedRequirements());

        // Sanity check that we can resolve all result resources
        List<XResource> mandatory = new ArrayList<XResource>();
        mandatory.addAll(resources);
        try {
            XResolveContext context = resolver.createResolveContext(envclone, mandatory, null);
            resolver.resolve(context).entrySet();
        } catch (ResolutionException ex) {
            LOGGER.cannotResolveResultResources(ex);
        }

        return result;
    }

    // Returns true if the unresolved resources could be resolved
    // With allVersions every version of a provider in the repository is a candidate, the resolver chooses among them
    private boolean findResources(XEnvironment env, List<XResource> unresolved, Map<XRequirement, XResource> mapping, Set<XRequirement> unstatisfied,
            List<XResource> resources, boolean allVersions) {

        // Resolve the unsatisfied reqs in the environment
        boolean resolved = resolveInEnvironment(env, unresolved, mapping, unstatisfied, resources, allVersions);
        if (resolved || unstatisfied.isEmpty())
            return resolved;

        boolean envModified = false;
        Set<XResource> installable = new LinkedHashSet<XResource>();

        LOGGER.debugf("Finding unsatisfied reqs");

        Iterator<XRequirement> itun = unstatisfied.iterator();
        while (itun.hasNext()) {
            XRequirement req = itun.next();
            String reqnamespace = req.getNamespace();

            // Ignore requirements that are already in the environment
            if (!env.findProviders(req).isEmpty()) {
                continue;
            }

            // Continue if we cannot find a provider for a given requirement
            List<XCapability> caps = findProvidersInRepository(req);
            if (caps.isEmpty()) {
                continue;
            }

            // Convert a maven/module resource to it's associated target resource
            XCapability cap = caps.get(0);
            XIdentityCapability icap = cap.getResource().getIdentityCapability();
            String icaptype = (String) icap.getAttribute(XResource.CAPABILITY_TYPE_ATTRIBUTE);
            if (XResource.TYPE_ABSTRACT.equals(icaptype)) {
                if (getRequirementDelegate(icap, XResource.MAVEN_IDENTITY_NAMESPACE) != null) {
                    XRequirement mreq = getRequirementDelegate(icap, XResource.MAVEN_IDENTITY_NAMESPACE);
                    Iterator<Capability> capIt = repository.findProviders(mreq).iterator();
                    if (capIt.hasNext()) {
                        XCapability mcap = (XCapability) capIt.next();
                        icap = mcap.getResource().getIdentityCapability();
                    }
                } else if (getRequirementDelegate(icap, XResource.MODULE_IDENTITY_NAMESPACE) != null) {
                    XRequirement mreq = getRequirementDelegate(icap, XResource.MODULE_IDENTITY_NAMESPACE);
                    Iterator<Capability> capIt = repository.findProviders(mreq).iterator();
                    if (capIt.hasNext()) {
                        XCapability mcap = (XCapability) capIt.next();
                        icap = mcap.getResource().getIdentityCapability();
                    }
                }

                // Remove the abstract requirement
                itun.remove();

            } else if (XResource.MAVEN_IDENTITY_NAMESPACE.equals(reqnamespace)) {

                // Remove the maven requirement
                itun.remove();
            }

            installable.add(icap.getResource());

            // The other versions, abstract providers are removed when there are several providers
            if (allVersions) {
                for (XCapability aux : caps.subList(1, caps.size())) {
                    installable.add(aux.getResource());
                }
            }
        }

        // Install the resources that match the unsatisfied reqs
        for (XResource res : installable) {
            if (!resources.contains(res)) {
                Collection<XRequirement> reqs = getRequirements(res, null);
                Iterator<XRequirement> itreqs = reqs.iterator();
                while (itreqs.hasNext()) {
                    XRequirement req = itreqs.next();
                    boolean dynamic = RESOLUTION_DYNAMIC.equals(req.getDirective(REQUIREMENT_RESOLUTION_DIRECTIVE));
                    boolean optional = RESOLUTION_OPTIONAL.equals(req.getDirective(REQUIREMENT_RESOLUTION_DIRECTIVE));
                    if (dynamic || optional || env.findProviders(req).size() > 0) {
                        itreqs.remove();
                    }
                }
                LOGGER.debugf("Adding %d unsatisfied reqs", reqs.size());
                unstatisfied.addAll(reqs);
                env.installResources(res);
                resources.add(res);
                envModified = true;
            }
        }

        // Recursivly find the missing resources
        if (envModified) {
            return findResources(env, unresolved, mapping, unstatisfied, resources, allVersions);
        }
        return false;
    }

    private Collection<XRequirement> getRequirements(XResource res, String[] namespaces) {
        Set<XRequirement> reqs = new HashSet<XRequirement>();
        if (namespaces != null) {
            for (String ns : namespaces) {
                for (Requirement req : res.getRequirements(ns)) {
                    reqs.add((XRequirement) req);
                }
            }
        } else {
            for (Requirement req : res.getRequirements(null)) {
                reqs.add((XRequirement) req);
            }
        }
        return reqs;
    }

    private XRequirement getRequirementDelegate(XIdentityCapability icap, String namespace) {
        List<Requirement> mreqs = icap.getResource().getRequirements(namespace);
        return (XRequirement) (mreqs.size() == 1 ? mreqs.get(0) : null);
    }

    // The providers in the repository, the highest version first
    private List<XCapability> findProvidersInRepository(XRequirement req) {

        // Find the providers in the repository
        LOGGER.debugf("Find in repository: %s", req);
        Collection<Capability> providers = repository.findProviders(req);

        // Remove abstract resources
        if (providers.size() > 1) {
            providers = new ArrayList<Capability>(providers);
            Iterator<Capability> itcap = providers.iterator();
            while (itcap.hasNext()) {
                XCapability cap = (XCapability) itcap.next();
                if (cap.getResource().isAbstract()) {
                    itcap.remove();
                }
            }
        }

        List<XCapability> sorted = new ArrayList<XCapability>();
        for (Capability cap : providers) {
            sorted.add((XCapability) cap);
        }
        if (sorted.size() == 1) {
            LOGGER.debugf(" Found one: %s", sorted.get(0));
        } else if (sorted.size() > 1) {
            Collections.sort(sorted, new Comparator<Capability>() {
                @Override
                public int compare(Capability cap1, Capability cap2) {
                    XIdentityCapability icap1 = ((XResource) cap1.getResource()).getIdentityCapability();
                    XIdentityCapability icap2 = ((XResource) cap2.getResource()).getIdentityCapability();
                    Version v1 = icap1.getVersion();
                    Version v2 = icap2.getVersion();
                    return v2.compareTo(v1);
                }
            });
            LOGGER.debugf(" Found multiple: %s", sorted);
        } else {
            LOGGER.debugf(" Not found: %s", req);
        }
        return sorted;
    }

    private boolean resolveInEnvironment(XEnvironment env, List<XResource> unresolved, Map<XRequirement, XResource> mapping, Set<XRequirement> unstatisfied,
            List<XResource> resources, boolean allVersions) {
        List<XResource> mandatory = new ArrayList<XResource>();
        mandatory.addAll(unresolved);
        // Of all versions only those that the resolver chooses are needed
        if (!allVersions) {
            mandatory.addAll(resources);
        }
        try {
            XResolveContext context = resolver.createResolveContext(env, mandatory, null);
            Map<Resource, List<Wire>> wiremap = resolver.resolve(context);
            for (Entry<Resource, List<Wire>> entry : wiremap.entrySet()) {
                Iterator<XRequirement> itunsat = unstatisfied.iterator();
                while (itunsat.hasNext()) {
                    XRequirement req = itunsat.next();
                    for (Wire wire : entry.getValue()) {
                        if (wire.getRequirement() == req) {
                            XResource provider = (XResource) wire.getProvider();
                            mapping.put(req, provider);
                        }
                    }
                }
            }
            unstatisfied.clear();
            if (allVersions) {
                resources.retainAll(getWiredResources(unresolved, wiremap));
            }
            return true;
        } catch (ResolutionException ex) {
            for (Requirement req : ex.getUnresolvedRequirements()) {
                LOGGER.debugf(" unresolved: %s", req);
            }
            return false;
        }
    }

    // The resources that the given resources are wired to, directly or indirectly, and the fragments attached to them
    private Set<Resource> getWiredResources(List<XResource> requirers, Map<Resource, List<Wire>> wiremap) {
        Set<Resource> result = new HashSet<Resource>();
        List<Resource> pending = new ArrayList<Resource>(requirers);
        while (!pending.isEmpty()) {
            List<Wire> wires = wiremap.get(pending.remove(pending.size() - 1));
            if (wires != null) {
                for (Wire wire : wires) {
                    if (result.add(wire.getProvider())) {
                        pending.add(wire.getProvider());
                    }
                }
            }
        }
        for (Entry<Resource, List<Wire>> entry : wiremap.entrySet()) {
            for (Wire wire : entry.getValue()) {
                if (HOST_NAMESPACE.equals(wire.getRequirement().getNamespace()) && result.contains(wire.getProvider())) {
                    result.add(entry.getKey());
                }
            }
        }
        return result;
    }

    private XEnvironment cloneEnvironment(XEnvironment env, List<XResource> uninstalled) {
        XEnvironment clone;
        if (env instanceof AbstractEnvironment) {
            clone = ((AbstractEnvironment) env).clone();
        } else {
            clone = new AbstractEnvironment();
            Iterator<XResource> itres = env.getResources(null);
            while (itres.hasNext()) {
                clone.installResources(itres.next());
            }
        }
        for (XResource res : uninstalled) {
            clone.installResources(res);
        }
        return clone;
    }

    static class AbstractProvisionResult implements ProvisionResult {

        private final Map<XRequirement, XResource> mapping;
        private final Set<XRequirement> unsatisfied;
        private final List<XResource> resources;

        public AbstractProvisionResult(Map<XRequirement, XResource> mapping, Set<XRequirement> unstatisfied, List<XResource> resources) {
            this.mapping = mapping;
            this.unsatisfied = unstatisfied;
            this.resources = resources;
        }

        @Override
        public Map<XRequirement, XResource> getRequirementMapping() {
            return Collections.unmodifiableMap(mapping);
        }

        @Override
        public List<XResource> getResources() {
            return Collections.unmodifiableList(resources);
        }

        @Override
        public Set<XRequirement> getUnsatisfiedRequirements() {
            return Collections.unmodifiableSet(unsatisfied);
        }
    }
}
