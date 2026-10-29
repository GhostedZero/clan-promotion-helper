package com.clanpromotionhelper;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(ClanPromotionHelperConfig.GROUP)
public interface ClanPromotionHelperConfig extends Config
{
	String GROUP = "clanpromotionhelper";
	String THIRD_PARTY_WARNING = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers";

	@ConfigSection(
		name = "Wise Old Man",
		description = "Where XP gained since joining comes from",
		position = 0
	)
	String wiseOldManSection = "wiseOldMan";

	@ConfigSection(
		name = "Rank requirements",
		description = "Months in the clan and XP needed for each rank",
		position = 1,
		closedByDefault = true
	)
	String ranksSection = "ranks";

	@ConfigSection(
		name = "Display",
		description = "How promotions are shown",
		position = 2
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "enableWiseOldMan",
		name = "Look up XP on Wise Old Man",
		description = "Fetch each clan member's XP gained since joining from wiseoldman.net. This sends your clan members' names and join dates to Wise Old Man, and nothing else is sent anywhere. Promotions can't be worked out without it.",
		warning = THIRD_PARTY_WARNING,
		section = wiseOldManSection,
		position = 0
	)
	default boolean enableWiseOldMan()
	{
		return false;
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "Optional Wise Old Man API key. Raises the limit from 20 to 100 lookups a minute. Ask for one on the Wise Old Man Discord.",
		secret = true,
		section = wiseOldManSection,
		position = 1
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "contact",
		name = "Contact name",
		description = "Your Discord name. Sent with each lookup so Wise Old Man can reach you about your usage.",
		section = wiseOldManSection,
		position = 2
	)
	default String contact()
	{
		return "";
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "womGroupId",
		name = "Wise Old Man group ID",
		description = "Optional. If your clan has a Wise Old Man group, enter its ID (the number at the end of its web address). "
			+ "Refreshes then need one request instead of one per member. Leave at 0 to look each member up individually.",
		section = wiseOldManSection,
		position = 3
	)
	default int womGroupId()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "rankRequirements",
		name = "Rank requirements",
		description = "One rank per line, lowest first, as Rank:months:XP in millions. Rank names must match your clan's in-game rank titles.",
		section = ranksSection,
		position = 0
	)
	default String rankRequirements()
	{
		return RankRules.DEFAULT_TABLE;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show promotions panel",
		description = "Show a panel listing members who are due a promotion",
		section = displaySection,
		position = 0
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightMemberList",
		name = "Highlight in member list",
		description = "Highlight members who are due a promotion in the Clan Settings member list",
		section = displaySection,
		position = 1
	)
	default boolean highlightMemberList()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightColor",
		name = "Highlight colour",
		description = "Colour used for promotions in the panel and member list",
		section = displaySection,
		position = 2
	)
	default Color highlightColor()
	{
		return new Color(255, 200, 0);
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "maxDisplayed",
		name = "Max shown in panel",
		description = "Most promotions to list in the panel (0 for all)",
		section = displaySection,
		position = 3
	)
	default int maxDisplayed()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "ignoredPlayers",
		name = "Ignored players",
		description = "Comma-separated names to never suggest for promotion",
		section = displaySection,
		position = 4
	)
	default String ignoredPlayers()
	{
		return "";
	}

	@ConfigItem(
		keyName = "ignoredTargetRanks",
		name = "Ignored target ranks",
		description = "Comma-separated ranks to never suggest promoting to",
		section = displaySection,
		position = 5
	)
	default String ignoredTargetRanks()
	{
		return "";
	}
}
