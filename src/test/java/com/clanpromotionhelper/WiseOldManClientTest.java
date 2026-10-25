package com.clanpromotionhelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.clanpromotionhelper.WiseOldManClient.Result;
import com.google.gson.Gson;
import org.junit.Test;

public class WiseOldManClientTest
{
	private final Gson gson = new Gson();

	@Test
	public void successReturnsOverallXpGained()
	{
		Result result = WiseOldManClient.parse(200,
			"{\"data\":{\"skills\":{\"overall\":{\"experience\":{\"gained\":5000000}}}}}", null, gson);
		assertEquals(Result.Status.OK, result.getStatus());
		assertEquals(Long.valueOf(5_000_000L), result.getXpGained());
	}

	@Test
	public void zeroGainedIsStillASuccess()
	{
		Result result = WiseOldManClient.parse(200,
			"{\"data\":{\"skills\":{\"overall\":{\"experience\":{\"gained\":0}}}}}", null, gson);
		assertEquals(Result.Status.OK, result.getStatus());
		assertEquals(Long.valueOf(0L), result.getXpGained());
	}

	@Test
	public void notFoundIsAFailureNotZeroXp()
	{
		Result result = WiseOldManClient.parse(404, "{\"message\":\"Player not found.\"}", null, gson);
		assertEquals(Result.Status.FAILED, result.getStatus());
		assertNull(result.getXpGained());
		assertEquals("Player not found.", result.getError());
	}

	@Test
	public void rateLimitRetriesAfterHeader()
	{
		Result result = WiseOldManClient.parse(429, "{\"message\":\"Too many requests\"}", "12", gson);
		assertEquals(Result.Status.RETRY, result.getStatus());
		assertEquals(12, result.getRetryAfterSeconds());
	}

	@Test
	public void rateLimitWithoutHeaderWaitsAMinute()
	{
		Result result = WiseOldManClient.parse(429, "", null, gson);
		assertEquals(Result.Status.RETRY, result.getStatus());
		assertEquals(60, result.getRetryAfterSeconds());
	}

	@Test
	public void serverErrorRetries()
	{
		Result result = WiseOldManClient.parse(503, "<html>down</html>", null, gson);
		assertEquals(Result.Status.RETRY, result.getStatus());
	}

	@Test
	public void missingOverallXpIsAFailure()
	{
		Result result = WiseOldManClient.parse(200, "{\"data\":{\"skills\":{}}}", null, gson);
		assertEquals(Result.Status.FAILED, result.getStatus());
		assertNull(result.getXpGained());
	}

	@Test
	public void nonJsonSuccessIsAFailure()
	{
		Result result = WiseOldManClient.parse(200, "not json", null, gson);
		assertEquals(Result.Status.FAILED, result.getStatus());
	}

	@Test
	public void userAgentIncludesContactWhenSet()
	{
		assertEquals("ClanPromotionHelper (RuneLite plugin); contact: Player#1234", WiseOldManClient.userAgent(" Player#1234 "));
		assertEquals("ClanPromotionHelper (RuneLite plugin)", WiseOldManClient.userAgent(""));
	}

	// Trimmed from a real Wise Old Man /players/{name}/gained response
	private static final String REAL_PLAYER_GAINED = "{\"startsAt\":\"2025-01-01T10:43:42.703Z\",\"endsAt\":\"2026-10-23T06:44:13.987Z\","
		+ "\"data\":{\"skills\":{\"overall\":{\"metric\":\"overall\",\"experience\":{\"gained\":260267325,\"start\":182870123,\"end\":443137448}}}}}";

	// Trimmed from a real Wise Old Man /groups/{id} response
	private static final String REAL_GROUP = "{\"id\":6371,\"memberCount\":3,\"memberships\":["
		+ "{\"playerId\":1,\"role\":\"member\",\"player\":{\"username\":\"vghosted\",\"displayName\":\"vghosted\",\"exp\":443293491,\"status\":\"active\"}},"
		+ "{\"playerId\":2,\"role\":\"member\",\"player\":{\"username\":\"ghosted kate\",\"displayName\":\"Ghosted Kate\",\"exp\":12345678}},"
		+ "{\"playerId\":3,\"role\":\"member\",\"player\":{\"username\":\"untracked guy\",\"displayName\":\"Untracked_Guy\",\"exp\":0}}]}";

	@Test
	public void realPlayerResponseGivesGainedAndBaseline()
	{
		Result result = WiseOldManClient.parse(200, REAL_PLAYER_GAINED, null, gson);
		assertEquals(Result.Status.OK, result.getStatus());
		assertEquals(Long.valueOf(260_267_325L), result.getXpGained());
		assertEquals(Long.valueOf(182_870_123L), result.getStartXp());
	}

	@Test
	public void missingOrNegativeStartMeansNoBaseline()
	{
		Result missing = WiseOldManClient.parse(200,
			"{\"data\":{\"skills\":{\"overall\":{\"experience\":{\"gained\":5}}}}}", null, gson);
		assertNull(missing.getStartXp());

		Result negative = WiseOldManClient.parse(200,
			"{\"data\":{\"skills\":{\"overall\":{\"experience\":{\"gained\":5,\"start\":-1,\"end\":9}}}}}", null, gson);
		assertEquals(Long.valueOf(5L), negative.getXpGained());
		assertNull(negative.getStartXp());
	}

	@Test
	public void groupResponseIsKeyedByUsernameAndDisplayName()
	{
		WiseOldManClient.GroupResult result = WiseOldManClient.parseGroup(200, REAL_GROUP, gson);
		assertTrue(result.isOk());
		assertEquals(Long.valueOf(443_293_491L), result.getExp().get(XpCache.key("vghosted")));
		assertEquals(Long.valueOf(12_345_678L), result.getExp().get(XpCache.key("ghosted kate")));
		assertEquals(Long.valueOf(12_345_678L), result.getExp().get(XpCache.key("Ghosted Kate")));
	}

	@Test
	public void groupMembersWithZeroExpAreLeftOut()
	{
		WiseOldManClient.GroupResult result = WiseOldManClient.parseGroup(200, REAL_GROUP, gson);
		assertNull(result.getExp().get(XpCache.key("untracked guy")));
		assertNull(result.getExp().get(XpCache.key("Untracked_Guy")));
	}

	@Test
	public void groupNamesMatchAcrossUnderscoresAndHyphens()
	{
		WiseOldManClient.GroupResult result = WiseOldManClient.parseGroup(200,
			"{\"memberships\":[{\"player\":{\"username\":\"zezima x\",\"displayName\":\"Zezima-X\",\"exp\":10}}]}", gson);
		assertEquals(Long.valueOf(10L), result.getExp().get(XpCache.key("Zezima_X")));
		assertEquals(Long.valueOf(10L), result.getExp().get(XpCache.key("zezima x")));
	}

	@Test
	public void groupNotFoundIsAFailureWithTheServersMessage()
	{
		WiseOldManClient.GroupResult result = WiseOldManClient.parseGroup(404, "{\"message\":\"Group not found.\"}", gson);
		assertFalse(result.isOk());
		assertEquals("Group not found.", result.getError());
	}

	@Test
	public void groupRateLimitIsAFailureNotARetry()
	{
		WiseOldManClient.GroupResult result = WiseOldManClient.parseGroup(429, "", gson);
		assertFalse(result.isOk());
		assertEquals("Rate limited by Wise Old Man", result.getError());
	}

	@Test
	public void groupWithoutMembershipsIsAFailure()
	{
		assertFalse(WiseOldManClient.parseGroup(200, "{\"id\":1}", gson).isOk());
		assertFalse(WiseOldManClient.parseGroup(200, "not json", gson).isOk());
	}
}
