/*
 * Copyright Skodjob authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.odh.test.e2e.standard;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.Template;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.dsl.TemplateResource;
import io.kserve.serving.v1alpha1.ServingRuntime;
import io.kserve.serving.v1alpha1.ServingRuntimeBuilder;
import io.kserve.serving.v1alpha1.servingruntimespec.VolumesBuilder;
import io.kserve.serving.v1alpha1.servingruntimespec.containers.VolumeMountsBuilder;
import io.kserve.serving.v1alpha1.servingruntimespec.volumes.EmptyDirBuilder;
import io.kserve.serving.v1beta1.InferenceService;
import io.kserve.serving.v1beta1.InferenceServiceBuilder;
import io.odh.test.Environment;
import io.odh.test.OdhAnnotationsLabels;
import io.odh.test.OdhConstants;
import io.odh.test.TestUtils;
import io.odh.test.utils.DscUtils;
import io.opendatahub.datasciencecluster.v1.DataScienceCluster;
import io.opendatahub.datasciencecluster.v1.DataScienceClusterBuilder;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.ComponentsBuilder;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.components.Dashboard;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.components.DashboardBuilder;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.components.Kserve;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.components.KserveBuilder;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.components.Modelmeshserving;
import io.opendatahub.datasciencecluster.v1.datascienceclusterspec.components.ModelmeshservingBuilder;
import io.opendatahub.dscinitialization.v1.DSCInitialization;
import io.skodjob.annotations.Contact;
import io.skodjob.annotations.Desc;
import io.skodjob.annotations.Step;
import io.skodjob.annotations.SuiteDoc;
import io.skodjob.annotations.TestDoc;
import io.skodjob.testframe.resources.KubeResourceManager;
import io.skodjob.testframe.utils.PodUtils;
import lombok.SneakyThrows;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.odh.test.TestUtils.DEFAULT_TIMEOUT_DURATION;
import static io.odh.test.TestUtils.DEFAULT_TIMEOUT_UNIT;
import static org.hamcrest.MatcherAssert.assertThat;

@SuppressWarnings({"checkstyle:ClassFanOutComplexity"})
@SuiteDoc(
    description = @Desc("Verifies simple setup of ODH for model serving by spin-up operator, setup DSCI, and setup DSC."),
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
public class ModelServingST extends StandardAbstract {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelServingST.class);

    private static final String DS_PROJECT_NAME = "test-model-serving";

    private final OpenShiftClient kubeClient = KubeResourceManager.getKubeClient().getOpenShiftClient();

    @BeforeAll
    static void deployDataScienceCluster() {
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
                                .withDashboard(
                                        new DashboardBuilder().withManagementState(Environment.SKIP_DEPLOY_DSC_DASHBOARD ? Dashboard.ManagementState.Removed : Dashboard.ManagementState.Managed).build()
                                )
                                .withKserve(
                                        new KserveBuilder().withManagementState(Kserve.ManagementState.Managed).build()
                                )
                                .withModelmeshserving(
                                        new ModelmeshservingBuilder().withManagementState(Modelmeshserving.ManagementState.Managed).build()
                                )
                                .build())
                .endSpec()
                .build();

        KubeResourceManager.getInstance().createOrUpdateResourceWithWait(dsci);
        KubeResourceManager.getInstance().createResourceWithWait(dsc);
    }

    @TestDoc(
        description = @Desc("Check that user can create, run inference and delete MultiModelServing server from a DataScience project"),
        contact = @Contact(name = "Jiri Danek", email = "jdanek@redhat.com"),
        steps = {
            @Step(value = "Create namespace for ServingRuntime application with proper name, labels and annotations", expected = "Namespace is created"),
            @Step(value = "Create a serving runtime using the processModelServerTemplate method", expected = "Serving runtime instance has been created"),
            @Step(value = "Create a secret that exists, even though it contains no useful information", expected = "Secret has been created"),
            @Step(value = "Create an inference service", expected = "Inference service has been created"),
            @Step(value = "Perform model inference through the route", expected = "The model inference execution has been successful"),
            @Step(value = "Delete the Inference Service", expected = "The Inference service has been deleted"),
            @Step(value = "Delete the secret", expected = "The secret has been deleted"),
            @Step(value = "Delete the serving runtime", expected = "The serving runtime has been deleted"),
        }
    )
    @Test
    void testMultiModelServerInference() {
        final String projectName = "multi-model-serving";
        final String runtimeName = "some-runtime";
        final String modelName = "some-model";

        // https://github.com/onnx/models/blob/main/validated/vision/classification/mnist/README.md
        final String modelStorageUrl = "https://github.com/onnx/models/blob/bec48b6a70e5e9042c0badbaafefe4454e072d08/validated/vision/classification/mnist/model/mnist-8.onnx?raw=true";
        final String modelInputPath = "modelmesh/modelmesh-mnist-input.json";
        final String expectedModelOutput = "\"data\":[-8.233052,-7.7497034,-3.42368,12.363029,-12.079105,17.266596,-10.570976,0.71307594,3.321714,1.362123]";

        // create project
        Namespace ns = new NamespaceBuilder()
                .withNewMetadata()
                .withName(projectName)
                .addToLabels(OdhAnnotationsLabels.LABEL_DASHBOARD, "true")
                .addToAnnotations(OdhAnnotationsLabels.ANNO_SERVICE_MESH, "false")
                .addToLabels(OdhAnnotationsLabels.ANNO_MODEL_MESH, "true")
                .endMetadata()
                .build();
        KubeResourceManager.getInstance().createResourceWithWait(ns);

        // secret must exist for ServingRuntime to start, even though it contains no useful information
        Secret storageConfig = new SecretBuilder()
                .withNewMetadata()
                .withName("storage-config") // this name is exactly required
                .withNamespace(projectName)
                .endMetadata()
                .withType("Opaque")
                .addToStringData("aws-connection-no-such-connection", "{}")
                .build();
        KubeResourceManager.getInstance().createResourceWithWait(storageConfig);

        // create serving runtime
        ServingRuntime servingRuntime = processModelServerTemplate("ovms");
        ServingRuntime servingRuntimeInstance = new ServingRuntimeBuilder(servingRuntime)
                .editMetadata()
                .withName(runtimeName)
                .withNamespace(projectName)
                .addToAnnotations("enable-route", "true")
                .addToAnnotations("opendatahub.io/apiProtocol", "REST")
                .addToAnnotations("opendatahub.io/accelerator-name", "")
                .addToLabels(OdhAnnotationsLabels.LABEL_DASHBOARD, "true")
                .endMetadata()
                .editSpec()
                .editFirstContainer()
                // with unspecified resources, one would get
                //  java.lang.Exception: Reported model capacity -0.125GiB too small relative to advertised default model size of 1MiB
                .editServingruntimespecResources()
                .addToLimits("cpu", new IntOrString("2"))
                .addToLimits("memory", new IntOrString("8Gi"))
                .addToRequests("cpu", new IntOrString("1"))
                .addToRequests("memory", new IntOrString("4Gi"))
                .endServingruntimespecResources()
                // server would not start without this storage
                .addToVolumeMounts(new VolumeMountsBuilder().withMountPath("/dev/shm").withName("shm").build())
                .endServingruntimespecContainer()
                .addToVolumes(new VolumesBuilder().withName("shm").withEmptyDir(new EmptyDirBuilder().withMedium("Memory").withSizeLimit(new IntOrString("2Gi")).build()).build())
                .endSpec()
                .build();
        KubeResourceManager.getInstance().createResourceWithWait(servingRuntimeInstance);

        // create inference service
        InferenceService inferenceService = new InferenceServiceBuilder()
                .withNewMetadata()
                .withName(modelName)
                .withNamespace(projectName)
                .addToLabels("opendatahub.io/dashboard", "true")
                .addToAnnotations("openshift.io/display-name", modelName)
                .addToAnnotations("serving.kserve.io/deploymentMode", "ModelMesh")
                .endMetadata()
                .withNewSpec()
                .withNewPredictor()
                .withNewModel()
                .withNewModelFormat()
                .withName("onnx")
                .withVersion("1")
                .endModelFormat()
                .withRuntime(runtimeName)
                .withStorageUri(modelStorageUrl)
                .endPredictorModel()
                .endInferenceservicespecPredictor()
                .endSpec()
                .build();
        KubeResourceManager.getInstance().createResourceWithWait(inferenceService);

        String namespace = "knative-serving";
        LOGGER.info("Waiting for pods readiness in {}", namespace);
        PodUtils.waitForPodsReady(namespace, true, () -> {
            KubeResourceManager.getKubeCmdClient().inNamespace(namespace).exec(false, "get", "pods");
            KubeResourceManager.getKubeCmdClient().inNamespace(namespace).exec(false, "get", "events");
        });

        Route route = kubeClient.routes().inNamespace(projectName).withName(modelName).get();
        String modelServerUrl = "https://" + route.getSpec().getHost() + route.getSpec().getPath();

        queryModelAndCheckMnistInference(modelServerUrl, modelInputPath, expectedModelOutput);
    }

    ServingRuntime processModelServerTemplate(String templateName) {
        TemplateResource templateResource = kubeClient.templates().inNamespace(OdhConstants.CONTROLLERS_NAMESPACE).withName(templateName);
        Template template = templateResource.get();
        Assertions.assertNotNull(template);
        Assertions.assertEquals(0, template.getParameters().size());

        List<HasMetadata> instances = templateResource.process().getItems();
        Assertions.assertEquals(1, instances.size());
        return instances.stream().map(it -> {
            GenericKubernetesResource genericKubernetesResource = (GenericKubernetesResource) it;
            // WORKAROUND(RHOAIENG-4547) ServingRuntime should not have top level `labels` key
            genericKubernetesResource.getAdditionalProperties().remove("labels");
            return castResource(it, ServingRuntime.class);
        }).findFirst().orElseThrow();
    }

    @SneakyThrows
    void queryModelAndCheckMnistInference(String baseUrl, String modelInputPath, String expectedModelOutput) {
        SSLContext sslContext = getSSLContextFromSecret();

        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .sslContext(sslContext)
                .build();

        TestUtils.waitForServiceNotUnavailable(httpClient, baseUrl);

        HttpRequest inferRequest = HttpRequest.newBuilder()
                .uri(new URI("%s/infer".formatted(baseUrl)))
                // this is the Content-Type header that `curl --data` sets by default
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> this.getClass().getClassLoader().getResourceAsStream(modelInputPath)))
                .timeout(Duration.of(DEFAULT_TIMEOUT_DURATION, DEFAULT_TIMEOUT_UNIT.toChronoUnit()))
                .build();
        HttpResponse<String> inferResponse = httpClient.send(inferRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(inferResponse.body(), inferResponse.statusCode(), Matchers.is(200));
        assertThat(inferResponse.body(), Matchers.containsString(expectedModelOutput));
    }

    private <T> T castResource(KubernetesResource value, Class<T> type) {
        String resourceAsString = Serialization.asJson(value);
        return Serialization.unmarshal(resourceAsString, type);
    }

    private SSLContext getSSLContextFromSecret() throws Exception {
        Secret signingKey = kubeClient.secrets().inNamespace("openshift-ingress").withName("router-certs-default").get();
        String caSecret = signingKey.getData().get("tls.crt");
        SSLContext sslContext = createSslContext(caSecret);
        return sslContext;
    }

    private SSLContext createSslContext(String base64EncodedPems) throws Exception {
        Base64.Decoder decoder = Base64.getMimeDecoder();
        String pem = new String(decoder.decode(base64EncodedPems));
        Pattern parse = Pattern.compile("(?m)(?s)^---*BEGIN ([^-]+)---*$([^-]+)^---*END[^-]+-+$");
        Matcher m = parse.matcher(pem);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        List<Certificate> certList = new ArrayList<>();

        int start = 0;
        while (m.find(start)) {
            String type = m.group(1);
            String base64Data = m.group(2);
            byte[] data = decoder.decode(base64Data);
            start += m.group(0).length();
            type = type.toUpperCase(Locale.ENGLISH);
            if (type.contains("CERTIFICATE")) {
                Certificate cert = certFactory.generateCertificate(new ByteArrayInputStream(data));
                certList.add(cert);
            } else {
                LOGGER.error("Unsupported type: {}", type);
            }
        }

        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, null);

        int count = 0;
        for (Certificate cert : certList) {
            trustStore.setCertificateEntry("cert" + count, cert);
            count++;
        }

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return sslContext;
    }
}
