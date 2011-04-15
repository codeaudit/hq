package org.hyperic.hq.operation.rabbit.shared;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.hyperic.hq.operation.Envelope;
import org.hyperic.hq.operation.RegisterAgentRequest;
import org.hyperic.hq.operation.annotation.Operation;
import org.hyperic.hq.operation.annotation.OperationDispatcher;
import org.hyperic.hq.operation.annotation.OperationEndpoint;
import org.hyperic.hq.operation.rabbit.convert.JsonMappingConverter;
import org.hyperic.hq.operation.rabbit.core.*;
import org.hyperic.hq.operation.rabbit.util.Constants;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.task.TaskExecutor;

import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/**
 * @author Helena Edelson
 */
public class OperationServiceTests {

    private AnnotatedRabbitOperationService operationService;

    private JsonMappingConverter converter = new JsonMappingConverter();

    private RegisterAgentRequest registerAgentRequest = new RegisterAgentRequest(null,"testAuth", "5.0", 1, "localhost", 0, "hqadmin", "hqadmin", false);

    @OperationDispatcher
    static class TestDispatcher {
        @Operation(operationName = Constants.OPERATION_NAME_AGENT_REGISTER_REQUEST, exchangeName = Constants.TO_SERVER_EXCHANGE, value = Constants.OPERATION_NAME_AGENT_REGISTER_REQUEST)
        void report(Object data) {
            System.out.println("Invoked method=report with data=" + data);
        }
    }

    @OperationEndpoint
    static class TestEndpoint {
        @Operation(operationName = Constants.OPERATION_NAME_AGENT_REGISTER_RESPONSE, exchangeName = Constants.TO_AGENT_EXCHANGE, value = Constants.OPERATION_NAME_AGENT_REGISTER_RESPONSE)
        void handle(Object data) {
            System.out.println("Invoked method=handle with data=" + data);
        }
    }

    @Before
    public void prepare() {
        /* not working with mock yet */
        ConnectionFactory cf = new ConnectionFactory();
        this.operationService = new AnnotatedRabbitOperationService(cf, new OperationToRoutingKeyRegistry(cf), new JsonMappingConverter());
    }

    @Test
    public void perform() {
        this.operationService.discover(new TestDispatcher(), OperationDispatcher.class);
        assertNotNull(this.operationService.getMappings().map(Constants.OPERATION_NAME_AGENT_REGISTER_REQUEST));
        Envelope envelope = new Envelope(Constants.OPERATION_NAME_AGENT_REGISTER_REQUEST, converter.write(registerAgentRequest));
        assertTrue(envelope.getContent().equals(converter.write(registerAgentRequest)));
        assertTrue((Boolean) this.operationService.perform(envelope));
    }

    @Test
    public void discover() {
        this.operationService.discover(new TestDispatcher(), OperationDispatcher.class);
        this.operationService.discover(new TestEndpoint(), OperationEndpoint.class);
        assertEquals(this.operationService.getMappings().getOperationMappings().size(), 1);
    }

    @Test
    public void dispatch() {
        this.operationService.discover(new TestDispatcher(), OperationDispatcher.class);
        this.operationService.dispatch(Constants.OPERATION_NAME_AGENT_REGISTER_REQUEST, registerAgentRequest);
        OperationMethodInvokingRegistry.MethodInvoker invoker = this.operationService.getMappings().getOperationMappings().get(Constants.OPERATION_NAME_AGENT_REGISTER_REQUEST);
        assertTrue(invoker.toString().contains(Constants.OPERATION_NAME_AGENT_REGISTER_REQUEST));
    }

    @Test
    public void handle() throws InvocationTargetException, IllegalAccessException {
        this.operationService.discover(new TestEndpoint(), OperationEndpoint.class);
        this.operationService.discover(new TestDispatcher(), OperationDispatcher.class);
        this.operationService.dispatch(Constants.OPERATION_NAME_AGENT_REGISTER_RESPONSE, registerAgentRequest);

        OperationMethodInvokingRegistry.MethodInvoker invoker = this.operationService.getMappings().map(Constants.OPERATION_NAME_AGENT_REGISTER_RESPONSE);
        assertNotNull(invoker);

        invoker.invoke(converter.write(registerAgentRequest));
        Envelope envelope = new Envelope(Constants.OPERATION_NAME_AGENT_REGISTER_RESPONSE, converter.write(registerAgentRequest));

        this.operationService.handle(envelope);
    }


    private final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);

    private final TaskExecutor taskExecutor = mock(TaskExecutor.class);

    private final Connection connection = mock(Connection.class);

    private final ConsumerCallbackFactory consumingCallbackFactory = mock(ConsumerCallbackFactory.class);

    private final ConsumerCallback consumingCallback = mock(ConsumerCallback.class);

    @Test
    public void consumerTests() {
        /*RabbitMessageListenerContainer listenerContainer = new RabbitMessageListenerContainer(this.connectionFactory);
        System.out.println(listenerContainer);*/
    }
}
