package com.clanpromotionhelper;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Remembers each member's XP gained since joining, so members are only looked up again
 * once their entry is older than the refresh interval or their join date changes.
 */
class XpCache
{
	private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>()
	{
	}.getType();

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	static class Entry
	{
		/** XP gained since joining; null when the last lookup failed */
		Long xpGained;
		long fetchedAt;
		String joinDate;
		String error;
		/**
		 * The member's total XP when their join-date window starts, from Wise Old Man. It belongs to this
		 * name and join date permanently, so it is kept across refreshes and lets a single group request
		 * work out everyone's XP gained. Null when unknown (including caches saved by older versions).
		 */
		Long baselineXp;

		Entry(Long xpGained, long fetchedAt, String joinDate, String error)
		{
			this(xpGained, fetchedAt, joinDate, error, null);
		}
	}

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	/**
	 * Matches names the way Wise Old Man does: case-insensitive, with spaces, underscores, hyphens and
	 * non-breaking spaces all treated as the same character.
	 */
	static String key(String rsn)
	{
		// A plain loop rather than regular expressions: this runs for every member on every roster
		// check and every frame the member list is open
		StringBuilder key = new StringBuilder(rsn.length());
		boolean pendingSpace = false;
		for (int i = 0; i < rsn.length(); i++)
		{
			char c = rsn.charAt(i);
			if (c == ' ' || c == '\u00A0' || c == '_' || c == '-')
			{
				pendingSpace = key.length() > 0;
			}
			else
			{
				if (pendingSpace)
				{
					key.append(' ');
					pendingSpace = false;
				}
				key.append(c);
			}
		}
		return key.toString().trim().toLowerCase(Locale.ROOT);
	}

	Entry get(String rsn)
	{
		return entries.get(key(rsn));
	}

	void put(String rsn, Entry entry)
	{
		entries.put(key(rsn), entry);
	}

	int size()
	{
		return entries.size();
	}

	/** Whether the member has no result for their join date, or only one fetched before {@code refreshBefore}. */
	boolean needsFetch(String rsn, LocalDate joinDate, long refreshBefore)
	{
		Entry entry = get(rsn);
		if (entry == null || !joinDate.toString().equals(entry.getJoinDate()))
		{
			return true;
		}
		return entry.getFetchedAt() < refreshBefore;
	}

	/** The saved baseline for this member, or null if there isn't one for their current join date. */
	Long baselineFor(String rsn, LocalDate joinDate)
	{
		Entry entry = get(rsn);
		if (entry == null || entry.getBaselineXp() == null || !joinDate.toString().equals(entry.getJoinDate()))
		{
			return null;
		}
		return entry.getBaselineXp();
	}

	/**
	 * Works out a member's XP gained from their current total in a Wise Old Man group, using the saved
	 * baseline. Returns false, leaving the member to be looked up individually, when there's no baseline,
	 * the group has no usable total for them, or the total is below the baseline.
	 */
	boolean applyGroupXp(String rsn, LocalDate joinDate, Map<String, Long> groupExp, long nowMillis)
	{
		Long baseline = baselineFor(rsn, joinDate);
		Long current = groupExp.get(key(rsn));
		if (baseline == null || current == null || current <= 0 || current < baseline)
		{
			return false;
		}

		put(rsn, new Entry(current - baseline, nowMillis, joinDate.toString(), null, baseline));
		return true;
	}

	/** Drops members who are no longer in the clan. */
	void retainOnly(Collection<String> rsns)
	{
		Set<String> keep = new HashSet<>();
		for (String rsn : rsns)
		{
			keep.add(key(rsn));
		}
		entries.keySet().retainAll(keep);
	}

	String toJson(Gson gson)
	{
		return gson.toJson(entries, MAP_TYPE);
	}

	static XpCache fromJson(String json, Gson gson)
	{
		XpCache cache = new XpCache();
		if (json == null || json.isEmpty())
		{
			return cache;
		}

		try
		{
			Map<String, Entry> loaded = gson.fromJson(json, MAP_TYPE);
			if (loaded != null)
			{
				for (Map.Entry<String, Entry> saved : loaded.entrySet())
				{
					// The cache can't hold nulls, so skip any a damaged save left behind
					if (saved.getKey() != null && saved.getValue() != null)
					{
						cache.entries.put(saved.getKey(), saved.getValue());
					}
				}
			}
		}
		catch (JsonParseException e)
		{
			// A corrupt cache just means everyone is looked up again
		}
		return cache;
	}
}
