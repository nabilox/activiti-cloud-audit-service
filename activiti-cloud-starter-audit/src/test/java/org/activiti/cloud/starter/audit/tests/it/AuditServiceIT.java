/*
 * Copyright 2018 Alfresco, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.activiti.cloud.starter.audit.tests.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.activiti.api.process.model.events.BPMNActivityEvent;
import org.activiti.api.runtime.model.impl.BPMNActivityImpl;
import org.activiti.api.runtime.model.impl.ProcessInstanceImpl;
import org.activiti.api.task.model.Task;
import org.activiti.api.task.model.events.TaskRuntimeEvent;
import org.activiti.api.task.model.impl.TaskImpl;
import org.activiti.cloud.api.model.shared.events.CloudRuntimeEvent;
import org.activiti.cloud.api.model.shared.impl.conf.IgnoredRuntimeEvent;
import org.activiti.cloud.api.model.shared.impl.events.CloudRuntimeEventImpl;
import org.activiti.cloud.api.process.model.events.CloudBPMNActivityEvent;
import org.activiti.cloud.api.process.model.events.CloudBPMNActivityStartedEvent;
import org.activiti.cloud.api.process.model.impl.events.CloudBPMNActivityCancelledEventImpl;
import org.activiti.cloud.api.process.model.impl.events.CloudBPMNActivityCompletedEventImpl;
import org.activiti.cloud.api.process.model.impl.events.CloudBPMNActivityStartedEventImpl;
import org.activiti.cloud.api.process.model.impl.events.CloudProcessCancelledEventImpl;
import org.activiti.cloud.api.process.model.impl.events.CloudProcessCompletedEventImpl;
import org.activiti.cloud.api.process.model.impl.events.CloudProcessStartedEventImpl;
import org.activiti.cloud.api.task.model.events.CloudTaskAssignedEvent;
import org.activiti.cloud.api.task.model.events.CloudTaskCancelledEvent;
import org.activiti.cloud.api.task.model.impl.events.CloudTaskAssignedEventImpl;
import org.activiti.cloud.api.task.model.impl.events.CloudTaskCancelledEventImpl;
import org.activiti.cloud.api.task.model.impl.events.CloudTaskCompletedEventImpl;
import org.activiti.cloud.api.task.model.impl.events.CloudTaskCreatedEventImpl;
import org.activiti.cloud.services.audit.jpa.repository.EventsRepository;
import org.activiti.cloud.starters.test.MyProducer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.hateoas.PagedResources;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
@TestPropertySource("classpath:application.properties")
public class AuditServiceIT {

    @Autowired
    private EventsRestTemplate eventsRestTemplate;

    @Autowired
    private EventsRepository repository;

    @Autowired
    private MyProducer producer;



    @Before
    public void setUp() {
        repository.deleteAll();
    }

    @Test
    public void findAllShouldReturnAllAvailableEvents() {
        //given
        List<CloudRuntimeEvent> coveredEvents = getTestEvents();
        producer.send(coveredEvents.toArray(new CloudRuntimeEvent[coveredEvents.size()]));

        await().untilAsserted(() -> {

            //when
            ResponseEntity<PagedResources<CloudRuntimeEvent>> eventsPagedResources = eventsRestTemplate.executeFindAll();

            //then
            Collection<CloudRuntimeEvent> retrievedEvents = eventsPagedResources.getBody().getContent();
            assertThat(retrievedEvents).hasSameSizeAs(coveredEvents);
            for (CloudRuntimeEvent coveredEvent : coveredEvents) {

                assertThat(retrievedEvents)
                        .extracting(
                                CloudRuntimeEvent::getEventType,
                                CloudRuntimeEvent::getServiceName,
                                CloudRuntimeEvent::getServiceVersion)
                        .contains(tuple(coveredEvent.getEventType(),
                                        coveredEvent.getServiceName(),
                                        coveredEvent.getServiceVersion()));
            }
        });
    }

    @Test
    public void shouldBeAbleToFilterOnProcessInstanceId() {
        //given
        List<CloudRuntimeEvent> coveredEvents = getTestEvents();
        producer.send(coveredEvents.toArray(new CloudRuntimeEvent[coveredEvents.size()]));

        await().untilAsserted(() -> {

            //when
            ResponseEntity<PagedResources<CloudRuntimeEvent>> eventsPagedResources = eventsRestTemplate.executeFind(Collections.singletonMap("processInstanceId",
                                                                                                                                             "4"));

            //then
            Collection<CloudRuntimeEvent> retrievedEvents = eventsPagedResources.getBody().getContent();
            assertThat(retrievedEvents).hasSize(2);
            for(CloudRuntimeEvent event : retrievedEvents) {
                CloudBPMNActivityEvent cloudBPMNActivityEvent = (CloudBPMNActivityEvent) event;
                assertThat(cloudBPMNActivityEvent.getProcessInstanceId()).isEqualTo("4");
            }
        });
    }


    @Test
    public void shouldBeAbleToFilterOnProcessInstanceIdAndEventType() {
        //given
        List<CloudRuntimeEvent> coveredEvents = getTestEvents();
        producer.send(coveredEvents.toArray(new CloudRuntimeEvent[coveredEvents.size()]));


       await().untilAsserted(() -> {

            //when
            Map<String, Object> filters = new HashMap<>();
            filters.put("processInstanceId","4");
            filters.put("eventType", BPMNActivityEvent.ActivityEvents.ACTIVITY_STARTED.name());
            ResponseEntity<PagedResources<CloudRuntimeEvent>> eventsPagedResources = eventsRestTemplate.executeFind(filters);

            //then
            Collection<CloudRuntimeEvent> retrievedEvents = eventsPagedResources.getBody().getContent();
            assertThat(retrievedEvents).hasSize(2);
            for(CloudRuntimeEvent event : retrievedEvents) {
                CloudBPMNActivityEvent cloudBPMNActivityEvent = (CloudBPMNActivityStartedEvent) event;
                assertThat(cloudBPMNActivityEvent.getEventType()).isEqualTo(BPMNActivityEvent.ActivityEvents.ACTIVITY_STARTED);
            }
        });
    }

    @Test
    public void shouldGetEventsForACancelledTask() {
        //given
        List<CloudRuntimeEvent> coveredEvents = getTaskCancelledEvents();
        producer.send(coveredEvents.toArray(new CloudRuntimeEvent[coveredEvents.size()]));

        await().untilAsserted(() -> {

            //when
            Map<String, Object> filters = new HashMap<>();
            filters.put("entityId","1234-abc-5678-def");

            ResponseEntity<PagedResources<CloudRuntimeEvent>> eventsPagedResources = eventsRestTemplate.executeFind(filters);

            //then
            Collection<CloudRuntimeEvent> retrievedEvents = eventsPagedResources.getBody().getContent();
            assertThat(retrievedEvents).hasSize(3);
            for(CloudRuntimeEvent e : retrievedEvents){
                assertThat(e.getEntityId()).isEqualTo("1234-abc-5678-def");
            }

        });
    }


    @Test
    public void shouldBeAbleToFilterOnEventType() {
        //given
        List<CloudRuntimeEvent> coveredEvents = getTestEvents();
        producer.send(coveredEvents.toArray(new CloudRuntimeEvent[coveredEvents.size()]));

        await().untilAsserted(() -> {

            //when
            ResponseEntity<PagedResources<CloudRuntimeEvent>> eventsPagedResources = eventsRestTemplate.executeFind(Collections.singletonMap("eventType",
                                                                                                                                             TaskRuntimeEvent.TaskEvents.TASK_ASSIGNED.name()));

            //then
            Collection<CloudRuntimeEvent> retrievedEvents = eventsPagedResources.getBody().getContent();
            assertThat(retrievedEvents).hasSize(1);
            CloudTaskAssignedEvent cloudTaskAssignedEvent = (CloudTaskAssignedEvent) retrievedEvents.iterator().next();
            assertThat(cloudTaskAssignedEvent.getEventType()).isEqualTo(TaskRuntimeEvent.TaskEvents.TASK_ASSIGNED);
            assertThat(cloudTaskAssignedEvent.getEntity().getProcessDefinitionId()).isEqualTo("27");
            assertThat(cloudTaskAssignedEvent.getEntity().getProcessInstanceId()).isEqualTo("46");
        });
    }

    @Test
    public void shouldBeAbleToFilterOnEventTypeActivitiStarted() {
        //given
        List<CloudRuntimeEvent> coveredEvents = getTestEvents();
        producer.send(coveredEvents.toArray(new CloudRuntimeEvent[coveredEvents.size()]));

        await().untilAsserted(() -> {

            //when
            ResponseEntity<PagedResources<CloudRuntimeEvent>> eventsPagedResources = eventsRestTemplate.executeFind(Collections.singletonMap("eventType",
                                                                                                                                             BPMNActivityEvent.ActivityEvents.ACTIVITY_STARTED.name()));

            //then
            Collection<CloudRuntimeEvent> retrievedEvents = eventsPagedResources.getBody().getContent();
            assertThat(retrievedEvents).hasSize(3);
            for(CloudRuntimeEvent event : retrievedEvents) {
                CloudBPMNActivityEvent cloudBPMNActivityEvent = (CloudBPMNActivityStartedEvent) event;
                assertThat(cloudBPMNActivityEvent.getEventType()).isEqualTo(BPMNActivityEvent.ActivityEvents.ACTIVITY_STARTED);
            }
        });
    }

    @Test
    public void shouldBeAbleToFilterOnEventEntityId() {
        //given
        List<CloudRuntimeEvent> coveredEvents = getTestEvents();
        producer.send(coveredEvents.toArray(new CloudRuntimeEvent[coveredEvents.size()]));


       await().untilAsserted(() -> {

            //when

            ResponseEntity<PagedResources<CloudRuntimeEvent>> eventsPagedResources = eventsRestTemplate.executeFind(Collections.singletonMap("entityId",
                                                                                                                                             "1234-abc-5678-def"));
            //then
            Collection<CloudRuntimeEvent> retrievedEvents = eventsPagedResources.getBody().getContent();
            assertThat(retrievedEvents).hasSize(4);
            for(CloudRuntimeEvent event : retrievedEvents) {

                assertThat(event.getEntityId()).isEqualTo("1234-abc-5678-def");
            }
       });
    }

    @Test
    public void shouldBeAbleToFilterOnEventTypeTaskCancelled() {
        //given
        List<CloudRuntimeEvent> coveredEvents = getTestEvents();
        producer.send(coveredEvents.toArray(new CloudRuntimeEvent[coveredEvents.size()]));

        await().untilAsserted(() -> {

            //when
            ResponseEntity<PagedResources<CloudRuntimeEvent>> eventsPagedResources = eventsRestTemplate.executeFind(Collections.singletonMap("eventType",
                                                                                                                                             TaskRuntimeEvent.TaskEvents.TASK_CANCELLED.name()));

            //then
            Collection<CloudRuntimeEvent> retrievedEvents = eventsPagedResources.getBody().getContent();
            assertThat(retrievedEvents).hasSize(1);
            CloudTaskCancelledEvent cloudBPMNTaskCancelled = (CloudTaskCancelledEvent) retrievedEvents.iterator().next();
            assertThat(cloudBPMNTaskCancelled.getEventType()).isEqualTo(TaskRuntimeEvent.TaskEvents.TASK_CANCELLED);
            assertThat(cloudBPMNTaskCancelled.getEntityId()).isEqualTo("1234-abc-5678-def");
            assertThat(cloudBPMNTaskCancelled.getEntity()).isInstanceOf(Task.class);
            assertThat(((Task)cloudBPMNTaskCancelled.getEntity()).getId()).isEqualTo("1234-abc-5678-def");

        });
    }

    @Test
    public void findByIdShouldReturnTheEventIdentifiedByTheGivenId() {
        //given
        CloudRuntimeEvent[] events = new CloudRuntimeEvent[1];

        BPMNActivityImpl bpmnActivityStarted = new BPMNActivityImpl();
        bpmnActivityStarted.setActivityName("first step");
        String eventId = "ActivityStartedEventId" + UUID.randomUUID().toString();
        CloudBPMNActivityStartedEventImpl cloudBPMNActivityStartedEvent = new CloudBPMNActivityStartedEventImpl(eventId,
                                                                                                                System.currentTimeMillis(),
                                                                                                                bpmnActivityStarted,
                                                                                                                "3",
                                                                                                                "4");


        events[0] = cloudBPMNActivityStartedEvent;

        producer.send(events);

        await().untilAsserted(() -> {

            //when
            ResponseEntity<CloudRuntimeEvent> responseEntity = eventsRestTemplate.executeFindById(eventId);

            //then
            CloudRuntimeEvent event = responseEntity.getBody();

            assertThat(event).isInstanceOf(CloudBPMNActivityStartedEvent.class);

            CloudBPMNActivityStartedEvent cloudProcessStartedEvent = (CloudBPMNActivityStartedEvent) event;
            assertThat(cloudProcessStartedEvent.getEventType()).isEqualTo(BPMNActivityEvent.ActivityEvents.ACTIVITY_STARTED);
            assertThat(cloudProcessStartedEvent.getProcessDefinitionId()).isEqualTo("3");
            assertThat(cloudProcessStartedEvent.getProcessInstanceId()).isEqualTo("4");
            assertThat(cloudProcessStartedEvent.getEntity().getActivityName()).isEqualTo("first step");
        });
    }

    @Test
    public void unknownEventShouldNotPreventHandlingOfKnownEvents() {
        //given
        CloudRuntimeEvent[] events = new CloudRuntimeEvent[2];

        BPMNActivityImpl bpmnActivityStarted = new BPMNActivityImpl();
        bpmnActivityStarted.setActivityName("first step");
        String eventId = "ActivityStartedEventId" + UUID.randomUUID().toString();
        CloudBPMNActivityStartedEventImpl cloudBPMNActivityStartedEvent = new CloudBPMNActivityStartedEventImpl(eventId,
                                                                                                                System.currentTimeMillis(),
                                                                                                                bpmnActivityStarted,
                                                                                                                "3",
                                                                                                                "4");


        events[0] = cloudBPMNActivityStartedEvent;
        events[1] = new CloudRuntimeEventImpl() {
            @Override
            public Enum<?> getEventType() {
                return IgnoredRuntimeEvent.IgnoredRuntimeEvents.IGNORED;
            }
        };

        producer.send(events);

        await().untilAsserted(() -> {
            //then
            ResponseEntity<PagedResources<CloudRuntimeEvent>> eventsPagedResources = eventsRestTemplate.executeFindAll();
            assertThat(eventsPagedResources.getBody()).isNotEmpty();

            Collection<CloudRuntimeEvent> retrievedEvents = eventsPagedResources.getBody().getContent();
            assertThat(retrievedEvents).hasSize(1);
            CloudRuntimeEvent event = retrievedEvents.iterator().next();
            //when
            assertThat(event).isInstanceOf(CloudBPMNActivityStartedEvent.class);

            CloudBPMNActivityStartedEvent cloudProcessStartedEvent = (CloudBPMNActivityStartedEvent) event;
            assertThat(cloudProcessStartedEvent.getEventType()).isEqualTo(BPMNActivityEvent.ActivityEvents.ACTIVITY_STARTED);
            assertThat(cloudProcessStartedEvent.getProcessDefinitionId()).isEqualTo("3");
            assertThat(cloudProcessStartedEvent.getProcessInstanceId()).isEqualTo("4");
            assertThat(cloudProcessStartedEvent.getEntity().getActivityName()).isEqualTo("first step");
        });
    }

    private List<CloudRuntimeEvent> getTaskCancelledEvents(){
        List<CloudRuntimeEvent> testEvents = new ArrayList<>();
        TaskImpl taskCreated = new TaskImpl("1234-abc-5678-def", "my task",
                                            Task.TaskStatus.CREATED);
        taskCreated.setProcessDefinitionId("proc-def");
        taskCreated.setProcessInstanceId("100");
        CloudTaskCreatedEventImpl cloudTaskCreatedEvent = new CloudTaskCreatedEventImpl("TaskCreatedEventId",
                                                                                        System.currentTimeMillis(),
                                                                                        taskCreated);
        testEvents.add(cloudTaskCreatedEvent);


        TaskImpl taskAssigned = new TaskImpl("1234-abc-5678-def", "my task",
                                             Task.TaskStatus.ASSIGNED);
        taskAssigned.setProcessDefinitionId("proc-def");
        taskAssigned.setProcessInstanceId("100");
        CloudTaskAssignedEventImpl cloudTaskAssignedEvent = new CloudTaskAssignedEventImpl("TaskAssignedEventId",
                                                                                           System.currentTimeMillis(),
                                                                                           taskAssigned);
        testEvents.add(cloudTaskAssignedEvent);

        TaskImpl taskCancelled = new TaskImpl("1234-abc-5678-def", "my task",
                                              Task.TaskStatus.CANCELLED);
        taskCancelled.setProcessDefinitionId("proc-def");
        taskCancelled.setProcessInstanceId("100");
        CloudTaskCancelledEventImpl cloudTaskCancelledEvent = new CloudTaskCancelledEventImpl("TaskCancelledEventId",
                                                                                              System.currentTimeMillis(),
                                                                                              taskCancelled);
        testEvents.add(cloudTaskCancelledEvent);
        return testEvents;

    }

    private List<CloudRuntimeEvent> getTestEvents() {
        List<CloudRuntimeEvent> testEvents = new ArrayList<>();

        BPMNActivityImpl bpmnActivityCancelled = new BPMNActivityImpl();

        CloudBPMNActivityCancelledEventImpl cloudBPMNActivityCancelledEvent = new CloudBPMNActivityCancelledEventImpl("ActivityCancelledEventId",
                                                                                                                      System.currentTimeMillis(),
                                                                                                                      bpmnActivityCancelled,
                                                                                                                      "103",
                                                                                                                      "104",
                                                                                                                      "manually cancelled");


        testEvents.add(cloudBPMNActivityCancelledEvent);

        BPMNActivityImpl bpmnActivityStarted = new BPMNActivityImpl("1", "My Service Task", "Service Task");


        CloudBPMNActivityStartedEventImpl cloudBPMNActivityStartedEvent = new CloudBPMNActivityStartedEventImpl("ActivityStartedEventId",
                                                                                                                System.currentTimeMillis(),
                                                                                                                bpmnActivityStarted,
                                                                                                                "3",
                                                                                                                "4");


        testEvents.add(cloudBPMNActivityStartedEvent);

        BPMNActivityImpl bpmnActivityStarted2 = new BPMNActivityImpl("2", "My User Task", "User Task");


        CloudBPMNActivityStartedEventImpl cloudBPMNActivityStartedEvent2 = new CloudBPMNActivityStartedEventImpl("ActivityStartedEventId2",
                                                                                                                System.currentTimeMillis(),
                                                                                                                 bpmnActivityStarted2,
                                                                                                                 "3",
                                                                                                                 "4");


        testEvents.add(cloudBPMNActivityStartedEvent2);

        BPMNActivityImpl bpmnActivityStarted3 = new BPMNActivityImpl("2", "My User Task", "User Task");


        CloudBPMNActivityStartedEventImpl cloudBPMNActivityStartedEvent3 = new CloudBPMNActivityStartedEventImpl("ActivityStartedEventId3",
                                                                                                                 System.currentTimeMillis(),
                                                                                                                 bpmnActivityStarted3,
                                                                                                                 "3",
                                                                                                                 "5");


        testEvents.add(cloudBPMNActivityStartedEvent3);

        BPMNActivityImpl bpmnActivityCompleted = new BPMNActivityImpl();

        CloudBPMNActivityCompletedEventImpl cloudBPMNActivityCompletedEvent = new CloudBPMNActivityCompletedEventImpl("ActivityCompletedEventId",
                                                                                                                      System.currentTimeMillis(),
                                                                                                                      bpmnActivityCompleted,
                                                                                                                      "23",
                                                                                                                      "42");

        testEvents.add(cloudBPMNActivityCompletedEvent);

        ProcessInstanceImpl processInstanceCompleted = new ProcessInstanceImpl();
        processInstanceCompleted.setId("24");
        processInstanceCompleted.setProcessDefinitionId("43");

        CloudProcessCompletedEventImpl cloudProcessCompletedEvent = new CloudProcessCompletedEventImpl("ProcessCompletedEventId",
                                                                                                       System.currentTimeMillis(),
                                                                                                       processInstanceCompleted);

        testEvents.add(cloudProcessCompletedEvent);

        ProcessInstanceImpl processInstanceCancelled = new ProcessInstanceImpl();
        processInstanceCancelled.setId("124");
        processInstanceCancelled.setProcessDefinitionId("143");

        CloudProcessCancelledEventImpl cloudProcessCancelledEvent = new CloudProcessCancelledEventImpl("ProcessCancelledEventId",
                                                                                                       System.currentTimeMillis(),
                                                                                                       processInstanceCancelled);

        testEvents.add(cloudProcessCancelledEvent);

        ProcessInstanceImpl processInstanceStarted = new ProcessInstanceImpl();
        processInstanceStarted.setId("25");
        processInstanceStarted.setProcessDefinitionId("44");

        CloudProcessStartedEventImpl cloudProcessStartedEvent = new CloudProcessStartedEventImpl("ProcessStartedEventId",
                                                                                                 System.currentTimeMillis(),
                                                                                                 processInstanceStarted);

        testEvents.add(cloudProcessStartedEvent);

        TaskImpl taskAssigned = new TaskImpl("1234-abc-5678-def", "task assigned",
                                             Task.TaskStatus.ASSIGNED);
        taskAssigned.setProcessDefinitionId("27");
        taskAssigned.setProcessInstanceId("46");
        CloudTaskAssignedEventImpl cloudTaskAssignedEvent = new CloudTaskAssignedEventImpl("TaskAssignedEventId",
                                                                                           System.currentTimeMillis(),
                                                                                           taskAssigned);
        testEvents.add(cloudTaskAssignedEvent);

        TaskImpl taskCompleted = new TaskImpl("1234-abc-5678-def", "task completed",
                                              Task.TaskStatus.COMPLETED);
        taskCompleted.setProcessDefinitionId("28");
        taskCompleted.setProcessInstanceId("47");
        CloudTaskCompletedEventImpl cloudTaskCompletedEvent = new CloudTaskCompletedEventImpl("TaskCompletedEventId",
                                                                                              System.currentTimeMillis(),
                                                                                              taskCompleted);
        testEvents.add(cloudTaskCompletedEvent);

        TaskImpl taskCreated = new TaskImpl("1234-abc-5678-def", "task created",
                                            Task.TaskStatus.CREATED);
        taskCreated.setProcessDefinitionId("28");
        taskCreated.setProcessInstanceId("47");
        CloudTaskCreatedEventImpl cloudTaskCreatedEvent = new CloudTaskCreatedEventImpl("TaskCreatedEventId",
                                                                                        System.currentTimeMillis(),
                                                                                        taskCreated);
        testEvents.add(cloudTaskCreatedEvent);


        TaskImpl taskCancelled = new TaskImpl("1234-abc-5678-def", "task cancelled",
                                              Task.TaskStatus.CANCELLED);
        taskCancelled.setProcessDefinitionId("28");
        taskCancelled.setProcessInstanceId("47");
        CloudTaskCancelledEventImpl cloudTaskCancelledEvent = new CloudTaskCancelledEventImpl("TaskCancelledEventId",
                                                                                            System.currentTimeMillis(),
                                                                                            taskCancelled);
        testEvents.add(cloudTaskCancelledEvent);


        return testEvents;
    }
}
