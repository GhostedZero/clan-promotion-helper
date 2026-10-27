package com.clanpromotionhelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class VanityRulesTest
{
	private static final String TOPEZ = String.join("\n",
		"PvM Legend: rank Sergeant or 6 months; level slayer 99; kills all 12500; kills raids 50",
		"Maxed Legend: rank Sergeant or 6 months; level all except sailing 99",
		"PvM GOD: rank Sergeant or 6 months; kills TzKal-Zuk, Sol Heredit 1; kills all 5000");
	private static final Map<String, Integer> RANKS = new HashMap<>();

	static
	{
		RANKS.put("pawn", 10);
		RANKS.put("sergeant", 30);
		RANKS.put("marshall", 60);
	}

	private final VanityRules rules = VanityRules.parse(TOPEZ);

	private static Map<String, Integer> levels(int level, int sailing)
	{
		Map<String, Integer> levels = new HashMap<>();
		for (String skill : VanityRules.SKILLS)
		{
			levels.put(skill, level);
		}
		levels.put("sailing", sailing);
		return levels;
	}

	private static Map<String, Long> kills(Object... pairs)
	{
		Map<String, Long> kills = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			kills.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
		}
		return kills;
	}

	private List<String> qualified(String current, int rank, int peak, int months, Map<String, Integer> levels, Map<String, Long> kills)
	{
		return rules.qualified(current, new VanityRules.Stats(rank, peak, months, levels, kills), RANKS);
	}

	@Test
	public void clanRulesParseWithoutErrors()
	{
		assertTrue(rules.getErrors().isEmpty());
		assertEquals(3, rules.getRules().size());
		assertEquals(Arrays.asList("tzkal_zuk", "sol_heredit"), rules.getRules().get(2).getConditions().get(1).getNames());
	}

	@Test
	public void textRoundTrips()
	{
		for (VanityRules.Rule rule : rules.getRules())
		{
			assertEquals(rule, VanityRules.parse(rule.toText()).getRules().get(0));
		}
	}

	@Test
	public void pvmLegendNeedsEverything()
	{
		// 12,550 kills in all, skilling bosses included, 50 of them raids
		Map<String, Long> enough = kills("zulrah", 12_000, "chambers_of_xeric", 30, "tombs_of_amascut_expert", 20, "wintertodt", 500);
		Map<String, Integer> slayer99 = levels(80, 1);
		slayer99.put("slayer", 99);
		assertEquals(Collections.singletonList("PvM Legend"), qualified("Sergeant", 30, 30, 1, slayer99, enough));

		Map<String, Integer> slayer98 = levels(80, 1);
		slayer98.put("slayer", 98);
		assertTrue(qualified("Sergeant", 30, 30, 1, slayer98, enough).isEmpty());

		assertTrue(qualified("Sergeant", 30, 30, 1, slayer99, kills("zulrah", 12_500, "chambers_of_xeric", 49)).isEmpty());
		assertTrue(qualified("Sergeant", 30, 30, 1, slayer99, kills("zulrah", 12_449, "chambers_of_xeric", 50)).isEmpty());
	}

	@Test
	public void rankOrTimePrerequisite()
	{
		Map<String, Integer> maxed = levels(99, 1);
		// Pawn for 5 months: neither half
		assertTrue(qualified("Pawn", 10, 10, 5, maxed, kills()).isEmpty());
		// Pawn for 6 months: time half
		assertEquals(Collections.singletonList("Maxed Legend"), qualified("Pawn", 10, 10, 6, maxed, kills()));
		// Higher rank than Sergeant
		assertEquals(Collections.singletonList("Maxed Legend"), qualified("Marshall", 60, 60, 0, maxed, kills()));
		// Now on a low-numbered vanity rank, but the plugin saw them reach Sergeant
		assertEquals(Collections.singletonList("Maxed Legend"), qualified("PvM GOD", 5, 30, 0, maxed, kills()));
	}

	@Test
	public void maxedIgnoresSailingOnly()
	{
		assertEquals(Collections.singletonList("Maxed Legend"), qualified("Sergeant", 30, 30, 0, levels(99, 1), kills()));
		Map<String, Integer> oneShort = levels(99, 1);
		oneShort.put("construction", 98);
		assertTrue(qualified("Sergeant", 30, 30, 0, oneShort, kills()).isEmpty());
		// A skill Wise Old Man didn't return can't be assumed to be 99
		Map<String, Integer> missing = levels(99, 1);
		missing.remove("hunter");
		assertTrue(qualified("Sergeant", 30, 30, 0, missing, kills()).isEmpty());
	}

	@Test
	public void pvmGodNeedsZukOrSol()
	{
		Map<String, Integer> low = levels(70, 1);
		assertEquals(Collections.singletonList("PvM GOD"), qualified("Sergeant", 30, 30, 0, low, kills("sol_heredit", 1, "vorkath", 4999)));
		assertTrue(qualified("Sergeant", 30, 30, 0, low, kills("vorkath", 6000)).isEmpty());
		assertTrue(qualified("Sergeant", 30, 30, 0, low, kills("tzkal_zuk", 1, "vorkath", 4998)).isEmpty());
	}

	@Test
	public void heldRankIsNotSuggestedAgain()
	{
		assertTrue(qualified("Maxed Legend", 30, 30, 0, levels(99, 1), kills()).isEmpty());
		assertTrue(rules.appliesTo("Maxed Legend"));
		assertFalse(VanityRules.parse("Maxed Legend: 6 months").appliesTo("maxed legend"));
		assertFalse(VanityRules.parse("").appliesTo("Pawn"));
	}

	@Test
	public void unknownRankTitleNeverMatchesButTimeStillCan()
	{
		VanityRules custom = VanityRules.parse("Shiny: rank Nonexistent or 3 months");
		VanityRules.Stats stats = new VanityRules.Stats(100, 100, 2, levels(1, 1), kills());
		assertTrue(custom.qualified("Pawn", stats, RANKS).isEmpty());
		assertEquals(Collections.singletonList("Shiny"),
			custom.qualified("Pawn", new VanityRules.Stats(100, 100, 3, levels(1, 1), kills()), RANKS));
	}

	@Test
	public void oversizedNumbersAreBadLinesNotCrashes()
	{
		assertNull(VanityRules.parseCondition("kills all 99999999999999999999"));
		assertNull(VanityRules.parseCondition("rank Sergeant or 99999999999 months"));
		assertEquals(1, VanityRules.parse("Huge: level slayer 99999999999999999999").getErrors().size());
	}

	@Test
	public void unknownRankTitlesAreReportedOnceTitlesAreKnown()
	{
		VanityRules custom = VanityRules.parse("God: rank Marshal or 6 months\nLegend: rank sergeant; 3 months");
		assertEquals(Collections.singletonList("God: no rank called \"Marshal\" (it never matches)"),
			custom.unknownRanks(RANKS.keySet()));
		assertTrue(custom.unknownRanks(Collections.emptySet()).isEmpty());
	}

	@Test
	public void badLinesAreReported()
	{
		VanityRules bad = VanityRules.parse("No colon here\nEmpty:\nTypo: level slayr 99\nFine: kills all 1\nWhat: dance 5");
		assertEquals(1, bad.getRules().size());
		assertEquals(4, bad.getErrors().size());
		assertNull(VanityRules.parseCondition("level all except sailling 99"));
		assertEquals(Collections.singletonList("runecrafting"), VanityRules.parseCondition("level Runecraft 99").getNames());
	}
}
