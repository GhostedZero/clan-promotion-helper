package com.clanpromotionhelper;

/**
 * Turns "when did this happen" into short relative text such as "5 min ago".
 */
final class TimeAgo
{
	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60 * MINUTE;
	private static final long DAY = 24 * HOUR;

	private TimeAgo()
	{
	}

	static String format(long nowMillis, long thenMillis)
	{
		long elapsed = Math.max(0, nowMillis - thenMillis);
		if (elapsed < MINUTE)
		{
			return "just now";
		}
		if (elapsed < HOUR)
		{
			return elapsed / MINUTE + " min ago";
		}
		if (elapsed < DAY)
		{
			return elapsed / HOUR + " h ago";
		}
		return elapsed / DAY + " d ago";
	}
}
