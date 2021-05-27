package io.harness.cfsdk;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import io.harness.cfsdk.cloud.Cloud;
import io.harness.cfsdk.cloud.NetworkInfoProvider;
import io.harness.cfsdk.cloud.analytics.AnalyticsManager;
import io.harness.cfsdk.cloud.cache.CloudCache;
import io.harness.cfsdk.cloud.core.client.ApiException;
import io.harness.cfsdk.cloud.core.model.Evaluation;
import io.harness.cfsdk.cloud.core.model.FeatureConfig;
import io.harness.cfsdk.cloud.core.model.Variation;
import io.harness.cfsdk.cloud.events.AuthCallback;
import io.harness.cfsdk.cloud.events.AuthResult;
import io.harness.cfsdk.cloud.events.EvaluationListener;
import io.harness.cfsdk.cloud.factories.CloudFactory;
import io.harness.cfsdk.cloud.model.AuthInfo;
import io.harness.cfsdk.cloud.model.Target;
import io.harness.cfsdk.cloud.oksse.EventsListener;
import io.harness.cfsdk.cloud.oksse.model.SSEConfig;
import io.harness.cfsdk.cloud.oksse.model.StatusEvent;
import io.harness.cfsdk.cloud.polling.EvaluationPolling;
import io.harness.cfsdk.cloud.repository.FeatureRepository;
import io.harness.cfsdk.cloud.sse.SSEController;
import io.harness.cfsdk.common.Destroyable;
import io.harness.cfsdk.logging.CfLog;
import io.harness.cfsdk.utils.CfUtils;

/**
 * Main class used for any operation on SDK. Operations include, but not limited to, reading evaluations and
 * observing changes in state of SDK.
 * Before it can be used, one of the {@link CfClient#initialize} methods <strong>must be</strong>  called
 */
public final class CfClient implements Destroyable {

    private Cloud cloud;
    private Target target;
    private AuthInfo authInfo;
    private boolean useStream;
    private final String logTag;
    private volatile boolean ready;
    private final Executor executor;
    private boolean analyticsEnabled;
    private static CfClient instance;
    private SSEController sseController;
    private final CloudFactory cloudFactory;
    private AnalyticsManager analyticsManager;
    private FeatureRepository featureRepository;
    private EvaluationPolling evaluationPolling;
    private final Executor listenerUpdateExecutor;
    private NetworkInfoProvider networkInfoProvider;
    private final Set<EventsListener> eventsListenerSet;
    private final Cache<String, FeatureConfig> featureCache;
    private final ConcurrentHashMap<String, Set<EvaluationListener>> evaluationListenerSet;

    {

        logTag = CfClient.class.getSimpleName();
        executor = Executors.newSingleThreadExecutor();
        evaluationListenerSet = new ConcurrentHashMap<>();
        listenerUpdateExecutor = Executors.newSingleThreadExecutor();
        featureCache = CacheBuilder.newBuilder().maximumSize(10000).build();
        eventsListenerSet = Collections.synchronizedSet(new LinkedHashSet<>());
    }

    private final EventsListener eventsListener = statusEvent -> {
        if (!ready) return;
        switch (statusEvent.getEventType()) {
            case SSE_START:

                evaluationPolling.stop();
                break;
            case SSE_END:

                if (networkInfoProvider.isNetworkAvailable()) {

                    final String environmentID = authInfo.getEnvironmentIdentifier();
                    final String clusterID = authInfo.getClusterIdentifier();

                    initFeatureCache(environmentID, clusterID);
                    this.featureRepository.getAllEvaluations(

                            environmentID,
                            target.getIdentifier(),
                            false
                    );
                    evaluationPolling.start(this::reschedule);
                }
                break;

            case EVALUATION_CHANGE:

                Evaluation evaluation = statusEvent.extractPayload();
                Evaluation e = featureRepository.getEvaluation(authInfo.getEnvironmentIdentifier(), target.getIdentifier(), evaluation.getFlag(), false);
                statusEvent = new StatusEvent(statusEvent.getEventType(), e);
                notifyListeners(e);
                break;
            case EVALUATION_REMOVE:

                Evaluation eval = statusEvent.extractPayload();
                featureRepository.remove(authInfo.getEnvironmentIdentifier(), target.getIdentifier(), eval.getFlag());
                break;
        }
        sendEvent(statusEvent);
    };

    /**
     * Base constructor, used internally. Use {@link CfClient#getInstance()} to get instance of this class.
     */
    CfClient(CloudFactory cloudFactory) {
        this.cloudFactory = cloudFactory;
    }

    /**
     * Retrieves the single instance of {@link CfClient} to be used for SDK operation
     *
     * @return single instance used as entry point of SDK
     */
    public static CfClient getInstance() {

        if (instance == null) {
            synchronized (CfClient.class) {

                if (instance == null) {
                    instance = new CfClient(new CloudFactory());
                }
            }
        }
        return instance;
    }

    private void sendEvent(StatusEvent statusEvent) {

        listenerUpdateExecutor.execute(() -> {
            Iterator<EventsListener> iterator = eventsListenerSet.iterator();
            while (iterator.hasNext()) {
                EventsListener listener = iterator.next();
                listener.onEventReceived(statusEvent);
            }
        });
    }

    private void notifyListeners(Evaluation evaluation) {
        if (evaluationListenerSet.containsKey(evaluation.getFlag())) {
            final Set<EvaluationListener> callbacks = evaluationListenerSet.get(evaluation.getFlag());
            if (callbacks != null) {

                for (EvaluationListener listener : callbacks) {

                    listener.onEvaluation(evaluation);
                }
            }
        }
    }

    private void reschedule() {
        executor.execute(() -> {
            try {
                if (!ready) {

                    boolean success = cloud.initialize();
                    if (success) {

                        ready = true;
                        this.authInfo = cloud.getAuthInfo();
                    }
                }
                if (!ready) {

                    return;
                }

                final String environmentID = authInfo.getEnvironmentIdentifier();
                final String clusterID = authInfo.getClusterIdentifier();

                initFeatureCache(environmentID, clusterID);
                List<Evaluation> evaluations = this.featureRepository.getAllEvaluations(

                        environmentID,
                        target.getIdentifier(),
                        false
                );
                sendEvent(new StatusEvent(StatusEvent.EVENT_TYPE.EVALUATION_RELOAD, evaluations));

                if (useStream) {

                    startSSE();
                } else {

                    evaluationPolling.start(this::reschedule);
                }
            } catch (Exception e) {

                CfLog.OUT.e(logTag, e.getMessage(), e);
                if (networkInfoProvider.isNetworkAvailable()) {
                    evaluationPolling.start(this::reschedule);
                }
            }
        });
    }

    private void setupNetworkInfo(Context context) {
        if (networkInfoProvider != null) {
            networkInfoProvider.unregisterAll();
        } else networkInfoProvider = cloudFactory.networkInfoProvider(context);

        networkInfoProvider.register(status -> {

            if (status == NetworkInfoProvider.NetworkStatus.CONNECTED) {
                reschedule();
            } else {
                evaluationPolling.stop();
            }
        });
    }

    private synchronized void startSSE() {

        SSEConfig config = cloud.getConfig();
        if (config.isValid()) {

            sseController.start(config, eventsListener);
        }
    }

    private synchronized void stopSSE() {

        this.useStream = false;
        if (sseController != null) {
            sseController.stop();
        }
    }


    /**
     * Initialize the client and sets up needed dependencies. Upon called, it is dispatched to another thread and result is returned trough
     * provided {@link AuthCallback} instance.
     *
     * @param context       Context of application
     * @param apiKey        API key used for authentication
     * @param configuration Collection of different configuration flags, which defined the behaviour of SDK
     * @param target        Desired target against which we want features to be evaluated
     * @param cloudCache    Custom {@link CloudCache} implementation. If non provided, the default implementation will be used
     * @param authCallback  The callback that will be invoked when initialization is finished
     */
    public void initialize(

            final Context context,
            final String apiKey,
            final CfConfiguration configuration,
            final Target target,
            final CloudCache cloudCache,
            @Nullable final AuthCallback authCallback
    ) {
        try {
            executor.execute(() -> {

                if (target == null || configuration == null) {
                    if (authCallback != null) {

                        final String message = "Target and configuration must not be null!";
                        final IllegalArgumentException error = new IllegalArgumentException(message);
                        final AuthResult result = new AuthResult(false, error);
                        authCallback.authorizationSuccess(authInfo, result);
                    }
                    return;
                }

                unregister();
                this.target = target;
                this.cloud = cloudFactory.cloud(configuration.getStreamURL(), configuration.getBaseURL(), apiKey, target);
                setupNetworkInfo(context);
                featureRepository = cloudFactory.getFeatureRepository(cloud, cloudCache);
                evaluationPolling = cloudFactory.evaluationPolling(configuration.getPollingInterval(), TimeUnit.SECONDS);

                this.useStream = configuration.getStreamEnabled();
                this.analyticsEnabled = configuration.isAnalyticsEnabled();

                boolean success = cloud.initialize();
                if (success) {

                    this.authInfo = cloud.getAuthInfo();
                    this.sseController = cloudFactory.sseController(cloud, this.authInfo, featureCache);

                    final String environmentID = authInfo.getEnvironment();
                    final String clusterID = authInfo.getClusterIdentifier();

                    initFeatureCache(environmentID, clusterID);
                    ready = true;

                    if (networkInfoProvider.isNetworkAvailable()) {

                        List<Evaluation> evaluations = featureRepository.getAllEvaluations(
                                this.authInfo.getEnvironmentIdentifier(),
                                target.getIdentifier(),
                                false
                        );
                        sendEvent(new StatusEvent(StatusEvent.EVENT_TYPE.EVALUATION_RELOAD, evaluations));
                        if (useStream) {

                            startSSE();
                        } else {

                            evaluationPolling.start(this::reschedule);
                        }
                    }

                    if (analyticsEnabled) {

                        this.analyticsManager = new AnalyticsManager(environmentID, apiKey, configuration);
                    }

                    if (authCallback != null) {

                        final AuthResult result = new AuthResult(true);
                        authCallback.authorizationSuccess(authInfo, result);
                    }
                } else {

                    if (authCallback != null) {

                        final String message = "Authorization was not successful";
                        final Exception error = new Exception(message);
                        final AuthResult result = new AuthResult(false, error);
                        authCallback.authorizationSuccess(authInfo, result);
                    }
                }
            });
        } catch (RejectedExecutionException e) {

            CfLog.OUT.e(logTag, e.getMessage(), e);
            if (authCallback != null) {

                final AuthResult result = new AuthResult(false, e);
                authCallback.authorizationSuccess(authInfo, result);
            }
        }
    }

    public void initialize(Context context, String apiKey, CfConfiguration configuration, Target target, AuthCallback authCallback) {
        initialize(context, apiKey, configuration, target, cloudFactory.defaultCache(context), authCallback);
    }

    public void initialize(Context context, String apiKey, CfConfiguration configuration, Target target, CloudCache cloudCache) {
        initialize(context, apiKey, configuration, target, cloudCache, null);
    }

    public void initialize(Context context, String apiKey, CfConfiguration configuration, Target target) {
        initialize(context, apiKey, configuration, target, cloudFactory.defaultCache(context));
    }

    /**
     * Register a listener to observe changes on a evaluation with given id. The change <strong>will not</strong> be triggered
     * in case of reloading all evaluations, but only when single evaluation is changed.
     * It is possible to register multiple observers for a single evaluatio.
     *
     * @param evaluationId Evaluation identifier we would like to observe.
     * @param listener     {@link EvaluationListener} instance that will be invoked when evaluation is changed
     * @return Was evaluation registered with success?
     */
    public boolean registerEvaluationListener(String evaluationId, EvaluationListener listener) {

        if (listener != null) {

            Set<EvaluationListener> set = evaluationListenerSet.get(evaluationId);
            if (set == null) {

                set = new HashSet<>();
            }
            boolean success = set.add(listener);
            evaluationListenerSet.put(evaluationId, set);
            return success;
        }
        return false;
    }


    /**
     * Removes specified listener for an evaluation with given id.
     *
     * @param evaluationId Evaluation identifier.
     * @param listener     {@link EvaluationListener} instance we want to remove
     * @return Was evaluation un-registered with success?
     */
    public boolean unregisterEvaluationListener(String evaluationId, EvaluationListener listener) {

        if (listener != null) {

            Set<EvaluationListener> set = this.evaluationListenerSet.get(evaluationId);
            if (set != null) {

                return set.remove(listener);
            }
        }
        return false;
    }

    /**
     * Retrieves single {@link Evaluation instance} based on provided id. If no such evaluation is found,
     * returns one with provided default value.
     *
     * @param evaluationId Identifier of target evaluation
     * @param defaultValue Default value to be used in case when evaluation is not found
     * @return Evaluation for a given id
     */
    private <T> Evaluation getEvaluationById(String evaluationId, String target, T defaultValue) {

        final Evaluation result = new Evaluation();
        if (ready) {

            final String identifier = authInfo.getEnvironmentIdentifier();
            final Evaluation evaluation = featureRepository.getEvaluation(

                    identifier, target, evaluationId, true
            );

            if (evaluation == null) {

                result.value(defaultValue)
                        .flag(evaluationId);
            } else {

                result.flag(evaluation.getFlag())
                        .value(evaluation.getValue())
                        .kind(evaluation.getKind())
                        .identifier(evaluation.getIdentifier());
            }
        } else {

            result.value(defaultValue)
                    .flag(evaluationId);
        }

        final FeatureConfig featureConfig = featureCache.getIfPresent(evaluationId);
        if (!this.target.isPrivate()
                && this.target.isValid()
                && analyticsEnabled
                && analyticsManager != null
                && featureConfig != null
        ) {

            final Variation variation = new Variation();
            variation.setName(evaluationId);
            variation.setValue(String.valueOf(result));
            variation.setIdentifier(result.getIdentifier());
            analyticsManager.pushToQueue(this.target, featureConfig, variation);
        }

        return result;
    }

    public boolean boolVariation(String evaluationId, boolean defaultValue) {

        final Evaluation evaluation = getEvaluationById(

                evaluationId,
                target.getIdentifier(),
                defaultValue
        );

        final Object value = evaluation.getValue();
        if (value instanceof Boolean) {

            return (Boolean) value;
        }
        if (value instanceof String) {

            return "true".equals(value);
        }
        return defaultValue;
    }

    public String stringVariation(String evaluationId, String defaultValue) {

        return getEvaluationById(evaluationId, target.getIdentifier(), defaultValue).getValue();
    }

    public double numberVariation(String evaluationId, double defaultValue) {

        final Evaluation evaluation = getEvaluationById(

                evaluationId,
                target.getIdentifier(),
                defaultValue
        );

        final Object value = evaluation.getValue();
        if (value instanceof Number) {

            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {

            final String strValue = (String) value;
            try {

                return Double.parseDouble(strValue);
            } catch (NumberFormatException e) {

                CfLog.OUT.e(logTag, e.getMessage(), e);
            }
        }
        return defaultValue;
    }

    public JSONObject jsonVariation(String evaluationId, JSONObject defaultValue) {

        try {

            Evaluation e = getEvaluationById(evaluationId, target.getIdentifier(), defaultValue);
            if (e.getValue() == null) {

                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put(evaluationId, null);
                return new JSONObject(resultMap);
            } else {

                return new JSONObject((String) e.getValue());
            }
        } catch (JSONException e) {

            CfLog.OUT.e(logTag, e.getMessage(), e);
        }
        return null;
    }


    /**
     * Adds new listener for various SDK events. See {@link StatusEvent.EVENT_TYPE} for possible types.
     *
     * @param observer {@link EventsListener} implementation that will be triggered when there is a change in state of SDK
     * @return Was listener registered with success?
     */
    public boolean registerEventsListener(final EventsListener observer) {

        if (observer != null) {

            return eventsListenerSet.add(observer);
        }
        return false;
    }

    /**
     * Removes registered listener from list of registered events listener
     *
     * @param observer {@link EventsListener} implementation that needs to be removed
     * @return Was listener un-registered with success?
     */
    public boolean unregisterEventsListener(final EventsListener observer) {

        return eventsListenerSet.remove(observer);
    }

    /**
     * Clears the occupied resources and shut's down the sdk.
     * After calling this method, the {@link #initialize} must be called again. It will also
     * remove any registered event listeners.
     */
    @Override
    public void destroy() {

        unregister();
        if (analyticsManager != null) {

            analyticsManager.destroy();
        }
        this.evaluationListenerSet.clear();
        eventsListenerSet.clear();
    }

    private void unregister() {

        ready = false;
        stopSSE();
        if (evaluationPolling != null) evaluationPolling.stop();
        if (featureRepository != null) featureRepository.clear();
    }

    private void initFeatureCache(String environmentID, String clusterID) {

        if (CfUtils.Text.isNotEmpty(environmentID)) {

            try {
                final List<FeatureConfig> featureConfigs =
                        cloud.getFeatureConfig(environmentID, clusterID);

                if (featureConfigs != null) {

                    for (final FeatureConfig config : featureConfigs) {
                        featureCache.put(config.getFeature(), config);
                    }
                }
                CfLog.OUT.d(logTag, "Feature cache populated");
            } catch (ApiException e) {

                final String error = e.getMessage();
                CfLog.OUT.e(logTag, "Feature cache error: " + error, e);
            }
        } else {

            CfLog.OUT.e(logTag, "Environment ID is null or empty");
        }
    }
}