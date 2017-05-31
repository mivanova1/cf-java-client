/*
 * Copyright 2013-2017 the original author or authors.
 *
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
 */

package org.cloudfoundry.client.v2;

import org.cloudfoundry.AbstractIntegrationTest;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.ApplicationInstanceInfo;
import org.cloudfoundry.client.v2.applications.ApplicationInstancesRequest;
import org.cloudfoundry.client.v2.applications.ApplicationInstancesResponse;
import org.cloudfoundry.client.v2.applications.AssociateApplicationRouteRequest;
import org.cloudfoundry.client.v2.applications.AssociateApplicationRouteResponse;
import org.cloudfoundry.client.v2.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v2.applications.CreateApplicationResponse;
import org.cloudfoundry.client.v2.applications.DeleteApplicationRequest;
import org.cloudfoundry.client.v2.applications.GetApplicationRequest;
import org.cloudfoundry.client.v2.applications.GetApplicationResponse;
import org.cloudfoundry.client.v2.applications.UpdateApplicationRequest;
import org.cloudfoundry.client.v2.applications.UpdateApplicationResponse;
import org.cloudfoundry.client.v2.applications.UploadApplicationRequest;
import org.cloudfoundry.client.v2.applications.UploadApplicationResponse;
import org.cloudfoundry.client.v2.routes.CreateRouteRequest;
import org.cloudfoundry.client.v2.routes.CreateRouteResponse;
import org.cloudfoundry.client.v2.servicebrokers.CreateServiceBrokerRequest;
import org.cloudfoundry.client.v2.servicebrokers.DeleteServiceBrokerRequest;
import org.cloudfoundry.client.v2.servicebrokers.GetServiceBrokerRequest;
import org.cloudfoundry.client.v2.servicebrokers.ListServiceBrokersRequest;
import org.cloudfoundry.client.v2.servicebrokers.UpdateServiceBrokerRequest;
import org.cloudfoundry.client.v2.shareddomains.ListSharedDomainsRequest;
import org.cloudfoundry.client.v2.shareddomains.SharedDomainResource;
import org.cloudfoundry.client.v2.spaces.CreateSpaceRequest;
import org.cloudfoundry.client.v2.spaces.CreateSpaceResponse;
import org.cloudfoundry.util.JobUtils;
import org.cloudfoundry.util.PaginationUtils;
import org.cloudfoundry.util.ResourceUtils;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cloudfoundry.util.DelayUtils.exponentialBackOff;
import static org.cloudfoundry.util.tuple.TupleUtils.function;

public final class ServiceBrokersTest extends AbstractIntegrationTest {

    private final Path application = new ClassPathResource("test-service-broker.jar").getFile().toPath();

    @Autowired
    private CloudFoundryClient cloudFoundryClient;

    @Autowired
    private Mono<String> organizationId;

    @Autowired
    private Mono<String> serviceBrokerId;

    @Autowired
    private String serviceBrokerName;

    public ServiceBrokersTest() throws IOException {
    }

    @Test
    public void create() {
        String serviceBrokerName = this.nameFactory.getServiceBrokerName();
        String serviceName = this.nameFactory.getServiceName();
        String planName = this.nameFactory.getPlanName();

        ApplicationMetadata applicationMetadata = this.organizationId
            .flatMap(organizationId -> pushServiceBroker(this.cloudFoundryClient, organizationId, serviceName, planName))
            .block(Duration.ofMinutes(5));

        this.cloudFoundryClient.serviceBrokers()
            .create(CreateServiceBrokerRequest.builder()
                .authenticationPassword("test-authentication-password")
                .authenticationUsername("test-authentication-username")
                .brokerUrl(applicationMetadata.uri)
                .name(serviceBrokerName)
                .spaceId(applicationMetadata.spaceId)
                .build())
            .flatMapMany(response -> PaginationUtils
                .requestClientV2Resources(page -> this.cloudFoundryClient.serviceBrokers()
                    .list(ListServiceBrokersRequest.builder()
                        .name(serviceBrokerName)
                        .page(page)
                        .build())))
            .as(StepVerifier::create)
            .expectNextCount(1)
            .expectComplete()
            .verify(Duration.ofMinutes(5));

        deleteServiceBroker(this.cloudFoundryClient, applicationMetadata.applicationId)
            .block(Duration.ofMinutes(5));
    }

    @Test
    public void delete() {
        String serviceBrokerName = this.nameFactory.getServiceBrokerName();
        String serviceName = this.nameFactory.getServiceName();
        String planName = this.nameFactory.getPlanName();

        ServiceBrokerMetadata serviceBrokerMetadata = this.organizationId
            .flatMap(organizationId -> createServiceBroker(this.cloudFoundryClient, organizationId, serviceBrokerName, serviceName, planName))
            .block(Duration.ofMinutes(5));

        this.cloudFoundryClient.serviceBrokers()
            .delete(DeleteServiceBrokerRequest.builder()
                .serviceBrokerId(serviceBrokerMetadata.serviceBrokerId)
                .build())
            .flatMapMany(response -> PaginationUtils
                .requestClientV2Resources(page -> this.cloudFoundryClient.serviceBrokers()
                    .list(ListServiceBrokersRequest.builder()
                        .name(serviceBrokerName)
                        .page(page)
                        .build())))
            .as(StepVerifier::create)
            .expectComplete()
            .verify(Duration.ofMinutes(5));

        deleteServiceBroker(this.cloudFoundryClient, serviceBrokerMetadata.applicationMetadata.applicationId)
            .block(Duration.ofMinutes(5));
    }

    @Test
    public void get() {
        this.serviceBrokerId
            .flatMap(serviceBrokerId -> this.cloudFoundryClient.serviceBrokers()
                .get(GetServiceBrokerRequest.builder()
                    .serviceBrokerId(serviceBrokerId)
                    .build()))
            .as(StepVerifier::create)
            .assertNext(serviceBroker -> assertThat(ResourceUtils.getEntity(serviceBroker).getName()).isEqualTo(this.serviceBrokerName))
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void list() {
        PaginationUtils
            .requestClientV2Resources(page -> this.cloudFoundryClient.serviceBrokers()
                .list(ListServiceBrokersRequest.builder()
                    .name(this.serviceBrokerName)
                    .page(page)
                    .build()))
            .as(StepVerifier::create)
            .expectNextCount(1)
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void update() {
        String serviceBrokerName1 = this.nameFactory.getServiceBrokerName();
        String serviceBrokerName2 = this.nameFactory.getServiceBrokerName();
        String serviceName = this.nameFactory.getServiceName();
        String planName = this.nameFactory.getPlanName();

        ServiceBrokerMetadata serviceBrokerMetadata = this.organizationId
            .flatMap(organizationId -> createServiceBroker(this.cloudFoundryClient, organizationId, serviceBrokerName1, serviceName, planName))
            .block(Duration.ofMinutes(5));

        this.cloudFoundryClient.serviceBrokers()
            .update(UpdateServiceBrokerRequest.builder()
                .serviceBrokerId(serviceBrokerMetadata.serviceBrokerId)
                .name(serviceBrokerName2)
                .build())
            .flatMapMany(serviceBrokerId -> PaginationUtils
                .requestClientV2Resources(page -> this.cloudFoundryClient.serviceBrokers()
                    .list(ListServiceBrokersRequest.builder()
                        .name(serviceBrokerName2)
                        .page(page)
                        .build())))
            .as(StepVerifier::create)
            .expectNextCount(1)
            .expectComplete()
            .verify(Duration.ofMinutes(5));

        deleteServiceBroker(this.cloudFoundryClient, serviceBrokerMetadata.applicationMetadata.applicationId)
            .block(Duration.ofMinutes(5));
    }

    private static Mono<Void> deleteServiceBroker(CloudFoundryClient cloudFoundryClient, String applicationId) {
        return cloudFoundryClient.applicationsV2()
            .delete(DeleteApplicationRequest.builder()
                .applicationId(applicationId)
                .build());
    }

    private static Mono<ApplicationInstancesResponse> requestApplicationInstances(CloudFoundryClient cloudFoundryClient, String applicationId) {
        return cloudFoundryClient.applicationsV2()
            .instances(ApplicationInstancesRequest.builder()
                .applicationId(applicationId)
                .build());
    }

    private static Mono<AssociateApplicationRouteResponse> requestAssociateApplicationRoute(CloudFoundryClient cloudFoundryClient, String applicationId, String routeId) {
        return cloudFoundryClient.applicationsV2()
            .associateRoute(AssociateApplicationRouteRequest.builder()
                .applicationId(applicationId)
                .routeId(routeId)
                .build());
    }

    private static Mono<CreateApplicationResponse> requestCreateApplication(CloudFoundryClient cloudFoundryClient, String spaceId, String applicationName) {
        return cloudFoundryClient.applicationsV2()
            .create(CreateApplicationRequest.builder()
                .buildpack("https://github.com/cloudfoundry/java-buildpack.git")
                .memory(768)
                .name(applicationName)
                .spaceId(spaceId)
                .build());
    }

    private static Mono<CreateRouteResponse> requestCreateRoute(CloudFoundryClient cloudFoundryClient, String domainId, String spaceId, String hostName) {
        return cloudFoundryClient.routes()
            .create(CreateRouteRequest.builder()
                .domainId(domainId)
                .host(hostName)
                .spaceId(spaceId)
                .build());
    }

    private static Mono<CreateSpaceResponse> requestCreateSpace(CloudFoundryClient cloudFoundryClient, String organizationId, String spaceName) {
        return cloudFoundryClient.spaces()
            .create(CreateSpaceRequest.builder()
                .name(spaceName)
                .organizationId(organizationId)
                .build());
    }

    private static Mono<GetApplicationResponse> requestGetApplication(CloudFoundryClient cloudFoundryClient, String applicationId) {
        return cloudFoundryClient.applicationsV2()
            .get(GetApplicationRequest.builder()
                .applicationId(applicationId)
                .build());
    }

    private static Flux<SharedDomainResource> requestListSharedDomains(CloudFoundryClient cloudFoundryClient) {
        return PaginationUtils
            .requestClientV2Resources(page -> cloudFoundryClient.sharedDomains()
                .list(ListSharedDomainsRequest.builder()
                    .page(page)
                    .build()));
    }

    private static Mono<UpdateApplicationResponse> requestUpdateApplication(CloudFoundryClient cloudFoundryClient, String applicationId, String state, String serviceName, String planName) {
        return cloudFoundryClient.applicationsV2()
            .update(UpdateApplicationRequest.builder()
                .applicationId(applicationId)
                .environmentJson("SERVICE_NAME", serviceName)
                .environmentJson("PLAN_NAME", planName)
                .state(state)
                .build());
    }

    private static Mono<UploadApplicationResponse> requestUploadApplication(CloudFoundryClient cloudFoundryClient, String applicationId, Path application) {
        return cloudFoundryClient.applicationsV2()
            .upload(UploadApplicationRequest.builder()
                .application(application)
                .applicationId(applicationId)
                .async(true)
                .build());
    }

    private Mono<ServiceBrokerMetadata> createServiceBroker(CloudFoundryClient cloudFoundryClient, String organizationId, String serviceBrokerName, String serviceName, String planName) {
        return pushServiceBroker(cloudFoundryClient, organizationId, serviceName, planName)
            .flatMap(applicationMetadata -> this.cloudFoundryClient.serviceBrokers()
                .create(CreateServiceBrokerRequest.builder()
                    .authenticationPassword("test-authentication-password")
                    .authenticationUsername("test-authentication-username")
                    .brokerUrl(applicationMetadata.uri)
                    .name(serviceBrokerName)
                    .spaceId(applicationMetadata.spaceId)
                    .build())
                .map(response -> new ServiceBrokerMetadata(applicationMetadata, ResourceUtils.getId(response))));
    }

    private Mono<ApplicationMetadata> pushServiceBroker(CloudFoundryClient cloudFoundryClient, String organizationId, String serviceName, String planName) {
        String applicationName = this.nameFactory.getApplicationName();
        String hostName = this.nameFactory.getHostName();
        String spaceName = this.nameFactory.getSpaceName();

        return Mono
            .when(
                requestCreateSpace(cloudFoundryClient, organizationId, spaceName)
                    .map(ResourceUtils::getId),
                requestListSharedDomains(cloudFoundryClient)
                    .next()
            )
            .flatMap(function((spaceId, domain) -> Mono
                .when(
                    requestCreateApplication(cloudFoundryClient, spaceId, applicationName)
                        .map(ResourceUtils::getId),
                    Mono.just(ResourceUtils.getId(domain))
                        .flatMap(domainId -> requestCreateRoute(cloudFoundryClient, domainId, spaceId, hostName))
                        .map(ResourceUtils::getId)
                )
                .flatMap(function((applicationId, routeId) -> requestAssociateApplicationRoute(cloudFoundryClient, applicationId, routeId)
                    .then(Mono.just(applicationId))))
                .flatMap(applicationId -> requestUploadApplication(cloudFoundryClient, applicationId, this.application)
                    .flatMap(job -> JobUtils.waitForCompletion(cloudFoundryClient, Duration.ofMinutes(5), job))
                    .then(Mono.just(applicationId)))
                .flatMap(applicationId -> requestUpdateApplication(cloudFoundryClient, applicationId, "STARTED", serviceName, planName)
                    .then(Mono.just(applicationId)))
                .flatMap(applicationId -> requestGetApplication(cloudFoundryClient, applicationId)
                    .map(response -> ResourceUtils.getEntity(response).getPackageState())
                    .filter(state -> "STAGED".equals(state) || "FAILED".equals(state))
                    .repeatWhenEmpty(exponentialBackOff(Duration.ofSeconds(1), Duration.ofSeconds(15), Duration.ofMinutes(5)))
                    .then(Mono.just(applicationId)))
                .flatMap(applicationId -> requestApplicationInstances(cloudFoundryClient, applicationId)
                    .flatMapMany(response -> Flux.fromIterable(response.getInstances().values()))
                    .single()
                    .map(ApplicationInstanceInfo::getState)
                    .filter("RUNNING"::equals)
                    .repeatWhenEmpty(exponentialBackOff(Duration.ofSeconds(1), Duration.ofSeconds(15), Duration.ofMinutes(5)))
                    .map(status -> new ApplicationMetadata(applicationId, spaceId, String.format("https://%s.%s", hostName, ResourceUtils.getEntity(domain).getName()))))
            ));
    }

    private static final class ApplicationMetadata {

        private final String applicationId;

        private final String spaceId;

        private final String uri;

        private ApplicationMetadata(String applicationId, String spaceId, String uri) {
            this.applicationId = applicationId;
            this.spaceId = spaceId;
            this.uri = uri;
        }

    }

    private static final class ServiceBrokerMetadata {

        private final ApplicationMetadata applicationMetadata;

        private final String serviceBrokerId;

        private ServiceBrokerMetadata(ApplicationMetadata applicationMetadata, String serviceBrokerId) {
            this.applicationMetadata = applicationMetadata;
            this.serviceBrokerId = serviceBrokerId;
        }

    }

}
