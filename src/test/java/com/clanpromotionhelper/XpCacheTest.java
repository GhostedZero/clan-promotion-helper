package com.clanpromotionhelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class XpCacheTest
{
	private static final LocalDate JOINED = LocalDate.of(2024, 3, 31);
	private static final long NOW = 1_800_000_000_000L;
	/** Start of the day the player logged in: results from before then are due their daily refresh */
	private static final long TODAY_START = NOW - TimeUnit.HOURS.toMillis(4);
	private final Gson gson = new Gson();

	@Test
	public void unknownMemberNeedsFetch()
	{
		assertTrue(new XpCache().needsFetch("2006 Buffon", JOINED, TODAY_START));
	}

	@Test
	public void entryFromTodayDoesNotNeedFetch()
	{
		XpCache cache = new XpCache();
		cache.put("2006 Buffon", new XpCache.Entry(80_000_000L, NOW - TimeUnit.HOURS.toMillis(1), JOINED.toString(), null));
		assertFalse(cache.needsFetch("2006 Buffon", JOINED, TODAY_START));
	}

	@Test
	public void entryFromBeforeTodayNeedsFetch()
	{
		XpCache cache = new XpCache();
		cache.put("2006 Buffon", new XpCache.Entry(80_000_000L, NOW - TimeUnit.HOURS.toMillis(6), JOINED.toString(), null));
		assertTrue(cache.needsFetch("2006 Buffon", JOINED, TODAY_START));
	}

	@Test
	public void changedJoinDateNeedsFetch()
	{
		XpCache cache = new XpCache();
		cache.put("2006 Buffon", new XpCache.Entry(80_000_000L, NOW, JOINED.toString(), null));
		assertTrue(cache.needsFetch("2006 Buffon", JOINED.plusDays(1), TODAY_START));
	}

	@Test
	public void failedLookupWaitsForTheNextRefresh()
	{
		XpCache cache = new XpCache();
		cache.put("Missing", new XpCache.Entry(null, NOW, JOINED.toString(), "Player not found."));
		assertFalse(cache.needsFetch("Missing", JOINED, TODAY_START));
	}

	@Test
	public void namesMatchIgnoringCaseAndNonBreakingSpaces()
	{
		XpCache cache = new XpCache();
		cache.put("2006\u00A0Buffon", new XpCache.Entry(1L, NOW, JOINED.toString(), null));
		assertEquals(Long.valueOf(1L), cache.get("2006 buffon").getXpGained());
	}

	@Test
	public void retainOnlyDropsMembersWhoLeft()
	{
		XpCache cache = new XpCache();
		cache.put("Stays", new XpCache.Entry(1L, NOW, JOINED.toString(), null));
		cache.put("Left", new XpCache.Entry(1L, NOW, JOINED.toString(), null));
		cache.retainOnly(Arrays.asList("stays"));
		assertEquals(1, cache.size());
		assertNull(cache.get("Left"));
	}

	@Test
	public void survivesJsonRoundTrip()
	{
		XpCache cache = new XpCache();
		cache.put("2006 Buffon", new XpCache.Entry(80_000_000L, NOW, JOINED.toString(), null));
		cache.put("Missing", new XpCache.Entry(null, NOW, JOINED.toString(), "Player not found."));

		XpCache loaded = XpCache.fromJson(cache.toJson(gson), gson);
		assertEquals(Long.valueOf(80_000_000L), loaded.get("2006 Buffon").getXpGained());
		assertEquals("Player not found.", loaded.get("Missing").getError());
		assertNull(loaded.get("Missing").getXpGained());
	}

	@Test
	public void corruptJsonGivesEmptyCache()
	{
		assertEquals(0, XpCache.fromJson("{not json", gson).size());
		assertEquals(0, XpCache.fromJson(null, gson).size());
	}

	private static Map<String, Long> group(Object... pairs)
	{
		Map<String, Long> map = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			map.put(XpCache.key((String) pairs[i]), (Long) pairs[i + 1]);
		}
		return map;
	}

	private static XpCache.Entry withBaseline(long gained, long baseline, long fetchedAt)
	{
		return new XpCache.Entry(gained, fetchedAt, JOINED.toString(), null, baseline);
	}

	@Test
	public void namesMatchAcrossUnderscoresHyphensAndRepeatedSpaces()
	{
		assertEquals(XpCache.key("Zezima X"), XpCache.key("zezima_x"));
		assertEquals(XpCache.key("Zezima X"), XpCache.key("ZEZIMA-X"));
		assertEquals(XpCache.key("Zezima X"), XpCache.key(" zezima  x "));
	}

	@Test
	public void baselineSurvivesTheRefreshInterval()
	{
		XpCache cache = new XpCache();
		long old = NOW - TimeUnit.HOURS.toMillis(30);
		cache.put("2006 Buffon", withBaseline(80_000_000L, 100_000_000L, old));

		// Stale, so it needs refreshing, but the baseline is still there to refresh it with
		assertTrue(cache.needsFetch("2006 Buffon", JOINED, TODAY_START));
		assertEquals(Long.valueOf(100_000_000L), cache.baselineFor("2006 Buffon", JOINED));
	}

	@Test
	public void baselineIsIgnoredWhenTheJoinDateChanges()
	{
		XpCache cache = new XpCache();
		cache.put("2006 Buffon", withBaseline(1L, 5L, NOW));
		assertNull(cache.baselineFor("2006 Buffon", JOINED.plusDays(1)));
	}

	@Test
	public void groupXpIsCurrentTotalMinusBaseline()
	{
		XpCache cache = new XpCache();
		cache.put("2006 Buffon", withBaseline(1L, 100_000_000L, NOW - TimeUnit.HOURS.toMillis(30)));

		assertTrue(cache.applyGroupXp("2006 Buffon", JOINED, group("2006 buffon", 180_000_000L), NOW));
		XpCache.Entry entry = cache.get("2006 Buffon");
		assertEquals(Long.valueOf(80_000_000L), entry.getXpGained());
		assertEquals(NOW, entry.getFetchedAt());
		assertEquals(Long.valueOf(100_000_000L), entry.getBaselineXp());
		assertFalse(cache.needsFetch("2006 Buffon", JOINED, TODAY_START));
	}

	@Test
	public void groupXpNeedsABaseline()
	{
		XpCache cache = new XpCache();
		cache.put("NoBaseline", new XpCache.Entry(5L, NOW - TimeUnit.HOURS.toMillis(30), JOINED.toString(), null));
		assertFalse(cache.applyGroupXp("NoBaseline", JOINED, group("NoBaseline", 9L), NOW));
		assertFalse(cache.applyGroupXp("NeverSeen", JOINED, group("NeverSeen", 9L), NOW));
	}

	@Test
	public void groupXpSkipsMembersTheGroupCannotAnswerFor()
	{
		XpCache cache = new XpCache();
		long old = NOW - TimeUnit.HOURS.toMillis(30);
		cache.put("NotInGroup", withBaseline(1L, 100L, old));
		cache.put("ZeroExp", withBaseline(1L, 100L, old));
		cache.put("BelowBaseline", withBaseline(1L, 100L, old));

		Map<String, Long> exp = group("ZeroExp", 0L, "BelowBaseline", 50L);
		assertFalse(cache.applyGroupXp("NotInGroup", JOINED, exp, NOW));
		assertFalse(cache.applyGroupXp("ZeroExp", JOINED, exp, NOW));
		assertFalse(cache.applyGroupXp("BelowBaseline", JOINED, exp, NOW));
		// Left untouched, so they stay stale and get an individual lookup
		assertTrue(cache.needsFetch("NotInGroup", JOINED, TODAY_START));
		assertTrue(cache.needsFetch("BelowBaseline", JOINED, TODAY_START));
	}

	@Test
	public void cachesSavedBeforeBaselinesExistLoadWithoutOne()
	{
		String oldJson = "{\"2006 buffon\":{\"xpGained\":80000000,\"fetchedAt\":1800000000000,\"joinDate\":\"2024-03-31\"}}";
		XpCache loaded = XpCache.fromJson(oldJson, gson);
		assertEquals(Long.valueOf(80_000_000L), loaded.get("2006 Buffon").getXpGained());
		assertNull(loaded.baselineFor("2006 Buffon", JOINED));
	}

	@Test
	public void baselineSurvivesJsonRoundTrip()
	{
		XpCache cache = new XpCache();
		cache.put("2006 Buffon", withBaseline(80_000_000L, 100_000_000L, NOW));
		XpCache loaded = XpCache.fromJson(cache.toJson(gson), gson);
		assertEquals(Long.valueOf(100_000_000L), loaded.baselineFor("2006 Buffon", JOINED));
	}

	@Test
	public void namesIgnoreLeadingTrailingAndRepeatedSeparators()
	{
		assertEquals("zezima x", XpCache.key("__Zezima--X__ "));
		assertEquals("zezima x", XpCache.key("\u00A0Zezima\u00A0\u00A0X"));
		assertEquals("a b c", XpCache.key("A_-_B   C"));
	}

	@Test
	public void emptyAndSeparatorOnlyNamesGiveAnEmptyKey()
	{
		assertEquals("", XpCache.key(""));
		assertEquals("", XpCache.key(" _-\u00A0"));
	}

	@Test
	public void digitsAndMixedCaseAreKept()
	{
		assertEquals("2006 buffon", XpCache.key("2006 Buffon"));
		assertEquals("618 ironman", XpCache.key("618_IRONMAN"));
	}

	@Test
	public void damagedSaveWithNullEntriesStillLoads()
	{
		String json = "{\"good\":{\"xpGained\":5,\"fetchedAt\":1,\"joinDate\":\"2024-03-31\"},\"bad\":null}";
		XpCache loaded = XpCache.fromJson(json, gson);
		assertEquals(1, loaded.size());
		assertEquals(Long.valueOf(5L), loaded.get("good").getXpGained());
	}
}
