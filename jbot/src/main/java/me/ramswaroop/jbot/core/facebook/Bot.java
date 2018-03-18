package me.ramswaroop.jbot.core.facebook;

import me.ramswaroop.jbot.core.common.BaseBot;
import me.ramswaroop.jbot.core.common.Controller;
import me.ramswaroop.jbot.core.common.EventType;
import me.ramswaroop.jbot.core.facebook.models.Callback;
import me.ramswaroop.jbot.core.facebook.models.Entry;
import me.ramswaroop.jbot.core.facebook.models.Event;
import me.ramswaroop.jbot.core.facebook.models.Message;
import me.ramswaroop.jbot.core.facebook.models.Payload;
import me.ramswaroop.jbot.core.facebook.models.Postback;
import me.ramswaroop.jbot.core.facebook.models.Response;
import me.ramswaroop.jbot.core.facebook.models.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.regex.Matcher;

/**
 * @author ramswaroop
 * @version 11/09/2016
 */
public abstract class Bot extends BaseBot {

    private static final Logger logger = LoggerFactory.getLogger(Bot.class);

    @Value("${fbSubscribeUrl}")
    private String subscribeUrl;

    @Value("${fbSendUrl}")
    private String fbSendUrl;

    @Value("${fbMessengerProfileUrl}")
    private String fbMessengerProfileUrl;

    @Autowired
    protected RestTemplate restTemplate;

    @PostConstruct
    private void constructFbSendUrl() {
        fbSendUrl = fbSendUrl.replace("{PAGE_ACCESS_TOKEN}", getPageAccessToken());
        fbMessengerProfileUrl = fbMessengerProfileUrl.replace("{PAGE_ACCESS_TOKEN}", getPageAccessToken());
    }

    /**
     * Class extending this must implement this as it's
     * required to setup the webhook.
     *
     * @return facebook token
     */
    public abstract String getFbToken();

    /**
     * Class extending this must implement this as it's
     * required for Send API.
     *
     * @return facebook page access token
     */
    public abstract String getPageAccessToken();

    /**
     * @param mode
     * @param verifyToken
     * @param challenge
     * @return
     */
    @GetMapping("/webhook")
    public final ResponseEntity setupWebhookVerification(@RequestParam("hub.mode") String mode,
                                                         @RequestParam("hub.verify_token") String verifyToken,
                                                         @RequestParam("hub.challenge") String challenge) {
        if (EventType.SUBSCRIBE.name().equalsIgnoreCase(mode) && getFbToken().equals(verifyToken)) {
            return ResponseEntity.ok(challenge);
        } else {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Add webhook endpoint
     *
     * @param callback
     * @return
     */
    @ResponseBody
    @PostMapping("/webhook")
    public final ResponseEntity setupWebhookEndpoint(@RequestBody Callback callback) {
        try {
            // Checks this is an event from a page subscription
            if (!callback.getObject().equals("page")) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            logger.debug("Callback from fb: {}", callback);
            for (Entry entry : callback.getEntry()) {
                if (entry.getMessaging() != null) {
                    for (Event event : entry.getMessaging()) {
                        if (event.getMessage() != null) {
                            if (event.getMessage().isEcho() != null &&
                                    event.getMessage().isEcho()) {
                                event.setType(EventType.MESSAGE_ECHO);
                            } else if (event.getMessage().getQuickReply() != null) {
                                event.setType(EventType.QUICK_REPLY);
                            } else {
                                event.setType(EventType.MESSAGE);
                                // send typing on indicator to create a conversational experience
                                sendTypingOnIndicator(event.getSender());
                            }
                        } else if (event.getDelivery() != null) {
                            event.setType(EventType.MESSAGE_DELIVERED);
                        } else if (event.getRead() != null) {
                            event.setType(EventType.MESSAGE_READ);
                        } else if (event.getPostback() != null) {
                            event.setType(EventType.POSTBACK);
                        } else if (event.getOptin() != null) {
                            event.setType(EventType.OPT_IN);
                        } else if (event.getReferral() != null) {
                            event.setType(EventType.REFERRAL);
                        } else if (event.getAccountLinking() != null) {
                            event.setType(EventType.ACCOUNT_LINKING);
                        } else {
                            logger.debug("Callback/Event type not supported: {}", event);
                            return ResponseEntity.ok("Callback not supported yet!");
                        }
                        if (isConversationOn(event)) {
                            invokeChainedMethod(event);
                        } else {
                            invokeMethods(event);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error in fb webhook: Callback: {} \nException: ", callback.toString(), e);
        }
        // fb advises to send a 200 response within 20 secs
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    private void sendTypingOnIndicator(User recipient) {
        restTemplate.postForEntity(fbSendUrl,
                new Event().setRecipient(recipient).setSenderAction("typing_on"), Response.class);
    }

    private void sendTypingOffIndicator(User recipient) {
        restTemplate.postForEntity(fbSendUrl,
                new Event().setRecipient(recipient).setSenderAction("typing_off"), Response.class);
    }

    public final ResponseEntity<String> reply(Event event) {
        sendTypingOffIndicator(event.getRecipient());
        logger.debug("Send message: {}", event.toString());
        try {
            return restTemplate.postForEntity(fbSendUrl, event, String.class);
        } catch (HttpClientErrorException e) {
            logger.error("Send message error: Response body: {} \nException: ", e.getResponseBodyAsString(), e);
            return new ResponseEntity<>(e.getResponseBodyAsString(), e.getStatusCode());
        }
    }

    public ResponseEntity<String> reply(Event event, String text) {
        Event response = new Event()
                .setMessagingType("RESPONSE")
                .setRecipient(event.getSender())
                .setMessage(new Message().setText(text));
        return reply(response);
    }

    public ResponseEntity<String> reply(Event event, Message message) {
        Event response = new Event()
                .setMessagingType("RESPONSE")
                .setRecipient(event.getSender())
                .setMessage(message);
        return reply(response);
    }

    /**
     * Call this method with a {@code payload} to set the "Get Started" button. A user sees this button
     * when it first starts a conversation with the bot.
     * <p>
     * See https://developers.facebook.com/docs/messenger-platform/discovery/welcome-screen for more.
     *
     * @param payload for "Get Started" button
     * @return
     */
    public final ResponseEntity<Response> setGetStartedButton(String payload) {
        Event event = new Event().setGetStarted(new Postback().setPayload(payload));
        return restTemplate.postForEntity(fbMessengerProfileUrl, event, Response.class);
    }

    /**
     * Call this method to set the "Greeting Text". A user sees this when it opens up the chat window for the
     * first time. You can specify different messages for different locales. Therefore, this method receives an
     * array of {@code greeting}.
     * <p>
     * See https://developers.facebook.com/docs/messenger-platform/discovery/welcome-screen for more.
     *
     * @param greeting an array of Payload consisting of text and locale
     * @return
     */
    public final ResponseEntity<Response> setGreetingText(Payload[] greeting) {
        Event event = new Event().setGreeting(greeting);
        return restTemplate.postForEntity(fbMessengerProfileUrl, event, Response.class);
    }

    /**
     * Invoke this method to make the bot subscribe to a page after which
     * your users can interact with your page or in other words, the bot.
     * <p>
     * NOTE: It seems Fb now allows the bot to subscribe to a page via the
     * ui. See https://developers.facebook.com/docs/messenger-platform/getting-started/app-setup
     */
    @PostMapping("/subscribe")
    public final void subscribeAppToPage() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.set("access_token", getPageAccessToken());
        restTemplate.postForEntity(subscribeUrl, params, String.class);
    }

    /**
     * Call this method to start a conversation.
     *
     * @param event
     */
    public final void startConversation(Event event, String methodName) {
        startConversation(event.getSender().getId(), methodName);
    }

    /**
     * Call this method to jump to the next method in a conversation.
     *
     * @param event
     */
    public final void nextConversation(Event event) {
        nextConversation(event.getSender().getId());
    }

    /**
     * Call this method to stop the end the conversation.
     *
     * @param event
     */
    public final void stopConversation(Event event) {
        stopConversation(event.getSender().getId());
    }

    /**
     * Check whether a conversation is up in a particular slack channel.
     *
     * @param event
     * @return true if a conversation is on, false otherwise.
     */
    public final boolean isConversationOn(Event event) {
        return isConversationOn(event.getSender().getId());
    }

    /**
     * Invoke the methods with matching {@link Controller#events()}
     * and {@link Controller#pattern()} in events received from Slack/Facebook.
     *
     * @param event
     */
    private void invokeMethods(Event event) {
        try {
            List<MethodWrapper> methodWrappers = eventToMethodsMap.get(event.getType().name().toUpperCase());
            if (methodWrappers == null) return;

            methodWrappers = new ArrayList<>(methodWrappers);
            MethodWrapper matchedMethod =
                    getMethodWithMatchingPatternAndFilterUnmatchedMethods(getPatternFromEventType(event), methodWrappers);
            if (matchedMethod != null) {
                methodWrappers = new ArrayList<>();
                methodWrappers.add(matchedMethod);
            }

            for (MethodWrapper methodWrapper : methodWrappers) {
                Method method = methodWrapper.getMethod();
                if (Arrays.asList(method.getParameterTypes()).contains(Matcher.class)) {
                    method.invoke(this, event, methodWrapper.getMatcher());
                } else {
                    method.invoke(this, event);
                }
            }
        } catch (Exception e) {
            logger.error("Error invoking controller: ", e);
        }
    }

    /**
     * Invoke the appropriate method in a conversation.
     *
     * @param event
     */
    private void invokeChainedMethod(Event event) {
        Queue<MethodWrapper> queue = conversationQueueMap.get(event.getSender().getId());

        if (queue != null && !queue.isEmpty()) {
            MethodWrapper methodWrapper = queue.peek();

            try {
                EventType[] eventTypes = methodWrapper.getMethod().getAnnotation(Controller.class).events();
                for (EventType eventType : eventTypes) {
                    if (eventType.name().equalsIgnoreCase(event.getType().name())) {
                        methodWrapper.getMethod().invoke(this, event);
                        return;
                    }
                }
            } catch (Exception e) {
                logger.error("Error invoking chained method: ", e);
            }
        }
    }

    /**
     * Match the pattern with different attributes based on the event type.
     *
     * @param event
     * @return the pattern string
     */
    private String getPatternFromEventType(Event event) {
        switch (event.getType()) {
            case MESSAGE:
                return event.getMessage().getText();
            case QUICK_REPLY:
                return event.getMessage().getQuickReply().getPayload();
            case POSTBACK:
                return event.getPostback().getPayload();
            default:
                return event.getMessage().getText();
        }
    }
}
