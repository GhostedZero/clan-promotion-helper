package com.clanpromotionhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.api.Skill;

/**
 * Vanity ranks: side ranks a member can qualify for from anywhere on the ladder, on lifetime stats rather
 * than gains. One rank per line as {@code Name: condition; condition; ...}, every condition needed:
 * <ul>
 *     <li>{@code rank Sergeant or 6 months}: holds that rank or higher (by in-game rank order), or has been in
 *     the clan that long; either half can be left out</li>
 *     <li>{@code level slayer 99}, {@code level all 99}, {@code level all except sailing 99}</li>
 *     <li>{@code kills all 12500}, {@code kills raids 50}, {@code kills tzkal_zuk, sol_heredit 1}: total
 *     kill count over those bosses, using Wise Old Man's boss names</li>
 * </ul>
 */
final class VanityRules
{
	/** Every raid mode on the hiscores (Theatre of Blood entry mode isn't one) */
	static final List<String> RAIDS = Collections.unmodifiableList(Arrays.asList(
		"chambers_of_xeric", "chambers_of_xeric_challenge_mode", "theatre_of_blood", "theatre_of_blood_hard_mode",
		"tombs_of_amascut", "tombs_of_amascut_expert"));

	/** Wise Old Man's skill names, which match RuneLite's except Runecraft */
	static final List<String> SKILLS;

	static
	{
		List<String> skills = new ArrayList<>();
		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL)
			{
				skills.add(skill == Skill.RUNECRAFT ? "runecrafting" : skill.getName().toLowerCase(Locale.ROOT));
			}
		}
		SKILLS = Collections.unmodifiableList(skills);
	}

	private static final Pattern RANK_OR_TIME = Pattern.compile("(?i)rank\\s+(.+?)\\s+or\\s+(\\d+)\\s+months?");
	private static final Pattern RANK_ONLY = Pattern.compile("(?i)rank\\s+(.+)");
	private static final Pattern TIME_ONLY = Pattern.compile("(?i)(\\d+)\\s+months?");
	private static final Pattern LEVEL = Pattern.compile("(?i)level\\s+(.+?)\\s+(\\d+)");
	private static final Pattern KILLS = Pattern.compile("(?i)kills\\s+(.+?)\\s+(\\d+)");

	enum Kind
	{
		RANK_OR_TIME,
		LEVEL,
		KILLS
	}

	@Value
	static class Condition
	{
		Kind kind;
		/** RANK_OR_TIME: the rank title, or null for time only */
		String rank;
		/** RANK_OR_TIME: months in the clan, or 0 for rank only */
		int months;
		/** LEVEL: one skill, or every skill except {@link #except} when empty. KILLS: boss names, or "all"/"raids". */
		List<String> names;
		List<String> except;
		/** LEVEL: the level needed. KILLS: the total kill count needed. */
		long value;

		String toText()
		{
			switch (kind)
			{
				case RANK_OR_TIME:
					return rank == null ? months + " months" : months == 0 ? "rank " + rank : "rank " + rank + " or " + months + " months";
				case LEVEL:
					return "level " + (names.isEmpty() ? "all" + (except.isEmpty() ? "" : " except " + String.join(", ", except)) : names.get(0)) + " " + value;
				default:
					return "kills " + String.join(", ", names) + " " + value;
			}
		}
	}

	@Value
	static class Rule
	{
		String name;
		List<Condition> conditions;

		String toText()
		{
			List<String> parts = new ArrayList<>();
			for (Condition condition : conditions)
			{
				parts.add(condition.toText());
			}
			return name + ": " + String.join("; ", parts);
		}
	}

	/** What the conditions are checked against. Levels and kills are Wise Old Man's latest values, by its names. */
	@Value
	static class Stats
	{
		/** The member's in-game rank number now, and the highest one the plugin has seen them hold */
		int rank;
		int peakRank;
		int monthsInClan;
		Map<String, Integer> levels;
		/** Only bosses with kills; anything missing counts as 0 */
		Map<String, Long> kills;
	}

	private final List<Rule> rules;
	private final List<String> errors;

	private VanityRules(List<Rule> rules, List<String> errors)
	{
		this.rules = Collections.unmodifiableList(rules);
		this.errors = Collections.unmodifiableList(errors);
	}

	static VanityRules parse(String text)
	{
		List<Rule> rules = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		if (text == null)
		{
			return new VanityRules(rules, errors);
		}

		String[] lines = text.split("\\r?\\n");
		for (int i = 0; i < lines.length; i++)
		{
			String line = lines[i].trim();
			if (line.isEmpty())
			{
				continue;
			}

			int colon = line.indexOf(':');
			String error = null;
			List<Condition> conditions = new ArrayList<>();
			if (colon <= 0)
			{
				error = "expected Name: condition; condition";
			}
			else
			{
				for (String part : line.substring(colon + 1).split(";"))
				{
					if (part.trim().isEmpty())
					{
						continue;
					}
					Condition condition = parseCondition(part.trim());
					if (condition == null)
					{
						error = "can't read \"" + part.trim() + "\"";
						break;
					}
					conditions.add(condition);
				}
				if (error == null && conditions.isEmpty())
				{
					error = "needs at least one condition";
				}
			}

			if (error == null)
			{
				rules.add(new Rule(line.substring(0, colon).trim(), Collections.unmodifiableList(conditions)));
			}
			else
			{
				errors.add("Vanity line " + (i + 1) + " (" + line + "): " + error);
			}
		}
		return new VanityRules(rules, errors);
	}

	/** Returns null for anything that isn't a valid condition, including unknown skill names and oversized numbers. */
	static Condition parseCondition(String text)
	{
		try
		{
			return parseConditionOrThrow(text);
		}
		catch (NumberFormatException e)
		{
			// The patterns accept any number of digits, so a typo can overflow an int or long
			return null;
		}
	}

	private static Condition parseConditionOrThrow(String text)
	{
		Matcher m;
		if ((m = RANK_OR_TIME.matcher(text)).matches())
		{
			return new Condition(Kind.RANK_OR_TIME, m.group(1).trim(), Integer.parseInt(m.group(2)), list(), list(), 0);
		}
		if ((m = TIME_ONLY.matcher(text)).matches())
		{
			return new Condition(Kind.RANK_OR_TIME, null, Integer.parseInt(m.group(1)), list(), list(), 0);
		}
		if ((m = RANK_ONLY.matcher(text)).matches())
		{
			return new Condition(Kind.RANK_OR_TIME, m.group(1).trim(), 0, list(), list(), 0);
		}
		if ((m = LEVEL.matcher(text)).matches())
		{
			String skills = m.group(1).trim();
			long level = Long.parseLong(m.group(2));
			if (skills.equalsIgnoreCase("all"))
			{
				return new Condition(Kind.LEVEL, null, 0, list(), list(), level);
			}
			if (skills.toLowerCase(Locale.ROOT).startsWith("all except "))
			{
				List<String> except = names(skills.substring("all except ".length()));
				return SKILLS.containsAll(except) ? new Condition(Kind.LEVEL, null, 0, list(), except, level) : null;
			}
			String skill = name(skills);
			skill = skill.equals("runecraft") ? "runecrafting" : skill;
			return SKILLS.contains(skill) ? new Condition(Kind.LEVEL, null, 0, list(skill), list(), level) : null;
		}
		if ((m = KILLS.matcher(text)).matches())
		{
			List<String> bosses = names(m.group(1));
			return bosses.isEmpty() ? null : new Condition(Kind.KILLS, null, 0, bosses, list(), Long.parseLong(m.group(2)));
		}
		return null;
	}

	/** Turns "TzKal-Zuk" or "Phosani's Nightmare" into Wise Old Man's "tzkal_zuk" and "phosanis_nightmare". */
	static String name(String text)
	{
		return text.trim().toLowerCase(Locale.ROOT).replace("'", "").replaceAll("[\\s-]+", "_");
	}

	private static List<String> names(String text)
	{
		Set<String> names = new LinkedHashSet<>();
		for (String part : text.split(","))
		{
			if (!part.trim().isEmpty())
			{
				names.add(name(part));
			}
		}
		return Collections.unmodifiableList(new ArrayList<>(names));
	}

	private static List<String> list(String... values)
	{
		return Collections.unmodifiableList(Arrays.asList(values));
	}

	List<Rule> getRules()
	{
		return rules;
	}

	/** A message if the condition names a rank title that isn't in {@code knownTitles} (lower case), else null. */
	static String unknownRank(Condition condition, Set<String> knownTitles)
	{
		return condition.getKind() == Kind.RANK_OR_TIME && condition.getRank() != null
			&& !knownTitles.contains(condition.getRank().toLowerCase(Locale.ROOT))
			? "no rank called \"" + condition.getRank() + "\"" : null;
	}

	/** One message per condition naming a rank the clan doesn't have; none until the clan's titles are known. */
	List<String> unknownRanks(Set<String> knownTitles)
	{
		List<String> messages = new ArrayList<>();
		if (knownTitles.isEmpty())
		{
			return messages;
		}
		for (Rule rule : rules)
		{
			for (Condition condition : rule.getConditions())
			{
				String message = unknownRank(condition, knownTitles);
				if (message != null)
				{
					messages.add(rule.getName() + ": " + message + " (it never matches)");
				}
			}
		}
		return messages;
	}

	List<String> getErrors()
	{
		return errors;
	}

	/** Whether there's a vanity rank this member doesn't hold yet, so they need levels and kill counts looked up. */
	boolean appliesTo(String currentRank)
	{
		for (Rule rule : rules)
		{
			if (!rule.getName().equalsIgnoreCase(currentRank == null ? "" : currentRank.trim()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The vanity ranks the member qualifies for and doesn't already hold, in table order.
	 * {@code rankNumbers} maps lower-case rank titles to their in-game rank numbers.
	 */
	List<String> qualified(String currentRank, Stats stats, Map<String, Integer> rankNumbers)
	{
		List<String> met = new ArrayList<>();
		for (Rule rule : rules)
		{
			if (rule.getName().equalsIgnoreCase(currentRank == null ? "" : currentRank.trim()))
			{
				continue;
			}

			boolean all = true;
			for (Condition condition : rule.getConditions())
			{
				if (!meets(condition, stats, rankNumbers))
				{
					all = false;
					break;
				}
			}
			if (all)
			{
				met.add(rule.getName());
			}
		}
		return met;
	}

	static boolean meets(Condition condition, Stats stats, Map<String, Integer> rankNumbers)
	{
		switch (condition.getKind())
		{
			case RANK_OR_TIME:
			{
				Integer needed = condition.getRank() == null ? null : rankNumbers.get(condition.getRank().toLowerCase(Locale.ROOT));
				boolean rankMet = needed != null && Math.max(stats.getRank(), stats.getPeakRank()) >= needed;
				boolean timeMet = condition.getMonths() > 0 && stats.getMonthsInClan() >= condition.getMonths();
				// A blank half isn't part of the rule; an unknown rank title never matches
				return rankMet || timeMet || (condition.getRank() == null && condition.getMonths() == 0);
			}
			case LEVEL:
			{
				List<String> skills = condition.getNames().isEmpty() ? SKILLS : condition.getNames();
				for (String skill : skills)
				{
					if (condition.getExcept().contains(skill))
					{
						continue;
					}
					Integer level = stats.getLevels().get(skill);
					if (level == null || level < condition.getValue())
					{
						return false;
					}
				}
				return true;
			}
			default:
			{
				long total = 0;
				if (condition.getNames().contains("all"))
				{
					for (long kills : stats.getKills().values())
					{
						total += kills;
					}
				}
				else
				{
					Set<String> bosses = new LinkedHashSet<>();
					for (String name : condition.getNames())
					{
						bosses.addAll(name.equals("raids") ? RAIDS : Collections.singletonList(name));
					}
					for (String boss : bosses)
					{
						total += stats.getKills().getOrDefault(boss, 0L);
					}
				}
				return total >= condition.getValue();
			}
		}
	}
}
