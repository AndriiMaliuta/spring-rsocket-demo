package io.pivotal.rsocketserver;

import io.pivotal.rsocketserver.data.Message;
import io.rsocket.SocketAcceptor;
import io.rsocket.core.RSocketServer;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.metadata.WellKnownMimeType;
import io.rsocket.transport.netty.server.CloseableChannel;
import io.rsocket.transport.netty.server.TcpServerTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.cbor.Jackson2CborDecoder;
import org.springframework.http.codec.cbor.Jackson2CborEncoder;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.messaging.rsocket.annotation.support.RSocketMessageHandler;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public class RSocketClientToServerIT {

    private static CloseableChannel server;

    private static RSocketRequester requester;

    @BeforeAll
    public static void setupOnce() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ServerConfig.class);
        RSocketMessageHandler messageHandler = context.getBean(RSocketMessageHandler.class);
        RSocketStrategies strategies = context.getBean(RSocketStrategies.class);

        // Spring will bind your custom RSocketController bean to this RSocketServer
        SocketAcceptor responder = messageHandler.responder();
        server = RSocketServer.create(responder)
                .payloadDecoder(PayloadDecoder.ZERO_COPY)
                .bind(TcpServerTransport.create("localhost", 0))
                .block();

        // Create an RSocket requester bound to the server above for use in this test suite
        MimeType metadataMimeType = MimeTypeUtils.parseMimeType(
                WellKnownMimeType.MESSAGE_RSOCKET_ROUTING.getString());
        requester = RSocketRequester.builder()
                .metadataMimeType(metadataMimeType)
                .rsocketStrategies(strategies)
                .connectTcp("localhost", server.address().getPort())
                .block();
    }

    @Test
    public void testRequestGetsResponse() {
        // Send a request message
        Mono<Message> result = requester
                .route("request-response")
                .data(new Message("TEST", "Request"))
                .retrieveMono(Message.class);

        // Verify that the response message contains the expected data
        StepVerifier
                .create(result)
                .consumeNextWith(message -> {
                    assertThat(message.getOrigin()).isEqualTo(RSocketController.SERVER);
                    assertThat(message.getInteraction()).isEqualTo(RSocketController.RESPONSE);
                    assertThat(message.getIndex()).isEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    public void TestFireAndForget() {
        // Send a fire-and-forget message
        Mono<Message> result = requester
                .route("fire-and-forget")
                .data(new Message("TEST", "Fire-And-Forget"))
                .retrieveMono(Message.class);

        // Assert that the result is a completed Mono.
        StepVerifier
                .create(result)
                .verifyComplete();
    }

    @Test
    public void testRequestGetsStream() {
        // Send a request message
        Flux<Message> result = requester
                .route("stream")
                .data(new Message("TEST", "Stream"))
                .retrieveFlux(Message.class);

        // Verify that the response messages contain the expected data
        StepVerifier
                .create(result)
                .consumeNextWith(message -> {
                    assertThat(message.getOrigin()).isEqualTo(RSocketController.SERVER);
                    assertThat(message.getInteraction()).isEqualTo(RSocketController.STREAM);
                    assertThat(message.getIndex()).isEqualTo(0L);
                })
                .expectNextCount(3)
                .consumeNextWith(message -> {
                    assertThat(message.getOrigin()).isEqualTo(RSocketController.SERVER);
                    assertThat(message.getInteraction()).isEqualTo(RSocketController.STREAM);
                    assertThat(message.getIndex()).isEqualTo(4L);
                })
                .thenCancel()
                .verify();
    }

    @Test
    public void testStreamGetsStream() {
        Mono<Duration> setting1 = Mono.just(Duration.ofSeconds(1));
        Mono<Duration> setting2 = Mono.just(Duration.ofSeconds(3)).delayElement(Duration.ofSeconds(2));
        Flux<Duration> settings = Flux.concat(setting1, setting2);

        // Send a stream of request messages
        Flux<Message> result = requester
                .route("channel")
                .data(settings)
                .retrieveFlux(Message.class);

        // Verify that the response messages contain the expected data
        StepVerifier
                .create(result)
                .consumeNextWith(message -> {
                    assertThat(message.getOrigin()).isEqualTo(RSocketController.SERVER);
                    assertThat(message.getInteraction()).isEqualTo(RSocketController.CHANNEL);
                    assertThat(message.getIndex()).isEqualTo(0L);
                })
                .consumeNextWith(message -> {
                    assertThat(message.getOrigin()).isEqualTo(RSocketController.SERVER);
                    assertThat(message.getInteraction()).isEqualTo(RSocketController.CHANNEL);
                    assertThat(message.getIndex()).isEqualTo(0L);
                })
                .thenCancel()
                .verify();
    }

    @Test
    public void testNoMatchingRouteGetsException() {
        // Send a request with bad route and data
        Mono<String> result = requester
                .route("invalid")
                .data("anything")
                .retrieveMono(String.class);

        // Verify that an error is generated
        StepVerifier.create(result)
                .expectErrorMessage("No handler for destination 'invalid'")
                .verify(Duration.ofSeconds(5));
    }

    @AfterAll
    public static void tearDownOnce() {
        requester.rsocket().dispose();
        server.dispose();
    }

    /**
     * This test-specific configuration allows Spring to help configure our test environment.
     * These beans will be placed into the Spring context and can be accessed when required.
     */
    @TestConfiguration
    static class ServerConfig {

        @Bean
        public RSocketController controller() {
            return new RSocketController();
        }

        @Bean
        public RSocketMessageHandler messageHandler(RSocketStrategies strategies) {
            RSocketMessageHandler handler = new RSocketMessageHandler();
            handler.setRSocketStrategies(strategies);
            return handler;
        }

        @Bean
        public RSocketStrategies rsocketStrategies() {
            return RSocketStrategies.builder().encoder(new Jackson2CborEncoder()).decoder(new Jackson2CborDecoder()).build();
        }
    }
}