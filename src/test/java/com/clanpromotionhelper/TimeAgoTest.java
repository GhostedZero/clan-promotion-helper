package com.clanpromotionhelper;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TimeAgoTest
{
	private static final long NOW = 1_800_000_000_000L;

	@Test
	public void underAMinuteIsJustNow()
	{
		assertEquals("just now", TimeAgo.format(NOW, NOW));
		assertEquals("just now", TimeAgo.format(NOW, NOW - 59_000));
	}

	@Test
	public void minutesHoursAndDays()
	{
		assertEquals("1 min ago", TimeAgo.format(NOW, NOW - 60_000));
		assertEquals("59 min ago", TimeAgo.format(NOW, NOW - 59 * 60_000));
		assertEquals("1 h ago", TimeAgo.format(NOW, NOW - 3_600_000));
		assertEquals("23 h ago", TimeAgo.format(NOW, NOW - 23 * 3_600_000L));
		assertEquals("2 d ago", TimeAgo.format(NOW, NOW - 2 * 86_400_000L));
	}

	@Test
	public void aTimeInTheFutureIsJustNow()
	{
		assertEquals("just now", TimeAgo.format(NOW, NOW + 5_000));
	}
}
