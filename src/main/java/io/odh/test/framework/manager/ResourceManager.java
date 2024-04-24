/*
 * Copyright Skodjob authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.odh.test.framework.manager;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.odh.test.TestConstants;
import io.odh.test.TestUtils;
import io.odh.test.framework.manager.resources.DataScienceClusterResource;
import io.odh.test.framework.manager.resources.DataScienceInitializationResource;
import io.odh.test.framework.manager.resources.InferenceServiceResource;
import io.odh.test.framework.manager.resources.NamespaceResource;
import io.odh.test.framework.manager.resources.NotebookResource;
import io.odh.test.framework.manager.resources.OperatorGroupResource;
import io.odh.test.framework.manager.resources.SubscriptionResource;
import io.odh.test.platform.KubeClient;
import io.odh.test.platform.cmdClient.KubeCmdClient;
import io.odh.test.platform.cmdClient.Oc;
import io.odh.test.utils.DeploymentUtils;
import io.opendatahub.datasciencecluster.v1.DataScienceCluster;
import io.opendatahub.dscinitialization.v1.DSCInitialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceManager.class);

    private static ResourceManager instance;
    private static KubeClient client;
    private static KubeCmdClient kubeCmdClient;

    public static final Stack<ResourceItem<? extends HasMetadata>> CLASS_RESOURCE_STACK = new Stack<>();
    public static final Stack<ResourceItem<? extends HasMetadata>> METHOD_RESOURCE_STACK = new Stack<>();

    static Stack<ResourceItem<? extends HasMetadata>> resourceStackPointer = CLASS_RESOURCE_STACK;

    static List<String> defaultNamespacesForLogCollect = Arrays.asList(
        "openshift-marketplace",
        "openshift-operators"
    );

    public static synchronized ResourceManager getInstance() {
        if (instance == null) {
            instance = new ResourceManager();
            client = new KubeClient(TestConstants.DEFAULT_NAMESPACE);
            kubeCmdClient = new Oc(client.getKubeconfigPath());
            for (String ns : defaultNamespacesForLogCollect) {
                addNamespaceForLogCollect(ns);
            }
        }
        return instance;
    }

    public static KubeClient getKubeClient() {
        return client;
    }

    public static KubeCmdClient getKubeCmdClient() {
        return kubeCmdClient;
    }

    private final ResourceType<? extends HasMetadata>[] resourceTypes = new ResourceType<?>[]{
        new NamespaceResource(),
        new SubscriptionResource(),
        new OperatorGroupResource(),
        new DataScienceClusterResource(),
        new DataScienceInitializationResource(),
        new NotebookResource(),
        new InferenceServiceResource(),
    };

    public final void switchToTestResourceStack() {
        resourceStackPointer = METHOD_RESOURCE_STACK;
    }

    public final void switchToClassResourceStack() {
        resourceStackPointer = CLASS_RESOURCE_STACK;
    }

    public static void addNamespaceForLogCollect(String namespace) {
        NamespaceResource.labelNamespace(namespace, TestConstants.LOG_COLLECT_LABEL, "true");
    }

    public final void pushToStack(ResourceItem<? extends HasMetadata> item) {
        resourceStackPointer.push(item);
    }

    @SafeVarargs
    public final <T extends HasMetadata> void createResourceWithoutWait(T... resources) {
        createResource(false, resources);
    }

    @SafeVarargs
    public final <T extends HasMetadata> void createResourceWithWait(T... resources) {
        createResource(true, resources);
    }

    @SafeVarargs
    private <T extends HasMetadata> void createResource(boolean waitReady, T... resources) {
        for (T resource : resources) {
            ResourceType<T> type = findResourceType(resource);

            synchronized (this) {
                resourceStackPointer.push(
                        new ResourceItem<T>(
                                () -> deleteResource(resource),
                                resource
                        ));
            }

            if (resource.getMetadata().getNamespace() == null) {
                LOGGER.info("Creating/Updating {} {}",
                        resource.getKind(), resource.getMetadata().getName());
            } else {
                LOGGER.info("Creating/Updating {} {}/{}",
                        resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
                addNamespaceForLogCollect(resource.getMetadata().getNamespace());
            }

            if (type == null) {
                if (resource instanceof Deployment deployment) {
                    if (client.getClient().apps().deployments().resource(deployment).get() != null) {
                        client.getClient().apps().deployments().resource(deployment).update();
                    } else {
                        client.getClient().apps().deployments().resource(deployment).create();
                    }
                    if (waitReady) {
                        DeploymentUtils.waitForDeploymentReady(resource.getMetadata().getNamespace(), resource.getMetadata().getName());
                    }
                } else {
                    if (client.getClient().resource(resource).get() != null) {
                        client.getClient().resource(resource).update();
                    } else {
                        client.getClient().resource(resource).create();
                    }
                }
            } else {
                if (type.get(resource.getMetadata().getNamespace(), resource.getMetadata().getName()) != null) {
                    type.update(resource);
                } else {
                    type.create(resource);
                }

                if (waitReady) {
                    assertTrue(waitResourceCondition(resource, ResourceCondition.readiness(type)),
                            String.format("Timed out waiting for %s %s/%s to be ready", resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
                }
            }
        }
    }

    @SafeVarargs
    public final <T extends HasMetadata> void deleteResource(T... resources) {
        for (T resource : resources) {
            ResourceType<T> type = findResourceType(resource);
            if (type == null) {
                LOGGER.info("Deleting of {} {}",
                        resource.getKind(), resource.getMetadata().getName());
                if (resource instanceof Deployment) {
                    Deployment deployment = (Deployment) resource;
                    client.getClient().apps().deployments().resource(deployment).delete();
                    DeploymentUtils.waitForDeploymentDeletion(resource.getMetadata().getNamespace(), resource.getMetadata().getName());
                } else {
                    client.getClient().resource(resource).delete();
                }
            } else {
                if (resource.getMetadata().getNamespace() == null) {
                    LOGGER.info("Deleting of {} {}",
                            resource.getKind(), resource.getMetadata().getName());
                } else {
                    LOGGER.info("Deleting of {} {}/{}",
                            resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
                }

                try {
                    type.delete(resource);
                    assertTrue(waitResourceCondition(resource, ResourceCondition.deletion()),
                            String.format("Timed out deleting %s %s/%s", resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
                } catch (Exception e) {
                    if (resource.getMetadata().getNamespace() == null) {
                        LOGGER.error("Failed to delete {} {}", resource.getKind(), resource.getMetadata().getName(), e);
                    } else {
                        LOGGER.error("Failed to delete {} {}/{}", resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName(), e);
                    }
                }
            }
        }
    }

    @SafeVarargs
    public final <T extends HasMetadata> void updateResource(T... resources) {
        for (T resource : resources) {
            ResourceType<T> type = findResourceType(resource);
            if (type != null) {
                type.update(resource);
            } else {
                client.getClient().resource(resource).update();
            }
        }
    }

    public final <T extends HasMetadata> boolean waitResourceCondition(T resource, ResourceCondition<T> condition) {
        assertNotNull(resource);
        assertNotNull(resource.getMetadata());
        assertNotNull(resource.getMetadata().getName());

        // cluster role binding and custom resource definition does not need namespace...
        if (!(resource instanceof ClusterRoleBinding || resource instanceof CustomResourceDefinition
                || resource instanceof ClusterRole || resource instanceof ValidatingWebhookConfiguration
                || resource instanceof DataScienceCluster || resource instanceof Namespace
                || resource instanceof DSCInitialization)) {
            assertNotNull(resource.getMetadata().getNamespace());
        }

        ResourceType<T> type = findResourceType(resource);
        assertNotNull(type);
        boolean[] resourceReady = new boolean[1];

        TestUtils.waitFor("resource condition: " + condition.getConditionName() + " to be fulfilled for resource " + resource.getKind() + ":" + resource.getMetadata().getName(),
                TestConstants.GLOBAL_POLL_INTERVAL_MEDIUM, TestConstants.GLOBAL_TIMEOUT,
                () -> {
                    T res = type.get(resource.getMetadata().getNamespace(), resource.getMetadata().getName());
                    resourceReady[0] = condition.getPredicate().test(res);
                    if (!resourceReady[0]) {
                        type.delete(res);
                    }
                    return resourceReady[0];
                });

        return resourceReady[0];
    }

    public void deleteResources() {
        if (!resourceStackPointer.isEmpty()) {
            LOGGER.info(String.join("", Collections.nCopies(76, "#")));

            while (!resourceStackPointer.empty()) {
                try {
                    ResourceItem<?> resourceItem = resourceStackPointer.pop();
                    resourceItem.getThrowableRunner().run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            LOGGER.info(String.join("", Collections.nCopies(76, "#")));
        }
    }

    private <T extends HasMetadata> ResourceType<T> findResourceType(T resource) {
        // other no conflicting types
        for (ResourceType<?> type : resourceTypes) {
            if (type.getKind().equals(resource.getKind())) {
                return (ResourceType<T>) type;
            }
        }
        return null;
    }
}
