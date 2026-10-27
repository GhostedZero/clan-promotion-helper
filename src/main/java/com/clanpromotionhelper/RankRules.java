package com.clanpromotionhelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import lombok.Value;

/**
 * The clan's promotion ladder: each rank needs a minimum number of whole months and activity since
 * the member's window started (their join date, or their last rank change): XP gained, boss kills, or
 * both. A member qualifies for the next rank only.
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
		/** Boss kills needed; 0 when bosses aren't part of the rule */
		int bosses;
		/** True when either XP or boss kills is enough, false when both are needed */
		boolean either;
	}

	private final List<Rank> ranks;
	/** One message per line that was skipped, for the sidebar */
	private final List<String> errors;

	private RankRules(List<Rank> ranks, List<String> errors)
	{
		this.ranks = Collections.unmodifiableList(ranks);
		this.errors = Collections.unmodifiableList(errors);
	}

	/**
	 * Parses one rank per line, lowest rank first, as {@code Name:months:xpMillions} with optional
	 * {@code :bosses} and {@code :AND}/{@code :OR} (default AND). A requirement of 0 isn't part of the rule,
	 * so OR with 0 boss kills still needs the XP. Blank lines are skipped; malformed ones are skipped and
	 * reported by {@link #getErrors}.
	 */
	static RankRules parse(String table)
	{
		List<Rank> ranks = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		if (table == null)
		{
			return new RankRules(ranks, errors);
		}

		String[] lines = table.split("\\r?\\n");
		for (int i = 0; i < lines.length; i++)
		{
			String line = lines[i].trim();
			if (line.isEmpty())
			{
				continue;
			}

			String error = null;
			String[] parts = line.split(":");
			if (parts.length < 3 || parts.length > 5 || parts[0].trim().isEmpty())
			{
				error = "expected Rank:months:XP[:bosses[:AND|OR]]";
			}
			else
			{
				try
				{
					int months = Integer.parseInt(parts[1].trim());
					double xp = Double.parseDouble(parts[2].trim());
					int bosses = parts.length > 3 ? Integer.parseInt(parts[3].trim()) : 0;
					String logic = parts.length > 4 ? parts[4].trim().toUpperCase(Locale.ROOT) : "AND";
					if (months < 0 || xp < 0 || bosses < 0)
					{
						error = "numbers can't be negative";
					}
					else if (!logic.equals("AND") && !logic.equals("OR"))
					{
						error = "last part must be AND or OR";
					}
					else
					{
						ranks.add(new Rank(parts[0].trim(), months, xp, bosses, logic.equals("OR")));
					}
				}
				catch (NumberFormatException e)
				{
					error = "a number doesn't parse";
				}
			}

			if (error != null)
			{
				errors.add("Line " + (i + 1) + " (" + line + "): " + error);
			}
		}

		return new RankRules(ranks, errors);
	}

	List<Rank> getRanks()
	{
		return ranks;
	}

	List<String> getErrors()
	{
		return errors;
	}

	/** Whether promoting from this rank needs boss kills, so the member needs a full lookup rather than the group total. */
	boolean nextNeedsBosses(String currentRank)
	{
		int index = indexOf(currentRank);
		return index != -1 && index + 1 < ranks.size() && ranks.get(index + 1).getBosses() > 0;
	}

	private int indexOf(String rank)
	{
		if (rank != null)
		{
			for (int i = 0; i < ranks.size(); i++)
			{
				if (ranks.get(i).getName().equalsIgnoreCase(rank.trim()))
				{
					return i;
				}
			}
		}
		return -1;
	}

	/**
	 * Whole months between the start date (join date or last rank change) and today, one fewer if this month's join day hasn't arrived yet.
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
		int requiredBosses;
	}

	/**
	 * Returns the rank the member should be promoted to, or null if they don't qualify yet,
	 * are already at the top rank, hold a rank that isn't in the table, or have no XP data.
	 */
	String nextRankFor(String currentRank, LocalDate since, Long xpGained, LocalDate today)
	{
		Evaluation evaluation = evaluate(currentRank, since, xpGained, null, today);
		return evaluation.getStatus() == PromotionStatus.READY ? evaluation.getNextRank() : null;
	}

	/**
	 * Works out whether a member is due a promotion and, if not, why not. {@code since} is when their
	 * window started, and the XP and boss kills are counted from then; null boss kills means unknown.
	 */
	Evaluation evaluate(String currentRank, LocalDate since, Long xpGained, Long bossKills, LocalDate today)
	{
		int months = since == null ? 0 : monthsInClan(since, today);

		int currentIndex = indexOf(currentRank);
		if (currentIndex == -1)
		{
			return new Evaluation(PromotionStatus.UNKNOWN_RANK, null, months, 0, 0, 0);
		}

		if (currentIndex + 1 >= ranks.size())
		{
			return new Evaluation(PromotionStatus.MAX_RANK, null, months, 0, 0, 0);
		}

		Rank next = ranks.get(currentIndex + 1);
		Evaluation noData = new Evaluation(PromotionStatus.NO_XP, next.getName(), months, next.getMonths(),
			next.getXpMillions(), next.getBosses());
		if (since == null || xpGained == null)
		{
			return noData;
		}

		boolean needXp = next.getXpMillions() > 0;
		boolean needBosses = next.getBosses() > 0;
		boolean xpMet = xpGained / 1_000_000.0 >= next.getXpMillions();
		Boolean bossesMet = !needBosses ? Boolean.TRUE : bossKills == null ? null : bossKills >= next.getBosses();

		Boolean activity;
		if (next.isEither() && needXp && needBosses)
		{
			// Enough XP decides it even when the boss kills are unknown
			activity = xpMet ? Boolean.TRUE : bossesMet;
		}
		else
		{
			activity = !xpMet ? Boolean.FALSE : bossesMet;
		}

		// A failed or missing boss lookup is never treated as 0 kills
		if (activity == null)
		{
			return noData;
		}

		boolean ready = months >= next.getMonths() && activity;
		return new Evaluation(ready ? PromotionStatus.READY : PromotionStatus.NOT_READY,
			next.getName(), months, next.getMonths(), next.getXpMillions(), next.getBosses());
	}
}
