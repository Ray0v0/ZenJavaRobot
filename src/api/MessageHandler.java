package api;

import onebot.model.OneBotEvent;

/**
 * Interface for message event handlers.
 *
 * <p>Registered handlers are called for each incoming message event in order.
 * A handler should return {@code true} if it handled the event (preventing
 * downstream handlers from running), or {@code false} to pass it on.</p>
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * Handle a OneBot event.
     *
     * @param event the incoming event
     * @return {@code true} if the event was handled (stop propagation),
     *         {@code false} to continue to the next handler
     */
    boolean onEvent(OneBotEvent event);
}
