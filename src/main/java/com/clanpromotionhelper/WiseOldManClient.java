package com.clanpromotionhelper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Looks up how much overall XP a player gained between two dates on Wise Old Man.
 * Any non-2xx response is reported as a failure or a retry, never as 0 XP gained.
 */
@Slf4j
@Singleton
class WiseOldManClient
{
	private static final HttpUrl BASE_URL = HttpUrl.get("https://api.wiseoldman.net/v2/");
	private static final int DEFAULT_RATE_LIMIT_WAIT_SECONDS = 60;
	private static final int SERVER_ERROR_WAIT_SECONDS = 5;

	@Value
	static class Result
	{
		enum Status
		{
			OK,
			RETRY,
			FAILED
		}

		Status status;
		Long xpGained;
		/** Total XP when the window starts; null when Wise Old Man has no snapshot there */
		Long startXp;
		int retryAfterSeconds;
		String error;

		static Result ok(long xpGained, Long startXp)
		{
			return new Result(Status.OK, xpGained, startXp, 0, null);
		}

		static Result retry(int seconds, String error)
		{
			return new Result(Status.RETRY, null, null, seconds, error);
		}

		static Result failed(String error)
		{
			return new Result(Status.FAILED, null, null, 0, error);
		}
	}

	/** Every group member's current total XP, keyed by {@link XpCache#key}. */
	@Value
	static class GroupResult
	{
		boolean ok;
		Map<String, Long> exp;
		String error;
	}

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	WiseOldManClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	/**
	 * Runs the lookup on OkHttp's thread pool and passes the result to the callback on that thread.
	 */
	void fetchXpGained(String rsn, LocalDate startDate, LocalDate endDate, String apiKey, String contact, Consumer<Result> callback)
	{
		HttpUrl url = BASE_URL.newBuilder()
			.addPathSegment("players")
			.addPathSegment(rsn)
			.addPathSegment("gained")
			.addQueryParameter("startDate", startDate.toString())
			.addQueryParameter("endDate", endDate.toString())
			.build();

		Request.Builder request = new Request.Builder()
			.url(url)
			.header("Accept", "application/json")
			.header("User-Agent", userAgent(contact));

		if (apiKey != null && !apiKey.trim().isEmpty())
		{
			request.header("x-api-key", apiKey.trim());
		}

		httpClient.newCall(request.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Wise Old Man request for {} failed", rsn, e);
				callback.accept(Result.retry(SERVER_ERROR_WAIT_SECONDS, "Network error: " + e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					String text = body == null ? "" : body.string();
					callback.accept(parse(response.code(), text, response.header("Retry-After"), gson));
				}
				catch (IOException e)
				{
					log.debug("Reading Wise Old Man response for {} failed", rsn, e);
					callback.accept(Result.retry(SERVER_ERROR_WAIT_SECONDS, "Network error: " + e.getMessage()));
				}
			}
		});
	}

	/**
	 * Fetches the whole group in one request. Failures are reported, never retried: the caller falls back
	 * to individual lookups, so a wrong group ID or a rate limit can't turn into a retry loop.
	 */
	void fetchGroup(int groupId, String apiKey, String contact, Consumer<GroupResult> callback)
	{
		HttpUrl url = BASE_URL.newBuilder()
			.addPathSegment("groups")
			.addPathSegment(String.valueOf(groupId))
			.build();

		Request.Builder request = new Request.Builder()
			.url(url)
			.header("Accept", "application/json")
			.header("User-Agent", userAgent(contact));

		if (apiKey != null && !apiKey.trim().isEmpty())
		{
			request.header("x-api-key", apiKey.trim());
		}

		httpClient.newCall(request.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Wise Old Man group request failed", e);
				callback.accept(new GroupResult(false, new HashMap<>(), "Network error: " + e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					callback.accept(parseGroup(response.code(), body == null ? "" : body.string(), gson));
				}
				catch (IOException e)
				{
					log.debug("Reading Wise Old Man group response failed", e);
					callback.accept(new GroupResult(false, new HashMap<>(), "Network error: " + e.getMessage()));
				}
			}
		});
	}

	static GroupResult parseGroup(int code, String body, Gson gson)
	{
		JsonObject root;
		try
		{
			root = gson.fromJson(body, JsonObject.class);
		}
		catch (JsonParseException | IllegalStateException e)
		{
			root = null;
		}

		if (code < 200 || code >= 300)
		{
			String message = code == 429 ? "Rate limited by Wise Old Man"
				: root != null && root.has("message") && root.get("message").isJsonPrimitive()
					? root.get("message").getAsString()
					: "Wise Old Man returned HTTP " + code;
			return new GroupResult(false, new HashMap<>(), message);
		}

		JsonElement memberships = root == null ? null : root.get("memberships");
		if (memberships == null || !memberships.isJsonArray())
		{
			return new GroupResult(false, new HashMap<>(), "Unexpected Wise Old Man response (no group members)");
		}

		Map<String, Long> exp = new HashMap<>();
		JsonArray array = memberships.getAsJsonArray();
		for (JsonElement membership : array)
		{
			JsonElement player = membership.isJsonObject() ? membership.getAsJsonObject().get("player") : null;
			if (player == null || !player.isJsonObject())
			{
				continue;
			}

			JsonObject playerObject = player.getAsJsonObject();
			JsonElement total = playerObject.get("exp");
			if (total == null || !total.isJsonPrimitive() || !total.getAsJsonPrimitive().isNumber() || total.getAsLong() <= 0)
			{
				continue;
			}

			// A member can be matched by either their lookup name or their display name
			for (String field : new String[]{"username", "displayName"})
			{
				JsonElement name = playerObject.get(field);
				if (name != null && name.isJsonPrimitive() && !name.getAsString().trim().isEmpty())
				{
					exp.put(XpCache.key(name.getAsString()), total.getAsLong());
				}
			}
		}

		return new GroupResult(true, exp, null);
	}

	static String userAgent(String contact)
	{
		String agent = "ClanPromotionHelper (RuneLite plugin)";
		if (contact != null && !contact.trim().isEmpty())
		{
			agent += "; contact: " + contact.trim();
		}
		return agent;
	}

	static Result parse(int code, String body, String retryAfterHeader, Gson gson)
	{
		if (code == 429)
		{
			return Result.retry(parseRetryAfter(retryAfterHeader), "Rate limited by Wise Old Man");
		}

		if (code >= 500)
		{
			return Result.retry(SERVER_ERROR_WAIT_SECONDS, "Wise Old Man returned HTTP " + code);
		}

		JsonObject root;
		try
		{
			root = gson.fromJson(body, JsonObject.class);
		}
		catch (JsonParseException | IllegalStateException e)
		{
			root = null;
		}

		if (code < 200 || code >= 300)
		{
			String message = root != null && root.has("message") && root.get("message").isJsonPrimitive()
				? root.get("message").getAsString()
				: "Wise Old Man returned HTTP " + code;
			return Result.failed(message);
		}

		JsonElement gained = path(root, "data", "skills", "overall", "experience", "gained");
		if (gained == null || !gained.isJsonPrimitive() || !gained.getAsJsonPrimitive().isNumber())
		{
			return Result.failed("Unexpected Wise Old Man response (no overall XP data)");
		}

		JsonElement start = path(root, "data", "skills", "overall", "experience", "start");
		Long startXp = start != null && start.isJsonPrimitive() && start.getAsJsonPrimitive().isNumber() && start.getAsLong() >= 0
			? start.getAsLong()
			: null;

		return Result.ok(gained.getAsLong(), startXp);
	}

	private static int parseRetryAfter(String header)
	{
		if (header != null)
		{
			try
			{
				int seconds = Integer.parseInt(header.trim());
				if (seconds > 0)
				{
					return seconds;
				}
			}
			catch (NumberFormatException e)
			{
				// Fall back to the default wait
			}
		}
		return DEFAULT_RATE_LIMIT_WAIT_SECONDS;
	}

	private static JsonElement path(JsonObject root, String... keys)
	{
		JsonElement current = root;
		for (String key : keys)
		{
			if (current == null || !current.isJsonObject())
			{
				return null;
			}
			current = current.getAsJsonObject().get(key);
		}
		return current;
	}
}
