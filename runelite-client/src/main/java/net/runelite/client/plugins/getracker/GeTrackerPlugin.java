/*
 * Copyright (c) 2020, Jere Liimatainen <https://github.com/jliima>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.runelite.client.plugins.getracker;

import com.google.gson.*;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.*;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

@PluginDescriptor(
        name = "GETracker Plugin",
        description = "Updates GE Tracker automatically.",
        tags = {"external", "integration", "notifications", "prices", "trade"}
)

@Slf4j
public class GeTrackerPlugin extends Plugin
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final JsonParser JSON_PARSER = new JsonParser();
    private static final okhttp3.OkHttpClient HTTP_CLIENT = new OkHttpClient();

    @Inject
    private Client client;

    @Inject
    private ConfigManager configManager;

    @Inject
    private GeTrackerConfig geTrackerConfig;

    @Provides
    GeTrackerConfig getConfig(ConfigManager configManager) {
        return configManager.getConfig(GeTrackerConfig.class);
    }

    private SavedGeTrackerOffer getSavedGeTrackerOffer(int itemId, String offerState, int offerQuantity)
    {
        String offer = configManager.getConfiguration("getracker." + client.getUsername().toLowerCase(),
                itemId + ";" + offerState + ";" + offerQuantity);
        if (offer == null)
        {
            return null;
        }
        return GSON.fromJson(offer, SavedGeTrackerOffer.class);
    }

    private void setSavedGeTrackerOffer(SavedGeTrackerOffer offer)
    {
        configManager.setConfiguration("getracker." + client.getUsername().toLowerCase(),
                offer.getItemId() + ";" + offer.getState() + ";" + offer.getQuantity(), GSON.toJson(offer));
    }

    private void deleteSavedGeTrackerOffer(SavedGeTrackerOffer offer)
    {
        configManager.unsetConfiguration("getracker." + client.getUsername().toLowerCase(),
                offer.getItemId() + ";" + offer.getState() + ";" + offer.getQuantity());
    }

    @Override
    protected void startUp() throws IOException {
        updateSavedGeTrackerOffers();
    }

    private Response makeCall(Request request)
    {
        try {
            Response response = HTTP_CLIENT.newCall(request).execute();
            System.out.println("Call made successfully!");
            return response;

        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private JsonObject getActiveTransactions() throws IOException
    {
        Request request = new Request.Builder()
                .url("https://www.ge-tracker.com/api/profit-tracker/active-transactions")
                .method("GET", null)
                .addHeader("Accept", "application/x.getracker.v1+json")
                .addHeader("Authorization", "Bearer " + GeTrackerConfig.ApiKey())
                .build();
        Response response = makeCall(request);

        return JSON_PARSER.parse(response.body().string()).getAsJsonObject().getAsJsonObject("data");
    }

    private String addNewTransactionToGeTracker(SavedGeTrackerOffer offer) throws IOException
    {

        MediaType mediaType = MediaType.parse("application/json");

        String bodyString = "{" +
                "\"item_id\":" + offer.getItemId() +
                ", \"qty\":"+ offer.getQuantity() +
                ", \"buy_price\":" + offer.getBuyPrice();

        if (offer.getSellPrice() != 0) {
            bodyString += ", \"status\":\"" + offer.getState().toLowerCase() + "\"" + ", \"sell_price\":" + offer.getSellPrice() + "}";
        } else {
            bodyString += "}";
        }

        RequestBody body = RequestBody.create(mediaType, bodyString);

        Request request = new Request.Builder()
                .url("https://www.ge-tracker.com/api/profit-tracker")
                .method("POST", body)
                .addHeader("Accept", "application/x.getracker.v1+json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + GeTrackerConfig.ApiKey())
                .build();
        Response response = makeCall(request);

        String itemIdUrl = JSON_PARSER.parse(response.body().string()).getAsJsonObject().get("resource_url").toString();
        String transactionId = itemIdUrl.substring(47, itemIdUrl.length()-1);
        System.out.println("Transaction added. Transaction Id: " + transactionId);

        return transactionId;
    }

    private void deleteTransactionFromGeTracker(String offerId) throws IOException
    {
        MediaType mediaType = MediaType.parse("text/plain");
        RequestBody body = RequestBody.create(mediaType, "");
        Request request = new Request.Builder()
                .url("https://www.ge-tracker.com/api/profit-tracker/" + offerId)
                .method("DELETE", body)
                .addHeader("Accept", "application/x.getracker.v1+json")
                .addHeader("Authorization", "Bearer " + GeTrackerConfig.ApiKey())
                .build();
        Response response = makeCall(request);
        System.out.println("https://www.ge-tracker.com/api/profit-tracker/" + offerId);
        System.out.println("Delete transaction response: " + JSON_PARSER.parse(response.body().string()));

    }

    private void updateSavedGeTrackerOffers() throws IOException
    {
        System.out.println("\nUpdating active offers.");
        JsonObject activeTransactions = getActiveTransactions();

        Set<String> keys = activeTransactions.keySet();
        Iterator<String> itr = keys.iterator();

        while (itr.hasNext()) {
            JsonArray array = activeTransactions.getAsJsonArray(itr.next());

            for (int i = 0; i < array.size(); ++i) {

                JsonObject offerObject = array.get(i).getAsJsonObject();
                SavedGeTrackerOffer newOffer = new SavedGeTrackerOffer();


                newOffer.setItemId(offerObject.getAsJsonObject("order").get("itemId").getAsInt());
                newOffer.setQuantity(offerObject.getAsJsonObject("order").get("qty").getAsInt());
                newOffer.setBuyPrice(offerObject.getAsJsonObject("order").get("buyPrice").getAsInt());
                newOffer.setOfferId(offerObject.get("id").toString().replace("\"", ""));

                if (offerObject.get("status").toString().equals("\"selling\"")){
                    newOffer.setState("SELLING");
                    newOffer.setSellPrice(offerObject.getAsJsonObject("order").get("sellPrice").getAsInt());
                } else if (offerObject.get("status").toString().equals("\"bought\"")){
                    newOffer.setState("BOUGHT");
                } else if ((offerObject.get("status").toString().equals("\"buying\""))) {
                    newOffer.setState("BUYING");
                }

                setSavedGeTrackerOffer(newOffer);

            }
        }
        System.out.println("Active transactions have been updated.\n");
    }

    private SavedGeTrackerOffer createSavedGeTrackerOffer(GrandExchangeOffer offer)
    {

        SavedGeTrackerOffer newSavedGeTrackerOffer = new SavedGeTrackerOffer();


        newSavedGeTrackerOffer.setItemId(offer.getItemId());
        newSavedGeTrackerOffer.setQuantity(offer.getTotalQuantity());

        if (offer.getState() == GrandExchangeOfferState.SELLING) {
            newSavedGeTrackerOffer.setState("SELLING");
            newSavedGeTrackerOffer.setSellPrice(offer.getPrice());
        } else if (offer.getState() == GrandExchangeOfferState.BUYING) {
            newSavedGeTrackerOffer.setState("BUYING");
            newSavedGeTrackerOffer.setBuyPrice(offer.getPrice());
        } else if (offer.getState() == GrandExchangeOfferState.BOUGHT) {
            newSavedGeTrackerOffer.setState("BOUGHT");
            newSavedGeTrackerOffer.setBuyPrice(offer.getPrice());
        }
        return newSavedGeTrackerOffer;
    }

    private SavedGeTrackerOffer isInSavedOffers(GrandExchangeOffer grandExchangeOffer)
    {
        return getSavedGeTrackerOffer(grandExchangeOffer.getItemId(),
                grandExchangeOffer.getState().toString(), grandExchangeOffer.getTotalQuantity());

    }

    private void submitChangedOffer(GrandExchangeOffer grandExchangeOffer) throws IOException
    {
        if (grandExchangeOffer.getState() == GrandExchangeOfferState.EMPTY) {
            System.out.println("Offer is empty. Submitting cancelled");
            return;
        }

        if (grandExchangeOffer.getState() == GrandExchangeOfferState.BUYING && isInSavedOffers(grandExchangeOffer) == null) {
            SavedGeTrackerOffer newOffer = createSavedGeTrackerOffer(grandExchangeOffer);
            String offerId = addNewTransactionToGeTracker(newOffer);
            newOffer.setOfferId(offerId);
            setSavedGeTrackerOffer(newOffer);
            return;
        }

        if (grandExchangeOffer.getState() == GrandExchangeOfferState.CANCELLED_BUY && grandExchangeOffer.getQuantitySold() == 0 &&
                isInSavedOffers(grandExchangeOffer) == null) {
            System.out.println("GrandExchangeoffer has been cancelled and it wasn't found on saved GeTrackerOffers.");
            return;
        }

        if (grandExchangeOffer.getState() == GrandExchangeOfferState.CANCELLED_BUY && grandExchangeOffer.getQuantitySold() == 0 &&
                isInSavedOffers(grandExchangeOffer) != null) {

            SavedGeTrackerOffer offerToBeRemoved = isInSavedOffers(grandExchangeOffer);
            assert offerToBeRemoved != null;

            System.out.println("Trying to delete a offer with offerId: " + offerToBeRemoved.getOfferId());
            deleteTransactionFromGeTracker(offerToBeRemoved.getOfferId());
            deleteSavedGeTrackerOffer(offerToBeRemoved);


        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged offerEvent) throws IOException
    {
        final GrandExchangeOffer offer = offerEvent.getOffer();
        submitChangedOffer(offer);

    }

}