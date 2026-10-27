package com.clanpromotionhelper;

import static com.clanpromotionhelper.PromotionStatus.FAILED;
import static com.clanpromotionhelper.PromotionStatus.IGNORED;
import static com.clanpromotionhelper.PromotionStatus.MAX_RANK;
import static com.clanpromotionhelper.PromotionStatus.NOT_READY;
import static com.clanpromotionhelper.PromotionStatus.NO_XP;
import static com.clanpromotionhelper.PromotionStatus.PENDING;
import static com.clanpromotionhelper.PromotionStatus.READY;
import static com.clanpromotionhelper.PromotionStatus.UNKNOWN_RANK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.clanpromotionhelper.ClanPromotionHelperPlugin.Outcome;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class OutcomeTest
{
	private static final Set<String> NONE = Collections.emptySet();

	private static Outcome looked(PromotionStatus ladder, boolean vanityApplies, List<String> vanityMet, boolean playerIgnored, Set<String> ignored)
	{
		return ClanPromotionHelperPlugin.decide(ladder, "Corporal", true, true, null, vanityApplies, vanityMet, playerIgnored, ignored);
	}

	@Test
	public void ladderOnly()
	{
		Outcome outcome = looked(READY, false, null, false, NONE);
		assertEquals(READY, outcome.getStatus());
		assertEquals(Collections.singletonList("Corporal"), outcome.getTargets());
		assertTrue(outcome.isShowLadder());
		assertEquals(NOT_READY, looked(NOT_READY, false, null, false, NONE).getStatus());
	}

	@Test
	public void vanityOnlyHidesTheLadderBars()
	{
		Outcome outcome = looked(UNKNOWN_RANK, true, Collections.singletonList("PvM GOD"), false, NONE);
		assertEquals(READY, outcome.getStatus());
		assertEquals(Collections.singletonList("PvM GOD"), outcome.getTargets());
		assertFalse(outcome.isShowLadder());
	}

	@Test
	public void bothAreListed()
	{
		assertEquals(Arrays.asList("Corporal", "PvM GOD"), looked(READY, true, Collections.singletonList("PvM GOD"), false, NONE).getTargets());
	}

	@Test
	public void ignoredTargetsAndPlayers()
	{
		// Only the ladder target is ignored: the vanity rank still shows, without the ladder's bars
		Outcome partly = looked(READY, true, Collections.singletonList("PvM GOD"), false, new HashSet<>(Collections.singletonList("corporal")));
		assertEquals(READY, partly.getStatus());
		assertEquals(Collections.singletonList("PvM GOD"), partly.getTargets());
		assertFalse(partly.isShowLadder());

		Outcome all = looked(READY, true, Collections.singletonList("PvM GOD"), false, new HashSet<>(Arrays.asList("corporal", "pvm god")));
		assertEquals(IGNORED, all.getStatus());
		assertTrue(all.getTargets().isEmpty());

		assertEquals(IGNORED, looked(READY, false, null, true, NONE).getStatus());
	}

	@Test
	public void missingDataWaitsInsteadOfFailingTheMember()
	{
		// Vanity ranks apply but levels haven't been looked up
		assertEquals(PENDING, looked(UNKNOWN_RANK, true, null, false, NONE).getStatus());
		// The ladder already says ready, so missing vanity data doesn't hide that
		assertEquals(READY, looked(READY, true, null, false, NONE).getStatus());
		// Group refresh gave XP but no boss kills
		assertEquals(PENDING, looked(NO_XP, false, null, false, NONE).getStatus());
	}

	@Test
	public void noLookupYetOrFailedLookup()
	{
		assertEquals(PENDING, ClanPromotionHelperPlugin.decide(NOT_READY, "Corporal", false, false, null, false, null, false, NONE).getStatus());
		// Off the ladder with no vanity ranks: say why, not "waiting"
		assertEquals(UNKNOWN_RANK, ClanPromotionHelperPlugin.decide(UNKNOWN_RANK, null, false, false, null, false, null, false, NONE).getStatus());
		assertEquals(PENDING, ClanPromotionHelperPlugin.decide(MAX_RANK, null, false, false, null, true, null, false, NONE).getStatus());

		Outcome failed = ClanPromotionHelperPlugin.decide(NO_XP, "Corporal", true, false, "Player not found.", true, null, false, NONE);
		assertEquals(FAILED, failed.getStatus());
		assertEquals("Player not found.", failed.getDetail());
	}
}
