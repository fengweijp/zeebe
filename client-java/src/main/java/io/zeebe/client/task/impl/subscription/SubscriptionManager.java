/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.client.task.impl.subscription;

import io.zeebe.client.ZeebeClient;
import io.zeebe.client.clustering.impl.ClientTopologyManager;
import io.zeebe.client.event.PollableTopicSubscriptionBuilder;
import io.zeebe.client.event.TopicSubscriptionBuilder;
import io.zeebe.client.event.impl.PollableTopicSubscriptionBuilderImpl;
import io.zeebe.client.event.impl.TopicSubscriptionBuilderImpl;
import io.zeebe.client.impl.ZeebeClientImpl;
import io.zeebe.client.impl.data.MsgPackMapper;
import io.zeebe.client.task.PollableTaskSubscriptionBuilder;
import io.zeebe.client.task.TaskSubscriptionBuilder;
import io.zeebe.transport.ClientInputMessageSubscription;
import io.zeebe.transport.RemoteAddress;
import io.zeebe.transport.TransportListener;
import io.zeebe.util.actor.Actor;
import io.zeebe.util.actor.ActorReference;
import io.zeebe.util.actor.ActorScheduler;

public class SubscriptionManager implements TransportListener, Actor
{
    protected final EventAcquisition taskAcquisition;
    protected final EventAcquisition topicSubscriptionAcquisition;
    protected final ClientInputMessageSubscription messageSubscription;
    protected final MsgPackMapper msgPackMapper;
    protected final ClientTopologyManager topologyManager;

    protected final ActorScheduler executorActorScheduler;
    protected final ActorScheduler acquisitionActorScheduler;

    protected ActorReference[] acquisitionActorRefs;
    protected ActorReference[] executorActorRefs;

    protected final int numExecutionThreads;

    protected final EventSubscribers taskSubscribers;
    protected final EventSubscribers topicSubscribers;

    // topic-subscription specific config
    protected final int topicSubscriptionPrefetchCapacity;

    public SubscriptionManager(
            ZeebeClientImpl client,
            int numExecutionThreads,
            int topicSubscriptionPrefetchCapacity)
    {
        this.taskSubscribers = new EventSubscribers();
        this.topicSubscribers = new EventSubscribers();

        this.taskAcquisition = new EventAcquisition("task-acquisition", taskSubscribers);
        this.topicSubscriptionAcquisition = new EventAcquisition("topic-event-acquisition", topicSubscribers);

        final SubscribedEventCollector taskCollector = new SubscribedEventCollector(
                taskAcquisition,
                topicSubscriptionAcquisition,
                client.getMsgPackConverter());
        this.messageSubscription = client.getTransport()
                .openSubscription("event-acquisition", taskCollector)
                .join();

        this.numExecutionThreads = numExecutionThreads;
        this.msgPackMapper = new MsgPackMapper(client.getObjectMapper());

        this.topicSubscriptionPrefetchCapacity = topicSubscriptionPrefetchCapacity;
        this.topologyManager = client.getTopologyManager();
//
//        this.acquisitionActorScheduler = ActorSchedulerBuilder.createDefaultScheduler("acquisition");
//        this.executorActorScheduler = ActorSchedulerBuilder.createDefaultScheduler("executors", numExecutionThreads);
    }

    public void start()
    {
        startAcquisition();
        startExecution();
    }

    public void stop()
    {
        stopAcquisition();
        stopExecution();
    }

    public void close()
    {
        acquisitionActorScheduler.close();
        executorActorScheduler.close();
    }

    protected void startAcquisition()
    {
        if (acquisitionActorRefs == null)
        {
            acquisitionActorRefs = new ActorReference[3];

            acquisitionActorRefs[0] = acquisitionActorScheduler.schedule(this);
            acquisitionActorRefs[1] = acquisitionActorScheduler.schedule(taskAcquisition);
            acquisitionActorRefs[2] = acquisitionActorScheduler.schedule(topicSubscriptionAcquisition);
        }
    }

    protected void stopAcquisition()
    {
        for (int i = 0; i < acquisitionActorRefs.length; i++)
        {
            acquisitionActorRefs[i].close();
        }

        acquisitionActorRefs = null;
    }

    protected void startExecution()
    {
        if (executorActorRefs == null)
        {
            executorActorRefs = new ActorReference[numExecutionThreads * 2];

            for (int i = 0; i < executorActorRefs.length; i += 2)
            {
                executorActorRefs[i] = executorActorScheduler.schedule(new SubscriptionExecutor(taskSubscribers));
                executorActorRefs[i + 1] = executorActorScheduler.schedule(new SubscriptionExecutor(topicSubscribers));
            }
        }
    }

    protected void stopExecution()
    {
        for (int i = 0; i < executorActorRefs.length; i++)
        {
            executorActorRefs[i].close();
        }

        executorActorRefs = null;
    }

    public void closeAllSubscribers()
    {
        this.taskSubscribers.closeAllGroups();
        this.topicSubscribers.closeAllGroups();
    }

    public TaskSubscriptionBuilder newTaskSubscription(ZeebeClient client, String topic)
    {
        return new TaskSubscriptionBuilderImpl(client, topologyManager, topic, taskAcquisition, msgPackMapper);
    }

    public PollableTaskSubscriptionBuilder newPollableTaskSubscription(ZeebeClient client, String topic)
    {
        return new PollableTaskSubscriptionBuilderImpl(client, topologyManager, topic, taskAcquisition, msgPackMapper);
    }

    public TopicSubscriptionBuilder newTopicSubscription(ZeebeClient client, String topic)
    {
        return new TopicSubscriptionBuilderImpl(client, topologyManager, topic, topicSubscriptionAcquisition, msgPackMapper, topicSubscriptionPrefetchCapacity);
    }

    public PollableTopicSubscriptionBuilder newPollableTopicSubscription(ZeebeClient client, String topic)
    {
        return new PollableTopicSubscriptionBuilderImpl(client, topologyManager, topic, topicSubscriptionAcquisition, topicSubscriptionPrefetchCapacity);
    }

    @Override
    public void onConnectionEstablished(RemoteAddress remoteAddress)
    {

    }

    @Override
    public void onConnectionClosed(RemoteAddress remoteAddress)
    {
        // doing this async in the context of the acquisition agent. This actor owns
        // the subscriptions and can reliably determine those that are connected
        // to the given remote address
        taskAcquisition.reopenSubscriptionsForRemoteAsync(remoteAddress);
        topicSubscriptionAcquisition.reopenSubscriptionsForRemoteAsync(remoteAddress);
    }

    @Override
    public int doWork() throws Exception
    {
        return messageSubscription.poll();
    }
}
