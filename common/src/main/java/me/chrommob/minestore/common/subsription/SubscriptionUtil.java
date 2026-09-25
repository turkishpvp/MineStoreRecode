package me.chrommob.minestore.common.subsription;

import com.google.gson.JsonObject;

import me.chrommob.minestore.api.web.Result;
import me.chrommob.minestore.api.web.WebApiAccessor;
import me.chrommob.minestore.api.web.WebContext;
import me.chrommob.minestore.api.web.WebRequest;
import me.chrommob.minestore.common.subsription.json.ReturnSubscriptionObject;

public class SubscriptionUtil {
    public static ReturnSubscriptionObject getSubscription(String username) {
        // Sent as JSON. Upstream sent `username=<name>` form data under the
        // `application/json` content type every request carries, so Laravel saw
        // no username, failed validation and redirected to the storefront.
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        WebRequest<ReturnSubscriptionObject> request = new WebRequest.Builder<>(ReturnSubscriptionObject.class).path("in-game/manageSubscriptions/").requiresApiKey(true).type(WebRequest.Type.POST).strBody(body.toString()).build();
        Result<ReturnSubscriptionObject, WebContext> res = WebApiAccessor.request(request);
        if (res.isError()) {
            return null;
        }
        return res.value();
    }
}
