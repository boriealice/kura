/*******************************************************************************
 * Copyright (c) 2011, 2023 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Eurotech
 *******************************************************************************/
package org.eclipse.kura.core.data;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.eclipse.kura.KuraConnectException;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.KuraNotConnectedException;
import org.eclipse.kura.KuraStoreCapacityReachedException;
import org.eclipse.kura.KuraStoreException;
import org.eclipse.kura.KuraTooManyInflightMessagesException;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.configuration.ConfigurationService;
import org.eclipse.kura.core.data.store.MessageStoreState;
import org.eclipse.kura.core.db.H2DbMessageStoreImpl;
import org.eclipse.kura.core.internal.data.TokenBucket;
import org.eclipse.kura.data.DataService;
import org.eclipse.kura.data.DataTransportService;
import org.eclipse.kura.data.DataTransportToken;
import org.eclipse.kura.data.listener.DataServiceListener;
import org.eclipse.kura.data.transport.listener.DataTransportListener;
import org.eclipse.kura.db.H2DbService;
import org.eclipse.kura.message.store.StoredMessage;
import org.eclipse.kura.message.store.provider.MessageStore;
import org.eclipse.kura.message.store.provider.MessageStoreProvider;
import org.eclipse.kura.status.CloudConnectionStatusComponent;
import org.eclipse.kura.status.CloudConnectionStatusEnum;
import org.eclipse.kura.status.CloudConnectionStatusService;
import org.eclipse.kura.watchdog.CriticalComponent;
import org.eclipse.kura.watchdog.WatchdogService;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.ComponentException;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataServiceImpl implements DataService, DataTransportListener, ConfigurableComponent,
        CloudConnectionStatusComponent, CriticalComponent, AutoConnectStrategy.ConnectionManager {

    private static final int RECONNECTION_MIN_DELAY = 1;

    private static final Logger logger = LoggerFactory.getLogger(DataServiceImpl.class);

    private static final int TRANSPORT_TASK_TIMEOUT = 1; // In seconds

    private DataServiceOptions dataServiceOptions;

    private DataTransportService dataTransportService;
    private DataServiceListenerS dataServiceListeners;

    protected ScheduledExecutorService connectionMonitorExecutor;
    private ScheduledFuture<?> connectionMonitorFuture;

    // A dedicated executor for the publishing task
    private ExecutorService publisherExecutor;

    private MessageStoreState store;

    private Map<DataTransportToken, Integer> inFlightMsgIds;

    private ScheduledExecutorService congestionExecutor;
    private ScheduledFuture<?> congestionFuture;

    private CloudConnectionStatusService cloudConnectionStatusService;
    private CloudConnectionStatusEnum notificationStatus = CloudConnectionStatusEnum.OFF;

    private TokenBucket throttle;

    private final Lock lock = new ReentrantLock();
    private boolean notifyPending;
    private final Condition lockCondition = this.lock.newCondition();

    private final AtomicBoolean publisherEnabled = new AtomicBoolean();

    private ServiceTracker<Object, Object> dbServiceTracker;
    private ComponentContext componentContext;

    private WatchdogService watchdogService;

    private AtomicInteger connectionAttempts;

    private Optional<AutoConnectStrategy> autoConnectStrategy = Optional.empty();

    private final Random random = new SecureRandom();

    // ----------------------------------------------------------------
    //
    // Activation APIs
    //
    // ----------------------------------------------------------------

    protected void activate(ComponentContext componentContext, Map<String, Object> properties) {
        String pid = (String) properties.get(ConfigurationService.KURA_SERVICE_PID);
        logger.info("Activating {}...", pid);

        this.componentContext = componentContext;

        this.dataServiceOptions = new DataServiceOptions(properties);

        this.connectionMonitorExecutor = Executors.newSingleThreadScheduledExecutor();
        this.publisherExecutor = Executors.newSingleThreadExecutor();
        this.congestionExecutor = Executors.newSingleThreadScheduledExecutor();

        createThrottle();
        submitPublishingWork();

        restartDbServiceTracker(this.dataServiceOptions.getDbServiceInstancePid());

        this.dataServiceListeners = new DataServiceListenerS(componentContext);

        // Register the component in the CloudConnectionStatus Service
        this.cloudConnectionStatusService.register(this);

        this.dataTransportService.addDataTransportListener(this);

        createAutoConnectStrategy();
    }

    private void restartDbServiceTracker(String kuraServicePid) {
        stopDbServiceTracker();
        try {
            final Filter filter = FrameworkUtil
                    .createFilter("(" + ConfigurationService.KURA_SERVICE_PID + "=" + kuraServicePid + ")");
            this.dbServiceTracker = new ServiceTracker<>(this.componentContext.getBundleContext(), filter,
                    new ServiceTrackerCustomizer<Object, Object>() {

                        @Override
                        public Object addingService(ServiceReference<Object> reference) {
                            logger.info("Message store instance found");
                            Object service = DataServiceImpl.this.componentContext
                                    .getBundleContext()
                                    .getService(reference);

                            if (service instanceof MessageStoreProvider) {
                                setMessageStoreProvider((MessageStoreProvider) service);
                            } else if (service instanceof H2DbService) {
                                setH2DbService((H2DbService) service);
                            } else {
                                DataServiceImpl.this.componentContext
                                        .getBundleContext().ungetService(reference);
                                return null;
                            }

                            return service;
                        }

                        @Override
                        public void modifiedService(ServiceReference<Object> reference,
                                Object service) {
                            logger.info("Message store instance updated, recreating table if needed...");
                            synchronized (DataServiceImpl.this) {
                                DataServiceImpl.this.store.update(
                                        DataServiceImpl.this.dataServiceOptions);
                            }
                        }

                        @Override
                        public void removedService(ServiceReference<Object> reference,
                                Object service) {
                            logger.info("Message store instance removed");
                            unsetMessageStoreProvider();
                            DataServiceImpl.this.componentContext.getBundleContext().ungetService(reference);
                        }
                    });
            this.dbServiceTracker.open();
        } catch (InvalidSyntaxException e) {
            throw new ComponentException(e);
        }
    }

    private void stopDbServiceTracker() {
        if (this.dbServiceTracker != null) {
            this.dbServiceTracker.close();
            this.dbServiceTracker = null;
        }
    }

    private synchronized void startDbStore() {
        try {
            // The initial list of in-flight messages
            List<StoredMessage> inFlightMsgs = this.store.getMessageStore().getInFlightMessages();

            // The map associating a DataTransportToken with a message ID
            this.inFlightMsgIds = new ConcurrentHashMap<>();

            if (inFlightMsgs != null) {
                for (StoredMessage message : inFlightMsgs) {

                    final Optional<DataTransportToken> token = message.getDataTransportToken();

                    if (!token.isPresent()) {
                        logger.warn("In-flight message has no associated DataTransportToken");
                        continue;
                    }

                    this.inFlightMsgIds.put(token.get(), message.getId());

                    logger.debug("Restored in-fligh messages from store. Topic: {}, ID: {}, MQTT message ID: {}",
                            message.getTopic(), message.getId(), token.get().getMessageId());
                }
            }
        } catch (KuraStoreException e) {
            logger.error("Failed to start store", e);
        }
    }

    public synchronized void updated(Map<String, Object> properties) {
        logger.info("Updating {}...", properties.get(ConfigurationService.KURA_SERVICE_PID));

        shutdownAutoConnectStrategy();

        final String oldDbServicePid = this.dataServiceOptions.getDbServiceInstancePid();

        this.dataServiceOptions = new DataServiceOptions(properties);

        createThrottle();

        final String currentDbServicePid = this.dataServiceOptions.getDbServiceInstancePid();

        if (oldDbServicePid.equals(currentDbServicePid)) {
            if (this.store != null) {
                this.store.update(this.dataServiceOptions);
            }
        } else {
            restartDbServiceTracker(currentDbServicePid);
        }

        createAutoConnectStrategy();
    }

    protected void deactivate(ComponentContext componentContext) {
        logger.info("Deactivating {}...", this.dataServiceOptions.getKuraServicePid());

        shutdownAutoConnectStrategy();
        this.connectionMonitorExecutor.shutdownNow();

        this.congestionExecutor.shutdownNow();

        disconnect();

        // Await termination of the publisher executor tasks
        try {
            // Waits to publish latest messages e.g. disconnect message
            Thread.sleep(TRANSPORT_TASK_TIMEOUT * 1000L);

            // Clean publisher thread shutdown
            this.publisherEnabled.set(false);
            signalPublisher();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("Interrupted", e);
        }
        this.publisherExecutor.shutdownNow();

        this.dataTransportService.removeDataTransportListener(this);

        this.store.shutdown();

        stopDbServiceTracker();
    }

    // ----------------------------------------------------------------
    //
    // Dependencies
    //
    // ----------------------------------------------------------------

    public void setDataTransportService(DataTransportService dataTransportService) {
        this.dataTransportService = dataTransportService;
    }

    public void unsetDataTransportService(DataTransportService dataTransportService) {
        this.dataTransportService = null;
    }

    public synchronized void setMessageStoreProvider(MessageStoreProvider messageStoreProvider) {
        this.store = new MessageStoreState(messageStoreProvider, this.dataServiceOptions);
        startDbStore();
        signalPublisher();
    }

    public synchronized void unsetMessageStoreProvider() {
        disconnect();
        this.store.shutdown();
        this.store = null;
    }

    public synchronized void setH2DbService(H2DbService dbService) {
        setMessageStoreProvider(name -> new H2DbMessageStoreImpl(
                dbService, name));
    }

    public synchronized void unsetH2DbService(H2DbService dbService) {
        unsetMessageStoreProvider();
    }

    public void setCloudConnectionStatusService(CloudConnectionStatusService cloudConnectionStatusService) {
        this.cloudConnectionStatusService = cloudConnectionStatusService;
    }

    public void unsetCloudConnectionStatusService(CloudConnectionStatusService cloudConnectionStatusService) {
        this.cloudConnectionStatusService = null;
    }

    public void setWatchdogService(WatchdogService watchdogService) {
        this.watchdogService = watchdogService;
    }

    public void unsetWatchdogService(WatchdogService watchdogService) {
        this.watchdogService = null;
    }

    @Override
    public void addDataServiceListener(DataServiceListener listener) {
        this.dataServiceListeners.add(listener);
    }

    @Override
    public void removeDataServiceListener(DataServiceListener listener) {
        this.dataServiceListeners.remove(listener);
    }

    @Override
    public void onConnectionEstablished(boolean newSession) {

        logger.info("Notified connected");
        this.cloudConnectionStatusService.updateStatus(this, CloudConnectionStatusEnum.ON);

        // On a new session all messages the were in-flight in the previous session
        // would be lost and never confirmed by the DataPublisherService.
        //
        // If the DataPublisherService is configured with Clean Start flag set to true,
        // then the session and connection boundaries are the same.
        // Otherwise, a session spans multiple connections as far as the client connects
        // to the same broker instance with the same client ID.
        //
        // We have two options here:
        // Forget them.
        // Unpublish them so they will be republished on the new session.
        //
        // The latter has the potential drawback that duplicates can be generated with
        // any QoS.
        // This can occur for example if the DataPublisherService is connecting with a
        // different client ID
        // or to a different broker URL resolved to the same broker instance.
        //
        // Also note that unpublished messages will be republished accordingly to their
        // original priority. Thus a message reordering may occur too.
        // Even if we artificially upgraded the priority of unpublished messages to -1
        // so to
        // republish them first, their relative order would not necessarily match the
        // order
        // in the DataPublisherService persistence.

        if (newSession) {
            if (this.dataServiceOptions.isPublishInFlightMessages()) {
                logger.info("New session established. Unpublishing all in-flight messages. Disregarding the QoS level, "
                        + "this may cause duplicate messages.");
                try {
                    this.store.getMessageStore().unpublishAllInFlighMessages();
                    this.inFlightMsgIds.clear();
                } catch (KuraStoreException e) {
                    logger.error("Failed to unpublish in-flight messages", e);
                }
            } else {
                logger.info("New session established. Dropping all in-flight messages.");
                try {
                    this.store.getMessageStore().dropAllInFlightMessages();
                    this.inFlightMsgIds.clear();
                } catch (KuraStoreException e) {
                    logger.error("Failed to drop in-flight messages", e);
                }
            }
        }

        // Notify the listeners
        this.dataServiceListeners.onConnectionEstablished();

        signalPublisher();
    }

    @Override
    public void onDisconnecting() {
        logger.info("Notified disconnecting");

        // Notify the listeners
        this.dataServiceListeners.onDisconnecting();
    }

    @Override
    public void onDisconnected() {
        logger.info("Notified disconnected");
        this.cloudConnectionStatusService.updateStatus(this, CloudConnectionStatusEnum.OFF);

        // Notify the listeners
        this.dataServiceListeners.onDisconnected();
    }

    @Override
    public void onConfigurationUpdating(boolean wasConnected) {
        logger.info("Notified DataTransportService configuration updating...");
        this.dataTransportService.disconnect(0);
    }

    @Override
    public void onConfigurationUpdated(boolean wasConnected) {
        logger.info("Notified DataTransportService configuration updated.");
        shutdownAutoConnectStrategy();
        createAutoConnectStrategy();
        if (!this.autoConnectStrategy.isPresent() && wasConnected) {
            try {
                connect();
            } catch (KuraConnectException e) {
                logger.error("Error during re-connect after configuration update.", e);
            }
        }
    }

    @Override
    public void onConnectionLost(Throwable cause) {
        logger.info("connectionLost");

        // Notify the listeners
        this.dataServiceListeners.onConnectionLost(cause);
    }

    @Override
    public void onMessageArrived(String topic, byte[] payload, int qos, boolean retained) {

        logger.debug("Message arrived on topic: {}", topic);

        // Notify the listeners
        this.dataServiceListeners.onMessageArrived(topic, payload, qos, retained);

        signalPublisher();
    }

    @Override
    // It's very important that the publishInternal and messageConfirmed methods are
    // synchronized
    public synchronized void onMessageConfirmed(DataTransportToken token) {

        logger.debug("Confirmed message with MQTT message ID: {} on session ID: {}", token.getMessageId(),
                token.getSessionId());

        Integer messageId = this.inFlightMsgIds.remove(token);
        if (messageId == null) {
            logger.info(
                    "Confirmed message published with MQTT message ID: {} not tracked in the map of in-flight messages",
                    token.getMessageId());
        } else {

            Optional<StoredMessage> confirmedMessage = Optional.empty();
            try {
                logger.info("Confirmed message ID: {} to store", messageId);
                this.store.getMessageStore().markAsConfirmed(messageId);
                confirmedMessage = this.store.getMessageStore().get(messageId);
            } catch (KuraStoreException e) {
                logger.error("Cannot confirm message to store", e);
            }

            // Notify the listeners
            if (confirmedMessage.isPresent()) {
                String topic = confirmedMessage.get().getTopic();
                this.dataServiceListeners.onMessageConfirmed(messageId, topic);
            } else {
                logger.error("Confirmed Message with ID {} could not be loaded from the DataStore.", messageId);
            }
        }

        if (this.inFlightMsgIds.size() < this.dataServiceOptions.getMaxInFlightMessages()) {
            handleInFlightDecongestion();
        }

        signalPublisher();
    }

    @Override
    public void connect() throws KuraConnectException {
        shutdownAutoConnectStrategy();
        if (this.store == null) {
            throw new KuraConnectException("Message store instance not attached, not connecting");
        }

        if (!this.dataTransportService.isConnected()) {
            this.dataTransportService.connect();
        }
    }

    @Override
    public boolean isConnected() {
        return this.dataTransportService.isConnected();
    }

    @Override
    public boolean isAutoConnectEnabled() {
        return this.dataServiceOptions.isAutoConnect();
    }

    @Override
    public int getRetryInterval() {
        return this.dataServiceOptions.getConnectDelay();
    }

    @Override
    public void disconnect(long quiesceTimeout) {
        shutdownAutoConnectStrategy();
        this.dataTransportService.disconnect(quiesceTimeout);
    }

    @Override
    public void subscribe(String topic, int qos) throws KuraException {
        this.dataTransportService.subscribe(topic, qos);
    }

    @Override
    public void unsubscribe(String topic) throws KuraException {
        this.dataTransportService.unsubscribe(topic);
    }

    @Override
    public int publish(String topic, byte[] payload, int qos, boolean retain, int priority) throws KuraStoreException {

        if (this.autoConnectStrategy.isPresent()) {
            this.autoConnectStrategy.get().onPublishRequested(topic, payload, qos, retain, priority);
        }

        logger.info("Storing message on topic: {}, priority: {}", topic, priority);

        final MessageStore currentStore = this.store.getMessageStore();
        final int messageId;

        synchronized (currentStore) {
            // Priority 0 are used for life-cycle messages like birth and death
            // certificates.
            // Priority 1 are used for remove management by Cloudlet applications.
            // For those messages, bypass the maximum message count check of the DB cache.
            // We want to publish those message even if the DB is full, so allow their
            // storage.
            if (priority != 0 && priority != 1) {
                int count = currentStore.getMessageCount();
                logger.debug("Store message count: {}", count);
                if (count >= this.dataServiceOptions.getStoreCapacity()) {
                    logger.error("Store capacity exceeded");
                    throw new KuraStoreCapacityReachedException("Store capacity exceeded");
                }
            }

            messageId = currentStore.store(topic, payload, qos, retain, priority);
            logger.info("Stored message on topic: {}, priority: {}", topic, priority);
        }

        signalPublisher();

        return messageId;
    }

    @Override
    public List<Integer> getUnpublishedMessageIds(String topicRegex) throws KuraStoreException {
        List<StoredMessage> messages = this.store.getMessageStore().getUnpublishedMessages();
        return buildMessageIds(messages, topicRegex);
    }

    @Override
    public List<Integer> getInFlightMessageIds(String topicRegex) throws KuraStoreException {
        List<StoredMessage> messages = this.store.getMessageStore().getInFlightMessages();
        return buildMessageIds(messages, topicRegex);
    }

    @Override
    public List<Integer> getDroppedInFlightMessageIds(String topicRegex) throws KuraStoreException {
        List<StoredMessage> messages = this.store.getMessageStore().getDroppedMessages();
        return buildMessageIds(messages, topicRegex);
    }

    private void signalPublisher() {
        this.lock.lock();
        this.notifyPending = true;
        this.lockCondition.signal();
        this.lock.unlock();
    }

    private void createAutoConnectStrategy() {
        if (!this.dataServiceOptions.isAutoConnect()) {
            return;
        }

        final Optional<AutoConnectStrategy> currentStrategy = this.autoConnectStrategy;

        if (currentStrategy.isPresent()) {
            return;
        }

        final Optional<CronExpression> schedule = this.dataServiceOptions.getConnectionScheduleExpression();

        final AutoConnectStrategy strategy;

        if (!this.dataServiceOptions.isConnectionScheduleEnabled() || !schedule.isPresent()) {
            strategy = new AlwaysConnectedStrategy(this);
        } else {
            strategy = new ScheduleStrategy(schedule.get(), this.dataServiceOptions, this);
        }

        this.autoConnectStrategy = Optional.of(strategy);
        this.dataServiceListeners.prepend(strategy);

    }

    private void shutdownAutoConnectStrategy() {
        final Optional<AutoConnectStrategy> currentStrategy = this.autoConnectStrategy;

        if (currentStrategy.isPresent()) {
            currentStrategy.get().shutdown();
            this.dataServiceListeners.remove(currentStrategy.get());
            this.autoConnectStrategy = Optional.empty();
        }
    }

    private void startConnectionMonitorTask() {
        if (this.connectionMonitorFuture != null && !this.connectionMonitorFuture.isDone()) {
            logger.info("Reconnect task already running");
        }

        //
        // Establish a reconnect Thread based on the reconnect interval
        boolean autoConnect = this.dataServiceOptions.isAutoConnect();
        int reconnectInterval = this.dataServiceOptions.getConnectDelay();
        if (autoConnect) {

            if (this.dataServiceOptions.isConnectionRecoveryEnabled()) {
                this.watchdogService.registerCriticalComponent(this);
                this.watchdogService.checkin(this);
                this.connectionAttempts = new AtomicInteger(0);
            }

            // Change notification status to slow blinking when connection is expected to
            // happen in the future
            this.cloudConnectionStatusService.updateStatus(this, CloudConnectionStatusEnum.SLOW_BLINKING);
            // add a delay on the reconnect
            int maxDelay = reconnectInterval / 5;
            maxDelay = maxDelay > 0 ? maxDelay : 1;

            int initialDelay = Math.max(this.random.nextInt(maxDelay), RECONNECTION_MIN_DELAY);

            logger.info("Starting reconnect task with initial delay {}", initialDelay);
            this.connectionMonitorFuture = this.connectionMonitorExecutor.scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    Thread.currentThread().setName("DataServiceImpl:ReconnectTask:"
                            + DataServiceImpl.this.dataServiceOptions.getKuraServicePid());
                    boolean connected = false;
                    try {
                        if (DataServiceImpl.this.store == null) {
                            logger.warn("Message store instance not attached, not connecting");
                            return;
                        }
                        logger.info("Connecting...");
                        if (DataServiceImpl.this.dataTransportService.isConnected()) {
                            logger.info("Already connected. Reconnect task will be terminated.");

                        } else {
                            DataServiceImpl.this.dataTransportService.connect();
                            logger.info("Connected. Reconnect task will be terminated.");
                        }
                        connected = true;
                    } catch (KuraConnectException e) {
                        logger.warn("Connect failed", e);

                        if (DataServiceImpl.this.dataServiceOptions.isConnectionRecoveryEnabled()) {
                            if (isAuthenticationException(e) || DataServiceImpl.this.connectionAttempts
                                    .getAndIncrement() < DataServiceImpl.this.dataServiceOptions
                                            .getRecoveryMaximumAllowedFailures()) {
                                logger.info("Checkin done.");
                                DataServiceImpl.this.watchdogService.checkin(DataServiceImpl.this);
                            } else {
                                logger.info("Maximum number of connection attempts reached. Requested reboot...");
                            }
                        }
                    } finally {
                        if (connected) {
                            unregisterAsCriticalComponent();
                            // Throwing an exception will suppress subsequent executions of this periodic
                            // task.
                            throw new RuntimeException("Connected. Reconnect task will be terminated.");
                        }
                    }
                }

                private boolean isAuthenticationException(KuraConnectException e) {
                    boolean authenticationException = false;
                    if (e.getCause() instanceof MqttException) {
                        MqttException mqttException = (MqttException) e.getCause();
                        if (mqttException.getReasonCode() == MqttException.REASON_CODE_FAILED_AUTHENTICATION
                                || mqttException.getReasonCode() == MqttException.REASON_CODE_INVALID_CLIENT_ID
                                || mqttException.getReasonCode() == MqttException.REASON_CODE_NOT_AUTHORIZED) {
                            logger.info("Authentication exception encountered.");
                            authenticationException = true;
                        }
                    }
                    return authenticationException;
                }
            }, initialDelay, reconnectInterval, TimeUnit.SECONDS);
        } else {
            // Change notification status to off. Connection is not expected to happen in
            // the future
            this.cloudConnectionStatusService.updateStatus(this, CloudConnectionStatusEnum.OFF);
            unregisterAsCriticalComponent();
        }
    }

    private void createThrottle() {
        if (this.dataServiceOptions.isRateLimitEnabled()) {
            int publishRate = this.dataServiceOptions.getRateLimitAverageRate();
            int burstLength = this.dataServiceOptions.getRateLimitBurstSize();

            long publishPeriod = this.dataServiceOptions.getRateLimitTimeUnit() / publishRate;

            logger.info("Get Throttle with burst length {} and send a message every {} nanoseconds", burstLength,
                    publishPeriod);
            this.throttle = new TokenBucket(burstLength, publishPeriod);
        }
    }

    private void stopConnectionMonitorTask() {
        if (this.connectionMonitorFuture != null && !this.connectionMonitorFuture.isDone()) {

            logger.info("Reconnect task running. Stopping it");

            this.connectionMonitorFuture.cancel(true);
        }
        unregisterAsCriticalComponent();
    }

    private void unregisterAsCriticalComponent() {
        this.watchdogService.unregisterCriticalComponent(this);
    }

    @Override
    public void disconnect() {
        long millis = this.dataServiceOptions.getDisconnectDelay() * 1000L;
        this.dataTransportService.disconnect(millis);
    }

    private void submitPublishingWork() {
        this.publisherEnabled.set(true);

        this.publisherExecutor.execute(new PublishManager());
    }

    // It's very important that the publishInternal and messageConfirmed methods are
    // synchronized
    private synchronized void publishInternal(StoredMessage message) throws KuraException {

        String topic = message.getTopic();
        byte[] payload = message.getPayload();
        int qos = message.getQos();
        boolean retain = message.isRetain();
        int msgId = message.getId();

        logger.debug("Publishing message with ID: {} on topic: {}, priority: {}", msgId, topic, message.getPriority());

        DataTransportToken token = DataServiceImpl.this.dataTransportService.publish(topic, payload, qos,
                retain);

        if (token == null) {
            DataServiceImpl.this.store.getMessageStore().markAsPublished(msgId);
            logger.debug("Published message with ID: {}", msgId);
        } else {

            // Check if the token is already tracked in the map (in which case we are in
            // trouble)
            Integer trackedMsgId = DataServiceImpl.this.inFlightMsgIds.get(token);
            if (trackedMsgId != null) {
                logger.error("Token already tracked: {} - {}", token.getSessionId(), token.getMessageId());
            }

            DataServiceImpl.this.inFlightMsgIds.put(token, msgId);
            DataServiceImpl.this.store.getMessageStore().markAsPublished(msgId, token);
            logger.debug("Published message with ID: {} and MQTT message ID: {}", msgId, token.getMessageId());
        }
    }

    private List<Integer> buildMessageIds(List<StoredMessage> messages, String topicRegex) {
        Pattern topicPattern = Pattern.compile(topicRegex);
        List<Integer> ids = new ArrayList<>();

        if (messages != null) {
            for (StoredMessage message : messages) {
                String topic = message.getTopic();
                if (topicPattern.matcher(topic).matches()) {
                    ids.add(message.getId());
                }
            }
        }

        return ids;
    }

    private void handleInFlightDecongestion() {
        if (this.congestionFuture != null && !this.congestionFuture.isDone()) {
            this.congestionFuture.cancel(true);
        }
    }

    @Override
    public int getNotificationPriority() {
        return CloudConnectionStatusService.PRIORITY_LOW;
    }

    @Override
    public CloudConnectionStatusEnum getNotificationStatus() {
        return this.notificationStatus;
    }

    @Override
    public void setNotificationStatus(CloudConnectionStatusEnum status) {
        this.notificationStatus = status;
    }

    private final class PublishManager implements Runnable {

        @Override
        public void run() {
            Thread.currentThread().setName("DataServiceImpl:Submit");
            while (DataServiceImpl.this.publisherEnabled.get()) {
                long sleepingTime = -1;
                boolean messagePublished = false;

                if (DataServiceImpl.this.dataTransportService.isConnected()) {
                    try {
                        final Optional<StoredMessage> message = DataServiceImpl.this.store.getMessageStore()
                                .getNextMessage();

                        if (message.isPresent()) {
                            checkInFlightMessages(message.get());

                            if (DataServiceImpl.this.dataServiceOptions.isRateLimitEnabled()
                                    && message.get().getPriority() >= 5) {
                                messagePublished = publishMessageTokenBucket(message.get());
                                sleepingTime = DataServiceImpl.this.throttle.getTokenWaitTime();
                            } else {
                                publishMessageUnbound(message.get());
                                messagePublished = true;
                            }
                        }
                    } catch (KuraNotConnectedException e) {
                        logger.info("DataPublisherService is not connected");
                    } catch (KuraTooManyInflightMessagesException e) {
                        logger.info("Too many in-flight messages");
                        handleInFlightCongestion();
                    } catch (Exception e) {
                        logger.error("Probably an unrecoverable exception", e);
                    }
                } else {
                    logger.info("DataPublisherService not connected");
                }

                if (!messagePublished) {
                    suspendPublisher(sleepingTime, TimeUnit.NANOSECONDS);
                }
            }
            logger.debug("Exited publisher loop.");
        }

        private void checkInFlightMessages(StoredMessage message) throws KuraTooManyInflightMessagesException {
            if (message.getQos() > 0 && DataServiceImpl.this.inFlightMsgIds
                    .size() >= DataServiceImpl.this.dataServiceOptions.getMaxInFlightMessages()) {
                logger.warn("The configured maximum number of in-flight messages has been reached");
                throw new KuraTooManyInflightMessagesException("Too many in-flight messages");
            }
        }

        private void suspendPublisher(long timeout, TimeUnit timeUnit) {
            if (!DataServiceImpl.this.publisherEnabled.get()) {
                return;
            }
            try {
                DataServiceImpl.this.lock.lock();
                if (!DataServiceImpl.this.notifyPending) {
                    if (timeout == -1) {
                        logger.debug("Suspending publishing thread indefinitely");
                        DataServiceImpl.this.lockCondition.await();
                    } else {
                        logger.debug("Suspending publishing thread for {} nanoseconds", timeout);
                        DataServiceImpl.this.lockCondition.await(timeout, timeUnit);
                    }
                }
                DataServiceImpl.this.notifyPending = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                DataServiceImpl.this.lock.unlock();
            }
        }

        private void publishMessageUnbound(StoredMessage message) throws KuraException {
            publishInternal(message);
            // Notify the listeners
            DataServiceImpl.this.dataServiceListeners.onMessagePublished(message.getId(), message.getTopic());
        }

        private boolean publishMessageTokenBucket(StoredMessage message) throws KuraException {
            boolean tokenAvailable = DataServiceImpl.this.throttle.getToken();

            if (tokenAvailable) {
                publishMessageUnbound(message);
                return true;
            }
            return false;
        }

        private void handleInFlightCongestion() {
            int timeout = DataServiceImpl.this.dataServiceOptions.getInFlightMessagesCongestionTimeout();

            // Do not schedule more that one task at a time
            if (timeout != 0 && (DataServiceImpl.this.congestionFuture == null
                    || DataServiceImpl.this.congestionFuture.isDone())) {
                logger.warn("In-flight message congestion timeout started");
                DataServiceImpl.this.congestionFuture = DataServiceImpl.this.congestionExecutor.schedule(() -> {
                    Thread.currentThread().setName("DataServiceImpl:InFlightCongestion");
                    logger.warn("In-flight message congestion timeout elapsed. Disconnecting and reconnecting again");
                    disconnect();
                    startConnectionMonitorTask();
                }, timeout, TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public String getCriticalComponentName() {
        return "DataServiceImpl";
    }

    @Override
    public int getCriticalComponentTimeout() {
        return this.dataServiceOptions.getCriticalComponentTimeout();
    }

    public Map<String, String> getConnectionInfo() {
        Map<String, String> result = new HashMap<>();
        result.put("Broker URL", this.dataTransportService.getBrokerUrl());
        result.put("Account", this.dataTransportService.getAccountName());
        result.put("Username", this.dataTransportService.getUsername());
        result.put("Client ID", this.dataTransportService.getClientId());
        return result;
    }

    @Override
    public void startConnectionTask() {
        startConnectionMonitorTask();
    }

    @Override
    public void stopConnectionTask() {
        disconnect();
        stopConnectionMonitorTask();
    }

    @Override
    public boolean hasInFlightMessages() {
        return !this.inFlightMsgIds.isEmpty();
    }

    @Override
    public Optional<StoredMessage> getNextMessage() {
        Optional<StoredMessage> message = Optional.empty();
        try {
            message = DataServiceImpl.this.store.getMessageStore().getNextMessage();
        } catch (Exception e) {
            logger.error("Probably an unrecoverable exception", e);
        }
        return message;
    }

}
