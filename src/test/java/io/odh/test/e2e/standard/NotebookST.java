/*
 * Copyright Skodjob authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.odh.test.e2e.standard;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.odh.test.Environment;
import io.odh.test.OdhAnnotationsLabels;
import io.odh.test.framework.manager.resources.NotebookType;
import io.odh.test.utils.DscUtils;
import io.opendatahub.datasciencecluster.v1.DataScienceCluster;
import io.opendatahub.datasciencecluster.v1.DataScienceClusterBuilder;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.ComponentsBuilder;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.components.Dashboard;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.components.DashboardBuilder;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.components.Workbenches;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.components.WorkbenchesBuilder;
import io.opendatahub.dscinitialization.v1.DSCInitialization;
import io.skodjob.annotations.Contact;
import io.skodjob.annotations.Desc;
import io.skodjob.annotations.Step;
import io.skodjob.annotations.SuiteDoc;
import io.skodjob.annotations.TestDoc;
import io.skodjob.testframe.resources.KubeResourceManager;
import io.skodjob.testframe.utils.PodUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kubeflow.v1.Notebook;
import org.kubeflow.v1.NotebookBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

@SuiteDoc(
    description = @Desc("Verifies deployments of Notebooks via GitOps approach"),
    beforeTestSteps = {
        @Step(value = "Deploy Pipelines Operator", expected = "Pipelines operator is available on the cluster"),
        @Step(value = "Deploy ServiceMesh Operator", expected = "ServiceMesh operator is available on the cluster"),
        @Step(value = "Deploy Serverless Operator", expected = "Serverless operator is available on the cluster"),
        @Step(value = "Install ODH operator", expected = "Operator is up and running and is able to serve it's operands"),
        @Step(value = "Deploy DSCI", expected = "DSCI is created and ready"),
        @Step(value = "Deploy DSC", expected = "DSC is created and ready")
    },
    afterTestSteps = {
        @Step(value = "Delete ODH operator and all created resources", expected = "Operator is removed and all other resources as well")
    }
)
public class NotebookST extends StandardAbstract {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotebookST.class);

    private static final String DS_PROJECT_NAME = "test-notebooks";

    private static final String NTB_NAME = "test-odh-notebook";
    private static final String NTB_NAMESPACE = "test-odh-notebook";

    @TestDoc(
        description = @Desc("Create simple Notebook with all needed resources and see if Operator creates it properly"),
        contact = @Contact(name = "Jakub Stejskal", email = "jstejska@redhat.com"),
        steps = {
            @Step(value = "Create namespace for Notebook resources with proper name, labels and annotations", expected = "Namespace is created"),
            @Step(value = "Create PVC with proper labels and data for Notebook", expected = "PVC is created"),
            @Step(value = "Create Notebook resource with Jupyter Minimal image in pre-defined namespace", expected = "Notebook resource is created"),
            @Step(value = "Wait for Notebook pods readiness", expected = "Notebook pods are up and running, Notebook is in ready state")
        }
    )
    @Test
    void testCreateSimpleNotebook() throws IOException {
        // Create namespace
        Namespace ns = new NamespaceBuilder()
            .withNewMetadata()
            .withName(NTB_NAMESPACE)
            .addToLabels(OdhAnnotationsLabels.LABEL_DASHBOARD, "true")
            .addToAnnotations(OdhAnnotationsLabels.ANNO_SERVICE_MESH, "false")
            .endMetadata()
            .build();
        KubeResourceManager.getInstance().createResourceWithoutWait(ns);

        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(NTB_NAME)
                .withNamespace(NTB_NAMESPACE)
                .addToLabels(OdhAnnotationsLabels.LABEL_DASHBOARD, "true")
                .endMetadata()
                .withNewSpec()
                .addToAccessModes("ReadWriteOnce")
                .withNewResources()
                .addToRequests("storage", new Quantity("10Gi"))
                .endResources()
                .withVolumeMode("Filesystem")
                .endSpec()
                .build();
        KubeResourceManager.getInstance().createResourceWithoutWait(pvc);

        String notebookImage = NotebookType.getNotebookImage(NotebookType.JUPYTER_MINIMAL_IMAGE, NotebookType.JUPYTER_MINIMAL_2023_2_TAG);
        Notebook notebook = new NotebookBuilder(NotebookType.loadDefaultNotebook(NTB_NAMESPACE, NTB_NAME, notebookImage)).build();
        KubeResourceManager.getInstance().createResourceWithoutWait(notebook);

        LabelSelector lblSelector = new LabelSelectorBuilder()
                .withMatchLabels(Map.of("app", NTB_NAME))
                .build();

        PodUtils.waitForPodsReady(NTB_NAMESPACE, lblSelector, 1, true, () -> { });
    }

    @BeforeAll
    void deployDataScienceCluster() {
        if (Environment.SKIP_DEPLOY_DSCI_DSC) {
            LOGGER.info("DSCI and DSC deploy is skipped");
            return;
        }
        // Create DSCI
        DSCInitialization dsci = DscUtils.getBasicDSCI();

        // Create DSC
        DataScienceCluster dsc = new DataScienceClusterBuilder()
                .withNewMetadata()
                .withName(DS_PROJECT_NAME)
                .endMetadata()
                .withNewSpec()
                .withComponents(
                    new ComponentsBuilder()
                        .withWorkbenches(
                            new WorkbenchesBuilder().withManagementState(Workbenches.ManagementState.Managed).build()
                        )
                        .withDashboard(
                            new DashboardBuilder().withManagementState(Environment.SKIP_DEPLOY_DASHBOARD ? Dashboard.ManagementState.Removed : Dashboard.ManagementState.Managed).build()
                        )
                        .build())
                .endSpec()
                .build();
        // Deploy DSCI,DSC
        KubeResourceManager.getInstance().createOrUpdateResourceWithWait(dsci);
        KubeResourceManager.getInstance().createResourceWithWait(dsc);
    }
}
