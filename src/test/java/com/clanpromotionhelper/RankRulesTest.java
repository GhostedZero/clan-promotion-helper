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
	public void customTableSkipsAndReportsBadLines()
	{
		RankRules custom = RankRules.parse("Bronze:0:0\n\nnot a rank\nSilver:x:1\nGold:1:0.5\nIron:1:1:5:MAYBE\nTin:-1:0\n");
		assertEquals(2, custom.getRanks().size());
		assertEquals("Gold", custom.nextRankFor("Bronze", LocalDate.of(2026, 9, 1), 500_000L, TODAY));
		assertEquals(4, custom.getErrors().size());
		assertTrue(custom.getErrors().get(0).startsWith("Line 3 "));
		assertTrue(rules.getErrors().isEmpty());
	}

	private static final LocalDate LONG_AGO = LocalDate.of(2020, 1, 1);

	private static PromotionStatus status(String table, Long xp, Long bosses)
	{
		return RankRules.parse(table).evaluate("Bronze", LONG_AGO, xp, bosses, TODAY).getStatus();
	}

	@Test
	public void bossesAndLogicParse()
	{
		RankRules.Rank silver = RankRules.parse("Bronze:0:0\nSilver:1:2:25:or").getRanks().get(1);
		assertEquals(25, silver.getBosses());
		assertTrue(silver.isEither());
		assertFalse(RankRules.parse("Bronze:0:0\nSilver:1:2:25").getRanks().get(1).isEither());
	}

	@Test
	public void orWithZeroBossesStillNeedsTheXp()
	{
		// rankforge's default criteria passed everyone here, because 0 kills >= 0 is always true
		assertEquals(PromotionStatus.NOT_READY, status("Bronze:0:0\nSilver:0:1:0:OR", 0L, 0L));
		assertEquals(PromotionStatus.READY, status("Bronze:0:0\nSilver:0:1:0:OR", 1_000_000L, null));
	}

	@Test
	public void andNeedsBoth()
	{
		String table = "Bronze:0:0\nSilver:0:1:10:AND";
		assertEquals(PromotionStatus.NOT_READY, status(table, 1_000_000L, 9L));
		assertEquals(PromotionStatus.NOT_READY, status(table, 999_999L, 10L));
		assertEquals(PromotionStatus.READY, status(table, 1_000_000L, 10L));
	}

	@Test
	public void orNeedsEither()
	{
		String table = "Bronze:0:0\nSilver:0:1:10:OR";
		assertEquals(PromotionStatus.READY, status(table, 1_000_000L, 0L));
		assertEquals(PromotionStatus.READY, status(table, 0L, 10L));
		assertEquals(PromotionStatus.NOT_READY, status(table, 0L, 9L));
	}

	@Test
	public void unknownBossKillsAreNeverZero()
	{
		assertEquals(PromotionStatus.NO_XP, status("Bronze:0:0\nSilver:0:0:10", 5_000_000L, null));
		// OR: enough XP decides it without the kills, not enough XP needs them
		assertEquals(PromotionStatus.READY, status("Bronze:0:0\nSilver:0:1:10:OR", 1_000_000L, null));
		assertEquals(PromotionStatus.NO_XP, status("Bronze:0:0\nSilver:0:1:10:OR", 0L, null));
		// AND: not enough XP already rules it out
		assertEquals(PromotionStatus.NOT_READY, status("Bronze:0:0\nSilver:0:1:10:AND", 0L, null));
	}

	@Test
	public void monthsStayAHardGateWhateverTheLogic()
	{
		RankRules custom = RankRules.parse("Bronze:0:0\nSilver:3:1:10:OR");
		assertEquals(PromotionStatus.NOT_READY,
			custom.evaluate("Bronze", LocalDate.of(2026, 9, 1), 9_000_000L, 99L, TODAY).getStatus());
	}

	@Test
	public void nextNeedsBossesLooksAtTheNextRank()
	{
		RankRules custom = RankRules.parse("Bronze:0:0\nSilver:0:1:10\nGold:0:1");
		assertTrue(custom.nextNeedsBosses("bronze"));
		assertFalse(custom.nextNeedsBosses("Silver"));
		assertFalse(custom.nextNeedsBosses("Gold"));
		assertFalse(custom.nextNeedsBosses("Owner"));
	}

	@Test
	public void evaluateReportsEachStatus()
	{
		assertEquals(PromotionStatus.READY,
			rules.evaluate("Admiral", LocalDate.of(2024, 3, 31), 80_000_000L, null, TODAY).getStatus());
		assertEquals(PromotionStatus.NOT_READY,
			rules.evaluate("Recruit", LocalDate.of(2025, 7, 26), 1_000L, null, TODAY).getStatus());
		assertEquals(PromotionStatus.NO_XP,
			rules.evaluate("Recruit", LocalDate.of(2024, 1, 1), null, null, TODAY).getStatus());
		assertEquals(PromotionStatus.MAX_RANK,
			rules.evaluate("Marshal", LocalDate.of(2020, 1, 1), 500_000_000L, null, TODAY).getStatus());
		assertEquals(PromotionStatus.UNKNOWN_RANK,
			rules.evaluate("Owner", LocalDate.of(2020, 1, 1), 500_000_000L, null, TODAY).getStatus());
		assertEquals(PromotionStatus.UNKNOWN_RANK,
			rules.evaluate(null, LocalDate.of(2020, 1, 1), 500_000_000L, null, TODAY).getStatus());
	}

	@Test
	public void evaluateIncludesNextRankAndRequirementsEvenWhenNotReady()
	{
		RankRules.Evaluation evaluation = rules.evaluate("Recruit", LocalDate.of(2026, 10, 1), 50_000_000L, null, TODAY);
		assertEquals(PromotionStatus.NOT_READY, evaluation.getStatus());
		assertEquals("Corporal", evaluation.getNextRank());
		assertEquals(0, evaluation.getMonths());
		assertEquals(2, evaluation.getRequiredMonths());
		assertEquals(0.5, evaluation.getRequiredXpMillions(), 0.0001);
	}
}
