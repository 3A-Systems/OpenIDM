/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2012-2016 ForgeRock AS.
 * Portions Copyrighted 2024 3A Systems LLC.
 */
package org.forgerock.openidm.info.impl;

import org.forgerock.api.models.ApiDescription;
import org.forgerock.http.ApiProducer;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.Request;
import org.forgerock.json.resource.RequestHandler;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.Router;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openidm.cluster.ClusterEvent;
import org.forgerock.openidm.cluster.ClusterEventListener;
import org.forgerock.openidm.cluster.ClusterManagementService;
import org.forgerock.openidm.core.IdentityServer;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.info.HealthInfo;
import org.forgerock.openidm.info.health.DatabaseInfoResourceProvider;
import org.forgerock.openidm.info.health.MemoryInfoResourceProvider;
import org.forgerock.openidm.info.health.OsInfoResourceProvider;
import org.forgerock.openidm.info.health.ReconInfoResourceProvider;
import org.forgerock.openidm.osgi.ServiceTrackerListener;
import org.forgerock.openidm.osgi.ServiceTrackerNotifier;
import org.forgerock.services.context.Context;
import org.forgerock.services.descriptor.Describable;
import org.forgerock.util.promise.Promise;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.propertytypes.ServiceDescription;
import org.osgi.service.component.propertytypes.ServiceVendor;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.forgerock.json.resource.Router.uriTemplate;

/**
 * A health service determining system state.
 */
@Component(
        name = HealthService.PID,
        configurationPolicy = ConfigurationPolicy.IGNORE,
        immediate = true,
        property = {
                Constants.SERVICE_PID + "=" + HealthService.PID,
                ServerConstants.ROUTER_PREFIX + "=/health/*"
        },
        service = { HealthInfo.class, RequestHandler.class })
@ServiceVendor(ServerConstants.SERVER_VENDOR_NAME)
@ServiceDescription("OpenIDM Health Service")
public class HealthService
        implements HealthInfo, ClusterEventListener, Describable<ApiDescription, Request>,
                ServiceTrackerListener<ClusterManagementService, ClusterManagementService>, RequestHandler {

    public static final String PID = "org.forgerock.openidm.health";

    /**
     * Setup logging for the {@link HealthService}.
     */
    private static final Logger logger = LoggerFactory.getLogger(HealthService.class);

    private static final String LISTENER_ID = "healthService";

    /**
     * Application states
     */
    enum AppState {
        STARTING, ACTIVE_READY, ACTIVE_NOT_READY, STOPPING
    }

    static ServiceTracker<?, ?> tracker;

    private ComponentContext context;
    private FrameworkListener frameworkListener;
    private ServiceListener svcListener;
    private BundleListener bundleListener;

    /**
     * The Cluster Management Service
     */
    private ClusterManagementService cluster = null;

    /**
     * An {@link ScheduledExecutorService} used to schedule a task to check the state of OpenIDM
     */
    private ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

    /** 
     * A boolean indicating if we consider the underlying framework as started
     */
    private static volatile boolean frameworkStarted = false;

    /** 
     * Flag to help in processing state during start-up. For clients to querying application state, 
     * use the state detail instead
     */
    private volatile boolean appStarting = true;
    
    /**
     * A boolean indicating if the cluster management thread is up in the "running" state
     */
    private volatile boolean clusterUp = false;
    
    /**
     * A boolean indicating if the cluster management service is enabled
     */
    private volatile boolean clusterEnabled = true;
    
    /**
     * A framework status instance used to store the latest framework event status.
     */
    private FrameworkStatus frameworkStatus = null;

    /**
     * The current state of OpenIDM
     */
    private volatile StateDetail stateDetail = new StateDetail(AppState.STARTING, "OpenIDM starting");

    /**
     * Bundles and bundle fragments required to be started or resolved respectively for the system to 
     * consider itself READY. Required bundles may be expressed as a regex, for example:
     * 
     * "org.openidentityplatform.openidm.repo-(orientdb|jdbc)"
     */
    private List<String> requiredBundles = new ArrayList<String>();

    /**
     * An array default bundles and bundle fragments required to be started or resolved respectively 
     * for the system to consider itself READY. 
     */
    private final String[] defaultRequiredBundles = new String[] {
        //ICF Bundles
        "org.openidentityplatform.openicf.framework.connector-framework",
        "org.openidentityplatform.openicf.framework.connector-framework-internal",
        "org.openidentityplatform.openicf.framework.connector-framework-protobuf",
        "org.openidentityplatform.openicf.framework.connector-framework-rpc",
        "org.openidentityplatform.openicf.framework.connector-framework-server",
        "org.openidentityplatform.openicf.framework.icfl-over-slf4j",

        // ForgeRock Commons Bundles
        "org.openidentityplatform.commons.audit.core",
        "org.openidentityplatform.commons.util",
        "org.openidentityplatform.commons.authn-filter.jaspi-runtime",
        "org.openidentityplatform.commons.authn-filter.jaspi-modules.*-module",
        //"org.openidentityplatform.commons.guava.forgerock-guava-.*",
        
        "org.openidentityplatform.commons.i18n-framework.core",
        "org.openidentityplatform.commons.i18n-framework.slf4j",
        "org.openidentityplatform.commons.json-crypto.core",
        "org.openidentityplatform.commons.json-resource",
        "org.openidentityplatform.commons.json-resource-http",
        "org.openidentityplatform.commons.json-schema.core",
        "org.openidentityplatform.commons.json-web-token",
        "org.openidentityplatform.commons.script.common",
        "org.openidentityplatform.commons.script.javascript",
        "org.openidentityplatform.commons.script.groovy",
        "org.openidentityplatform.commons.http-framework.core",
        "org.openidentityplatform.commons.http-framework.servlet",
        
        // OpenIDM Bundles
        "org.openidentityplatform.openidm.api-servlet",
        "org.openidentityplatform.openidm.audit",
        "org.openidentityplatform.openidm.authnfilter",
        "org.openidentityplatform.openidm.cluster",
        "org.openidentityplatform.openidm.config",
        "org.openidentityplatform.openidm.core",
        "org.openidentityplatform.openidm.crypto",
        "org.openidentityplatform.openidm.customendpoint",
        "org.openidentityplatform.openidm.enhanced-config",
        "org.openidentityplatform.openidm.external-email",
        "org.openidentityplatform.openidm.external-rest",
        "org.openidentityplatform.openidm.httpcontext",
        "org.openidentityplatform.openidm.infoservice",
        "org.openidentityplatform.openidm.jetty-fragment",
        //"org.openidentityplatform.openidm.maintenance",
        "org.openidentityplatform.openidm.policy",
        "org.openidentityplatform.openidm.provisioner",
        "org.openidentityplatform.openidm.provisioner-openicf",
        "org.openidentityplatform.openidm.quartz-fragment",
        "org.openidentityplatform.openidm.repo",
        "org.openidentityplatform.openidm.repo-(orientdb|jdbc)",
        "org.openidentityplatform.openidm.router",
        "org.openidentityplatform.openidm.scheduler",
        "org.openidentityplatform.openidm.security",
        "org.openidentityplatform.openidm.security-jetty",
        "org.openidentityplatform.openidm.servlet",
        "org.openidentityplatform.openidm.servlet-registrator",
        "org.openidentityplatform.openidm.smartevent",
        "org.openidentityplatform.openidm.script",
        "org.openidentityplatform.openidm.system",
        "org.openidentityplatform.openidm.util",
        "org.openidentityplatform.openidm.keystore",
        
        // 3rd Party Bundles
        "org.ops4j.pax.web.pax-web-jetty-bundle"
    };

    /**
     * Maximum time after framework start for required services to register to consider the system 
     * startup as successful
     */
    private long serviceStartMax = 15000;
    
    /**
     * Services required to be registered for the system to consider itself READY. Required services 
     * may be expressed as a regex, for example:
     * 
     * "org.openidentityplatform.openidm.bootrepo.(orientdb|jdbc)"
     */
    private List<String> requiredServices = new ArrayList<String>();

    /**
     * An array default services required to be registered for the system to consider itself READY. 
     * Required services may be expressed as a regex, for example:
     * 
     * "org.openidentityplatform.openidm.bootrepo.(orientdb|jdbc)"
     */
    private final String[] defaultRequiredServices = new String[] {
            "org.openidentityplatform.openidm.api-servlet",
            "org.openidentityplatform.openidm.audit",
            "org.openidentityplatform.openidm.authentication",
            "org.openidentityplatform.openidm.bootrepo.(orientdb|jdbc)",
            "org.openidentityplatform.openidm.cluster",
            "org.openidentityplatform.openidm.config.enhanced",
            "org.openidentityplatform.openidm.config.manage",
            "org.openidentityplatform.openidm.crypto",
            "org.openidentityplatform.openidm.external.rest",
//            "org.openidentityplatform.openidm.maintenance",
            "org.openidentityplatform.openidm.managed",
            "org.openidentityplatform.openidm.policy",
            "org.openidentityplatform.openidm.provisioner",
            "org.openidentityplatform.openidm.provisioner.openicf.connectorinfoprovider",
            "org.openidentityplatform.openidm.repo.(orientdb|jdbc)",
            "org.openidentityplatform.openidm.router.internal",
            "org.openidentityplatform.openidm.router.servlet",
            "org.openidentityplatform.openidm.scheduler",
            "org.openidentityplatform.openidm.script",
            "org.openidentityplatform.openidm.security",
            "org.openidentityplatform.openidm.servletfilter.registrator"
    };

    /**
     * A router used to service requests for system health endpoints such as: os, memory, recon, jdbc.
     */
    private final Router router = new Router();
    
    @Activate
    protected void activate(final ComponentContext context) {
        this.context = context;
        requiredBundles = new ArrayList<String>();
        requiredBundles.addAll(Arrays.asList(defaultRequiredBundles));
        requiredServices = new ArrayList<String>();
        requiredServices.addAll(Arrays.asList(defaultRequiredServices));
        applyPropertyConfig();

        // Get the framework status service instance
        frameworkStatus = FrameworkStatus.getInstance();
        
        // Set up tracker
        BundleContext ctx = FrameworkUtil.getBundle(HealthService.class).getBundleContext();
        tracker = initServiceTracker(ctx);

        // Handle framework changes
        frameworkListener = new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                final int eventType = event.getType();
                logger.debug("Handle framework event {} {}", eventType, event.toString());
                
                // Store the framework event type as the framework status
                frameworkStatus.setFrameworkStatus(eventType);

                if (eventType == FrameworkEvent.STARTED) {
                    logger.debug("OSGi framework started event.");
                    frameworkStarted = true;
                }
                // Start checking status once framework reported started
                if (frameworkStarted) {
                    switch (eventType) {
                    case FrameworkEvent.PACKAGES_REFRESHED: // fall through
                    case FrameworkEvent.STARTLEVEL_CHANGED: // fall through
                    case FrameworkEvent.WARNING: // fall trough
                    case FrameworkEvent.INFO:
                        // For now do not re-check state for these
                        break;
                    default:
                        checkState();
                    }
                }
                if (eventType == FrameworkEvent.STARTED) {
                    // IF it's not yet ready, give it up to max service startup
                    // time before reporting failure
                    if (!stateDetail.state.equals(AppState.ACTIVE_READY)) {
                        scheduleCheckStartup(serviceStartMax);
                    }
                }
            }
        };

        // Handle service changes
        svcListener = new ServiceListener() {
            @Override
            public void serviceChanged(ServiceEvent event) {
                logger.debug("Handle service event {} {}", event.getType(), event.toString());
                if (frameworkStarted) {
                    switch (event.getType()) {
                    case ServiceEvent.REGISTERED: // fall through
                    case ServiceEvent.UNREGISTERING: // fall through
                    case ServiceEvent.MODIFIED:
                        checkState();
                        break;
                    }
                }
            }
        };

        // Handle bundle changes
        bundleListener = new BundleListener() {
            @Override
            public void bundleChanged(BundleEvent event) {
                logger.debug("Handle bundle event {} {}", event.getType(), event.toString());
                if (frameworkStarted) {
                    switch (event.getType()) {
                    case BundleEvent.STARTED: // fall through
                    case BundleEvent.STOPPED: // fall through
                    case BundleEvent.UNRESOLVED:
                        checkState();
                        break;
                    case BundleEvent.RESOLVED:
                        if (isFragment(event.getBundle())) {
                            checkState();
                        }
                        break;
                    }
                }
            }
        };

        context.getBundleContext().addServiceListener(svcListener);
        context.getBundleContext().addBundleListener(bundleListener);
        context.getBundleContext().addFrameworkListener(frameworkListener);

        router.addRoute(uriTemplate("os"), new OsInfoResourceProvider());
        router.addRoute(uriTemplate("memory"), new MemoryInfoResourceProvider());
        router.addRoute(uriTemplate("recon"), new ReconInfoResourceProvider());
        router.addRoute(uriTemplate("jdbc"), new DatabaseInfoResourceProvider());

        // Check if the framework has already started.  If so, schedule the start up
        // thread that checks the state of OpenIDM.
        if (frameworkStatus.isReady()) {
            scheduleCheckStartup(2000);
        }
        
        logger.info("OpenIDM Health Service component is activated.");
    }

    /**
     * Apply configuration overrides from properties if present
     */
    private void applyPropertyConfig() {
        // Override default requirements
        String reqBundlesProp =
                IdentityServer.getInstance().getProperty("openidm.healthservice.reqbundles");
        if (reqBundlesProp != null) {
            requiredBundles = parseProp(reqBundlesProp);
        }
        String reqServicesProp =
                IdentityServer.getInstance().getProperty("openidm.healthservice.reqservices");
        if (reqServicesProp != null) {
            requiredServices = parseProp(reqServicesProp);
        }
        // Optionally add to requirements
        String additionalReqBundlesProp =
                IdentityServer.getInstance().getProperty(
                        "openidm.healthservice.additionalreqbundles");
        if (additionalReqBundlesProp != null) {
            requiredBundles.addAll(parseProp(additionalReqBundlesProp));
        }
        String additionalReqServicesProp =
                IdentityServer.getInstance().getProperty(
                        "openidm.healthservice.additionalreqservices");
        if (additionalReqServicesProp != null) {
            requiredServices.addAll(parseProp(additionalReqServicesProp));
        }

        String serviceStartMaxProp =
                IdentityServer.getInstance().getProperty("openidm.healthservice.servicestartmax");
        if (serviceStartMaxProp != null) {
            serviceStartMax = Long.parseLong(serviceStartMaxProp);
        }
    }

    /**
     * After the timeout period passes past framework start event, check that
     * the required services are present. If not, report startup error
     * 
     * @param delay a delay in milliseconds before checking the state.
     */
    private void scheduleCheckStartup(long delay) {
        Runnable command = new Runnable() {
            @Override
            public void run() {
                appStarting = false; // From now on, report not ready rather
                                     // than starting if something fails
                checkState();
                if (!stateDetail.state.equals(AppState.ACTIVE_READY)) {
                    logger.error("OpenIDM failure during startup, {}: {}", stateDetail.state,
                            stateDetail.shortDesc);
                } else {
                    logger.debug("Startup check found ready state");
                }
            }
        };
        if (scheduledExecutor.isShutdown()) {
            scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        }
        scheduledExecutor.schedule(command, delay, TimeUnit.MILLISECONDS);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.forgerock.openidm.info.HealthInfo#getHealthInfo()
     */
    @Override
    public JsonValue getHealthInfo() {
        return stateDetail.toJsonValue();
    }

    /**
     * Initialize the service tracker and open it.
     *
     * @param context
     *            the BundleContext
     * @return the ServiceTracker
     */
    private ServiceTracker<?, ?> initServiceTracker(BundleContext context) {
        ServiceTracker<?, ?> tracker =
                new ServiceTrackerNotifier<>(context, ClusterManagementService.class.getName(), null, this);
        tracker.open();
        return tracker;
    }

    @Override
    public void addedService(ServiceReference<ClusterManagementService> reference, ClusterManagementService service) {
        if (service != null) {
            service.register(LISTENER_ID, this);
            clusterEnabled = service.isEnabled();
            cluster = service;
            if (clusterEnabled && !cluster.isStarted()) {
                cluster.startClusterManagement();
            }
        }
    }

    @Override
    public void removedService(ServiceReference<ClusterManagementService> reference, ClusterManagementService service) {
        if (cluster != null) {
            cluster.unregister(LISTENER_ID);
            cluster = null;
            clusterUp = false;
        }
    }

    @Override
    public void modifiedService(ServiceReference<ClusterManagementService> reference, ClusterManagementService service) {
        if (cluster != null) {
            cluster.unregister(LISTENER_ID);
            cluster = null;
        }
        if (service != null) {
            service.register(LISTENER_ID, this);
            cluster = service;
        }
    }

    /**
     * Check and update the application state
     */
    private void checkState() {
        // Check if the required bundles are started or bundle fragments
        // resolved
        Bundle[] bundles = context.getBundleContext().getBundles();
        List<String> missingBundles = new ArrayList<String>(requiredBundles);
        List<String> bundleFailures = new ArrayList<String>();
        List<String> fragmentFailures = new ArrayList<String>();
        for (String req : requiredBundles) {
            for (Bundle bundle : bundles) {
                String symbolicName = bundle.getSymbolicName();
                if (symbolicName != null && symbolicName.matches(req)) {
                    if (isFragment(bundle)) {
                        if (bundle.getState() != Bundle.RESOLVED) {
                            fragmentFailures.add(bundle.getSymbolicName());
                        }
                    } else {
                        if (bundle.getState() != Bundle.ACTIVE) {
                            bundleFailures.add(bundle.getSymbolicName());
                        }
                    }
                    missingBundles.remove(req);
                }
            }
        }

        // Get currently registered services
        ServiceReference<?>[] refs = null;
        try {
            refs = context.getBundleContext().getAllServiceReferences(null, null);
        } catch (Exception e) {
            // Bundles and context may be in flux during shutdown
            logger.debug("Unexpected failure in getting service references", e);
        }

        // Scan the registered services for matches to our list of
        // required services.  Required services can be expressed as a regex,
        // for example: "org.openidentityplatform.openidm.bootrepo.(orientdb|jdbc)"
        List<String> missingServices = new ArrayList<String>(requiredServices);
        if (refs != null && refs.length > 0) {
            for (String req : requiredServices) {
                for (ServiceReference<?> ref : refs) {
                    final String pid;
                    if(ref.getProperty(Constants.SERVICE_PID) instanceof List) {
                        pid = ((List<String>) ref.getProperty(Constants.SERVICE_PID)).get(0);
                    } else {
                        pid = (String) ref.getProperty(Constants.SERVICE_PID);
                    }
                    if (pid != null && (pid.matches(req)||pid.replace("org.forgerock.","org.openidentityplatform.").matches(req))) {
                        missingServices.remove(req);
                        break;
                    }
                }
            }
        }

        // Ensure state is up to date
        AppState updatedAppState = null;
        String updatedShortDesc = null;
        if (missingBundles.size() > 0 || bundleFailures.size() > 0 || fragmentFailures.size() > 0) {
            updatedAppState = AppState.ACTIVE_NOT_READY;
            updatedShortDesc = "Not all modules started " + missingBundles + " " + bundleFailures + " " + fragmentFailures;
        } else if (missingServices.size() > 0) {
            updatedAppState = AppState.ACTIVE_NOT_READY;
            updatedShortDesc = "Required services not all started " + missingServices;
        } else if (clusterEnabled && !clusterUp) {
            updatedAppState = AppState.ACTIVE_NOT_READY;
            updatedShortDesc = "This node can not yet join the cluster";
        } else {
            updatedAppState = AppState.ACTIVE_READY;
            updatedShortDesc = "OpenIDM ready";
        }
        setState(updatedAppState, updatedShortDesc);
    }

    /**
     * Process detected state, if it's different than the current state process
     * the state change and report appropriately
     *
     * @param state
     *            new app state
     * @param shortDesc
     *            new short description of state
     */
    private void setState(AppState state, String shortDesc) {
        synchronized (this) {
            if (!stateDetail.isSameState(state, shortDesc)) {
                // Whilst we're still not past start-up timeout or successful
                // start, keep it at starting rather than error
                if (appStarting) {
                    if (state == AppState.ACTIVE_READY) {
                        appStarting = false; // From now on, report not ready
                                             // rather than starting if
                                             // something fails
                    } else {
                        return;
                    }
                }

                StateDetail updatedState = new StateDetail(state, shortDesc);

                // If we're changing from ready to another state, report
                if (stateDetail.isState(AppState.ACTIVE_READY)
                        && !updatedState.isState(AppState.ACTIVE_READY)) {
                    if (updatedState.state == AppState.ACTIVE_NOT_READY) {
                        // Whilst we do not have a mechanism to detect regular
                        // system shut down and distinguish, we log as info
                        logger.info("System changed to a not ready state {}: {}", updatedState
                                .getState(), updatedState.getShortDesc());
                    } else {
                        logger.info("System changed state {}: {}", updatedState.getState(),
                                updatedState.getShortDesc());
                    }
                }

                // IF we're changing to a ready state, report ready
                if (!stateDetail.isState(AppState.ACTIVE_READY)
                        && updatedState.isState(AppState.ACTIVE_READY)) {
                    logger.info("OpenIDM ready");
                    // Show ready on the system console
                    System.out.println("OpenIDM ready");
                }

                stateDetail = updatedState;
            }
        }
    }
 
    /**
     * @param bundle
     *            the bundle / bundle fragment to check
     * @return true if a bundle is a bundle fragment, false if it's a full
     *         bundle
     */
    private boolean isFragment(Bundle bundle) {
        return (bundle.getHeaders().get(Constants.FRAGMENT_HOST) != null);
    }

    /**
     * Parse the comma delimited property into a list
     *
     * @param prop
     *            comma delimited values
     * @return properties split by comma
     */
    private List<String> parseProp(String prop) {
        String[] items = new String[0];
        if (prop != null) {
            items = prop.split(",\\s*");
        }
        return Arrays.asList(items);
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }
        if (frameworkListener != null) {
            context.getBundleContext().removeFrameworkListener(frameworkListener);
        }
        if (svcListener != null) {
            context.getBundleContext().removeServiceListener(svcListener);
        }
        if (bundleListener != null) {
            context.getBundleContext().removeBundleListener(bundleListener);
        }
        if (tracker != null) {
            tracker.close();
            tracker = null;
        }

        // For now we have to rely on this bundle stopping as an indicator
        // that the system may be shutting down
        // Ideally replace with enhanced detection on regular shutdown initiated
        frameworkStarted = false;
        setState(AppState.STOPPING, "OpenIDM stopping");
        logger.info("OpenIDM Health Service component is deactivated.");
    }

    /**
     * Detailed State
     *
     */
    private static class StateDetail {
        JsonValue jsonState;

        public StateDetail(AppState state, String shortDesc) {
            this.state = state;
            this.shortDesc = shortDesc;
            jsonState = new JsonValue(new HashMap<String, Object>());
            jsonState.put("state", state.name());
            jsonState.put("shortDesc", shortDesc);
        }

        private AppState state = AppState.STARTING;
        private String shortDesc;

        protected AppState getState() {
            return state;
        }

        protected String getShortDesc() {
            return shortDesc;
        }

        protected boolean isState(AppState compareState) {
            return state == compareState;
        }

        protected boolean isSameState(AppState compareState, String compareShortDesc) {
            return state == compareState
                    && ((shortDesc == null && compareShortDesc == null)
                        || (shortDesc != null && shortDesc.equals(compareShortDesc)));
        }

        protected JsonValue toJsonValue() {
            return jsonState;
        }
    }

    @Override
    public boolean handleEvent(ClusterEvent event) {
        switch (event.getType()) {
        case INSTANCE_FAILED:
            clusterUp = false;
            checkState();
            break;
        case INSTANCE_RUNNING:
            clusterUp = true;
            checkState();
            break;
		default:
			break;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Promise<ActionResponse, ResourceException> handleAction(Context context, ActionRequest request) {
        return router.handleAction(context, request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Promise<ResourceResponse, ResourceException> handleCreate(Context context, CreateRequest request) {
        return router.handleCreate(context, request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Promise<ResourceResponse, ResourceException> handleDelete(Context context, DeleteRequest request) {
        return router.handleDelete(context, request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Promise<ResourceResponse, ResourceException> handlePatch(Context context, PatchRequest request) {
        return router.handlePatch(context, request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Promise<QueryResponse, ResourceException> handleQuery(Context context, QueryRequest request,
            QueryResourceHandler handler) {
        return router.handleQuery(context, request, handler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Promise<ResourceResponse, ResourceException> handleRead(Context context, ReadRequest request) {
        return router.handleRead(context, request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Promise<ResourceResponse, ResourceException> handleUpdate(Context context, UpdateRequest request) {
        return router.handleUpdate(context, request);
    }

    @Override
    public ApiDescription api(final ApiProducer<ApiDescription> apiProducer) {
        return router.api(apiProducer);
    }

    @Override
    public ApiDescription handleApiRequest(final Context context, final Request request) {
        return router.handleApiRequest(context,request);
    }

    @Override
    public void addDescriptorListener(final Listener listener) {
        router.addDescriptorListener(listener);
    }

    @Override
    public void removeDescriptorListener(final Listener listener) {
        router.removeDescriptorListener(listener);
    }
}
