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
import org.cloudfoundry.client.v2.privatedomains.AbstractPrivateDomainResource;
import org.cloudfoundry.client.v2.privatedomains.CreatePrivateDomainRequest;
import org.cloudfoundry.client.v2.privatedomains.CreatePrivateDomainResponse;
import org.cloudfoundry.client.v2.privatedomains.DeletePrivateDomainRequest;
import org.cloudfoundry.client.v2.privatedomains.DeletePrivateDomainResponse;
import org.cloudfoundry.client.v2.privatedomains.GetPrivateDomainRequest;
import org.cloudfoundry.client.v2.privatedomains.GetPrivateDomainResponse;
import org.cloudfoundry.client.v2.privatedomains.ListPrivateDomainsRequest;
import org.cloudfoundry.client.v2.privatedomains.PrivateDomainResource;
import org.cloudfoundry.util.JobUtils;
import org.cloudfoundry.util.PaginationUtils;
import org.cloudfoundry.util.ResourceUtils;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cloudfoundry.util.tuple.TupleUtils.consumer;
import static org.cloudfoundry.util.tuple.TupleUtils.function;

public final class PrivateDomainsTest extends AbstractIntegrationTest {

    @Autowired
    private CloudFoundryClient cloudFoundryClient;

    @Autowired
    private Mono<String> organizationId;

    @Test
    public void create() throws TimeoutException, InterruptedException {
        String privateDomainName = this.nameFactory.getDomainName();

        this.organizationId
            .flatMap(organizationId -> Mono.when(
                requestCreatePrivateDomain(this.cloudFoundryClient, organizationId, privateDomainName),
                Mono.just(organizationId)
            ))
            .as(StepVerifier::create)
            .consumeNextWith(domainNameAndOrganizationIdEquality(privateDomainName))
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void delete() throws TimeoutException, InterruptedException {
        String privateDomainName = this.nameFactory.getDomainName();

        this.organizationId
            .flatMap(organizationId -> requestCreatePrivateDomain(this.cloudFoundryClient, organizationId, privateDomainName))
            .delayUntil(privateDomainResource -> requestDeletePrivateDomain(this.cloudFoundryClient, ResourceUtils.getId(privateDomainResource))
                .flatMap(jobResource -> JobUtils.waitForCompletion(this.cloudFoundryClient, Duration.ofMinutes(5), jobResource)))
            .flatMap(privateDomainResource -> requestGetPrivateDomain(this.cloudFoundryClient, ResourceUtils.getId(privateDomainResource)))
            .as(StepVerifier::create)
            .consumeErrorWith(t -> assertThat(t).isInstanceOf(ClientV2Exception.class).hasMessageMatching("CF-DomainNotFound\\([0-9]+\\): The domain could not be found: .*"))
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void get() throws TimeoutException, InterruptedException {
        String privateDomainName = this.nameFactory.getDomainName();

        this.organizationId
            .flatMap(organizationId -> Mono.when(
                Mono.just(organizationId),
                requestCreatePrivateDomain(this.cloudFoundryClient, organizationId, privateDomainName)
            ))
            .flatMap(function((organizationId, privateDomainResource) -> Mono.when(
                requestGetPrivateDomain(this.cloudFoundryClient, ResourceUtils.getId(privateDomainResource)),
                Mono.just(organizationId)
            )))
            .as(StepVerifier::create)
            .consumeNextWith(domainNameAndOrganizationIdEquality(privateDomainName))
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void list() throws TimeoutException, InterruptedException {
        String privateDomainName = this.nameFactory.getDomainName();

        this.organizationId
            .flatMap(organizationId -> Mono.when(
                Mono.just(organizationId),
                requestCreatePrivateDomain(this.cloudFoundryClient, organizationId, privateDomainName)
            ))
            .flatMap(function((organizationId, privateDomainResource) -> Mono.when(
                listPrivateDomains(this.cloudFoundryClient)
                    .filter(resource -> ResourceUtils.getId(privateDomainResource).equals(ResourceUtils.getId(resource)))
                    .single(),
                Mono.just(organizationId)
            )))
            .as(StepVerifier::create)
            .consumeNextWith(domainNameAndOrganizationIdEquality(privateDomainName))
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void listFilterByName() throws TimeoutException, InterruptedException {
        String privateDomainName = this.nameFactory.getDomainName();

        this.organizationId
            .flatMap(organizationId -> Mono.when(
                Mono.just(organizationId),
                requestCreatePrivateDomain(this.cloudFoundryClient, organizationId, privateDomainName)
            ))
            .flatMap(function((organizationId, privateDomainResource) -> Mono.when(
                listPrivateDomains(this.cloudFoundryClient, privateDomainName)
                    .filter(resource -> ResourceUtils.getId(privateDomainResource).equals(ResourceUtils.getId(resource)))
                    .single(),
                Mono.just(organizationId)
            )))
            .as(StepVerifier::create)
            .consumeNextWith(domainNameAndOrganizationIdEquality(privateDomainName))
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    private static <R extends AbstractPrivateDomainResource> Consumer<Tuple2<R, String>> domainNameAndOrganizationIdEquality(String domainName) {
        return consumer((resource, organizationId) -> {
            assertThat(ResourceUtils.getEntity(resource).getName()).isEqualTo(domainName);
            assertThat(ResourceUtils.getEntity(resource).getOwningOrganizationId()).isEqualTo(organizationId);
        });
    }

    private static Flux<PrivateDomainResource> listPrivateDomains(CloudFoundryClient cloudFoundryClient, String privateDomainName) {
        return PaginationUtils
            .requestClientV2Resources(page -> cloudFoundryClient.privateDomains()
                .list(ListPrivateDomainsRequest.builder()
                    .page(page)
                    .name(privateDomainName)
                    .build()));
    }

    private static Flux<PrivateDomainResource> listPrivateDomains(CloudFoundryClient cloudFoundryClient) {
        return PaginationUtils
            .requestClientV2Resources(page -> cloudFoundryClient.privateDomains()
                .list(ListPrivateDomainsRequest.builder()
                    .page(page)
                    .build()));
    }

    private static Mono<CreatePrivateDomainResponse> requestCreatePrivateDomain(CloudFoundryClient cloudFoundryClient, String organizationId, String domainName) {
        return cloudFoundryClient.privateDomains()
            .create(CreatePrivateDomainRequest.builder()
                .name(domainName)
                .owningOrganizationId(organizationId)
                .build());
    }

    private static Mono<DeletePrivateDomainResponse> requestDeletePrivateDomain(CloudFoundryClient cloudFoundryClient, String privateDomainId) {
        return cloudFoundryClient.privateDomains()
            .delete(DeletePrivateDomainRequest.builder()
                .privateDomainId(privateDomainId)
                .build());
    }

    private static Mono<GetPrivateDomainResponse> requestGetPrivateDomain(CloudFoundryClient cloudFoundryClient, String privateDomainId) {
        return cloudFoundryClient.privateDomains()
            .get(GetPrivateDomainRequest.builder()
                .privateDomainId(privateDomainId)
                .build());
    }

}

