/*
    Copyright 2019, Strategic Gains, Inc.

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

		http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.
*/
package com.nifli.toggles.client;

import java.io.IOException;
import java.text.SimpleDateFormat;

import org.apache.http.HttpHeaders;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.ObjectMapper;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.nifli.toggles.client.authn.TokenManager;
import com.nifli.toggles.client.authn.TokenManagerException;
import com.nifli.toggles.client.authn.TokenManagerImpl;
import com.nifli.toggles.client.domain.StageToggles;

/**
 * The controlling class for all feature flag decisions.
 * 
 * @author tfredrich
 */
public class TogglesClient
{
	private TogglesConfiguration config;
	private TokenManager tokens;

	/**
	 * Create a new feature flag client with default configuration, using the clientId and secret for this application.
	 * 
	 * @param clientId
	 * @param clientSecret
	 */
	public TogglesClient(char[] clientId, char[] clientSecret)
	{
		this(new TogglesConfiguration()
			.setClientId(clientId)
			.setClientSecret(clientSecret)
		);
	}

	/**
	 * Create a new feature flag client, specifying configuration details in a TogglesConfiguration instance.
	 * 
	 * @param togglesConfiguration
	 */
	public TogglesClient(TogglesConfiguration togglesConfiguration)
	{
		super();
		this.config = togglesConfiguration;
		this.tokens = new TokenManagerImpl(togglesConfiguration);

		//TODO: Wrap ObjectMapper
		Unirest.setObjectMapper(new ObjectMapper()
		{
			private com.fasterxml.jackson.databind.ObjectMapper jacksonObjectMapper = new com.fasterxml.jackson.databind.ObjectMapper()
				.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
	
				// Ignore additional/unknown properties in a payload.
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				
				// Only serialize populated properties (do no serialize nulls)
				.setSerializationInclusion(JsonInclude.Include.NON_NULL)
				
				// Use fields directly.
				.setVisibility(PropertyAccessor.FIELD, Visibility.ANY)
				
				// Ignore accessor and mutator methods (use fields per above).
				.setVisibility(PropertyAccessor.GETTER, Visibility.NONE)
				.setVisibility(PropertyAccessor.SETTER, Visibility.NONE)
				.setVisibility(PropertyAccessor.IS_GETTER, Visibility.NONE)
				
				// Set default ISO 8601 timepoint output format.
				.setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"));


			public <T> T readValue(String value, Class<T> valueType)
			{
				try
				{
					return jacksonObjectMapper.readValue(value, valueType);
				}
				catch (IOException e)
				{
					throw new RuntimeException(e);
				}
			}

			public String writeValue(Object value)
			{
				try
				{
					return jacksonObjectMapper.writeValueAsString(value);
				}
				catch (JsonProcessingException e)
				{
					throw new RuntimeException(e);
				}
			}
		});
	}

	/**
	 * Set which development stage (e.g. dev, test, prod) this client is working against.
	 * 
	 * @param stage the 'slug' name of the desired stage. 
	 * @return this TogglesClient instance to facilitate method chaining.
	 */
	public TogglesClient setStage(String stage)
	{
		this.config.setStage(stage);
		return this;
	}

	/**
	 * Answer whether this feature is enabled in this stage for this application. If the flag is not able to be retrieved
	 * from the remote API, returns the defaultValue.
	 * 
	 * @param featureName the textual name of the feature.
	 * @param defaultValue boolean value to return if unable to retrieve the setting from the API.
	 * @return true if the feature is enabled for this application in the stage.
	 */
	public boolean isEnabled(String featureName, boolean defaultValue)
	{
		return isEnabled(featureName, null, defaultValue);
	}

	/**
	 * Answer whether this feature is enabled in this stage for this application, using the additional context to test against
	 * feature activation strategies. If the flag is not able to be retrieved from the remote API, returns the defaultValue.
	 * 
	 * @param featureName the textual name of the feature.
	 * @param context additional contextual values to test against feature-activation strategies.
	 * @param defaultValue boolean value to return if unable to retrieve the setting from the API.
	 * @return true if the feature is enabled for this application in the stage, given the context.
	 */
	public boolean isEnabled(String featureName, TogglesContext context, boolean defaultValue)
	{
		int retries = config.getMaxRetries();
		HttpResponse<StageToggles> response = null;

		try
		{
			while (--retries >= 0)
			{
				response = Unirest.get(config.getTogglesEndpoint())
					.header(HttpHeaders.AUTHORIZATION, tokens.getAccessToken())
			        .header("accept", "application/json")
			        .header("Content-Type", "application/json")
					.asObject(StageToggles.class);
	
				if (response.getStatus() == 401) // assume needs a token refresh
				{
					tokens.newAccessToken();
				}
				else if (isSuccessful(response))
				{
					return processContext(featureName, response.getBody(), context, defaultValue);
				}
			}
		}
		catch (UnirestException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		catch (TokenManagerException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return defaultValue;
	}

	private boolean processContext(String featureName, StageToggles toggles, TogglesContext context, boolean defaultValue)
	{
		Boolean enabled = toggles.isFeatureEnabled(featureName);

		return (enabled != null ? enabled : defaultValue);
	}

	private boolean isSuccessful(HttpResponse<StageToggles> response)
	{
		return response.getStatus() >= 200 && response.getStatus() <= 299;
	}
}
