package com.clanpromotionhelper;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Remembers each member's clan rank and the day the plugin saw it change. The game doesn't say when a
 * rank changed, so this is the only record there is: changes made while nobody with the plugin was
 * logged in are dated to when they were next seen, and members never seen changing have no date.
 */
class RankHistory
{
	private static final Type MAP_TYPE = new TypeToken<Map<String, Seen>>()
	{
	}.getType();

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	static class Seen
	{
		/** The game's rank number, not the title, so renaming a title doesn't count as a rank change */
		int rank;
		/** The day the change was seen (ISO date); null when the member has never been seen changing rank */
		String since;
		/** The highest rank number seen, so moving to a vanity rank doesn't lose "reached Sergeant"; 0 in older saves */
		int peak;
	}

	private final Map<String, Seen> entries = new ConcurrentHashMap<>();

	/** Notes the member's current rank, dating it to today if it differs from the last one seen. */
	void record(String rsn, int rank, LocalDate today)
	{
		String key = XpCache.key(rsn);
		Seen seen = entries.get(key);
		if (seen == null)
		{
			entries.put(key, new Seen(rank, null, rank));
		}
		else if (seen.getRank() != rank || seen.getPeak() < rank)
		{
			String since = seen.getRank() != rank ? today.toString() : seen.getSince();
			entries.put(key, new Seen(rank, since, Math.max(seen.getPeak(), rank)));
		}
	}

	/** The highest rank number seen for this member, or {@code current} if there's no record. */
	int peak(String rsn, int current)
	{
		Seen seen = entries.get(XpCache.key(rsn));
		return seen == null ? current : Math.max(seen.getPeak(), current);
	}

	/**
	 * When the member's current window starts: the last rank change seen, or the join date when there's
	 * none or it's from before they (re)joined.
	 */
	LocalDate since(String rsn, LocalDate joinDate)
	{
		Seen seen = entries.get(XpCache.key(rsn));
		if (seen == null || seen.getSince() == null)
		{
			return joinDate;
		}
		LocalDate changed = LocalDate.parse(seen.getSince());
		return changed.isAfter(joinDate) ? changed : joinDate;
	}

	/** Drops members who are no longer in the clan. */
	void retainOnly(Collection<String> rsns)
	{
		Set<String> keep = new HashSet<>();
		for (String rsn : rsns)
		{
			keep.add(XpCache.key(rsn));
		}
		entries.keySet().retainAll(keep);
	}

	String toJson(Gson gson)
	{
		return gson.toJson(entries, MAP_TYPE);
	}

	static RankHistory fromJson(String json, Gson gson)
	{
		RankHistory history = new RankHistory();
		if (json == null || json.isEmpty())
		{
			return history;
		}

		try
		{
			Map<String, Seen> loaded = gson.fromJson(json, MAP_TYPE);
			if (loaded != null)
			{
				for (Map.Entry<String, Seen> saved : loaded.entrySet())
				{
					if (saved.getKey() != null && saved.getValue() != null && isDateOrNull(saved.getValue().getSince()))
					{
						history.entries.put(saved.getKey(), saved.getValue());
					}
				}
			}
		}
		catch (JsonParseException e)
		{
			// A corrupt save only loses the dates; ranks are recorded again from the roster
		}
		return history;
	}

	private static boolean isDateOrNull(String date)
	{
		try
		{
			return date == null || LocalDate.parse(date) != null;
		}
		catch (java.time.format.DateTimeParseException e)
		{
			return false;
		}
	}
}
