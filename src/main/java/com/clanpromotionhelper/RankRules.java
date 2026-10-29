package com.clanpromotionhelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Value;

/**
 * The clan's promotion ladder: each rank needs a minimum number of whole months in the clan
 * and a minimum amount of XP gained since joining. A member qualifies for the next rank only.
 */
public final class RankRules
{
	static final String DEFAULT_TABLE = String.join("\n",
		"Thief:0:0",
		"Recruit:1:0.1",
		"Corporal:2:0.5",
		"Sergeant:3:1",
		"Lieutenant:4:2",
		"Captain:5:3",
		"General:6:8",
		"Officer:9:12",
		"Commander:12:20",
		"Colonel:15:25",
		"Brigadier:17:30",
		"Admiral:20:50",
		"Marshal:24:75");

	@Value
	static class Rank
	{
		String name;
		int months;
		double xpMillions;
	}

	private final List<Rank> ranks;

	private RankRules(List<Rank> ranks)
	{
		this.ranks = Collections.unmodifiableList(ranks);
	}

	/**
	 * Parses one rank per line as {@code Name:months:xpMillions}, lowest rank first.
	 * Blank and malformed lines are skipped.
	 */
	static RankRules parse(String table)
	{
		List<Rank> ranks = new ArrayList<>();
		if (table == null)
		{
			return new RankRules(ranks);
		}

		for (String line : table.split("\\r?\\n"))
		{
			String[] parts = line.split(":");
			if (parts.length != 3 || parts[0].trim().isEmpty())
			{
				continue;
			}

			try
			{
				ranks.add(new Rank(parts[0].trim(), Integer.parseInt(parts[1].trim()), Double.parseDouble(parts[2].trim())));
			}
			catch (NumberFormatException e)
			{
				// Skip lines whose numbers don't parse
			}
		}

		return new RankRules(ranks);
	}

	List<Rank> getRanks()
	{
		return ranks;
	}

	/** Whether a member with this rank can move up the ladder: not true of top ranks or ones the table doesn't have, such as staff. */
	boolean canBePromoted(String currentRank)
	{
		PromotionStatus status = evaluate(currentRank, null, null, LocalDate.MIN).getStatus();
		return status != PromotionStatus.UNKNOWN_RANK && status != PromotionStatus.MAX_RANK;
	}

	/**
	 * Whole months between the join date and today, one fewer if this month's join day hasn't arrived yet.
	 */
	static int monthsInClan(LocalDate joinDate, LocalDate today)
	{
		int months = (today.getYear() - joinDate.getYear()) * 12 + (today.getMonthValue() - joinDate.getMonthValue());
		if (today.getDayOfMonth() < joinDate.getDayOfMonth())
		{
			months -= 1;
		}
		return Math.max(0, months);
	}

	@Value
	static class Evaluation
	{
		PromotionStatus status;
		/** The rank after the member's current one; null for unknown or top ranks */
		String nextRank;
		int months;
		int requiredMonths;
		double requiredXpMillions;
	}

	/**
	 * Returns the rank the member should be promoted to, or null if they don't qualify yet,
	 * are already at the top rank, hold a rank that isn't in the table, or have no XP data.
	 */
	String nextRankFor(String currentRank, LocalDate joinDate, Long xpGained, LocalDate today)
	{
		Evaluation evaluation = evaluate(currentRank, joinDate, xpGained, today);
		return evaluation.getStatus() == PromotionStatus.READY ? evaluation.getNextRank() : null;
	}

	/**
	 * Works out whether a member is due a promotion and, if not, why not.
	 */
	Evaluation evaluate(String currentRank, LocalDate joinDate, Long xpGained, LocalDate today)
	{
		int months = joinDate == null ? 0 : monthsInClan(joinDate, today);

		int currentIndex = -1;
		if (currentRank != null)
		{
			for (int i = 0; i < ranks.size(); i++)
			{
				if (ranks.get(i).getName().equalsIgnoreCase(currentRank.trim()))
				{
					currentIndex = i;
					break;
				}
			}
		}

		if (currentIndex == -1)
		{
			return new Evaluation(PromotionStatus.UNKNOWN_RANK, null, months, 0, 0);
		}

		if (currentIndex + 1 >= ranks.size())
		{
			return new Evaluation(PromotionStatus.MAX_RANK, null, months, 0, 0);
		}

		Rank next = ranks.get(currentIndex + 1);
		if (joinDate == null || xpGained == null)
		{
			return new Evaluation(PromotionStatus.NO_XP, next.getName(), months, next.getMonths(), next.getXpMillions());
		}

		boolean ready = months >= next.getMonths() && xpGained / 1_000_000.0 >= next.getXpMillions();
		return new Evaluation(ready ? PromotionStatus.READY : PromotionStatus.NOT_READY,
			next.getName(), months, next.getMonths(), next.getXpMillions());
	}
}
