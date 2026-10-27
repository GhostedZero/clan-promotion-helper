package com.clanpromotionhelper;

import static org.junit.Assert.assertEquals;
import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.Collections;
import org.junit.Test;

public class RankHistoryTest
{
	private static final LocalDate JOINED = LocalDate.of(2024, 3, 31);
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
	private final Gson gson = new Gson();

	@Test
	public void firstSightingFallsBackToJoinDate()
	{
		RankHistory history = new RankHistory();
		history.record("2006 Buffon", 10, TODAY);
		history.record("2006 Buffon", 10, TODAY.plusDays(3));
		assertEquals(JOINED, history.since("2006 Buffon", JOINED));
		assertEquals(JOINED, history.since("Never seen", JOINED));
	}

	@Test
	public void rankChangeIsDatedToWhenItWasSeen()
	{
		RankHistory history = new RankHistory();
		history.record("2006 Buffon", 10, TODAY.minusDays(30));
		history.record("2006 buffon", 20, TODAY);
		assertEquals(TODAY, history.since("2006 Buffon", JOINED));
	}

	@Test
	public void changeFromBeforeRejoiningUsesTheNewJoinDate()
	{
		RankHistory history = new RankHistory();
		history.record("Rejoined", 10, TODAY.minusDays(100));
		history.record("Rejoined", 20, TODAY.minusDays(50));
		assertEquals(TODAY.minusDays(10), history.since("Rejoined", TODAY.minusDays(10)));
	}

	@Test
	public void survivesSaveAndLoadAndDropsLeavers()
	{
		RankHistory history = new RankHistory();
		history.record("Stays", 10, TODAY.minusDays(1));
		history.record("Stays", 20, TODAY);
		history.record("Left", 10, TODAY);
		history.retainOnly(Collections.singletonList("Stays"));

		RankHistory loaded = RankHistory.fromJson(history.toJson(gson), gson);
		assertEquals(TODAY, loaded.since("Stays", JOINED));
		// Left was dropped, so if they come back their first sighting starts fresh
		loaded.record("Left", 20, TODAY);
		assertEquals(JOINED, loaded.since("Left", JOINED));
	}

	@Test
	public void peakRankSurvivesMovingDown()
	{
		RankHistory history = new RankHistory();
		history.record("Legend", 30, TODAY.minusDays(10));
		history.record("Legend", 5, TODAY);
		assertEquals(30, history.peak("Legend", 5));
		assertEquals(7, history.peak("Unseen", 7));
		// Saves from before peaks were recorded load as 0 and catch up with the current rank
		RankHistory old = RankHistory.fromJson("{\"legend\":{\"rank\":20}}", gson);
		old.record("Legend", 20, TODAY);
		assertEquals(20, old.peak("Legend", 0));
	}

	@Test
	public void damagedSavesAreIgnored()
	{
		assertEquals(JOINED, RankHistory.fromJson("not json", gson).since("x", JOINED));
		assertEquals(JOINED, RankHistory.fromJson("{\"x\":{\"rank\":1,\"since\":\"garbage\"}}", gson).since("x", JOINED));
	}
}
