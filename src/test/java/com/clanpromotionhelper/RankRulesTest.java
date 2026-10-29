package com.clanpromotionhelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.time.LocalDate;
import org.junit.Test;

public class RankRulesTest
{
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
	private final RankRules rules = RankRules.parse(RankRules.DEFAULT_TABLE);

	@Test
	public void defaultTableHasAllRanks()
	{
		assertEquals(13, rules.getRanks().size());
		assertEquals("Thief", rules.getRanks().get(0).getName());
		assertEquals("Marshal", rules.getRanks().get(12).getName());
	}

	@Test
	public void admiralWithEnoughXpAndTimeBecomesMarshal()
	{
		assertEquals("Marshal", rules.nextRankFor("Admiral", LocalDate.of(2024, 3, 31), 80_000_000L, TODAY));
	}

	@Test
	public void recruitWithEnoughXpAndTimeBecomesCorporal()
	{
		assertEquals("Corporal", rules.nextRankFor("Recruit", LocalDate.of(2026, 8, 1), 600_000L, TODAY));
	}

	@Test
	public void rankMatchIsCaseInsensitive()
	{
		assertEquals("Lieutenant", rules.nextRankFor("sergeant", LocalDate.of(2025, 1, 1), 2_500_000L, TODAY));
	}

	@Test
	public void notEnoughXpMeansNoPromotion()
	{
		assertNull(rules.nextRankFor("Recruit", LocalDate.of(2025, 7, 26), 1_000L, TODAY));
	}

	@Test
	public void notEnoughTimeMeansNoPromotion()
	{
		assertNull(rules.nextRankFor("Recruit", LocalDate.of(2026, 10, 1), 50_000_000L, TODAY));
	}

	@Test
	public void missingXpMeansNoPromotion()
	{
		assertNull(rules.nextRankFor("Recruit", LocalDate.of(2024, 1, 1), null, TODAY));
	}

	@Test
	public void topRankHasNoPromotion()
	{
		assertNull(rules.nextRankFor("Marshal", LocalDate.of(2020, 1, 1), 500_000_000L, TODAY));
	}

	@Test
	public void unknownRankHasNoPromotion()
	{
		assertNull(rules.nextRankFor("Owner", LocalDate.of(2020, 1, 1), 500_000_000L, TODAY));
	}

	@Test
	public void monthCountsOnlyOnceTheJoinDayArrives()
	{
		// Joined the 25th: on the 24th of a later month the last month isn't complete yet
		assertEquals(1, RankRules.monthsInClan(LocalDate.of(2026, 8, 25), TODAY));
		assertEquals(2, RankRules.monthsInClan(LocalDate.of(2026, 8, 24), TODAY));
		assertEquals(0, RankRules.monthsInClan(LocalDate.of(2026, 10, 25), TODAY));
	}

	@Test
	public void monthBoundaryDecidesPromotion()
	{
		// Corporal needs 2 months: joined 2026-08-25 is only 1 full month on 2026-10-24
		assertNull(rules.nextRankFor("Recruit", LocalDate.of(2026, 8, 25), 600_000L, TODAY));
		assertEquals("Corporal", rules.nextRankFor("Recruit", LocalDate.of(2026, 8, 24), 600_000L, TODAY));
	}

	@Test
	public void customTableSkipsBadLines()
	{
		RankRules custom = RankRules.parse("Bronze:0:0\n\nnot a rank\nSilver:x:1\nGold:1:0.5\n");
		assertEquals(2, custom.getRanks().size());
		assertEquals("Gold", custom.nextRankFor("Bronze", LocalDate.of(2026, 9, 1), 500_000L, TODAY));
	}

	@Test
	public void onlyLadderRanksBelowTheTopCanBePromoted()
	{
		assertTrue(rules.canBePromoted("Recruit"));
		assertTrue(rules.canBePromoted("admiral"));
		assertFalse(rules.canBePromoted("Marshal"));
		assertFalse(rules.canBePromoted("Owner"));
		assertFalse(rules.canBePromoted(null));
	}

	@Test
	public void evaluateReportsEachStatus()
	{
		assertEquals(PromotionStatus.READY,
			rules.evaluate("Admiral", LocalDate.of(2024, 3, 31), 80_000_000L, TODAY).getStatus());
		assertEquals(PromotionStatus.NOT_READY,
			rules.evaluate("Recruit", LocalDate.of(2025, 7, 26), 1_000L, TODAY).getStatus());
		assertEquals(PromotionStatus.NO_XP,
			rules.evaluate("Recruit", LocalDate.of(2024, 1, 1), null, TODAY).getStatus());
		assertEquals(PromotionStatus.MAX_RANK,
			rules.evaluate("Marshal", LocalDate.of(2020, 1, 1), 500_000_000L, TODAY).getStatus());
		assertEquals(PromotionStatus.UNKNOWN_RANK,
			rules.evaluate("Owner", LocalDate.of(2020, 1, 1), 500_000_000L, TODAY).getStatus());
		assertEquals(PromotionStatus.UNKNOWN_RANK,
			rules.evaluate(null, LocalDate.of(2020, 1, 1), 500_000_000L, TODAY).getStatus());
	}

	@Test
	public void evaluateIncludesNextRankAndRequirementsEvenWhenNotReady()
	{
		RankRules.Evaluation evaluation = rules.evaluate("Recruit", LocalDate.of(2026, 10, 1), 50_000_000L, TODAY);
		assertEquals(PromotionStatus.NOT_READY, evaluation.getStatus());
		assertEquals("Corporal", evaluation.getNextRank());
		assertEquals(0, evaluation.getMonths());
		assertEquals(2, evaluation.getRequiredMonths());
		assertEquals(0.5, evaluation.getRequiredXpMillions(), 0.0001);
	}
}
